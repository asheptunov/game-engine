import logging.LogManager;
import logging.Logger;
import rendering.AwtViewer;
import rendering.BlendMode;
import rendering.MixerProxy;
import rendering.MixingRenderer;
import rendering.Patterns;
import rendering.PixelRaster;
import rendering.Raster;
import rendering.Renderer;
import scenes.MuxProxyBuilder;
import scenes.Scene;
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
    var displayRaster = new PixelRaster(WIDTH, HEIGHT, NamedColor.BLACK);
    var clock = Clock.systemUTC();
    var textureEditor = new TextureEditor(displayRaster, clock, 16, 16);
    var switchingListener = MuxProxyBuilder.create()
            .withInterfaces(KeyListener.class, MouseListener.class, MouseWheelListener.class, MouseMotionListener.class)
            .withTargetForScene(TextureEditor.class, textureEditor)
            .withSceneSupplier(SCENE::get)
            .build();
    var switchingRenderer = (Renderer) MixerProxy.builder(Renderer.class)
            .withTarget(() -> true, textureEditor)
            .withTarget()
            .withTargetForScene(TextureEditor.class, textureEditor)
            .withSceneSupplier(SCENE::get)
            .build();
    var renderer = MixingRenderer.builder()
            .withChannel(_ -> true, new Renderer() {
                final Raster checkerboard = Patterns.checkerboard(WIDTH, HEIGHT, 0.8f, 0.9f);

                @Override
                public void render(Context context) {
                    context.painter().drawImg(0, 0, checkerboard, BlendMode.NORMAL);
                }
            })
            .build();
    var renderer = new MixingRenderer(List.of(
            switchingRenderer,
            new AwtViewer(displayRaster, switchingListener)
    ));
    new PeriodicExecutor(FRAME_RATE, clock, renderer::render).execute();
}
