package org.team100.lib.localization;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;
import java.util.function.ObjDoubleConsumer;

import org.team100.lib.config.Camera;
import org.team100.lib.copies.SwerveDrivePoseEstimator100;
import org.team100.lib.dashboard.Glassy;
import org.team100.lib.experiments.Experiment;
import org.team100.lib.experiments.Experiments;
import org.team100.lib.geometry.GeometryUtil;
import org.team100.lib.telemetry.Telemetry;
import org.team100.lib.telemetry.Telemetry.Level;
import org.team100.lib.util.Names;
import org.team100.lib.util.Util;

import edu.wpi.first.cscore.CameraServerCvJNI;
import edu.wpi.first.math.Matrix;
import edu.wpi.first.math.VecBuilder;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Rotation3d;
import edu.wpi.first.math.geometry.Transform3d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.numbers.N1;
import edu.wpi.first.math.numbers.N3;
import edu.wpi.first.networktables.NetworkTableEvent;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.NetworkTableValue;
import edu.wpi.first.networktables.ValueEventData;
import edu.wpi.first.util.struct.StructBuffer;
import edu.wpi.first.wpilibj.DriverStation.Alliance;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.Timer;

/**
 * Extracts robot pose estimates from camera input.
 * 
 * This "24" version uses the "struct" method instead of the "msgpack" method,
 * which matches the TagFinder24 code on the camera.
 */
public class VisionDataProvider24 implements Glassy {
    /**
     * Standard deviation of pose estimate, as a fraction of target range.
     * This is a guess based on figure 5 in the Apriltag2 paper:
     * https://april.eecs.umich.edu/media/media/pdfs/wang2016iros.pdf
     * The error is much worse at very long range but I don't think that
     * matters for us.
     */
    private static final double kRelativeError = 0.1;
    /**
     * Time between events in reality and their appearance here; the average
     * end-to-end latency of the camera, detection code, network tables, and rio
     * looping.
     * 
     * Note this latency varies a little bit, depending on the relative timing of
     * the camera frame and the rio loop.
     * 
     * TODO: use the correct timing instead of this average.
     */
    private static final double kTotalLatencySeconds = 0.075;
    /**
     * If the tag is closer than this threshold, then the camera's estimate of tag
     * rotation might be more accurate than the gyro, so we use the camera's
     * estimate of tag rotation to update the robot pose. If the tag is further away
     * than this, then the camera-derived rotation is probably less accurate than
     * the gyro, so we use the gyro instead.
     * 
     * Set this to zero to disable tag-derived rotation and always use the gyro.
     * 
     * Set this to some large number (e.g. 100) to disable gyro-derived rotation and
     * always use the camera.
     */
    private static final double kTagRotationBeliefThresholdMeters = 0;
    /** Discard results further than this from the previous one. */
    private static final double kVisionChangeToleranceMeters = 0.1;
    // private static final double kVisionChangeToleranceMeters = 1;

    private final Telemetry t = Telemetry.get();

    private final DoubleFunction<Optional<Rotation2d>> rotationSupplier;

    private final SwerveDrivePoseEstimator100 poseEstimator;
    private final AprilTagFieldLayoutWithCorrectOrientation layout;
    private final String m_name;

    // for blip filtering
    private Pose2d lastRobotInFieldCoords;

    // reuse the buffer since it takes some time to make
    private StructBuffer<Blip24> m_buf = StructBuffer.create(Blip24.struct);

    /**
     * @param layout
     * @param poseEstimator    can be null for testing.
     * @param rotationSupplier rotation for the given time in seconds
     * @throws IOException
     */
    public VisionDataProvider24(
            AprilTagFieldLayoutWithCorrectOrientation layout,
            SwerveDrivePoseEstimator100 poseEstimator,
            DoubleFunction<Optional<Rotation2d>> rotationSupplier) throws IOException {
        // load the JNI (used by PoseEstimationHelper)
        CameraServerCvJNI.forceLoad();
        this.layout = layout;
        this.poseEstimator = poseEstimator;
        this.rotationSupplier = rotationSupplier;
        m_name = Names.name(this);
    }

    /** Start listening for updates. */
    public void enable() {
        NetworkTableInstance.getDefault().addListener(
                new String[] { "vision" },
                EnumSet.of(NetworkTableEvent.Kind.kValueAll),
                this::accept);
    }

    /**
     * Accept a NetworkTableEvent and convert it to a Blips object
     * 
     * @param event the event to accept
     */

    public void accept(NetworkTableEvent e) {
        ValueEventData ve = e.valueData;
        NetworkTableValue v = ve.value;
        String name = ve.getTopic().getName();
        String[] fields = name.split("/");
        if (fields.length != 3)
            return;
        if (fields[2].equals("fps")) {
            // FPS is not used by the robot
        } else if (fields[2].equals("latency")) {
            // latency is not used by the robot
        } else if (fields[2].equals("blips")) {
            // decode the way StructArrayEntryImpl does
            byte[] b = v.getRaw();
            if (b.length == 0)
                return;
            Blip24[] blips;
            try {
                synchronized (m_buf) {
                    blips = m_buf.readArray(b);
                }
            } catch (RuntimeException ex) {
                return;
            }
            // the ID of the camera
            String cameraSerialNumber = fields[1];

            Optional<Alliance> alliance = DriverStation.getAlliance();
            if (!alliance.isPresent())
                return;

            // TODO: add a real firing solution consumer.
            estimateRobotPose(
                    poseEstimator::addVisionMeasurement,
                    f -> {
                    },
                    cameraSerialNumber,
                    blips,
                    alliance.get());
        } else {
            // this event is not for us
            // Util.println("weird vision update key: " + name);
        }
    }

    /**
     * @param estimateConsumer   is the pose estimator but exposing it here makes it
     *                           easier to test.
     * @param cameraSerialNumber the camera identity, obtained from proc/cpuinfo
     * @param blips              all the targets the camera sees right now
     */
    void estimateRobotPose(
            final ObjDoubleConsumer<Pose2d> estimateConsumer,
            Consumer<Translation2d> firingSolutionConsumer,
            String cameraSerialNumber,
            final Blip24[] blips,
            Alliance alliance) {
        final Transform3d cameraInRobotCoordinates = Camera.get(cameraSerialNumber).getOffset();

        // Estimated instant represented by the blips
        final double frameTime = Timer.getFPGATimestamp() - kTotalLatencySeconds;
        Optional<Rotation2d> optionalGyroRotation = rotationSupplier.apply(frameTime);

        if (optionalGyroRotation.isEmpty()) {
            Util.warn("No gyro rotation available!");
            return;
        }

        final Rotation2d gyroRotation = optionalGyroRotation.get();

        estimateFromBlips(
                estimateConsumer,
                cameraSerialNumber,
                blips,
                cameraInRobotCoordinates,
                frameTime,
                gyroRotation,
                alliance);

        if (Experiments.instance.enabled(Experiment.Triangulate)) {
            triangulate(
                    estimateConsumer,
                    cameraSerialNumber,
                    blips,
                    cameraInRobotCoordinates,
                    frameTime,
                    gyroRotation,
                    alliance);
        }

        firingSolution(
                firingSolutionConsumer,
                cameraSerialNumber,
                blips,
                cameraInRobotCoordinates,
                alliance);

    }

    /**
     * a firing solution is a robot-relative translation2d to the correct target --
     * 7 if we're blue, 5 if we're red.
     * 
     * @param firingSolutionConsumer
     * @param blips
     * @param cameraInRobotCoordinates
     */
    private void firingSolution(
            Consumer<Translation2d> firingSolutionConsumer,
            final String cameraSerialNumber,
            final Blip24[] blips,
            final Transform3d cameraInRobotCoordinates,
            Alliance alliance) {
        for (Blip24 blip : blips) {
            if ((blip.getId() == 7 && alliance == Alliance.Blue) ||
                    (blip.getId() == 5 && alliance == Alliance.Red)) {
                Translation2d translation2d = PoseEstimationHelper.toTarget(cameraInRobotCoordinates, blip)
                        .getTranslation().toTranslation2d();
                t.log(Level.DEBUG, m_name, cameraSerialNumber + "/Firing Solution", translation2d);
                if (Experiments.instance.enabled(Experiment.HeedVision)) {
                    double distance = translation2d.getNorm();
                    if (poseEstimator != null)
                        poseEstimator.setVisionMeasurementStdDevs(visionMeasurementStdDevs(distance));
                    firingSolutionConsumer.accept(translation2d);
                }
            }
        }
    }

    private void estimateFromBlips(
            final ObjDoubleConsumer<Pose2d> estimateConsumer,
            final String cameraSerialNumber,
            final Blip24[] blips,
            final Transform3d cameraInRobotCoordinates,
            final double frameTime,
            final Rotation2d gyroRotation,
            Alliance alliance) {
        for (Blip24 blip : blips) {

            // this is just for logging
            Rotation3d tagRotation = PoseEstimationHelper.blipToRotation(blip);
            t.log(Level.DEBUG, m_name, cameraSerialNumber + "/Blip Tag Rotation", tagRotation.getAngle());

            Optional<Pose3d> tagInFieldCoordsOptional = layout.getTagPose(alliance, blip.getId());
            if (!tagInFieldCoordsOptional.isPresent())
                continue;

            if(blip.getPose().getTranslation().getNorm() > 4.5){
                return;
            }

            // Gyro only produces yaw so use zero roll and zero pitch
            Rotation3d robotRotationInFieldCoordsFromGyro = new Rotation3d(
                    0, 0, gyroRotation.getRadians());

            Pose3d tagInFieldCoords = tagInFieldCoordsOptional.get();
            t.log(Level.DEBUG, m_name, cameraSerialNumber + "/Blip Tag In Field Cords", tagInFieldCoords.toPose2d());

            Pose3d robotPoseInFieldCoords = PoseEstimationHelper.getRobotPoseInFieldCoords(
                    cameraInRobotCoordinates,
                    tagInFieldCoords,
                    blip,
                    robotRotationInFieldCoordsFromGyro,
                    kTagRotationBeliefThresholdMeters);

            Translation2d robotTranslationInFieldCoords = robotPoseInFieldCoords.getTranslation().toTranslation2d();

            Pose2d currentRobotinFieldCoords = new Pose2d(robotTranslationInFieldCoords, gyroRotation);

            t.log(Level.DEBUG, m_name, cameraSerialNumber + "/Blip Pose", currentRobotinFieldCoords);

            if (lastRobotInFieldCoords != null) {
                double distanceM = GeometryUtil.distance(lastRobotInFieldCoords, currentRobotinFieldCoords);
                if (distanceM <= kVisionChangeToleranceMeters) {
                    // this hard limit excludes false positives, which were a bigger problem in 2023
                    // due to the coarse tag family used. in 2024 this might not be an issue.
                    if (Experiments.instance.enabled(Experiment.HeedVision)) {
                        if (poseEstimator != null)
                            poseEstimator.setVisionMeasurementStdDevs(visionMeasurementStdDevs(distanceM));
                        estimateConsumer.accept(currentRobotinFieldCoords, frameTime);
                    }
                } else {
                    // System.out.println("IGNORE " + currentRobotinFieldCoords);
                    // System.out.println("previous " + lastRobotInFieldCoords);
                    // System.out.println("blip " + blip);
                    // System.out.println("distance " + distanceM);
                }
            }

            lastRobotInFieldCoords = currentRobotinFieldCoords;

        }
    }

    private void triangulate(
            ObjDoubleConsumer<Pose2d> estimateConsumer,
            final String cameraSerialNumber,
            Blip24[] blips,
            Transform3d cameraInRobotCoordinates,
            double frameTime,
            Rotation2d gyroRotation,
            Alliance alliance) {
        // if multiple tags are in view, triangulate to get another (perhaps more
        // accurate) estimate
        for (int i = 0; i < blips.length - 1; i++) {
            Blip24 b0 = blips[i];
            for (int j = i + 1; j < blips.length; ++j) {
                Blip24 b1 = blips[j];

                Optional<Pose3d> tagInFieldCordsOptional0 = layout.getTagPose(alliance, b0.getId());
                Optional<Pose3d> tagInFieldCordsOptional1 = layout.getTagPose(alliance, b1.getId());

                if (!tagInFieldCordsOptional0.isPresent())
                    continue;
                if (!tagInFieldCordsOptional1.isPresent())
                    continue;

                Translation2d T0 = tagInFieldCordsOptional0.get().getTranslation().toTranslation2d();
                Translation2d T1 = tagInFieldCordsOptional1.get().getTranslation().toTranslation2d();

                // in camera frame
                Transform3d t0 = PoseEstimationHelper.blipToTransform(b0);
                Transform3d t1 = PoseEstimationHelper.blipToTransform(b1);

                // in robot frame
                Transform3d rf0 = t0.plus(cameraInRobotCoordinates);
                Transform3d rf1 = t1.plus(cameraInRobotCoordinates);

                // in 2d
                Translation2d tr0 = rf0.getTranslation().toTranslation2d();
                Translation2d tr1 = rf1.getTranslation().toTranslation2d();

                // rotations
                Rotation2d r0 = tr0.getAngle();
                Rotation2d r1 = tr1.getAngle();

                Translation2d X = TriangulationHelper.solve(T0, T1, r0, r1);
                Pose2d currentRobotinFieldCoords = new Pose2d(X, gyroRotation);

                t.log(Level.DEBUG, m_name, cameraSerialNumber + "/Triangulate Pose", currentRobotinFieldCoords);

                if (lastRobotInFieldCoords != null) {
                    double distanceM = GeometryUtil.distance(lastRobotInFieldCoords, currentRobotinFieldCoords);
                    if (distanceM <= kVisionChangeToleranceMeters) {
                        // this hard limit excludes false positives, which were a bigger problem in 2023
                        // due to the coarse tag family used. in 2024 this might not be an issue.
                        if (Experiments.instance.enabled(Experiment.HeedVision)) {
                            if (poseEstimator != null)
                                poseEstimator.setVisionMeasurementStdDevs(visionMeasurementStdDevs(distanceM));
                            estimateConsumer.accept(currentRobotinFieldCoords, frameTime);
                        }
                    } else {
                        // System.out.println("triangulation too far");
                        // System.out.println("IGNORE " + currentRobotinFieldCoords);
                        // System.out.println("previous " + lastRobotInFieldCoords);
                        // System.out.println("distance " + distanceM);
                    }
                }
                lastRobotInFieldCoords = currentRobotinFieldCoords;
            }
        }
    }

    /** This is an educated guess. */
    static Matrix<N3, N1> visionMeasurementStdDevs(double distanceM) {
        double stddev = kRelativeError * distanceM;
        return VecBuilder.fill(stddev, stddev, Double.MAX_VALUE);
    }

    @Override
    public String getGlassName() {
        return "VisionDataProvider24";
    }

}
