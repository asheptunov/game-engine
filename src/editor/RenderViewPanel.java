package editor;

import engine.*;
import engine.objects.Rect;
import math.Vec3;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** One independent asynchronously rendered editor view. */
public final class RenderViewPanel extends JPanel implements AutoCloseable {
    private record Request(SceneSnapshot snapshot, Camera camera, int width, int height) {}
    private record PickToken(SceneSnapshot snapshot, SpatialQuery query) {}
    private record DisplayFrame(BufferedImage image, long generation, long samples, Camera camera, PickToken token) {}
    private record PaintedFrame(DisplayFrame frame, Rectangle content) {}
    private record CameraChoice(NodeId id, String label) { @Override public String toString() { return label; } }

    private final EditorController controller;
    private final String title;
    private final ScheduledExecutorService executor;
    private final AtomicReference<Request> pending = new AtomicReference<>();
    private final JComboBox<CameraChoice> cameraChoice = new JComboBox<>();
    private final JLabel renderStatus = new JLabel("Preparing…");
    private final Map<Long, PickToken> tokens = new HashMap<>();
    private volatile DisplayFrame readyFrame;
    private volatile PaintedFrame paintedFrame;
    private volatile NodeId selected;
    private volatile String selectedLabel = "Nothing selected";
    private RenderSession session;
    private long convertedNanos = -1;
    private SceneSnapshot latestSnapshot;
    private SpatialQuery preparedQuery;
    private long preparedRevision = -1;
    private PickToken currentPickToken;
    private volatile boolean active = true;
    private volatile boolean closed;
    private NodeId sceneCamera;
    private final float initialYaw, initialPitch, initialDistance;
    private float yaw, pitch, distance;
    private Vec3 target = new Vec3(0, 0, 3.2f);
    private Point dragStart;
    private Point pressPoint;
    private boolean dragged;
    private boolean adjustingChoices;

    public RenderViewPanel(EditorController controller, String title, float yaw, float pitch, float distance) {
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller); this.title = title;
        initialYaw = this.yaw = yaw; initialPitch = this.pitch = pitch; initialDistance = this.distance = distance;
        setBackground(new Color(18, 22, 28)); setBorder(BorderFactory.createLineBorder(new Color(58, 68, 82)));
        cameraChoice.setToolTipText("Choose an editor view or a camera node");
        cameraChoice.addActionListener(_ -> { if (!adjustingChoices) chooseCamera(); });
        renderStatus.setOpaque(true); renderStatus.setBackground(new Color(28, 34, 42));
        renderStatus.setForeground(new Color(190, 202, 216)); renderStatus.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        var reset = new JButton("Reset"); reset.addActionListener(_ -> resetView());
        var header = new JPanel(new BorderLayout(6, 0)); header.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        var label = new JLabel(title); label.setFont(label.getFont().deriveFont(Font.BOLD)); header.add(label, BorderLayout.WEST);
        header.add(cameraChoice, BorderLayout.CENTER); header.add(reset, BorderLayout.EAST);
        add(header, BorderLayout.NORTH); add(renderStatus, BorderLayout.SOUTH);
        installMouse();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "scene-editor-view-" + title.toLowerCase(Locale.ROOT).replace(' ', '-'));
            thread.setDaemon(true); return thread;
        });
        executor.scheduleWithFixedDelay(this::tick, 0, 12, TimeUnit.MILLISECONDS);
    }

    public void update(EditorController.State state) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("View updates belong to EDT");
        latestSnapshot = state.snapshot(); selected = state.selection();
        selectedLabel = selected == null ? "Nothing selected" : state.snapshot().findNode(selected).map(SceneNode::label).orElse("Selection changed");
        rebuildCameraChoices(state.snapshot()); submitCurrent(); repaint();
    }

    private void rebuildCameraChoices(SceneSnapshot snapshot) {
        adjustingChoices = true;
        try {
            cameraChoice.removeAllItems(); cameraChoice.addItem(new CameraChoice(null, "Editor camera"));
            for (var node : snapshot.nodes()) if (node.camera() != null) cameraChoice.addItem(new CameraChoice(node.id(), node.label()));
            int desired = 0;
            if (sceneCamera != null) for (int i = 1; i < cameraChoice.getItemCount(); i++)
                if (sceneCamera.equals(cameraChoice.getItemAt(i).id())) desired = i;
            if (desired == 0) sceneCamera = null;
            cameraChoice.setSelectedIndex(desired);
        } finally { adjustingChoices = false; }
    }

    private void chooseCamera() {
        var choice = (CameraChoice) cameraChoice.getSelectedItem(); sceneCamera = choice == null ? null : choice.id(); submitCurrent();
    }
    private void resetView() {
        sceneCamera = null; yaw = initialYaw; pitch = initialPitch; distance = initialDistance; target = new Vec3(0, 0, 3.2f);
        adjustingChoices = true; cameraChoice.setSelectedIndex(0); adjustingChoices = false; submitCurrent();
    }
    private void submitCurrent() {
        if (latestSnapshot == null) return;
        Camera camera;
        try { camera = sceneCamera == null ? orbitCamera() : latestSnapshot.camera(sceneCamera); }
        catch (RuntimeException error) { renderStatus.setText(error.getMessage()); return; }
        int[] dimensions = dimensions(camera); pending.set(new Request(latestSnapshot, camera, dimensions[0], dimensions[1]));
    }

    private Camera orbitCamera() {
        double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        var offset = new Vec3((float) (distance * sy * cp), (float) (distance * sp), (float) (-distance * cy * cp));
        var eye = target.add(offset); var forward = target.sub(eye).normalized();
        var right = new Vec3(0, 1, 0).cross(forward).normalized(); var up = forward.cross(right).normalized();
        float height = (float) (2 * Math.tan(Math.toRadians(50) / 2)); float width = height * 1.6f;
        var center = eye.add(forward);
        return new Camera(eye, new Rect(center.sub(right.scale(width / 2)).sub(up.scale(height / 2)), right.scale(width), up.scale(height)));
    }

    private static int[] dimensions(Camera camera) {
        float aspect = camera.sensor().edge1().length() / camera.sensor().edge2().length();
        int width, height;
        if (aspect >= 1) { width = 360; height = Math.max(64, Math.round(width / aspect)); }
        else { height = 280; width = Math.max(64, Math.round(height * aspect)); }
        return new int[]{Math.min(640, width), Math.min(480, height)};
    }

    private void tick() {
        if (closed) return;
        try {
            var request = pending.getAndSet(null);
            if (request != null) apply(request);
            if (session == null) return;
            if (!active) return;
            session.request();
            var after = session.progress(null);
            if (currentPickToken != null) tokens.put(after.requestedGeneration(), currentPickToken);
            try (var image = session.acquireImage()) {
                if (image == null) { pruneTokens(after.requestedGeneration(), after.activeGeneration(), -1); return; }
                var token = tokens.get(image.generation());
                if (token == null) { postStatus("Waiting for matching scene publication…"); return; }
                long samples = image.samples(), generation = image.generation();
                if (image.finishedNanos() == convertedNanos) {
                    var ready = readyFrame;
                    if (ready != null && ready.generation() == generation
                            && ready.token().snapshot().revision() != token.snapshot().revision()) {
                        readyFrame = new DisplayFrame(ready.image(), generation, samples, image.camera(), token);
                        SwingUtilities.invokeLater(() -> { if (!closed) repaint(); });
                    }
                    pruneTokens(after.requestedGeneration(), after.activeGeneration(), generation); return;
                }
                var buffered = convert(image.presentationPixels());
                session.presented(image); convertedNanos = image.finishedNanos();
                readyFrame = new DisplayFrame(buffered, generation, samples, image.camera(), token);
                pruneTokens(after.requestedGeneration(), after.activeGeneration(), generation);
                SwingUtilities.invokeLater(() -> { if (!closed) { renderStatus.setText(samples + " spp · gen " + generation); repaint(); } });
            }
        } catch (Throwable error) { postStatus("Render error: " + message(error)); }
    }

    private void pruneTokens(long requested, long observedActive, long acquired) {
        var keep = new HashSet<Long>(); keep.add(requested);
        if (observedActive >= 0) keep.add(observedActive);
        var progress = session == null ? null : session.progress(null);
        if (progress != null && progress.activeGeneration() >= 0) keep.add(progress.activeGeneration());
        if (acquired >= 0) keep.add(acquired);
        var ready = readyFrame; if (ready != null) keep.add(ready.generation());
        var painted = paintedFrame; if (painted != null) keep.add(painted.frame().generation());
        tokens.keySet().removeIf(generation -> !keep.contains(generation));
    }

    private void apply(Request request) {
        if (preparedQuery == null || preparedRevision != request.snapshot().revision()) {
            postStatus("Preparing selection data…"); preparedQuery = SpatialQuery.prepare(request.snapshot()); preparedRevision = request.snapshot().revision();
        }
        var settings = RenderSettings.defaults().withPathDepth(3).withSeed(title.hashCode())
                .withWorkers(Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2)))
                .withSamplesPerBatch(1).withSampleTarget(8);
        var view = new RenderView(request.camera(), request.width(), request.height());
        if (session == null) session = RenderEngine.openSession(request.snapshot().toWorldSnapshot(), view, settings);
        else session.update(request.snapshot().toWorldSnapshot(), view, settings);
        currentPickToken = new PickToken(request.snapshot(), preparedQuery);
    }

    private static BufferedImage convert(RgbPixels pixels) {
        var image = new BufferedImage(pixels.width(), pixels.height(), BufferedImage.TYPE_INT_RGB);
        var output = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < pixels.height(); y++) for (int x = 0; x < pixels.width(); x++) {
            int rgb = 0;
            for (int c = 0; c < 3; c++) rgb = (rgb << 8) | Byte.toUnsignedInt(DisplayMapping.encode(pixels.value(c, x, y), 1));
            output[(pixels.height() - 1 - y) * pixels.width() + x] = rgb;
        }
        return image;
    }

    private void installMouse() {
        var mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { pressPoint = dragStart = event.getPoint(); dragged = false; }
            @Override public void mouseReleased(MouseEvent event) {
                if (!dragged && pressPoint != null && pressPoint.distance(event.getPoint()) < 3 && SwingUtilities.isLeftMouseButton(event)) pick(event.getPoint());
                pressPoint = dragStart = null;
            }
            @Override public void mouseDragged(MouseEvent event) {
                if (dragStart == null) return; ensureEditorCamera();
                int dx = event.getX() - dragStart.x, dy = event.getY() - dragStart.y; dragStart = event.getPoint();
                if (pressPoint != null && pressPoint.distance(event.getPoint()) >= 3) dragged = true;
                if (SwingUtilities.isRightMouseButton(event) || event.isShiftDown()) {
                    var camera = orbitCamera(); float scale = distance * .0025f;
                    target = target.add(camera.sensor().edge1().normalized().scale(-dx * scale))
                            .add(camera.sensor().edge2().normalized().scale(dy * scale));
                } else { yaw += dx * .008f; pitch = Math.clamp(pitch + dy * .008f, -1.45f, 1.45f); }
                submitCurrent();
            }
            @Override public void mouseWheelMoved(MouseWheelEvent event) {
                ensureEditorCamera(); distance = Math.clamp((float) (distance * Math.pow(1.12, event.getPreciseWheelRotation())), .5f, 80); submitCurrent();
            }
        };
        addMouseListener(mouse); addMouseMotionListener(mouse); addMouseWheelListener(mouse);
        setToolTipText("Click to select · drag to orbit · Shift/right-drag to pan · wheel to zoom");
    }

    private void ensureEditorCamera() {
        if (sceneCamera == null || latestSnapshot == null) return;
        try {
            var camera = latestSnapshot.camera(sceneCamera); target = camera.eye().add(camera.forward().scale(camera.focus())); distance = camera.focus();
            var direction = target.sub(camera.eye()).normalized(); pitch = (float) -Math.asin(direction.y()); yaw = (float) Math.atan2(-direction.x(), direction.z());
        } catch (RuntimeException ignored) { }
        sceneCamera = null; adjustingChoices = true; cameraChoice.setSelectedIndex(0); adjustingChoices = false;
    }

    private void pick(Point point) {
        var painted = paintedFrame;
        if (painted == null) { postStatus("No displayed image yet"); return; }
        var frame = painted.frame(); var area = new Rectangle(painted.content());
        if (frame == null || !area.contains(point)) { postStatus("Click inside the rendered image"); return; }
        float u = (point.x - area.x) / (float) area.width; float v = 1 - (point.y - area.y) / (float) area.height;
        executor.execute(() -> {
            if (closed) return;
            var hit = frame.token().query().pick(frame.camera(), Math.clamp(u, 0, 1), Math.clamp(v, 0, 1)).orElse(null);
            SwingUtilities.invokeLater(() -> { if (!closed) controller.acceptPick(hit, frame.token().snapshot().revision()); });
        });
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics); var frame = readyFrame; int top = getInsets().top + 34, bottom = 24;
        int availableWidth = getWidth() - getInsets().left - getInsets().right;
        int availableHeight = getHeight() - top - bottom;
        if (frame != null && availableWidth > 0 && availableHeight > 0) {
            float scale = Math.min(availableWidth / (float) frame.image().getWidth(), availableHeight / (float) frame.image().getHeight());
            int width = Math.max(1, Math.round(frame.image().getWidth() * scale)), height = Math.max(1, Math.round(frame.image().getHeight() * scale));
            int x = (getWidth() - width) / 2, y = top + (availableHeight - height) / 2; var area = new Rectangle(x, y, width, height);
            graphics.drawImage(frame.image(), x, y, width, height, null);
            paintedFrame = new PaintedFrame(frame, area);
        }
        var g = (Graphics2D) graphics.create();
        g.setColor(new Color(18, 22, 28, 210)); g.fillRoundRect(10, top + 8, Math.min(getWidth() - 20, 260), 26, 10, 10);
        g.setColor(new Color(116, 214, 190)); g.drawString("Selected: " + selectedLabel, 18, top + 26); g.dispose();
    }

    private void postStatus(String text) { SwingUtilities.invokeLater(() -> { if (!closed) renderStatus.setText(text); }); }
    private static String message(Throwable error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    public boolean hasFrame() { return paintedFrame != null; }
    public long displayedRevision() { return paintedFrame == null ? -1 : paintedFrame.frame().token().snapshot().revision(); }
    void pickNormalizedForTest(float u, float v) {
        var painted = paintedFrame;
        if (painted == null) throw new IllegalStateException("No painted frame");
        var area = painted.content();
        pick(new Point(area.x + Math.round(Math.clamp(u, 0, 1) * Math.max(0, area.width - 1)),
                area.y + Math.round((1 - Math.clamp(v, 0, 1)) * Math.max(0, area.height - 1))));
    }
    void navigateForTest(float yawDelta) { yaw += yawDelta; sceneCamera = null; submitCurrent(); }
    public void setActive(boolean value) {
        if (closed) return;
        active = value;
        if (!value) executor.execute(() -> { if (session != null) session.suspend(); });
        else submitCurrent();
    }
    @Override public void close() {
        if (closed) return; closed = true;
        executor.execute(() -> { if (session != null) session.close(); session = null; executor.shutdown(); });
    }
}
