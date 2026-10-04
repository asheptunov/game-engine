import di.Injector;
import profiling.FrameProfiler;
import rendering.Checkerboard;
import rendering.Eraser;
import scenes.viewport.Viewport;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import scenes.Scene;
import math.Vec3;
import scenes.viewport.objects.Rect;

/** Headless full-resolution benchmark; timings exclude AWT presentation. */
public class ViewportBenchmark {
    public static void main(String[] args) {
        var module = new MainModule();
        var injector = Injector.create(module);
        module.registerScenes(injector);
        var viewport = injector.get(Viewport.class);
        boolean close = Arrays.asList(args).contains("close");
        if (close) {
            var state = viewport.state();
            var delta = new Vec3(0, 0, 9);
            state.eye(state.eye().add(delta));
            var sensor = state.cameraSensor();
            state.cameraSensor(new Rect(sensor.origin().add(delta), sensor.edge1(), sensor.edge2()));
        }
        var eraser = injector.get(Eraser.class);
        var checker = injector.get(Checkerboard.class);
        var profiler = injector.get(FrameProfiler.class);
        if (Arrays.asList(args).contains("details")) profiler.toggleTraceDetails();
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
        var frame = profiler.snapshot().frames().getLast();
        System.out.println("Scene: " + (close ? "close" : "default") + "; rays: " + frame.rays());
        System.out.println("Trace profile: " + frame.trace());
        System.out.println("Trace execution samples: " + profiler.traceSamples());
        profiler.close();
    }
}
