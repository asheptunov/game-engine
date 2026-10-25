package game;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/** Independent landscape and block-gallery application using the public engine boundary. */
public final class SampleGameMain {
    private SampleGameMain() {}

    private static engine.WorldSnapshot world(String scene) {
        return switch (scene) {
            case "gallery" -> BlockWorld.gallery().snapshot();
            case "landscape" -> BlockWorld.landscape().snapshot();
            default -> AdventureWorld.create();
        };
    }

    public static void main(String[] args) {
        String scene = args.length == 0 ? "world" : args[0];
        if (!scene.equals("world") && !scene.equals("landscape") && !scene.equals("gallery")) {
            throw new IllegalArgumentException("Scene must be world, landscape or gallery");
        }
        String backend = args.length > 1 ? args[1] : "cpu";
        int width = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int height = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        SwingUtilities.invokeLater(
                () -> {
                    var navigation = new GameNavigation();
                    if (width != 0) {
                        navigation.fixedResolution(width, height);
                    }
                    GameRenderer renderer;
                    try {
                        renderer = new GameRenderer(navigation, world(scene), backend);
                    } catch (RuntimeException | LinkageError failure) {
                        javax.swing.JOptionPane.showMessageDialog(
                                null,
                                failure.getMessage(),
                                "Rendering unavailable - choose CPU with ./game.ps1 -Backend cpu",
                                javax.swing.JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    if (scene.equals("world")) {
                        renderer.dayCycle(true);
                        navigation.speed(8);
                    }
                    var panel = new GamePanel(navigation, renderer);
                    var window = new JFrame("Sample game — " + scene);
                    window.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                    window.setContentPane(panel);
                    window.pack();
                    window.setLocationByPlatform(true);
                    window.addWindowFocusListener(
                            new WindowAdapter() {
                                @Override
                                public void windowLostFocus(WindowEvent event) {
                                    panel.renderingActive(false);
                                }

                                @Override
                                public void windowGainedFocus(WindowEvent event) {
                                    panel.renderingActive(true);
                                }
                            });
                    window.addWindowListener(
                            new WindowAdapter() {
                                @Override
                                public void windowClosed(WindowEvent event) {
                                    panel.close();
                                }
                            });
                    window.setVisible(true);
                });
    }
}
