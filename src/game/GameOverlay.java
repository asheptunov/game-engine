package game;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

/** Small independent overlay patches; full-window effects are uploaded only while active. */
final class GameOverlay {
    private GameOverlay() {}

    static int[] capture(
            int width,
            int height,
            double scale,
            boolean full,
            java.util.function.Consumer<Graphics2D> paint) {
        int top = (int) Math.ceil(214 * scale);
        int bottom = (int) Math.ceil(160 * scale);
        int middle = (int) Math.ceil(100 * scale);
        List<Rectangle> regions;
        if (full
                || (height - middle) / 2 < top
                || (height + middle) / 2 > height - bottom
                || width < 680 * scale) {
            regions = List.of(new Rectangle(0, 0, width, height));
        } else {
            int centerWidth = Math.min(width, (int) Math.ceil(512 * scale));
            regions =
                    List.of(
                            new Rectangle(0, 0, Math.min(width, (int) Math.ceil(680 * scale)), top),
                            new Rectangle(0, height - bottom, width, bottom),
                            new Rectangle(
                                    (width - centerWidth) / 2,
                                    (height - middle) / 2,
                                    centerWidth,
                                    middle));
        }
        int header = 4 + regions.size() * 5;
        int count = header;
        for (var region : regions) {
            count = Math.addExact(count, region.width * region.height);
        }
        int[] result = new int[count];
        result[0] = regions.size();
        result[1] = width;
        result[2] = height;
        int index = 4;
        int offset = header;
        for (var region : regions) {
            var image = new BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_ARGB);
            var draw = image.createGraphics();
            try {
                draw.translate(-region.x, -region.y);
                draw.scale(scale, scale);
                paint.accept(draw);
            } finally {
                draw.dispose();
            }
            result[index++] = region.x;
            result[index++] = region.y;
            result[index++] = region.width;
            result[index++] = region.height;
            result[index++] = offset;
            image.getRGB(0, 0, region.width, region.height, result, offset, region.width);
            offset += region.width * region.height;
        }
        return result;
    }
}
