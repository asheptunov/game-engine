package rendering;

import di.annotations.Inject;
import di.annotations.Named;
import logging.LogManager;
import logging.Logger;

import javax.swing.JFrame;
import java.awt.event.KeyListener;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelListener;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

public class AwtViewer implements Renderer {
    private static final Logger LOG = LogManager.instance().getThis();

    private final Raster         raster;
    private final BufferStrategy bs;
    private final BufferedImage  image;

    @Inject
    public AwtViewer(Raster raster, @Named("input_listener") Object listener) {
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
        image = new BufferedImage(raster.width(), raster.height(), BufferedImage.TYPE_INT_RGB);
        if (listener instanceof KeyListener kl) frame.addKeyListener(kl);
        if (listener instanceof java.awt.event.FocusListener fl) frame.addFocusListener(fl);
        if (listener instanceof MouseListener ml) frame.addMouseListener(ml);
        if (listener instanceof MouseMotionListener mml) frame.addMouseMotionListener(mml);
        if (listener instanceof MouseWheelListener mwl) frame.addMouseWheelListener(mwl);
    }

    @Override
    public void render() {
        // Pack directly into the reusable image, avoiding a 3-channel int[] allocation and setPixels.
        var pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        RgbPacking.copy(raster,pixels);
        int i = 0;
        do {
            LOG.debug("Render attempt %d...", i);
            do {
                var g = bs.getDrawGraphics();
                g.drawImage(image, 0, 0, null);
                g.dispose();
            } while (bs.contentsRestored());
            bs.show();
        } while (bs.contentsLost());
    }
}
