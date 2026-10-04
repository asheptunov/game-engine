package scenes.viewport;

import di.annotations.Inject;
import di.annotations.Named;
import logging.LogManager;
import logging.Logger;
import math.Vec3;
import rendering.Font;
import rendering.Painter;
import rendering.Printer;
import rendering.Raster;
import rendering.RasterPainter;
import rendering.RasterPrinter;
import rendering.Renderer;
import scenes.CmdScene;
import scenes.Scene;
import scenes.viewport.lights.PointLight;
import scenes.viewport.objects.Rect;
import scenes.viewport.objects.Tri;
import ui.ActionRegistry;
import ui.BindingsLoader;
import ui.InputBindings;
import ui.KeyAction;
import ui.MouseBindings;
import ui.MouseGesture;
import ui.console.CmdExit;
import ui.console.Console;
import ui.console.DelegatingCommand;
import ui.console.TrimmingCommand;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import profiling.FrameProfiler;

public class Viewport implements
        Scene, Renderer,
        KeyListener, MouseListener, MouseMotionListener, MouseWheelListener {
    private static final Logger LOG = LogManager.instance().getThis();

    // Sensor pixel grid. Each sensor pixel is drawn as a block on the display.
    private static final int   SENSOR_W = 1600;
    private static final int   SENSOR_H = 1600;
    private static final float MOVE_STEP = 0.25f;

    private final Raster             display;
    private final Painter            painter;
    private final int                width;
    private final int                height;
    private final Console            console;
    private final ViewportState      state;
    private final BackwardRayTracer  tracer;
    private final FrameProfiler profiler;
    private final ActionRegistry<Runnable>             actions;
    private final InputBindings                        bindings;
    private final ActionRegistry<Consumer<MouseEvent>> mouseActions;
    private final MouseBindings                        mouseBindings;

    private boolean consoleOpen = false;

    @Inject
    public Viewport(Raster display,
                    Font font,
                    @Named("display_width") int width,
                    @Named("display_height") int height,
                    Map<String, Scene> scenes,
                    AtomicReference<Scene> sceneRef, FrameProfiler profiler) {
        this.profiler = profiler;
        this.display = display;
        this.painter = new RasterPainter(display);
        Printer printer = new RasterPrinter(display, font);
        this.width = width;
        this.height = height;

        int fontSize = 16;
        int charSpacing = -4;
        int lineSpacing = 0;

        var rootCmd = new TrimmingCommand(DelegatingCommand.builder()
                .withCommand("scene", new CmdScene(scenes, sceneRef))
                .withCommand("exit", new CmdExit())
                .build());

        this.console = new Console(painter, printer, width, height,
                fontSize, charSpacing, lineSpacing,
                () -> consoleOpen = false,
                100,
                rootCmd);

        this.state = defaultScene();
        this.tracer = new BackwardRayTracer(state);

        this.actions = new ActionRegistry<Runnable>()
                .register("console.open", () -> consoleOpen = true)
                .register("camera.move.forward", () -> translateCamera(new Vec3(0, 0, MOVE_STEP)))
                .register("camera.move.back", () -> translateCamera(new Vec3(0, 0, -MOVE_STEP)))
                .register("camera.strafe.left", () -> translateCamera(new Vec3(-MOVE_STEP, 0, 0)))
                .register("camera.strafe.right", () -> translateCamera(new Vec3(MOVE_STEP, 0, 0)))
                .register("camera.move.up", () -> translateCamera(new Vec3(0, MOVE_STEP, 0)))
                .register("camera.move.down", () -> translateCamera(new Vec3(0, -MOVE_STEP, 0)))
                .register("camera.reset", this::resetCamera);

        this.bindings = BindingsLoader
                .loadInto(Path.of("assets/bindings/viewport.properties"), new InputBindings(actions))
                .validate("Viewport");

        this.mouseActions = new ActionRegistry<Consumer<MouseEvent>>()
                .register("console.scroll", console::acceptScroll);

        this.mouseBindings = BindingsLoader
                .loadInto(Path.of("assets/bindings/viewport-mouse.properties"), new MouseBindings(mouseActions))
                .validate("Viewport mouse");
    }

    private static ViewportState defaultScene() {
        // Sensor (image plane): 1×1 unit square at z=0. Eye is at z=-1 (pinhole 1 unit behind sensor).
        var sensor = new Rect(
                new Vec3(-0.5f, -0.5f, 0),
                new Vec3(1, 0, 0),
                new Vec3(0, 1, 0));
        var st = new ViewportState(sensor, SENSOR_W, SENSOR_H);
        st.eye(new Vec3(0, 0, -1));
        st.addObject(new Tri(new Vec3(-2, -2, 10), new Vec3(2, -2, 10), new Vec3(0, 2, 10)));
        st.addLight(new PointLight(new Vec3(0, 0, 5)));
        return st;
    }

    public ViewportState state() {
        return state;
    }

    @Override
    public void render() {
        var traced = profiler.measure(FrameProfiler.Stage.TRACE, tracer::trace);
        profiler.traceStats(tracer.profile());
        profiler.rayStats(new FrameProfiler.Rays(state.sensorPixelsW(), state.sensorPixelsH(),
                tracer.primaryRays(), tracer.primaryHits(), tracer.shadowRays(),
                tracer.shadowsOccluded(), tracer.litPixels(), tracer.traceNanos()));
        var resampled = profiler.measure(FrameProfiler.Stage.RESAMPLE,
                () -> Resampler.resample(traced, height, width));
        final float[][] displayBuf = resampled.buf();
        // A fully opaque grayscale image replaces the display, including black miss pixels.
        final float invMax = resampled.max() > 0 ? 1f / resampled.max() : 0;
        // displayBuf row 0 follows the sensor's row-0-is-bottom convention; flip when reading.
        profiler.measure(FrameProfiler.Stage.PAINT, () -> {
            var a = display.alpha();
            var r = display.red();
            var g = display.green();
            var b = display.blue();
            for (int y = 0; y < height; y++) {
                var row = displayBuf[height - 1 - y];
                for (int x = 0; x < width; x++) {
                    int i = y * display.width() + x;
                    byte gray = (byte) (row[x] * invMax * 255);
                    a[i] = (byte) 255;
                    r[i] = g[i] = b[i] = gray;
                }
            }
        });

        if (consoleOpen) {
            console.render();
        }
    }

    @Override
    public void keyPressed(KeyEvent e) {
        LOG.trace("Handling %s", e);
        var action = KeyAction.fromAwt(e);
        if (consoleOpen) {
            console.accept(action);
            return;
        }
        bindings.handle(action);
    }

    private void translateCamera(Vec3 delta) {
        state.eye(state.eye().add(delta));
        var s = state.cameraSensor();
        state.cameraSensor(new Rect(s.origin().add(delta), s.edge1(), s.edge2()));
        LOG.info("Camera at eye=%s", state.eye());
    }

    private void resetCamera() {
        state.eye(new Vec3(0, 0, -1));
        state.cameraSensor(new Rect(
                new Vec3(-0.5f, -0.5f, 0),
                new Vec3(1, 0, 0),
                new Vec3(0, 1, 0)));
        LOG.info("Camera reset");
    }

    @Override public void keyTyped(KeyEvent e) {}
    @Override public void keyReleased(KeyEvent e) { LOG.trace("Handling %s", e); }

    @Override public void mouseClicked(MouseEvent e)         { LOG.trace("Handling %s", e); }
    @Override public void mouseEntered(MouseEvent e)         { LOG.trace("Handling %s", e); }
    @Override public void mouseExited(MouseEvent e)          { LOG.trace("Handling %s", e); }
    @Override public void mouseMoved(MouseEvent e)           { LOG.trace("Handling %s", e); }
    @Override public void mousePressed(MouseEvent e)         { route(MouseGesture.PRESS, e); }
    @Override public void mouseReleased(MouseEvent e)        { route(MouseGesture.RELEASE, e); }
    @Override public void mouseDragged(MouseEvent e)         { route(MouseGesture.DRAG, e); }
    @Override public void mouseWheelMoved(MouseWheelEvent e) { route(MouseGesture.WHEEL, e); }

    private void route(MouseGesture gesture, MouseEvent e) {
        LOG.trace("Handling %s", e);
        mouseBindings.handle(gesture, e, consoleOpen ? "console" : "");
    }
}
