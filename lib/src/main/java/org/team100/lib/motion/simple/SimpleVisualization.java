package org.team100.lib.motion.simple;

import edu.wpi.first.wpilibj.smartdashboard.Mechanism2d;
import edu.wpi.first.wpilibj.smartdashboard.MechanismLigament2d;
import edu.wpi.first.wpilibj.smartdashboard.MechanismRoot2d;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import edu.wpi.first.wpilibj.util.Color;
import edu.wpi.first.wpilibj.util.Color8Bit;

/**
 * Visualization for a 1-dof mechanism.
 * 
 * This represents an elevator, one ligament with variable length.
 */
public class SimpleVisualization {
    private static final double kScale = 10.0;
    private final Positioning m_subsystem;
    private final Mechanism2d m_sideView;
    private final MechanismLigament2d m_ligament;

    public SimpleVisualization(String name, Positioning subsystem) {
        m_subsystem = subsystem;
        m_sideView = new Mechanism2d(100, 100);
        MechanismRoot2d root = m_sideView.getRoot("root", 50, 50);
        double position = m_subsystem.getPositionRad();
        m_ligament = new MechanismLigament2d(name, kScale * position, 90, 5, new Color8Bit(Color.kOrange));
        root.append(m_ligament);
        SmartDashboard.putData(name, m_sideView);
    }

    public void periodic() {
        double position = m_subsystem.getPositionRad();
        m_ligament.setLength(kScale * position);
    }

}
