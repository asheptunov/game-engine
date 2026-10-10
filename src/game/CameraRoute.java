package game;

import math.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Immutable elapsed-time replay fixture; positions are world units and angles are radians. */
public final class CameraRoute {
    public record Pose(double seconds, Vec3 position, double yaw, double pitch) {}

    private final List<Pose> poses;

    private CameraRoute(List<Pose> poses) {
        this.poses = List.copyOf(poses);
    }

    public static CameraRoute load(Path file) throws IOException {
        var lines = Files.readAllLines(file);
        if (lines.size() != 6 || !lines.getFirst().equals("seconds,x,y,z,yaw,pitch")) {
            throw new IllegalArgumentException("Route must have a header and five G4 waypoints");
        }
        var poses = new ArrayList<Pose>();
        double[] times = {0, 8, 16, 25, 30};
        for (int index = 1; index < lines.size(); index++) {
            String[] fields = lines.get(index).split(",");
            if (fields.length != 6) {
                throw new IllegalArgumentException("Route row must have six values: " + index);
            }
            double[] values = new double[6];
            for (int field = 0; field < fields.length; field++) {
                values[field] = Double.parseDouble(fields[field]);
                if (!Double.isFinite(values[field])) {
                    throw new IllegalArgumentException("Route values must be finite");
                }
            }
            if (values[0] != times[index - 1]) {
                throw new IllegalArgumentException("G4 waypoint times must be 0, 8, 16, 25, 30");
            }
            var position = new Vec3((float) values[1], (float) values[2], (float) values[3]);
            // Reuse navigation validation, including float overflow and pitch bounds.
            new GameNavigation().pose(position, values[4], values[5]);
            poses.add(new Pose(values[0], position, values[4], values[5]));
        }
        var hold = poses.get(3);
        var end = poses.get(4);
        if (!hold.position().equals(end.position())
                || hold.yaw() != end.yaw()
                || hold.pitch() != end.pitch()) {
            throw new IllegalArgumentException("The last five seconds must hold the final pose");
        }
        return new CameraRoute(poses);
    }

    /** Interpolate unwrapped angles directly; never choose a different shortest-angle route. */
    public Pose at(double seconds) {
        double time = Math.clamp(seconds, 0, 30);
        for (int index = 1; index < poses.size(); index++) {
            var next = poses.get(index);
            if (time <= next.seconds()) {
                var previous = poses.get(index - 1);
                if (previous.position().equals(next.position())
                        && previous.yaw() == next.yaw()
                        && previous.pitch() == next.pitch()) {
                    return new Pose(time, next.position(), next.yaw(), next.pitch());
                }
                double fraction =
                        (time - previous.seconds()) / (next.seconds() - previous.seconds());
                return new Pose(
                        time,
                        previous.position()
                                .scale((float) (1 - fraction))
                                .add(next.position().scale((float) fraction)),
                        previous.yaw() + fraction * (next.yaw() - previous.yaw()),
                        previous.pitch() + fraction * (next.pitch() - previous.pitch()));
            }
        }
        return poses.getLast();
    }

    public void apply(GameNavigation navigation, double seconds) {
        var pose = at(seconds);
        navigation.pose(pose.position(), pose.yaw(), pose.pitch());
    }
}
