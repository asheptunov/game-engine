package game;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/** Independent landscape and block-gallery application using the public engine boundary. */
public final class SampleGameMain {
    private SampleGameMain() {}

    static engine.WorldSnapshot world(String scene) {
        return switch (scene) {
            case "gallery" -> BlockWorld.gallery().snapshot();
            case "landscape" -> BlockWorld.landscape().snapshot();
            default -> AdventureWorld.create();
        };
    }

    public static void main(String[] args) {
        launch(args, engine.RenderEngine::openSession);
    }

    // Package-local launch seam for the separately compiled manual fault preview.
    static void launch(String[] args, GameRenderer.SessionFactory factory) {
        String scene = args.length == 0 ? "world" : args[0];
        if (!scene.equals("world") && !scene.equals("landscape") && !scene.equals("gallery")) {
            throw new IllegalArgumentException("Scene must be world, landscape or gallery");
        }
        String backend = args.length > 1 ? args[1] : "cpu";
        int width = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int height = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        var diagnostics = new GameDiagnostics();
        var crashes =
                new GameCrashHandler(
                        scene,
                        backend,
                        width,
                        height,
                        java.nio.file.Path.of("out/game/crashes"),
                        diagnostics);
        GameCapture.Loaded capture;
        try {
            capture = args.length > 4 ? GameCapture.load(java.nio.file.Path.of(args[4])) : null;
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("Cannot load capture", failure);
        }
        SwingUtilities.invokeLater(
                () ->
                        showGame(
                                scene,
                                backend,
                                width,
                                height,
                                diagnostics,
                                crashes,
                                factory,
                                capture));
    }

    private static void showGame(
            String scene,
            String backend,
            int width,
            int height,
            GameDiagnostics diagnostics,
            GameCrashHandler crashes,
            GameRenderer.SessionFactory factory,
            GameCapture.Loaded capture) {

        var navigation = new GameNavigation();
        if (width != 0) {
            navigation.fixedResolution(width, height);
        }
        GameRenderer renderer;
        try {
            renderer =
                    new GameRenderer(
                            navigation,
                            capture == null
                                    ? world(scene)
                                    : capture.world(capture.manifest().current().world()),
                            backend,
                            GameRenderer::copyImage,
                            new GameRenderer.RuntimeHooks(diagnostics, crashes::fatal, factory));
        } catch (Throwable failure) {
            crashes.fatal(GameRenderer.asFailure(backend, "initialization", failure));
            return;
        }
        crashes.cleanup(renderer::close);
        crashes.releasePointer(navigation::release);
        if (scene.equals("world")) {
            renderer.dayCycle(true);
            navigation.speed(5);
        }
        navigation.walking(true);
        if (capture != null) {
            renderer.restoreCapture(capture);
        }
        var panel = new GamePanel(navigation, renderer);
        if (capture != null) {
            var saved = capture.manifest().current().navigation();
            panel.setPreferredSize(new java.awt.Dimension(saved.width(), saved.height()));
        }
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
        crashes.releasePointer(
                () -> {
                    navigation.release();
                    try {
                        SwingUtilities.invokeAndWait(
                                () -> {
                                    panel.releaseForFailure();
                                    window.dispose();
                                });
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
        window.setVisible(true);
    }
}
