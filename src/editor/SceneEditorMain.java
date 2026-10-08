package editor;

import java.awt.*;
import java.awt.event.*;

import javax.swing.*;

/** Separate scene-authoring executable. */
public final class SceneEditorMain {
    private SceneEditorMain() {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(
                () -> {
                    UIManager.put("swing.boldMetal", false);
                    var controller = new EditorController();
                    var panel = new SceneEditorPanel(controller);
                    var frame = new JFrame("Ray Tracing Scene Editor");
                    var usable =
                            GraphicsEnvironment.getLocalGraphicsEnvironment()
                                    .getMaximumWindowBounds();
                    frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                    frame.setContentPane(panel);
                    frame.setMinimumSize(
                            new Dimension(
                                    Math.min(1120, usable.width), Math.min(720, usable.height)));
                    frame.setSize(Math.min(1500, usable.width), Math.min(920, usable.height));
                    frame.setLocationByPlatform(true);
                    final boolean[] iconified = {false};
                    frame.addWindowListener(
                            new WindowAdapter() {
                                @Override
                                public void windowClosing(WindowEvent event) {
                                    panel.requestClose();
                                }

                                @Override
                                public void windowIconified(WindowEvent event) {
                                    iconified[0] = true;
                                    panel.setRenderingActive(false);
                                }

                                @Override
                                public void windowDeiconified(WindowEvent event) {
                                    iconified[0] = false;
                                    panel.setRenderingActive(frame.isFocused());
                                }

                                @Override
                                public void windowClosed(WindowEvent event) {
                                    panel.close();
                                }
                            });
                    frame.addWindowFocusListener(
                            new WindowAdapter() {
                                @Override
                                public void windowLostFocus(WindowEvent event) {
                                    panel.setRenderingActive(false);
                                }

                                @Override
                                public void windowGainedFocus(WindowEvent event) {
                                    if (!iconified[0]) panel.setRenderingActive(true);
                                }
                            });
                    frame.setVisible(true);
                });
    }
}
