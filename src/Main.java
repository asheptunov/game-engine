import logging.LogManager;
import logging.Logger;
import rendering.AwtViewer;
import rendering.Color;
import rendering.CompositeRenderer;
import rendering.PixelRaster;
import rendering.Renderer;
import scenes.Scene;
import scenes.SceneAwareProxyBuilder;
import scenes.textureeditor.TextureEditor;
import timing.PeriodicExecutor;

import java.awt.event.KeyListener;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelListener;

private static final Logger LOG = LogManager.instance().getThis();
private static final int WIDTH = 800;
private static final int HEIGHT = 800;
private static final int FRAME_RATE = 60;

private static final AtomicReference<Scene> SCENE = new AtomicReference<>();

public static void main(String[] ignoredArgs) throws InterruptedException {
    LOG.info("Width %d, height %d, frame rate %d hz", WIDTH, HEIGHT, FRAME_RATE);
    var displayRaster = new PixelRaster(WIDTH, HEIGHT, (_, _) -> Color.BLACK);
    var clock = Clock.systemUTC();
    var textureEditor = new TextureEditor(displayRaster, clock, 16, 16);
    SCENE.set(textureEditor);
    var switchingListener = SceneAwareProxyBuilder.create()
            .withInterfaces(KeyListener.class, MouseListener.class, MouseWheelListener.class, MouseMotionListener.class)
            .withTargetForScene(TextureEditor.class, textureEditor)
            .withSceneSupplier(SCENE::get)
            .build();
    var switchingRenderer = (Renderer) SceneAwareProxyBuilder.create()
            .withInterface(Renderer.class)
            .withTargetForScene(TextureEditor.class, textureEditor)
            .withSceneSupplier(SCENE::get)
            .build();
    var renderer = new CompositeRenderer(List.of(
            switchingRenderer,
            new AwtViewer(displayRaster, switchingListener)
    ));
    new PeriodicExecutor(FRAME_RATE, clock, renderer::render).execute();
}
