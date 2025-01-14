package org.team100.frc2024.motion.intake;

import org.team100.frc2024.RobotState100;
import org.team100.frc2024.RobotState100.IntakeState100;
import org.team100.frc2024.SensorInterface;
import org.team100.lib.config.FeedforwardConstants;
import org.team100.lib.config.Identity;
import org.team100.lib.config.PIDConstants;
import org.team100.lib.config.SysParam;
import org.team100.lib.motion.components.LimitedVelocityServo;
import org.team100.lib.motion.components.ServoFactory;
import org.team100.lib.motion.simple.SpeedingVisualization;
import org.team100.lib.telemetry.Telemetry;
import org.team100.lib.units.Distance100;
import org.team100.lib.util.Names;

import edu.wpi.first.wpilibj.PWM;
import edu.wpi.first.wpilibj.Timer;

/**
 * Direct-drive roller intake
 * 
 * Typical free speed of 6k rpm => 100 turn/sec
 * diameter of 0.05m => 0.15 m/turn
 * therefore top speed is around 15 m/s.
 * 
 * This system has very low intertia, so can spin up
 * very fast, but it's fragile: limit accel to avoid stressing it.
 */
public class IntakeRoller extends Intake {
    // TODO: tune the current limit
    private static final int kCurrentLimit = 40;

    /**
     * Surface velocity of whatever is turning in the intake.
     */
    private static final double kIntakeVelocityM_S = 3;
    private static final double kUpperIntakeM_S = 0.5;

    private static final double kCenteringVelocityM_S = 3;

    private final String m_name;
    private final LimitedVelocityServo<Distance100> intakeRoller;
    // private final LimitedVelocityServo<Distance100> centeringWheels;
    private final PWM centeringWheels;
    private final LimitedVelocityServo<Distance100> superRollers;

    private final SpeedingVisualization m_viz;
    private final SensorInterface m_sensors;
    private final Telemetry t;

    public IntakeRoller(SensorInterface sensors, int intakeCAN, int centerCAN, int superCAN) {

        m_name = Names.name(this);

        m_sensors = sensors;

        t = Telemetry.get();

        SysParam rollerParameter = SysParam.limitedNeoVelocityServoSystem(9, 0.05, 15, 10, -10);

               
        switch (Identity.instance) {
            case COMP_BOT:
            //TODO tune kV
                intakeRoller = ServoFactory.limitedNeoVelocityServo(
                        m_name + "/Intake Roller",
                        intakeCAN,
                        false,
                        kCurrentLimit,
                        rollerParameter,
                        new FeedforwardConstants(0.122,0,0.1,0.065),
                        new PIDConstants(0.0001, 0, 0));

                centeringWheels = new PWM(1);
                superRollers = ServoFactory.limitedNeoVelocityServo(
                        m_name + "/Super Roller",
                        superCAN,
                        true,
                        kCurrentLimit,
                        rollerParameter,
                        new FeedforwardConstants(0.122,0,0.1,0.065),
                        new PIDConstants(0.0001, 0, 0));
                break;
            case BLANK:
            default:
                intakeRoller = ServoFactory.limitedSimulatedVelocityServo(
                        m_name + "/Intake Roller",
                        rollerParameter);

                centeringWheels = new PWM(1);

                superRollers = ServoFactory.limitedSimulatedVelocityServo(
                        m_name + "/Center Wheels",
                        rollerParameter);
        }
        m_viz = new SpeedingVisualization(m_name, this);
    }

    @Override
    //All you have to do is set the state once and it handles the rest. No need for commands 
    public void intakeSmart() {
        // System.out.println("IM RUNNING");
        if(!m_sensors.getFeederSensor()){
            System.out.println("STOPING INAKE" + Timer.getFPGATimestamp());
            intakeRoller.setVelocity(0);
            centeringWheels.setSpeed(0);
            superRollers.setVelocity(0);
            RobotState100.changeIntakeState(IntakeState100.STOP);
        } else {
            intakeRoller.setVelocity(kIntakeVelocityM_S);
            centeringWheels.setSpeed(0.8);
            superRollers.setVelocity(kIntakeVelocityM_S);
        }
        

    }

    @Override
    public void intake(){
        centeringWheels.setSpeed(0.8);
        intakeRoller.setDutyCycle(0.8);
        superRollers.setDutyCycle(0.8);

    }

     @Override
    public void runUpper(){
        superRollers.setDutyCycle(0.8);

    }

    @Override
    public void outtake() {
        intakeRoller.setDutyCycle(-0.8);
        centeringWheels.setSpeed(-0.8);
        superRollers.setVelocity(-0.8);
    }

    @Override
    public void stop() {
        // System.out.println("STOPPPP");
        intakeRoller.setDutyCycle(0);
        centeringWheels.setSpeed(0);
        superRollers.setDutyCycle(0);

    }

    @Override
    public void periodic() {
        intakeRoller.periodic();
        superRollers.periodic();
        m_viz.periodic();

        // System.out.println(m_sensors.getFeederSensor());
    }

    @Override
    public double getVelocity() {
        return intakeRoller.getVelocity();
    }

    
}
