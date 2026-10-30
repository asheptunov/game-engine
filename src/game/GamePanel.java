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
    private final profiling.FreshPaintRate freshPaints = new profiling.FreshPaintRate();
    private final GameSettingsPanel settingsPanel;
    private final GameDisplay nativeDisplay;
    private final DisplayStallMonitor stallMonitor;
    private Robot pointer;
    private final java.util.Set<Integer> captureKeys = new java.util.HashSet<>();
    private String pointerError;
    private int settingsRefreshTicks;

    public GamePanel(GameNavigation navigation, GameRenderer renderer) {
        this.navigation = navigation;
        this.renderer = renderer;
        setPreferredSize(new Dimension(960, 600));
        setBackground(Color.BLACK);
        setFocusable(true);
        setLayout(null);
        bindCapture(KeyEvent.VK_F8, false);
        bindCapture(KeyEvent.VK_F9, true);
        settingsPanel = new GameSettingsPanel(navigation, renderer);
        add(settingsPanel);
        addKeyListener(
                new KeyAdapter() {
                    @Override
                    public void keyPressed(KeyEvent event) {
                        if (combatKey(event)) {
                            return;
                        }
                        navigation.key(AwtInputAdapter.key(event));
                        updateCursor();
                    }

                    @Override
                    public void keyReleased(KeyEvent event) {
                        captureKeys.remove(event.getKeyCode());
                        navigation.key(AwtInputAdapter.key(event));
                    }
                });
        var mouse =
                new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent event) {
                        pressMouse(event);
                    }

                    @Override
                    public void mouseReleased(MouseEvent event) {
                        if (event.getButton() == MouseEvent.BUTTON3) {
                            navigation.scoped(false);
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
        // repaint() is thread-safe; Swing coalesces dirty regions when the EDT is busy.
        // Fresh images no longer wait for the UI timer. Keep that timer for controls/HUD updates.
        if (!Boolean.getBoolean("game.timerPresentation")) {
            renderer.onFrameAvailable(this::repaint);
        }
        nativeDisplay = createNativeDisplay();
        if (nativeDisplay != null) {
            var surface = nativeDisplay.component();
            add(surface);
            setComponentZOrder(settingsPanel, 0);
            surface.addMouseListener(mouse);
            surface.addMouseMotionListener(mouse);
            renderer.devicePresentation();
        }
        stallMonitor = new DisplayStallMonitor(this::latestPublication, renderer::displayStalls);
        timer.start();
    }

    private long latestPublication() {
        var latest = renderer.frame();
        return latest == null ? 0 : latest.publicationNanos();
    }

    private GameDisplay createNativeDisplay() {
        if (java.awt.GraphicsEnvironment.isHeadless()
                || renderer.capabilities() != engine.RenderCapabilities.GPU
                || !Boolean.getBoolean("game.nativePresentation")
                || Boolean.getBoolean("game.swingPresentation")) {
            return null;
        }
        try {
            return (GameDisplay)
                    Class.forName("gpu.GpuGameSurface")
                            .getConstructor(Runnable.class, java.util.function.Consumer.class)
                            .newInstance(
                                    (Runnable) this::repaint,
                                    (java.util.function.Consumer<Throwable>)
                                            renderer::displayFailure);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot initialize native GPU presentation", error);
        }
    }

    private void bindCapture(int key, boolean history) {
        String press = "capture-" + key;
        String release = press + "-release";
        getInputMap(WHEN_IN_FOCUSED_WINDOW)
                .put(javax.swing.KeyStroke.getKeyStroke(key, 0, false), press);
        getInputMap(WHEN_IN_FOCUSED_WINDOW)
                .put(javax.swing.KeyStroke.getKeyStroke(key, 0, true), release);
        getActionMap()
                .put(
                        press,
                        new javax.swing.AbstractAction() {
                            @Override
                            public void actionPerformed(java.awt.event.ActionEvent event) {
                                if (captureKeys.add(key)) {
                                    renderer.captureBundle(history);
                                }
                            }
                        });
        getActionMap()
                .put(
                        release,
                        new javax.swing.AbstractAction() {
                            @Override
                            public void actionPerformed(java.awt.event.ActionEvent event) {
                                captureKeys.remove(key);
                            }
                        });
    }

    private void pressMouse(MouseEvent event) {
        if (navigation.captured() && renderer.shooter() != null) {
            if (event.getButton() == MouseEvent.BUTTON1) {
                synchronized (navigation) {
                    if (renderer.shooter()
                            .fire(navigation.view().camera(), navigation.aimAmount())) {
                        navigation.recoil();
                    }
                }
            } else if (event.getButton() == MouseEvent.BUTTON3) {
                navigation.scoped(true);
            }
            return;
        }
        if (event.getButton() == MouseEvent.BUTTON1 && !lightingClick(event)) {
            capturePointer();
        }
    }

    private String movementHelp() {
        if (renderer.shooter() != null) {
            return "WASD: move | C: crouch | Z: prone | LMB: fire | RMB: scope | R:"
                    + " reload | Enter: restart";
        }
        return navigation.walking()
                ? "WASD: walk | Space: jump | Mouse: look | R: respawn"
                : "WASD: fly | Space/Ctrl: up/down | Mouse: look | R: reset";
    }

    private boolean combatKey(KeyEvent event) {
        var combat = renderer.shooter();
        if (combat == null || !navigation.captured()) {
            return false;
        }
        if (event.getKeyCode() == KeyEvent.VK_R) {
            combat.reload();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.VK_ENTER) {
            combat.reset();
            navigation.reset();
            return true;
        }
        if (combat.status().health() == 0 && event.getKeyCode() != KeyEvent.VK_ESCAPE) {
            return true;
        }
        return false;
    }

    @Override
    public void doLayout() {
        if (nativeDisplay != null) {
            nativeDisplay.component().setBounds(0, 0, getWidth(), getHeight());
        }
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
        captureKeys.clear();
        updateCursor();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (nativeDisplay != null) {
            super.paintComponent(graphics);
            if (nativeDisplay.ready()) {
                presentNativeFrame();
            }
            return; // Do not download a resident frame while its native peer is hidden or starting.
        }
        var draw = (Graphics2D) graphics.create();
        var frame = renderer.acquireFrame();
        try {
            if (frame != null) {
                var image = frame.image();
                paintFrame(draw, image, getWidth(), getHeight(), getBackground());
            } else {
                super.paintComponent(graphics);
            }
            drawFrameOverlay(draw, frame);
            markPresented(frame);
        } finally {
            draw.dispose();
            if (frame != null) {
                frame.release();
            }
        }
    }

    /** Opaque frames replace their background; only uncovered letterbox bars need clearing. */
    static void paintFrame(
            Graphics2D draw,
            BufferedImage image,
            int panelWidth,
            int panelHeight,
            Color background) {
        double scale =
                Math.min(
                        (double) panelWidth / image.getWidth(),
                        (double) panelHeight / image.getHeight());
        int width = (int) Math.round(image.getWidth() * scale);
        int height = (int) Math.round(image.getHeight() * scale);
        int x = (panelWidth - width) / 2;
        int y = (panelHeight - height) / 2;
        draw.setColor(background);
        if (image.getColorModel().hasAlpha() || Boolean.getBoolean("game.fullPaintClear")) {
            draw.fillRect(0, 0, panelWidth, panelHeight);
        } else {
            draw.fillRect(0, 0, panelWidth, y);
            draw.fillRect(0, y + height, panelWidth, panelHeight - y - height);
            draw.fillRect(0, y, x, height);
            draw.fillRect(x + width, y, panelWidth - x - width, height);
        }
        draw.drawImage(image, x, y, width, height, null);
    }

    private void drawFrameOverlay(Graphics2D draw, GameRenderer.Frame frame) {
        if (renderer.shooter() != null && navigation.captured()) {
            if (frame != null) {
                drawTracers(draw, frame);
            }
            ShooterOverlay.draw(
                    draw, getWidth(), getHeight(), navigation, renderer.shooter().status());
        }
        drawOverlay(draw, frame);
        draw.setColor(Color.WHITE);
        drawBackendStatus(draw);
        draw.drawString(renderer.captureStatus(), 14, getHeight() - 60);
        if (frame != null) {
            draw.drawString(
                    "Generation "
                            + frame.generation()
                            + " | age "
                            + ((System.nanoTime() - frame.capturedNanos()) / 1_000_000)
                            + " ms since capture | "
                            + String.format(
                                    java.util.Locale.ROOT,
                                    "%.1f fresh paints/s (2s)",
                                    freshPaintRate()),
                    14,
                    getHeight() - 10);
        }
    }

    private void presentNativeFrame() {
        var frame = renderer.acquireFrame();
        if (frame == null) {
            return;
        }
        try {
            if (frame.devicePixels() == null) {
                return;
            }
            var transform = getGraphicsConfiguration().getDefaultTransform();
            double scale = transform.getScaleX();
            int width = (int) Math.ceil(getWidth() * scale);
            int height = (int) Math.ceil(getHeight() * scale);
            boolean full =
                    renderer.shooter() != null
                            && navigation.captured()
                            && (renderer.shooter().status().hurtTime() > 0
                                    || !renderer.shooter().tracers().isEmpty());
            int[] overlay =
                    GameOverlay.capture(
                            width, height, scale, full, draw -> drawFrameOverlay(draw, frame));
            nativeDisplay.submit(frame, width, height, overlay, this::markPresented);
        } finally {
            frame.release();
        }
    }

    private void markPresented(GameRenderer.Frame frame) {
        if (frame != null) {
            stallMonitor.painted(
                    frame.publicationNanos(), navigation.captured() && navigation.active());
        }
        renderer.displayed(frame);
        if (frame != null) {
            synchronized (freshPaints) {
                freshPaints.painted(frame.publicationNanos(), System.nanoTime());
            }
        }
    }

    private double freshPaintRate() {
        synchronized (freshPaints) {
            return freshPaints.perSecond(System.nanoTime());
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

    private void drawTracers(Graphics2D draw, GameRenderer.Frame frame) {
        double scale =
                Math.min(
                        (double) getWidth() / frame.width(), (double) getHeight() / frame.height());
        int width = (int) Math.round(frame.width() * scale);
        int height = (int) Math.round(frame.height() * scale);
        var layer = (Graphics2D) draw.create();
        try {
            layer.translate((getWidth() - width) / 2, (getHeight() - height) / 2);
            layer.clipRect(0, 0, width, height);
            TracerOverlay.draw(layer, width, height, frame.camera(), renderer.shooter());
        } finally {
            layer.dispose();
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
        draw.drawString(movementHelp(), 24, 56);
        var view = navigation.view();
        String shown = frame == null ? "waiting" : frame.width() + " x " + frame.height();
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
        if (navigation.captured() && navigation.aimAmount() < .5f) {
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
        stallMonitor.close();
        renderer.onFrameAvailable(null);
        if (nativeDisplay != null) {
            nativeDisplay.close();
        }
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
