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
import scenes.viewport.ScenePresets;

/** Headless full-resolution benchmark; timings exclude AWT presentation. */
public class ViewportBenchmark {
    public static void main(String[] args) {
        var module = new MainModule();
        var injector = Injector.create(module);
        module.registerScenes(injector);
        var viewport = injector.get(Viewport.class);
        boolean close = Arrays.asList(args).contains("close");
        ScenePresets.load(viewport.state(), Arrays.asList(args).contains("volume-room") ? "volume-room"
                : Arrays.asList(args).contains("mesh-room") ? "mesh-room"
                : Arrays.asList(args).contains("rough-room") ? "rough-room"
                : Arrays.asList(args).contains("glass-inside") ? "glass-inside"
                : Arrays.asList(args).contains("glass") ? "glass"
                : Arrays.asList(args).contains("bounce-room") ? "bounce-room"
                : Arrays.asList(args).contains("playground") ? "playground" : "triangle");
        for (String arg : args) {
            if (arg.startsWith("size=")) viewport.state().resolution(Integer.parseInt(arg.substring(5)));
            if (arg.startsWith("depth=")) viewport.state().pathDepth(Integer.parseInt(arg.substring(6)));
            if (arg.startsWith("samples=")) viewport.state().samplesPerFrame(Integer.parseInt(arg.substring(8)));
            if (arg.startsWith("seed=")) viewport.state().seed(Long.parseLong(arg.substring(5)));
            if (arg.equals("brute")) viewport.state().acceleration(false);
            if (arg.startsWith("detail=")) {
                var mesh=scenes.viewport.IndexedMesh.sphere(Integer.parseInt(arg.substring(7)));
                var instances=viewport.state().instances();
                for(int i=0;i<instances.size();i++){var o=instances.get(i);if(o.geometry() instanceof scenes.viewport.IndexedMesh)instances.set(i,new scenes.viewport.SceneInstance(o.name(),mesh,o.transform(),o.material()));}
            }
        }
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
        // Publish the final completed frame before reading its counters.
        profiler.beginFrame();
        Arrays.sort(times);
        System.out.printf("Headless frame: median %.2f ms, p95 %.2f ms%n", times[50]/1e6, times[94]/1e6);
        var frame = profiler.snapshot().frames().getLast();
        System.out.println("Scene: " + viewport.state().preset() + (close ? " close" : "")
                + "; acceleration="+(viewport.state().acceleration()?"bvh":"brute")
                + "; primitives="+viewport.state().instances().stream().mapToInt(o->o.geometry().size()).sum()
                + "; depth=" + viewport.state().pathDepth() + "; spp/batch max=" + viewport.state().samplesPerFrame()
                + "; accumulated=" + viewport.state().accumulatedSamples() + "; seed=" + viewport.state().seed()
                + (viewport.state().pathDepth() == 0 ? "; pixel centers" : "; jittered progressive") + "; rays: " + frame.rays());
        System.out.printf("Trace throughput (primary + continuation + visibility): %.2f Mrays/s%n",
                (frame.rays().primary() + frame.trace().continuationRays() + frame.rays().shadows())*1000./frame.rays().traceNanos());
        System.out.println("Trace profile: " + frame.trace());
        System.out.println("Trace execution samples: " + profiler.traceSamples());
        profiler.close();
    }
}
