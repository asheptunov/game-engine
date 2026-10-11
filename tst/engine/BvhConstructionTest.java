package engine;

import static harness.Assertions.assertEquals;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/** Construction changes spatial order, never original indices or conservative query bounds. */
public final class BvhConstructionTest {
    @Test
    void coincidentCentroidsFallBackAndRetainEveryIdentity() {
        var bounds = new PrimitiveBvh.Bounds[257];
        for (int i = 0; i < bounds.length; i++) {
            bounds[i] = bound(-1, -1, -1, 1, 1, 1);
        }
        for (boolean surfaceArea : new boolean[] {false, true}) {
            var tree = new PrimitiveBvh(bounds, surfaceArea, 1);
            var sorted = tree.order.clone();
            Arrays.sort(sorted);
            for (int i = 0; i < sorted.length; i++) {
                assertEquals(i, sorted[i]);
            }
            var cursor = new ObjectTraversal(true);
            cursor.reset(tree, bounds.length, 0, 0, 0, 0, 0, 1);
            int visited = 0;
            while (cursor.next(Float.POSITIVE_INFINITY) >= 0) {
                visited++;
            }
            assertEquals(bounds.length, visited);
        }
    }

    @Test
    void skewedRangesRespectDepthCapWithoutLosingEntries() {
        var bounds = new PrimitiveBvh.Bounds[256];
        for (int i = 0; i < bounds.length; i++) {
            float x = Math.scalb(1f, i / 2 - 70);
            bounds[i] = bound(x * .99f, -1, -1, x * 1.01f, 1, 1);
        }
        var tree = new PrimitiveBvh(bounds, true, 1);
        if (tree.maximumDepth > BvhBuilder.MAX_DEPTH) {
            throw new AssertionError("Construction exceeded traversal capacity");
        }
        var cursor = new ObjectTraversal(true);
        cursor.reset(tree, bounds.length, 0, 0, 0, 1, 0, 0);
        int visited = 0;
        while (cursor.next(Float.POSITIVE_INFINITY) >= 0) {
            visited++;
        }
        assertEquals(bounds.length, visited);
        var ids = new Integer[bounds.length];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = i;
        }
        var nodes = new ArrayList<PrimitiveBvh.Node>();
        new BvhBuilder(bounds, 1, true).build(nodes, ids, 0, ids.length, BvhBuilder.MAX_DEPTH);
        assertEquals(1, nodes.size());
        assertEquals(bounds.length, nodes.getFirst().to);
    }

    @Test
    void policiesAndLeafSizesAgreeForParallelInsideAndLimitedRays() {
        var random = new Random(917);
        var bounds = new PrimitiveBvh.Bounds[80];
        for (int i = 0; i < bounds.length; i++) {
            float x = random.nextFloat() * 8 - 4;
            float y = random.nextFloat() * 8 - 4;
            float z = random.nextFloat() * 8 - 4;
            bounds[i] = bound(x, y, z, x + random.nextFloat(), y + 1, z + 1);
        }
        for (int leafSize : new int[] {1, 2, 4, 8}) {
            var tree = new PrimitiveBvh(bounds, true, leafSize);
            var median = new PrimitiveBvh(bounds, false, leafSize);
            var near = new ObjectTraversal(true);
            var escape = new ObjectTraversal(false);
            for (int i = 0; i < 1000; i++) {
                float x = random.nextFloat() * 10 - 5;
                float y = random.nextFloat() * 10 - 5;
                float z = random.nextFloat() * 10 - 5;
                float dx = i % 2 == 0 ? 0 : 1;
                float limit = i % 3 == 0 ? Float.POSITIVE_INFINITY : 2;
                near.reset(tree, bounds.length, x, y, z, dx, 0, 1);
                escape.reset(median, bounds.length, x, y, z, dx, 0, 1);
                assertEquals(
                        nearest(bounds, near, x, y, z, dx, limit),
                        nearest(bounds, escape, x, y, z, dx, limit));
            }
        }
    }

    private static int nearest(
            PrimitiveBvh.Bounds[] bounds,
            ObjectTraversal cursor,
            float x,
            float y,
            float z,
            float dx,
            float limit) {
        int result = -1;
        int index;
        while ((index = cursor.next(limit)) >= 0) {
            float entry = bounds[index].entryDistance(x, y, z, dx, 0, 1, limit);
            if (entry < limit || (entry == limit && result >= 0 && index < result)) {
                result = index;
                limit = entry;
            }
        }
        return result;
    }

    private static PrimitiveBvh.Bounds bound(
            float x, float y, float z, float maxX, float maxY, float maxZ) {
        var bounds = new PrimitiveBvh.Bounds();
        bounds.include(new Vec3(x, y, z));
        bounds.include(new Vec3(maxX, maxY, maxZ));
        return bounds;
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
