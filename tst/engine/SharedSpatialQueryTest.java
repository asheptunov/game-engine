package engine;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertFalse;
import static harness.Assertions.assertTrue;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

public class SharedSpatialQueryTest {
    private record Built(
            SceneDocument document,
            NodeId sphere,
            NodeId box,
            GeometryId boxGeometry,
            MaterialId material) {}

    private static Built scene() {
        var document = new SceneDocument();
        var ids = new Object[4];
        document.transact(
                edit -> {
                    var sphereGeometry =
                            edit.createGeometry(
                                    "sphere",
                                    PolygonMesh.approximateSphere(
                                            new AnalyticSphere(Vec3.ZERO, 1), 12));
                    var boxGeometry = edit.createGeometry("box", PolygonMesh.unitBox());
                    var material =
                            edit.createMaterial(
                                    "gray", new Material("gray", new Vec3(.5f, .5f, .5f)));
                    var sphere = edit.createNode("sphere", null, Transform.IDENTITY);
                    var box =
                            edit.createNode(
                                    "box",
                                    null,
                                    new Transform(new Vec3(4, 0, 0), Vec3.ZERO, new Vec3(1, 1, 1)));
                    edit.assignGeometry(sphere, sphereGeometry, material);
                    edit.assignGeometry(box, boxGeometry, material);
                    ids[0] = sphere;
                    ids[1] = box;
                    ids[2] = boxGeometry;
                    ids[3] = material;
                });
        return new Built(
                document,
                (NodeId) ids[0],
                (NodeId) ids[1],
                (GeometryId) ids[2],
                (MaterialId) ids[3]);
    }

    @Test
    void concurrentViewsShareOneImmutableQueryAndMatchIndependentReference() throws Exception {
        var snapshot = scene().document().snapshot();
        try (var workers = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Callable<SpatialQuery>>();
            for (int worker = 0; worker < 8; worker++) {
                tasks.add(
                        () -> {
                            var query = SpatialQuery.prepare(snapshot);
                            checkReference(snapshot, query);
                            return query;
                        });
            }
            var results = workers.invokeAll(tasks);
            var first = results.getFirst().get();
            for (var result : results) {
                assertTrue(first == result.get());
            }
            assertTrue(first == SpatialQuery.prepare(snapshot, true));
        }
    }

    @Test
    void geometryTransformMaterialAndReplacementInvalidateOnlyMatchingObjects() {
        var built = scene();
        var document = built.document();
        var original = document.snapshot();
        var originalQuery = SpatialQuery.prepare(original);
        var spherePrepared = original.queryObjects().get(built.sphere()).prepared();
        var boxPrepared = original.queryObjects().get(built.box()).prepared();
        var originalHit = originalQuery.nearest(new Vec3(0, 0, 5), new Vec3(0, 0, -1));
        var moved =
                document.transact(
                        edit -> edit.translateVertex(built.boxGeometry(), 0, new Vec3(.01f, 0, 0)));
        var movedQuery = SpatialQuery.prepare(moved);
        assertFalse(originalQuery == movedQuery);
        assertTrue(spherePrepared == moved.queryObjects().get(built.sphere()).prepared());
        assertFalse(boxPrepared == moved.queryObjects().get(built.box()).prepared());
        checkReference(moved, movedQuery);
        assertEquals(originalHit, originalQuery.nearest(new Vec3(0, 0, 5), new Vec3(0, 0, -1)));

        var renamed = document.transact(edit -> edit.renameNode(built.sphere(), "renamed"));
        var renamedQuery = SpatialQuery.prepare(renamed);
        assertTrue(spherePrepared == renamed.queryObjects().get(built.sphere()).prepared());
        checkReference(renamed, renamedQuery);
        var transformed =
                document.transact(
                        edit ->
                                edit.setLocalTransform(
                                        built.sphere(),
                                        new Transform(
                                                new Vec3(0, 0, 1), Vec3.ZERO, new Vec3(1, 1, 1))));
        checkReference(transformed, SpatialQuery.prepare(transformed));
        assertFalse(spherePrepared == transformed.queryObjects().get(built.sphere()).prepared());
        var materialChanged =
                document.transact(
                        edit ->
                                edit.replaceMaterial(
                                        built.material(),
                                        new Material("gray", new Vec3(.2f, .3f, .4f))));
        checkReference(materialChanged, SpatialQuery.prepare(materialChanged));
        assertFalse(
                transformed.queryObjects().get(built.sphere()).prepared()
                        == materialChanged.queryObjects().get(built.sphere()).prepared());
        var restored = document.replace(original);
        var restoredQuery = SpatialQuery.prepare(restored);
        assertFalse(originalQuery == restoredQuery);
        checkReference(restored, restoredQuery);
        var removed = document.transact(edit -> edit.deleteSubtree(built.box()));
        checkReference(removed, SpatialQuery.prepare(removed));
        assertEquals(1, removed.queryObjects().size());
        assertFalse(removed.queryObjects().containsKey(built.box()));
    }

    @Test
    void reclaimedCacheRebuildsWithoutChangingOldQueryOrRetainingPreparedObjects()
            throws Exception {
        var snapshot = scene().document().snapshot();
        var original = SpatialQuery.prepare(snapshot);
        var field = SceneSnapshot.class.getDeclaredField("preparedQuery");
        field.setAccessible(true);
        // Model reclamation deterministically rather than depending on when the GC runs.
        ((Reference<?>) field.get(snapshot)).clear();
        assertTrue(snapshot.queryObjects().isEmpty());
        var rebuilt = SpatialQuery.prepare(snapshot);
        assertFalse(original == rebuilt);
        checkReference(snapshot, original);
        checkReference(snapshot, rebuilt);
        assertTrue(rebuilt == SpatialQuery.prepare(snapshot));
    }

    private static void checkReference(SceneSnapshot snapshot, SpatialQuery accelerated) {
        var reference = SpatialQuery.prepare(snapshot, false);
        for (int x = -2; x <= 6; x++) {
            for (int y = -2; y <= 2; y++) {
                var origin = new Vec3(x * .75f, y * .4f, 5);
                var direction = new Vec3(0, 0, -1);
                assertEquals(
                        reference.nearest(origin, direction),
                        accelerated.nearest(origin, direction));
            }
        }
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
