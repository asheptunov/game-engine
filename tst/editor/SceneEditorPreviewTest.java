package editor;

import editor.overlay.OverlayGeometry;
import engine.*;
import engine.objects.Rect;
import harness.Test;
import math.Vec3;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static harness.Assertions.*;

public class SceneEditorPreviewTest {
    public static void main(String[] args) { harness.SuiteRunner.runThis(); }

    @Test public void realPanelRendersTwoViewsAndExposesAuthoringControlsWithoutWindow() throws Exception {
        assertTrue(GraphicsEnvironment.isHeadless());
        var controller = onEdt(EditorController::new); var panel = onEdt(() -> new SceneEditorPanel(controller));
        try {
            var canvas = new BufferedImage(1400, 850, BufferedImage.TYPE_INT_RGB); long deadline = System.nanoTime() + 25_000_000_000L;
            var output = Path.of(System.getProperty("editor.preview", "out/editor/scene-editor-preview.png")); Files.createDirectories(output.toAbsolutePath().getParent());
            while (System.nanoTime() < deadline) {
                onEdt(() -> { panel.setSize(1400, 850); layout(panel); var g = canvas.createGraphics(); panel.printAll(g); g.dispose(); return null; });
                if (onEdt(panel::viewsReady)) break; Thread.sleep(30);
            }
            assertTrue(onEdt(panel::viewsReady)); var revisions = onEdt(panel::displayedRevisions); assertEquals(revisions[0], revisions[1]);
            var orbitBefore = onEdt(() -> panel.viewCameraForTest(0));
            onEdt(() -> { panel.mouseDragForTest(0, MouseEvent.BUTTON3, 0, 24, 0); return null; });
            var orbitAfter = onEdt(() -> panel.viewCameraForTest(0));
            assertTrue(orbitAfter.eye().x() < orbitBefore.eye().x());
            onEdt(() -> { panel.mouseDragForTest(0, MouseEvent.BUTTON1, 0, 24, 0); return null; });
            assertEquals(orbitAfter, onEdt(() -> panel.viewCameraForTest(0)));
            var panForward = orbitAfter.forward();
            var view = onEdt(() -> panel.viewComponentForTest(0));
            onEdt(() -> { panel.dispatchKeyForTest(new KeyEvent(view,KeyEvent.KEY_PRESSED,System.currentTimeMillis(),0,KeyEvent.VK_SPACE,' '));panel.mouseDragForTest(0, MouseEvent.BUTTON3, 0, 24, 0);panel.dispatchKeyForTest(new KeyEvent(view,KeyEvent.KEY_RELEASED,System.currentTimeMillis(),0,KeyEvent.VK_SPACE,' '));return null; });
            assertVec(panForward, onEdt(() -> panel.viewCameraForTest(0)).forward(), 2e-5f);
            var middlePanForward=onEdt(() -> panel.viewCameraForTest(0)).forward();
            onEdt(() -> { panel.dispatchKeyForTest(new KeyEvent(view,KeyEvent.KEY_PRESSED,System.currentTimeMillis(),0,KeyEvent.VK_SPACE,' '));panel.mouseDragForTest(0, MouseEvent.BUTTON2, 0, 18, 0);panel.dispatchKeyForTest(new KeyEvent(view,KeyEvent.KEY_RELEASED,System.currentTimeMillis(),0,KeyEvent.VK_SPACE,' '));return null; });
            assertVec(middlePanForward,onEdt(() -> panel.viewCameraForTest(0)).forward(),2e-5f);
            var beforeAmbiguous = onEdt(() -> panel.viewCameraForTest(0));
            onEdt(() -> { panel.mouseDragForTest(0, MouseEvent.BUTTON3, InputEvent.BUTTON1_DOWN_MASK, 20, 0); return null; });
            assertEquals(beforeAmbiguous, onEdt(() -> panel.viewCameraForTest(0)));
            var labels = onEdt(() -> componentText(panel));
            for (var expected : new String[]{"Box", "Sphere", "Plane", "Group", "Light", "Camera", "Apply transform (one edit)",
                    "Apply to shared material", "Make unique", "Reparent · keep local pose", "Capture view", "Wireframe", "Move", "Rotate",
                    "Output", "Input"})
                assertTrue(labels.contains(expected));
            assertFalse(onEdt(() -> panel.hasInspectorTabForTest("Components")));
            assertFalse(labels.contains("Point light (position uses Transform)")); assertFalse(labels.contains("Camera optics (pose uses Transform)"));
            writePanel(panel, canvas, output.resolveSibling("scene-editor-component-absent-preview.png"));

            var anchor = onEdt(controller::selection);
            onEdt(() -> controller.create(EditorController.Primitive.POINT_LIGHT)); var light = onEdt(controller::selection);
            onEdt(() -> controller.applyTransform(light, new Transform(new Vec3(-2.2f, 1.2f, 3), Vec3.ZERO, new Vec3(1, 1, 1))));
            onEdt(() -> controller.select(anchor)); onEdt(() -> controller.create(EditorController.Primitive.CAMERA)); var camera = onEdt(controller::selection);
            onEdt(() -> controller.applyTransform(camera, new Transform(new Vec3(2.2f, 1.1f, 3), Vec3.ZERO, new Vec3(1, 1, 1))));
            onEdt(() -> controller.select(anchor));
            awaitDisplayedRevision(panel,onEdt(()->controller.snapshot().revision()));
            awaitOverlay(panel, 0, anchor);
            var overlay = onEdt(() -> panel.overlayForTest(0));
            assertTrue(overlay.wireframe().size() > 0);
            assertTrue(overlay.markers().size() >= 1);
            assertEquals(3, overlay.handles().size());
            writePanel(panel, canvas, output.resolveSibling("scene-editor-move-preview.png"));
            assertTrue(onEdt(() -> panel.pickMarkerForTest(0, light))); assertEquals(light, onEdt(controller::selection)); labels = onEdt(() -> componentText(panel));
            assertTrue(labels.contains("Point light (position uses Transform)")); assertFalse(labels.contains("Camera optics (pose uses Transform)"));
            assertTrue(onEdt(() -> panel.hasInspectorTabForTest("Components")));
            assertTrue(labels.contains("Apply")); assertFalse(labels.contains("Remove point light"));
            awaitOverlay(panel, 0, light);
            onEdt(() -> { panel.selectInspectorTabForTest("Components"); return null; });
            writePanel(panel, canvas, output.resolveSibling("scene-editor-light-preview.png"));
            assertTrue(onEdt(() -> panel.pickMarkerForTest(0, camera))); assertEquals(camera, onEdt(controller::selection)); labels = onEdt(() -> componentText(panel));
            assertTrue(labels.contains("Camera optics (pose uses Transform)")); assertFalse(labels.contains("Point light (position uses Transform)"));
            assertTrue(labels.contains("Apply")); assertFalse(labels.contains("Remove camera"));
            awaitOverlay(panel, 0, camera);
            onEdt(() -> { panel.selectInspectorTabForTest("Components"); return null; });
            writePanel(panel, canvas, output.resolveSibling("scene-editor-camera-preview.png"));

            var localSkew = new Camera(new Vec3(.2f, -.1f, .3f),
                    new Rect(new Vec3(-.7f, -.45f, 1.25f), new Vec3(1.7f, 0, 0), new Vec3(.28f, 1.05f, 0)),
                    Camera.Projection.ORTHOGRAPHIC, Camera.Mode.ORTHOGRAPHIC, 6.5f, .18f, 3.25f, .22f).validated();
            onEdt(() -> controller.setCamera(camera, new CameraComponent(localSkew)));
            var worldSkew = onEdt(() -> controller.snapshot().camera(camera)); var signature = cameraSignature(worldSkew);
            onEdt(() -> { panel.useSceneCameraForTest(0, camera); panel.mouseDragForTest(0, MouseEvent.BUTTON3, 0, 18, -7); return null; });
            var navigatedSkew = onEdt(() -> panel.viewCameraForTest(0));
            assertEquals(worldSkew.projection(), navigatedSkew.projection()); assertEquals(worldSkew.mode(), navigatedSkew.mode());
            assertNear(worldSkew.focus(), navigatedSkew.focus(), 2e-4f); assertNear(worldSkew.aperture(), navigatedSkew.aperture(), 2e-4f);
            assertNear(worldSkew.height(), navigatedSkew.height(), 2e-4f); assertSignature(signature, cameraSignature(navigatedSkew), 3e-4f);
            float heightBeforeWheel = navigatedSkew.height();
            onEdt(() -> { panel.mouseWheelForTest(0, -1); return null; });
            var zoomedSkew = onEdt(() -> panel.viewCameraForTest(0));
            assertTrue(zoomedSkew.height() < heightBeforeWheel); assertNear(navigatedSkew.aperture(), zoomedSkew.aperture(), 2e-4f);
            onEdt(() -> { panel.resetViewForTest(0); return null; });

            var group = onEdt(() -> controller.snapshot().nodes().stream().filter(node -> node.label().equals("Composition")).findFirst().orElseThrow().id());
            onEdt(() -> controller.select(group)); awaitOverlay(panel, 0, group); overlay = onEdt(() -> panel.overlayForTest(0));
            assertTrue(overlay.wireframe().stream().anyMatch(line -> !line.nodeId().equals(group)));

            var outputArea = onEdt(panel::commandOutputForTest); assertFalse(outputArea.isEditable()); assertTrue(outputArea.isFocusable());
            onEdt(() -> { panel.executeCommandForTest("status"); outputArea.select(0, Math.min(4, outputArea.getDocument().getLength())); return null; });
            assertNotNull(onEdt(outputArea::getSelectedText)); assertTrue(onEdt(panel::commandInputForTest).isEditable());
            for (int i = 0; i < 700; i++) onEdt(() -> { panel.executeCommandForTest("status"); return null; });
            assertTrue(onEdt(panel::commandOutputLengthForTest) <= 32_000);
            var chooser = onEdt(panel::chooserForTest); var filter = chooser.getFileFilter();
            assertFalse(chooser.isAcceptAllFileFilterUsed()); assertTrue(filter.accept(new java.io.File("example.SCENE.XML")));
            assertFalse(filter.accept(new java.io.File("example.xml"))); assertEquals(Path.of("Example.SCENE.XML"), SceneEditorPanel.scenePathForTest(Path.of("Example.SCENE.XML")));

            var selected = onEdt(() -> controller.snapshot().nodes().stream().filter(node -> node.label().equals("Teal box")).findFirst().orElseThrow().id()); onEdt(() -> controller.select(selected));
            assertTrue(onEdt(() -> panel.hasInspectorTabForTest("Mesh"))); assertTrue(onEdt(() -> panel.meshFaceTextForTest()).contains("No face"));
            onEdt(() -> { panel.selectInspectorTabForTest("Mesh"); panel.selectionModeForTest(EditorController.SelectionMode.FACE); return null; });
            assertEquals(EditorController.SelectionMode.FACE,onEdt(controller::selectionMode));
            assertTrue(onEdt(panel::meshConvertForTest)); assertTrue(onEdt(() -> controller.snapshot().requireGeometry(
                    controller.snapshot().requireNode(selected).geometry().geometryId()).geometry() instanceof PolygonMesh));
            awaitDisplayedRevision(panel,onEdt(()->controller.snapshot().revision()));
            assertTrue(pickFaceFromView(panel,controller,selected,0));
            awaitFaceOverlay(panel,0,selected);awaitFaceOverlay(panel,1,selected);
            assertTrue(onEdt(()->panel.overlayForTest(0).handles().isEmpty()));
            assertFalse(onEdt(()->panel.beginHandleDragForTest(0,OverlayGeometry.Axis.X,18)));
            assertTrue(onEdt(()->panel.pickMarkerForTest(0,light)));Thread.sleep(250);onEdt(()->null);
            assertFalse(light.equals(onEdt(controller::selection)));
            if(onEdt(controller::faceSelection)==null){onEdt(()->{controller.select(selected);return null;});assertTrue(pickFaceFromView(panel,controller,selected,0));awaitFaceOverlay(panel,0,selected);awaitFaceOverlay(panel,1,selected);}
            onEdt(()->{panel.wireframeForTest(0,false);panel.wireframeForTest(1,false);panel.selectInspectorTabForTest("Mesh");return null;});pumpPaint(panel,4);
            assertFalse(onEdt(()->panel.overlayForTest(0).selectedFace().isEmpty()));assertFalse(onEdt(()->panel.overlayForTest(1).selectedFace().isEmpty()));
            assertTrue(onEdt(() -> panel.meshFaceTextForTest()).contains("Selected face"));
            assertFalse(onEdt(panel::meshConvertEnabledForTest));assertTrue(onEdt(panel::meshExtrudeEnabledForTest));
            writePanel(panel,canvas,output.resolveSibling("scene-editor-mesh-preview.png"));
            int faceCountBefore=((PolygonMesh)onEdt(()->controller.snapshot().requireGeometry(controller.faceSelection().geometryId()).geometry())).faces().size();
            assertTrue(onEdt(()->panel.meshExtrudeForTest(.35f)));
            assertTrue(((PolygonMesh)onEdt(()->controller.snapshot().requireGeometry(controller.faceSelection().geometryId()).geometry())).faces().size()>faceCountBefore);
            awaitDisplayedRevision(panel,onEdt(()->controller.snapshot().revision()));awaitFaceOverlay(panel,0,selected);awaitFaceOverlay(panel,1,selected);
            onEdt(()->{panel.selectInspectorTabForTest("Mesh");return null;});writePanel(panel,canvas,output.resolveSibling("scene-editor-mesh-extruded-preview.png"));
            assertTrue(onEdt(controller::undo));assertEquals(faceCountBefore,((PolygonMesh)onEdt(()->controller.snapshot().requireGeometry(controller.faceSelection().geometryId()).geometry())).faces().size());
            assertTrue(onEdt(controller::redo));awaitDisplayedRevision(panel,onEdt(()->controller.snapshot().revision()));
            onEdt(()->{panel.selectionModeForTest(EditorController.SelectionMode.OBJECT);panel.selectionModeForTest(EditorController.SelectionMode.FACE);return null;});
            assertTrue(pickFaceFromView(panel,controller,selected,1));awaitFaceOverlay(panel,0,selected);awaitFaceOverlay(panel,1,selected);
            onEdt(()->{panel.selectionModeForTest(EditorController.SelectionMode.OBJECT);panel.wireframeForTest(0,true);panel.wireframeForTest(1,true);return null;});
            awaitOverlay(panel, 0, selected); var beforeDrag = onEdt(() -> controller.snapshot().requireNode(selected).localTransform());
            assertTrue(onEdt(() -> panel.dragHandleForTest(0, OverlayGeometry.Axis.X, 24, true)));
            var afterDrag = onEdt(() -> controller.snapshot().requireNode(selected).localTransform()); assertFalse(afterDrag.equals(beforeDrag));
            var input = onEdt(panel::commandInputForTest);
            assertTrue(onEdt(() -> panel.dispatchKeyForTest(new KeyEvent(input, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_Z, 'Z'))));
            assertEquals(beforeDrag, onEdt(() -> controller.snapshot().requireNode(selected).localTransform()));
            assertFalse(onEdt(() -> panel.dispatchKeyForTest(new KeyEvent(input, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_C, 'C'))));
            assertTrue(onEdt(() -> panel.dispatchKeyForTest(new KeyEvent(input, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_Z, 'Z'))));
            assertEquals(afterDrag, onEdt(() -> controller.snapshot().requireNode(selected).localTransform()));
            assertTrue(onEdt(controller::undo)); assertEquals(beforeDrag, onEdt(() -> controller.snapshot().requireNode(selected).localTransform()));
            awaitDisplayedRevision(panel,onEdt(()->controller.snapshot().revision()));awaitOverlay(panel, 0, selected); assertTrue(onEdt(() -> panel.beginHandleDragForTest(0, OverlayGeometry.Axis.Y, 18)));
            assertFalse(beforeDrag.equals(onEdt(() -> controller.snapshot().requireNode(selected).localTransform())));
            assertTrue(onEdt(() -> panel.dispatchKeyForTest(new KeyEvent(input, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED))));
            assertEquals(beforeDrag, onEdt(() -> controller.snapshot().requireNode(selected).localTransform()));
            onEdt(() -> controller.rename(selected, "Renamed without retrace"));
            long renamedRevision = onEdt(() -> controller.snapshot().revision());
            awaitDisplayedRevision(panel, renamedRevision);
            assertTrue(pickFromView(panel, controller, 0));
            var movingSerials=new HashSet<Long>();movingSerials.add(onEdt(()->panel.paintedSerialForTest(1)));int movementStep=0;long movementDeadline=System.nanoTime()+6_000_000_000L;
            while(System.nanoTime()<movementDeadline&&movingSerials.size()<2){
                int step = movementStep++;
                onEdt(() -> { panel.navigateViewForTest(1, .012f + step * .0001f);paintPanel(panel,1400,850);assertTrue(panel.overlayForTest(1)!=null);assertTrue(panel.paintedBundleCoherentForTest(1));movingSerials.add(panel.paintedSerialForTest(1));return null; });
                Thread.sleep(20);
            }
            if(movingSerials.size()<2)throw new RuntimeException("No coherent preview advanced during six seconds of movement: "+onEdt(()->panel.overlayProgressForTest(1)));
            assertTrue(pickFromView(panel, controller, 1));
            onEdt(() -> controller.select(selected));awaitOverlay(panel,0,selected);
            long oldOverlaySerial=onEdt(() -> panel.paintedSerialForTest(0));
            var alternate=onEdt(() -> controller.snapshot().nodes().stream().filter(node->node.geometry()!=null&&!node.id().equals(selected)).findFirst().orElseThrow().id());
            var projectionBlock=onEdt(() -> panel.blockNextProjectionForTest(0));onEdt(() -> controller.select(alternate));
            awaitProjectionBlocked(panel,projectionBlock);
            assertEquals(oldOverlaySerial,onEdt(() -> panel.paintedSerialForTest(0)));assertTrue(onEdt(() -> panel.paintedBundleCoherentForTest(0)));
            assertTrue(onEdt(() -> panel.overlayForTest(0).handles().stream().anyMatch(handle->handle.nodeId().equals(selected))));
            projectionBlock.release().countDown();awaitOverlay(panel,0,alternate);assertTrue(onEdt(() -> panel.paintedSerialForTest(0))>oldOverlaySerial);
            onEdt(() -> controller.select(selected));awaitOverlay(panel,0,selected);
            var modeBlock=onEdt(()->panel.blockNextProjectionForTest(0));onEdt(()->{panel.gizmoModeForTest(0,OverlayGeometry.GizmoMode.ROTATE);return null;});awaitProjectionBlocked(panel,modeBlock);
            onEdt(()->{panel.gizmoModeForTest(0,OverlayGeometry.GizmoMode.TRANSLATE);paintPanel(panel,1400,850);return null;});modeBlock.release().countDown();pumpPaint(panel,8);
            assertTrue(onEdt(()->panel.overlayForTest(0).handles().stream().allMatch(handle->handle.mode()==OverlayGeometry.GizmoMode.TRANSLATE)));
            var resizeBlock=onEdt(()->panel.blockNextProjectionForTest(0));onEdt(()->{panel.setSize(1260,780);layout(panel);paintPanel(panel,1260,780);return null;});awaitProjectionBlocked(panel,resizeBlock);
            onEdt(()->{panel.setSize(1400,850);layout(panel);paintPanel(panel,1400,850);return null;});resizeBlock.release().countDown();pumpPaint(panel,8);assertTrue(onEdt(()->panel.paintedBundleCoherentForTest(0)));
            onEdt(() -> { controller.select(selected); panel.gizmoModeForTest(0, OverlayGeometry.GizmoMode.ROTATE); panel.selectInspectorTabForTest("Material"); return null; });
            awaitOverlayMode(panel, 0, selected, OverlayGeometry.GizmoMode.ROTATE);
            overlay = onEdt(() -> panel.overlayForTest(0));
            assertTrue(overlay.wireframe().size() > 0); assertTrue(overlay.markers().size() >= 1);
            assertTrue(overlay.handles().stream().allMatch(handle -> handle.mode() == OverlayGeometry.GizmoMode.ROTATE));
            writePanel(panel, canvas, output);
            assertTrue(Files.size(output) > 20_000); assertTrue(distinctPixels(canvas) > 64);
        } finally { onEdt(() -> { panel.close(); return null; }); }
    }

    @Test public void editorMouseRouteUsesRemappedBindingFile() throws Exception {
        var directory = Files.createTempDirectory("scene-editor-bindings");
        var keys = directory.resolve("keys.properties"); var mouse = directory.resolve("mouse.properties");
        Files.writeString(keys, "escape = gesture.cancel\nctrl+z = history.undo\nctrl+shift+z = history.redo\n");
        Files.writeString(mouse, "middle+drag+viewport = view.orbit\nwheel+viewport = view.zoom\n");
        var controller = onEdt(EditorController::new); var panel = onEdt(() -> new SceneEditorPanel(controller, keys, mouse));
        try {
            onEdt(() -> { panel.setSize(1120, 720); layout(panel); return null; });
            var before = onEdt(() -> panel.viewCameraForTest(0));
            onEdt(() -> { panel.mouseDragForTest(0, MouseEvent.BUTTON2, 0, 20, 0); return null; });
            var middle = onEdt(() -> panel.viewCameraForTest(0)); assertFalse(before.equals(middle));
            onEdt(() -> { panel.mouseDragForTest(0, MouseEvent.BUTTON3, 0, 20, 0); return null; });
            assertEquals(middle, onEdt(() -> panel.viewCameraForTest(0)));
        } finally { onEdt(() -> { panel.close(); return null; }); }
    }

    private static void awaitDisplayedRevision(SceneEditorPanel panel, long revision) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            onEdt(() -> { var image = new BufferedImage(1400, 850, BufferedImage.TYPE_INT_RGB); var g = image.createGraphics(); panel.printAll(g); g.dispose(); return null; });
            var shown = onEdt(panel::displayedRevisions);
            if (shown[0] == revision && shown[1] == revision) return;
            Thread.sleep(15);
        }
        throw new AssertionError("Displayed frames did not rebind to renamed scene revision " + revision);
    }

    private static void awaitOverlay(SceneEditorPanel panel, int view, NodeId selected) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            onEdt(() -> { var image = new BufferedImage(1400, 850, BufferedImage.TYPE_INT_RGB); var g = image.createGraphics(); panel.printAll(g); g.dispose(); return null; });
            var overlay = onEdt(() -> panel.overlayForTest(view));
            if (overlay != null && overlay.handles().stream().anyMatch(handle -> handle.nodeId().equals(selected))) return;
            Thread.sleep(15);
        }
        throw new AssertionError("Overlay did not become ready for " + selected);
    }

    private static void awaitFaceOverlay(SceneEditorPanel panel,int view,NodeId selected)throws Exception{
        long deadline=System.nanoTime()+5_000_000_000L;
        while(System.nanoTime()<deadline){
            onEdt(()->{paintPanel(panel,1400,850);return null;});
            var overlay=onEdt(()->panel.overlayForTest(view));
            if(overlay!=null&&!overlay.selectedFace().isEmpty()&&overlay.selectedFace().stream().allMatch(line->line.nodeId().equals(selected)))return;
            Thread.sleep(15);
        }
        throw new AssertionError("Selected face overlay did not become ready for "+selected+" in view "+view);
    }

    private static void awaitOverlayMode(SceneEditorPanel panel, int view, NodeId selected, OverlayGeometry.GizmoMode mode) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            onEdt(() -> { var image = new BufferedImage(1400, 850, BufferedImage.TYPE_INT_RGB); var g = image.createGraphics(); panel.printAll(g); g.dispose(); return null; });
            var overlay = onEdt(() -> panel.overlayForTest(view));
            if (overlay != null && overlay.handles().stream().anyMatch(handle -> handle.nodeId().equals(selected) && handle.mode() == mode)) return;
            Thread.sleep(15);
        }
        throw new AssertionError("Overlay mode did not become ready for " + selected + ": " + mode);
    }

    private static void awaitProjectionBlocked(SceneEditorPanel panel,RenderViewPanel.ProjectionBlock block)throws Exception{
        long deadline=System.nanoTime()+3_000_000_000L;
        while(System.nanoTime()<deadline&&block.entered().getCount()!=0){
            onEdt(()->{var image=new BufferedImage(1400,850,BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();panel.printAll(graphics);graphics.dispose();return null;});
            Thread.sleep(10);
        }
        assertEquals(0L,block.entered().getCount());
    }
    private static void pumpPaint(SceneEditorPanel panel,int count)throws Exception{for(int i=0;i<count;i++){onEdt(()->{paintPanel(panel,panel.getWidth(),panel.getHeight());return null;});Thread.sleep(15);}}
    private static void paintPanel(SceneEditorPanel panel,int width,int height){var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();panel.printAll(graphics);graphics.dispose();}

    private static void writePanel(SceneEditorPanel panel, BufferedImage canvas, Path output) throws Exception {
        onEdt(() -> { layout(panel); var g = canvas.createGraphics(); panel.printAll(g); g.dispose(); return null; });
        ImageIO.write(canvas, "png", output.toFile());
    }

    private static boolean pickFromView(SceneEditorPanel panel, EditorController controller, int view) throws Exception {
        for (float v : new float[]{.5f, .4f, .6f, .3f, .7f}) for (float u : new float[]{.5f, .4f, .6f, .3f, .7f}) {
            onEdt(() -> { controller.select(null); panel.pickInViewForTest(view, u, v); return null; });
            long deadline = System.nanoTime() + 700_000_000L;
            while (System.nanoTime() < deadline) { if (onEdt(controller::selection) != null) return true; Thread.sleep(10); }
        }
        return false;
    }
    private static boolean pickFaceFromView(SceneEditorPanel panel,EditorController controller,NodeId target,int view)throws Exception{
        for(float v:new float[]{.5f,.4f,.6f,.3f,.7f,.2f,.8f})for(float u:new float[]{.5f,.4f,.6f,.3f,.7f,.2f,.8f}){
            onEdt(()->{controller.select(target);panel.pickInViewForTest(view,u,v);return null;});
            long deadline=System.nanoTime()+800_000_000L;
            while(System.nanoTime()<deadline){var face=onEdt(controller::faceSelection);if(face!=null&&face.nodeId().equals(target))return true;Thread.sleep(10);}
        }
        return false;
    }
    private static int distinctPixels(BufferedImage image) { var values = new HashSet<Integer>(); for (int y = 0; y < image.getHeight(); y += 8) for (int x = 0; x < image.getWidth(); x += 8) values.add(image.getRGB(x, y)); return values.size(); }
    private static float[] cameraSignature(Camera camera) {
        var right = camera.sensor().edge1().normalized(); var forward = camera.forward(); var up = forward.cross(right).normalized();
        var origin = camera.sensor().origin().sub(camera.eye()); var a = camera.sensor().edge1(); var b = camera.sensor().edge2();
        return new float[]{origin.dot(right), origin.dot(up), origin.dot(forward), a.dot(right), a.dot(up), a.dot(forward),
                b.dot(right), b.dot(up), b.dot(forward)};
    }
    private static void assertSignature(float[] expected, float[] actual, float tolerance) { for (int i = 0; i < expected.length; i++) assertNear(expected[i], actual[i], tolerance); }
    private static void assertVec(Vec3 expected, Vec3 actual, float tolerance) { assertNear(expected.x(), actual.x(), tolerance); assertNear(expected.y(), actual.y(), tolerance); assertNear(expected.z(), actual.z(), tolerance); }
    private static void assertNear(float expected, float actual, float tolerance) { assertTrue(Math.abs(expected - actual) <= tolerance); }
    private static Set<String> componentText(Component component) {
        var values = new HashSet<String>();
        if (component instanceof AbstractButton button) values.add(button.getText()); if (component instanceof JLabel label) values.add(label.getText());
        if (component instanceof JComponent swing && swing.getBorder() instanceof javax.swing.border.TitledBorder titled) values.add(titled.getTitle());
        if (component instanceof Container container) for (var child : container.getComponents()) values.addAll(componentText(child)); return values;
    }
    private static void layout(Container container) { container.doLayout(); for (var child : container.getComponents()) if (child instanceof Container nested) layout(nested); }
    private static <T> T onEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call(); var value = new AtomicReference<T>(); var error = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> { try { value.set(action.call()); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new RuntimeException(error.get()); return value.get();
    }
}
