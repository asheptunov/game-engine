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
    private final GameSettingsPanel settingsPanel;
    private Robot pointer;
    private String pointerError;
    private int settingsRefreshTicks;

    public GamePanel(GameNavigation navigation, GameRenderer renderer) {
        this.navigation = navigation;
        this.renderer = renderer;
        setPreferredSize(new Dimension(960, 600));
        setBackground(Color.BLACK);
        setFocusable(true);
        setLayout(null);
        settingsPanel = new GameSettingsPanel(navigation, renderer);
        add(settingsPanel);
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
                            if (!lightingClick(event)) {
                                capturePointer();
                            }
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
        timer =
                new Timer(
                        16,
                        event -> {
                            settingsPanel.setVisible(!navigation.captured());
                            if (!navigation.captured() && ++settingsRefreshTicks >= 15) {
                                settingsRefreshTicks = 0;
                                settingsPanel.refresh();
                            }
                            repaint();
                        });
        timer.start();
    }

    @Override
    public void doLayout() {
        settingsPanel.setBounds(
                12,
                214,
                Math.min(440, Math.max(100, getWidth() - 24)),
                Math.max(80, getHeight() - 270));
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

    private boolean lightingClick(MouseEvent event) {
        if (!navigation.captured()
                && event.getY() >= 160
                && event.getY() < 194
                && event.getX() >= 236
                && event.getX() < 436) {
            renderer.refinement(!renderer.refinement());
            repaint();
            return true;
        }
        if (!navigation.captured()
                && event.getY() >= 160
                && event.getY() < 194
                && event.getX() >= 24
                && event.getX() < 228) {
            if (renderer.adaptiveSupported()) {
                renderer.adaptive(!renderer.adaptive());
            }
            repaint();
            return true;
        }
        if (navigation.captured() || event.getY() < 120 || event.getY() >= 154) {
            return false;
        }
        int button = (event.getX() - 24) / 104;
        if (event.getX() < 24 || button > 3) {
            return false;
        }
        var current = renderer.lighting();
        if (button == 3) {
            renderer.lighting(current.withSky(!current.skyEnabled()));
        } else {
            renderer.lightingPreset(GameLighting.Preset.values()[button]);
        }
        repaint();
        return true;
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
            draw.setColor(Color.WHITE);
            drawBackendStatus(draw);
            if (frame != null) {
                draw.drawString(
                        "Generation "
                                + frame.generation()
                                + " | age "
                                + ((System.nanoTime() - frame.capturedNanos()) / 1_000_000)
                                + " ms since capture",
                        14,
                        getHeight() - 10);
            }
        } finally {
            draw.dispose();
        }
    }

    private void drawBackendStatus(Graphics2D draw) {
        var lines = new java.util.ArrayList<String>();
        String line = "";
        for (String part : renderer.backendStatus().split(" ")) {
            String candidate = line.isEmpty() ? part : line + " " + part;
            if (!line.isEmpty() && draw.getFontMetrics().stringWidth(candidate) > getWidth() - 28) {
                lines.add(line);
                line = part;
            } else {
                line = candidate;
            }
        }
        lines.add(line);
        int y = getHeight() - 28 - (lines.size() - 1) * 16;
        for (String text : lines) {
            draw.drawString(text, 14, y);
            y += 16;
        }
    }

    private void drawOverlay(Graphics2D draw, GameRenderer.Frame frame) {
        draw.setColor(new Color(15, 20, 28, 220));
        draw.fillRoundRect(
                12, 12, Math.min(640, getWidth() - 24), navigation.captured() ? 100 : 194, 12, 12);
        draw.setColor(Color.WHITE);
        String capture =
                navigation.captured()
                        ? "Mouse captured | Esc releases pointer"
                        : "Pointer free | Click the view to move";
        draw.drawString("Sample game | " + capture, 24, 34);
        draw.drawString(
                navigation.walking()
                        ? "WASD: walk | Space: jump | Mouse: look | R: respawn"
                        : "WASD: fly | Space/Ctrl: up/down | Mouse: look | R: reset",
                24,
                56);
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
                        + (navigation.active() ? "Active" : "Suspended")
                        + (frame == null ? "" : " | " + frame.samples() + " spp"),
                24,
                78);
        String error = renderer.error() == null ? pointerError : renderer.error();
        draw.drawString(
                error == null
                        ? (navigation.walking() ? "Walking: " : "Flight: ")
                                + navigation.speed()
                                + " units/s | Depth "
                                + renderer.settings().pathDepth()
                                + " | Adaptive: "
                                + (renderer.adaptive() ? "on" : "off")
                        : error,
                24,
                100);
        if (!navigation.captured()) {
            drawLighting(draw);
        }
        if (navigation.captured()) {
            draw.drawLine(getWidth() / 2 - 5, getHeight() / 2, getWidth() / 2 + 5, getHeight() / 2);
            draw.drawLine(getWidth() / 2, getHeight() / 2 - 5, getWidth() / 2, getHeight() / 2 + 5);
        }
    }

    private void drawLighting(Graphics2D draw) {
        var current = renderer.lighting();
        String[] labels = {
            "Morning", "Noon", "Evening", current.skyEnabled() ? "Sky: on" : "Sky: off"
        };
        for (int button = 0; button < labels.length; button++) {
            boolean selected =
                    button == 3 ? current.skyEnabled() : current.preset().ordinal() == button;
            draw.setColor(selected ? new Color(65, 105, 145) : new Color(45, 50, 60));
            draw.fillRoundRect(24 + button * 104, 120, 100, 34, 8, 8);
            draw.setColor(Color.WHITE);
            draw.drawString(labels[button], 34 + button * 104, 142);
        }
        draw.setColor(new Color(45, 50, 60));
        draw.fillRoundRect(24, 160, 204, 34, 8, 8);
        draw.setColor(Color.WHITE);
        draw.drawString(
                renderer.adaptiveSupported()
                        ? "Adaptive: " + (renderer.adaptive() ? "on" : "off")
                        : "GPU: fixed resolution",
                34,
                182);
        draw.setColor(renderer.refinement() ? new Color(65, 105, 145) : new Color(45, 50, 60));
        draw.fillRoundRect(236, 160, 200, 34, 8, 8);
        draw.setColor(Color.WHITE);
        draw.drawString("Refinement: " + (renderer.refinement() ? "on" : "off"), 246, 182);
    }

    void releaseForFailure() {
        timer.stop();
        navigation.release();
        updateCursor();
    }

    @Override
    public void close() {
        releaseForFailure();
        renderer.close();
    }
}
