package gpu;

import engine.*;

import game.*;

import math.Vec3;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

/** Fixed-camera scene-only comparisons, separate from live timing measurements. */
public final class FoveatedPreview {
    public static void main(String[] args) throws Exception {
        var directory = Path.of("out/performance/foveated-preview");
        Files.createDirectories(directory);
        var navigation = new GameNavigation();
        navigation.fixedResolution(1920, 1080);
        navigation.pose(new Vec3(10.879049f, 2.7514815f, 22.884184f), 1.251, .079);
        var clock = new DayNightCycle();
        clock.hour(6.396);
        var light = clock.atmosphericLighting(Atmosphere.Parameters.clearAir());
        var world =
                new WorldSnapshot(
                        0,
                        AdventureWorld.create().instances(),
                        List.of(),
                        List.of(light.sun()),
                        light.sky());
        var settings = RenderSettings.defaults().withPathDepth(8);
        var view = navigation.view();
        try (var kernel = new GpuKernel()) {
            var reference = render(kernel, world, view, settings, 64, directory.resolve("uniform"));
            var foveated =
                    render(
                            kernel,
                            world,
                            view,
                            settings.withFoveated(true),
                            64,
                            directory.resolve("foveated"));
            var half =
                    render(
                            kernel,
                            world,
                            new RenderView(view.camera(), 960, 540),
                            settings,
                            64,
                            directory.resolve("half"));
            var enlarged = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
            var g = enlarged.createGraphics();
            g.drawImage(half, 0, 0, 1920, 1080, null);
            g.dispose();
            ImageIO.write(enlarged, "png", directory.resolve("half-enlarged-64.png").toFile());
            var crop = new BufferedImage(1200, 300, BufferedImage.TYPE_INT_RGB);
            g = crop.createGraphics();
            int index = 0;
            for (var image : new BufferedImage[] {reference, foveated, enlarged}) {
                g.drawImage(
                        image, index * 400, 0, (index + 1) * 400, 300, 760, 390, 1160, 690, null);
                index++;
            }
            g.dispose();
            ImageIO.write(crop, "png", directory.resolve("center-full-foveated-half.png").toFile());
            double centerError = 0, halfError = 0;
            int count = 0;
            for (int y = 390; y < 690; y++)
                for (int x = 760; x < 1160; x++) {
                    int a = reference.getRGB(x, y),
                            b = foveated.getRGB(x, y),
                            c = enlarged.getRGB(x, y);
                    for (int shift : new int[] {0, 8, 16}) {
                        centerError += Math.pow(((a >> shift) & 255) - ((b >> shift) & 255), 2);
                        halfError += Math.pow(((a >> shift) & 255) - ((c >> shift) & 255), 2);
                        count++;
                    }
                }
            System.out.printf(
                    java.util.Locale.ROOT,
                    "64-spp center encoded RGB RMSE: foveated=%.6f, half=%.6f%n",
                    Math.sqrt(centerError / count),
                    Math.sqrt(halfError / count));
            long rays = 0;
            for (int y = 0; y < 1080; y += 8)
                for (int x = 0; x < 1920; x += 8) {
                    int rate = GpuFoveation.rate(x, y, 1920, 1080);
                    rays +=
                            (Math.min(8, 1920 - x) + rate - 1)
                                    / rate
                                    * ((Math.min(8, 1080 - y) + rate - 1) / rate);
                }
            System.out.printf(
                    java.util.Locale.ROOT,
                    "Primary paths per 1080p pass: %d / %d (%.3f%%)%n",
                    rays,
                    1920 * 1080,
                    100. * rays / (1920 * 1080));
        }
    }

    private static BufferedImage render(
            GpuKernel kernel,
            WorldSnapshot world,
            RenderView view,
            RenderSettings settings,
            int samples,
            Path prefix)
            throws Exception {
        var pixels = new float[view.width() * view.height() * 3];
        BufferedImage image = null;
        for (int sample = 0; sample < samples; sample++) {
            kernel.trace(world, view, settings, sample, pixels);
            if (sample == 0 || sample == samples - 1) {
                image = new BufferedImage(view.width(), view.height(), BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < view.height(); y++)
                    for (int x = 0; x < view.width(); x++) {
                        int i = (y * view.width() + x) * 3;
                        image.setRGB(
                                x,
                                view.height() - 1 - y,
                                DisplayMapping.pack(pixels[i], pixels[i + 1], pixels[i + 2], 1));
                    }
                ImageIO.write(image, "png", Path.of(prefix + "-" + (sample + 1) + ".png").toFile());
            }
        }
        return image;
    }
}
