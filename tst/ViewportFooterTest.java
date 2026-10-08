import static harness.Assertions.*;

import di.Injector;

import harness.SuiteRunner;
import harness.Test;

import rendering.Raster;

import scenes.viewport.Viewport;

import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

public class ViewportFooterTest {
    @Test
    void bothFooterLinesHaveGlyphsAndTransparentGaps() throws Exception {
        var module = new MainModule();
        var injector = Injector.create(module);
        module.registerScenes(injector);
        try (var viewport = injector.get(Viewport.class)) {
            viewport.state().paused(true);
            viewport.render();
            var raster = injector.get(Raster.class);
            // A paused viewport with no publication has a black background. Each text
            // line must contain ink AND gaps, rather than the old solid white strip.
            for (int offset : new int[] {48, 26}) {
                int white = 0, black = 0;
                for (int y = raster.height() - offset; y < raster.height() - offset + 19; y++) {
                    for (int x = 12; x < 300; x++) {
                        int rgb = raster.pixel(x, y).rgbInt24();
                        if (rgb == 0xffffff) white++;
                        if (rgb == 0) black++;
                    }
                }
                assertTrue(white > 100);
                assertTrue(black > white);
            }
            var image = new BufferedImage(raster.width(), 60, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 60; y++)
                for (int x = 0; x < raster.width(); x++)
                    image.setRGB(x, y, raster.pixel(x, raster.height() - 60 + y).rgbInt24());
            ImageIO.write(image, "png", new File("out/cli/viewport-footer-preview.png"));
        }
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
