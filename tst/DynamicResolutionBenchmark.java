import di.Injector;

import math.Vec3;

import profiling.FrameProfiler;
import profiling.PerformanceOverlay;

import rendering.Raster;

import scenes.viewport.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Locale;

import javax.imageio.ImageIO;

/** Same elapsed-time camera path in both modes; no window, overlay cost or AWT submission. */
public class DynamicResolutionBenchmark {
    public static void main(String[] args) throws Exception {
        System.out.println(
                "Glass native 1440x900, depth 8, seed 1, workers 14, tile 32, 144Hz nominal"
                        + " pacing");
        System.out.println(
                "3s warmup motion + 4s measured motion + stationary convergence; final 2s"
                        + " fresh-image metrics");
        for (boolean auto : new boolean[] {false, true, true, false}) {
            var module = new MainModule();
            var injector = Injector.create(module);
            module.registerScenes(injector);
            try (var viewport = injector.get(Viewport.class);
                    var p = injector.get(FrameProfiler.class)) {
                var s = viewport.state();
                s.resolution(1440, 900);
                ScenePresets.load(s, "glass");
                s.interactive(auto);
                s.sampleTarget(8);
                s.samplesPerFrame(8);
                var eye = s.eye();
                long start = System.nanoTime();
                int previousWidth = 1440, changes = 0, minWidth = 1440, maxWidth = 0;
                double stopFull = -1;
                FrameProfiler.ImageSnapshot moving = null;
                do {
                    long before = System.nanoTime();
                    double seconds = (before - start) / 1e9;
                    if (seconds < 7)
                        synchronized (s) {
                            s.eye(eye.add(new Vec3((float) Math.sin(seconds * 1.5) * .1f, 0, 0)));
                        }
                    p.beginFrame();
                    viewport.render();
                    p.endFrame();
                    if (seconds < 7) {
                        int width = s.sampledWidth();
                        if (seconds >= 3) {
                            minWidth = Math.min(minWidth, width);
                            maxWidth = Math.max(maxWidth, width);
                        }
                        if (width != previousWidth) changes++;
                        previousWidth = width;
                    }
                    if (seconds >= 7 && moving == null) {
                        moving = p.imageSnapshot();
                        if (auto && args.length > 0 && args[0].equals("preview")) {
                            p.toggle();
                            injector.get(PerformanceOverlay.class).render();
                            var r = injector.get(Raster.class);
                            var image =
                                    new BufferedImage(
                                            r.width(), r.height(), BufferedImage.TYPE_INT_RGB);
                            for (int y = 0; y < r.height(); y++)
                                for (int x = 0; x < r.width(); x++)
                                    image.setRGB(x, y, r.pixel(x, y).rgbInt24());
                            ImageIO.write(image, "png", new File("out/cli/p4-live-preview.png"));
                        }
                    }
                    var latest = p.latestFrame();
                    var progress = p.renderProgress();
                    if (seconds >= 7
                            && latest != null
                            && latest.rays() != null
                            && latest.trace() != null
                            && progress != null
                            && progress.generation() == progress.shownGeneration()
                            && progress.completedSamples() > 0
                            && latest.rays().width() == 1440
                            && s.sampledWidth() == 1440
                            && latest.trace().accumulatedSamples() >= 1
                            && stopFull < 0) stopFull = (System.nanoTime() - start) / 1e6 - 7000;
                    if (seconds >= 9 && s.accumulatedSamples() >= 8) break;
                    long wait = 6_944_444 - (System.nanoTime() - before);
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                } while (System.nanoTime() - start < 12_000_000_000L);
                System.out.printf(
                        Locale.ROOT,
                        "%s: fresh %.1f FPS, interval avg/p95 %.1f/%.1fms, at-update age p95"
                                + " %.1fms, grids width %d..%d, changes %d, full after stop %.1fms,"
                                + " final %dx%d %d spp%n",
                        auto ? "auto" : "fixed",
                        moving.fps(),
                        moving.meanMs(),
                        moving.p95Ms(),
                        moving.ageP95Ms(),
                        minWidth,
                        maxWidth,
                        changes,
                        stopFull,
                        s.sampledWidth(),
                        s.sampledHeight(),
                        s.accumulatedSamples());
            }
        }
    }
}
