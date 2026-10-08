package editor;

import engine.*;
import editor.overlay.GizmoMath;
import editor.overlay.OverlayGeometry;
import harness.Test;
import math.Vec3;
import engine.objects.Rect;

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
            var other = snapshot.nodes().stream().map(SceneNode::id).filter(id -> !id.equals(accepted.nodeId())).findFirst().orElseThrow();
            long supersededByTree = onEdt(() -> controller.beginPick(snapshot.revision())); onEdt(() -> controller.select(other));
            assertFalse(onEdt(() -> controller.acceptPick(accepted, snapshot.revision(), supersededByTree))); assertEquals(other, onEdt(controller::selection));
            long firstView = onEdt(() -> controller.beginPick(snapshot.revision())); long secondView = onEdt(() -> controller.beginPick(snapshot.revision()));
            assertFalse(onEdt(() -> controller.acceptPick(accepted, snapshot.revision(), firstView)));
            assertTrue(onEdt(() -> controller.acceptPick(accepted, snapshot.revision(), secondView))); assertEquals(hit.nodeId(), onEdt(controller::selection));
            var selected = hit.nodeId(); long before = onEdt(() -> controller.snapshot().revision());
            assertFalse(onEdt(() -> controller.reparent(selected, selected))); assertEquals(before, onEdt(() -> controller.snapshot().revision())); assertEquals(selected, onEdt(controller::selection));
            onEdt(() -> controller.rename(selected, "Edited after publication")); assertFalse(onEdt(() -> controller.acceptPick(accepted, snapshot.revision()))); assertEquals(selected, onEdt(controller::selection));
            long current = onEdt(() -> controller.snapshot().revision()); assertTrue(onEdt(() -> controller.acceptPick(null, current))); assertNull(onEdt(controller::selection));
        } finally { onEdt(() -> { controller.close(); return null; }); }
    }

    @Test public void faceModeConvertsMakesUniqueExtrudesAndReconcilesAcrossHistoryAndLoad() throws Exception {
        var jobs = new ManualExecutor(); var storage = new MemoryStorage(); var controller = onEdt(() -> new EditorController(storage, jobs));
        try {
            var commands = new EditorCommandProcessor(controller); var initial = onEdt(controller::snapshot);
            var cameraId = initial.nodes().stream().filter(node -> node.camera() != null).findFirst().orElseThrow().id();
            var query = SpatialQuery.prepare(initial); RayHit hit = null;
            for (int y = 1; y < 10 && hit == null; y++) for (int x = 1; x < 10 && hit == null; x++)
                hit = query.pick(initial.camera(cameraId), x / 10f, y / 10f).orElse(null);
            assertNotNull(hit); var picked = hit;

            assertTrue(onEdt(() -> commands.execute("mode face")).startsWith("Face selection mode"));
            assertEquals(EditorController.SelectionMode.FACE, onEdt(controller::selectionMode));
            long intent = onEdt(() -> controller.beginPick(initial.revision()));
            assertTrue(onEdt(() -> controller.acceptPick(picked, initial.revision(), intent)));
            assertEquals(picked.nodeId(), onEdt(controller::selection)); assertNotNull(onEdt(controller::faceSelection));

            long staleIntent = onEdt(() -> controller.beginPick(initial.revision()));
            assertTrue(onEdt(() -> controller.setSelectionMode(EditorController.SelectionMode.OBJECT)));
            assertFalse(onEdt(() -> controller.acceptPick(picked, initial.revision(), staleIntent)));
            assertTrue(onEdt(() -> controller.setSelectionMode(EditorController.SelectionMode.FACE)));

            assertTrue(onEdt(()->commands.execute("create box")).contains("Create Box"));var selected=onEdt(controller::selection);
            assertTrue(onEdt(() -> commands.execute("mesh convert")).contains("editable mesh"));
            var converted = onEdt(controller::snapshot); var convertedNode = converted.requireNode(selected);
            var editable = (PolygonMesh) converted.requireGeometry(convertedNode.geometry().geometryId()).geometry();
            long faceId = editable.faces().getFirst().id();
            assertTrue(onEdt(() -> commands.execute("face select " + faceId)).contains("Selected face"));
            var selectedFaceBeforeNoop=onEdt(controller::faceSelection);long sameModeIntent=onEdt(()->controller.beginPick(controller.snapshot().revision()));
            assertTrue(onEdt(()->controller.setSelectionMode(EditorController.SelectionMode.FACE)));assertEquals(selectedFaceBeforeNoop,onEdt(controller::faceSelection));
            assertTrue(onEdt(()->controller.acceptPick(null,controller.snapshot().revision(),sameModeIntent)));
            assertTrue(onEdt(() -> commands.execute("face select " + faceId)).contains("Selected face"));

            assertTrue(onEdt(controller::duplicateSelection)); var copy = onEdt(controller::selection);
            assertEquals(convertedNode.geometry().geometryId(), onEdt(() -> controller.snapshot().requireNode(copy).geometry().geometryId()));
            assertTrue(onEdt(() -> commands.execute("face select " + faceId)).contains("Selected face"));
            assertTrue(onEdt(() -> commands.execute("mesh unique")).contains("unique"));
            var uniqueGeometry = onEdt(() -> controller.snapshot().requireNode(copy).geometry().geometryId());
            assertNotEquals(convertedNode.geometry().geometryId(), uniqueGeometry);
            assertEquals(uniqueGeometry, onEdt(controller::faceSelection).geometryId());

            int facesBefore = ((PolygonMesh) onEdt(() -> controller.snapshot().requireGeometry(uniqueGeometry).geometry())).faces().size();
            long beforeBadCommand = onEdt(() -> controller.snapshot().revision());
            assertTrue(onEdt(() -> commands.execute("face extrude .4 extra")).startsWith("Error:"));
            assertEquals(beforeBadCommand, onEdt(() -> controller.snapshot().revision()));
            assertTrue(onEdt(() -> commands.execute("face extrude .4")).contains("Extrude face"));
            var extruded = (PolygonMesh) onEdt(() -> controller.snapshot().requireGeometry(uniqueGeometry).geometry());
            assertTrue(extruded.faces().size() > facesBefore); assertEquals(faceId, onEdt(controller::faceSelection).faceId());

            var savedSnapshot = onEdt(controller::snapshot); var save = onEdt(() -> controller.save(Path.of("mesh.scene.xml")));
            jobs.runNext(); flushEdt(); assertTrue(save.get(2, TimeUnit.SECONDS)); assertTrue(storage.saved.sameContent(savedSnapshot));

            assertTrue(onEdt(controller::undo));
            assertEquals(facesBefore, ((PolygonMesh) onEdt(() -> controller.snapshot().requireGeometry(uniqueGeometry).geometry())).faces().size());
            assertEquals(faceId, onEdt(controller::faceSelection).faceId());
            assertTrue(onEdt(controller::redo));assertTrue(((PolygonMesh)onEdt(()->controller.snapshot().requireGeometry(uniqueGeometry).geometry())).faces().size()>facesBefore);
            assertEquals(faceId,onEdt(controller::faceSelection).faceId());assertTrue(onEdt(controller::undo));
            assertTrue(onEdt(controller::undo));
            assertEquals(convertedNode.geometry().geometryId(), onEdt(controller::faceSelection).geometryId());
            assertTrue(onEdt(controller::undo)); assertNull(onEdt(controller::faceSelection));

            storage.loaded = savedSnapshot; var load = onEdt(() -> controller.load(Path.of("mesh.scene.xml")));
            jobs.runNext(); flushEdt(); assertTrue(load.get(2, TimeUnit.SECONDS)); assertNull(onEdt(controller::faceSelection));
            assertEquals(EditorController.SelectionMode.FACE, onEdt(controller::selectionMode));
            assertTrue(onEdt(controller::snapshot).geometryAssets().stream().anyMatch(asset -> asset.geometry() instanceof PolygonMesh));
            var loadedNode=onEdt(()->controller.snapshot().nodes().stream().filter(node->node.geometry()!=null&&controller.snapshot().requireGeometry(node.geometry().geometryId()).geometry() instanceof PolygonMesh).findFirst().orElseThrow());
            onEdt(()->controller.select(loadedNode.id()));var loadedMesh=(PolygonMesh)onEdt(()->controller.snapshot().requireGeometry(loadedNode.geometry().geometryId()).geometry());
            long loadedFace=loadedMesh.faces().getFirst().id();int loadedFaces=loadedMesh.faces().size();assertTrue(onEdt(()->controller.selectFace(loadedFace)));assertTrue(onEdt(()->controller.extrudeSelectedFace(.1f)));
            assertTrue(((PolygonMesh)onEdt(()->controller.snapshot().requireGeometry(loadedNode.geometry().geometryId()).geometry())).faces().size()>loadedFaces);
            var help = onEdt(() -> commands.execute("help"));
            assertTrue(help.contains("mode object|vertex|edge|face")); assertTrue(help.contains("vertex move")); assertTrue(help.contains("edge move"));assertTrue(help.contains("face extrude"));
        } finally { onEdt(() -> { controller.close(); return null; }); }
    }

    @Test public void directVertexEdgeEditingSharesValidatesGroupsAndReconcilesStableSelections() throws Exception {
        var controller=onEdt(EditorController::new);
        try{
            var sphereNode=onEdt(()->controller.snapshot().nodes().stream().filter(node->node.label().equals("Terracotta sphere")).findFirst().orElseThrow());
            onEdt(()->controller.select(sphereNode.id()));
            assertTrue(onEdt(()->controller.convertGeometryToEditable(sphereNode.id())));
            var sharedGeometry=onEdt(()->controller.snapshot().requireNode(sphereNode.id()).geometry().geometryId());
            assertTrue(onEdt(controller::duplicateSelection));var duplicate=onEdt(controller::selection);
            assertEquals(sharedGeometry,onEdt(()->controller.snapshot().requireNode(duplicate).geometry().geometryId()));

            long beforeModeRevision=onEdt(()->controller.snapshot().revision());boolean beforeModeDirty=onEdt(controller::dirty);
            assertTrue(onEdt(()->controller.setSelectionMode(EditorController.SelectionMode.VERTEX)));
            assertEquals(beforeModeRevision,onEdt(()->controller.snapshot().revision()));assertEquals(beforeModeDirty,onEdt(controller::dirty));
            var sharedMesh=(PolygonMesh)onEdt(()->controller.snapshot().requireGeometry(sharedGeometry).geometry());long vertexId=sharedMesh.editableVertices().getFirst().id();
            assertTrue(onEdt(()->controller.selectVertex(vertexId)));var beforePosition=sharedMesh.requireVertex(vertexId).position();
            assertTrue(onEdt(()->controller.translateSelectedElement(new Vec3(0,.05f,0))));
            var movedShared=(PolygonMesh)onEdt(()->controller.snapshot().requireGeometry(sharedGeometry).geometry());
            assertEquals(beforePosition.add(new Vec3(0,.05f,0)),movedShared.requireVertex(vertexId).position());
            assertEquals(sharedGeometry,onEdt(()->controller.snapshot().requireNode(sphereNode.id()).geometry().geometryId()));

            assertTrue(onEdt(()->controller.makeGeometryUnique(duplicate)));var uniqueGeometry=onEdt(()->controller.snapshot().requireNode(duplicate).geometry().geometryId());
            assertNotEquals(sharedGeometry,uniqueGeometry);assertEquals(uniqueGeometry,onEdt(controller::vertexSelection).geometryId());
            assertTrue(onEdt(()->controller.translateSelectedElement(new Vec3(.04f,0,0))));
            assertNotEquals(((PolygonMesh)onEdt(()->controller.snapshot().requireGeometry(uniqueGeometry).geometry())).requireVertex(vertexId).position(),
                    ((PolygonMesh)onEdt(()->controller.snapshot().requireGeometry(sharedGeometry).geometry())).requireVertex(vertexId).position());
            assertTrue(onEdt(controller::undo));assertEquals(uniqueGeometry,onEdt(controller::vertexSelection).geometryId());assertTrue(onEdt(controller::redo));

            var uniqueMesh=(PolygonMesh)onEdt(()->controller.snapshot().requireGeometry(uniqueGeometry).geometry());var edge=uniqueMesh.edges().getFirst();
            assertTrue(onEdt(()->controller.setSelectionMode(EditorController.SelectionMode.EDGE)));
            assertTrue(onEdt(()->controller.selectEdge(edge.secondVertexId(),edge.firstVertexId())));
            assertEquals(edge.firstVertexId(),onEdt(controller::edgeSelection).firstVertexId());
            assertTrue(onEdt(()->controller.translateSelectedElement(new Vec3(0,0,.03f))));
            var beforeGesture=(PolygonMesh)onEdt(()->controller.snapshot().requireGeometry(uniqueGeometry).geometry());
            var beforeGesturePosition=beforeGesture.requireVertex(edge.firstVertexId()).position();long gestureRevision=onEdt(()->controller.snapshot().revision());
            var gestureContext = onEdt(controller::state);
            long pendingOtherViewPick = onEdt(() -> controller.beginPick(
                    gestureContext.snapshot().revision(), gestureContext.selectionMode(), gestureContext.selection(),
                    gestureContext.vertexSelection(), gestureContext.edgeSelection(), gestureContext.faceSelection()));
            assertTrue(onEdt(()->controller.beginElementGesture(gestureRevision)));
            assertEquals(-1L, onEdt(() -> controller.beginPick(controller.snapshot().revision())));
            assertFalse(onEdt(() -> controller.acceptEdgePick(
                    duplicate, uniqueGeometry, edge.firstVertexId(), edge.secondVertexId(),
                    gestureContext.snapshot().revision(), pendingOtherViewPick)));
            assertEquals(duplicate, onEdt(controller::selection));
            assertTrue(onEdt(()->controller.updateElementGesture(new Vec3(.01f,0,0))));
            assertTrue(onEdt(()->controller.updateElementGesture(new Vec3(.025f,0,0))));
            assertTrue(onEdt(controller::commitTransformGesture));
            assertEquals(beforeGesturePosition.add(new Vec3(.025f,0,0)),((PolygonMesh)onEdt(()->controller.snapshot().requireGeometry(uniqueGeometry).geometry())).requireVertex(edge.firstVertexId()).position());
            assertTrue(onEdt(controller::undo));assertEquals(beforeGesturePosition,((PolygonMesh)onEdt(()->controller.snapshot().requireGeometry(uniqueGeometry).geometry())).requireVertex(edge.firstVertexId()).position());
            assertNotNull(onEdt(controller::edgeSelection));assertTrue(onEdt(controller::redo));

            var floor=onEdt(()->controller.snapshot().nodes().stream().filter(node->node.label().equals("Floor")).findFirst().orElseThrow());
            onEdt(()->controller.select(floor.id()));assertTrue(onEdt(()->controller.setSelectionMode(EditorController.SelectionMode.VERTEX)));
            var floorMesh=(PolygonMesh)onEdt(()->controller.snapshot().requireGeometry(floor.geometry().geometryId()).geometry());long floorVertex=floorMesh.editableVertices().getFirst().id();
            assertTrue(onEdt(()->controller.selectVertex(floorVertex)));var beforeInvalid=onEdt(controller::snapshot);
            assertTrue(onEdt(()->controller.beginElementGesture(beforeInvalid.revision())));
            assertFalse(onEdt(()->controller.updateElementGesture(new Vec3(0,1,0))));assertSame(beforeInvalid,onEdt(controller::snapshot));
            assertTrue(onEdt(controller::commitTransformGesture));assertTrue(onEdt(()->controller.state().status()).startsWith("Cancelled invalid edit:"));
            assertTrue(beforeInvalid.sameContent(onEdt(controller::snapshot)));

            var context=onEdt(controller::state);long staleIntent=onEdt(()->controller.beginPick(context.snapshot().revision(),context.selectionMode(),
                    context.selection(),context.vertexSelection(),context.edgeSelection(),context.faceSelection()));
            onEdt(()->controller.select(sphereNode.id()));
            assertFalse(onEdt(()->controller.acceptVertexPick(floor.id(),floor.geometry().geometryId(),floorVertex,
                    context.snapshot().revision(),staleIntent)));
        }finally{onEdt(()->{controller.close();return null;});}
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

    @Test public void cameraCaptureKeepsParentAndWorldOpticsInOneUndoableEdit() throws Exception {
        var controller = onEdt(EditorController::new);
        try {
            onEdt(() -> controller.select(null)); assertTrue(onEdt(() -> controller.create(EditorController.Primitive.GROUP)));
            var parent = onEdt(controller::selection);
            assertTrue(onEdt(() -> controller.applyTransform(parent, new Transform(new Vec3(2, -1, 4), new Vec3(12, -24, 7), new Vec3(2, 2, 2)))));
            assertTrue(onEdt(() -> controller.create(EditorController.Primitive.CAMERA))); var cameraId = onEdt(controller::selection);
            var before = onEdt(controller::snapshot);
            var localOptics = new Camera(Vec3.ZERO, new Rect(new Vec3(-1.2f, -.8f, 1), new Vec3(3.2f, 0, 0), new Vec3(.4f, 2, 0)),
                    Camera.Projection.ORTHOGRAPHIC, Camera.Mode.ORTHOGRAPHIC, 8, .35f, 3.5f, .55f).validated();
            var desired = localOptics.transformed(new Transform(new Vec3(-3, 5, -6), new Vec3(-18, 37, 11), new Vec3(1, 1, 1)));
            assertTrue(onEdt(() -> controller.setCameraFromView(cameraId, desired)));
            var capturedSnapshot = onEdt(controller::snapshot); var capturedNode = capturedSnapshot.requireNode(cameraId);
            assertEquals(parent, capturedNode.parentId()); assertCameraNear(desired, capturedSnapshot.camera(cameraId));
            assertTrue(onEdt(controller::undo)); assertTrue(onEdt(controller::snapshot).sameContent(before));
            assertTrue(onEdt(controller::redo)); assertCameraNear(desired, onEdt(controller::snapshot).camera(cameraId));
        } finally { onEdt(() -> { controller.close(); return null; }); }
    }

    @Test public void transformGesturePublishesLiveButCommitsOneUndoAndCancelsExactly() throws Exception {
        var controller = onEdt(EditorController::new);
        try {
            var selected = onEdt(controller::selection); var original = onEdt(() -> controller.snapshot().requireNode(selected).localTransform());
            assertTrue(onEdt(() -> controller.beginTransformGesture(selected, "Move X")));
            assertFalse(onEdt(() -> controller.select(null))); assertEquals(selected, onEdt(controller::selection));
            for (int i = 1; i <= 12; i++) {
                int step = i; assertTrue(onEdt(() -> controller.updateTransformGesture(selected,
                        new Transform(new Vec3(step * .1f, 0, 0), Vec3.ZERO, new Vec3(1, 1, 1)))));
            }
            var moved = onEdt(() -> controller.snapshot().requireNode(selected).localTransform()); assertFalse(moved.equals(original));
            assertTrue(onEdt(controller::commitTransformGesture)); assertTrue(onEdt(controller::undo));
            assertEquals(original, onEdt(() -> controller.snapshot().requireNode(selected).localTransform()));
            assertFalse(onEdt(controller::undo)); assertTrue(onEdt(controller::redo));
            assertEquals(moved, onEdt(() -> controller.snapshot().requireNode(selected).localTransform()));

            assertTrue(onEdt(() -> controller.beginTransformGesture(selected, "Rotate Z")));
            assertTrue(onEdt(() -> controller.updateTransformGesture(selected,
                    new Transform(new Vec3(9, 8, 7), new Vec3(0, 0, 35), new Vec3(1, 1, 1)))));
            assertTrue(onEdt(controller::cancelTransformGesture));
            assertEquals(moved, onEdt(() -> controller.snapshot().requireNode(selected).localTransform()));
        } finally { onEdt(() -> { controller.close(); return null; }); }
    }

    @Test public void invalidGizmoCandidateKeepsPreviousWorldAndCanCancel() throws Exception {
        var controller = onEdt(EditorController::new);
        try {
            onEdt(() -> controller.select(null)); assertTrue(onEdt(() -> controller.create(EditorController.Primitive.GROUP))); var parent = onEdt(controller::selection);
            assertTrue(onEdt(() -> controller.applyTransform(parent, new Transform(Vec3.ZERO, Vec3.ZERO, new Vec3(2, 1, 1)))));
            assertTrue(onEdt(() -> controller.create(EditorController.Primitive.BOX))); var child = onEdt(controller::selection);
            var before = onEdt(controller::snapshot); var candidate = GizmoMath.rotated(before, child, OverlayGeometry.Axis.Z, 35);
            assertTrue(onEdt(() -> controller.beginTransformGesture(child, "Rotate Z", before.revision())));
            assertFalse(onEdt(() -> controller.updateTransformGesture(child, candidate))); assertSame(before, onEdt(controller::snapshot));
            assertTrue(onEdt(controller::cancelTransformGesture)); assertTrue(onEdt(controller::snapshot).sameContent(before));
        } finally { onEdt(() -> { controller.close(); return null; }); }
    }

    private static SceneSnapshot meshScene(GeometryId geometry, MaterialId material, NodeId node, long face) {
        var document = new SceneDocument(); document.transact(e -> {
            e.createGeometry(geometry, "mesh", PolygonMesh.triangleSurface(List.of(new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)), new int[]{0, 1, 2}, new long[]{face}));
            e.createMaterial(material, "mat", Material.srgb("mat", 0xffffff)); e.createNode(node, "node", null, Transform.IDENTITY); e.assignGeometry(node, geometry, material);
        }); return document.snapshot();
    }

    private static void assertCameraNear(Camera expected, Camera actual) {
        assertEquals(expected.projection(), actual.projection()); assertEquals(expected.mode(), actual.mode());
        near(expected.eye(), actual.eye()); near(expected.sensor().origin(), actual.sensor().origin());
        near(expected.sensor().edge1(), actual.sensor().edge1()); near(expected.sensor().edge2(), actual.sensor().edge2());
        assertTrue(Math.abs(expected.focus() - actual.focus()) < 2e-4f); assertTrue(Math.abs(expected.aperture() - actual.aperture()) < 2e-4f);
        assertTrue(Math.abs(expected.height() - actual.height()) < 2e-4f); assertTrue(Math.abs(expected.rememberedAperture() - actual.rememberedAperture()) < 2e-4f);
    }
    private static void near(Vec3 expected, Vec3 actual) { assertTrue(expected.sub(actual).length() < 3e-4f); }

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
