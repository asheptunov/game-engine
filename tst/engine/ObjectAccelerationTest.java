package engine;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertNotNull;
import static harness.Assertions.assertNull;
import static harness.Assertions.assertSame;

import engine.objects.Rect;

import game.BlockWorld;
import game.GameLighting;
import game.GameNavigation;

import harness.SuiteRunner;
import harness.Test;

import math.Ray;
import math.Vec3;

import scenes.viewport.ScenePresets;

import java.util.Random;

/** Top-level traversal must preserve identities, limits, and transport, not just hit distance. */
public final class ObjectAccelerationTest {
    static ViewportState state(int size) {
        return new ViewportState(
                new Rect(new Vec3(-.5f, -.5f, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)),
                size,
                size);
    }

    static SceneInstance box(String name, float x, float y, float z) {
        return new SceneInstance(
                name,
                BoxGeometry.UNIT,
                new Transform(new Vec3(x, y, z), Vec3.ZERO, new Vec3(.5f, .5f, .5f)),
                Material.srgb("white", 0xffffff));
    }

    @Test
    void nearFirstVisitsCloseLeafAndPrunesDeferredSibling() {
        var bounds = new PrimitiveBvh.Bounds[64];
        for (int i = 0; i < bounds.length; i++) {
            var bound = new PrimitiveBvh.Bounds();
            bound.include(new Vec3(i * 2, -1, -1));
            bound.include(new Vec3(i * 2 + 1, 1, 1));
            bounds[i] = bound;
        }
        var tree = new PrimitiveBvh(bounds);
        var near = new ObjectTraversal(true);
        var escape = new ObjectTraversal(false);
        // From the high-X end, fixed preorder visits the farthest leaf first.
        near.reset(tree, bounds.length, 130, 0, 0, -1, 0, 0);
        escape.reset(tree, bounds.length, 130, 0, 0, -1, 0, 0);
        assertEquals(60, near.next(Float.POSITIVE_INFINITY));
        assertEquals(0, escape.next(Float.POSITIVE_INFINITY));
        int candidates = 1;
        while (near.next(3) >= 0) {
            candidates++;
        }
        assertEquals(4, candidates);
        // Reset discards every pending sibling from the preceding query.
        near.reset(tree, bounds.length, 0, 0, 0, 0, 1, 0);
        candidates = 0;
        while (near.next(1) >= 0) {
            candidates++;
        }
        assertEquals(4, candidates);
        near.reset(tree, bounds.length, -1, 0, 0, 0, 1, 0);
        assertEquals(-1, near.next(Float.POSITIVE_INFINITY));
    }

    @Test
    void touchingBlocksParallelInsideAndBoundedRaysMatchBrute() {
        var state = state(1);
        for (int z = 0; z < 8; z++) {
            for (int x = 0; x < 8; x++) {
                state.instances().add(box("b" + x + "-" + z, x, 0, z));
            }
        }
        var random = new Random(718);
        try (var tracer = new DirectRgbTracer(state)) {
            for (int i = 0; i < 2500; i++) {
                var origin =
                        new Vec3(
                                random.nextFloat() * 10 - 1,
                                i % 3 == 0 ? .5f : random.nextFloat() * 4 - 2,
                                random.nextFloat() * 10 - 1);
                var direction =
                        i % 4 == 0
                                ? new Vec3(0, 0, 1)
                                : new Vec3(
                                                random.nextFloat() - .5f,
                                                random.nextFloat() - .5f,
                                                random.nextFloat() - .5f)
                                        .normalized();
                var ray = new Ray(origin, direction);
                state.acceleration(true);
                var accelerated = tracer.intersect(ray);
                state.acceleration(false);
                var brute = tracer.intersect(ray);
                compare(accelerated, brute);
                float limit = i % 2 == 0 ? Float.POSITIVE_INFINITY : random.nextFloat() * 5;
                var source = brute == null ? null : brute.primitive;
                boolean blocked =
                        tracer.occluded(
                                origin.x(),
                                origin.y(),
                                origin.z(),
                                direction.x(),
                                direction.y(),
                                direction.z(),
                                limit,
                                source);
                state.acceleration(true);
                assertEquals(
                        blocked,
                        tracer.occluded(
                                origin.x(),
                                origin.y(),
                                origin.z(),
                                direction.x(),
                                direction.y(),
                                direction.z(),
                                limit,
                                source));
            }
        }
    }

    private static void compare(DirectRgbTracer.Hit a, DirectRgbTracer.Hit b) {
        if (b == null) {
            assertNull(a);
            return;
        }
        assertNotNull(a);
        assertEquals(b.distance, a.distance);
        assertSame(b.primitive, a.primitive);
        assertSame(b.object, a.object);
        assertEquals(b.frontFace, a.frontFace);
        assertEquals(b.nx, a.nx);
        assertEquals(b.ny, a.ny);
        assertEquals(b.nz, a.nz);
    }

    @Test
    void crossObjectTiesKeepSceneOrderDespiteSpatialSortingAndEdits() {
        var state = state(1);
        // Both boxes meet at x=.5. The earlier object lies to the right, so a spatial
        // traversal visits it later; the seam must still choose the earlier object.
        state.instances().add(box("first", 1, 0, 3));
        state.instances().add(box("second", 0, 0, 3));
        for (int i = 0; i < 32; i++) {
            state.instances().add(box("far" + i, i - 16, 0, 20));
        }
        var ray = new Ray(new Vec3(.5f, 0, 0), new Vec3(0, 0, 1));
        try (var tracer = new DirectRgbTracer(state)) {
            assertEquals("first", tracer.intersect(ray).primitive.objectId);
            long builds = tracer.geometryBuilds;
            state.eye(new Vec3(1, 2, -2));
            tracer.intersect(ray);
            assertEquals(builds, tracer.geometryBuilds);
            state.instances().set(0, box("moved", 30, 0, 3));
            assertEquals("second", tracer.intersect(ray).primitive.objectId);
            assertEquals(builds + 1, tracer.geometryBuilds);
            state.instances().remove(1);
            assertEquals("far16", tracer.intersect(ray).primitive.objectId);
            state.instances().clear();
            assertNull(tracer.intersect(ray));
            state.instances().add(box("single", 0, 0, 3));
            assertEquals("single", tracer.intersect(ray).primitive.objectId);
        }
    }

    @Test
    void seededGlassVolumesEmittersAndTexturedSunSkyAgree() {
        for (String preset :
                new String[] {
                    "glass", "glass-inside", "volume-room", "rough-room", "mesh-room", "game"
                }) {
            var state = state(24);
            if (preset.equals("game")) {
                state.instances().addAll(BlockWorld.landscape().snapshot().instances());
                state.camera(new GameNavigation().view().camera());
                state.lights().add(GameLighting.defaults().sun());
                state.sky(GameLighting.defaults().sky());
                state.pathDepth(2);
            } else {
                ScenePresets.load(state, preset);
                // Force the top-level path while retaining each preset's optical setup.
                for (int i = 0; i < 20; i++) {
                    state.instances().add(box("remote" + i, 100 + i * 2, 0, 100));
                }
            }
            state.workers(1);
            state.samplesPerFrame(2);
            state.sampleTarget(2);
            var reference = state.renderSnapshot();
            reference.acceleration(false);
            try (var fast = new DirectRgbTracer(state, 1);
                    var brute = new DirectRgbTracer(reference)) {
                while (reference.accumulatedSamples() < 2) {
                    brute.trace();
                }
                while (state.accumulatedSamples() < 2) {
                    fast.trace();
                }
                if (!java.util.Objects.deepEquals(brute.trace(), fast.trace())) {
                    throw new AssertionError("Seeded image differs for " + preset);
                }
            }
        }
    }

    @Test
    void movingInstancesRetainStaticPreparationAndMatchFreshTrace() {
        var state = state(24);
        state.instances().addAll(game.AdventureWorld.create().instances());
        state.instances().add(box("moving", 0, 0, 3));
        state.camera(new GameNavigation().view().camera());
        state.lights().add(GameLighting.defaults().sun());
        state.sky(GameLighting.defaults().sky());
        state.workers(1);
        state.samplesPerFrame(1);
        state.sampleTarget(1);
        try (var retained = new DirectRgbTracer(state)) {
            retained.trace();
            long builds = retained.preparedObjectBuilds;
            for (int frame = 0; frame < 4; frame++) {
                state.instances().set(state.instances().size() - 1, box("moving", frame, 0, 4));
                var actual = retained.trace();
                assertEquals(builds + frame + 1, retained.preparedObjectBuilds);
                try (var fresh = new DirectRgbTracer(state.renderSnapshot())) {
                    assertEquals(fresh.trace(), actual);
                }
            }
            state.instances().removeLast();
            var actual = retained.trace();
            assertEquals(builds + 4, retained.preparedObjectBuilds);
            try (var fresh = new DirectRgbTracer(state.renderSnapshot())) {
                assertEquals(fresh.trace(), actual);
            }
        }
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
