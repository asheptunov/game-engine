package game;

import java.awt.*;
import java.awt.image.BufferedImage;

/** Patch packing must reproduce full-window UI without overlapping alpha applications. */
public final class GameOverlayCheck {
    public static void main(String[] args) {
        for (double scale : new double[] {1, 1.25, 2})
            for (int[] size : new int[][] {{3840, 2160}, {800, 474}, {800, 600}, {320, 180}})
                for (boolean full : new boolean[] {false, true}) {
                    int w = size[0], h = size[1];
                    java.util.function.Consumer<Graphics2D> paint =
                            g -> {
                                g.setColor(new Color(255, 0, 0, 128));
                                g.fillRect(0, 0, 100, 80);
                                g.setColor(new Color(0, 255, 0, 160));
                                g.fillRect(10, (int) (h / scale) - 40, 80, 30);
                                g.setColor(Color.BLUE);
                                g.fillRect(
                                        (int) (w / scale / 2) - 5,
                                        (int) (h / scale / 2) - 5,
                                        10,
                                        10);
                            };
                    var expected = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    var g = expected.createGraphics();
                    g.scale(scale, scale);
                    paint.accept(g);
                    g.dispose();
                    int[] patches = GameOverlay.capture(w, h, scale, full, paint),
                            actual = new int[w * h];
                    boolean[] touched = new boolean[w * h];
                    for (int n = 0; n < patches[0]; n++) {
                        int r = 4 + n * 5,
                                x = patches[r],
                                y = patches[r + 1],
                                pw = patches[r + 2],
                                ph = patches[r + 3],
                                offset = patches[r + 4];
                        for (int py = 0; py < ph; py++)
                            for (int px = 0; px < pw; px++) {
                                int index = (y + py) * w + x + px;
                                if (touched[index])
                                    throw new AssertionError("Overlapping UI patches");
                                touched[index] = true;
                                actual[index] = patches[offset + py * pw + px];
                            }
                    }
                    if (!java.util.Arrays.equals(actual, expected.getRGB(0, 0, w, h, null, 0, w)))
                        throw new AssertionError("UI pixels " + w + "x" + h + " scale=" + scale);
                }
        System.out.println("PASS UI patches, alpha, small windows and 100/125/200 percent DPI");
    }
}
