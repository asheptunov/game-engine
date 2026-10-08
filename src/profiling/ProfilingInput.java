package profiling;

import java.awt.event.*;

/** Global toggle outside scene bindings, including while a scene console is open. */
public final class ProfilingInput
        implements KeyListener,
                MouseListener,
                MouseMotionListener,
                MouseWheelListener,
                FocusListener {
    private final FrameProfiler profiler;
    private final Object delegate;
    private boolean held;
    private boolean detailsHeld;

    public ProfilingInput(FrameProfiler profiler, Object delegate) {
        this.profiler = profiler;
        this.delegate = delegate;
    }

    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_F3) {
            if (!held) profiler.toggle();
            held = true;
        } else if (e.getKeyCode() == KeyEvent.VK_F4) {
            if (!detailsHeld) profiler.toggleTraceDetails();
            detailsHeld = true;
        } else ((KeyListener) delegate).keyPressed(e);
    }

    @Override
    public void keyReleased(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_F3) held = false;
        else if (e.getKeyCode() == KeyEvent.VK_F4) detailsHeld = false;
        else ((KeyListener) delegate).keyReleased(e);
    }

    @Override
    public void keyTyped(KeyEvent e) {
        ((KeyListener) delegate).keyTyped(e);
    }

    @Override
    public void focusGained(FocusEvent e) {
        if (delegate instanceof FocusListener listener) listener.focusGained(e);
    }

    @Override
    public void focusLost(FocusEvent e) {
        held = detailsHeld = false;
        if (delegate instanceof FocusListener listener) listener.focusLost(e);
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        ((MouseListener) delegate).mouseClicked(e);
    }

    @Override
    public void mousePressed(MouseEvent e) {
        ((MouseListener) delegate).mousePressed(e);
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        ((MouseListener) delegate).mouseReleased(e);
    }

    @Override
    public void mouseEntered(MouseEvent e) {
        ((MouseListener) delegate).mouseEntered(e);
    }

    @Override
    public void mouseExited(MouseEvent e) {
        ((MouseListener) delegate).mouseExited(e);
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        ((MouseMotionListener) delegate).mouseDragged(e);
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        ((MouseMotionListener) delegate).mouseMoved(e);
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        ((MouseWheelListener) delegate).mouseWheelMoved(e);
    }
}
