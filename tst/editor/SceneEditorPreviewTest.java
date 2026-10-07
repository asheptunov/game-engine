package editor;

import harness.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
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
            while (System.nanoTime() < deadline) {
                onEdt(() -> { panel.setSize(1400, 850); layout(panel); var g = canvas.createGraphics(); panel.printAll(g); g.dispose(); return null; });
                if (onEdt(panel::viewsReady)) break; Thread.sleep(30);
            }
            assertTrue(onEdt(panel::viewsReady)); var revisions = onEdt(panel::displayedRevisions); assertEquals(revisions[0], revisions[1]);
            var labels = onEdt(() -> componentText(panel));
            for (var expected : new String[]{"Box", "Sphere", "Plane", "Group", "Light", "Camera", "Apply transform (one edit)",
                    "Apply to shared material", "Make unique", "Reparent · keep local pose", "Add / apply point light", "Add / apply camera"})
                assertTrue(labels.contains(expected));

            var selected = onEdt(controller::selection);
            onEdt(() -> controller.rename(selected, "Renamed without retrace"));
            long renamedRevision = onEdt(() -> controller.snapshot().revision());
            awaitDisplayedRevision(panel, renamedRevision);
            assertTrue(pickFromView(panel, controller, 0));
            for (int i = 0; i < 24; i++) {
                int step = i;
                onEdt(() -> { panel.navigateViewForTest(1, .012f + step * .0001f); return null; });
                Thread.sleep(14);
            }
            assertTrue(pickFromView(panel, controller, 1));
            var output = Path.of(System.getProperty("editor.preview", "out/editor/scene-editor-preview.png")); Files.createDirectories(output.toAbsolutePath().getParent());
            onEdt(() -> { var g = canvas.createGraphics(); panel.printAll(g); g.dispose(); return null; }); ImageIO.write(canvas, "png", output.toFile());
            assertTrue(Files.size(output) > 20_000); assertTrue(distinctPixels(canvas) > 64);
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

    private static boolean pickFromView(SceneEditorPanel panel, EditorController controller, int view) throws Exception {
        for (float v : new float[]{.5f, .4f, .6f, .3f, .7f}) for (float u : new float[]{.5f, .4f, .6f, .3f, .7f}) {
            onEdt(() -> { controller.select(null); panel.pickInViewForTest(view, u, v); return null; });
            long deadline = System.nanoTime() + 700_000_000L;
            while (System.nanoTime() < deadline) { if (onEdt(controller::selection) != null) return true; Thread.sleep(10); }
        }
        return false;
    }
    private static int distinctPixels(BufferedImage image) { var values = new HashSet<Integer>(); for (int y = 0; y < image.getHeight(); y += 8) for (int x = 0; x < image.getWidth(); x += 8) values.add(image.getRGB(x, y)); return values.size(); }
    private static Set<String> componentText(Component component) {
        var values = new HashSet<String>();
        if (component instanceof AbstractButton button) values.add(button.getText()); if (component instanceof JLabel label) values.add(label.getText());
        if (component instanceof Container container) for (var child : container.getComponents()) values.addAll(componentText(child)); return values;
    }
    private static void layout(Container container) { container.doLayout(); for (var child : container.getComponents()) if (child instanceof Container nested) layout(nested); }
    private static <T> T onEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call(); var value = new AtomicReference<T>(); var error = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> { try { value.set(action.call()); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new RuntimeException(error.get()); return value.get();
    }
}
