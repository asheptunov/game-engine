package rendering;

import static harness.Assertions.*;

import harness.SuiteRunner;
import harness.Test;

import misc.monads.Result;

import ui.KeyAction;
import ui.console.Console;

import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

public class AwtPrinterTest {
    @Test
    void matchesNativeTextWithCasePunctuationSizeAndClipping() {
        for (int size : new int[] {12, 16, 24}) {
            var actual = new PixelRaster(260, 50, Color.NamedColor.BLACK);
            var printer = new AwtPrinter(actual, 16);
            String text = "AaZz 019 /:[]_gjpq";
            printer.print(text, -3, -2, Printer.Size.of(size), Printer.Spacing.of(1));
            var expected = new BufferedImage(260, 50, BufferedImage.TYPE_INT_RGB);
            var g = expected.createGraphics();
            try {
                g.setFont(AwtText.monospaced(size));
                g.setColor(java.awt.Color.WHITE);
                int x = -3;
                for (char c : text.toCharArray()) {
                    g.drawString(String.valueOf(c), x, -2 + g.getFontMetrics().getAscent());
                    x += g.getFontMetrics().charWidth('M') + 1;
                }
            } finally {
                g.dispose();
            }
            for (int y = 0; y < 50; y++)
                for (int x = 0; x < 260; x++)
                    assertEquals(expected.getRGB(x, y), actual.pixel(x, y).argbInt32());
        }
    }

    @Test
    void consolePreservesAnsiColorsAndWritesPreview() throws Exception {
        var raster = new PixelRaster(720, 240, Color.NamedColor.BLACK);
        var console =
                Console.withAwtText(
                        raster,
                        () -> {},
                        100,
                        _ ->
                                Result.success(
                                        "Viewport console - Native AWT text\n"
                                                + "view preset volume-room | resolution 200 | depth"
                                                + " 12\n"
                                                + "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                                                + " abcdefghijklmnopqrstuvwxyz\n"
                                                + "0123456789 /:[](){} <> +-= _ gjpq\n"
                                                + Color.AnsiColor.RED.formatted()
                                                + "ERROR"
                                                + Color.AnsiColor.NONE.formatted()
                                                + " normal text\n"
                                                + "Wrapping: "
                                                + "readable text ".repeat(9)));
        // Blank submissions are intentionally ignored by Console; submit a command for the fixture.
        console.accept(
                new KeyAction(
                        KeyAction.Key.LOWER_V,
                        KeyAction.Key.LOWER_V,
                        KeyAction.Action.PRESS,
                        new KeyAction.Modifiers(
                                false, false, false, false, false, false, false, false)));
        console.accept(
                new KeyAction(
                        KeyAction.Key.ENTER,
                        KeyAction.Key.ENTER,
                        KeyAction.Action.PRESS,
                        new KeyAction.Modifiers(
                                false, false, false, false, false, false, false, false)));
        console.render();
        int red = 0, white = 0;
        var image = new BufferedImage(raster.width(), raster.height(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < raster.height(); y++)
            for (int x = 0; x < raster.width(); x++) {
                var pixel = raster.pixel(x, y);
                if ((pixel.r() & 255) > 100 && (pixel.g() & 255) < 50) red++;
                if ((pixel.r() & 255) > 200 && (pixel.g() & 255) > 200) white++;
                image.setRGB(x, y, pixel.rgbInt24());
            }
        assertTrue(red > 0);
        assertTrue(white > red);
        ImageIO.write(image, "png", new File("out/cli/console-awt-preview.png"));
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
