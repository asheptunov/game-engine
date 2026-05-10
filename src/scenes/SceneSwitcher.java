package scenes;

import logging.LogManager;
import logging.Logger;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class SceneSwitcher
        implements KeyListener, MouseListener, MouseMotionListener, MouseWheelListener {
    private static final Logger LOG = LogManager.instance().getThis();
    private static final int    SWITCH_KEY = KeyEvent.VK_F12;

    private final List<Scene>            scenes;
    private final AtomicReference<Scene> activeScene;
    private final KeyListener            kl;
    private final MouseListener          ml;
    private final MouseMotionListener    mml;
    private final MouseWheelListener     mwl;

    public SceneSwitcher(List<Scene> scenes, AtomicReference<Scene> activeScene, Object delegate) {
        if (scenes == null || scenes.isEmpty()) {
            throw new IllegalArgumentException("scenes must be non-empty");
        }
        this.scenes = List.copyOf(scenes);
        this.activeScene = activeScene;
        this.kl = (KeyListener) delegate;
        this.ml = (MouseListener) delegate;
        this.mml = (MouseMotionListener) delegate;
        this.mwl = (MouseWheelListener) delegate;
    }

    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == SWITCH_KEY) {
            cycle();
            return;
        }
        kl.keyPressed(e);
    }

    private void cycle() {
        var current = activeScene.get();
        var idx = scenes.indexOf(current);
        var next = scenes.get(((idx < 0 ? -1 : idx) + 1) % scenes.size());
        activeScene.set(next);
        LOG.info("Switched scene: %s -> %s", current, next);
    }

    @Override public void keyTyped(KeyEvent e) { kl.keyTyped(e); }
    @Override public void keyReleased(KeyEvent e) { kl.keyReleased(e); }

    @Override public void mouseClicked(MouseEvent e) { ml.mouseClicked(e); }
    @Override public void mousePressed(MouseEvent e) { ml.mousePressed(e); }
    @Override public void mouseReleased(MouseEvent e) { ml.mouseReleased(e); }
    @Override public void mouseEntered(MouseEvent e) { ml.mouseEntered(e); }
    @Override public void mouseExited(MouseEvent e) { ml.mouseExited(e); }

    @Override public void mouseDragged(MouseEvent e) { mml.mouseDragged(e); }
    @Override public void mouseMoved(MouseEvent e) { mml.mouseMoved(e); }

    @Override public void mouseWheelMoved(MouseWheelEvent e) { mwl.mouseWheelMoved(e); }
}
