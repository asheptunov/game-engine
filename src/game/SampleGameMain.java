package game;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/** Independent block-gallery application using the public engine boundary. */
public final class SampleGameMain {
    private SampleGameMain() {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(
                () -> {
                    var navigation = new GameNavigation();
                    var renderer = new GameRenderer(navigation, BlockWorld.gallery());
                    var panel = new GamePanel(navigation, renderer);
                    var window = new JFrame("Sample game — textured gallery (G2)");
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
