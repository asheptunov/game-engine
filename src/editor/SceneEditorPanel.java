package editor;

import editor.overlay.OverlayGeometry;

import engine.*;

import math.Vec3;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.filechooser.FileFilter;
import javax.swing.text.DefaultCaret;
import javax.swing.tree.*;

/** Complete native Swing authoring surface; safe to construct and paint headlessly. */
public final class SceneEditorPanel extends JPanel
        implements EditorController.Listener, AutoCloseable {
    private static final int MAX_COMMAND_LOG_CHARS = 32_000;

    private static final class SelectionOnlyCaret extends DefaultCaret {
        SelectionOnlyCaret() {
            setBlinkRate(0);
        }

        @Override
        public void paint(Graphics graphics) {}
    }

    private record NodeChoice(NodeId id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final class TreeNode extends DefaultMutableTreeNode {
        final NodeId id;

        TreeNode(NodeId id, String label) {
            super(label);
            this.id = id;
        }
    }

    private final EditorController controller;
    private final EditorCommandProcessor commands;
    private final JTree hierarchy = new JTree();
    private final JLabel status = new JLabel("Ready");
    private final JLabel dirty = new JLabel();
    private final JLabel dirtyFooter = new JLabel();
    private final JTextArea commandLog = new JTextArea(4, 80);
    private final JTextField commandEntry = new JTextField();
    private final Inspector inspector;
    private final RenderViewPanel viewA, viewB;
    private final EditorInputBindings inputBindings;
    private final JButton undo = new JButton("Undo"), redo = new JButton("Redo");
    private final JToggleButton objectSelect = new JToggleButton("Object", true),
            vertexSelect = new JToggleButton("Vertex"),
            edgeSelect = new JToggleButton("Edge"),
            faceSelect = new JToggleButton("Face");
    private boolean updatingTree;
    private String bindingWarning;
    private boolean closed;

    public SceneEditorPanel(EditorController controller) {
        this(
                controller,
                Path.of("assets", "bindings", "scene-editor.properties"),
                Path.of("assets", "bindings", "scene-editor-mouse.properties"),
                Path.of("config", "scene-editor-bindings.properties"));
    }

    SceneEditorPanel(EditorController controller, Path keyFile, Path mouseFile) {
        this(
                controller,
                keyFile,
                mouseFile,
                keyFile.toAbsolutePath()
                        .resolveSibling("scene-editor-bindings.override.properties"));
    }

    SceneEditorPanel(EditorController controller, Path keyFile, Path mouseFile, Path userFile) {
        super(new BorderLayout(6, 6));
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("SceneEditorPanel belongs to EDT");
        this.controller = Objects.requireNonNull(controller);
        commands = new EditorCommandProcessor(controller);
        inputBindings = new EditorInputBindings(this, controller, keyFile, mouseFile, userFile);
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        setBackground(new Color(32, 37, 44));
        add(toolbar(), BorderLayout.NORTH);
        hierarchy.setRootVisible(true);
        hierarchy.setShowsRootHandles(true);
        hierarchy.addTreeSelectionListener(this::treeSelected);
        var hierarchyPane = new JPanel(new BorderLayout());
        hierarchyPane.setBorder(BorderFactory.createTitledBorder("Scene hierarchy"));
        hierarchyPane.add(new JScrollPane(hierarchy));
        hierarchyPane.setPreferredSize(new Dimension(230, 600));
        viewA = new RenderViewPanel(controller, inputBindings, "View A", .58f, .28f, 12);
        viewB = new RenderViewPanel(controller, inputBindings, "View B", -.72f, .42f, 11);
        var views = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, viewA, viewB);
        views.setResizeWeight(.5);
        views.setDividerSize(5);
        inspector = new Inspector(controller);
        inspector.setPreferredSize(new Dimension(310, 600));
        var left = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, hierarchyPane, views);
        left.setResizeWeight(.16);
        left.setDividerLocation(230);
        var body = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, inspector);
        body.setResizeWeight(.79);
        body.setDividerLocation(1050);
        body.setMinimumSize(new Dimension(500, 300));
        var workspace = new JSplitPane(JSplitPane.VERTICAL_SPLIT, body, commandPanel());
        workspace.setResizeWeight(.72);
        workspace.setDividerLocation(.72);
        workspace.setDividerSize(7);
        add(workspace, BorderLayout.CENTER);
        add(statusStrip(), BorderLayout.SOUTH);
        controller.addListener(this);
    }

    private JComponent toolbar() {
        var bar = new JToolBar();
        bar.setFloatable(false);
        addButton(bar, "New", _ -> guardUnsaved(controller::newScene));
        addButton(bar, "Open", _ -> guardUnsaved(this::chooseOpen));
        addButton(bar, "Save", _ -> chooseSave(null));
        bar.addSeparator();
        for (var primitive :
                List.of(
                        EditorController.Primitive.BOX,
                        EditorController.Primitive.SPHERE,
                        EditorController.Primitive.PLANE,
                        EditorController.Primitive.GROUP,
                        EditorController.Primitive.POINT_LIGHT,
                        EditorController.Primitive.CAMERA))
            addButton(bar, label(primitive), _ -> controller.create(primitive));
        bar.addSeparator();
        addButton(bar, "Duplicate", _ -> controller.duplicateSelection());
        addButton(bar, "Delete", _ -> controller.deleteSelection());
        undo.addActionListener(_ -> controller.undo());
        redo.addActionListener(_ -> controller.redo());
        bar.add(undo);
        bar.add(redo);
        bar.addSeparator();
        bar.add(new JLabel("Select:"));
        var selectionModes = new ButtonGroup();
        for (var button : List.of(objectSelect, vertexSelect, edgeSelect, faceSelect))
            selectionModes.add(button);
        objectSelect.setToolTipText("Object mode: select nodes and use transform handles");
        vertexSelect.setToolTipText(
                "Vertex mode: x-ray pick within 8 logical pixels; drag local-axis move handles");
        edgeSelect.setToolTipText(
                "Edge mode: x-ray pick within 6 logical pixels; drag local-axis move handles");
        faceSelect.setToolTipText(
                "Face mode: depth-visible surface pick; polygon boundaries and centers stay"
                        + " visible; selected faces have move handles");
        objectSelect.addActionListener(
                _ -> controller.setSelectionMode(EditorController.SelectionMode.OBJECT));
        vertexSelect.addActionListener(
                _ -> controller.setSelectionMode(EditorController.SelectionMode.VERTEX));
        edgeSelect.addActionListener(
                _ -> controller.setSelectionMode(EditorController.SelectionMode.EDGE));
        faceSelect.addActionListener(
                _ -> controller.setSelectionMode(EditorController.SelectionMode.FACE));
        bar.add(objectSelect);
        bar.add(vertexSelect);
        bar.add(edgeSelect);
        bar.add(faceSelect);
        bar.addSeparator();
        addButton(bar, "Bindings…", _ -> showBindings());
        bar.add(Box.createHorizontalGlue());
        bar.add(dirty);
        return bar;
    }

    private JComponent commandPanel() {
        var panel = new JPanel(new BorderLayout(5, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Commands (type help)"));
        commandLog.setEditable(false);
        commandLog.setLineWrap(true);
        commandLog.setWrapStyleWord(true);
        commandLog.setCaret(new SelectionOnlyCaret());
        commandEntry.addActionListener(
                _ -> {
                    var text = commandEntry.getText();
                    if (text.isBlank()) return;
                    appendCommandOutput("> " + text + "\n" + commands.execute(text) + "\n");
                    commandEntry.setText("");
                });
        var output = new JPanel(new BorderLayout());
        output.setBorder(BorderFactory.createTitledBorder("Output"));
        output.add(new JScrollPane(commandLog));
        var input = new JPanel(new BorderLayout());
        input.setBorder(BorderFactory.createTitledBorder("Input"));
        input.add(commandEntry);
        panel.add(output, BorderLayout.CENTER);
        panel.add(input, BorderLayout.SOUTH);
        panel.setMinimumSize(new Dimension(300, 110));
        panel.setPreferredSize(new Dimension(900, 210));
        return panel;
    }

    private JComponent statusStrip() {
        var footer = new JPanel(new BorderLayout(8, 0));
        footer.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(96, 104, 114)),
                        BorderFactory.createEmptyBorder(3, 6, 3, 6)));
        footer.add(status);
        footer.add(dirtyFooter, BorderLayout.EAST);
        return footer;
    }

    private void appendCommandOutput(String text) {
        commandLog.append(text);
        int extra = commandLog.getDocument().getLength() - MAX_COMMAND_LOG_CHARS;
        if (extra > 0)
            try {
                String prefix =
                        commandLog.getText(
                                0, Math.min(commandLog.getDocument().getLength(), extra + 1024));
                int newline = prefix.indexOf('\n', extra);
                commandLog.getDocument().remove(0, newline < 0 ? extra : newline + 1);
            } catch (javax.swing.text.BadLocationException impossible) {
                throw new AssertionError(impossible);
            }
        commandLog.setCaretPosition(commandLog.getDocument().getLength());
    }

    private static void addButton(
            JToolBar bar, String label, java.awt.event.ActionListener action) {
        var button = new JButton(label);
        button.addActionListener(action);
        bar.add(button);
    }

    private static String label(EditorController.Primitive p) {
        return switch (p) {
            case POINT_LIGHT -> "Light";
            default -> p.name().substring(0, 1) + p.name().substring(1).toLowerCase(Locale.ROOT);
        };
    }

    @Override
    public void changed(EditorController.State state) {
        rebuildTree(state);
        inspector.update(state);
        viewA.update(state);
        viewB.update(state);
        objectSelect.setSelected(state.selectionMode() == EditorController.SelectionMode.OBJECT);
        vertexSelect.setSelected(state.selectionMode() == EditorController.SelectionMode.VERTEX);
        edgeSelect.setSelected(state.selectionMode() == EditorController.SelectionMode.EDGE);
        faceSelect.setSelected(state.selectionMode() == EditorController.SelectionMode.FACE);
        undo.setEnabled(state.canUndo() && !state.busy());
        redo.setEnabled(state.canRedo() && !state.busy());
        status.setText(bindingWarning == null ? state.status() : bindingWarning);
        var marker = (state.dirty() ? "● Unsaved" : "Saved") + (state.busy() ? " · working…" : "");
        dirty.setText(marker);
        dirtyFooter.setText(marker);
    }

    private void rebuildTree(EditorController.State state) {
        var expanded = expandedIds();
        var root = new TreeNode(null, "Scene");
        var byId = new LinkedHashMap<NodeId, TreeNode>();
        for (var node : state.snapshot().nodes())
            byId.put(node.id(), new TreeNode(node.id(), icon(node) + " " + node.label()));
        for (var node : state.snapshot().nodes()) {
            var treeNode = byId.get(node.id());
            var parent = node.parentId() == null ? root : byId.get(node.parentId());
            (parent == null ? root : parent).add(treeNode);
        }
        updatingTree = true;
        try {
            hierarchy.setModel(new DefaultTreeModel(root));
            expandMatching(new TreePath(root), expanded);
            if (state.selection() != null) selectTreeNode(root, state.selection());
        } finally {
            updatingTree = false;
        }
    }

    private Set<NodeId> expandedIds() {
        var result = new HashSet<NodeId>();
        for (int row = 0; row < hierarchy.getRowCount(); row++)
            if (hierarchy.isExpanded(row)) {
                var value = hierarchy.getPathForRow(row).getLastPathComponent();
                if (value instanceof TreeNode n && n.id != null) result.add(n.id);
            }
        return result;
    }

    private void expandMatching(TreePath path, Set<NodeId> expanded) {
        var node = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (!(node instanceof TreeNode n) || n.id == null || expanded.contains(n.id))
            hierarchy.expandPath(path);
        for (int i = 0; i < node.getChildCount(); i++)
            expandMatching(path.pathByAddingChild(node.getChildAt(i)), expanded);
    }

    private void selectTreeNode(DefaultMutableTreeNode node, NodeId id) {
        if (node instanceof TreeNode n && Objects.equals(n.id, id)) {
            hierarchy.setSelectionPath(new TreePath(node.getPath()));
            hierarchy.scrollPathToVisible(new TreePath(node.getPath()));
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++)
            selectTreeNode((DefaultMutableTreeNode) node.getChildAt(i), id);
    }

    private void treeSelected(TreeSelectionEvent event) {
        if (!updatingTree && event.getPath().getLastPathComponent() instanceof TreeNode node)
            controller.select(node.id);
    }

    private static String icon(SceneNode node) {
        if (node.camera() != null) return "◉";
        if (node.light() != null) return "☀";
        if (node.geometry() != null) return "◆";
        return "▾";
    }

    private void chooseOpen() {
        inputBindings.clearTransient();
        var chooser = chooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            controller.load(chooser.getSelectedFile().toPath());
    }

    private void chooseSave(Runnable continuation) {
        Path path = controller.state().file();
        if (path == null) {
            inputBindings.clearTransient();
            var chooser = chooser();
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            path = scenePath(chooser.getSelectedFile().toPath());
        }
        saveThen(path, continuation);
    }

    private void saveThen(Path path, Runnable continuation) {
        controller
                .save(path)
                .thenAccept(
                        ok ->
                                SwingUtilities.invokeLater(
                                        () -> {
                                            if (!ok || continuation == null) return;
                                            if (controller.dirty()) guardUnsaved(continuation);
                                            else continuation.run();
                                        }));
    }

    private JFileChooser chooser() {
        var chooser = new JFileChooser();
        chooser.setDialogTitle("Ray tracing scene (.scene.xml)");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(
                new FileFilter() {
                    @Override
                    public boolean accept(File file) {
                        return file.isDirectory()
                                || file.getName().toLowerCase(Locale.ROOT).endsWith(".scene.xml");
                    }

                    @Override
                    public String getDescription() {
                        return "Ray tracing scenes (*.scene.xml)";
                    }
                });
        return chooser;
    }

    private static Path scenePath(Path path) {
        return path.toString().toLowerCase(Locale.ROOT).endsWith(".scene.xml")
                ? path
                : Path.of(path + ".scene.xml");
    }

    private void guardUnsaved(Runnable action) {
        if (!controller.dirty()) {
            action.run();
            return;
        }
        Object[] options = {"Save", "Discard", "Cancel"};
        int result =
                JOptionPane.showOptionDialog(
                        this,
                        "Save changes before continuing?",
                        "Unsaved scene",
                        JOptionPane.DEFAULT_OPTION,
                        JOptionPane.WARNING_MESSAGE,
                        null,
                        options,
                        options[0]);
        if (result == 0) {
            Path path = controller.state().file();
            if (path == null) chooseSave(action);
            else saveThen(path, action);
        } else if (result == 1) action.run();
    }

    public void requestClose() {
        guardUnsaved(this::closeWindow);
    }

    private void closeWindow() {
        var window = SwingUtilities.getWindowAncestor(this);
        close();
        if (window != null) window.dispose();
    }

    public void setRenderingActive(boolean active) {
        if (!active) inputBindings.clearTransient();
        viewA.setActive(active);
        viewB.setActive(active);
    }

    void bindingStatus(String warning) {
        bindingWarning = warning;
        status.setText(warning == null ? controller.state().status() : warning);
    }

    private void showBindings() {
        inputBindings.dialogActive(true);
        var dialog = new java.util.concurrent.atomic.AtomicReference<JDialog>();
        var panel =
                new BindingPreferencesPanel(
                        inputBindings,
                        () -> {
                            var value = dialog.get();
                            if (value != null) value.dispose();
                        });
        var pane =
                new JOptionPane(
                        panel,
                        JOptionPane.PLAIN_MESSAGE,
                        JOptionPane.DEFAULT_OPTION,
                        null,
                        new Object[] {});
        var value = pane.createDialog(this, "Editor bindings");
        dialog.set(value);
        value.setModal(true);
        try {
            value.setVisible(true);
        } finally {
            inputBindings.dialogActive(false);
        }
    }

    public boolean viewsReady() {
        return viewA.hasFrame() && viewB.hasFrame();
    }

    public long[] displayedRevisions() {
        return new long[] {viewA.displayedRevision(), viewB.displayedRevision()};
    }

    void pickInViewForTest(int view, float u, float v) {
        (view == 0 ? viewA : viewB).pickNormalizedForTest(u, v);
    }

    void navigateViewForTest(int view, float yawDelta) {
        (view == 0 ? viewA : viewB).navigateForTest(yawDelta);
    }

    void resetViewForTest(int view) {
        (view == 0 ? viewA : viewB).resetForTest();
    }

    void gizmoModeForTest(int view, OverlayGeometry.GizmoMode mode) {
        (view == 0 ? viewA : viewB).gizmoModeForTest(mode);
    }

    void useSceneCameraForTest(int view, NodeId id) {
        (view == 0 ? viewA : viewB).useSceneCameraForTest(id);
    }

    void mouseDragForTest(int view, int button, int modifiers, int dx, int dy) {
        (view == 0 ? viewA : viewB).mouseDragForTest(button, modifiers, dx, dy);
    }

    void mouseWheelForTest(int view, double rotation) {
        (view == 0 ? viewA : viewB).mouseWheelForTest(rotation);
    }

    void setSelectedCameraFromViewForTest(int view) {
        (view == 0 ? viewA : viewB).setSelectedCameraFromViewForTest();
    }

    Camera viewCameraForTest(int view) {
        return (view == 0 ? viewA : viewB).currentCameraForTest();
    }

    OverlayGeometry.Frame overlayForTest(int view) {
        return (view == 0 ? viewA : viewB).overlayForTest();
    }

    OverlayGeometry.ElementMode paintedElementModeForTest(int view) {
        return (view == 0 ? viewA : viewB).paintedElementModeForTest();
    }

    NodeId paintedSelectionForTest(int view) {
        return (view == 0 ? viewA : viewB).paintedSelectionForTest();
    }

    boolean paintedContextMatchesForTest(int view, EditorController.State state) {
        return (view == 0 ? viewA : viewB).paintedContextMatchesForTest(state);
    }

    long pickVisibleVertexForTest(int view, NodeId nodeId) {
        return (view == 0 ? viewA : viewB).pickVisibleVertexForTest(nodeId);
    }

    long pickVertexVisibleInBothForTest(int view, NodeId nodeId) {
        return view == 0
                ? viewA.pickVertexVisibleInBothForTest(nodeId, viewB)
                : viewB.pickVertexVisibleInBothForTest(nodeId, viewA);
    }

    long[] pickVisibleEdgeForTest(int view, NodeId nodeId) {
        return (view == 0 ? viewA : viewB).pickVisibleEdgeForTest(nodeId);
    }

    long[] pickEdgeVisibleInBothForTest(int view, NodeId nodeId) {
        return view == 0
                ? viewA.pickEdgeVisibleInBothForTest(nodeId, viewB)
                : viewB.pickEdgeVisibleInBothForTest(nodeId, viewA);
    }

    boolean pickVisibleFaceForTest(int view, NodeId nodeId) {
        return (view == 0 ? viewA : viewB).pickVisibleFaceForTest(nodeId);
    }

    boolean pickVisibleObjectForTest(int view) {
        return (view == 0 ? viewA : viewB).pickVisibleObjectForTest();
    }

    RenderViewPanel.ProjectionBlock blockNextProjectionForTest(int view) {
        return (view == 0 ? viewA : viewB).blockNextProjectionForTest();
    }

    RenderViewPanel.ProjectionBlock blockNextElementPickForTest(int view) {
        return (view == 0 ? viewA : viewB).blockNextElementPickForTest();
    }

    long paintedGenerationForTest(int view) {
        return (view == 0 ? viewA : viewB).paintedGenerationForTest();
    }

    long paintedSerialForTest(int view) {
        return (view == 0 ? viewA : viewB).paintedSerialForTest();
    }

    boolean paintedBundleCoherentForTest(int view) {
        return (view == 0 ? viewA : viewB).paintedBundleCoherentForTest();
    }

    String overlayProgressForTest(int view) {
        return (view == 0 ? viewA : viewB).overlayProgressForTest();
    }

    Component viewComponentForTest(int view) {
        return view == 0 ? viewA : viewB;
    }

    boolean pickMarkerForTest(int view, NodeId node) {
        return (view == 0 ? viewA : viewB).pickMarkerForTest(node);
    }

    boolean dragHandleForTest(int view, OverlayGeometry.Axis axis, int pixels, boolean commit) {
        return (view == 0 ? viewA : viewB).dragHandleForTest(axis, pixels, commit);
    }

    boolean beginHandleDragForTest(int view, OverlayGeometry.Axis axis, int pixels) {
        return (view == 0 ? viewA : viewB).beginHandleDragForTest(axis, pixels);
    }

    void cancelGizmoForTest(int view) {
        (view == 0 ? viewA : viewB).cancelGizmoForTest();
    }

    void executeCommandForTest(String text) {
        commandEntry.setText(text);
        commandEntry.postActionEvent();
    }

    int commandOutputLengthForTest() {
        return commandLog.getDocument().getLength();
    }

    JTextArea commandOutputForTest() {
        return commandLog;
    }

    JTextField commandInputForTest() {
        return commandEntry;
    }

    boolean hasInspectorTabForTest(String title) {
        return inspector.hasTab(title);
    }

    void selectInspectorTabForTest(String title) {
        inspector.selectTab(title);
    }

    JFileChooser chooserForTest() {
        return chooser();
    }

    static Path scenePathForTest(Path path) {
        return scenePath(path);
    }

    boolean dispatchKeyForTest(KeyEvent event) {
        return inputBindings.handleKeyForTest(event);
    }

    BindingPreferencesPanel bindingPreferencesForTest() {
        return new BindingPreferencesPanel(inputBindings, () -> {});
    }

    String bindingStatusForTest() {
        return status.getText();
    }

    String navigationHelpForTest() {
        return inputBindings.navigationHelp();
    }

    void bindingDialogActiveForTest(boolean active) {
        inputBindings.dialogActive(active);
    }

    void selectionModeForTest(EditorController.SelectionMode mode) {
        switch (mode) {
            case OBJECT -> objectSelect.doClick();
            case VERTEX -> vertexSelect.doClick();
            case EDGE -> edgeSelect.doClick();
            case FACE -> faceSelect.doClick();
        }
    }

    void wireframeForTest(int view, boolean enabled) {
        (view == 0 ? viewA : viewB).wireframeForTest(enabled);
    }

    String meshFaceTextForTest() {
        return inspector.meshFaceText();
    }

    String meshGeometryTextForTest() {
        return inspector.meshGeometryText();
    }

    boolean meshApproximateEnabledForTest() {
        return inspector.meshApproximateEnabled();
    }

    boolean meshExtrudeEnabledForTest() {
        return inspector.meshExtrudeEnabled();
    }

    boolean applySphereForTest(Vec3 center, float radius) {
        return inspector.applyAnalyticSphere(center, radius);
    }

    boolean approximateSphereForTest(int detail) {
        return inspector.approximateAnalyticSphere(detail);
    }

    boolean meshExtrudeForTest(float distance) {
        return inspector.extrude(distance);
    }

    boolean translateSelectedElementForTest(Vec3 delta) {
        return inspector.translateSelectedElement(delta);
    }

    void cancelActiveGesture() {
        if (viewA != null) viewA.cancelGizmo();
        if (viewB != null) viewB.cancelGizmo();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        inputBindings.close();
        controller.removeListener(this);
        viewA.close();
        viewB.close();
        controller.close();
    }

    private static final class Inspector extends JTabbedPane {
        private static final class ViewportWidthPanel extends JPanel implements Scrollable {
            private ViewportWidthPanel() {
                setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
                setAlignmentX(Component.LEFT_ALIGNMENT);
            }

            @Override
            public void doLayout() {
                super.doLayout();
                for (Component child : getComponents()) {
                    if (!child.isVisible()) {
                        continue;
                    }
                    Rectangle bounds = child.getBounds();
                    child.setBounds(0, bounds.y, getWidth(), bounds.height);
                }
            }

            @Override
            public Dimension getPreferredScrollableViewportSize() {
                return getPreferredSize();
            }

            @Override
            public int getScrollableUnitIncrement(
                    Rectangle visibleRectangle, int orientation, int direction) {
                return 12;
            }

            @Override
            public int getScrollableBlockIncrement(
                    Rectangle visibleRectangle, int orientation, int direction) {
                return Math.max(12, visibleRectangle.height - 24);
            }

            @Override
            public boolean getScrollableTracksViewportWidth() {
                return true;
            }

            @Override
            public boolean getScrollableTracksViewportHeight() {
                return false;
            }
        }

        private final EditorController controller;
        private EditorController.State state;
        private final JTextField name = new JTextField();
        private final JTextField[] transform = fields(9, "0");
        private final JComboBox<NodeChoice> parent = new JComboBox<>();
        private final JComboBox<Material.Kind> kind = new JComboBox<>(Material.Kind.values());
        private final JTextField[] color = fields(3, ".8");
        private final JTextField roughness = new JTextField("0"), ior = new JTextField("1.5");
        private final JLabel meshNote = new JLabel("No geometry");
        private final JLabel meshFace = new JLabel("No face selected");
        private final JTextField extrusionDistance = new JTextField("0.5");
        private final JTextField[] elementDelta = fields(3, "0");
        private final JTextField[] sphereCenter = fields(3, "0");
        private final JTextField sphereRadius = new JTextField("1");
        private final JSpinner sphereDetail = new JSpinner(new SpinnerNumberModel(12, 4, 64, 1));
        private final JButton applySphere = new JButton("Apply");
        private final JButton approximateSphere = new JButton("Approximate as mesh");
        private final JButton translateElement = new JButton("Translate selection");
        private final JButton extrudeFace = new JButton("Extrude face");
        private final JTextField[] light = fields(4, "1");
        private final JComboBox<Camera.Projection> projection =
                new JComboBox<>(Camera.Projection.values());
        private final JTextField framing = new JTextField("50"),
                focus = new JTextField("5"),
                aperture = new JTextField("0");
        private final JPanel componentPanel = stack();
        private final JPanel geometryEditor = new JPanel(new CardLayout());
        private final JComponent transformPanel;
        private final JComponent meshPanel;
        private final JComponent materialPanel;
        private final JComponent componentsPanel;

        Inspector(EditorController controller) {
            this.controller = controller;
            transformPanel = transformTab();
            meshPanel = meshTab();
            materialPanel = materialTab();
            componentsPanel = componentTab();
            addTab("Transform", transformPanel);
        }

        void update(EditorController.State state) {
            this.state = state;
            var node = selected();
            rebuildTabs(node);
            if (node == null) return;
            name.setText(node.label());
            var t = node.localTransform();
            put(transform, t.position, 0);
            put(transform, t.rotation, 3);
            put(transform, t.scale, 6);
            parent.removeAllItems();
            parent.addItem(new NodeChoice(null, "Scene root"));
            int parentIndex = 0, index = 1;
            for (var n : state.snapshot().nodes())
                if (!n.id().equals(node.id())) {
                    parent.addItem(new NodeChoice(n.id(), n.label()));
                    if (n.id().equals(node.parentId())) parentIndex = index;
                    index++;
                }
            parent.setSelectedIndex(parentIndex);
            if (node.geometry() != null) {
                var geometry = state.snapshot().requireGeometry(node.geometry().geometryId());
                var geometryValue = geometry.geometry();
                boolean polygon = geometryValue instanceof PolygonMesh;
                meshNote.setText(geometryLabel(geometryValue));
                ((CardLayout) geometryEditor.getLayout())
                        .show(
                                geometryEditor,
                                geometryValue instanceof AnalyticSphere ? "analytic" : "polygon");
                if (geometryValue instanceof AnalyticSphere sphere) {
                    put(sphereCenter, sphere.center(), 0);
                    sphereRadius.setText(Float.toString(sphere.radius()));
                }
                var selectedVertex = state.vertexSelection();
                var selectedEdge = state.edgeSelection();
                var selectedFace = state.faceSelection();
                if (selectedVertex != null && selectedVertex.nodeId().equals(node.id())) {
                    meshFace.setText("Selected vertex ID: " + selectedVertex.vertexId());
                } else if (selectedEdge != null && selectedEdge.nodeId().equals(node.id())) {
                    meshFace.setText(
                            "Selected edge IDs: "
                                    + selectedEdge.firstVertexId()
                                    + " - "
                                    + selectedEdge.secondVertexId());
                } else if (selectedFace != null && selectedFace.nodeId().equals(node.id())) {
                    meshFace.setText("Selected face ID: " + selectedFace.faceId());
                } else {
                    meshFace.setText("No mesh element selected");
                }
                applySphere.setEnabled(geometryValue instanceof AnalyticSphere);
                approximateSphere.setEnabled(geometryValue instanceof AnalyticSphere);
                boolean selectedTranslatableElement =
                        (selectedVertex != null && selectedVertex.nodeId().equals(node.id()))
                                || (selectedEdge != null && selectedEdge.nodeId().equals(node.id()))
                                || (selectedFace != null
                                        && selectedFace.nodeId().equals(node.id()));
                translateElement.setEnabled(polygon && selectedTranslatableElement);
                extrudeFace.setEnabled(
                        polygon && selectedFace != null && selectedFace.nodeId().equals(node.id()));
                var asset = state.snapshot().requireMaterial(node.geometry().materialId());
                var m = asset.material();
                put(color, m.color(), 0);
                kind.setSelectedItem(m.kind());
                roughness.setText(Float.toString(m.roughness()));
                ior.setText(Float.toString(m.ior()));
            }
            rebuildComponents(node);
        }

        private void rebuildTabs(SceneNode node) {
            String selectedTitle =
                    getSelectedIndex() < 0 ? "Transform" : getTitleAt(getSelectedIndex());
            removeAll();
            addTab("Transform", transformPanel);
            setEnabledAt(0, node != null);
            if (node != null && node.geometry() != null) {
                addTab("Mesh", meshPanel);
                addTab("Material", materialPanel);
            }
            if (node != null && (node.light() != null || node.camera() != null))
                addTab("Components", componentsPanel);
            for (int i = 0; i < getTabCount(); i++)
                if (getTitleAt(i).equals(selectedTitle)) {
                    setSelectedIndex(i);
                    return;
                }
            setSelectedIndex(0);
        }

        private JComponent transformTab() {
            var panel = stack();
            panel.add(row("Name", name));
            var rename = new JButton("Rename");
            rename.addActionListener(
                    _ -> {
                        var n = selected();
                        if (n != null) controller.rename(n.id(), name.getText());
                    });
            panel.add(rename);
            String[] labels = {"Position X", "Y", "Z", "Rotation X", "Y", "Z", "Scale X", "Y", "Z"};
            for (int i = 0; i < 9; i++) panel.add(row(labels[i], transform[i]));
            var apply = new JButton("Apply transform (one edit)");
            apply.addActionListener(_ -> applyTransform());
            panel.add(apply);
            panel.add(row("Parent", parent));
            var reparent = new JButton("Reparent · keep local pose");
            reparent.addActionListener(
                    _ -> {
                        var n = selected();
                        var p = (NodeChoice) parent.getSelectedItem();
                        if (n != null) controller.reparent(n.id(), p == null ? null : p.id());
                    });
            panel.add(reparent);
            return scroll(panel);
        }

        private JComponent materialTab() {
            var panel = stack();
            panel.add(row("Kind", kind));
            panel.add(row("Linear red", color[0]));
            panel.add(row("Green", color[1]));
            panel.add(row("Blue", color[2]));
            panel.add(row("Roughness", roughness));
            panel.add(row("IOR", ior));
            var apply = new JButton("Apply");
            apply.addActionListener(_ -> applyMaterial());
            panel.add(apply);
            return scroll(panel);
        }

        private JComponent meshTab() {
            var panel = stack();
            panel.add(line(meshNote));
            panel.add(new JSeparator());

            geometryEditor.add(analyticSpherePanel(), "analytic");
            geometryEditor.add(polygonMeshPanel(), "polygon");
            geometryEditor.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
            panel.add(geometryEditor);
            return scroll(panel);
        }

        private JComponent analyticSpherePanel() {
            var panel = section();
            panel.add(line(new JLabel("Analytic sphere (asset-local)")));
            panel.add(row("Center X", sphereCenter[0]));
            panel.add(row("Center Y", sphereCenter[1]));
            panel.add(row("Center Z", sphereCenter[2]));
            panel.add(row("Radius", sphereRadius));
            applySphere.setToolTipText(
                    "Apply center and radius to the selected object's shape as one undoable edit");
            applySphere.addActionListener(
                    _ -> {
                        try {
                            applyAnalyticSphere(vec(sphereCenter, 0), number(sphereRadius));
                        } catch (RuntimeException error) {
                            showError(error);
                        }
                    });
            panel.add(line(applySphere));
            panel.add(new JSeparator());
            panel.add(
                    infoText(
                            "Approximation replaces the selected object's analytic shape with"
                                + " polygon topology. It preserves the object, transform, and"
                                + " material, but exact curvature and analytic parameters are lost"
                                + " until Undo."));
            panel.add(row("Detail (4..64)", sphereDetail));
            approximateSphere.setToolTipText(
                    "Create a polygon approximation at the chosen detail as one validated undoable"
                            + " edit");
            approximateSphere.addActionListener(
                    _ -> approximateAnalyticSphere((Integer) sphereDetail.getValue()));
            panel.add(line(approximateSphere));
            return panel;
        }

        private JComponent polygonMeshPanel() {
            var panel = section();
            panel.add(line(new JLabel("Polygon element controls")));
            panel.add(line(meshFace));
            panel.add(row("Local delta X", elementDelta[0]));
            panel.add(row("Local delta Y", elementDelta[1]));
            panel.add(row("Local delta Z", elementDelta[2]));
            translateElement.setToolTipText(
                    "Translate the selected stable vertex, edge endpoints, or face boundary"
                            + " vertices in asset-local units as one validated edit");
            translateElement.addActionListener(
                    _ -> {
                        try {
                            controller.translateSelectedElement(vec(elementDelta, 0));
                        } catch (RuntimeException error) {
                            showError(error);
                        }
                    });
            panel.add(line(translateElement));
            panel.add(new JSeparator());
            panel.add(row("Distance (local units)", extrusionDistance));
            extrudeFace.setToolTipText(
                    "Extrude the selected polygon along its listed-winding normal in one undoable"
                            + " edit");
            extrudeFace.addActionListener(
                    _ -> {
                        try {
                            extrude(Float.parseFloat(extrusionDistance.getText().trim()));
                        } catch (RuntimeException error) {
                            showError(error);
                        }
                    });
            panel.add(line(extrudeFace));
            return panel;
        }

        private JComponent componentTab() {
            return scroll(componentPanel);
        }

        private void rebuildComponents(SceneNode node) {
            componentPanel.removeAll();
            if (node.light() != null) {
                put(light, node.light().color(), 0);
                light[3].setText(Float.toString(node.light().intensity()));
                componentPanel.add(new JLabel("Point light (position uses Transform)"));
                componentPanel.add(row("Light red", light[0]));
                componentPanel.add(row("Green", light[1]));
                componentPanel.add(row("Blue", light[2]));
                componentPanel.add(row("Intensity", light[3]));
                var apply = new JButton("Apply");
                apply.addActionListener(
                        _ -> {
                            var selected = selected();
                            if (selected != null && selected.light() != null)
                                try {
                                    controller.setPointLight(
                                            selected.id(),
                                            new PointLightComponent(
                                                    vec(light, 0), number(light[3])));
                                } catch (RuntimeException error) {
                                    showError(error);
                                }
                        });
                componentPanel.add(apply);
            }
            if (node.light() != null && node.camera() != null) componentPanel.add(new JSeparator());
            if (node.camera() != null) {
                var camera = node.camera().camera();
                projection.setSelectedItem(camera.projection());
                framing.setText(
                        Float.toString(
                                camera.projection() == Camera.Projection.ORTHOGRAPHIC
                                        ? camera.height()
                                        : camera.fov()));
                focus.setText(Float.toString(camera.focus()));
                aperture.setText(Float.toString(camera.aperture()));
                componentPanel.add(new JLabel("Camera optics (pose uses Transform)"));
                componentPanel.add(row("Projection", projection));
                componentPanel.add(row("FOV / ortho height", framing));
                componentPanel.add(row("Focus distance", focus));
                componentPanel.add(row("Aperture", aperture));
                var apply = new JButton("Apply");
                apply.addActionListener(_ -> applyCamera());
                componentPanel.add(apply);
            }
            componentPanel.revalidate();
            componentPanel.repaint();
        }

        private void applyTransform() {
            var n = selected();
            if (n == null) return;
            try {
                controller.applyTransform(
                        n.id(),
                        new Transform(vec(transform, 0), vec(transform, 3), vec(transform, 6)));
            } catch (RuntimeException e) {
                showError(e);
            }
        }

        private void applyMaterial() {
            var node = selected();
            if (node == null || node.geometry() == null) {
                return;
            }
            try {
                var oldMaterial =
                        state.snapshot().requireMaterial(node.geometry().materialId()).material();
                var nextMaterial =
                        oldMaterial
                                .withKind((Material.Kind) kind.getSelectedItem())
                                .withColor(vec(color, 0))
                                .withRoughness(number(roughness))
                                .withIor(number(ior));
                controller.applyMaterial(node.id(), nextMaterial);
            } catch (RuntimeException exception) {
                showError(exception);
            }
        }

        private void applyCamera() {
            var n = selected();
            if (n == null || n.camera() == null) return;
            try {
                var c = n.camera().camera();
                var p = (Camera.Projection) projection.getSelectedItem();
                c = c.withProjection(p.name().toLowerCase(Locale.ROOT));
                c =
                        p == Camera.Projection.ORTHOGRAPHIC
                                ? c.withHeight(number(framing))
                                : c.withFov(number(framing));
                c = c.withFocus(number(focus)).withAperture(number(aperture));
                controller.setCamera(n.id(), new CameraComponent(c));
            } catch (RuntimeException e) {
                showError(e);
            }
        }

        private SceneNode selected() {
            return state == null || state.selection() == null
                    ? null
                    : state.snapshot().findNode(state.selection()).orElse(null);
        }

        private boolean applyAnalyticSphere(Vec3 center, float radius) {
            var node = selected();
            return node != null && controller.applyAnalyticSphere(node.id(), center, radius);
        }

        private boolean approximateAnalyticSphere(int detail) {
            var node = selected();
            return node != null && controller.approximateAnalyticSphere(node.id(), detail);
        }

        private boolean extrude(float distance) {
            return controller.extrudeSelectedFace(distance);
        }

        private boolean translateSelectedElement(Vec3 delta) {
            put(elementDelta, delta, 0);
            long beforeRevision = controller.snapshot().revision();
            translateElement.doClick();
            return controller.snapshot().revision() != beforeRevision;
        }

        private String meshFaceText() {
            return meshFace.getText();
        }

        private String meshGeometryText() {
            return meshNote.getText();
        }

        private boolean meshApproximateEnabled() {
            return approximateSphere.isEnabled();
        }

        private boolean meshExtrudeEnabled() {
            return extrudeFace.isEnabled();
        }

        private void showError(RuntimeException error) {
            JOptionPane.showMessageDialog(
                    this, error.getMessage(), "Invalid value", JOptionPane.ERROR_MESSAGE);
        }

        private boolean hasTab(String title) {
            for (int i = 0; i < getTabCount(); i++) if (getTitleAt(i).equals(title)) return true;
            return false;
        }

        private void selectTab(String title) {
            for (int i = 0; i < getTabCount(); i++)
                if (getTitleAt(i).equals(title)) {
                    setSelectedIndex(i);
                    return;
                }
            throw new IllegalArgumentException("Missing inspector tab: " + title);
        }

        private static JPanel stack() {
            var p = section();
            p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            return p;
        }

        private static JPanel section() {
            return new ViewportWidthPanel();
        }

        private static JTextArea infoText(String value) {
            var text = new JTextArea(value);
            text.setEditable(false);
            text.setFocusable(false);
            text.setOpaque(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setColumns(20);
            text.setRows(5);
            text.setAlignmentX(Component.LEFT_ALIGNMENT);
            text.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
            return text;
        }

        private static JPanel line(Component value) {
            var panel = new JPanel(new BorderLayout());
            if (value instanceof JLabel label) {
                label.setHorizontalAlignment(SwingConstants.LEFT);
            }
            panel.add(value);
            panel.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.setMinimumSize(new Dimension(0, 24));
            panel.setPreferredSize(new Dimension(0, 24));
            panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
            return panel;
        }

        private static JComponent scroll(JComponent value) {
            var scroll = new JScrollPane(value);
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.getVerticalScrollBar().setUnitIncrement(12);
            return scroll;
        }

        private static JPanel row(String label, Component value) {
            var p = new JPanel(new BorderLayout(5, 2));
            p.add(new JLabel(label), BorderLayout.WEST);
            p.add(value);
            p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            return p;
        }

        private static JTextField[] fields(int count, String value) {
            var fields = new JTextField[count];
            Arrays.setAll(fields, _ -> new JTextField(value));
            return fields;
        }

        private static void put(JTextField[] fields, Vec3 value, int offset) {
            fields[offset].setText(Float.toString(value.x()));
            fields[offset + 1].setText(Float.toString(value.y()));
            fields[offset + 2].setText(Float.toString(value.z()));
        }

        private static Vec3 vec(JTextField[] fields, int offset) {
            return new Vec3(
                    number(fields[offset]), number(fields[offset + 1]), number(fields[offset + 2]));
        }

        private static float number(JTextField field) {
            return Float.parseFloat(field.getText().trim());
        }

        private static String geometryLabel(GeometryData geometry) {
            if (geometry instanceof PolygonMesh) return "Polygon mesh";
            if (geometry instanceof AnalyticSphere) return "Sphere";
            return "Geometry";
        }
    }
}
