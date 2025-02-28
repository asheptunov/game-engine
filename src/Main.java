import di.GraphBuilder;
import di.Injector;
import di.Module;
import logging.LogManager;
import logging.Logger;
import rendering.AwtViewer;
import rendering.Checkerboard;
import rendering.CompositeRenderer;
import rendering.Eraser;
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

import static rendering.Color.NamedColor;

private static final Logger LOG = LogManager.instance().getThis();
private static final int WIDTH = 800;
private static final int HEIGHT = 800;
private static final int FRAME_RATE = 144;

private static final AtomicReference<Scene> SCENE = new AtomicReference<>();

public static void main(String[] ignoredArgs) throws InterruptedException {
    LOG.info("Width %d, height %d, frame rate %d hz", WIDTH, HEIGHT, FRAME_RATE);
    Injector.create(new Module() {
                @Override
                public void configure(GraphBuilder graphBuilder) {
                    graphBuilder.bind(int.class).named("width").toInstance(WIDTH);
                    graphBuilder.bind(int.class).named("height").toInstance(HEIGHT);
                    graphBuilder.bind(int.class).named("frame_rate").toInstance(FRAME_RATE);
                    graphBuilder.bind(Clock.class).toInstance(Clock.systemDefaultZone());
                    graphBuilder.bind(Renderer.class).to(CompositeRenderer.class);
                }
            })
            .get(PeriodicExecutor.class)
            .execute();
    var displayRaster = new PixelRaster(WIDTH, HEIGHT, NamedColor.BLACK);
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
            new Eraser(displayRaster),
            new Checkerboard(0.8f, 0.9f, displayRaster),
            switchingRenderer,
            new AwtViewer(displayRaster, switchingListener)
    ));
    new PeriodicExecutor(FRAME_RATE, clock, renderer::render).execute();
}
