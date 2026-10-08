import di.Injector;

import math.Vec3;

import profiling.FrameProfiler;

import scenes.viewport.*;

/** Paced live tracing under repeatable motion, without a window or AWT presentation. */
public class FreshImageBenchmark {
    public static void main(String[] args) throws Exception {
        System.out.println(
                "Glass depth 8, seed 1, workers 14, tile 32, display 1440x900; nominal 144 Hz"
                        + " pacing");
        System.out.println(
                "Each case: 3s warmup + 3s continuous motion; no overlay, AWT submission, or"
                        + " physical input");
        for (double scale : new double[] {1, .5, .25}) {
            var module = new MainModule();
            var injector = Injector.create(module);
            module.registerScenes(injector);
            try (var viewport = injector.get(Viewport.class);
                    var p = injector.get(FrameProfiler.class)) {
                var s = viewport.state();
                s.resolution((int) (1440 * scale), (int) (900 * scale));
                ScenePresets.load(s, "glass");
                var eye = s.eye();
                long start = System.nanoTime();
                int frame = 0;
                do {
                    long before = System.nanoTime();
                    synchronized (s) {
                        s.eye(eye.add(new Vec3((float) Math.sin(frame++ * .02) * .1f, 0, 0)));
                    }
                    p.beginFrame();
                    viewport.render();
                    p.endFrame();
                    long wait = 6_944_444 - (System.nanoTime() - before);
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                } while (System.nanoTime() - start < 6_000_000_000L);
                var images = p.imageSnapshot();
                var display = p.snapshot();
                System.out.printf(
                        "sensor %dx%d: fresh %.1f FPS, interval avg/p95 %.1f/%.1fms, hold %.1fms,"
                                + " age %.1fms; display %.1f FPS%n",
                        s.sensorPixelsW(),
                        s.sensorPixelsH(),
                        images.fps(),
                        images.meanMs(),
                        images.p95Ms(),
                        images.holdMs(),
                        images.ageMs(),
                        display.fps());
            }
        }
    }
}
