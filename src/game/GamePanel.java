package game;

import platform.awt.input.AwtInputAdapter;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

import javax.swing.JPanel;
import javax.swing.Timer;

/** Swing input/display shell. All pointer operations and painting belong to the event thread. */
public final class GamePanel extends JPanel implements AutoCloseable {
    private final GameNavigation navigation;
    private final GameRenderer renderer;
    private final Timer timer;
    private Robot pointer;
    private String pointerError;

    public GamePanel(GameNavigation navigation, GameRenderer renderer) {
        this.navigation = navigation;
        this.renderer = renderer;
        setPreferredSize(new Dimension(960, 600));
        setBackground(Color.BLACK);
        setFocusable(true);
        addKeyListener(
                new KeyAdapter() {
                    @Override
                    public void keyPressed(KeyEvent event) {
                        navigation.key(AwtInputAdapter.key(event));
                        updateCursor();
                    }

                    @Override
                    public void keyReleased(KeyEvent event) {
                        navigation.key(AwtInputAdapter.key(event));
                    }
                });
        var mouse =
                new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent event) {
                        if (event.getButton() == MouseEvent.BUTTON1) {
                            capturePointer();
                        }
                    }

                    @Override
                    public void mouseMoved(MouseEvent event) {
                        look(event);
                    }

                    @Override
                    public void mouseDragged(MouseEvent event) {
                        look(event);
                    }
                };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addComponentListener(
                new ComponentAdapter() {
                    @Override
                    public void componentResized(ComponentEvent event) {
                        navigation.resize(getWidth(), getHeight());
                        centerPointer();
                    }
                });
        timer = new Timer(16, event -> repaint());
        timer.start();
    }

    private void capturePointer() {
        try {
            if (pointer == null) {
                pointer = new Robot(getGraphicsConfiguration().getDevice());
            }
            requestFocusInWindow();
            navigation.capture();
            updateCursor();
            centerPointer();
            pointerError = null;
        } catch (AWTException | SecurityException failure) {
            pointerError = "Mouse capture unavailable: " + failure.getMessage();
            navigation.release();
        }
    }

    private void look(MouseEvent event) {
        if (!navigation.captured() || !isShowing()) {
            return;
        }
        var center = screenCenter();
        int dx = event.getXOnScreen() - center.x;
        int dy = event.getYOnScreen() - center.y;
        // The synthetic recenter event has zero delta and must never turn the camera.
        if (dx != 0 || dy != 0) {
            navigation.look(dx, dy);
            centerPointer();
        }
    }

    private Point screenCenter() {
        var origin = getLocationOnScreen();
        return new Point(origin.x + getWidth() / 2, origin.y + getHeight() / 2);
    }

    private void centerPointer() {
        if (pointer != null && navigation.captured() && isShowing()) {
            var center = screenCenter();
            pointer.mouseMove(center.x, center.y);
        }
    }

    private void updateCursor() {
        if (navigation.captured()) {
            var blank = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            setCursor(
                    Toolkit.getDefaultToolkit().createCustomCursor(blank, new Point(), "captured"));
        } else {
            setCursor(Cursor.getDefaultCursor());
        }
    }

    public void renderingActive(boolean active) {
        navigation.active(active);
        updateCursor();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        var draw = (Graphics2D) graphics.create();
        try {
            var frame = renderer.frame();
            if (frame != null) {
                var image = frame.image();
                // Letterbox a lagging pre-resize frame rather than stretching its geometry.
                double scale =
                        Math.min(
                                (double) getWidth() / image.getWidth(),
                                (double) getHeight() / image.getHeight());
                int width = (int) Math.round(image.getWidth() * scale);
                int height = (int) Math.round(image.getHeight() * scale);
                draw.drawImage(
                        image,
                        (getWidth() - width) / 2,
                        (getHeight() - height) / 2,
                        width,
                        height,
                        null);
            }
            drawOverlay(draw, frame);
        } finally {
            draw.dispose();
        }
    }

    private void drawOverlay(Graphics2D draw, GameRenderer.Frame frame) {
        draw.setColor(new Color(15, 20, 28, 220));
        draw.fillRoundRect(12, 12, Math.min(640, getWidth() - 24), 100, 12, 12);
        draw.setColor(Color.WHITE);
        String capture =
                navigation.captured()
                        ? "Mouse captured | Esc releases pointer"
                        : "Pointer free | Click the view to fly";
        draw.drawString("Sample game G1 | " + capture, 24, 34);
        draw.drawString(
                "WASD: heading movement | Space/Ctrl: up/down | Mouse: look | R: reset", 24, 56);
        var view = navigation.view();
        String shown =
                frame == null
                        ? "waiting"
                        : frame.image().getWidth() + " x " + frame.image().getHeight();
        draw.drawString(
                "Requested "
                        + view.width()
                        + " x "
                        + view.height()
                        + " | Displayed "
                        + shown
                        + " | "
                        + (navigation.active() ? "Active" : "Suspended"),
                24,
                78);
        String error = renderer.error() == null ? pointerError : renderer.error();
        draw.drawString(
                error == null
                        ? "Free flight: 3 units/s | No collision | Colored cube gallery"
                        : error,
                24,
                100);
        if (navigation.captured()) {
            draw.drawLine(getWidth() / 2 - 5, getHeight() / 2, getWidth() / 2 + 5, getHeight() / 2);
            draw.drawLine(getWidth() / 2, getHeight() / 2 - 5, getWidth() / 2, getHeight() / 2 + 5);
        }
    }

    @Override
    public void close() {
        timer.stop();
        navigation.release();
        updateCursor();
        renderer.close();
    }
}
