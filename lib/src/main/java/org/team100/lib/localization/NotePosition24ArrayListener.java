package org.team100.lib.localization;

import java.util.EnumSet;
import java.util.Optional;

import org.team100.lib.config.Camera;
import org.team100.lib.util.CameraAngles;

import edu.wpi.first.math.geometry.Rotation3d;
import edu.wpi.first.math.geometry.Transform3d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.networktables.NetworkTableEvent;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.NetworkTableValue;
import edu.wpi.first.networktables.ValueEventData;
import edu.wpi.first.util.struct.StructBuffer;
import edu.wpi.first.wpilibj.Timer;

/** For testing the NotePosition struct array */
public class NotePosition24ArrayListener {
    private String id;
    StructBuffer<NotePosition24> m_buf = StructBuffer.create(NotePosition24.struct);
    NotePosition24[] positions = new NotePosition24[0];
    double latestTime = 0;
    void consumeValues(NetworkTableEvent e) {
        ValueEventData ve = e.valueData;
        NetworkTableValue v = ve.value;
        String name = ve.getTopic().getName();
        String[] fields = name.split("/");
        id = fields[1];
        System.out.println("Huh");
        if (fields.length != 3){
            System.out.println("Ruh roh");
            return;
        }
        if (fields[2].equals("fps")) {
            System.out.println("Ruh roh");
            // FPS is not used by the robot
        } else if (fields[2].equals("latency")) {
            System.out.println("Ruh roh");
            // latency is not used by the robot
        } else if (fields[2].equals("NotePosition24")) {
            System.out.println("yay");
            // decode the way StructArrayEntryImpl does
            byte[] b = v.getRaw();
            if (b.length == 0)
                return;
            try {
                synchronized (m_buf) {
                    positions = m_buf.readArray(b);
                    latestTime = Timer.getFPGATimestamp();
                }
            } catch (RuntimeException ex) {
                return;
            }
            for (NotePosition24 position : positions) {
                // this is where you would do something useful with the payload
                System.out.println(fields[1] + " " + position);
            }
        } else {
            System.out.println("note weird vision update key: " + name);
        }
    }

    /**
     * @return The y position in the camera in pixels, 0 should be the bottom of the screen 
    */
    public Optional<Translation2d>[] getTranslation2d() {
        Optional<Translation2d>[] notes = new Optional[]{Optional.empty()};
        int noteNum = 0;
        Optional<Double> y = Optional.empty();
        Optional<Double> x = Optional.empty();
        Transform3d cameraInRobotCoordinates = Camera.get(id).getOffset();
        CameraAngles ed = new CameraAngles(cameraInRobotCoordinates);
        
        for (NotePosition24 note : positions) {
            System.out.println("POSITIONS");
            if (note.getPitch() < Math.PI / 2 - cameraInRobotCoordinates.getRotation().getY()) {
            double dy = positions[0].getPitch();
            Double yz = dy;
            y = Optional.of(yz);
            double xd = positions[0].getYaw();
            Double v = xd;
            x = Optional.of(v);
            notes[noteNum] = Optional.of(ed.getTranslation2d(new Rotation3d(0,y.get(),x.get())));
            } else {
                // System.out.println("False positive from above");
            }
        }
        return notes;
        }

    public void enable() {
        NetworkTableInstance.getDefault().addListener(
                new String[] { "vision" },
                EnumSet.of(NetworkTableEvent.Kind.kValueAll),
                this::consumeValues);
    }
}
