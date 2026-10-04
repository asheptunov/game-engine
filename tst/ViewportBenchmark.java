import di.Injector;
import profiling.FrameProfiler;
import rendering.Checkerboard;
import rendering.Eraser;
import scenes.viewport.Viewport;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import scenes.Scene;

/** Headless full-resolution benchmark; timings exclude AWT presentation. */
public class ViewportBenchmark {
    public static void main(String[] args) {
        var module = new MainModule();
        var injector = Injector.create(module);
        module.registerScenes(injector);
        var viewport = injector.get(Viewport.class);
        var eraser = injector.get(Eraser.class);
        var checker = injector.get(Checkerboard.class);
        var profiler = injector.get(FrameProfiler.class);
        var pipeline = module.renderer(List.of(eraser, checker, viewport), profiler,
                new AtomicReference<Scene>(viewport));
        long[] times = new long[100];
        for (int i = -50; i < times.length; i++) {
            long start = System.nanoTime();
            pipeline.render();
            if (i >= 0) times[i] = System.nanoTime() - start;
        }
        Arrays.sort(times);
        System.out.printf("Headless frame: median %.2f ms, p95 %.2f ms%n", times[50]/1e6, times[94]/1e6);
    }
}
