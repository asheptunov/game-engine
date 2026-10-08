import di.Injector;

import profiling.FrameProfiler;

import scenes.viewport.ScenePresets;
import scenes.viewport.Viewport;

import java.awt.Canvas;
import java.awt.EventQueue;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.*;

/** Synthetic AWT event delivery under concurrent full viewport conversion; never opens a window. */
public class ResponsiveViewportBenchmark {
    public static void main(String[] args) throws Exception {
        boolean legacy = Arrays.asList(args).contains("legacy-lock");
        int workers = option(args, "workers", 14),
                tile = option(args, "tile", 32),
                events = option(args, "events", 100);
        var module = new MainModule();
        var injector = Injector.create(module);
        module.registerScenes(injector);
        var viewport = injector.get(Viewport.class);
        var state = viewport.state();
        var profiler = injector.get(FrameProfiler.class);
        state.resolution(1440, 900);
        ScenePresets.load(state, "glass");
        state.workers(workers);
        state.tileSize(tile);
        Runnable render =
                legacy
                        ? () -> {
                            synchronized (state) {
                                viewport.renderBlocking();
                            }
                        }
                        : viewport::render;
        if (!legacy) {
            long deadline = System.nanoTime() + 10_000_000_000L;
            do {
                render.run();
                Thread.sleep(2);
            } while ((profiler.renderProgress() == null
                            || profiler.renderProgress().shownGeneration() < 0)
                    && System.nanoTime() < deadline);
            if (profiler.renderProgress() == null
                    || profiler.renderProgress().shownGeneration() < 0)
                throw new AssertionError("Initial image did not arrive");
        }
        for (int i = 0; i < 10; i++) render.run();
        var stop = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        var moving = new AtomicBoolean();
        var motionImages = new AtomicInteger();
        var latest = new AtomicReference<FrameProfiler.RenderProgress>();
        var frames = new ArrayList<Long>();
        var initial = profiler.renderProgress();
        var thread =
                new Thread(
                        () -> {
                            FrameProfiler.RenderProgress previous = initial;
                            try {
                                while (!stop.get()) {
                                    long start = System.nanoTime();
                                    render.run();
                                    frames.add(System.nanoTime() - start);
                                    var progress = profiler.renderProgress();
                                    latest.set(progress);
                                    if (moving.get()
                                            && progress != null
                                            && initial != null
                                            && progress.shownGeneration() > initial.generation()
                                            && (previous == null
                                                    || progress.shownGeneration()
                                                            != previous.shownGeneration()
                                                    || progress.completedSamples()
                                                            != previous.completedSamples()))
                                        motionImages.incrementAndGet();
                                    previous = progress;
                                    Thread.sleep(1);
                                }
                            } catch (Throwable error) {
                                failure.set(error);
                            }
                        },
                        "responsive-benchmark-render");
        thread.setDaemon(true);
        thread.start();
        var canvas = new Canvas();
        var handling = new long[events];
        var delivery = new long[events];
        try {
            moving.set(true);
            for (int i = 0; i < events; i++) {
                int index = i;
                long posted = System.nanoTime();
                EventQueue.invokeAndWait(
                        () -> {
                            long start = System.nanoTime();
                            viewport.mouseMoved(
                                    new MouseEvent(
                                            canvas,
                                            MouseEvent.MOUSE_MOVED,
                                            0,
                                            0,
                                            500 + (index % 20) * 2,
                                            400 + (index % 13),
                                            0,
                                            false,
                                            0));
                            handling[index] = System.nanoTime() - start;
                            delivery[index] = System.nanoTime() - posted;
                        });
                Thread.sleep(16);
            }
            moving.set(false);
            long settle = System.nanoTime();
            if (!legacy)
                while (System.nanoTime() - settle < 10_000_000_000L) {
                    var progress = latest.get();
                    if (progress != null
                            && progress.shownGeneration() == progress.generation()
                            && progress.firstImageNanos() >= 0) break;
                    Thread.sleep(2);
                }
        } finally {
            stop.set(true);
            thread.join(10_000);
            viewport.close();
            profiler.close();
        }
        if (thread.isAlive()) throw new AssertionError("Render thread failed to stop");
        if (failure.get() != null) throw new AssertionError("Render failed", failure.get());
        System.out.println(
                "mode="
                        + (legacy ? "emulated P1 state lock" : "P2 async")
                        + "; glass 1440x900 sensor/display; workers="
                        + workers
                        + "; tile="
                        + tile
                        + "; events="
                        + events);
        System.out.printf(
                "handler median/p95 %.3f/%.3f ms; AWT synthetic delivery median/p95 %.3f/%.3f ms;"
                        + " display median/p95 %.3f/%.3f ms; frames=%d%n",
                percentile(handling, .5),
                percentile(handling, .95),
                percentile(delivery, .5),
                percentile(delivery, .95),
                percentile(frames.stream().mapToLong(Long::longValue).toArray(), .5),
                percentile(frames.stream().mapToLong(Long::longValue).toArray(), .95),
                frames.size());
        System.out.println("last progress=" + latest.get());
        System.out.println(
                "completed camera images displayed during continuous motion="
                        + (legacy ? "unmeasured (blocking mode)" : motionImages.get()));
        System.out.println(
                "Synthetic events only; no physical input or AWT presentation. legacy-lock emulates"
                        + " the previous full-frame monitor using the current transport code.");
    }

    private static int option(String[] args, String key, int fallback) {
        return Arrays.stream(args)
                .filter(s -> s.startsWith(key + "="))
                .map(s -> Integer.parseInt(s.substring(key.length() + 1)))
                .findFirst()
                .orElse(fallback);
    }

    private static double percentile(long[] values, double percentile) {
        Arrays.sort(values);
        return values[Math.max(0, (int) Math.ceil(values.length * percentile) - 1)] / 1e6;
    }
}
