package editor;

import editor.overlay.GizmoDrag;
import editor.overlay.GizmoMath;
import editor.overlay.OverlayGeometry;

import engine.*;
import engine.input.MouseButton;
import engine.input.MouseGesture;
import engine.input.MouseInput;
import engine.objects.Rect;

import math.Vec3;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.*;

/** One independent asynchronously rendered editor view. */
public final class RenderViewPanel extends JPanel implements AutoCloseable {
    private record Request(
            SceneSnapshot snapshot,
            Camera camera,
            int width,
            int height,
            NodeId selection,
            EditorController.SelectionMode selectionMode,
            EditorController.VertexSelection vertexSelection,
            EditorController.EdgeSelection edgeSelection,
            EditorController.FaceSelection faceSelection) {}

    private record PickToken(
            SceneSnapshot snapshot,
            SpatialQuery query,
            OverlayGeometry.Prepared overlay,
            NodeId selection,
            EditorController.SelectionMode selectionMode,
            EditorController.VertexSelection vertexSelection,
            EditorController.EdgeSelection edgeSelection,
            EditorController.FaceSelection faceSelection) {}

    private record DisplayFrame(
            BufferedImage image, long generation, long samples, Camera camera, PickToken token) {}

    private record DisplayBundle(
            DisplayFrame frame,
            int width,
            int height,
            OverlayGeometry.GizmoMode mode,
            OverlayGeometry.Frame overlay,
            long serial) {}

    private record PaintedFrame(
            DisplayFrame frame, Rectangle content, OverlayGeometry.Frame overlay, long serial) {}

    private record OverlayRequest(
            DisplayFrame frame,
            int width,
            int height,
            OverlayGeometry.GizmoMode mode,
            long serial) {}

    record ProjectionBlock(CountDownLatch entered, CountDownLatch release) {}

    private record CameraChoice(NodeId id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final EditorController controller;
    private final EditorInputBindings inputBindings;
    private final String title;
    private final ScheduledExecutorService executor;
    private final AtomicReference<Request> pending = new AtomicReference<>();
    private final AtomicReference<OverlayRequest> pendingOverlay = new AtomicReference<>();
    private final AtomicReference<ProjectionBlock> projectionBlockForTest = new AtomicReference<>();
    private final AtomicReference<ProjectionBlock> elementPickBlockForTest =
            new AtomicReference<>();
    private volatile ProjectionBlock activeProjectionBlock;
    private volatile ProjectionBlock activeElementPickBlock;
    private final JComboBox<CameraChoice> cameraChoice = new JComboBox<>();
    private final JButton setSelectedCamera = new JButton("Capture view");
    private final JCheckBox wireframe = new JCheckBox("Wireframe", true);
    private final JToggleButton translate = new JToggleButton("Move", true),
            rotate = new JToggleButton("Rotate");
    private final JLabel renderStatus = new JLabel("Preparing…");
    private final JPanel viewHeader = new JPanel(new BorderLayout(6, 3));
    private final Map<Long, PickToken> tokens = new HashMap<>();
    private final OverlayGeometry overlayGeometry = new OverlayGeometry();
    private final OverlayRasterCache overlayRaster = new OverlayRasterCache();
    private volatile DisplayFrame candidateFrame;
    private volatile DisplayBundle readyBundle;
    private volatile PaintedFrame paintedFrame;
    private OverlayRequest requestedOverlay;
    private volatile OverlayRequest desiredOverlay;
    private long overlaySerial;
    private long publishedOverlaySerial;
    private volatile NodeId selected;
    private volatile EditorController.SelectionMode selectionMode =
            EditorController.SelectionMode.OBJECT;
    private volatile EditorController.VertexSelection vertexSelection;
    private volatile EditorController.EdgeSelection edgeSelection;
    private volatile EditorController.FaceSelection faceSelection;
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
    private Camera editorCamera;
    private Point dragStart;
    private Point pressPoint;
    private boolean dragged;
    private boolean adjustingChoices;
    private OverlayGeometry.GizmoMode gizmoMode = OverlayGeometry.GizmoMode.TRANSLATE;
    private GizmoDrag gizmoDrag;
    private SceneSnapshot gizmoStart;
    private NodeId gizmoNode;
    private OverlayGeometry.Axis gizmoAxis;
    private Rectangle gizmoArea;
    private boolean elementGizmo;
    private MouseInput navigationGesture;

    public RenderViewPanel(
            EditorController controller,
            EditorInputBindings inputBindings,
            String title,
            float yaw,
            float pitch,
            float distance) {
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller);
        this.inputBindings = Objects.requireNonNull(inputBindings);
        this.title = title;
        initialYaw = this.yaw = yaw;
        initialPitch = this.pitch = pitch;
        initialDistance = this.distance = distance;
        editorCamera = cameraAtOrbit(StarterScene.canonicalCamera());
        setBackground(new Color(18, 22, 28));
        setBorder(BorderFactory.createLineBorder(new Color(58, 68, 82)));
        cameraChoice.setToolTipText("Choose an editor view or a camera node");
        cameraChoice.addActionListener(
                _ -> {
                    if (!adjustingChoices) chooseCamera();
                });
        renderStatus.setOpaque(true);
        renderStatus.setBackground(new Color(28, 34, 42));
        renderStatus.setForeground(new Color(190, 202, 216));
        renderStatus.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        var reset = new JButton("Reset");
        reset.addActionListener(_ -> resetView());
        setSelectedCamera.setToolTipText(
                "Set selected camera from this view: copy pose and optics in one undoable edit");
        setSelectedCamera.addActionListener(_ -> setSelectedCameraFromView());
        wireframe.setToolTipText(
                "Toggle selected-node or selected-group descendant x-ray wireframe");
        wireframe.addActionListener(_ -> repaint());
        translate.setToolTipText("Move on local X/Y/Z: left-click and drag a colored handle");
        rotate.setToolTipText("Rotate on local X/Y/Z: left-click and drag a colored ring");
        var modes = new ButtonGroup();
        modes.add(translate);
        modes.add(rotate);
        translate.addActionListener(_ -> setGizmoMode(OverlayGeometry.GizmoMode.TRANSLATE));
        rotate.addActionListener(_ -> setGizmoMode(OverlayGeometry.GizmoMode.ROTATE));
        viewHeader.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        var label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        viewHeader.add(label, BorderLayout.WEST);
        viewHeader.add(cameraChoice, BorderLayout.CENTER);
        viewHeader.add(reset, BorderLayout.EAST);
        var tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        tools.add(wireframe);
        tools.add(translate);
        tools.add(rotate);
        var authoring = new JPanel(new BorderLayout(4, 0));
        authoring.add(setSelectedCamera, BorderLayout.CENTER);
        authoring.add(tools, BorderLayout.EAST);
        viewHeader.add(authoring, BorderLayout.SOUTH);
        add(viewHeader, BorderLayout.NORTH);
        add(renderStatus, BorderLayout.SOUTH);
        setFocusable(true);
        inputBindings.addChangeListener(this::updateNavigationHelp);
        updateNavigationHelp();
        addFocusListener(
                new FocusAdapter() {
                    @Override
                    public void focusLost(FocusEvent event) {
                        inputBindings.clearTransient();
                        cancelGizmo();
                    }
                });
        installMouse();
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            var thread =
                                    new Thread(
                                            r,
                                            "scene-editor-view-"
                                                    + title.toLowerCase(Locale.ROOT)
                                                            .replace(' ', '-'));
                            thread.setDaemon(true);
                            return thread;
                        });
        executor.scheduleWithFixedDelay(this::tick, 0, 12, TimeUnit.MILLISECONDS);
    }

    public void update(EditorController.State state) {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("View updates belong to EDT");
        latestSnapshot = state.snapshot();
        selected = state.selection();
        selectionMode = state.selectionMode();
        vertexSelection = state.vertexSelection();
        edgeSelection = state.edgeSelection();
        faceSelection = state.faceSelection();
        selectedLabel =
                selected == null
                        ? "Nothing selected"
                        : state.snapshot()
                                .findNode(selected)
                                .map(SceneNode::label)
                                .orElse("Selection changed");
        if (vertexSelection != null) selectedLabel += " · vertex " + vertexSelection.vertexId();
        if (edgeSelection != null)
            selectedLabel +=
                    " · edge "
                            + edgeSelection.firstVertexId()
                            + "-"
                            + edgeSelection.secondVertexId();
        if (faceSelection != null) selectedLabel += " · face " + faceSelection.faceId();
        boolean objectMode = selectionMode == EditorController.SelectionMode.OBJECT;
        boolean translatableElement =
                (selectionMode == EditorController.SelectionMode.VERTEX && vertexSelection != null)
                        || (selectionMode == EditorController.SelectionMode.EDGE
                                && edgeSelection != null)
                        || (selectionMode == EditorController.SelectionMode.FACE
                                && faceSelection != null);
        translate.setEnabled(objectMode || translatableElement);
        rotate.setEnabled(objectMode);
        if (!objectMode && translatableElement) translate.setSelected(true);
        updateNavigationHelp();
        setSelectedCamera.setEnabled(
                selected != null
                        && state.snapshot()
                                .findNode(selected)
                                .map(node -> node.camera() != null)
                                .orElse(false));
        rebuildCameraChoices(state.snapshot());
        submitCurrent();
        repaint();
    }

    private void rebuildCameraChoices(SceneSnapshot snapshot) {
        var choices = new ArrayList<CameraChoice>();
        choices.add(new CameraChoice(null, "Editor camera"));
        for (var node : snapshot.nodes()) {
            if (node.camera() != null) {
                choices.add(new CameraChoice(node.id(), node.label()));
            }
        }
        if (cameraChoicesMatch(choices)) {
            return;
        }
        adjustingChoices = true;
        try {
            cameraChoice.removeAllItems();
            for (var choice : choices) {
                cameraChoice.addItem(choice);
            }
            int desired = 0;
            if (sceneCamera != null)
                for (int i = 1; i < cameraChoice.getItemCount(); i++)
                    if (sceneCamera.equals(cameraChoice.getItemAt(i).id())) desired = i;
            if (desired == 0) sceneCamera = null;
            cameraChoice.setSelectedIndex(desired);
        } finally {
            adjustingChoices = false;
        }
    }

    private boolean cameraChoicesMatch(java.util.List<CameraChoice> choices) {
        if (cameraChoice.getItemCount() != choices.size()) {
            return false;
        }
        for (int index = 0; index < choices.size(); index++) {
            if (!choices.get(index).equals(cameraChoice.getItemAt(index))) {
                return false;
            }
        }
        return true;
    }

    private void chooseCamera() {
        var choice = (CameraChoice) cameraChoice.getSelectedItem();
        sceneCamera = choice == null ? null : choice.id();
        submitCurrent();
    }

    private void resetView() {
        sceneCamera = null;
        yaw = initialYaw;
        pitch = initialPitch;
        distance = initialDistance;
        target = new Vec3(0, 0, 3.2f);
        editorCamera = cameraAtOrbit(StarterScene.canonicalCamera());
        adjustingChoices = true;
        cameraChoice.setSelectedIndex(0);
        adjustingChoices = false;
        submitCurrent();
    }

    private void submitCurrent() {
        if (latestSnapshot == null) return;
        Camera camera;
        try {
            if (sceneCamera == null) {
                editorCamera = cameraAtOrbit(editorCamera);
                camera = editorCamera;
            } else camera = latestSnapshot.camera(sceneCamera);
        } catch (RuntimeException error) {
            renderStatus.setText(error.getMessage());
            return;
        }
        int[] dimensions = dimensions(camera);
        pending.set(
                new Request(
                        latestSnapshot,
                        camera,
                        dimensions[0],
                        dimensions[1],
                        selected,
                        selectionMode,
                        vertexSelection,
                        edgeSelection,
                        faceSelection));
    }

    private Camera cameraAtOrbit(Camera optics) {
        double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        var offset =
                new Vec3(
                        (float) (distance * sy * cp),
                        (float) (distance * sp),
                        (float) (-distance * cy * cp));
        var eye = target.add(offset);
        var forward = target.sub(eye).normalized();
        var right = new Vec3(0, 1, 0).cross(forward).normalized();
        var up = forward.cross(right).normalized();
        var oldRight = optics.sensor().edge1().normalized();
        var oldForward = optics.forward();
        var oldUp = oldForward.cross(oldRight).normalized();
        var sensor =
                new Rect(
                        eye.add(
                                changeBasis(
                                        optics.sensor().origin().sub(optics.eye()),
                                        oldRight,
                                        oldUp,
                                        oldForward,
                                        right,
                                        up,
                                        forward)),
                        changeBasis(
                                optics.sensor().edge1(),
                                oldRight,
                                oldUp,
                                oldForward,
                                right,
                                up,
                                forward),
                        changeBasis(
                                optics.sensor().edge2(),
                                oldRight,
                                oldUp,
                                oldForward,
                                right,
                                up,
                                forward));
        return new Camera(
                        eye,
                        sensor,
                        optics.projection(),
                        optics.mode(),
                        optics.focus(),
                        optics.aperture(),
                        optics.height(),
                        optics.rememberedAperture())
                .validated();
    }

    private static Vec3 changeBasis(
            Vec3 value,
            Vec3 oldRight,
            Vec3 oldUp,
            Vec3 oldForward,
            Vec3 right,
            Vec3 up,
            Vec3 forward) {
        return right.scale(value.dot(oldRight))
                .add(up.scale(value.dot(oldUp)))
                .add(forward.scale(value.dot(oldForward)));
    }

    private void setSelectedCameraFromView() {
        if (selected == null || latestSnapshot == null) return;
        try {
            var camera = sceneCamera == null ? editorCamera : latestSnapshot.camera(sceneCamera);
            controller.setCameraFromView(selected, camera);
        } catch (RuntimeException error) {
            renderStatus.setText(message(error));
        }
    }

    private void setGizmoMode(OverlayGeometry.GizmoMode mode) {
        gizmoMode = mode;
        repaint();
    }

    private OverlayGeometry.GizmoMode effectiveGizmoMode() {
        if (selectionMode == EditorController.SelectionMode.OBJECT) return gizmoMode;
        if (selectionMode == EditorController.SelectionMode.VERTEX && vertexSelection != null)
            return OverlayGeometry.GizmoMode.TRANSLATE;
        if (selectionMode == EditorController.SelectionMode.EDGE && edgeSelection != null)
            return OverlayGeometry.GizmoMode.TRANSLATE;
        if (selectionMode == EditorController.SelectionMode.FACE && faceSelection != null)
            return OverlayGeometry.GizmoMode.TRANSLATE;
        return OverlayGeometry.GizmoMode.NONE;
    }

    private static int[] dimensions(Camera camera) {
        float aspect = camera.sensor().edge1().length() / camera.sensor().edge2().length();
        int width, height;
        if (aspect >= 1) {
            width = 360;
            height = Math.max(64, Math.round(width / aspect));
        } else {
            height = 280;
            width = Math.max(64, Math.round(height * aspect));
        }
        return new int[] {Math.min(640, width), Math.min(480, height)};
    }

    private void tick() {
        if (closed) return;
        try {
            var request = pending.getAndSet(null);
            if (request != null) apply(request);
            var overlayRequest = pendingOverlay.getAndSet(null);
            if (overlayRequest != null) projectOverlay(overlayRequest);
            if (session == null) return;
            if (!active) return;
            session.request();
            var after = session.progress(null);
            if (currentPickToken != null) tokens.put(after.requestedGeneration(), currentPickToken);
            try (var image = session.acquireImage()) {
                if (image == null) {
                    pruneTokens(after.requestedGeneration(), after.activeGeneration(), -1);
                    return;
                }
                var token = tokens.get(image.generation());
                if (token == null) {
                    postStatus("Waiting for matching scene publication…");
                    return;
                }
                long samples = image.samples(), generation = image.generation();
                if (image.finishedNanos() == convertedNanos) {
                    var ready = candidateFrame;
                    if (ready != null
                            && ready.generation() == generation
                            && ready.token() != token) {
                        candidateFrame =
                                new DisplayFrame(
                                        ready.image(), generation, samples, image.camera(), token);
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (!closed) repaint();
                                });
                    }
                    pruneTokens(after.requestedGeneration(), after.activeGeneration(), generation);
                    return;
                }
                var buffered = convert(image.presentationPixels());
                session.presented(image);
                convertedNanos = image.finishedNanos();
                candidateFrame =
                        new DisplayFrame(buffered, generation, samples, image.camera(), token);
                pruneTokens(after.requestedGeneration(), after.activeGeneration(), generation);
                SwingUtilities.invokeLater(
                        () -> {
                            if (!closed) {
                                renderStatus.setText(samples + " spp · gen " + generation);
                                repaint();
                            }
                        });
            }
        } catch (Throwable error) {
            postStatus("Render error: " + message(error));
        }
    }

    private void pruneTokens(long requested, long observedActive, long acquired) {
        var keep = new HashSet<Long>();
        keep.add(requested);
        if (observedActive >= 0) keep.add(observedActive);
        var progress = session == null ? null : session.progress(null);
        if (progress != null && progress.activeGeneration() >= 0)
            keep.add(progress.activeGeneration());
        if (acquired >= 0) keep.add(acquired);
        var candidate = candidateFrame;
        if (candidate != null) keep.add(candidate.generation());
        var ready = readyBundle;
        if (ready != null) keep.add(ready.frame().generation());
        var painted = paintedFrame;
        if (painted != null) keep.add(painted.frame().generation());
        tokens.keySet().removeIf(generation -> !keep.contains(generation));
    }

    private void apply(Request request) {
        if (preparedQuery == null || preparedRevision != request.snapshot().revision()) {
            postStatus("Preparing selection data…");
            preparedQuery = SpatialQuery.prepare(request.snapshot());
            preparedRevision = request.snapshot().revision();
        }
        var settings =
                RenderSettings.defaults()
                        .withPathDepth(3)
                        .withSeed(title.hashCode())
                        .withWorkers(
                                Math.min(
                                        4,
                                        Math.max(
                                                1, Runtime.getRuntime().availableProcessors() / 2)))
                        .withSamplesPerBatch(1)
                        .withSampleTarget(8);
        var view = new RenderView(request.camera(), request.width(), request.height());
        if (session == null)
            session =
                    RenderEngine.openSession(request.snapshot().toWorldSnapshot(), view, settings);
        else session.update(request.snapshot().toWorldSnapshot(), view, settings);
        var prior = currentPickToken;
        if (prior == null
                || prior.snapshot() != request.snapshot()
                || !Objects.equals(prior.selection(), request.selection())
                || prior.selectionMode() != request.selectionMode()
                || !Objects.equals(prior.vertexSelection(), request.vertexSelection())
                || !Objects.equals(prior.edgeSelection(), request.edgeSelection())
                || !Objects.equals(prior.faceSelection(), request.faceSelection())) {
            var elementSelection =
                    elementSelection(
                            request.vertexSelection(),
                            request.edgeSelection(),
                            request.faceSelection());
            var prepared =
                    overlayGeometry.prepare(
                            request.snapshot(),
                            request.selection(),
                            elementMode(request.selectionMode()),
                            elementSelection);
            currentPickToken =
                    new PickToken(
                            request.snapshot(),
                            preparedQuery,
                            prepared,
                            request.selection(),
                            request.selectionMode(),
                            request.vertexSelection(),
                            request.edgeSelection(),
                            request.faceSelection());
        }
    }

    private static OverlayGeometry.ElementMode elementMode(EditorController.SelectionMode mode) {
        return switch (mode) {
            case OBJECT -> OverlayGeometry.ElementMode.OBJECT;
            case VERTEX -> OverlayGeometry.ElementMode.VERTEX;
            case EDGE -> OverlayGeometry.ElementMode.EDGE;
            case FACE -> OverlayGeometry.ElementMode.FACE;
        };
    }

    private static OverlayGeometry.ElementSelection elementSelection(
            EditorController.VertexSelection vertex,
            EditorController.EdgeSelection edge,
            EditorController.FaceSelection face) {
        if (vertex != null)
            return new OverlayGeometry.ElementSelection(
                    vertex.nodeId(),
                    vertex.geometryId(),
                    OverlayGeometry.ElementKind.VERTEX,
                    vertex.vertexId(),
                    -1);
        if (edge != null)
            return new OverlayGeometry.ElementSelection(
                    edge.nodeId(),
                    edge.geometryId(),
                    OverlayGeometry.ElementKind.EDGE,
                    edge.firstVertexId(),
                    edge.secondVertexId());
        if (face != null)
            return new OverlayGeometry.ElementSelection(
                    face.nodeId(),
                    face.geometryId(),
                    OverlayGeometry.ElementKind.FACE,
                    face.faceId(),
                    -1);
        return null;
    }

    private void projectOverlay(OverlayRequest request) {
        var block = projectionBlockForTest.getAndSet(null);
        if (block != null)
            try {
                activeProjectionBlock = block;
                block.entered().countDown();
                block.release().await(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                activeProjectionBlock = null;
            }
        var overlay =
                overlayGeometry.project(
                        request.frame().token().overlay(),
                        request.frame().camera(),
                        request.mode(),
                        request.width(),
                        request.height(),
                        72);
        var desired = desiredOverlay;
        if (closed || desired == null || !samePresentationContext(request, desired)) {
            return;
        }
        if (request.serial() < publishedOverlaySerial) {
            return;
        }
        publishedOverlaySerial = request.serial();
        readyBundle =
                new DisplayBundle(
                        request.frame(),
                        request.width(),
                        request.height(),
                        request.mode(),
                        overlay,
                        request.serial());
        SwingUtilities.invokeLater(
                () -> {
                    if (!closed) repaint();
                });
    }

    private static BufferedImage convert(RgbPixels pixels) {
        var image = new BufferedImage(pixels.width(), pixels.height(), BufferedImage.TYPE_INT_RGB);
        var output = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < pixels.height(); y++)
            for (int x = 0; x < pixels.width(); x++) {
                int rgb = 0;
                for (int c = 0; c < 3; c++)
                    rgb =
                            (rgb << 8)
                                    | Byte.toUnsignedInt(
                                            DisplayMapping.encode(pixels.value(c, x, y), 1));
                output[(pixels.height() - 1 - y) * pixels.width() + x] = rgb;
            }
        return image;
    }

    private void installMouse() {
        var mouse =
                new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent event) {
                        requestFocusInWindow();
                        pressPoint = dragStart = event.getPoint();
                        dragged = false;
                        navigationGesture = null;
                        if (multipleButtonsHeld(event)) {
                            cancelGizmo();
                            return;
                        }
                        if (SwingUtilities.isLeftMouseButton(event)) {
                            if (beginGizmo(event.getPoint())) dragged = true;
                            return;
                        }
                        try {
                            var input = inputBindings.mouseInput(MouseGesture.PRESS, event);
                            if (input.button() != MouseButton.LEFT
                                    && input.button() != MouseButton.NONE)
                                navigationGesture =
                                        new MouseInput(
                                                input.button(),
                                                MouseGesture.DRAG,
                                                input.mode(),
                                                input.modifiers(),
                                                input.x(),
                                                input.y(),
                                                0,
                                                input.heldKeys());
                        } catch (IllegalArgumentException ignored) {
                            navigationGesture = null;
                        }
                    }

                    @Override
                    public void mouseReleased(MouseEvent event) {
                        if (gizmoDrag != null && SwingUtilities.isLeftMouseButton(event))
                            commitGizmo();
                        else if (!dragged
                                && pressPoint != null
                                && pressPoint.distance(event.getPoint()) < 3
                                && SwingUtilities.isLeftMouseButton(event)) pick(event.getPoint());
                        pressPoint = dragStart = null;
                        navigationGesture = null;
                    }

                    @Override
                    public void mouseDragged(MouseEvent event) {
                        if (dragStart == null) return;
                        if (gizmoDrag != null) {
                            updateGizmo(event.getPoint());
                            return;
                        }
                        if (pressPoint != null && pressPoint.distance(event.getPoint()) >= 3)
                            dragged = true;
                        if (navigationGesture == null) return;
                        var input =
                                new MouseInput(
                                        navigationGesture.button(),
                                        navigationGesture.gesture(),
                                        navigationGesture.mode(),
                                        navigationGesture.modifiers(),
                                        event.getX(),
                                        event.getY(),
                                        0,
                                        navigationGesture.heldKeys());
                        inputBindings.handleMouse(RenderViewPanel.this, input);
                    }

                    @Override
                    public void mouseWheelMoved(MouseWheelEvent event) {
                        try {
                            inputBindings.handleMouse(
                                    RenderViewPanel.this,
                                    inputBindings.mouseInput(MouseGesture.WHEEL, event));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    private void updateNavigationHelp() {
        String left =
                switch (selectionMode) {
                    case OBJECT -> "Left-click selects objects or drags transform handles";
                    case VERTEX -> "Left-click x-ray selects vertices (8 px) or drags move handles";
                    case EDGE -> "Left-click x-ray selects edges (6 px) or drags move handles";
                    case FACE ->
                            "Left-click depth-selects polygon faces or drags selected-face move"
                                    + " handles";
                };
        setToolTipText(left + " · " + inputBindings.navigationHelp());
    }

    void orbit(MouseInput input) {
        int dx = input.x() - dragStart.x, dy = input.y() - dragStart.y;
        dragStart = new Point(input.x(), input.y());
        ensureEditorCamera();
        yaw -= dx * .008f;
        pitch = Math.clamp(pitch + dy * .008f, -1.45f, 1.45f);
        submitCurrent();
    }

    void pan(MouseInput input) {
        int dx = input.x() - dragStart.x, dy = input.y() - dragStart.y;
        dragStart = new Point(input.x(), input.y());
        ensureEditorCamera();
        var camera = editorCamera;
        float scale = distance * .0025f;
        target =
                target.add(camera.sensor().edge1().normalized().scale(-dx * scale))
                        .add(camera.sensor().edge2().normalized().scale(dy * scale));
        submitCurrent();
    }

    void zoom(MouseInput input) {
        ensureEditorCamera();
        float factor = (float) Math.pow(1.12, input.wheelRotation());
        if (editorCamera.projection() == Camera.Projection.ORTHOGRAPHIC)
            editorCamera =
                    editorCamera.withHeight(
                            Math.clamp(editorCamera.height() * factor, .01f, 10_000f));
        else distance = Math.clamp(distance * factor, .5f, 80);
        submitCurrent();
    }

    private static boolean multipleButtonsHeld(MouseEvent event) {
        int mask = event.getModifiersEx(), count = 0;
        if ((mask & InputEvent.BUTTON1_DOWN_MASK) != 0) count++;
        if ((mask & InputEvent.BUTTON2_DOWN_MASK) != 0) count++;
        if ((mask & InputEvent.BUTTON3_DOWN_MASK) != 0) count++;
        return count > 1;
    }

    private void ensureEditorCamera() {
        if (sceneCamera == null || latestSnapshot == null) return;
        try {
            var camera = latestSnapshot.camera(sceneCamera);
            target = camera.eye().add(camera.forward().scale(camera.focus()));
            distance = camera.focus();
            var direction = target.sub(camera.eye()).normalized();
            pitch = (float) -Math.asin(direction.y());
            yaw = (float) Math.atan2(-direction.x(), direction.z());
            editorCamera = camera;
        } catch (RuntimeException ignored) {
        }
        sceneCamera = null;
        adjustingChoices = true;
        cameraChoice.setSelectedIndex(0);
        adjustingChoices = false;
    }

    private boolean beginGizmo(Point point) {
        var painted = paintedFrame;
        if (painted == null || painted.overlay() == null || !painted.content().contains(point))
            return false;
        var uv = normalized(point, painted.content());
        var hit =
                painted.overlay()
                        .pick(uv[0], uv[1], painted.content().width, painted.content().height, 9)
                        .orElse(null);
        if (hit == null || hit.kind() != OverlayGeometry.HitKind.HANDLE) return false;
        var token = painted.frame().token();
        var prepared = painted.frame().token().overlay();
        var axis = prepared.axis(hit.axis());
        Optional<GizmoDrag> drag =
                hit.mode() == OverlayGeometry.GizmoMode.TRANSLATE
                        ? GizmoDrag.beginTranslation(
                                painted.frame().camera(),
                                prepared.pivot(),
                                axis,
                                uv[0],
                                uv[1],
                                painted.content().width,
                                painted.content().height)
                        : GizmoDrag.beginRotation(
                                painted.frame().camera(), prepared.pivot(), axis, uv[0], uv[1]);
        if (drag.isEmpty()) {
            renderStatus.setText("Handle is aligned with this view; choose another axis or view");
            return false;
        }
        String label =
                (hit.mode() == OverlayGeometry.GizmoMode.TRANSLATE ? "Move " : "Rotate ")
                        + hit.axis();
        elementGizmo =
                token.selectionMode() == EditorController.SelectionMode.VERTEX
                        || token.selectionMode() == EditorController.SelectionMode.EDGE
                        || token.selectionMode() == EditorController.SelectionMode.FACE;
        boolean began =
                elementGizmo
                        ? controller.beginElementGesture(
                                painted.overlay().sceneRevision(),
                                token.selectionMode(),
                                token.selection(),
                                token.vertexSelection(),
                                token.edgeSelection(),
                                token.faceSelection())
                        : controller.beginTransformGesture(
                                hit.nodeId(), label, painted.overlay().sceneRevision());
        if (!began) {
            elementGizmo = false;
            return false;
        }
        gizmoDrag = drag.get();
        gizmoStart = painted.frame().token().snapshot();
        gizmoNode = hit.nodeId();
        gizmoAxis = hit.axis();
        gizmoArea = new Rectangle(painted.content());
        return true;
    }

    private void updateGizmo(Point point) {
        if (gizmoDrag == null || gizmoStart == null || gizmoArea == null) return;
        var uv = normalized(point, gizmoArea);
        var delta = gizmoDrag.update(uv[0], uv[1]);
        if (delta.isEmpty()) return;
        if (elementGizmo) {
            if (delta.get() instanceof GizmoDrag.Translation translation) {
                var localDelta =
                        gizmoStart
                                .worldTransform(gizmoNode)
                                .inverseVector(translation.worldDelta());
                controller.updateElementGesture(localDelta);
            }
            return;
        }
        Transform candidate;
        if (delta.get() instanceof GizmoDrag.Translation translation)
            candidate = GizmoMath.translated(gizmoStart, gizmoNode, translation.worldDelta());
        else {
            var rotation = (GizmoDrag.Rotation) delta.get();
            candidate = GizmoMath.rotated(gizmoStart, gizmoNode, gizmoAxis, rotation.degrees());
        }
        controller.updateTransformGesture(gizmoNode, candidate);
    }

    private void commitGizmo() {
        if (controller.commitTransformGesture()) clearGizmo();
    }

    void cancelGizmo() {
        if (gizmoDrag == null || controller.cancelTransformGesture()) clearGizmo();
    }

    private void clearGizmo() {
        gizmoDrag = null;
        gizmoStart = null;
        gizmoNode = null;
        gizmoAxis = null;
        gizmoArea = null;
        elementGizmo = false;
        pressPoint = dragStart = null;
        navigationGesture = null;
        repaint();
    }

    private static double[] normalized(Point point, Rectangle area) {
        return new double[] {
            Math.clamp((point.x - area.x) / (double) area.width, 0, 1),
            Math.clamp(1 - (point.y - area.y) / (double) area.height, 0, 1)
        };
    }

    private void pick(Point point) {
        var painted = paintedFrame;
        if (painted == null) {
            postStatus("No displayed image yet");
            return;
        }
        var frame = painted.frame();
        var area = new Rectangle(painted.content());
        if (frame == null || !area.contains(point)) {
            postStatus("Click inside the rendered image");
            return;
        }
        var uv = normalized(point, area);
        float u = (float) uv[0], v = (float) uv[1];
        var token = frame.token();
        var mode = token.selectionMode();
        if (mode == EditorController.SelectionMode.OBJECT && painted.overlay() != null) {
            var hit = painted.overlay().pick(u, v, area.width, area.height, 9).orElse(null);
            if (hit != null && hit.kind() == OverlayGeometry.HitKind.MARKER) {
                long markerIntent =
                        controller.beginPick(
                                token.snapshot().revision(),
                                mode,
                                token.selection(),
                                token.vertexSelection(),
                                token.edgeSelection(),
                                token.faceSelection());
                if (markerIntent >= 0) {
                    controller.acceptOverlayPick(
                            hit.nodeId(), painted.overlay().sceneRevision(), markerIntent);
                }
                return;
            }
        }
        long intent =
                controller.beginPick(
                        token.snapshot().revision(),
                        mode,
                        token.selection(),
                        token.vertexSelection(),
                        token.edgeSelection(),
                        token.faceSelection());
        if (intent < 0) return;
        executor.execute(
                () -> {
                    if (closed) return;
                    awaitElementPickBlockForTest();
                    if (closed) return;
                    if (mode == EditorController.SelectionMode.VERTEX) {
                        var vertex =
                                overlayGeometry
                                        .pickVertex(
                                                token.overlay(),
                                                frame.camera(),
                                                u,
                                                v,
                                                area.width,
                                                area.height)
                                        .orElse(null);
                        if (vertex != null) {
                            SwingUtilities.invokeLater(
                                    () -> {
                                        if (!closed)
                                            controller.acceptVertexPick(
                                                    vertex.nodeId(),
                                                    vertex.geometryId(),
                                                    vertex.vertexId(),
                                                    token.snapshot().revision(),
                                                    intent);
                                    });
                            return;
                        }
                    } else if (mode == EditorController.SelectionMode.EDGE) {
                        var edge =
                                overlayGeometry
                                        .pickEdge(
                                                token.overlay(),
                                                frame.camera(),
                                                u,
                                                v,
                                                area.width,
                                                area.height)
                                        .orElse(null);
                        if (edge != null) {
                            SwingUtilities.invokeLater(
                                    () -> {
                                        if (!closed)
                                            controller.acceptEdgePick(
                                                    edge.nodeId(),
                                                    edge.geometryId(),
                                                    edge.firstVertexId(),
                                                    edge.secondVertexId(),
                                                    token.snapshot().revision(),
                                                    intent);
                                    });
                            return;
                        }
                    }
                    var hit =
                            token.query()
                                    .pick(frame.camera(), Math.clamp(u, 0, 1), Math.clamp(v, 0, 1))
                                    .orElse(null);
                    SwingUtilities.invokeLater(
                            () -> {
                                if (!closed)
                                    controller.acceptPick(hit, token.snapshot().revision(), intent);
                            });
                });
    }

    private void awaitElementPickBlockForTest() {
        var block = elementPickBlockForTest.getAndSet(null);
        if (block == null) return;
        try {
            activeElementPickBlock = block;
            block.entered().countDown();
            block.release().await(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            activeElementPickBlock = null;
        }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        var frame = candidateFrame;
        int top = getInsets().top + viewHeader.getHeight(), bottom = renderStatus.getHeight();
        int availableWidth = getWidth() - getInsets().left - getInsets().right;
        int availableHeight = getHeight() - top - bottom;
        if (frame != null && availableWidth > 0 && availableHeight > 0) {
            float scale =
                    Math.min(
                            availableWidth / (float) frame.image().getWidth(),
                            availableHeight / (float) frame.image().getHeight());
            int width = Math.max(1, Math.round(frame.image().getWidth() * scale)),
                    height = Math.max(1, Math.round(frame.image().getHeight() * scale));
            int x = (getWidth() - width) / 2, y = top + (availableHeight - height) / 2;
            var area = new Rectangle(x, y, width, height);
            var effectiveMode = effectiveGizmoMode();
            if (desiredOverlay == null
                    || !requestMatches(desiredOverlay, frame, width, height, effectiveMode))
                desiredOverlay =
                        new OverlayRequest(frame, width, height, effectiveMode, ++overlaySerial);
            var bundle = readyBundle;
            if (bundle != null
                    && projectionMatches(bundle, frame, width, height, effectiveMode)
                    && bundle.frame() != frame) {
                bundle =
                        new DisplayBundle(
                                frame,
                                width,
                                height,
                                effectiveMode,
                                bundle.overlay(),
                                bundle.serial());
                readyBundle = bundle;
            }
            if (bundle != null
                    && bundle.frame() == frame
                    && bundle.width() == width
                    && bundle.height() == height
                    && bundle.mode() == effectiveMode) {
                paintBundle(graphics, bundle, area);
                paintedFrame =
                        new PaintedFrame(bundle.frame(), area, bundle.overlay(), bundle.serial());
            } else {
                if (!closed
                        && (requestedOverlay == null
                                || requestedOverlay.serial() != desiredOverlay.serial())) {
                    requestedOverlay = desiredOverlay;
                    pendingOverlay.set(requestedOverlay);
                }
                var previous = paintedFrame;
                if (bundle != null
                        && samePresentationContext(bundle, desiredOverlay)
                        && (previous == null || bundle.serial() > previous.serial())) {
                    int oldWidth = bundle.width(), oldHeight = bundle.height();
                    var oldArea =
                            new Rectangle(
                                    (getWidth() - oldWidth) / 2,
                                    top + (availableHeight - oldHeight) / 2,
                                    oldWidth,
                                    oldHeight);
                    paintBundle(graphics, bundle, oldArea);
                    paintedFrame =
                            new PaintedFrame(
                                    bundle.frame(), oldArea, bundle.overlay(), bundle.serial());
                } else if (previous != null) {
                    int oldWidth = previous.content().width, oldHeight = previous.content().height;
                    var oldArea =
                            new Rectangle(
                                    (getWidth() - oldWidth) / 2,
                                    top + (availableHeight - oldHeight) / 2,
                                    oldWidth,
                                    oldHeight);
                    graphics.drawImage(
                            previous.frame().image(),
                            oldArea.x,
                            oldArea.y,
                            oldArea.width,
                            oldArea.height,
                            null);
                    paintOverlay((Graphics2D) graphics, oldArea, previous.overlay());
                    paintedFrame =
                            new PaintedFrame(
                                    previous.frame(),
                                    oldArea,
                                    previous.overlay(),
                                    previous.serial());
                }
            }
        }
        var g = (Graphics2D) graphics.create();
        g.setColor(new Color(18, 22, 28, 210));
        g.fillRoundRect(10, top + 8, Math.min(getWidth() - 20, 260), 26, 10, 10);
        g.setColor(new Color(116, 214, 190));
        g.drawString("Selected: " + selectedLabel, 18, top + 26);
        g.dispose();
    }

    private void paintBundle(Graphics graphics, DisplayBundle bundle, Rectangle area) {
        graphics.drawImage(bundle.frame().image(), area.x, area.y, area.width, area.height, null);
        paintOverlay((Graphics2D) graphics, area, bundle.overlay());
    }

    private static boolean projectionMatches(
            DisplayBundle bundle,
            DisplayFrame frame,
            int width,
            int height,
            OverlayGeometry.GizmoMode mode) {
        return bundle.frame().token() == frame.token()
                && bundle.frame().camera().equals(frame.camera())
                && bundle.width() == width
                && bundle.height() == height
                && bundle.mode() == mode;
    }

    private static boolean requestMatches(
            OverlayRequest request,
            DisplayFrame frame,
            int width,
            int height,
            OverlayGeometry.GizmoMode mode) {
        return request.frame().token() == frame.token()
                && request.frame().camera().equals(frame.camera())
                && request.width() == width
                && request.height() == height
                && request.mode() == mode;
    }

    private static boolean samePresentationContext(OverlayRequest first, OverlayRequest second) {
        return sameSelectionContext(first.frame().token(), second.frame().token())
                && first.width() == second.width()
                && first.height() == second.height()
                && first.mode() == second.mode();
    }

    private static boolean samePresentationContext(DisplayBundle bundle, OverlayRequest request) {
        return sameSelectionContext(bundle.frame().token(), request.frame().token())
                && bundle.width() == request.width()
                && bundle.height() == request.height()
                && bundle.mode() == request.mode();
    }

    /**
     * Completed pairs may lag geometry or camera edits. Requiring the newest snapshot here starves
     * painting under sustained input. Each pair still owns its captured image, camera, overlay and
     * picking token; requestMatches/projectionMatches remain strict when reusing an overlay for a
     * different image. Selection and tool/size changes supersede pending projection.
     */
    private static boolean sameSelectionContext(PickToken first, PickToken second) {
        return Objects.equals(first.selection(), second.selection())
                && first.selectionMode() == second.selectionMode()
                && Objects.equals(first.vertexSelection(), second.vertexSelection())
                && Objects.equals(first.edgeSelection(), second.edgeSelection())
                && Objects.equals(first.faceSelection(), second.faceSelection());
    }

    private void paintOverlay(Graphics2D source, Rectangle area, OverlayGeometry.Frame overlay) {
        int cues =
                overlay.elementCueLines().size()
                        + overlay.elementCuePoints().size()
                        + overlay.selectedFace().size()
                        + overlay.selectedEdges().size()
                        + overlay.selectedVertices().size();
        if (wireframe.isSelected()) {
            cues += overlay.wireframe().size();
        }
        // Raster compositing is worthwhile for dense cues, but adds work to a small box overlay.
        if (cues < 256) {
            overlayRaster.clear();
            drawOverlay(source, area, overlay);
            return;
        }
        if (!overlayRaster.paint(
                source,
                getWidth(),
                getHeight(),
                area,
                overlay,
                wireframe.isSelected(),
                (graphics, content) -> drawOverlay(graphics, content, overlay))) {
            drawOverlay(source, area, overlay);
        }
    }

    private void drawOverlay(Graphics2D source, Rectangle area, OverlayGeometry.Frame overlay) {
        var graphics = (Graphics2D) source.create();
        graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (wireframe.isSelected()) {
            graphics.setStroke(
                    new BasicStroke(
                            1.25f,
                            BasicStroke.CAP_ROUND,
                            BasicStroke.JOIN_ROUND,
                            1,
                            new float[] {5, 4},
                            0));
            graphics.setColor(new Color(235, 244, 255, 205));
            for (var line : overlay.wireframe()) drawLine(graphics, area, line);
        }
        for (var line : overlay.elementCueLines()) {
            if (line.style() == OverlayGeometry.Style.VERTEX_BOUNDARY_CUE_XRAY) {
                graphics.setStroke(new BasicStroke(1f));
                graphics.setColor(new Color(116, 214, 190, 120));
            } else if (line.style() == OverlayGeometry.Style.EDGE_CUE_XRAY) {
                graphics.setStroke(
                        new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                graphics.setColor(new Color(116, 214, 190, 205));
            } else {
                graphics.setStroke(
                        new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                graphics.setColor(new Color(125, 180, 255, 185));
            }
            drawLine(graphics, area, line);
        }
        for (var point : overlay.elementCuePoints()) {
            int x = screenX(area, point.u());
            int y = screenY(area, point.v());
            if (point.kind() == OverlayGeometry.ElementKind.VERTEX) {
                graphics.setColor(new Color(116, 214, 190, 225));
                graphics.fillOval(x - 4, y - 4, 8, 8);
                graphics.setColor(new Color(235, 255, 250, 235));
                graphics.drawOval(x - 5, y - 5, 10, 10);
            } else {
                graphics.setColor(new Color(125, 180, 255, 225));
                int[] xs = {x, x + 5, x, x - 5};
                int[] ys = {y - 5, y, y + 5, y};
                graphics.fillPolygon(xs, ys, 4);
                graphics.setColor(new Color(230, 240, 255, 235));
                graphics.drawPolygon(xs, ys, 4);
            }
        }
        graphics.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(new Color(255, 116, 72, 245));
        for (var line : overlay.selectedFace()) drawLine(graphics, area, line);
        for (var line : overlay.selectedEdges()) drawLine(graphics, area, line);
        for (var point : overlay.selectedVertices()) {
            int x = screenX(area, point.u()), y = screenY(area, point.v());
            graphics.fillOval(x - 6, y - 6, 12, 12);
            graphics.setColor(new Color(255, 232, 214));
            graphics.drawOval(x - 7, y - 7, 14, 14);
            graphics.setColor(new Color(255, 116, 72, 245));
        }
        graphics.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (var handle : overlay.handles()) {
            graphics.setColor(axisColor(handle.axis()));
            for (var line : handle.lines()) drawLine(graphics, area, line);
            if (!handle.lines().isEmpty()) {
                var anchor = handle.lines().get(handle.lines().size() - 1);
                graphics.drawString(
                        handle.axis().name(),
                        screenX(area, anchor.u2()) + 4,
                        screenY(area, anchor.v2()) - 4);
            }
        }
        for (var marker : overlay.markers()) {
            int x = screenX(area, marker.u()), y = screenY(area, marker.v());
            if (marker.kind() == OverlayGeometry.MarkerKind.LIGHT) {
                graphics.setColor(new Color(255, 221, 92));
                graphics.drawOval(x - 6, y - 6, 12, 12);
                graphics.drawLine(x - 9, y, x + 9, y);
                graphics.drawLine(x, y - 9, x, y + 9);
                graphics.drawString("L", x + 8, y - 7);
            } else {
                graphics.setColor(new Color(89, 211, 255));
                graphics.drawRect(x - 6, y - 5, 12, 10);
                graphics.drawLine(x + 6, y - 4, x + 10, y - 8);
                graphics.drawLine(x + 6, y + 4, x + 10, y + 8);
                graphics.drawString("C", x + 12, y - 6);
            }
        }
        if (overlay.wireframeTruncated() && wireframe.isSelected()) {
            graphics.setColor(new Color(255, 190, 84));
            graphics.drawString("Wireframe truncated", area.x + 8, area.y + area.height - 8);
        }
        if (overlay.elementCandidatesTruncated()) {
            graphics.setColor(new Color(255, 190, 84));
            graphics.drawString(
                    "Element picking bounded to first 100,000 stable IDs",
                    area.x + 8,
                    area.y + area.height - 24);
        }
        if (overlay.elementCuesTruncated()) {
            graphics.setColor(new Color(255, 190, 84));
            graphics.drawString(
                    "Mode cues truncated at 10,000; click picking remains available",
                    area.x + 8,
                    area.y + area.height - 40);
        }
        graphics.dispose();
    }

    private static void drawLine(Graphics2D graphics, Rectangle area, OverlayGeometry.Line line) {
        graphics.drawLine(
                screenX(area, line.u1()),
                screenY(area, line.v1()),
                screenX(area, line.u2()),
                screenY(area, line.v2()));
    }

    private static int screenX(Rectangle area, double u) {
        return area.x + (int) Math.round(u * area.width);
    }

    private static int screenY(Rectangle area, double v) {
        return area.y + (int) Math.round((1 - v) * area.height);
    }

    private static Color axisColor(OverlayGeometry.Axis axis) {
        return switch (axis) {
            case X -> new Color(255, 92, 92);
            case Y -> new Color(92, 238, 126);
            case Z -> new Color(92, 156, 255);
        };
    }

    private void postStatus(String text) {
        SwingUtilities.invokeLater(
                () -> {
                    if (!closed) renderStatus.setText(text);
                });
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    public boolean hasFrame() {
        return paintedFrame != null;
    }

    public long displayedRevision() {
        return paintedFrame == null ? -1 : paintedFrame.frame().token().snapshot().revision();
    }

    void pickNormalizedForTest(float u, float v) {
        var painted = paintedFrame;
        if (painted == null) throw new IllegalStateException("No painted frame");
        var area = painted.content();
        pick(
                new Point(
                        area.x + Math.round(Math.clamp(u, 0, 1) * Math.max(0, area.width - 1)),
                        area.y
                                + Math.round(
                                        (1 - Math.clamp(v, 0, 1)) * Math.max(0, area.height - 1))));
    }

    void navigateForTest(float yawDelta) {
        yaw += yawDelta;
        sceneCamera = null;
        submitCurrent();
    }

    void resetForTest() {
        resetView();
    }

    void gizmoModeForTest(OverlayGeometry.GizmoMode mode) {
        if (mode == OverlayGeometry.GizmoMode.TRANSLATE) translate.doClick();
        else rotate.doClick();
    }

    void useSceneCameraForTest(NodeId id) {
        sceneCamera = id;
        submitCurrent();
    }

    void mouseDragForTest(int button, int modifiers, int dx, int dy) {
        int x = Math.max(20, getWidth() / 2),
                y = Math.max(20, getHeight() / 2),
                buttonMask =
                        switch (button) {
                            case MouseEvent.BUTTON1 -> InputEvent.BUTTON1_DOWN_MASK;
                            case MouseEvent.BUTTON2 -> InputEvent.BUTTON2_DOWN_MASK;
                            case MouseEvent.BUTTON3 -> InputEvent.BUTTON3_DOWN_MASK;
                            default -> 0;
                        };
        long now = System.currentTimeMillis();
        dispatchEvent(
                new MouseEvent(
                        this,
                        MouseEvent.MOUSE_PRESSED,
                        now,
                        modifiers | buttonMask,
                        x,
                        y,
                        1,
                        false,
                        button));
        dispatchEvent(
                new MouseEvent(
                        this,
                        MouseEvent.MOUSE_DRAGGED,
                        now + 1,
                        modifiers | buttonMask,
                        x + dx,
                        y + dy,
                        0,
                        false,
                        MouseEvent.NOBUTTON));
        dispatchEvent(
                new MouseEvent(
                        this,
                        MouseEvent.MOUSE_RELEASED,
                        now + 2,
                        modifiers,
                        x + dx,
                        y + dy,
                        1,
                        false,
                        button));
    }

    void mouseWheelForTest(double rotation) {
        int x = Math.max(20, getWidth() / 2), y = Math.max(20, getHeight() / 2);
        dispatchEvent(
                new MouseWheelEvent(
                        this,
                        MouseEvent.MOUSE_WHEEL,
                        System.currentTimeMillis(),
                        0,
                        x,
                        y,
                        0,
                        false,
                        MouseWheelEvent.WHEEL_UNIT_SCROLL,
                        1,
                        (int) rotation));
    }

    void setSelectedCameraFromViewForTest() {
        setSelectedCameraFromView();
    }

    Camera currentCameraForTest() {
        return sceneCamera == null ? editorCamera : latestSnapshot.camera(sceneCamera);
    }

    OverlayGeometry.Frame overlayForTest() {
        return paintedFrame == null ? null : paintedFrame.overlay();
    }

    OverlayGeometry.ElementMode paintedElementModeForTest() {
        return paintedFrame == null ? null : paintedFrame.frame().token().overlay().elementMode();
    }

    NodeId paintedSelectionForTest() {
        return paintedFrame == null ? null : paintedFrame.frame().token().selection();
    }

    boolean paintedContextMatchesForTest(EditorController.State state) {
        var painted = paintedFrame;
        if (painted == null) return false;
        var token = painted.frame().token();
        return token.snapshot().revision() == state.snapshot().revision()
                && token.selectionMode() == state.selectionMode()
                && Objects.equals(token.selection(), state.selection())
                && Objects.equals(token.vertexSelection(), state.vertexSelection())
                && Objects.equals(token.edgeSelection(), state.edgeSelection())
                && Objects.equals(token.faceSelection(), state.faceSelection());
    }

    long pickVisibleVertexForTest(NodeId nodeId) {
        var painted = paintedFrame;
        if (painted == null) {
            return -1;
        }
        var projector = CameraProjector.of(painted.frame().camera());
        for (var candidate : painted.frame().token().overlay().elementVertices()) {
            if (!candidate.nodeId().equals(nodeId)) {
                continue;
            }
            var projected = projector.project(candidate.position());
            if (projected.isEmpty() || !projected.get().insideViewport()) {
                continue;
            }
            var click =
                    new Point(
                            screenX(painted.content(), projected.get().u()),
                            screenY(painted.content(), projected.get().v()));
            var resolved = resolvedVertexAtForTest(painted, click);
            if (resolved != null && resolved.nodeId().equals(nodeId)) {
                pick(click);
                return resolved.vertexId();
            }
        }
        return -1;
    }

    long pickVertexVisibleInBothForTest(NodeId nodeId, RenderViewPanel other) {
        var painted = paintedFrame;
        var otherPainted = other.paintedFrame;
        if (painted == null || otherPainted == null) return -1;
        var projector = CameraProjector.of(painted.frame().camera());
        var otherProjector = CameraProjector.of(otherPainted.frame().camera());
        for (var candidate : painted.frame().token().overlay().elementVertices()) {
            if (!candidate.nodeId().equals(nodeId)) continue;
            var point = projector.project(candidate.position());
            var otherPoint = otherProjector.project(candidate.position());
            if (point.isPresent()
                    && point.get().insideViewport()
                    && otherPoint.isPresent()
                    && otherPoint.get().insideViewport()) {
                var click =
                        new Point(
                                screenX(painted.content(), point.get().u()),
                                screenY(painted.content(), point.get().v()));
                var resolved = resolvedVertexAtForTest(painted, click);
                if (resolved != null
                        && resolved.nodeId().equals(nodeId)
                        && vertexVisibleInForTest(otherPainted, resolved)) {
                    pick(click);
                    return resolved.vertexId();
                }
            }
        }
        return -1;
    }

    long[] pickVisibleEdgeForTest(NodeId nodeId) {
        var painted = paintedFrame;
        if (painted == null) {
            return null;
        }
        var projector = CameraProjector.of(painted.frame().camera());
        for (var candidate : painted.frame().token().overlay().elementEdges()) {
            if (!candidate.nodeId().equals(nodeId)) {
                continue;
            }
            var line = projector.clipAndProject(candidate.start(), candidate.end());
            if (line.isEmpty()) {
                continue;
            }
            double u = (line.get().start().u() + line.get().end().u()) * .5;
            double v = (line.get().start().v() + line.get().end().v()) * .5;
            var click = new Point(screenX(painted.content(), u), screenY(painted.content(), v));
            var resolved = resolvedEdgeAtForTest(painted, click);
            if (resolved != null && resolved.nodeId().equals(nodeId)) {
                pick(click);
                return new long[] {resolved.firstVertexId(), resolved.secondVertexId()};
            }
        }
        return null;
    }

    long[] pickEdgeVisibleInBothForTest(NodeId nodeId, RenderViewPanel other) {
        var painted = paintedFrame;
        var otherPainted = other.paintedFrame;
        if (painted == null || otherPainted == null) return null;
        var projector = CameraProjector.of(painted.frame().camera());
        var otherProjector = CameraProjector.of(otherPainted.frame().camera());
        for (var candidate : painted.frame().token().overlay().elementEdges()) {
            if (!candidate.nodeId().equals(nodeId)) continue;
            var line = projector.clipAndProject(candidate.start(), candidate.end());
            var otherLine = otherProjector.clipAndProject(candidate.start(), candidate.end());
            if (line.isEmpty() || otherLine.isEmpty()) continue;
            double u = (line.get().start().u() + line.get().end().u()) * .5;
            double v = (line.get().start().v() + line.get().end().v()) * .5;
            var click = new Point(screenX(painted.content(), u), screenY(painted.content(), v));
            var resolved = resolvedEdgeAtForTest(painted, click);
            if (resolved != null
                    && resolved.nodeId().equals(nodeId)
                    && edgeVisibleInForTest(otherPainted, resolved)) {
                pick(click);
                return new long[] {resolved.firstVertexId(), resolved.secondVertexId()};
            }
        }
        return null;
    }

    private OverlayGeometry.VertexHit resolvedVertexAtForTest(PaintedFrame painted, Point click) {
        var area = painted.content();
        if (!area.contains(click)) {
            return null;
        }
        var normalized = normalized(click, area);
        return overlayGeometry
                .pickVertex(
                        painted.frame().token().overlay(),
                        painted.frame().camera(),
                        (float) normalized[0],
                        (float) normalized[1],
                        area.width,
                        area.height)
                .orElse(null);
    }

    private static boolean vertexVisibleInForTest(
            PaintedFrame painted, OverlayGeometry.VertexHit vertex) {
        var projector = CameraProjector.of(painted.frame().camera());
        for (var candidate : painted.frame().token().overlay().elementVertices()) {
            if (candidate.nodeId().equals(vertex.nodeId())
                    && candidate.geometryId().equals(vertex.geometryId())
                    && candidate.vertexId() == vertex.vertexId()) {
                var projected = projector.project(candidate.position());
                return projected.isPresent() && projected.get().insideViewport();
            }
        }
        return false;
    }

    private static boolean edgeVisibleInForTest(
            PaintedFrame painted, OverlayGeometry.EdgeHit edge) {
        var projector = CameraProjector.of(painted.frame().camera());
        for (var candidate : painted.frame().token().overlay().elementEdges()) {
            if (candidate.nodeId().equals(edge.nodeId())
                    && candidate.geometryId().equals(edge.geometryId())
                    && candidate.firstVertexId() == edge.firstVertexId()
                    && candidate.secondVertexId() == edge.secondVertexId()) {
                return projector.clipAndProject(candidate.start(), candidate.end()).isPresent();
            }
        }
        return false;
    }

    private OverlayGeometry.EdgeHit resolvedEdgeAtForTest(PaintedFrame painted, Point click) {
        var area = painted.content();
        if (!area.contains(click)) {
            return null;
        }
        var normalized = normalized(click, area);
        return overlayGeometry
                .pickEdge(
                        painted.frame().token().overlay(),
                        painted.frame().camera(),
                        (float) normalized[0],
                        (float) normalized[1],
                        area.width,
                        area.height)
                .orElse(null);
    }

    boolean pickVisibleFaceForTest(NodeId nodeId) {
        var painted = paintedFrame;
        if (painted == null) return false;
        var token = painted.frame().token();
        for (int y = 1; y < 40; y++) {
            float v = y / 40f;
            for (int x = 1; x < 40; x++) {
                float u = x / 40f;
                var hit = token.query().pick(painted.frame().camera(), u, v).orElse(null);
                if (hit != null && hit.nodeId().equals(nodeId)) {
                    pick(new Point(screenX(painted.content(), u), screenY(painted.content(), v)));
                    return true;
                }
            }
        }
        return false;
    }

    boolean pickVisibleObjectForTest() {
        var painted = paintedFrame;
        if (painted == null) return false;
        var token = painted.frame().token();
        for (int y = 1; y < 24; y++) {
            float v = y / 24f;
            for (int x = 1; x < 24; x++) {
                float u = x / 24f;
                if (token.query().pick(painted.frame().camera(), u, v).isPresent()) {
                    pick(new Point(screenX(painted.content(), u), screenY(painted.content(), v)));
                    return true;
                }
            }
        }
        return false;
    }

    void wireframeForTest(boolean enabled) {
        wireframe.setSelected(enabled);
        repaint();
    }

    ProjectionBlock blockNextProjectionForTest() {
        var block = new ProjectionBlock(new CountDownLatch(1), new CountDownLatch(1));
        projectionBlockForTest.set(block);
        return block;
    }

    ProjectionBlock blockNextElementPickForTest() {
        var block = new ProjectionBlock(new CountDownLatch(1), new CountDownLatch(1));
        elementPickBlockForTest.set(block);
        return block;
    }

    long paintedGenerationForTest() {
        return paintedFrame == null ? -1 : paintedFrame.frame().generation();
    }

    long paintedSerialForTest() {
        return paintedFrame == null ? -1 : paintedFrame.serial();
    }

    boolean paintedBundleCoherentForTest() {
        var painted = paintedFrame;
        return painted != null
                && painted.overlay() != null
                && painted.overlay().sceneRevision()
                        == painted.frame().token().snapshot().revision();
    }

    String overlayProgressForTest() {
        var candidate = candidateFrame;
        var painted = paintedFrame;
        var desired = desiredOverlay;
        var ready = readyBundle;
        return "candidateGeneration="
                + (candidate == null ? -1 : candidate.generation())
                + ", paintedGeneration="
                + (painted == null ? -1 : painted.frame().generation())
                + ", paintedSerial="
                + (painted == null ? -1 : painted.serial())
                + ", desiredSerial="
                + (desired == null ? -1 : desired.serial())
                + ", readySerial="
                + (ready == null ? -1 : ready.serial());
    }

    boolean pickMarkerForTest(NodeId nodeId) {
        var painted = paintedFrame;
        if (painted == null || painted.overlay() == null) return false;
        var marker =
                painted.overlay().markers().stream()
                        .filter(value -> value.nodeId().equals(nodeId))
                        .findFirst()
                        .orElse(null);
        if (marker == null) return false;
        pick(
                new Point(
                        screenX(painted.content(), marker.u()),
                        screenY(painted.content(), marker.v())));
        return true;
    }

    boolean dragHandleForTest(OverlayGeometry.Axis axis, int pixels, boolean commit) {
        if (!beginHandleDragForTest(axis, pixels)) return false;
        if (commit) commitGizmo();
        else cancelGizmo();
        return true;
    }

    boolean beginHandleDragForTest(OverlayGeometry.Axis axis, int pixels) {
        var painted = paintedFrame;
        if (painted == null || painted.overlay() == null) return false;
        var handle =
                painted.overlay().handles().stream()
                        .filter(
                                value ->
                                        value.axis() == axis
                                                && value.mode() == effectiveGizmoMode())
                        .findFirst()
                        .orElse(null);
        if (handle == null || handle.lines().isEmpty()) return false;
        var line = handle.lines().get(0);
        double x1 = screenX(painted.content(), line.u1()),
                y1 = screenY(painted.content(), line.v1());
        double x2 = screenX(painted.content(), line.u2()),
                y2 = screenY(painted.content(), line.v2());
        double length = Math.hypot(x2 - x1, y2 - y1);
        if (length < 2) return false;
        var start =
                new Point(
                        (int) Math.round(x1 + (x2 - x1) * .65),
                        (int) Math.round(y1 + (y2 - y1) * .65));
        if (!beginGizmo(start)) return false;
        var end =
                new Point(
                        (int) Math.round(start.x + pixels * (x2 - x1) / length),
                        (int) Math.round(start.y + pixels * (y2 - y1) / length));
        updateGizmo(end);
        return true;
    }

    void cancelGizmoForTest() {
        cancelGizmo();
    }

    public void setActive(boolean value) {
        if (closed) return;
        active = value;
        if (!value) {
            inputBindings.clearTransient();
            cancelGizmo();
            executor.execute(
                    () -> {
                        if (session != null) session.suspend();
                    });
        } else submitCurrent();
    }

    @Override
    public void close() {
        if (closed) return;
        inputBindings.clearTransient();
        cancelGizmo();
        closed = true;
        overlayRaster.clear();
        pending.set(null);
        pendingOverlay.set(null);
        desiredOverlay = null;
        releaseTestBlock(projectionBlockForTest.getAndSet(null));
        releaseTestBlock(activeProjectionBlock);
        releaseTestBlock(elementPickBlockForTest.getAndSet(null));
        releaseTestBlock(activeElementPickBlock);
        executor.execute(
                () -> {
                    if (session != null) session.close();
                    session = null;
                    executor.shutdown();
                });
    }

    private static void releaseTestBlock(ProjectionBlock block) {
        if (block != null) block.release().countDown();
    }
}
