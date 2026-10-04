import di.Injector;
import harness.SuiteRunner;
import harness.Test;
import scenes.viewport.Viewport;
import scenes.viewport.ScenePresets;
import java.awt.Canvas;
import java.awt.event.KeyEvent;
import static harness.Assertions.*;

/** Requires a non-headless toolkit for keyboard lock state, but never opens a window. */
public class ViewportKeyBindingsTest {
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
        assertNotEquals(eye,state.eye());viewport.render();assertEquals(1L,state.accumulatedSamples());
    }
    public static void main(String[] args) { SuiteRunner.runThis(); }
}
