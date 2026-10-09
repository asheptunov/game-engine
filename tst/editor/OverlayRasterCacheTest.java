package editor;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertFalse;
import static harness.Assertions.assertTrue;

import editor.overlay.OverlayGeometry;

import harness.Test;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

public class OverlayRasterCacheTest {
    public static void main(String[] args) {
        harness.SuiteRunner.runThis();
    }

    @Test
    public void cachedPixelsMatchDirectShapesAtFractionalDisplayScalesAndOrigins() {
        for (double scale : new double[] {1, 1.25, 1.5, 2}) {
            for (double offset : new double[] {0, .25, .5}) {
                var cache = new OverlayRasterCache();
                var frame = frame();
                var area = new Rectangle(5, 8, 160, 100);
                var direct = background();
                var cached = background();
                var transform = new AffineTransform(scale, 0, 0, scale, 11 + offset, 13 + offset);
                var graphics = direct.createGraphics();
                graphics.setTransform(transform);
                graphics.setClip(0, 0, 180, 120);
                drawShapes(graphics, area);
                graphics.dispose();
                var source = cached.createGraphics();
                source.setTransform(transform);
                source.setClip(0, 0, 180, 120);
                assertTrue(
                        cache.paint(
                                source,
                                180,
                                120,
                                area,
                                frame,
                                true,
                                OverlayRasterCacheTest::drawShapes));
                source.dispose();
                assertPixelsClose(direct, cached);
            }
        }
    }

    @Test
    public void unchangedContextSkipsRasterizationAndChangedContextInvalidates() {
        var cache = new OverlayRasterCache();
        var image = background();
        var graphics = image.createGraphics();
        var area = new Rectangle(5, 8, 160, 100);
        var frame = frame();
        var count = new int[1];
        java.util.function.BiConsumer<Graphics2D, Rectangle> render =
                (source, content) -> {
                    count[0]++;
                    drawShapes(source, content);
                };
        try {
            assertTrue(cache.paint(graphics, 180, 120, area, frame, true, render));
            assertTrue(cache.paint(graphics, 180, 120, area, frame, true, render));
            assertEquals(1, count[0]);
            assertTrue(cache.paint(graphics, 180, 120, area, frame, false, render));
            assertEquals(2, count[0]);
            var resizedArea = new Rectangle(5, 8, 150, 100);
            assertTrue(cache.paint(graphics, 180, 120, resizedArea, frame, false, render));
            assertEquals(3, count[0]);
            assertTrue(cache.paint(graphics, 180, 120, resizedArea, frame(), false, render));
            assertEquals(4, count[0]);
            graphics.scale(1.5, 1.5);
            assertTrue(cache.paint(graphics, 180, 120, area, frame, false, render));
            assertEquals(5, count[0]);
            graphics.setFont(graphics.getFont().deriveFont(18f));
            assertTrue(cache.paint(graphics, 180, 120, area, frame, false, render));
            assertEquals(6, count[0]);
            cache.clear();
            assertTrue(cache.paint(graphics, 180, 120, area, frame, false, render));
            assertEquals(7, count[0]);
        } finally {
            graphics.dispose();
        }
    }

    @Test
    public void unsupportedTransformsCompositesAndLargeBuffersUseDirectFallback() {
        var cache = new OverlayRasterCache();
        var graphics = background().createGraphics();
        var area = new Rectangle(5, 8, 160, 100);
        var frame = frame();
        try {
            assertFalse(
                    cache.paint(
                            graphics,
                            4000,
                            4000,
                            area,
                            frame,
                            true,
                            OverlayRasterCacheTest::drawShapes));
            graphics.shear(.1, 0);
            assertFalse(
                    cache.paint(
                            graphics,
                            180,
                            120,
                            area,
                            frame,
                            true,
                            OverlayRasterCacheTest::drawShapes));
            graphics.setTransform(new AffineTransform());
            graphics.setComposite(AlphaComposite.SrcOver.derive(.5f));
            assertFalse(
                    cache.paint(
                            graphics,
                            180,
                            120,
                            area,
                            frame,
                            true,
                            OverlayRasterCacheTest::drawShapes));
        } finally {
            graphics.dispose();
        }
    }

    private static OverlayGeometry.Frame frame() {
        var overlays = new OverlayGeometry();
        var snapshot = StarterScene.create().snapshot();
        return overlays.project(
                overlays.prepare(snapshot, null),
                StarterScene.canonicalCamera(),
                OverlayGeometry.GizmoMode.NONE,
                180,
                120,
                72);
    }

    private static BufferedImage background() {
        var image = new BufferedImage(420, 320, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(51, 91, 137));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        return image;
    }

    private static void drawShapes(Graphics2D graphics, Rectangle area) {
        graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(255, 100, 80, 180));
        graphics.drawLine(area.x, area.y, area.x + area.width, area.y + area.height);
        graphics.fillOval(10, 10, 24, 24);
        graphics.fillOval(18, 15, 24, 24);
        graphics.setColor(new Color(80, 255, 100, 180));
        graphics.drawRect(16, 18, 32, 24);
        graphics.drawString("XYZ", 60, 65);
    }

    private static void assertPixelsClose(BufferedImage first, BufferedImage second) {
        // Transparent premultiplied compositing can differ by a few channel rounding units.
        for (int y = 0; y < first.getHeight(); y++) {
            for (int x = 0; x < first.getWidth(); x++) {
                int expected = first.getRGB(x, y);
                int actual = second.getRGB(x, y);
                for (int shift : new int[] {0, 8, 16}) {
                    int difference =
                            Math.abs(((expected >> shift) & 255) - ((actual >> shift) & 255));
                    if (difference > 3) {
                        throw new AssertionError(
                                "Raster differs at " + x + "," + y + " by " + difference);
                    }
                }
            }
        }
    }
}
