package editor.overlay;

import engine.NodeId;
import engine.SceneSnapshot;
import engine.Transform;

import math.Vec3;

import java.util.Objects;

/** Candidate local-transform math. Publishing and one-entry undo remain controller concerns. */
public final class GizmoMath {
    private GizmoMath() {}

    /** Convert a world-space movement through the parent transform and preserve rotation/scale. */
    public static Transform translated(SceneSnapshot snapshot, NodeId nodeId, Vec3 worldDelta) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(worldDelta, "worldDelta");
        var node = snapshot.requireNode(nodeId);
        var local = node.localTransform();
        var delta =
                node.parentId() == null
                        ? worldDelta
                        : snapshot.worldTransform(node.parentId()).inverseVector(worldDelta);
        return new Transform(local.position.add(delta), local.rotation, local.scale);
    }

    /**
     * Post-compose the node rotation about its displayed local axis while retaining scale. Scene
     * publication performs the final parent hierarchy/shear validation atomically.
     */
    public static Transform rotated(
            SceneSnapshot snapshot, NodeId nodeId, OverlayGeometry.Axis axis, float degrees) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(axis, "axis");
        if (!Float.isFinite(degrees))
            throw new IllegalArgumentException("Rotation delta must be finite");
        var local = snapshot.requireNode(nodeId).localTransform();
        double[][] rotation = rotation(local),
                delta = axisRotation(axis, degrees),
                composed = new double[3][3];
        for (int row = 0; row < 3; row++)
            for (int column = 0; column < 3; column++)
                for (int k = 0; k < 3; k++)
                    composed[row][column] += rotation[row][k] * delta[k][column];
        return new Transform(local.position, euler(composed), local.scale);
    }

    private static double[][] rotation(Transform transform) {
        var x = transform.vector(new Vec3(1, 0, 0)).scale(1 / transform.scale.x());
        var y = transform.vector(new Vec3(0, 1, 0)).scale(1 / transform.scale.y());
        var z = transform.vector(new Vec3(0, 0, 1)).scale(1 / transform.scale.z());
        return new double[][] {{x.x(), y.x(), z.x()}, {x.y(), y.y(), z.y()}, {x.z(), y.z(), z.z()}};
    }

    private static double[][] axisRotation(OverlayGeometry.Axis axis, float degrees) {
        double angle = Math.toRadians(degrees), s = Math.sin(angle), c = Math.cos(angle);
        return switch (axis) {
            case X -> new double[][] {{1, 0, 0}, {0, c, -s}, {0, s, c}};
            case Y -> new double[][] {{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
            case Z -> new double[][] {{c, -s, 0}, {s, c, 0}, {0, 0, 1}};
        };
    }

    private static Vec3 euler(double[][] r) {
        double y = Math.asin(Math.clamp(-r[2][0], -1, 1)), cy = Math.cos(y), x, z;
        if (Math.abs(cy) > 1e-7) {
            x = Math.atan2(r[2][1], r[2][2]);
            z = Math.atan2(r[1][0], r[0][0]);
        } else {
            z = 0;
            x = y > 0 ? Math.atan2(r[0][1], r[1][1]) : Math.atan2(-r[0][1], r[1][1]);
        }
        return new Vec3(
                (float) Math.toDegrees(x), (float) Math.toDegrees(y), (float) Math.toDegrees(z));
    }
}
