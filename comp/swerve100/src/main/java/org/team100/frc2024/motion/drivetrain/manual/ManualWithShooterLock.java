package org.team100.frc2024.motion.drivetrain.manual;

import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.team100.frc2024.motion.drivetrain.ShooterUtil;
import org.team100.lib.commands.drivetrain.FieldRelativeDriver;
import org.team100.lib.controller.State100;
import org.team100.lib.geometry.TargetUtil;
import org.team100.lib.geometry.Vector2d;
import org.team100.lib.motion.drivetrain.SwerveState;
import org.team100.lib.motion.drivetrain.kinodynamics.SwerveKinodynamics;
import org.team100.lib.profile.Constraints100;
import org.team100.lib.profile.TrapezoidProfile100;
import org.team100.lib.sensors.HeadingInterface;
import org.team100.lib.telemetry.Telemetry;
import org.team100.lib.telemetry.Telemetry.Level;
import org.team100.lib.util.DriveUtil;
import org.team100.lib.util.Math100;

import edu.wpi.first.math.MathUtil;
import edu.wpi.first.math.controller.PIDController;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.geometry.Twist2d;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.DriverStation.Alliance;

/**
 * Manual cartesian control, with rotational control based on a target position.
 * 
 * This is useful for shooting solutions, or for keeping the camera pointed at
 * something.
 * 
 * Rotation uses a profile, velocity feedforward, and positional feedback.
 * 
 * The targeting solution is based on bearing alone, so it won't work if the
 * robot or target is moving. That effect can be compensated, though.
 */
public class ManualWithShooterLock implements FieldRelativeDriver {
    private static final double kBallVelocityM_S = 5;
    private static final double kDtSec = 0.02;
    /**
     * Relative rotational speed. Use a moderate value to trade rotation for
     * translation
     */
    private static final double kRotationSpeed = 0.5;
    private final Telemetry t = Telemetry.get();
    private final SwerveKinodynamics m_swerveKinodynamics;
    private final HeadingInterface m_heading;
    private final PIDController m_thetaController;
    private final PIDController m_omegaController;
    private final String m_name;
    private final TrapezoidProfile100 m_profile;
    State100 m_thetaSetpoint;
    Translation2d m_ball;
    Translation2d m_ballV;
    BooleanSupplier m_trigger;
    Pose2d m_prevPose;
    private final double m_scale;
    private boolean isAligned;

    public ManualWithShooterLock(
            String parent,
            SwerveKinodynamics swerveKinodynamics,
            HeadingInterface heading,
            PIDController thetaController,
            PIDController omegaController,
            double scale) {
        m_swerveKinodynamics = swerveKinodynamics;
        m_heading = heading;
        m_thetaController = thetaController;
        m_omegaController = omegaController;
        m_scale = scale;
        isAligned = false;
        m_name = "MANUALLLLL";
        m_trigger = () -> false;
        Constraints100 c = new Constraints100(
                swerveKinodynamics.getMaxAngleSpeedRad_S() * kRotationSpeed,
                swerveKinodynamics.getMaxAngleAccelRad_S2() * kRotationSpeed);
        m_profile = new TrapezoidProfile100(c, 0.01);
    }

    @Override
    public void reset(Pose2d currentPose) {
        m_thetaSetpoint = new State100(currentPose.getRotation().getRadians(), m_heading.getHeadingRateNWU());
        m_ball = null;
        m_prevPose = currentPose;
        m_thetaController.reset();
        m_omegaController.reset();
    }

    /**
     * Clips the input to the unit circle, scales to maximum (not simultaneously
     * feasible) speeds, and then desaturates to a feasible holonomic velocity.
     */
    @Override
    public Twist2d apply(SwerveState state, Twist2d input) {
        Optional<Alliance> optionalAlliance = DriverStation.getAlliance();
        if (!optionalAlliance.isPresent())
            return new Twist2d();

        // clip the input to the unit circle
        Twist2d clipped = DriveUtil.clampTwist(input, 1.0);
        Rotation2d currentRotation = state.pose().getRotation();
        double headingRate = m_heading.getHeadingRateNWU();

        Translation2d currentTranslation = state.pose().getTranslation();
        Translation2d target = ShooterUtil.getOffsetTranslation(optionalAlliance.get(), state, m_scale);
        Rotation2d bearing = bearing(currentTranslation, target);
        Rotation2d bearingCorrected = aimWhileMoving(bearing, 20, state); //make this integreate with shooter blah blah blah h mhh bh h                                                                                                                    

        t.log(Level.TRACE, m_name, "bearing", bearing);

        // take the short path
        double measurement = currentRotation.getRadians();
        bearing = new Rotation2d(
                Math100.getMinDistance(measurement, bearing.getRadians()));

        checkBearing(bearing, currentRotation);

        t.log(Level.TRACE, m_name, "Bearing Check", bearing.minus(currentRotation).getDegrees());

        // make sure the setpoint uses the modulus close to the measurement.
        m_thetaSetpoint = new State100(
                Math100.getMinDistance(measurement, m_thetaSetpoint.x()),
                m_thetaSetpoint.v());

        // the goal omega should match the target's apparent motion
        double targetMotion = TargetUtil.targetMotion(state, target);
        t.log(Level.TRACE, m_name, "apparent motion", targetMotion);

        State100 goal = new State100(bearing.getRadians(), targetMotion);

        m_thetaSetpoint = m_profile.calculate(kDtSec, m_thetaSetpoint, goal);

        // this is user input scaled to m/s and rad/s
        Twist2d scaledInput = DriveUtil.scale(
                clipped,
                m_swerveKinodynamics.getMaxDriveVelocityM_S(),
                m_swerveKinodynamics.getMaxAngleSpeedRad_S());

        double thetaFF = m_thetaSetpoint.v();

        double thetaFB = m_thetaController.calculate(measurement, m_thetaSetpoint.x());

        t.log(Level.TRACE, m_name, "target", target);
        t.log(Level.TRACE, m_name, "theta/setpoint", m_thetaSetpoint);
        t.log(Level.TRACE, m_name, "theta/measurement", measurement);
        t.log(Level.TRACE, m_name, "theta/error", m_thetaController.getPositionError());
        t.log(Level.TRACE, m_name, "theta/fb", thetaFB);
        double omegaFB = m_omegaController.calculate(headingRate, m_thetaSetpoint.v());
        t.log(Level.TRACE, m_name, "omega/reference", m_thetaSetpoint);
        t.log(Level.TRACE, m_name, "omega/measurement", headingRate);
        t.log(Level.TRACE, m_name, "omega/error", m_omegaController.getPositionError());
        t.log(Level.TRACE, m_name, "omega/fb", omegaFB);
        t.log(Level.TRACE, m_name, "target motion", targetMotion);
        t.log(Level.TRACE, m_name, "goal X", goal.x());

        double omega = MathUtil.clamp(
                thetaFF + thetaFB + omegaFB,
                -m_swerveKinodynamics.getMaxAngleSpeedRad_S(),
                m_swerveKinodynamics.getMaxAngleSpeedRad_S());
        Twist2d twistWithLockM_S = new Twist2d(scaledInput.dx, scaledInput.dy, omega);

        // desaturate to feasibility by preferring the rotational velocity.
        twistWithLockM_S = m_swerveKinodynamics.preferRotation(twistWithLockM_S);

        // this name needs to be exactly "/field/target" for glass.
        t.log(Level.TRACE, "field", "target", new double[] {
                target.getX(),
                target.getY(),
                0 });

        // this is just for simulation
        if (m_trigger.getAsBoolean()) {
            m_ball = currentTranslation;
            // correct for newtonian relativity
            m_ballV = new Translation2d(kBallVelocityM_S * kDtSec, currentRotation)
                    .plus(state.pose().minus(m_prevPose).getTranslation());
        }
        if (m_ball != null) {
            m_ball = m_ball.plus(m_ballV);
            // this name needs to be exactly "/field/ball" for glass.
            t.log(Level.TRACE, "field", "ball", new double[] {
                    m_ball.getX(),
                    m_ball.getY(),
                    0 });
        }

        m_prevPose = state.pose();
        return twistWithLockM_S;
    }

    /**
     * Absolute bearing to the target.
     * 
     * The bearing is only a valid shooting solution if both the robot and the
     * target are at rest!
     * 
     * If the robot and/or target is moving, then the shooting solution needs to
     * lead or lag the target.
     */
    Rotation2d bearing(Translation2d robot, Translation2d target) {

        return target.minus(robot).getAngle();
    }

    public void checkBearing(Rotation2d bearing, Rotation2d currentRotation) {
        if (Math.abs(bearing.minus(currentRotation).getDegrees()) < 20) {
            isAligned = true;
        } else {
            isAligned = false;
        }
    }

    public boolean isAligned() {
        return isAligned;
    }

    static Rotation2d aimWhileMoving(Rotation2d bearing, double shooterVelocity, SwerveState state) {

        // its the shooter util code but robot moving vec is y velocity and angle in
        // rads is bearing

        // double angleWithoutMoving = bearing.getRadians();

        Rotation2d angleInRads = bearing;
        
        Vector2d stationaryRobotVector = new Vector2d(shooterVelocity, angleInRads);

        Vector2d robotMovingVector = new Vector2d(state.y().v(), 0);

        Vector2d resultingVector = Vector2d.sub(stationaryRobotVector, robotMovingVector);

        return resultingVector.getTheta();

    }

    @Override
    public String getGlassName() {
        return "ManualWithShooterLock";
    }

}