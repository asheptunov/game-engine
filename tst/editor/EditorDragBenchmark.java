package editor;

import engine.PolygonMesh;
import engine.RenderSession;

import math.Vec3;

import java.awt.Container;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Window-free sustained editing; counts newly painted revisions, not cached repaints. */
public final class EditorDragBenchmark {
    private static final int WIDTH = 1400;
    private static final int HEIGHT = 850;
    private static final long DURATION_MILLIS = 8000;
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        int repeats = args.length == 0 ? 3 : Integer.parseInt(args[0]);
        var mode =
                args.length < 2
                        ? EditorController.SelectionMode.VERTEX
                        : EditorController.SelectionMode.valueOf(args[1].toUpperCase(Locale.ROOT));
        System.out.printf(
                Locale.ROOT,
                "java=%s os=%s processors=%d panel=%dx%d mode=%s durationMs=%d paintPeriodMs=16"
                    + " depth=3 sppPerBatch=1 target=8 workers=%d wireframe=true seeds=View A/View"
                    + " B title hashes%n",
                System.getProperty("java.version"),
                System.getProperty("os.name"),
                Runtime.getRuntime().availableProcessors(),
                WIDTH,
                HEIGHT,
                mode,
                DURATION_MILLIS,
                Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2)));
        for (int repeat = 1; repeat <= repeats; repeat++) {
            for (int period : new int[] {16, 4}) {
                run(repeat, period, mode);
            }
        }
    }

    private static void run(int repeat, int period, EditorController.SelectionMode mode)
            throws Exception {
        var workload = onEdt(() -> new Workload(mode));
        try {
            for (int warmup = 0; warmup < 100; warmup++) {
                onEdt(
                        () -> {
                            workload.paint();
                            return null;
                        });
                Thread.sleep(30);
            }
            onEdt(
                    () -> {
                        workload.start(period);
                        return null;
                    });
            Thread.sleep(DURATION_MILLIS);
            onEdt(
                    () -> {
                        workload.stop();
                        return null;
                    });
            while (System.nanoTime() - workload.end < 5_000_000_000L) {
                if (onEdt(workload::settle)) {
                    break;
                }
                Thread.sleep(5);
            }
            onEdt(
                    () -> {
                        workload.report(repeat, period);
                        return null;
                    });
        } finally {
            onEdt(
                    () -> {
                        workload.close();
                        return null;
                    });
        }
    }

    /** All mutable measurement state and callbacks belong to Swing's event dispatch thread. */
    private static final class Workload {
        private final EditorController controller = new EditorController();
        private final SceneEditorPanel panel;
        private final BufferedImage canvas =
                new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        private final ViewMetrics[] views;
        private final ArrayList<Double> editMillis = new ArrayList<>();
        private final ArrayList<Double> editKiB = new ArrayList<>();
        private final ArrayList<Double> paintMillis = new ArrayList<>();
        private final HashMap<Long, Long> revisionTimes = new HashMap<>();
        private Timer input;
        private Timer painter;
        private Throwable failure;
        private long start;
        private long end;
        private long finalRevision;

        Workload(EditorController.SelectionMode mode) throws Exception {
            selectElement(mode);
            panel = new SceneEditorPanel(controller);
            panel.setSize(WIDTH, HEIGHT);
            views =
                    new ViewMetrics[] {
                        new ViewMetrics((RenderViewPanel) field(panel, "viewA")),
                        new ViewMetrics((RenderViewPanel) field(panel, "viewB"))
                    };
        }

        private void selectElement(EditorController.SelectionMode mode) {
            var node =
                    controller.snapshot().nodes().stream()
                            .filter(candidate -> candidate.label().equals("Teal box"))
                            .findFirst()
                            .orElseThrow();
            controller.select(node.id());
            require(controller.setSelectionMode(mode), controller);
            var mesh =
                    (PolygonMesh)
                            controller
                                    .snapshot()
                                    .requireGeometry(node.geometry().geometryId())
                                    .geometry();
            switch (mode) {
                case VERTEX ->
                        require(
                                controller.selectVertex(mesh.editableVertices().getFirst().id()),
                                controller);
                case EDGE -> {
                    var edge = mesh.edges().getFirst();
                    require(
                            controller.selectEdge(edge.firstVertexId(), edge.secondVertexId()),
                            controller);
                }
                case FACE ->
                        require(controller.selectFace(mesh.faces().getFirst().id()), controller);
                default -> throw new IllegalArgumentException("Use vertex, edge or face");
            }
        }

        void start(int period) throws Exception {
            if (!panel.viewsReady()) {
                throw new IllegalStateException("Warmup did not render both views");
            }
            require(controller.beginElementGesture(controller.snapshot().revision()), controller);
            start = System.nanoTime();
            for (var view : views) {
                view.start(start);
            }
            input = new Timer(period, event -> callback(this::edit));
            painter = new Timer(16, event -> callback(this::samplePaint));
            input.start();
            painter.start();
        }

        private void callback(CheckedAction action) {
            try {
                action.run();
            } catch (Throwable error) {
                if (failure == null) {
                    failure = error;
                }
            }
        }

        private void edit() {
            long started = System.nanoTime();
            long allocated = allocatedBytes();
            double seconds = (started - start) / 1e9;
            require(
                    controller.updateElementGesture(
                            new Vec3((float) (.15 * Math.sin(seconds * 8)), 0, 0)),
                    controller);
            long finished = System.nanoTime();
            editMillis.add((finished - started) / 1e6);
            if (allocated >= 0) {
                editKiB.add((allocatedBytes() - allocated) / 1024.0);
            }
            revisionTimes.put(controller.snapshot().revision(), started);
        }

        private void samplePaint() throws Exception {
            long started = System.nanoTime();
            paint();
            long finished = System.nanoTime();
            paintMillis.add((finished - started) / 1e6);
            for (var view : views) {
                view.observe(finished, revisionTimes);
            }
        }

        void stop() {
            input.stop();
            painter.stop();
            end = System.nanoTime();
            for (var view : views) {
                view.gapsMillis.add((end - view.lastFresh) / 1e6);
            }
            require(controller.commitTransformGesture(), controller);
            finalRevision = controller.snapshot().revision();
        }

        boolean settle() {
            paint();
            long now = System.nanoTime();
            for (var view : views) {
                if (view.settleMillis < 0 && view.view.displayedRevision() == finalRevision) {
                    view.settleMillis = (now - end) / 1e6;
                }
            }
            return views[0].settleMillis >= 0 && views[1].settleMillis >= 0;
        }

        void report(int repeat, int period) throws Exception {
            double seconds = (end - start) / 1e9;
            System.out.printf(
                    Locale.ROOT,
                    "repeat=%d inputPeriodMs=%d durationSeconds=%.3f edits=%d"
                            + " deliveredEditsPerSecond=%.2f paints=%d%n",
                    repeat,
                    period,
                    seconds,
                    editMillis.size(),
                    editMillis.size() / seconds,
                    paintMillis.size());
            stats("edit-ms", editMillis);
            stats("edit-allocated-KiB", editKiB);
            stats("paint-ms", paintMillis);
            for (int index = 0; index < views.length; index++) {
                views[index].report(index, seconds);
            }
            if (failure != null) {
                throw new IllegalStateException("Benchmark callback failed", failure);
            }
            if (views[0].settleMillis < 0 || views[1].settleMillis < 0) {
                throw new IllegalStateException("Final revision did not settle");
            }
        }

        void paint() {
            layout(panel);
            var graphics = canvas.createGraphics();
            try {
                panel.printAll(graphics);
            } finally {
                graphics.dispose();
            }
        }

        void close() {
            if (input != null) {
                input.stop();
                painter.stop();
            }
            panel.close();
        }
    }

    private static final class ViewMetrics {
        private final RenderViewPanel view;
        private RenderSession session;
        private long cancelledBefore;
        private long wastedBefore;
        private final ArrayList<Double> gapsMillis = new ArrayList<>();
        private final ArrayList<Double> agesMillis = new ArrayList<>();
        private long lastFresh;
        private long revision;
        private long generation = -1;
        private long serial = -1;
        private int fresh;
        private int candidates;
        private int overlays;
        private double settleMillis = -1;

        ViewMetrics(RenderViewPanel view) {
            this.view = view;
        }

        void start(long now) throws Exception {
            session = (RenderSession) field(view, "session");
            var progress = session.progress(null);
            cancelledBefore = progress.cancelledJobs();
            wastedBefore = progress.wastedPaths();
            revision = view.displayedRevision();
            lastFresh = now;
        }

        void observe(long now, HashMap<Long, Long> revisionTimes) throws Exception {
            long next = view.displayedRevision();
            if (next != revision) {
                fresh++;
                gapsMillis.add((now - lastFresh) / 1e6);
                lastFresh = now;
                revision = next;
                var captured = revisionTimes.get(next);
                if (captured != null) {
                    agesMillis.add((now - captured) / 1e6);
                }
            }
            var frame = field(view, "candidateFrame");
            if (frame != null) {
                long nextGeneration = (long) accessor(frame, "generation");
                if (nextGeneration != generation) {
                    candidates++;
                    generation = nextGeneration;
                }
            }
            var ready = field(view, "readyBundle");
            if (ready != null) {
                long nextSerial = (long) accessor(ready, "serial");
                if (nextSerial != serial) {
                    overlays++;
                    serial = nextSerial;
                }
            }
        }

        void report(int index, double seconds) throws Exception {
            var progress = session.progress(null);
            var frame = field(view, "candidateFrame");
            var image = (BufferedImage) accessor(frame, "image");
            System.out.printf(
                    Locale.ROOT,
                    "view=%d trace=%dx%d fresh=%d freshFPS=%.2f candidateGenerationsObserved=%d"
                            + " overlaySerialsObserved=%d cancelled=%d wastedPaths=%d"
                            + " finalRevisionLatencyMs=%.3f%n",
                    index,
                    image.getWidth(),
                    image.getHeight(),
                    fresh,
                    fresh / seconds,
                    candidates,
                    overlays,
                    progress.cancelledJobs() - cancelledBefore,
                    progress.wastedPaths() - wastedBefore,
                    settleMillis);
            stats("view-" + index + "-fresh-interval-ms-including-final-hold", gapsMillis);
            stats("view-" + index + "-edit-to-paint-age-ms", agesMillis);
        }
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    private static long allocatedBytes() {
        return THREADS.isThreadAllocatedMemorySupported()
                        && THREADS.isThreadAllocatedMemoryEnabled()
                ? THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId())
                : -1;
    }

    private static void require(boolean success, EditorController controller) {
        if (!success) {
            throw new IllegalStateException(controller.state().status());
        }
    }

    private static void stats(String name, ArrayList<Double> values) {
        if (values.isEmpty()) {
            System.out.println(name + ": n/a");
            return;
        }
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        System.out.printf(
                Locale.ROOT,
                "%s mean=%.3f p50=%.3f p95=%.3f max=%.3f%n",
                name,
                Arrays.stream(sorted).average().orElseThrow(),
                sorted[sorted.length / 2],
                sorted[Math.min(sorted.length - 1, (int) (sorted.length * .95))],
                sorted[sorted.length - 1]);
    }

    private static void layout(Container container) {
        container.doLayout();
        for (var child : container.getComponents()) {
            if (child instanceof Container nested) {
                layout(nested);
            }
        }
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object accessor(Object target, String name) throws Exception {
        var method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        var task = new FutureTask<T>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
