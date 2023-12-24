package org.team100.lib.swerve;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.team100.lib.geometry.GeometryUtil;

import edu.wpi.first.math.kinematics.SwerveModuleState;

class DriveAccelerationLimiterTest {

    private static final double kDelta = 0.001;

    @Test
    void testUnconstrained() {
        SwerveKinematicLimits l = new SwerveKinematicLimits(1, 1, 1, 1, 1);
        DriveAccelerationLimiter c = new DriveAccelerationLimiter(l);
        SwerveModuleState[] prevModuleStates = new SwerveModuleState[] {
                new SwerveModuleState(0, GeometryUtil.kRotationZero)
        };
        double[] prev_vx = new double[] { 0 };
        double[] prev_vy = new double[] { 0 };
        double[] desired_vx = new double[] { 0 };
        double[] desired_vy = new double[] { 0 };
        double min_s = 1;
        double s = c.enforceWheelAccelLimit(prevModuleStates, prev_vx, prev_vy, desired_vx, desired_vy, min_s);
        assertEquals(1, s, kDelta);
    }

    @Test
    void testConstrained() {
        SwerveKinematicLimits l = new SwerveKinematicLimits(1, 1, 1, 1, 1);
        DriveAccelerationLimiter c = new DriveAccelerationLimiter(l);
        SwerveModuleState[] prevModuleStates = new SwerveModuleState[] {
                new SwerveModuleState(0, GeometryUtil.kRotationZero)
        };
        double[] prev_vx = new double[] { 0 };
        double[] prev_vy = new double[] { 0 };
        double[] desired_vx = new double[] { 1 };
        double[] desired_vy = new double[] { 0 };
        double min_s = 1;
        double s = c.enforceWheelAccelLimit(prevModuleStates, prev_vx, prev_vy, desired_vx, desired_vy, min_s);
        assertEquals(0.02, s, kDelta);
    }
}