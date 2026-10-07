package editor;

import engine.*;
import math.Vec3;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/** Complete native Swing authoring surface; safe to construct and paint headlessly. */
public final class SceneEditorPanel extends JPanel implements EditorController.Listener, AutoCloseable {
    private record NodeChoice(NodeId id, String label) { @Override public String toString() { return label; } }
    private static final class TreeNode extends DefaultMutableTreeNode {
        final NodeId id;
        TreeNode(NodeId id, String label) { super(label); this.id = id; }
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
    private final JButton undo = new JButton("Undo"), redo = new JButton("Redo");
    private boolean updatingTree;
    private boolean closed;

    public SceneEditorPanel(EditorController controller) {
        super(new BorderLayout(6, 6));
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("SceneEditorPanel belongs to EDT");
        this.controller = Objects.requireNonNull(controller); commands = new EditorCommandProcessor(controller);
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6)); setBackground(new Color(32, 37, 44));
        add(toolbar(), BorderLayout.NORTH);
        hierarchy.setRootVisible(true); hierarchy.setShowsRootHandles(true); hierarchy.addTreeSelectionListener(this::treeSelected);
        var hierarchyPane = new JPanel(new BorderLayout()); hierarchyPane.setBorder(BorderFactory.createTitledBorder("Scene hierarchy"));
        hierarchyPane.add(new JScrollPane(hierarchy)); hierarchyPane.setPreferredSize(new Dimension(230, 600));
        viewA = new RenderViewPanel(controller, "View A", .58f, .28f, 12);
        viewB = new RenderViewPanel(controller, "View B", -.72f, .42f, 11);
        var views = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, viewA, viewB); views.setResizeWeight(.5); views.setDividerSize(5);
        inspector = new Inspector(controller); inspector.setPreferredSize(new Dimension(310, 600));
        var left = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, hierarchyPane, views); left.setResizeWeight(.16); left.setDividerLocation(230);
        var body = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, inspector); body.setResizeWeight(.79); body.setDividerLocation(1050);
        add(body, BorderLayout.CENTER); add(commandPanel(), BorderLayout.SOUTH);
        controller.addListener(this);
    }

    private JComponent toolbar() {
        var bar = new JToolBar(); bar.setFloatable(false);
        addButton(bar, "New", _ -> guardUnsaved(controller::newScene));
        addButton(bar, "Open", _ -> guardUnsaved(this::chooseOpen)); addButton(bar, "Save", _ -> chooseSave(null));
        bar.addSeparator();
        for (var primitive : List.of(EditorController.Primitive.BOX, EditorController.Primitive.SPHERE,
                EditorController.Primitive.PLANE, EditorController.Primitive.GROUP,
                EditorController.Primitive.POINT_LIGHT, EditorController.Primitive.CAMERA))
            addButton(bar, label(primitive), _ -> controller.create(primitive));
        bar.addSeparator();
        addButton(bar, "Duplicate", _ -> controller.duplicateSelection()); addButton(bar, "Delete", _ -> controller.deleteSelection());
        undo.addActionListener(_ -> controller.undo()); redo.addActionListener(_ -> controller.redo()); bar.add(undo); bar.add(redo);
        bar.add(Box.createHorizontalGlue()); bar.add(dirty);
        return bar;
    }

    private JComponent commandPanel() {
        var panel = new JPanel(new BorderLayout(5, 4)); panel.setBorder(BorderFactory.createTitledBorder("Commands (type help)"));
        commandLog.setEditable(false); commandLog.setLineWrap(true); commandLog.setWrapStyleWord(true);
        commandEntry.addActionListener(_ -> {
            var text = commandEntry.getText(); if (text.isBlank()) return;
            commandLog.append("> " + text + "\n" + commands.execute(text) + "\n"); commandEntry.setText("");
        });
        panel.add(new JScrollPane(commandLog), BorderLayout.CENTER); panel.add(commandEntry, BorderLayout.SOUTH);
        var footer = new JPanel(new BorderLayout()); footer.add(status); footer.add(dirtyFooter, BorderLayout.EAST); panel.add(footer, BorderLayout.NORTH);
        return panel;
    }

    private static void addButton(JToolBar bar, String label, java.awt.event.ActionListener action) { var button = new JButton(label); button.addActionListener(action); bar.add(button); }
    private static String label(EditorController.Primitive p) { return switch (p) { case POINT_LIGHT -> "Light"; default -> p.name().substring(0, 1) + p.name().substring(1).toLowerCase(Locale.ROOT); }; }

    @Override public void changed(EditorController.State state) {
        rebuildTree(state); inspector.update(state); viewA.update(state); viewB.update(state);
        undo.setEnabled(state.canUndo() && !state.busy()); redo.setEnabled(state.canRedo() && !state.busy());
        status.setText(state.status()); var marker = (state.dirty() ? "● Unsaved" : "Saved") + (state.busy() ? " · working…" : ""); dirty.setText(marker); dirtyFooter.setText(marker);
    }

    private void rebuildTree(EditorController.State state) {
        var expanded = expandedIds(); var root = new TreeNode(null, "Scene"); var byId = new LinkedHashMap<NodeId, TreeNode>();
        for (var node : state.snapshot().nodes()) byId.put(node.id(), new TreeNode(node.id(), icon(node) + " " + node.label()));
        for (var node : state.snapshot().nodes()) {
            var treeNode = byId.get(node.id()); var parent = node.parentId() == null ? root : byId.get(node.parentId());
            (parent == null ? root : parent).add(treeNode);
        }
        updatingTree = true;
        try {
            hierarchy.setModel(new DefaultTreeModel(root));
            expandMatching(new TreePath(root), expanded);
            if (state.selection() != null) selectTreeNode(root, state.selection());
        } finally { updatingTree = false; }
    }

    private Set<NodeId> expandedIds() {
        var result = new HashSet<NodeId>();
        for (int row = 0; row < hierarchy.getRowCount(); row++) if (hierarchy.isExpanded(row)) {
            var value = hierarchy.getPathForRow(row).getLastPathComponent(); if (value instanceof TreeNode n && n.id != null) result.add(n.id);
        }
        return result;
    }
    private void expandMatching(TreePath path, Set<NodeId> expanded) {
        var node = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (!(node instanceof TreeNode n) || n.id == null || expanded.contains(n.id)) hierarchy.expandPath(path);
        for (int i = 0; i < node.getChildCount(); i++) expandMatching(path.pathByAddingChild(node.getChildAt(i)), expanded);
    }
    private void selectTreeNode(DefaultMutableTreeNode node, NodeId id) {
        if (node instanceof TreeNode n && Objects.equals(n.id, id)) { hierarchy.setSelectionPath(new TreePath(node.getPath())); hierarchy.scrollPathToVisible(new TreePath(node.getPath())); return; }
        for (int i = 0; i < node.getChildCount(); i++) selectTreeNode((DefaultMutableTreeNode) node.getChildAt(i), id);
    }
    private void treeSelected(TreeSelectionEvent event) { if (!updatingTree && event.getPath().getLastPathComponent() instanceof TreeNode node) controller.select(node.id); }
    private static String icon(SceneNode node) { if (node.camera() != null) return "◉"; if (node.light() != null) return "☀"; if (node.geometry() != null) return "◆"; return "▾"; }

    private void chooseOpen() {
        var chooser = chooser(); if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) controller.load(chooser.getSelectedFile().toPath());
    }
    private void chooseSave(Runnable continuation) {
        Path path = controller.state().file();
        if (path == null) {
            var chooser = chooser(); if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return; path = scenePath(chooser.getSelectedFile().toPath());
        }
        saveThen(path, continuation);
    }
    private void saveThen(Path path, Runnable continuation) {
        controller.save(path).thenAccept(ok -> SwingUtilities.invokeLater(() -> {
            if (!ok || continuation == null) return;
            if (controller.dirty()) guardUnsaved(continuation); else continuation.run();
        }));
    }
    private JFileChooser chooser() { var chooser = new JFileChooser(); chooser.setDialogTitle("Ray tracing scene (.scene.xml)"); return chooser; }
    private static Path scenePath(Path path) { return path.toString().endsWith(".scene.xml") ? path : Path.of(path + ".scene.xml"); }
    private void guardUnsaved(Runnable action) {
        if (!controller.dirty()) { action.run(); return; }
        Object[] options = {"Save", "Discard", "Cancel"}; int result = JOptionPane.showOptionDialog(this,
                "Save changes before continuing?", "Unsaved scene", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (result == 0) { Path path = controller.state().file(); if (path == null) chooseSave(action); else saveThen(path, action); }
        else if (result == 1) action.run();
    }
    public void requestClose() { guardUnsaved(this::closeWindow); }
    private void closeWindow() { var window = SwingUtilities.getWindowAncestor(this); close(); if (window != null) window.dispose(); }
    public void setRenderingActive(boolean active) { viewA.setActive(active); viewB.setActive(active); }
    public boolean viewsReady() { return viewA.hasFrame() && viewB.hasFrame(); }
    public long[] displayedRevisions() { return new long[]{viewA.displayedRevision(), viewB.displayedRevision()}; }
    void pickInViewForTest(int view, float u, float v) { (view == 0 ? viewA : viewB).pickNormalizedForTest(u, v); }
    void navigateViewForTest(int view, float yawDelta) { (view == 0 ? viewA : viewB).navigateForTest(yawDelta); }

    @Override public void close() {
        if (closed) return; closed = true; controller.removeListener(this); viewA.close(); viewB.close(); controller.close();
    }

    private static final class Inspector extends JTabbedPane {
        private final EditorController controller;
        private EditorController.State state;
        private final JTextField name = new JTextField();
        private final JTextField[] transform = fields(9, "0");
        private final JComboBox<NodeChoice> parent = new JComboBox<>();
        private final JComboBox<MaterialAsset> material = new JComboBox<>();
        private final JComboBox<Material.Kind> kind = new JComboBox<>(Material.Kind.values());
        private final JTextField[] color = fields(3, ".8");
        private final JTextField roughness = new JTextField("0"), ior = new JTextField("1.5");
        private final JLabel materialNote = new JLabel("No material");
        private final JTextField[] light = fields(4, "1");
        private final JComboBox<Camera.Projection> projection = new JComboBox<>(Camera.Projection.values());
        private final JTextField framing = new JTextField("50"), focus = new JTextField("5"), aperture = new JTextField("0");
        private final JLabel componentNote = new JLabel("No components");

        Inspector(EditorController controller) {
            this.controller = controller;
            material.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                    var component = super.getListCellRendererComponent(list, value, index, selected, focus);
                    if (value instanceof MaterialAsset asset) setText(asset.label()); return component;
                }
            });
            addTab("Transform", transformTab()); addTab("Material", materialTab()); addTab("Components", componentTab());
        }

        void update(EditorController.State state) {
            this.state = state; var node = selected(); setEnabledAt(0, node != null); setEnabledAt(1, node != null && node.geometry() != null); setEnabledAt(2, node != null);
            if (node == null) return;
            name.setText(node.label()); var t = node.localTransform(); put(transform, t.position, 0); put(transform, t.rotation, 3); put(transform, t.scale, 6);
            parent.removeAllItems(); parent.addItem(new NodeChoice(null, "Scene root")); int parentIndex = 0, index = 1;
            for (var n : state.snapshot().nodes()) if (!n.id().equals(node.id())) { parent.addItem(new NodeChoice(n.id(), n.label())); if (n.id().equals(node.parentId())) parentIndex = index; index++; }
            parent.setSelectedIndex(parentIndex);
            material.removeAllItems(); for (var asset : state.snapshot().materialAssets()) material.addItem(asset);
            if (node.geometry() != null) {
                var asset = state.snapshot().requireMaterial(node.geometry().materialId()); material.setSelectedItem(asset); var m = asset.material();
                put(color, m.color(), 0); kind.setSelectedItem(m.kind()); roughness.setText(Float.toString(m.roughness())); ior.setText(Float.toString(m.ior()));
                long uses = state.snapshot().nodes().stream().filter(n -> n.geometry() != null && n.geometry().materialId().equals(asset.id())).count();
                materialNote.setText(uses + " node" + (uses == 1 ? " uses" : "s use") + " this shared material");
            }
            if (node.light() != null) { put(light, node.light().color(), 0); light[3].setText(Float.toString(node.light().intensity())); componentNote.setText("Point light selected"); }
            if (node.camera() != null) { var c = node.camera().camera(); projection.setSelectedItem(c.projection()); framing.setText(Float.toString(c.projection() == Camera.Projection.ORTHOGRAPHIC ? c.height() : c.fov())); focus.setText(Float.toString(c.focus())); aperture.setText(Float.toString(c.aperture())); componentNote.setText("Camera selected"); }
            if (node.light() == null && node.camera() == null) componentNote.setText("Add a point light or camera component");
        }

        private JComponent transformTab() {
            var panel = stack(); panel.add(row("Name", name)); var rename = new JButton("Rename"); rename.addActionListener(_ -> { var n = selected(); if (n != null) controller.rename(n.id(), name.getText()); }); panel.add(rename);
            String[] labels = {"Position X", "Y", "Z", "Rotation X", "Y", "Z", "Scale X", "Y", "Z"}; for (int i = 0; i < 9; i++) panel.add(row(labels[i], transform[i]));
            var apply = new JButton("Apply transform (one edit)"); apply.addActionListener(_ -> applyTransform()); panel.add(apply);
            panel.add(row("Parent", parent)); var reparent = new JButton("Reparent · keep local pose"); reparent.addActionListener(_ -> { var n = selected(); var p = (NodeChoice) parent.getSelectedItem(); if (n != null) controller.reparent(n.id(), p == null ? null : p.id()); }); panel.add(reparent); return scroll(panel);
        }
        private JComponent materialTab() {
            var panel = stack(); panel.add(row("Assigned", material)); var assign = new JButton("Assign selected material"); assign.addActionListener(_ -> { var n = selected(); var a = (MaterialAsset) material.getSelectedItem(); if (n != null && a != null) controller.assignMaterial(n.id(), a.id()); }); panel.add(assign);
            panel.add(materialNote); panel.add(row("Kind", kind)); panel.add(row("Linear red", color[0])); panel.add(row("Green", color[1])); panel.add(row("Blue", color[2])); panel.add(row("Roughness", roughness)); panel.add(row("IOR", ior));
            var shared = new JButton("Apply to shared material"); shared.addActionListener(_ -> applyMaterial()); var unique = new JButton("Make unique"); unique.addActionListener(_ -> { var n = selected(); if (n != null) controller.makeMaterialUnique(n.id()); }); panel.add(shared); panel.add(unique); return scroll(panel);
        }
        private JComponent componentTab() {
            var panel = stack(); panel.add(componentNote); panel.add(new JLabel("Point light (position uses Transform)")); panel.add(row("Light red", light[0])); panel.add(row("Green", light[1])); panel.add(row("Blue", light[2])); panel.add(row("Intensity", light[3]));
            var applyLight = new JButton("Add / apply point light"); applyLight.addActionListener(_ -> { var n = selected(); if (n != null) try { controller.setPointLight(n.id(), new PointLightComponent(vec(light, 0), number(light[3]))); } catch (RuntimeException e) { showError(e); } }); panel.add(applyLight);
            var removeLight = new JButton("Remove point light"); removeLight.addActionListener(_ -> { var n = selected(); if (n != null) controller.setPointLight(n.id(), null); }); panel.add(removeLight);
            panel.add(new JSeparator()); panel.add(new JLabel("Camera optics (pose uses Transform)")); panel.add(row("Projection", projection)); panel.add(row("FOV / ortho height", framing)); panel.add(row("Focus distance", focus)); panel.add(row("Aperture", aperture));
            var applyCamera = new JButton("Add / apply camera"); applyCamera.addActionListener(_ -> applyCamera()); panel.add(applyCamera); var removeCamera = new JButton("Remove camera"); removeCamera.addActionListener(_ -> { var n = selected(); if (n != null) controller.setCamera(n.id(), null); }); panel.add(removeCamera); return scroll(panel);
        }

        private void applyTransform() { var n = selected(); if (n == null) return; try { controller.applyTransform(n.id(), new Transform(vec(transform, 0), vec(transform, 3), vec(transform, 6))); } catch (RuntimeException e) { showError(e); } }
        private void applyMaterial() {
            var n = selected(); if (n == null || n.geometry() == null) return;
            try { var old = state.snapshot().requireMaterial(n.geometry().materialId()).material(); var next = old.withKind((Material.Kind) kind.getSelectedItem()).withColor(vec(color, 0)).withRoughness(number(roughness)).withIor(number(ior)); controller.editSharedMaterial(n.geometry().materialId(), next); } catch (RuntimeException e) { showError(e); }
        }
        private void applyCamera() {
            var n = selected(); if (n == null) return;
            try {
                var c = n.camera() == null ? StarterScene.canonicalCamera() : n.camera().camera(); var p = (Camera.Projection) projection.getSelectedItem(); c = c.withProjection(p.name().toLowerCase(Locale.ROOT));
                c = p == Camera.Projection.ORTHOGRAPHIC ? c.withHeight(number(framing)) : c.withFov(number(framing)); c = c.withFocus(number(focus)).withAperture(number(aperture)); controller.setCamera(n.id(), new CameraComponent(c));
            } catch (RuntimeException e) { showError(e); }
        }
        private SceneNode selected() { return state == null || state.selection() == null ? null : state.snapshot().findNode(state.selection()).orElse(null); }
        private void showError(RuntimeException error) { JOptionPane.showMessageDialog(this, error.getMessage(), "Invalid value", JOptionPane.ERROR_MESSAGE); }
        private static JPanel stack() { var p = new JPanel(); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS)); p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8)); return p; }
        private static JComponent scroll(JComponent value) { var s = new JScrollPane(value); s.getVerticalScrollBar().setUnitIncrement(12); return s; }
        private static JPanel row(String label, Component value) { var p = new JPanel(new BorderLayout(5, 2)); p.add(new JLabel(label), BorderLayout.WEST); p.add(value); p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30)); return p; }
        private static JTextField[] fields(int count, String value) { var fields = new JTextField[count]; Arrays.setAll(fields, _ -> new JTextField(value)); return fields; }
        private static void put(JTextField[] fields, Vec3 value, int offset) { fields[offset].setText(Float.toString(value.x())); fields[offset + 1].setText(Float.toString(value.y())); fields[offset + 2].setText(Float.toString(value.z())); }
        private static Vec3 vec(JTextField[] fields, int offset) { return new Vec3(number(fields[offset]), number(fields[offset + 1]), number(fields[offset + 2])); }
        private static float number(JTextField field) { return Float.parseFloat(field.getText().trim()); }
    }
}
