package org.team100.lib.motor.drive;

import org.team100.lib.config.FeedforwardConstants;
import org.team100.lib.config.PIDConstants;
import org.team100.lib.motor.MotorWithEncoder100;
import org.team100.lib.telemetry.Telemetry;
import org.team100.lib.telemetry.Telemetry.Level;
import org.team100.lib.units.Distance100;
import org.team100.lib.util.Names;

import com.ctre.phoenix6.StatusSignal;
import com.ctre.phoenix6.configs.CurrentLimitsConfigs;
import com.ctre.phoenix6.configs.MotorOutputConfigs;
import com.ctre.phoenix6.configs.Slot0Configs;
import com.ctre.phoenix6.configs.TalonFXConfiguration;
import com.ctre.phoenix6.configs.TalonFXConfigurator;
import com.ctre.phoenix6.controls.DutyCycleOut;
import com.ctre.phoenix6.controls.VelocityVoltage;
import com.ctre.phoenix6.hardware.TalonFX;
import com.ctre.phoenix6.signals.InvertedValue;
import com.ctre.phoenix6.signals.NeutralModeValue;

/**
 * PHOENIX 6 VERSION
 * 
 * Swerve drive motor using Falcon 500.
 * 
 * Uses default position/velocity sensor which is the integrated one.
 * 
 * Phoenix 6 uses a Kalman filter to eliminate velocity measurement lag.
 */
public class Falcon6DriveMotor implements MotorWithEncoder100<Distance100> {
    /**
     * Motor resistance, Kraken. Falcon is 0.03.
     * https://store.ctr-electronics.com/content/datasheet/Motor%20Performance%20Analysis%20Report.pdf
     */
    private static final double kROhms = 0.025;
    /**
     * Motor torque constant, Kraken. Falcon is 0.018.
     * https://store.ctr-electronics.com/content/datasheet/Motor%20Performance%20Analysis%20Report.pdf
     */
    private static final double kTNm_amp = 0.019;
    /**
     * The speed, below which, static friction applies, in motor revolutions per
     * second.
     */
    private static final double staticFrictionSpeedLimitRev_S = 0.1;

    /**
     * Friction feedforward in amps, for when the mechanism is stopped, or nearly
     * so.
     */
    private final double staticFrictionFFVolts;

    /**
     * Friction feedforward in amps, for when the mechanism is moving.
     */
    private final double dynamicFrictionFFVolts;

    /**
     * Velocity feedforward in amps
     */
    private final double velocityFFVolts_Rev_S;

    /**
     * Accel feedforward in amps
     */
    private final double accelFFVolts_M_S_S;

    private final Telemetry t = Telemetry.get();
    private final TalonFX m_motor;
    private final double m_gearRatio;
    private final double m_wheelDiameter;
    private final String m_name;
    private final double m_distancePerTurn;

    /** Current position, updated in periodic(). */
    private double m_positionRev;
    /** Current velocity, updated in periodic(). */
    private double m_velocityRev_S;
    /** Current output, updated in periodic() */
    private double m_outputDutyCycle;
    /** Current motor error, updated in periodic() */
    private double m_errorRev_S;

    /** updated in periodic() */
    private double m_positionM;
    /** updated in periodic() */
    private double m_velocityM_S;

    public Falcon6DriveMotor(
            String name,
            int canId,
            boolean motorPhase,
            double currentLimit,
            double kDriveReduction,
            double wheelDiameter,
            PIDConstants lowLevelVelocityConstants,
            FeedforwardConstants lowLevelFeedforwardConstants) {
        velocityFFVolts_Rev_S = lowLevelFeedforwardConstants.getkV();
        accelFFVolts_M_S_S = lowLevelFeedforwardConstants.getkA();
        dynamicFrictionFFVolts = lowLevelFeedforwardConstants.getkDS();
        staticFrictionFFVolts = lowLevelFeedforwardConstants.getkSS();
        if (name.startsWith("/"))
            throw new IllegalArgumentException();
        m_wheelDiameter = wheelDiameter;
        m_gearRatio = kDriveReduction;
        m_distancePerTurn = wheelDiameter * Math.PI / kDriveReduction;

        m_motor = new TalonFX(canId);

        TalonFXConfigurator talonFXConfigurator = m_motor.getConfigurator();

        TalonFXConfiguration conf = new TalonFXConfiguration();
        talonFXConfigurator.apply(conf);

        MotorOutputConfigs motorConfigs = new MotorOutputConfigs();
        motorConfigs.NeutralMode = NeutralModeValue.Brake;

        // Avoid draining the battery too much.
        CurrentLimitsConfigs currentConfigs = new CurrentLimitsConfigs();
        currentConfigs.SupplyCurrentLimit = currentLimit;
        currentConfigs.SupplyCurrentLimitEnable = true;
        talonFXConfigurator.apply(currentConfigs);

        if (motorPhase) {
            motorConfigs.Inverted = InvertedValue.CounterClockwise_Positive;
        } else {
            motorConfigs.Inverted = InvertedValue.Clockwise_Positive;
        }

        talonFXConfigurator.apply(motorConfigs);

        m_motor.getVelocity().setUpdateFrequency(50);

        // set slot 0 gains
        var slot0Configs = new Slot0Configs();
        slot0Configs.kV = 0.0;
        slot0Configs.kP = lowLevelVelocityConstants.getP();
        slot0Configs.kI = lowLevelVelocityConstants.getI();
        slot0Configs.kD = lowLevelVelocityConstants.getD();

        // apply gains, 50 ms total timeout
        m_motor.getConfigurator().apply(slot0Configs, 0.050);

        m_name = Names.append(name, this);
        t.log(Level.TRACE, m_name, "Device ID", m_motor.getDeviceID());
    }

    //////////////////
    // motor methods

    @Override
    public void setDutyCycle(double output) {
        DutyCycleOut d = new DutyCycleOut(output);
        m_motor.setControl(d);
        t.log(Level.TRACE, m_name, "desired duty cycle [-1,1]", output);
    }

    /**
     * Supports accel feedforward.
     */
    @Override
    public void setVelocity(double outputM_S, double accelM_S_S) {
        double wheelRev_S = outputM_S / (m_wheelDiameter * Math.PI);
        double wheelRev_S2 = accelM_S_S / (m_wheelDiameter * Math.PI);
        double motorRev_S = wheelRev_S * m_gearRatio;
        double motorRev_S2 = wheelRev_S2 * m_gearRatio;
        double currentMotorRev_S = m_velocityRev_S;

        double frictionFFVolts = frictionFFVolts(currentMotorRev_S, motorRev_S);
        double velocityFFVolts = velocityFFVolts(motorRev_S);
        double accelFFVolts = accelFFVolts(accelM_S_S);

        double kFFVolts = frictionFFVolts + velocityFFVolts + accelFFVolts;

        VelocityVoltage v = new VelocityVoltage(motorRev_S);
        v.FeedForward = kFFVolts;
        v.Acceleration = motorRev_S2;
        m_motor.setControl(v);

        t.log(Level.TRACE, m_name, "module input (RPS)", wheelRev_S);
        t.log(Level.TRACE, m_name, "motor input (RPS)", motorRev_S);
        t.log(Level.TRACE, m_name, "friction feedforward volts", frictionFFVolts);
        t.log(Level.TRACE, m_name, "velocity feedforward volts", velocityFFVolts);
        t.log(Level.TRACE, m_name, "accel feedforward volts", accelFFVolts);
    }

    /**
     * Supports accel feedforward.
     */
    @Override
    public void setVelocity(double outputM_S, double accelM_S_S, double torqueNm) {
        double wheelRev_S = outputM_S / (m_wheelDiameter * Math.PI);
        double wheelRev_S2 = accelM_S_S / (m_wheelDiameter * Math.PI);
        double motorRev_S = wheelRev_S * m_gearRatio;
        double motorRev_S2 = wheelRev_S2 * m_gearRatio;
        double currentMotorRev_S = m_velocityRev_S;

        double frictionFFVolts = frictionFFVolts(currentMotorRev_S, motorRev_S);
        double velocityFFVolts = velocityFFVolts(motorRev_S);
        double accelFFVolts = accelFFVolts(accelM_S_S);

        double torqueFFAmps = torqueNm / kTNm_amp;
        double torqueFFVolts = torqueFFAmps * kROhms;

        double kFFVolts = frictionFFVolts + velocityFFVolts + accelFFVolts + torqueFFVolts;

        VelocityVoltage v = new VelocityVoltage(motorRev_S);
        v.FeedForward = kFFVolts;
        v.Acceleration = motorRev_S2;
        m_motor.setControl(v);

        t.log(Level.TRACE, m_name, "module input (RPS)", wheelRev_S);
        t.log(Level.TRACE, m_name, "motor input (RPS)", motorRev_S);
        t.log(Level.TRACE, m_name, "friction feedforward volts", frictionFFVolts);
        t.log(Level.TRACE, m_name, "velocity feedforward volts", velocityFFVolts);
        t.log(Level.TRACE, m_name, "accel feedforward volts", accelFFVolts);
        t.log(Level.TRACE, m_name, "torque feedforward volts", torqueFFVolts);
    }

    @Override
    public double getTorque() {
        StatusSignal<Double> statorCurrentAmpsStatus = m_motor.getTorqueCurrent();
        // TODO: latency compensation
        double statorCurrentAmps = statorCurrentAmpsStatus.getValueAsDouble();
        return statorCurrentAmps * kTNm_amp;
    }

    @Override
    public void stop() {
        m_motor.stopMotor();
    }

    @Override
    public void close() {
        m_motor.close();
    }

    /**
     * @return integrated sensor velocity in rev per sec
     */
    public double getVelocityRev_S() {
        return m_velocityRev_S;
    }

    /**
     * Sets integrated sensor position to zero.
     */
    public void resetPosition() {
        m_motor.setPosition(0);
        m_positionRev = 0;
    }

    @Override
    public void periodic() {
        m_positionRev = m_motor.getPosition().getValueAsDouble();
        m_velocityRev_S = m_motor.getVelocity().getValueAsDouble();

        m_outputDutyCycle = m_motor.getDutyCycle().getValueAsDouble();
        m_errorRev_S = m_motor.getClosedLoopError().getValueAsDouble();

        m_positionM = m_positionRev * m_distancePerTurn;
        m_velocityM_S = m_velocityRev_S * m_distancePerTurn;

        t.log(Level.TRACE, m_name, "position (rev)", m_positionRev);
        t.log(Level.TRACE, m_name, "position (m)", m_positionM);
        t.log(Level.TRACE, m_name, "velocity (rev_s)", m_velocityRev_S);
        t.log(Level.TRACE, m_name, "velocity (m_s)", m_velocityM_S);

        t.log(Level.TRACE, m_name, "output [-1,1]", m_outputDutyCycle);
        t.log(Level.TRACE, m_name, "error (rev_s)", getErrorRev_S());
        t.log(Level.TRACE, m_name, "temperature (C)", m_motor.getDeviceTemp().getValueAsDouble());
        t.log(Level.TRACE, m_name, "current (A)", m_motor.getSupplyCurrent().getValueAsDouble());
    }

    //////////////////////////
    // encoder methods

    /** Position in meters */
    @Override
    public Double getPosition() {
        return m_positionM;
    }

    /** Velocity in meters/sec */
    @Override
    public double getRate() {
        return m_velocityM_S;
    }

    @Override
    public void reset() {
        resetPosition();
        m_positionM = 0;
    }

    ///////////////////////////////////////////////////////////////

    /**
     * Frictional feedforward in volts
     */
    private double frictionFFVolts(double currentMotorRev_S, double desiredMotorRev_S) {
        double direction = Math.signum(desiredMotorRev_S);
        if (currentMotorRev_S < staticFrictionSpeedLimitRev_S) {
            return staticFrictionFFVolts * direction;
        }
        return dynamicFrictionFFVolts * direction;
    }

    /**
     * Velocity feedforward in volts per rev per second
     */
    private double velocityFFVolts(double desiredMotorRev_S) {
        return velocityFFVolts_Rev_S * desiredMotorRev_S;
    }

    /**
     * Acceleration feedforward in volts per rev per second per second
     */
    private double accelFFVolts(double accelM_S_S) {
        return accelFFVolts_M_S_S * accelM_S_S;
    }

    private double getErrorRev_S() {
        return m_errorRev_S;
    }
}
