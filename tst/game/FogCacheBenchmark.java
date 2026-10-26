package game;

import engine.RenderEngine;
import engine.RenderSession;
import engine.RenderView;
import engine.WorldSnapshot;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Matched saved-view measurements; cache rebuild, publication and image conversion are timed. */
public final class FogCacheBenchmark {
    private FogCacheBenchmark() {}

    private record Case(
            String backend,
            int width,
            int daySeconds,
            double startHour,
            int frames,
            int fogDownsample) {}

    public static void main(String[] args) throws Exception {
        var capture = GameCapture.load(Path.of(args[0]));
        String backend = args.length > 1 ? args[1] : "gpu";
        String[] widths = (args.length > 2 ? args[2] : "418,836,1600").split(",");
        double startHour = args.length > 3 ? Double.parseDouble(args[3]) : 7;
        int frames = args.length > 4 ? Integer.parseInt(args[4]) : 20;
        int divisor = args.length > 5 ? Integer.parseInt(args[5]) : 1;
        System.out.println(
                "reference,backend,round,width,height,daySeconds,startHour,gpuMedianMs,gpuP95Ms,fogBuildMedianMs,transferMedianMs,traceMedianMs,copyMedianMs,totalMedianMs,totalP95Ms,fogDownsample");
        for (int round = 0; round < 2; round++) {
            for (int index = 0; index < widths.length; index++) {
                int width =
                        Integer.parseInt(widths[round == 0 ? index : widths.length - 1 - index]);
                for (int duration : new int[] {0, 600, 30}) {
                    measure(
                            capture,
                            new Case(backend, width, duration, startHour, frames, divisor),
                            round);
                }
            }
        }
    }

    private static WorldSnapshot world(GameCapture.Loaded capture, Case config, int pass) {
        var original = capture.world(capture.manifest().displayed().world());
        var clock = new DayNightCycle();
        clock.hour(
                config.startHour()
                        + (config.daySeconds() == 0 ? 0 : pass * .1 * 24 / config.daySeconds()));
        var light = clock.atmosphericLighting(original.sky().atmosphere().parameters());
        return new WorldSnapshot(
                pass,
                original.instances(),
                original.legacyObjects(),
                List.of(light.sun()),
                light.sky().withFog(original.sky().fog()));
    }

    private static double status(String text, String key) {
        var match = Pattern.compile(key + " ([0-9.]+) ms").matcher(text);
        return match.find() ? Double.parseDouble(match.group(1)) : Double.NaN;
    }

    private static void measure(GameCapture.Loaded capture, Case config, int round)
            throws Exception {
        var shown = capture.manifest().displayed();
        int height =
                Math.round(config.width() * (float) shown.view().height() / shown.view().width());
        var view = new RenderView(shown.view().camera(), config.width(), height);
        var settings =
                shown.settings()
                        .withSampleTarget(1)
                        .withSamplesPerBatch(1)
                        .withPaused(false)
                        .withInteractive(false, 16.67, 0, 0)
                        .withTemporal(false, 0)
                        .withFogDownsample(config.fogDownsample());
        var signal = new Semaphore(0);
        double[][] measurements = new double[6][config.frames()];
        try (var session =
                RenderEngine.openSession(
                        world(capture, config, 0), view, settings, config.backend())) {
            session.onImageAvailable(signal::release);
            for (int pass = 0; pass < config.frames() + 10; pass++) {
                var scene = world(capture, config, pass);
                long start = System.nanoTime();
                session.update(scene, view, settings.withRestartRevision(pass));
                session.request();
                if (!signal.tryAcquire(120, TimeUnit.SECONDS)) {
                    throw new AssertionError("Missing publication: " + session.status());
                }
                collect(session, measurements, pass - 10, start, shown.exposure());
            }
        }
        print(config, round, height, measurements);
    }

    private static void collect(
            RenderSession session, double[][] values, int index, long start, float exposure) {
        try (var image = session.acquireImage()) {
            if (image == null
                    || image.samples() != 1
                    || image.generation() != session.progress(null).requestedGeneration()) {
                throw new AssertionError("Wrong publication");
            }
            long copyStart = System.nanoTime();
            GameRenderer.copyImage(image, exposure);
            long finished = System.nanoTime();
            if (index >= 0) {
                String text = session.status();
                values[0][index] = status(text, "execute");
                values[1][index] = status(text, "fog build");
                values[2][index] = status(text, "transfer");
                values[3][index] = image.traceNanos() / 1e6;
                values[4][index] = (finished - copyStart) / 1e6;
                values[5][index] = (finished - start) / 1e6;
            }
        }
    }

    private static void print(Case config, int round, int height, double[][] values) {
        for (var column : values) {
            Arrays.sort(column);
        }
        int median = config.frames() / 2;
        int p95 = (int) Math.ceil(config.frames() * .95) - 1;
        System.out.printf(
                Locale.ROOT,
                "%b,%s,%d,%d,%d,%d,%.2f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d%n",
                Boolean.getBoolean("engine.fogSunReference"),
                config.backend(),
                round,
                config.width(),
                height,
                config.daySeconds(),
                config.startHour(),
                values[0][median],
                values[0][p95],
                values[1][median],
                values[2][median],
                values[3][median],
                values[4][median],
                values[5][median],
                values[5][p95],
                config.fogDownsample());
    }
}
