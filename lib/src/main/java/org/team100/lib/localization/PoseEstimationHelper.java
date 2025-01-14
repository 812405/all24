package org.team100.lib.localization;

import java.util.ArrayList;
import java.util.List;

import org.team100.lib.geometry.GeometryUtil;
import org.team100.lib.telemetry.Telemetry;
import org.team100.lib.telemetry.Telemetry.Level;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Rotation3d;
import edu.wpi.first.math.geometry.Transform2d;
import edu.wpi.first.math.geometry.Transform3d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.geometry.Translation3d;

/**
 * Static methods used to interpret camera input.
 */
public class PoseEstimationHelper {
    private static final Telemetry t = Telemetry.get();
    private static final String kName = PoseEstimationHelper.class.getSimpleName();

    /**
     * Converts camera rotation to an object to a robot relative translation,
     * accounts for roll, pitch, and yaw offsets by using the unit circle. For roll,
     * it takes the the angle between the pitch and yaw (roll) to the object and
     * adds the offseted roll, then gets the Cos (x in unit circle) and multiples it
     * by the norm, it does the same for yaw
     */
    public static Translation2d cameraRotationToRobotRelative(Transform3d cameraInRobotCoordinates,
            Rotation3d yawPitch) {
        Rotation2d angleNoRollOffset = new Rotation2d(yawPitch.getZ(), yawPitch.getY());
        Rotation2d angleWRoll = new Rotation2d(cameraInRobotCoordinates.getRotation().getX()).plus(angleNoRollOffset);
        double normInCamera = Math.hypot(yawPitch.getZ(), yawPitch.getY());
        Rotation3d rotToObject = new Rotation3d(0, angleWRoll.getSin() * normInCamera,
                angleWRoll.getCos() * normInCamera);
        double robotRelativeAngle = (cameraInRobotCoordinates.getRotation().getY() + rotToObject.getY());
        if (robotRelativeAngle == 0) {
            return new Translation2d();
        }
        double x = cameraInRobotCoordinates.getZ() / Math.tan(robotRelativeAngle);
        double y = x * Math.tan(rotToObject.getZ());
        Rotation2d cameraRelativeTranslation = new Rotation2d(x, y);
        double znorm = Math.hypot(x, y);
        Rotation2d angleToObject = new Rotation2d(cameraInRobotCoordinates.getRotation().getZ())
                .plus(cameraRelativeTranslation);
        Translation2d robotRelativeTranslation2dNoOffsets = new Translation2d(
                angleToObject.getCos() * znorm,
                angleToObject.getSin() * znorm);
        return robotRelativeTranslation2dNoOffsets.plus(cameraInRobotCoordinates.getTranslation().toTranslation2d());
    }

    /**
     * Converts camera rotation to objects into field relative translations
     */
    public static List<Translation2d> cameraRotsToFieldRelative(Pose2d currentPose,
            Transform3d cameraInRobotCoordinates, List<Rotation3d> rots) {
        ArrayList<Translation2d> Tnotes = new ArrayList<>();
        for (Rotation3d note : rots)
            if (note.getY() < cameraInRobotCoordinates.getRotation().getY()) {
                Translation2d cameraRotationRobotRelative = PoseEstimationHelper.cameraRotationToRobotRelative(
                        cameraInRobotCoordinates,
                        note);
                Translation2d fieldRealtiveTranslation = currentPose
                        .transformBy(new Transform2d(cameraRotationRobotRelative, new Rotation2d()))
                        .getTranslation();
                        if (fieldRealtiveTranslation.getY() > 0 && fieldRealtiveTranslation.getX() > 0) {
                            if (fieldRealtiveTranslation.getY() < 8.21 && fieldRealtiveTranslation.getX() < 16.54) {
                                Tnotes.add(fieldRealtiveTranslation);
                            }
                        }
            }

        return Tnotes;
    }

    /**
     * Converts camera rotation to objects into field relative translations
     */
    public static List<Translation2d> cameraRotsToFieldRelativeArray(Pose2d currentPose,
            Transform3d cameraInRobotCoordinates, Rotation3d[] rots) {
        ArrayList<Translation2d> Tnotes = new ArrayList<>();
        for (Rotation3d note : rots)
            if (note.getY() < cameraInRobotCoordinates.getRotation().getY()) {
                Translation2d cameraRotationRobotRelative = PoseEstimationHelper.cameraRotationToRobotRelative(
                        cameraInRobotCoordinates,
                        note);
                Translation2d fieldRelativeNote = currentPose
                        .transformBy(new Transform2d(cameraRotationRobotRelative, new Rotation2d()))
                        .getTranslation();
                        if (fieldRelativeNote.getX() > 0 && fieldRelativeNote.getY() > 0) {
                            if (fieldRelativeNote.getX() < 16.54 && fieldRelativeNote.getY() < 8.21) {
                Tnotes.add(fieldRelativeNote);
                            }
                        }
            }
        return Tnotes;
    }

    /**
     * Calculate robot pose.
     * 
     * First calculates the distance to the tag. If it's closer than the threshold,
     * use the camera-derived tag rotation. If it's far, use the gyro.
     */
    public static Pose3d getRobotPoseInFieldCoords(
            Transform3d cameraInRobotCoords,
            Pose3d tagInFieldCoords,
            Blip24 blip,
            Rotation3d robotRotationInFieldCoordsFromGyro,
            double thresholdMeters) {

        Translation3d tagTranslationInCameraCoords = blipToTranslation(blip);

        if (tagTranslationInCameraCoords.getNorm() < thresholdMeters) {
            t.log(Level.DEBUG, kName, "rotation_source", "CAMERA");
            return getRobotPoseInFieldCoords(
                    cameraInRobotCoords,
                    tagInFieldCoords,
                    blip);
        }

        t.log(Level.DEBUG, kName, "rotation_source", "GYRO");

        return getRobotPoseInFieldCoords(
                cameraInRobotCoords,
                tagInFieldCoords,
                blip,
                robotRotationInFieldCoordsFromGyro);
    }

    /**
     * Calculate robot pose.
     * 
     * Given the blip and its corresponding field location, and the camera offset,
     * return the robot pose in field coordinates.
     * 
     * This method trusts the tag rotation calculated by the camera.
     */
    public static Pose3d getRobotPoseInFieldCoords(
            Transform3d cameraInRobotCoords,
            Pose3d tagInFieldCoords,
            Blip24 blip) {

        Transform3d tagInCameraCoords = blipToTransform(blip);
        Pose3d cameraInFieldCoords = toFieldCoordinates(tagInCameraCoords, tagInFieldCoords);
        return applyCameraOffset(cameraInFieldCoords, cameraInRobotCoords);
    }

    /**
     * Calculate robot pose.
     * 
     * Given the blip, the heading, the camera offset, and the absolute tag pose,
     * return the absolute robot pose in field coordinates.
     * 
     * This method does not trust the tag rotation from the camera, it uses the gyro
     * signal instead.
     * 
     * @param cameraInRobotCoords                camera offset expressed as a
     *                                           transform from robot-frame to
     *                                           camera-frame, e.g.camera 0.5m in
     *                                           front of the robot center and 0.5m
     *                                           from the floor would have a
     *                                           translation (0.5, 0, 0.5)
     * @param tagInFieldCoords                   tag location expressed as a pose in
     *                                           field-frame. this should come from
     *                                           the json
     * @param blip                               direct from the camera
     * @param robotRotationInFieldCoordsFromGyro direct from the gyro. note that
     *                                           drive.getPose() isn't exactly the
     *                                           gyro reading; it might be better to
     *                                           use the real gyro than the getPose
     *                                           method.
     */
    public static Pose3d getRobotPoseInFieldCoords(
            Transform3d cameraInRobotCoords,
            Pose3d tagInFieldCoords,
            Blip24 blip,
            Rotation3d robotRotationInFieldCoordsFromGyro) {

        Rotation3d cameraRotationInFieldCoords = cameraRotationInFieldCoords(
                cameraInRobotCoords,
                robotRotationInFieldCoordsFromGyro);

        Translation3d tagTranslationInCameraCoords = blipToTranslation(blip);

        t.log(Level.DEBUG, kName, "CAMERA ROT IN FIELD COORDS", cameraRotationInFieldCoords.toRotation2d());
        t.log(Level.DEBUG, kName, "TAG TRANSLATION IN CAM COORDS", tagTranslationInCameraCoords.toTranslation2d());
        // System.out.println("TAG TRANLSAION IN CAM COORDS :" +
        // tagTranslationInCameraCoords.toTranslation2d());
        // System.out.println("CAMERA ROT IN FIELD COORDS: " +
        // cameraRotationInFieldCoords.toRotation2d());

        // System.out.println("TAG IN FIELD COORDS COOORDS" +
        // tagInFieldCoords.toPose2d());

        Rotation3d tagRotationInCameraCoords = tagRotationInRobotCoordsFromGyro(
                tagInFieldCoords.getRotation(),
                cameraRotationInFieldCoords);

        t.log(Level.DEBUG, kName, "TAG ROTATION IN CAM COOORDS", tagRotationInCameraCoords.toRotation2d());

        // System.out.println("TAG ROTATION IN CAM COOORDS"+
        // tagRotationInCameraCoords.toRotation2d());

        Transform3d tagInCameraCoords = new Transform3d(
                tagTranslationInCameraCoords,
                tagRotationInCameraCoords);

        Pose3d cameraInFieldCoords = toFieldCoordinates(
                tagInCameraCoords,
                tagInFieldCoords);

        // System.out.println("CAM IN FIELD COORDS:::: " +
        // cameraInFieldCoords.toPose2d());
        t.log(Level.DEBUG, kName, "CAM IN FIELD COORDS", cameraInFieldCoords.getTranslation().toTranslation2d());

        return applyCameraOffset(
                cameraInFieldCoords,
                cameraInRobotCoords);
    }

    //////////////////////////////
    //
    // package private below, don't use these.

    /**
     * given the gyro rotation and the camera offset, return the camera absolute
     * rotation. Package-private for testing.
     */
    static Rotation3d cameraRotationInFieldCoords(
            Transform3d cameraInRobotCoords,
            Rotation3d robotRotationInFieldCoordsFromGyro) {
        return cameraInRobotCoords.getRotation()
                .rotateBy(robotRotationInFieldCoordsFromGyro);
    }

    /**
     * Extract translation and rotation from z-forward blip and return the same
     * translation and rotation as an NWU x-forward transform. Package-private for
     * testing.
     */
    static Transform3d blipToTransform(Blip24 b) {
        return new Transform3d(blipToTranslation(b), blipToRotation(b));
    }

    /**
     * Extract the translation from a "z-forward" blip and return the same
     * translation expressed in our usual "x-forward" NWU translation.
     * It would be possible to also consume the blip rotation matrix, if it were
     * renormalized, but it's not very accurate, so we don't consume it.
     * Package-private for testing.
     */
    static Translation3d blipToTranslation(Blip24 b) {
        return GeometryUtil.zForwardToXForward(b.getPose().getTranslation());
    }

    /**
     * Extract the rotation from the "z forward" blip and return the same rotation
     * expressed in our usual "x forward" NWU coordinates. Package-private for
     * testing.
     */
    static Rotation3d blipToRotation(Blip24 b) {
        return GeometryUtil.zForwardToXForward(b.getPose().getRotation());
    }

    /**
     * Because the camera's estimate of tag rotation isn't very accurate, this
     * synthesizes an estimate using the tag rotation in field frame (from json) and
     * the camera rotation in field frame (from gyro). Package-private for testing.
     */
    static Rotation3d tagRotationInRobotCoordsFromGyro(
            Rotation3d tagRotationInFieldCoords,
            Rotation3d cameraRotationInFieldCoords) {
        return tagRotationInFieldCoords.rotateBy(cameraRotationInFieldCoords.unaryMinus());
    }

    /**
     * Given the tag in camera frame and tag in field frame, return the camera in
     * field frame. Package-private for testing.
     */
    static Pose3d toFieldCoordinates(Transform3d tagInCameraCords, Pose3d tagInFieldCords) {
        // First invert the camera-to-tag transform, obtaining tag-to-camera.
        Transform3d cameraInTagCords = tagInCameraCords.inverse();
        // Transform3d cameraInTagCords = tagInCameraCords;

        // Then compose field-to-tag with tag-to-camera to get field-to-camera.
        return tagInFieldCords.transformBy(cameraInTagCords);
    }

    /**
     * Given the camera in field frame and camera in robot frame, return the robot
     * in field frame. Package-private for testing.
     */
    static Pose3d applyCameraOffset(Pose3d cameraInFieldCoords, Transform3d cameraInRobotCoords) {
        Transform3d robotInCameraCoords = cameraInRobotCoords.inverse();
        return cameraInFieldCoords.transformBy(robotInCameraCoords);
    }

    /**
     * Return a robot relative transform to the blip.
     */
    static Transform3d toTarget(Transform3d cameraInRobotCoordinates, Blip24 blip) {
        Translation3d t = PoseEstimationHelper.blipToTranslation(blip);
        return cameraInRobotCoordinates.plus(
                new Transform3d(t, GeometryUtil.kRotation3Zero));
    }

    private PoseEstimationHelper() {
    }
}
