package game;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/** Compare partial clearing with the original full clear, including stale pixels and clips. */
public final class GamePaintCheck {
    public static void main(String[] args) {
        for (int type : new int[] {BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB}) {
            var image = new BufferedImage(160, 90, type);
            for (int y = 0; y < 90; y++)
                for (int x = 0; x < 160; x++) {
                    image.setRGB(x, y, ((x % 2 == 0 ? 128 : 255) << 24) | (x << 16) | (y << 8));
                }
            for (int[] size : new int[][] {{320, 180}, {321, 240}, {179, 321}, {1, 1}}) {
                for (double scale : new double[] {1, 1.25, 2})
                    for (boolean clip : new boolean[] {false, true}) {
                        var reference = paint(image, size, scale, clip, true);
                        var actual = paint(image, size, scale, clip, false);
                        if (!Arrays.equals(reference, actual))
                            throw new AssertionError("Paint mismatch");
                    }
            }
        }
        System.clearProperty("game.fullPaintClear");
        System.out.println(
                "PASS opaque/alpha, letterbox, odd sizes, clipped repaint and DPI equivalence");
    }

    private static int[] paint(
            BufferedImage image, int[] size, double scale, boolean clip, boolean full) {
        var target =
                new BufferedImage(
                        (int) Math.ceil(size[0] * scale),
                        (int) Math.ceil(size[1] * scale),
                        BufferedImage.TYPE_INT_RGB);
        var draw = target.createGraphics();
        try {
            draw.setColor(Color.MAGENTA);
            draw.fillRect(0, 0, target.getWidth(), target.getHeight());
            draw.scale(scale, scale);
            if (clip) draw.setClip(5, 7, Math.max(1, size[0] - 13), Math.max(1, size[1] - 19));
            System.setProperty("game.fullPaintClear", Boolean.toString(full));
            GamePanel.paintFrame(draw, image, size[0], size[1], Color.BLACK);
        } finally {
            draw.dispose();
        }
        return target.getRGB(
                0, 0, target.getWidth(), target.getHeight(), null, 0, target.getWidth());
    }
}
