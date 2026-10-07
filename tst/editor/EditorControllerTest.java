package editor;

import engine.*;
import harness.Test;
import math.Vec3;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static harness.Assertions.*;

public class EditorControllerTest {
    public static void main(String[] args) { harness.SuiteRunner.runThis(); }

    @Test public void operationsCommandsHistorySharingAndComponentsUseOneController() throws Exception {
        var controller = onEdt(EditorController::new);
        try {
            var original = onEdt(controller::state); var group = original.snapshot().nodes().stream().filter(n -> n.label().equals("Composition")).findFirst().orElseThrow();
            onEdt(() -> controller.select(group.id())); var commands = new EditorCommandProcessor(controller);
            String created = onEdt(() -> commands.execute("create box")); assertTrue(created.contains("Create Box"));
            var box = onEdt(() -> controller.state().snapshot().requireNode(controller.selection())); assertEquals(group.id(), box.parentId());
            long before = onEdt(() -> controller.snapshot().revision());
            assertTrue(onEdt(() -> commands.execute("transform 1 2 3 0 20 0 1 1 1 extra")).startsWith("Error:"));
            assertEquals(before, onEdt(() -> controller.snapshot().revision()));
            assertTrue(onEdt(() -> commands.execute("transform 1 2 3 0 20 0 1 1 1")).contains("Apply transform"));
            var transformed = onEdt(() -> controller.snapshot().requireNode(box.id()).localTransform()); assertEquals(new Vec3(1, 2, 3), transformed.position);
            onEdt(controller::undo); assertEquals(Transform.IDENTITY, onEdt(() -> controller.snapshot().requireNode(box.id()).localTransform()));
            onEdt(controller::redo); assertEquals(transformed, onEdt(() -> controller.snapshot().requireNode(box.id()).localTransform()));

            var material = box.geometry().materialId(); onEdt(controller::duplicateSelection); var copy = onEdt(() -> controller.snapshot().requireNode(controller.selection()));
            assertNotEquals(box.id(), copy.id()); assertEquals(box.geometry(), copy.geometry());
            onEdt(() -> controller.makeMaterialUnique(copy.id())); var unique = onEdt(() -> controller.snapshot().requireNode(copy.id()));
            assertNotEquals(material, unique.geometry().materialId());
            assertTrue(onEdt(() -> commands.execute("material edit mirror .3 .4 .5 .2 1.5")).contains("Edit shared material"));
            assertEquals(Material.Kind.MIRROR, onEdt(() -> controller.snapshot().requireMaterial(unique.geometry().materialId()).material().kind()));
            assertEquals(Material.Kind.DIFFUSE, onEdt(() -> controller.snapshot().requireMaterial(material).material().kind()));

            assertTrue(onEdt(() -> commands.execute("light set 1 .8 .6 30")).contains("point light"));
            assertEquals(30f, onEdt(() -> controller.snapshot().requireNode(unique.id()).light().intensity()));
            assertTrue(onEdt(() -> commands.execute("camera set perspective 55 4 0")).contains("camera"));
            assertEquals(55f, onEdt(() -> controller.snapshot().requireNode(unique.id()).camera().camera().fov()));
        } finally { onEdt(() -> { controller.close(); return null; }); }
    }

    @Test public void failedEditAndStalePickPreservePublishedWorld() throws Exception {
        var controller = onEdt(EditorController::new);
        try {
            var snapshot = onEdt(controller::snapshot); var cameraId = snapshot.nodes().stream().filter(n -> n.camera() != null).findFirst().orElseThrow().id();
            var query = SpatialQuery.prepare(snapshot); RayHit hit = null;
            for (int y = 1; y < 9 && hit == null; y++) for (int x = 1; x < 9 && hit == null; x++) hit = query.pick(snapshot.camera(cameraId), x / 10f, y / 10f).orElse(null);
            assertNotNull(hit); var accepted = hit;
            assertTrue(onEdt(() -> controller.acceptPick(accepted, snapshot.revision()))); assertEquals(hit.nodeId(), onEdt(controller::selection));
            var selected = hit.nodeId(); long before = onEdt(() -> controller.snapshot().revision());
            assertFalse(onEdt(() -> controller.reparent(selected, selected))); assertEquals(before, onEdt(() -> controller.snapshot().revision())); assertEquals(selected, onEdt(controller::selection));
            onEdt(() -> controller.rename(selected, "Edited after publication")); assertFalse(onEdt(() -> controller.acceptPick(accepted, snapshot.revision()))); assertEquals(selected, onEdt(controller::selection));
            long current = onEdt(() -> controller.snapshot().revision()); assertTrue(onEdt(() -> controller.acceptPick(null, current))); assertNull(onEdt(controller::selection));
        } finally { onEdt(() -> { controller.close(); return null; }); }
    }

    @Test public void asyncSaveTracksDiskBaselineAndLoadBlocksMutation() throws Exception {
        var jobs = new ManualExecutor(); var storage = new MemoryStorage(); var controller = onEdt(() -> new EditorController(storage, jobs));
        try {
            var id = onEdt(controller::selection); onEdt(() -> controller.rename(id, "Saved B")); var b = onEdt(controller::snapshot);
            var save = onEdt(() -> controller.save(Path.of("memory.scene.xml"))); onEdt(() -> controller.rename(id, "Newer C"));
            jobs.runNext(); flushEdt(); assertTrue(save.get(2, TimeUnit.SECONDS)); assertTrue(onEdt(controller::dirty));
            onEdt(controller::undo); assertFalse(onEdt(controller::dirty)); assertTrue(storage.saved.sameContent(b));

            storage.loaded = b; var load = onEdt(() -> controller.load(Path.of("loaded.scene.xml")));
            assertFalse(onEdt(() -> controller.rename(id, "Must be blocked"))); jobs.runNext(); flushEdt(); assertTrue(load.get(2, TimeUnit.SECONDS));
            assertEquals("Saved B", onEdt(() -> controller.snapshot().requireNode(id).label()));
            var beforeFailure = onEdt(controller::snapshot); storage.failLoad = true;
            var failed = onEdt(() -> controller.load(Path.of("broken.scene.xml"))); jobs.runNext(); flushEdt();
            assertFalse(failed.get(2, TimeUnit.SECONDS)); assertSame(beforeFailure, onEdt(controller::snapshot));
            assertTrue(onEdt(() -> controller.state().status()).startsWith("Load failed:"));
        } finally { onEdt(() -> { controller.close(); return null; }); }
    }

    @Test public void sceneContentComparisonIncludesMeshSourceIdentity() throws Exception {
        var geometryId = GeometryId.random(); var materialId = MaterialId.random(); var nodeId = NodeId.random();
        var first = onEdt(() -> meshScene(geometryId, materialId, nodeId, 7)); var second = onEdt(() -> meshScene(geometryId, materialId, nodeId, 8));
        assertFalse(first.sameContent(second));
    }

    private static SceneSnapshot meshScene(GeometryId geometry, MaterialId material, NodeId node, long face) {
        var document = new SceneDocument(); document.transact(e -> {
            e.createGeometry(geometry, "mesh", TriangleMesh.surface(List.of(new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)), new int[]{0, 1, 2}, new long[]{face}));
            e.createMaterial(material, "mat", Material.srgb("mat", 0xffffff)); e.createNode(node, "node", null, Transform.IDENTITY); e.assignGeometry(node, geometry, material);
        }); return document.snapshot();
    }

    private static final class MemoryStorage implements EditorController.Storage {
        volatile SceneSnapshot loaded, saved; volatile boolean failLoad;
        @Override public SceneSnapshot load(Path path) throws IOException { if (failLoad || loaded == null) throw new IOException("missing"); return loaded; }
        @Override public void save(Path path, SceneSnapshot snapshot) { saved = snapshot; }
    }
    private static final class ManualExecutor extends AbstractExecutorService {
        private final BlockingQueue<Runnable> jobs = new LinkedBlockingQueue<>(); private boolean shutdown;
        @Override public void shutdown() { shutdown = true; } @Override public List<Runnable> shutdownNow() { shutdown = true; return List.copyOf(jobs); }
        @Override public boolean isShutdown() { return shutdown; } @Override public boolean isTerminated() { return shutdown && jobs.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
        @Override public void execute(Runnable command) { jobs.add(command); }
        void runNext() throws Exception { var job = jobs.poll(2, TimeUnit.SECONDS); assertNotNull(job); job.run(); }
    }
    private static void flushEdt() throws Exception { onEdt(() -> null); }
    private static <T> T onEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call(); var value = new AtomicReference<T>(); var error = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> { try { value.set(action.call()); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new RuntimeException(error.get()); return value.get();
    }
}
