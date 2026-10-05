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
import scenes.viewport.objects.Rect;
import ui.ActionRegistry;
import ui.BindingsLoader;
import ui.InputBindings;
import ui.KeyAction;
import ui.KeyChord;
import ui.MouseBindings;
import ui.MouseChord;
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
    private final CameraControls cameraControls = new CameraControls();

    private final Raster             display;
    private final Painter            painter;
    private final Printer            printer;
    private final int                width;
    private final int                height;
    private final Console            console;
    private final ViewportState      state;
    private final DirectRgbTracer  tracer;
    private final FrameProfiler profiler;
    private final ActionRegistry<Runnable>             actions;
    private final InputBindings                        bindings;
    private final ActionRegistry<Consumer<MouseEvent>> mouseActions;
    private final MouseBindings                        mouseBindings;

    private volatile boolean consoleOpen = false;

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
        this.printer = new RasterPrinter(display, font);
        this.width = width;
        this.height = height;

        int fontSize = 16;
        int charSpacing = -4;
        int lineSpacing = 0;

        this.state = defaultScene();
        this.tracer = new DirectRgbTracer(state);
        var rootCmd = new TrimmingCommand(DelegatingCommand.builder()
                .withCommand("view", new ViewportCommand(state))
                .withCommand("scene", new CmdScene(scenes, sceneRef))
                .withCommand("exit", new CmdExit())
                .build());

        this.console = new Console(painter, printer, width, height,
                fontSize, charSpacing, lineSpacing,
                () -> consoleOpen = false,
                100,
                rootCmd);


        this.actions = new ActionRegistry<Runnable>()
                .register("console.open", () -> { suspendInput(); consoleOpen = true; })
                // Movement actions are held by CameraControls instead of dispatched on repeat.
                .register("camera.move.forward", () -> {})
                .register("camera.move.back", () -> {})
                .register("camera.strafe.left", () -> {})
                .register("camera.strafe.right", () -> {})
                .register("camera.move.up", () -> {})
                .register("camera.move.down", () -> {})
                .register("camera.reset", this::resetCamera);

        this.bindings = BindingsLoader
                .loadInto(Path.of("assets/bindings/viewport.properties"), new InputBindings(actions))
                .validate("Viewport");

        this.mouseActions = new ActionRegistry<Consumer<MouseEvent>>()
                .register("camera.look.move", e -> cameraControls.look(e.getX(), e.getY()))
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
        ScenePresets.load(st, "playground");
        return st;
    }

    public ViewportState state() {
        return state;
    }

    @Override
    public void render() {
        synchronized (state) { renderFrame(); }
    }
    private void renderFrame() {
        if (!consoleOpen) cameraControls.update(state, System.nanoTime());
        var traced = profiler.measure(FrameProfiler.Stage.TRACE, tracer::trace);
        profiler.traceStats(tracer.profile);
        profiler.rayStats(new FrameProfiler.Rays(state.sensorPixelsW(), state.sensorPixelsH(),
                tracer.primaryRays, tracer.primaryHits, tracer.shadowRays,
                tracer.shadowsOccluded, tracer.litPixels, tracer.traceNanos));
        var resampled = profiler.measure(FrameProfiler.Stage.RESAMPLE,
                () -> new float[][][]{Resampler.resample(traced[0], height, width).buf(),
                        Resampler.resample(traced[1], height, width).buf(), Resampler.resample(traced[2], height, width).buf()});
        final float exposure = (float) Math.pow(2, state.exposure());
        // displayBuf row 0 follows the sensor's row-0-is-bottom convention; flip when reading.
        profiler.measure(FrameProfiler.Stage.PAINT, () -> {
            var a = display.alpha();
            var r = display.red();
            var g = display.green();
            var b = display.blue();
            for (int y = 0; y < height; y++) {
                int sy = height - 1 - y;
                for (int x = 0; x < width; x++) {
                    int i = y * display.width() + x;
                    a[i] = (byte) 255;
                    r[i] = DisplayMapping.encode(resampled[0][sy][x], exposure);
                    g[i] = DisplayMapping.encode(resampled[1][sy][x], exposure);
                    b[i] = DisplayMapping.encode(resampled[2][sy][x], exposure);
                }
            }
        });

        if (consoleOpen) {
            console.render();
        } else {
            printer.print("WASD Space/Ctrl | Mouse: look | /: view help | " + state.preset() + " | N=" + state.pathDepth()
                            + " | " + state.accumulatedSamples() + " spp " + state.samplingStatus(),
                    12, height - 24, Printer.Size.of(12), Printer.Spacing.of(-3));
        }
    }

    @Override
    public void keyPressed(KeyEvent e) {
        synchronized (state) { handleKeyPressed(e); }
    }
    private void handleKeyPressed(KeyEvent e) {
        LOG.trace("Handling %s", e);
        var action = KeyAction.fromAwt(e);
        if (consoleOpen) {
            console.accept(action);
            return;
        }
        // Movement keys remain usable while Ctrl/Shift is held; console shortcuts stay strict.
        var id = bindings.lookup(KeyChord.of(action.raw())).orElse("");
        if (cameraControls.press(e.getKeyCode(), e.getKeyLocation(), id, System.nanoTime())) return;
        bindings.handle(action);
    }

    private void resetCamera() {
        synchronized (state) {
            cameraControls.clear();
            ScenePresets.resetCamera(state);
            LOG.info("Camera reset");
        }
    }

    @Override public void keyTyped(KeyEvent e) {}
    @Override public void keyReleased(KeyEvent e) {
        synchronized (state) { cameraControls.release(e.getKeyCode(), e.getKeyLocation()); }
    }
    @Override public void suspendInput() {
        synchronized (state) { cameraControls.clear(); }
    }

    @Override public void mouseClicked(MouseEvent e)         { LOG.trace("Handling %s", e); }
    @Override public void mouseEntered(MouseEvent e) {
        synchronized (state) {
            if (!consoleOpen) cameraControls.startLook(e.getX(), e.getY());
        }
    }
    @Override public void mouseExited(MouseEvent e)          { synchronized (state) { cameraControls.stopLook(); } }
    @Override public void mouseMoved(MouseEvent e)           { route(MouseGesture.MOVE, e); }
    @Override public void mousePressed(MouseEvent e) {
        route(MouseGesture.PRESS, e);
    }
    @Override public void mouseReleased(MouseEvent e) {
        route(MouseGesture.RELEASE, e);
    }
    @Override public void mouseDragged(MouseEvent e) {
        route(MouseGesture.DRAG, e);
    }
    @Override public void mouseWheelMoved(MouseWheelEvent e) { route(MouseGesture.WHEEL, e); }

    private void route(MouseGesture gesture, MouseEvent e) {
        synchronized (state) {
            LOG.trace("Handling %s", e);
            var chord = MouseChord.from(gesture, e, consoleOpen ? "console" : "");
            var plain = MouseChord.of(chord.button(), chord.gesture(), chord.mode());
            var id = mouseBindings.lookup(plain).orElse("");
            // Looking continues while movement modifiers (notably crouch/Ctrl) are held.
            if (id.startsWith("camera.look.")) mouseActions.get(id).orElseThrow().accept(e);
            else mouseBindings.handle(gesture, e, consoleOpen ? "console" : "");
        }
    }
}
