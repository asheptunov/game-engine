import di.Injector;
import harness.SuiteRunner;
import harness.Test;
import scenes.viewport.Viewport;
import scenes.viewport.ScenePresets;
import java.awt.Canvas;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.FocusEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import scenes.Scene;
import scenes.SceneSwitcher;
import scenes.CmdScene;
import profiling.FrameProfiler;
import profiling.ProfilingInput;
import static harness.Assertions.*;

/** Requires a non-headless toolkit for keyboard lock state, but never opens a window. */
public class ViewportKeyBindingsTest {
    private static Viewport viewport() {
        var module = new MainModule(); var injector = Injector.create(module); module.registerScenes(injector);
        var viewport = injector.get(Viewport.class); viewport.state().resolution(64);
        return viewport;
    }
    private static KeyEvent key(int id, int code, int modifiers, int location) {
        return new KeyEvent(new Canvas(), id, 0, modifiers, code, KeyEvent.CHAR_UNDEFINED, location);
    }
    @Test void controlAndSpaceCombineWithWasdAndOldVerticalKeysAreUnbound() {
        var viewport = viewport(); var state = viewport.state(); var eye = state.eye();
        viewport.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_CONTROL, KeyEvent.CTRL_DOWN_MASK, KeyEvent.KEY_LOCATION_LEFT));
        viewport.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_W, KeyEvent.CTRL_DOWN_MASK, KeyEvent.KEY_LOCATION_STANDARD));
        viewport.render(); assertTrue(state.eye().y() < eye.y() && state.eye().z() > eye.z());
        viewport.keyReleased(key(KeyEvent.KEY_RELEASED, KeyEvent.VK_CONTROL, 0, KeyEvent.KEY_LOCATION_LEFT));
        eye = state.eye(); viewport.render(); assertEquals(eye.y(), state.eye().y()); assertTrue(state.eye().z() > eye.z());
        viewport.suspendInput(); eye = state.eye();
        viewport.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0, KeyEvent.KEY_LOCATION_STANDARD));
        viewport.render(); assertTrue(state.eye().y() > eye.y()); viewport.suspendInput();
        eye = state.eye();
        viewport.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_Q, 0, KeyEvent.KEY_LOCATION_STANDARD));
        viewport.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_E, 0, KeyEvent.KEY_LOCATION_STANDARD));
        viewport.render(); assertEquals(eye, state.eye());
    }
    private static MouseEvent mouse(int id, int modifiers, int x, int y, int button) {
        return new MouseEvent(new Canvas(), id, 0, modifiers, x, y, 1, false, button);
    }
    @Test void mouseMovementNeedsNoButtonAndConsoleBlocksLookAndHeldMovement() {
        var viewport = viewport(); var state = viewport.state(); viewport.render(); viewport.render();
        var sensor = state.cameraSensor();
        viewport.mouseMoved(mouse(MouseEvent.MOUSE_MOVED, 0, 100, 100, 0));
        viewport.render(); assertEquals(sensor, state.cameraSensor());
        viewport.mouseMoved(mouse(MouseEvent.MOUSE_MOVED, MouseEvent.CTRL_DOWN_MASK, 200, 150, 0));
        viewport.render(); assertNotEquals(sensor, state.cameraSensor()); assertEquals(1L, state.accumulatedSamples());
        sensor = state.cameraSensor();
        viewport.mouseExited(mouse(MouseEvent.MOUSE_EXITED, 0, 200, 150, 0));
        viewport.mouseEntered(mouse(MouseEvent.MOUSE_ENTERED, 0, 500, 400, 0));
        viewport.render(); assertEquals(sensor, state.cameraSensor());
        viewport.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_W, 0, KeyEvent.KEY_LOCATION_STANDARD));
        viewport.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_SLASH, 0, KeyEvent.KEY_LOCATION_STANDARD));
        var eye = state.eye(); sensor = state.cameraSensor();
        viewport.mouseMoved(mouse(MouseEvent.MOUSE_MOVED, 0, 300, 300, 0));
        viewport.render(); assertEquals(eye, state.eye()); assertEquals(sensor, state.cameraSensor());
        viewport.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE, 0, KeyEvent.KEY_LOCATION_STANDARD));
        viewport.mouseMoved(mouse(MouseEvent.MOUSE_MOVED, 0, 600, 600, 0));
        viewport.render(); assertEquals(eye, state.eye()); assertEquals(sensor, state.cameraSensor());
    }
    @Test void focusLossAndBothSceneSwitchPathsClearHeldInput() {
        var viewport = viewport(); var other = new Scene() {}; var active = new AtomicReference<Scene>(viewport);
        var switcher = new SceneSwitcher(List.of(viewport, other), active, viewport);
        var listener = new ProfilingInput(new FrameProfiler(), switcher);
        listener.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_W, 0, KeyEvent.KEY_LOCATION_STANDARD));
        listener.focusLost(new FocusEvent(new Canvas(), FocusEvent.FOCUS_LOST));
        var eye = viewport.state().eye(); viewport.render(); assertEquals(eye, viewport.state().eye());
        listener.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_W, 0, KeyEvent.KEY_LOCATION_STANDARD));
        listener.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_F12, 0, KeyEvent.KEY_LOCATION_STANDARD));
        listener.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_F12, 0, KeyEvent.KEY_LOCATION_STANDARD));
        assertSame(viewport, active.get()); viewport.render(); assertEquals(eye, viewport.state().eye());
        listener.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_W, 0, KeyEvent.KEY_LOCATION_STANDARD));
        var command = new CmdScene(Map.of("viewport", viewport, "other", other), active);
        command.run("scene", "other"); command.run("scene", "viewport");
        viewport.render(); assertEquals(eye, viewport.state().eye());
    }
    @Test void consolePreservesAccumulationAndMovementRestartsIt() {
        var module=new MainModule();var injector=Injector.create(module);module.registerScenes(injector);
        var viewport=injector.get(Viewport.class);var state=viewport.state();
        state.resolution(64);ScenePresets.load(state,"bounce-room");
        var source=new Canvas();
        viewport.render();assertEquals(1L,state.accumulatedSamples());
        viewport.keyPressed(new KeyEvent(source,KeyEvent.KEY_PRESSED,0,0,KeyEvent.VK_SLASH,'/'));
        viewport.render();assertEquals(2L,state.accumulatedSamples());
        viewport.keyPressed(new KeyEvent(source,KeyEvent.KEY_PRESSED,0,0,KeyEvent.VK_ESCAPE,(char)27));
        viewport.render();assertEquals(3L,state.accumulatedSamples());
        var eye=state.eye();
        viewport.keyPressed(new KeyEvent(source,KeyEvent.KEY_PRESSED,0,0,KeyEvent.VK_W,'w'));
        viewport.render();assertNotEquals(eye,state.eye());assertEquals(1L,state.accumulatedSamples());
    }
    public static void main(String[] args) { SuiteRunner.runThis(); }
}
