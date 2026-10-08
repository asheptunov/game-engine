import di.GenericType;
import di.GraphBuilder;
import di.Injector;
import di.Module;
import di.annotations.Named;
import di.annotations.Provides;
import di.annotations.Singleton;

import profiling.FrameProfiler;
import profiling.PerformanceOverlay;
import profiling.ProfiledFrame;
import profiling.ProfilingInput;

import rendering.ArgbSerializer;
import rendering.AwtViewer;
import rendering.ChainRasterSerializer;
import rendering.Checkerboard;
import rendering.Color;
import rendering.CompositeRenderer;
import rendering.Eraser;
import rendering.FileSystemRasterRepository;
import rendering.Font;
import rendering.FsFontLoader;
import rendering.PixelRaster;
import rendering.Raster;
import rendering.RasterFilter;
import rendering.RasterRepository;
import rendering.Renderer;
import rendering.RgbSerializer;

import scenes.Scene;
import scenes.SceneAwareProxyBuilder;
import scenes.SceneSwitcher;
import scenes.textureeditor.TextureEditor;
import scenes.viewport.Viewport;

import java.awt.event.KeyListener;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelListener;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class MainModule implements Module {
    @Override
    public void configure(GraphBuilder b) {
        // Window/UI dimensions are independent of the viewport's tracing resolution.
        b.bind(int.class).named("display_width").toInstance(1440);
        b.bind(int.class).named("display_height").toInstance(900);
        b.bind(int.class).named("texture_width").toInstance(16);
        b.bind(int.class).named("texture_height").toInstance(16);
        b.bind(int.class).named("frame_rate").toInstance(144);

        // Required singletons. TextureEditor / Viewport are depended on by multiple providers
        // (each scene-aware proxy registers them, plus the Renderer pipeline lists them). Display
        // Raster is shared by every Renderer in the pipeline.
        b.bind(TextureEditor.class).singleton();
        b.bind(Viewport.class).singleton();

        // Mutable scene registry. Bound as an empty map at config time and populated post-injection
        // by registerScenes(). Breaks the cycle Scene → Console → Command → CmdScene → Map → Scene.
        b.bind(new GenericType<Map<String, Scene>>() {}).toInstance(new HashMap<String, Scene>());

        // Ordered list for the F12 cycle.
        var scenesList = b.bindList().of(Scene.class);
        scenesList.add(TextureEditor.class);
        scenesList.add(Viewport.class);

        var pipeline = b.bindList().of(Renderer.class);
        pipeline.add(Eraser.class);
        pipeline.add(Checkerboard.class);
        pipeline.add(Renderer.class).named("scene_renderer");
        pipeline.add(PerformanceOverlay.class);
        pipeline.add(AwtViewer.class);
    }

    public void registerScenes(Injector injector) {
        Map<String, Scene> map = injector.get(new GenericType<Map<String, Scene>>() {});
        AtomicReference<Scene> ref = injector.get(new GenericType<AtomicReference<Scene>>() {});
        var te = injector.get(TextureEditor.class);
        var vp = injector.get(Viewport.class);
        map.put("editor", te);
        map.put("viewport", vp);
        ref.set(te); // startup scene
    }

    @Provides
    @Singleton
    FrameProfiler profiler() {
        return new FrameProfiler();
    }

    @Provides
    @Singleton
    Renderer renderer(
            List<Renderer> pipeline, FrameProfiler profiler, AtomicReference<Scene> sceneRef) {
        var timed = new java.util.ArrayList<Renderer>();
        for (var delegate : pipeline) {
            var stage =
                    switch (delegate) {
                        case Eraser _, Checkerboard _ -> FrameProfiler.Stage.BACKGROUND;
                        case PerformanceOverlay _ -> FrameProfiler.Stage.OVERLAY;
                        case AwtViewer _ -> FrameProfiler.Stage.PRESENT;
                        default -> FrameProfiler.Stage.SCENE;
                    };
            Renderer active =
                    stage == FrameProfiler.Stage.BACKGROUND
                            ? () -> {
                                if (!(sceneRef.get() instanceof Viewport)) delegate.render();
                            }
                            : delegate;
            timed.add(ProfiledFrame.stage(profiler, stage, active));
        }
        return new ProfiledFrame(profiler, new CompositeRenderer(timed));
    }

    @Provides
    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }

    @Provides
    @Singleton
    Raster display(@Named("display_width") int w, @Named("display_height") int h) {
        return new PixelRaster(w, h, Color.NamedColor.BLACK);
    }

    @Provides
    @Singleton
    Checkerboard checkerboard(Raster raster) {
        return new Checkerboard(0.8f, 0.9f, raster);
    }

    @Provides
    @Singleton
    RasterRepository repo(Clock clock) {
        return new FileSystemRasterRepository(
                clock, ChainRasterSerializer.of(ArgbSerializer.INSTANCE, RgbSerializer.INSTANCE));
    }

    @Provides
    @Singleton
    Font font(RasterRepository repo, Clock clock) {
        return FsFontLoader.builder()
                .repository(repo)
                .clock(clock)
                .fontPath("assets/fonts/test")
                .fontDimensions(16)
                .filter(RasterFilter.antiAlias())
                .build()
                .load();
    }

    @Provides
    @Singleton
    AtomicReference<Scene> sceneRef() {
        // Initial scene set in registerScenes() — keeping this provider parameter-free breaks the
        // cycle TextureEditor → AtomicReference<Scene> → TextureEditor.
        return new AtomicReference<>();
    }

    @Provides
    @Named("input_listener")
    @Singleton
    Object inputListener(
            TextureEditor te,
            Viewport vp,
            AtomicReference<Scene> sceneRef,
            List<Scene> scenes,
            FrameProfiler profiler) {
        var proxy =
                SceneAwareProxyBuilder.create()
                        .withInterfaces(
                                KeyListener.class,
                                MouseListener.class,
                                MouseWheelListener.class,
                                MouseMotionListener.class)
                        .withTargetForScene(TextureEditor.class, te)
                        .withTargetForScene(Viewport.class, vp)
                        .withSceneSupplier(sceneRef::get)
                        .build();
        return new ProfilingInput(profiler, new SceneSwitcher(scenes, sceneRef, proxy));
    }

    @Provides
    @Named("scene_renderer")
    @Singleton
    Renderer sceneRenderer(TextureEditor te, Viewport vp, AtomicReference<Scene> sceneRef) {
        return (Renderer)
                SceneAwareProxyBuilder.create()
                        .withInterface(Renderer.class)
                        .withTargetForScene(TextureEditor.class, te)
                        .withTargetForScene(Viewport.class, vp)
                        .withSceneSupplier(sceneRef::get)
                        .build();
    }

    @Provides
    Runnable renderLoop(Renderer renderer) {
        return renderer::render;
    }
}
