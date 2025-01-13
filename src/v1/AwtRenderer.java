package v1;

import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;

public class AwtRenderer implements Renderer {
    private final BufferStrategy bs;
    private final int width;
    private final int height;
    private final BufferedImage image;

    public AwtRenderer(BufferStrategy bs, int width, int height) {
        this.bs = bs;
        this.width = width;
        this.height = height;
        this.image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
    }

    @Override
    public void render(int[] pixels) {
        System.out.println("rendering");
        do {
            do {
                var g = bs.getDrawGraphics();
                image.getRaster().setPixels(0, 0, width, height, pixels);
                g.drawImage(image, 0, 0, null);
                g.dispose();
            } while (bs.contentsRestored());
            bs.show();
        } while (bs.contentsLost());
    }
}
