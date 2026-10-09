package editor;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertFalse;
import static harness.Assertions.assertTrue;

import editor.overlay.OverlayGeometry;

import engine.PolygonMesh;
import engine.RenderSession;
import engine.SpatialQuery;

import harness.Test;

import math.Vec3;

import java.awt.Container;
import java.awt.image.BufferedImage;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

/** Forces handoff order without relying on render speed or elapsed-time performance assertions. */
public class RenderViewHandoffTest {
    public static void main(String[] args) {
        harness.SuiteRunner.runThis();
    }

    @Test
    public void completedPairsAdvanceWhenNewGeometrySupersedesTheirRequest() throws Exception {
        try (var fixture = new Fixture()) {
            onEdt(
                    () -> {
                        fixture.initialize();
                        for (int step = 1; step <= 20; step++) {
                            fixture.move(step * .002f);
                            long completedRevision = fixture.controller.snapshot().revision();
                            fixture.paint();
                            var completedRequest = field(fixture.view, "desiredOverlay");
                            fixture.move(step * .002f + .001f);
                            fixture.paint();
                            fixture.project(completedRequest);
                            fixture.paint();
                            assertEquals(completedRevision, fixture.view.displayedRevision());
                            assertTrue(fixture.view.paintedBundleCoherentForTest());
                            assertFalse(
                                    fixture.view.paintedContextMatchesForTest(
                                            fixture.controller.state()));
                        }
                        fixture.project(field(fixture.view, "desiredOverlay"));
                        fixture.paint();
                        assertEquals(
                                fixture.controller.snapshot().revision(),
                                fixture.view.displayedRevision());
                        assertTrue(fixture.view.paintedBundleCoherentForTest());
                        return null;
                    });
        }
    }

    @Test
    public void selectionResizeAndToolChangesRejectSupersededProjection() throws Exception {
        try (var fixture = new Fixture()) {
            onEdt(
                    () -> {
                        fixture.initialize();
                        long initialSerial = fixture.view.paintedSerialForTest();
                        fixture.move(.01f);
                        fixture.paint();
                        var obsolete = field(fixture.view, "desiredOverlay");
                        fixture.controller.cancelTransformGesture();
                        fixture.controller.setSelectionMode(EditorController.SelectionMode.OBJECT);
                        fixture.setCandidate();
                        fixture.paint();
                        fixture.project(obsolete);
                        fixture.paint();
                        assertEquals(initialSerial, fixture.view.paintedSerialForTest());
                        fixture.project(field(fixture.view, "desiredOverlay"));
                        fixture.paint();
                        long objectSerial = fixture.view.paintedSerialForTest();
                        fixture.view.gizmoModeForTest(OverlayGeometry.GizmoMode.ROTATE);
                        fixture.paint();
                        var rotateRequest = field(fixture.view, "desiredOverlay");
                        fixture.view.gizmoModeForTest(OverlayGeometry.GizmoMode.TRANSLATE);
                        fixture.paint();
                        fixture.project(rotateRequest);
                        fixture.paint();
                        assertEquals(objectSerial, fixture.view.paintedSerialForTest());
                        fixture.owner.setSize(1260, 780);
                        fixture.paint();
                        var resizeRequest = field(fixture.view, "desiredOverlay");
                        fixture.owner.setSize(1400, 850);
                        fixture.paint();
                        fixture.project(resizeRequest);
                        fixture.paint();
                        assertEquals(objectSerial, fixture.view.paintedSerialForTest());
                        setField(fixture.view, "closed", true);
                        fixture.project(field(fixture.view, "desiredOverlay"));
                        assertEquals(objectSerial, fixture.view.paintedSerialForTest());
                        return null;
                    });
        }
    }

    /**
     * Reflection isolates the existing private handoff, without adding a production testing API.
     */
    private static final class Fixture implements AutoCloseable {
        private final EditorController controller;
        private final SceneEditorPanel owner;
        private final RenderViewPanel view;
        private final RenderViewPanel secondView;
        private final OverlayGeometry overlays = new OverlayGeometry();
        private final BufferedImage canvas =
                new BufferedImage(1400, 850, BufferedImage.TYPE_INT_RGB);

        Fixture() throws Exception {
            controller = onEdt(EditorController::new);
            owner = onEdt(() -> new SceneEditorPanel(controller));
            view = (RenderViewPanel) field(owner, "viewA");
            secondView = (RenderViewPanel) field(owner, "viewB");
            // No worker may touch handoff fields while the test advances them manually on the EDT.
            for (var panel : new RenderViewPanel[] {view, secondView}) {
                var executor = (ScheduledExecutorService) field(panel, "executor");
                executor.shutdown();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }

        void initialize() throws Exception {
            var node =
                    controller.snapshot().nodes().stream()
                            .filter(candidate -> candidate.label().equals("Teal box"))
                            .findFirst()
                            .orElseThrow();
            controller.select(node.id());
            assertTrue(controller.setSelectionMode(EditorController.SelectionMode.VERTEX));
            var mesh =
                    (PolygonMesh)
                            controller
                                    .snapshot()
                                    .requireGeometry(node.geometry().geometryId())
                                    .geometry();
            assertTrue(controller.selectVertex(mesh.editableVertices().getFirst().id()));
            assertTrue(controller.beginElementGesture(controller.snapshot().revision()));
            owner.setSize(1400, 850);
            setCandidate();
            paint();
            project(field(view, "desiredOverlay"));
            paint();
            assertEquals(controller.snapshot().revision(), view.displayedRevision());
        }

        void move(float delta) throws Exception {
            assertTrue(controller.updateElementGesture(new Vec3(delta, 0, 0)));
            setCandidate();
        }

        void setCandidate() throws Exception {
            var state = controller.state();
            var vertex = state.vertexSelection();
            var element =
                    vertex == null
                            ? null
                            : new OverlayGeometry.ElementSelection(
                                    vertex.nodeId(),
                                    vertex.geometryId(),
                                    OverlayGeometry.ElementKind.VERTEX,
                                    vertex.vertexId(),
                                    -1);
            var mode =
                    vertex == null
                            ? OverlayGeometry.ElementMode.OBJECT
                            : OverlayGeometry.ElementMode.VERTEX;
            var prepared = overlays.prepare(state.snapshot(), state.selection(), mode, element);
            var token =
                    record(
                            "PickToken",
                            state.snapshot(),
                            SpatialQuery.prepare(state.snapshot()),
                            prepared,
                            state.selection(),
                            state.selectionMode(),
                            vertex,
                            state.edgeSelection(),
                            state.faceSelection());
            var image = new BufferedImage(360, 225, BufferedImage.TYPE_INT_RGB);
            setField(
                    view,
                    "candidateFrame",
                    record(
                            "DisplayFrame",
                            image,
                            state.snapshot().revision(),
                            1L,
                            view.currentCameraForTest(),
                            token));
        }

        void paint() {
            layout(owner);
            var graphics = canvas.createGraphics();
            try {
                owner.printAll(graphics);
            } finally {
                graphics.dispose();
            }
        }

        void project(Object request) throws Exception {
            var method =
                    RenderViewPanel.class.getDeclaredMethod("projectOverlay", request.getClass());
            method.setAccessible(true);
            method.invoke(view, request);
        }

        @Override
        public void close() throws Exception {
            onEdt(
                    () -> {
                        for (var panel : new RenderViewPanel[] {view, secondView}) {
                            var session = (RenderSession) field(panel, "session");
                            if (session != null) {
                                session.close();
                            }
                            setField(panel, "closed", true);
                        }
                        owner.close();
                        return null;
                    });
        }
    }

    private static Object record(String name, Object... values) throws Exception {
        var type = Class.forName("editor.RenderViewPanel$" + name);
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void layout(Container container) {
        container.doLayout();
        for (var child : container.getComponents()) {
            if (child instanceof Container nested) {
                layout(nested);
            }
        }
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        var task = new FutureTask<T>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
