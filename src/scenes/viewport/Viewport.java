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

public class Viewport implements AutoCloseable,
        Scene, Renderer,
        KeyListener, MouseListener, MouseMotionListener, MouseWheelListener {
    private static final Logger LOG = LogManager.instance().getThis();

    // Sensor pixel grid. Each sensor pixel is drawn as a block on the display.
    private static final int   SENSOR_LONG_EDGE = 1600;
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
    private final AsyncViewportTrace asyncTrace;
    private final DisplayConverter displayConverter;
    private ViewportState.RenderKey convertedKey;
    private long convertedSamples=-1;
    private float convertedExposure;
    private final ActionRegistry<Runnable>             actions;
    private final InputBindings                        bindings;
    private final ActionRegistry<Consumer<MouseEvent>> mouseActions;
    private final MouseBindings                        mouseBindings;

    private volatile boolean consoleOpen = false;
    private Boolean blockingMode;
    private synchronized void mode(boolean blocking) {
        if(blockingMode != null && blockingMode != blocking)
            throw new IllegalStateException("Do not mix blocking and asynchronous rendering on one viewport");
        blockingMode=blocking;
    }
    @Override public void close() { asyncTrace.close(); }

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
        this.displayConverter = new DisplayConverter(width,height);

        this.state = defaultScene(width, height);
        this.state.presentationAspect((float) width / height);
        this.tracer = new DirectRgbTracer(state);
        this.asyncTrace = new AsyncViewportTrace(state, tracer);
        var rootCmd = new TrimmingCommand(DelegatingCommand.builder()
                .withCommand("view", new ViewportCommand(state, width, height))
                .withCommand("scene", new CmdScene(scenes, sceneRef))
                .withCommand("exit", new CmdExit())
                .build());

        this.console = Console.withAwtText(display,
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

    private static ViewportState defaultScene(int width, int height) {
        // Sensor (image plane): 1×1 unit square at z=0. Eye is at z=-1 (pinhole 1 unit behind sensor).
        var sensor = new Rect(
                new Vec3(-0.5f, -0.5f, 0),
                new Vec3(1, 0, 0),
                new Vec3(0, 1, 0));
        double scale = (double) SENSOR_LONG_EDGE / Math.max(width, height);
        var st = new ViewportState(sensor, (int) Math.round(width * scale), (int) Math.round(height * scale));
        st.eye(new Vec3(0, 0, -1));
        ScenePresets.load(st, "playground");
        return st;
    }

    public ViewportState state() {
        return state;
    }
    /** Diagnostics for blocking measurement only; live tracing owns this tracer in the background. */
    public DirectRgbTracer tracer() { return tracer; }

    @Override
    public void render() {
        mode(false);
        AsyncViewportTrace.Image image;
        float exposure;
        String label, status;
        synchronized(state) {
            if (!consoleOpen) cameraControls.update(state,System.nanoTime());
            asyncTrace.request(); image=asyncTrace.acquireImage();
            exposure=(float)Math.pow(2,state.exposure()); label=label(); status=asyncTrace.status();
        }
        try {
            if (image != null) {
                profiler.traceStats(image.stats());
                profiler.rayStats(new FrameProfiler.Rays(image.key().width(),image.key().height(),
                        image.primaryRays(),image.primaryHits(),image.shadowRays(),image.shadowsOccluded(),
                        image.litPixels(),image.traceNanos()));
                paintImage(image.rgb(),exposure,image.key(),image.samples());
            } else {
                java.util.Arrays.fill(display.alpha(),(byte)255);
                java.util.Arrays.fill(display.red(),(byte)0);
                java.util.Arrays.fill(display.green(),(byte)0);
                java.util.Arrays.fill(display.blue(),(byte)0);
            }
            synchronized(state) {
                asyncTrace.presented(image); profiler.renderProgress(asyncTrace.progress(image));
                profiler.viewportImage(image==null || image.samples()==0?-1:image.requestedNanos(),
                        image==null?-1:image.requestedNanos(),state.sensorPixelsW(),state.sensorPixelsH(),
                        state.preset(),state.samplingStatus());
            }
            renderUi(label,status);
        } finally { synchronized(state) { asyncTrace.release(image); } }
    }
    /** Deterministic full-pipeline measurement; never mix with asynchronous render on this instance. */
    public void renderBlocking() {
        mode(true);
        ViewportState snapshot;
        float exposure;
        synchronized(state) {
            if (!consoleOpen) cameraControls.update(state,System.nanoTime());
            snapshot=state.renderSnapshot(); exposure=(float)Math.pow(2,state.exposure());
        }
        var traced = profiler.measure(FrameProfiler.Stage.TRACE, () -> tracer.trace(snapshot, () -> false));
        synchronized(state) { if(snapshot.renderKey().equals(state.renderKey())) state.accumulatedSamples(snapshot.accumulatedSamples()); }
        profiler.traceStats(tracer.profile);
        profiler.rayStats(new FrameProfiler.Rays(snapshot.sensorPixelsW(), snapshot.sensorPixelsH(),
                tracer.primaryRays, tracer.primaryHits, tracer.shadowRays,
                tracer.shadowsOccluded, tracer.litPixels, tracer.traceNanos));
        paintImage(traced,exposure,snapshot.renderKey(),snapshot.accumulatedSamples());
        String label;
        synchronized(state) { label=label(); }
        renderUi(label,null);
    }
    private String label() {
        return "WASD Space/Ctrl | Mouse: look | /: view help | " + state.preset() + " | N=" + state.pathDepth()
                + " | " + state.accumulatedSamples() + " spp " + state.samplingStatus();
    }
    private void paintImage(float[][][] traced,float exposure,ViewportState.RenderKey key,long samples) {
        if(!key.equals(convertedKey) || samples!=convertedSamples || exposure!=convertedExposure) {
            // Fused filtering/mapping is charged to RESAMPLE; PAINT restores the encoded cache.
            profiler.measure(FrameProfiler.Stage.RESAMPLE, () -> displayConverter.convert(traced,exposure));
            convertedKey=key; convertedSamples=samples; convertedExposure=exposure;
        }
        profiler.measure(FrameProfiler.Stage.PAINT, () -> displayConverter.paint(display));
    }
    private void renderUi(String label,String status) {
        if (consoleOpen) {
            console.render();
        } else {
            if(status != null) printer.print(status,12,height-42,Printer.Size.of(12),Printer.Spacing.of(-3));
            printer.print(label,
                    12, height - 24, Printer.Size.of(12), Printer.Spacing.of(-3));
        }
    }

    @Override
    public void keyPressed(KeyEvent e) {
        synchronized (state) { handleKeyPressed(e); asyncTrace.invalidate(); }
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
            asyncTrace.invalidate();
            LOG.info("Camera reset");
        }
    }

    @Override public void keyTyped(KeyEvent e) {}
    @Override public void keyReleased(KeyEvent e) {
        synchronized (state) { cameraControls.release(e.getKeyCode(), e.getKeyLocation()); }
    }
    @Override public void suspendInput() {
        synchronized (state) { cameraControls.clear(); asyncTrace.suspend(); }
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
            if (!consoleOpen) cameraControls.update(state,System.nanoTime());
            asyncTrace.invalidate();
        }
    }
}
