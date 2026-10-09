package editor;

import editor.overlay.OverlayGeometry;

import java.awt.AlphaComposite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.function.BiConsumer;

/** One reusable overlay raster per view, owned exclusively by Swing's event dispatch thread. */
final class OverlayRasterCache {
    // At most 16 MiB of pixel payload per view. Larger displays retain direct drawing.
    private static final long MAX_PIXELS = 4_194_304;
    private BufferedImage image;
    private OverlayGeometry.Frame frame;
    private Rectangle area;
    private boolean wireframe;
    private Font font;
    private RenderingHints hints;
    private double scaleX;
    private double scaleY;
    private double fractionX;
    private double fractionY;

    boolean paint(
            Graphics2D source,
            int width,
            int height,
            Rectangle content,
            OverlayGeometry.Frame overlay,
            boolean showWireframe,
            BiConsumer<Graphics2D, Rectangle> rasterize) {
        var transform = source.getTransform();
        double nextScaleX = transform.getScaleX();
        double nextScaleY = transform.getScaleY();
        if (transform.getShearX() != 0
                || transform.getShearY() != 0
                || nextScaleX <= 0
                || nextScaleY <= 0
                || !source.getComposite().equals(AlphaComposite.SrcOver)) {
            return false;
        }
        // Preserve physical pixel alignment even at fractional display scales and pane origins.
        double nextFractionX = transform.getTranslateX() - Math.floor(transform.getTranslateX());
        double nextFractionY = transform.getTranslateY() - Math.floor(transform.getTranslateY());
        double pixelWidth = Math.ceil(width * nextScaleX + nextFractionX);
        double pixelHeight = Math.ceil(height * nextScaleY + nextFractionY);
        if (!Double.isFinite(pixelWidth)
                || !Double.isFinite(pixelHeight)
                || pixelWidth <= 0
                || pixelHeight <= 0
                || pixelWidth * pixelHeight > MAX_PIXELS) {
            return false;
        }
        var nextHints = source.getRenderingHints();
        boolean reusable =
                image != null
                        && image.getWidth() == (int) pixelWidth
                        && image.getHeight() == (int) pixelHeight
                        && frame == overlay
                        && content.equals(area)
                        && wireframe == showWireframe
                        && source.getFont().equals(font)
                        && nextHints.equals(hints)
                        && scaleX == nextScaleX
                        && scaleY == nextScaleY
                        && fractionX == nextFractionX
                        && fractionY == nextFractionY;
        if (!reusable) {
            frame = null;
            prepareImage((int) pixelWidth, (int) pixelHeight);
            var graphics = image.createGraphics();
            try {
                graphics.setComposite(AlphaComposite.Clear);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                graphics.setComposite(AlphaComposite.SrcOver);
                graphics.setFont(source.getFont());
                graphics.setRenderingHints(nextHints);
                graphics.setTransform(
                        new AffineTransform(
                                nextScaleX, 0, 0, nextScaleY, nextFractionX, nextFractionY));
                rasterize.accept(graphics, content);
            } finally {
                graphics.dispose();
            }
            frame = overlay;
            area = new Rectangle(content);
            wireframe = showWireframe;
            font = source.getFont();
            hints = nextHints;
            scaleX = nextScaleX;
            scaleY = nextScaleY;
            fractionX = nextFractionX;
            fractionY = nextFractionY;
        }
        // Cancel the source's scale and fractional translation, so cached device pixels copy 1:1.
        var imageToUser =
                new AffineTransform(
                        1 / nextScaleX,
                        0,
                        0,
                        1 / nextScaleY,
                        -nextFractionX / nextScaleX,
                        -nextFractionY / nextScaleY);
        source.drawImage(image, imageToUser, null);
        return true;
    }

    private void prepareImage(int width, int height) {
        if (image == null || image.getWidth() != width || image.getHeight() != height) {
            image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        }
    }

    void clear() {
        image = null;
        frame = null;
        area = null;
        font = null;
        hints = null;
    }
}
