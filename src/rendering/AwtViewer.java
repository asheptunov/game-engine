package rendering;

import logging.LogManager;
import logging.Logger;

import javax.swing.*;
import java.awt.event.KeyListener;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelListener;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;

public class AwtViewer implements Renderer {
    private static final Logger LOG = LogManager.instance().getThis();

    private final Raster         raster;
    private final BufferStrategy bs;
    private final BufferedImage  image;

    // TODO maybe DI this?
    public AwtViewer(Raster raster, Object listener) {
        this.raster = raster;
        var frame = new JFrame();
        frame.setTitle("game");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(raster.width(), raster.height());
        frame.setResizable(false);
        frame.setUndecorated(true);
        frame.setVisible(true);
        frame.createBufferStrategy(2);
        bs = frame.getBufferStrategy();
        image = new BufferedImage(raster.width(), raster.height(), BufferedImage.TYPE_4BYTE_ABGR_PRE);
        if (listener instanceof KeyListener kl) frame.addKeyListener(kl);
        if (listener instanceof MouseListener ml) frame.addMouseListener(ml);
        if (listener instanceof MouseMotionListener mml) frame.addMouseMotionListener(mml);
        if (listener instanceof MouseWheelListener mwl) frame.addMouseWheelListener(mwl);
    }

    @Override
    public void render() {
        int i = 0;
        do {
            LOG.debug("Render attempt %d...", i);
            do {
                var g = bs.getDrawGraphics();
                image.getRaster().setPixels(0, 0, raster.width(), raster.height(), raster.pixels());
                g.drawImage(image, 0, 0, null);
                g.dispose();
            } while (bs.contentsRestored());
            bs.show();
        } while (bs.contentsLost());
    }
}
