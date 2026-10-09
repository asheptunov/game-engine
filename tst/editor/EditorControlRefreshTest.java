package editor;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertFalse;
import static harness.Assertions.assertTrue;

import engine.Material;
import engine.PolygonMesh;
import engine.Transform;

import harness.Test;

import math.Vec3;

import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.util.concurrent.FutureTask;

import javax.swing.JComboBox;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingUtilities;

/** Geometry publications preserve controls and drafts; changed source values still refresh. */
public class EditorControlRefreshTest {
    public static void main(String[] args) {
        harness.SuiteRunner.runThis();
    }

    @Test
    public void draggingRetainsHierarchyTabsChoicesAndUnrelatedDrafts() throws Exception {
        onEdt(
                () -> {
                    var controller = new EditorController();
                    var box =
                            controller.snapshot().nodes().stream()
                                    .filter(node -> node.label().equals("Teal box"))
                                    .findFirst()
                                    .orElseThrow();
                    controller.select(box.id());
                    var panel = new SceneEditorPanel(controller);
                    try {
                        assertTrue(
                                controller.setSelectionMode(EditorController.SelectionMode.VERTEX));
                        var mesh =
                                (PolygonMesh)
                                        controller
                                                .snapshot()
                                                .requireGeometry(box.geometry().geometryId())
                                                .geometry();
                        assertTrue(
                                controller.selectVertex(mesh.editableVertices().getFirst().id()));
                        var tree = (JTree) field(panel, "hierarchy");
                        var treeModel = tree.getModel();
                        var inspector = (JTabbedPane) field(panel, "inspector");
                        panel.selectInspectorTabForTest("Material");
                        var selectedTab = inspector.getSelectedComponent();
                        var name = (JTextField) field(inspector, "name");
                        var transform = ((JTextField[]) field(inspector, "transform"))[0];
                        var color = ((JTextField[]) field(inspector, "color"))[0];
                        name.setText("unfinished name");
                        transform.setText("unfinished transform");
                        color.setText("unfinished material");
                        color.setCaretPosition(3);
                        var events = new int[1];
                        inspector.addContainerListener(
                                new ContainerAdapter() {
                                    @Override
                                    public void componentRemoved(ContainerEvent event) {
                                        events[0]++;
                                    }
                                });
                        var parent = (JComboBox<?>) field(inspector, "parent");
                        parent.addActionListener(event -> events[0]++);
                        var camera = (JComboBox<?>) field(field(panel, "viewA"), "cameraChoice");
                        camera.addActionListener(event -> events[0]++);
                        assertTrue(
                                controller.beginElementGesture(controller.snapshot().revision()));
                        for (int index = 1; index <= 20; index++) {
                            assertTrue(
                                    controller.updateElementGesture(new Vec3(index * .002f, 0, 0)));
                            assertTrue(tree.getModel() == treeModel);
                            assertTrue(inspector.getSelectedComponent() == selectedTab);
                            assertEquals("unfinished name", name.getText());
                            assertEquals("unfinished transform", transform.getText());
                            assertEquals("unfinished material", color.getText());
                            assertEquals(3, color.getCaretPosition());
                        }
                        assertTrue(controller.commitTransformGesture());
                        assertEquals(0, events[0]);
                        assertTrue(controller.rename(box.id(), "Renamed box"));
                        assertFalse(tree.getModel() == treeModel);
                        assertEquals("Renamed box", name.getText());
                        assertTrue(
                                tree.getSelectionPath()
                                        .getLastPathComponent()
                                        .toString()
                                        .contains("Renamed box"));
                        assertTrue(
                                controller.applyTransform(
                                        box.id(),
                                        new Transform(
                                                new Vec3(1, 2, 3), Vec3.ZERO, new Vec3(1, 1, 1))));
                        assertEquals("1.0", transform.getText());
                        assertEquals("unfinished material", color.getText());
                        assertTrue(
                                controller.applyMaterial(
                                        box.id(),
                                        new Material("new color", new Vec3(.3f, .4f, .5f))));
                        assertEquals("0.3", color.getText());
                        controller.select(null);
                        assertTrue(tree.getSelectionPath() == null);
                        assertEquals(1, inspector.getTabCount());
                        assertFalse(inspector.isEnabledAt(0));
                    } finally {
                        panel.close();
                    }
                });
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void onEdt(CheckedAction action) throws Exception {
        var task =
                new FutureTask<Void>(
                        () -> {
                            action.run();
                            return null;
                        });
        SwingUtilities.invokeAndWait(task);
        task.get();
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }
}
