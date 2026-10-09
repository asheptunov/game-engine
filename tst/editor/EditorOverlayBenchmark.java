package editor;

import java.awt.Container;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import javax.swing.SwingUtilities;

/** Stationary dense-overlay paint cost, separately from editing and fresh-frame progress. */
public final class EditorOverlayBenchmark {
    private static final int WIDTH = 1400;
    private static final int HEIGHT = 850;
    private static final int COUNT = 60;

    public static void main(String[] args) throws Exception {
        int repeats = args.length == 0 ? 3 : Integer.parseInt(args[0]);
        System.out.printf(
                Locale.ROOT,
                "java=%s panel=%dx%d mesh=sphere detail=32 vertices=1986 faces=3968 paints=%d"
                        + " warmup=20 stationary=true%n",
                System.getProperty("java.version"),
                WIDTH,
                HEIGHT,
                COUNT);
        for (int repeat = 1; repeat <= repeats; repeat++) {
            var controller = onEdt(EditorController::new);
            var panel = onEdt(() -> createPanel(controller));
            try {
                var canvas = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
                awaitReady(panel, controller, canvas);
                for (boolean wireframe : new boolean[] {true, false}) {
                    for (var mode :
                            new EditorController.SelectionMode[] {
                                EditorController.SelectionMode.OBJECT,
                                EditorController.SelectionMode.VERTEX,
                                EditorController.SelectionMode.EDGE
                            }) {
                        measure(panel, controller, canvas, repeat, wireframe, mode);
                    }
                }
            } finally {
                onEdt(
                        () -> {
                            panel.close();
                            return null;
                        });
            }
        }
    }

    private static SceneEditorPanel createPanel(EditorController controller) {
        var sphere =
                controller.snapshot().nodes().stream()
                        .filter(node -> node.label().equals("Terracotta sphere"))
                        .findFirst()
                        .orElseThrow();
        controller.select(sphere.id());
        require(controller.approximateAnalyticSphere(sphere.id(), 32), controller);
        var panel = new SceneEditorPanel(controller);
        panel.setSize(WIDTH, HEIGHT);
        return panel;
    }

    private static void measure(
            SceneEditorPanel panel,
            EditorController controller,
            BufferedImage canvas,
            int repeat,
            boolean wireframe,
            EditorController.SelectionMode mode)
            throws Exception {
        onEdt(
                () -> {
                    panel.wireframeForTest(0, wireframe);
                    panel.wireframeForTest(1, wireframe);
                    require(controller.setSelectionMode(mode), controller);
                    return null;
                });
        awaitReady(panel, controller, canvas);
        for (int warmup = 0; warmup < 20; warmup++) {
            onEdt(
                    () -> {
                        paint(panel, canvas);
                        return null;
                    });
            Thread.sleep(15);
        }
        var millis = new double[COUNT];
        for (int index = 0; index < millis.length; index++) {
            int position = index;
            onEdt(
                    () -> {
                        long start = System.nanoTime();
                        paint(panel, canvas);
                        millis[position] = (System.nanoTime() - start) / 1e6;
                        return null;
                    });
            Thread.sleep(10);
        }
        Arrays.sort(millis);
        System.out.printf(
                Locale.ROOT,
                "repeat=%d mode=%s wireframe=%s paint-ms mean=%.3f p50=%.3f p95=%.3f max=%.3f%n",
                repeat,
                mode,
                wireframe,
                Arrays.stream(millis).average().orElseThrow(),
                millis[millis.length / 2],
                millis[(int) (millis.length * .95)],
                millis[millis.length - 1]);
    }

    private static void awaitReady(
            SceneEditorPanel panel, EditorController controller, BufferedImage canvas)
            throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            boolean ready =
                    onEdt(
                            () -> {
                                paint(panel, canvas);
                                return panel.paintedContextMatchesForTest(0, controller.state())
                                        && panel.paintedContextMatchesForTest(
                                                1, controller.state());
                            });
            if (ready) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("Overlay context did not settle");
    }

    private static void require(boolean success, EditorController controller) {
        if (!success) {
            throw new IllegalStateException(controller.state().status());
        }
    }

    private static void paint(SceneEditorPanel panel, BufferedImage canvas) {
        layout(panel);
        var graphics = canvas.createGraphics();
        try {
            panel.printAll(graphics);
        } finally {
            graphics.dispose();
        }
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
