package engine;

import static harness.Assertions.*;

import engine.objects.Rect;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

public class CameraProjectorTest {
    private static final Rect PLANE =
            new Rect(new Vec3(-1, -1, 1), new Vec3(2, 0, 0), new Vec3(0, 2, 0));

    @Test
    void perspectiveProjectionMatchesReferencePickingRaysIncludingFiniteAperture() {
        for (var camera :
                new Camera[] {
                    new Camera(Vec3.ZERO, PLANE).validated(),
                    new Camera(Vec3.ZERO, PLANE, Camera.Mode.LENS, 5, .3f, 0).validated(),
                    new Camera(
                                    Vec3.ZERO,
                                    new Rect(
                                            new Vec3(-1.4f, -.8f, 2),
                                            new Vec3(2.2f, .2f, 0),
                                            new Vec3(.2f, 1.7f, 0)))
                            .validated()
                })
            for (float u : new float[] {.1f, .5f, .9f})
                for (float v : new float[] {.15f, .55f, .85f}) {
                    var projector = CameraProjector.of(camera);
                    var ray = projector.referenceRay(u, v);
                    var point = ray.origin().add(ray.direction().scale(7));
                    var projected = projector.project(point).orElseThrow();
                    close(u, projected.u(), 2e-6);
                    close(v, projected.v(), 2e-6);
                    var publicRay = SpatialQuery.screenRay(camera, u, v);
                    assertEquals(publicRay, ray);
                }
    }

    @Test
    void orthographicProjectionAndScaleMatchReferenceRays() {
        var camera =
                new Camera(
                                Vec3.ZERO,
                                PLANE,
                                Camera.Projection.ORTHOGRAPHIC,
                                Camera.Mode.ORTHOGRAPHIC,
                                5,
                                .25f,
                                4,
                                .25f)
                        .validated();
        var projector = CameraProjector.of(camera);
        for (float u : new float[] {0, .3f, 1})
            for (float v : new float[] {0, .7f, 1}) {
                var ray = projector.referenceRay(u, v);
                var point = ray.origin().add(ray.direction().scale(8));
                var projected = projector.project(point).orElseThrow();
                close(u, projected.u(), 2e-6);
                close(v, projected.v(), 2e-6);
            }
        close(4, projector.screenHeightAt(new Vec3(0, 0, 2)).orElseThrow(), 1e-6);
        close(4, projector.screenHeightAt(new Vec3(0, 0, 20)).orElseThrow(), 1e-6);
    }

    @Test
    void nearAndViewportClippingRejectBehindAndRetainVisiblePortions() {
        var projector = CameraProjector.of(new Camera(Vec3.ZERO, PLANE).validated());
        assertTrue(projector.project(new Vec3(0, 0, -1)).isEmpty());
        assertTrue(projector.clipAndProject(new Vec3(0, 0, -2), new Vec3(0, 0, -1)).isEmpty());
        var near = projector.clipAndProject(new Vec3(0, 0, -1), new Vec3(0, 0, 2)).orElseThrow();
        close(.5, near.start().u(), 1e-6);
        close(.5, near.end().u(), 1e-6);
        var horizontal =
                projector.clipAndProject(new Vec3(-8, 0, 4), new Vec3(8, 0, 4)).orElseThrow();
        close(0, horizontal.start().u(), 1e-6);
        close(1, horizontal.end().u(), 1e-6);
        assertTrue(horizontal.start().insideViewport());
        assertTrue(horizontal.end().insideViewport());
        var depth = projector.clipAndProject(new Vec3(0, 0, 2), new Vec3(16, 0, 8)).orElseThrow();
        close(1, depth.end().u(), 1e-6);
        close(3.2, depth.end().axialDepth(), 1e-5);
        assertTrue(projector.clipAndProject(new Vec3(8, 8, 4), new Vec3(9, 9, 5)).isEmpty());
    }

    @Test
    void perspectiveScreenScaleGrowsWithAxialDepth() {
        var projector = CameraProjector.of(new Camera(Vec3.ZERO, PLANE).validated());
        close(4, projector.screenHeightAt(new Vec3(0, 0, 2)).orElseThrow(), 1e-6);
        close(10, projector.screenHeightAt(new Vec3(0, 0, 5)).orElseThrow(), 1e-6);
    }

    private static void close(double expected, double actual, double tolerance) {
        assertTrue(Math.abs(expected - actual) <= tolerance);
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
