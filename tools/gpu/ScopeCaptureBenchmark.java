package gpu;

import engine.*;

import game.ScopeCaptureScene;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

import javax.imageio.ImageIO;

/** Replays frozen capture lighting and camera; output PNGs have no UI or bloom. */
public final class ScopeCaptureBenchmark {
    public static void main(String[] args) throws Exception {
        var source = Path.of(args[0]);
        var output = Path.of(args[1]);
        Files.createDirectories(output);
        String mode = args.length > 2 ? args[2] : "quality";
        try (var csv = Files.newBufferedWriter(output.resolve(mode + ".csv"))) {
            csv.write("optic,mode,frame,trace_ms,reconstruction_ms,fsr_ms,host_ms\n");
            boolean reverse = args.length > 3 && args[3].equals("reverse");
            for (boolean gameplay :
                    reverse ? new boolean[] {true, false} : new boolean[] {false, true}) {
                if (args.length > 4 && args[4].equals("gameplay") && !gameplay) continue;
                var frame = ScopeCaptureScene.load(source, gameplay);
                String optic = gameplay ? "gameplay" : "glass";
                for (boolean temporal : new boolean[] {false, true}) {
                    if (args.length > 5 && args[5].equals("nrd-fsr") && !temporal) continue;
                    var settings =
                            frame.settings()
                                    .withPaused(false)
                                    .withInteractive(false, 16.67, 0, 0)
                                    .withTemporal(temporal, 1)
                                    .withDenoiser(Denoiser.NRD_RELAX)
                                    .withLightingScale(1)
                                    .withUpscaler(
                                            temporal ? Upscaler.FSR_PERFORMANCE : Upscaler.OFF);
                    String label = temporal ? "nrd-fsr" : "raw";
                    try (var kernel = new GpuKernel()) {
                        var raw = new GpuKernel.Snapshot();
                        var shown = new GpuKernel.Snapshot();
                        int count = mode.equals("timing") ? 150 : 64;
                        for (int i = 0; i < count; i++) {
                            var current =
                                    mode.equals("motion")
                                            ? ScopeCaptureScene.translated(frame, i * .002f)
                                            : frame;
                            var input = GpuLighting.sampled(current.view(), settings);
                            kernel.trace(
                                    current.world(),
                                    input,
                                    settings,
                                    mode.equals("motion") ? 0 : i,
                                    new GpuKernel.Destination(
                                            null,
                                            null,
                                            raw,
                                            frame.exposure(),
                                            shown,
                                            current.view()));
                            if (i >= 30 || !mode.equals("timing"))
                                csv.write(
                                        String.format(
                                                Locale.ROOT,
                                                "%s,%s,%d,%.6f,%.6f,%.6f,%.6f%n",
                                                optic,
                                                label,
                                                i,
                                                kernel.executeNanos / 1e6,
                                                kernel.reconstructionNanos / 1e6,
                                                kernel.upscalingNanos / 1e6,
                                                kernel.hostNanos / 1e6));
                            if (!mode.equals("timing")
                                    && (i == 0 || i == 7 || i == 31 || i == count - 1)) {
                                var values = (temporal ? shown : raw).read(kernel.gpu);
                                var image =
                                        new BufferedImage(
                                                current.view().width(),
                                                current.view().height(),
                                                BufferedImage.TYPE_INT_RGB);
                                var pixels =
                                        ((java.awt.image.DataBufferInt)
                                                        image.getRaster().getDataBuffer())
                                                .getData();
                                DisplayCopy.interleaved(
                                        values,
                                        pixels,
                                        image.getWidth(),
                                        image.getHeight(),
                                        frame.exposure());
                                ImageIO.write(
                                        image,
                                        "png",
                                        output.resolve(optic + "-" + label + "-" + (i + 1) + ".png")
                                                .toFile());
                            }
                        }
                        raw.source.close();
                        shown.source.close();
                    }
                    System.out.println(optic + " " + label + " " + mode + " complete");
                }
            }
        }
    }
}
