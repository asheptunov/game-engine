package rendering;

import logging.LogManager;
import logging.Logger;

import javax.swing.JFrame;
import java.awt.event.KeyListener;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelListener;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;

public class AwtViewer implements Display {
    private static final Logger LOG = LogManager.instance().getThis();

    private final BufferStrategy bs;
    private final BufferedImage  image;

    // TODO maybe DI this?
    public AwtViewer(int width, int height, Object listener) {
        var frame = new JFrame();
        frame.setTitle("game");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(width, height);
        frame.setResizable(false);
        frame.setUndecorated(true);
        frame.setVisible(true);
        frame.createBufferStrategy(2);
        bs = frame.getBufferStrategy();
        image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        if (listener instanceof KeyListener kl) frame.addKeyListener(kl);
        if (listener instanceof MouseListener ml) frame.addMouseListener(ml);
        if (listener instanceof MouseMotionListener mml) frame.addMouseMotionListener(mml);
        if (listener instanceof MouseWheelListener mwl) frame.addMouseWheelListener(mwl);
    }

    @Override
    public void display(Raster raster) {
        int i = 0;
        do {
            LOG.debug("Render attempt %d...", i);
            do {
                var g = bs.getDrawGraphics();
                image.getRaster().setPixels(0, 0, raster.width(), raster.height(), raster.rgb());
                g.drawImage(image, 0, 0, null);
                g.dispose();
            } while (bs.contentsRestored());
            bs.show();
        } while (bs.contentsLost());
    }
}
