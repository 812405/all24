package org.team100.frc2024.motion;

import org.team100.frc2024.motion.amp.AmpSubsystem;
import org.team100.frc2024.motion.intake.Intake;
import org.team100.frc2024.motion.shooter.Shooter;

import edu.wpi.first.wpilibj2.command.Command;

public class OuttakeCommand extends Command {
    Intake m_intake;
    Shooter m_shooter;
    AmpSubsystem m_amp;
    FeederSubsystem m_feeder;

    double value = 5;

    public OuttakeCommand(Intake intake, Shooter shooter, AmpSubsystem amp, FeederSubsystem feeder) {

        m_intake = intake;
        m_shooter = shooter;
        m_amp = amp;
        m_feeder = feeder;

        addRequirements(m_amp, m_intake, m_shooter, m_feeder);
    }

    @Override
    public void initialize() {
        //
    }

    @Override
    public void execute() {

        m_feeder.starve();
        m_intake.outtake();
        m_shooter.feed();
        m_amp.driveFeeder(-1);

    }

    @Override
    public void end(boolean interrupted) {
        m_shooter.stop();
        m_amp.driveFeeder(0);

    }

    @Override
    public boolean isFinished() {
        return false;
    }
}
