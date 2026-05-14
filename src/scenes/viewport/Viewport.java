package scenes.viewport;

import di.annotations.Inject;
import di.annotations.Named;
import logging.LogManager;
import logging.Logger;
import math.Vec3;
import rendering.BlendMode;
import rendering.Color;
import rendering.Color.RgbInt24Color;
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
import ui.InputBindings;
import ui.KeyAction;
import ui.KeyChord;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class Viewport implements
        Scene, Renderer,
        KeyListener, MouseListener, MouseMotionListener, MouseWheelListener {
    private static final Logger LOG = LogManager.instance().getThis();

    // Sensor pixel grid. Each sensor pixel is drawn as a block on the display.
    private static final int   SENSOR_W = 100;
    private static final int   SENSOR_H = 100;
    private static final float MOVE_STEP = 0.25f;

    private final Raster             display;
    private final Painter            painter;
    private final int                width;
    private final int                height;
    private final Console            console;
    private final ViewportState      state;
    private final BackwardRayTracer  tracer;
    private final ActionRegistry     actions;
    private final InputBindings      bindings;

    private boolean consoleOpen = false;

    @Inject
    public Viewport(Raster display,
                    Font font,
                    @Named("display_width") int width,
                    @Named("display_height") int height,
                    Map<String, Scene> scenes,
                    AtomicReference<Scene> sceneRef) {
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

        this.actions = new ActionRegistry()
                .register("console.open", () -> consoleOpen = true)
                .register("camera.move.forward", () -> translateCamera(new Vec3(0, 0, MOVE_STEP)))
                .register("camera.move.back", () -> translateCamera(new Vec3(0, 0, -MOVE_STEP)))
                .register("camera.strafe.left", () -> translateCamera(new Vec3(-MOVE_STEP, 0, 0)))
                .register("camera.strafe.right", () -> translateCamera(new Vec3(MOVE_STEP, 0, 0)))
                .register("camera.move.up", () -> translateCamera(new Vec3(0, MOVE_STEP, 0)))
                .register("camera.move.down", () -> translateCamera(new Vec3(0, -MOVE_STEP, 0)))
                .register("camera.reset", this::resetCamera);

        this.bindings = new InputBindings(actions)
                .bind(KeyChord.of(KeyAction.Key.FORWARD_SLASH), "console.open")
                .bind(KeyChord.of(KeyAction.Key.LOWER_W), "camera.move.forward")
                .bind(KeyChord.of(KeyAction.Key.LOWER_S), "camera.move.back")
                .bind(KeyChord.of(KeyAction.Key.LOWER_A), "camera.strafe.left")
                .bind(KeyChord.of(KeyAction.Key.LOWER_D), "camera.strafe.right")
                .bind(KeyChord.of(KeyAction.Key.LOWER_E), "camera.move.up")
                .bind(KeyChord.of(KeyAction.Key.LOWER_Q), "camera.move.down")
                .bind(KeyChord.of(KeyAction.Key.LOWER_R), "camera.reset")
                .validate("Viewport");
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
        painter.drawImg(0, 0, width, height, Color.NamedColor.BLACK, BlendMode.OVER_PRE);

        var buf = tracer.trace();
        float max = 0;
        for (var row : buf) {
            for (float v : row) {
                if (v > max) max = v;
            }
        }
        if (max > 0) {
            int blockW = width / SENSOR_W;
            int blockH = height / SENSOR_H;
            for (int sy = 0; sy < SENSOR_H; sy++) {
                for (int sx = 0; sx < SENSOR_W; sx++) {
                    float intensity = buf[sy][sx] / max;
                    if (intensity <= 0) continue;
                    int gray = (int) (intensity * 255);
                    // Flip Y: sensor v=0 is bottom of image, display y=0 is top.
                    int dy = (SENSOR_H - 1 - sy) * blockH;
                    int dx = sx * blockW;
                    var c = RgbInt24Color.of((gray << 16) | (gray << 8) | gray);
                    painter.drawImg(dx, dy, blockW, blockH, c, BlendMode.OVER_PRE);
                }
            }
        }

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

    @Override public void mouseClicked(MouseEvent e) { LOG.trace("Handling %s", e); }
    @Override public void mousePressed(MouseEvent e) { LOG.trace("Handling %s", e); }
    @Override public void mouseReleased(MouseEvent e) { LOG.trace("Handling %s", e); }
    @Override public void mouseEntered(MouseEvent e) { LOG.trace("Handling %s", e); }
    @Override public void mouseExited(MouseEvent e) { LOG.trace("Handling %s", e); }

    @Override public void mouseDragged(MouseEvent e) { LOG.trace("Handling %s", e); }
    @Override public void mouseMoved(MouseEvent e) { LOG.trace("Handling %s", e); }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        LOG.trace("Handling %s", e);
        if (consoleOpen) {
            console.accept(e);
        }
    }
}
