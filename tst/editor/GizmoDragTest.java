package editor;

import static harness.Assertions.*;

import editor.overlay.GizmoDrag;
import editor.overlay.GizmoMath;
import editor.overlay.OverlayGeometry;

import engine.*;
import engine.objects.Rect;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

public class GizmoDragTest {
    private static final Camera CAMERA =
            new Camera(
                            Vec3.ZERO,
                            new Rect(new Vec3(-1, -1, 1), new Vec3(2, 0, 0), new Vec3(0, 2, 0)))
                    .validated();

    @Test
    void perspectiveAndOrthographicAxisDragsReturnStableWorldDeltas() {
        var drag =
                GizmoDrag.beginTranslation(
                                CAMERA, new Vec3(0, 0, 5), new Vec3(1, 0, 0), .5, .5, 800, 600)
                        .orElseThrow();
        var moved = (GizmoDrag.Translation) drag.update(.6, .5).orElseThrow();
        close(1, moved.axisDistance(), 1e-4);
        close(1, moved.worldDelta().x(), 1e-4);
        var ortho =
                new Camera(
                                Vec3.ZERO,
                                CAMERA.sensor(),
                                Camera.Projection.ORTHOGRAPHIC,
                                Camera.Mode.ORTHOGRAPHIC,
                                5,
                                0,
                                2,
                                0)
                        .validated();
        var orthoDrag =
                GizmoDrag.beginTranslation(
                                ortho, new Vec3(0, 0, 5), new Vec3(1, 0, 0), .5, .5, 800, 600)
                        .orElseThrow();
        close(
                .2,
                ((GizmoDrag.Translation) orthoDrag.update(.6, .5).orElseThrow()).axisDistance(),
                1e-4);
        assertTrue(
                GizmoDrag.beginTranslation(
                                CAMERA, new Vec3(0, 0, 5), new Vec3(0, 0, 1), .5, .5, 800, 600)
                        .isEmpty());
    }

    @Test
    void ringDragIsSignedContinuousAndDegeneratePlaneIsUnavailable() {
        var drag =
                GizmoDrag.beginRotation(CAMERA, new Vec3(0, 0, 5), new Vec3(0, 0, 1), .6, .5)
                        .orElseThrow();
        close(90, ((GizmoDrag.Rotation) drag.update(.5, .6).orElseThrow()).degrees(), 1e-3);
        close(
                180,
                Math.abs(((GizmoDrag.Rotation) drag.update(.4, .5).orElseThrow()).degrees()),
                1e-3);
        var beyond = (GizmoDrag.Rotation) drag.update(.5, .4).orElseThrow();
        close(270, Math.abs(beyond.degrees()), 1e-3);
        assertTrue(
                GizmoDrag.beginRotation(CAMERA, new Vec3(0, 0, 5), new Vec3(1, 0, 0), .5, .5)
                        .isEmpty());
    }

    @Test
    void worldTranslationConvertsThroughParentAndInvalidRotationRemainsAtomic() {
        var document = new SceneDocument();
        var ids = new NodeId[2];
        document.transact(
                e -> {
                    ids[0] =
                            e.createNode(
                                    "parent",
                                    null,
                                    new Transform(
                                            Vec3.ZERO, new Vec3(0, 0, 90), new Vec3(2, 1, 1)));
                    ids[1] = e.createNode("child", ids[0], Transform.IDENTITY);
                });
        var snapshot = document.snapshot();
        var worldDelta = snapshot.worldTransform(ids[0]).vector(new Vec3(1, 0, 0));
        var translated = GizmoMath.translated(snapshot, ids[1], worldDelta);
        close(1, translated.position.x(), 1e-5);
        close(0, translated.position.y(), 1e-5);
        var rotated = GizmoMath.rotated(snapshot, ids[1], OverlayGeometry.Axis.Y, 30);
        long revision = snapshot.revision();
        boolean rejected = false;
        try {
            document.transact(e -> e.setLocalTransform(ids[1], rotated));
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(rejected);
        assertEquals(revision, document.snapshot().revision());
        assertEquals(Transform.IDENTITY, document.snapshot().requireNode(ids[1]).localTransform());
    }

    @Test
    void nontrivialRotationMovesGeometryAboutTheDisplayedWorldAxis() {
        var document = new SceneDocument();
        var id = new NodeId[1];
        document.transact(
                e ->
                        id[0] =
                                e.createNode(
                                        "rotated",
                                        null,
                                        new Transform(
                                                new Vec3(1, 2, 3),
                                                new Vec3(20, 30, 40),
                                                new Vec3(1, 1, 1))));
        var snapshot = document.snapshot();
        var before = snapshot.worldTransform(id[0]);
        var pivot = before.point(Vec3.ZERO);
        var worldAxis = normalized(before.vector(new Vec3(0, 1, 0)));
        var point = before.point(new Vec3(0, 0, 2));
        var candidate = GizmoMath.rotated(snapshot, id[0], OverlayGeometry.Axis.Y, 37);
        var expected =
                pivot.add(rodrigues(point.sub(pivot), worldAxis, (float) Math.toRadians(37)));
        var actual = candidate.point(new Vec3(0, 0, 2));
        close(expected.x(), actual.x(), 2e-5);
        close(expected.y(), actual.y(), 2e-5);
        close(expected.z(), actual.z(), 2e-5);
    }

    private static Vec3 rodrigues(Vec3 value, Vec3 axis, float angle) {
        return value.scale((float) Math.cos(angle))
                .add(axis.cross(value).scale((float) Math.sin(angle)))
                .add(axis.scale(axis.dot(value) * (1 - (float) Math.cos(angle))));
    }

    private static Vec3 normalized(Vec3 value) {
        double length =
                Math.sqrt(
                        (double) value.x() * value.x()
                                + (double) value.y() * value.y()
                                + (double) value.z() * value.z());
        return new Vec3(
                (float) (value.x() / length),
                (float) (value.y() / length),
                (float) (value.z() / length));
    }

    private static void close(double expected, double actual, double tolerance) {
        assertTrue(Math.abs(expected - actual) <= tolerance);
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
