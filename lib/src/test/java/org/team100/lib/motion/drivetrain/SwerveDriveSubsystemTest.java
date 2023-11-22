package org.team100.lib.motion.drivetrain;

import org.junit.jupiter.api.Test;
import org.team100.lib.experiments.MockExperiments;
import org.team100.lib.motion.drivetrain.kinematics.FrameTransform;

import edu.wpi.first.math.estimator.SwerveDrivePoseEstimator;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.geometry.Twist2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.math.kinematics.SwerveDriveKinematics;
import edu.wpi.first.math.kinematics.SwerveModulePosition;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.wpilibj.smartdashboard.Field2d;

class SwerveDriveSubsystemTest {
    @Test
    void testSimple() {
        MockHeading heading = new MockHeading();
        SwerveDriveKinematics kinematics = new SwerveDriveKinematics(
                new Translation2d(0.1, 0.1),
                new Translation2d(0.1, -0.1),
                new Translation2d(-0.1, 0.1),
                new Translation2d(-0.1, -0.1));
        Rotation2d gyroAngle = new Rotation2d();
        SwerveModulePosition[] modulePositions = new SwerveModulePosition[] {
                new SwerveModulePosition(),
                new SwerveModulePosition(),
                new SwerveModulePosition(),
                new SwerveModulePosition()
        };
        Pose2d initialPoseMeters = new Pose2d();

        SwerveDrivePoseEstimator poseEstimator = new SwerveDrivePoseEstimator(
                kinematics, gyroAngle, modulePositions, initialPoseMeters);

        FrameTransform frameTransform = new FrameTransform();
        MockExperiments experiments = new MockExperiments();
        SpeedLimits speedLimits = new SpeedLimits(1, 1, 1, 1);
        SwerveModuleCollectionInterface modules = new SwerveModuleCollection.Noop();

        SwerveLocal swerveLocal = new SwerveLocal(experiments, speedLimits, kinematics, modules);
        Field2d field = new Field2d();

        SwerveDriveSubsystem drive = new SwerveDriveSubsystem(
                heading,
                poseEstimator,
                frameTransform,
                swerveLocal,
                field);
        // try all the actuators
        drive.periodic();
        drive.driveInFieldCoords(new Twist2d(1, 1, 1));

        drive.periodic();
        drive.setChassisSpeeds(new ChassisSpeeds());

        drive.periodic();
        drive.setRawModuleStates(new SwerveModuleState[] {
                new SwerveModuleState(),
                new SwerveModuleState(),
                new SwerveModuleState(),
                new SwerveModuleState()
        });

        drive.periodic();
        drive.defense();

        drive.periodic();
        drive.stop();

        drive.close();
    }
}