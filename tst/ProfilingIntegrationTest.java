import di.Injector;
import harness.SuiteRunner;
import harness.Test;
import profiling.FrameProfiler;
import profiling.PerformanceOverlay;
import scenes.textureeditor.TextureEditor;
import scenes.viewport.Viewport;

import static harness.Assertions.*;

/** Exercises the real DI graph and scene timing hooks without opening an AWT window. */
public class ProfilingIntegrationTest {
    @Test void sharedProfilerAndBothScenes() {
        var module = new MainModule();
        var injector = Injector.create(module);
        module.registerScenes(injector);
        var profiler = injector.get(FrameProfiler.class);
        assertSame(profiler, injector.get(FrameProfiler.class));
        var overlay = injector.get(PerformanceOverlay.class);
        assertFalse(profiler.visible());
        profiler.beginFrame();
        profiler.measure(FrameProfiler.Stage.SCENE, injector.get(Viewport.class)::render);
        profiler.endFrame(); profiler.beginFrame();
        var viewport = profiler.snapshot().frames().getLast();
        assertEquals(2_560_000, viewport.rays().primary());
        assertTrue(viewport.stage(FrameProfiler.Stage.TRACE) > 0);
        assertTrue(viewport.stage(FrameProfiler.Stage.RESAMPLE) > 0);
        assertTrue(viewport.stage(FrameProfiler.Stage.PAINT) > 0);
        profiler.toggle();
        profiler.measure(FrameProfiler.Stage.SCENE, injector.get(TextureEditor.class)::render);
        profiler.measure(FrameProfiler.Stage.OVERLAY, overlay::render);
        profiler.endFrame(); profiler.beginFrame();
        var editor = profiler.snapshot().frames().getLast();
        assertNull(editor.rays());
        assertEquals(0L, editor.stage(FrameProfiler.Stage.TRACE));
        assertTrue(editor.stage(FrameProfiler.Stage.OVERLAY) > 0);
    }
    @Test void progressiveViewportKeepsSamplesAcrossOverlayExposureAndSceneSwitch() {
        var module = new MainModule();var injector = Injector.create(module);module.registerScenes(injector);
        var viewport = injector.get(Viewport.class);var state = viewport.state();
        state.resolution(64);scenes.viewport.ScenePresets.load(state,"bounce-room");
        var profiler = injector.get(FrameProfiler.class);
        viewport.render();assertEquals(1L,state.accumulatedSamples());
        profiler.toggle();state.exposure(1);
        viewport.render();assertEquals(2L,state.accumulatedSamples());
        profiler.toggle();viewport.render();assertEquals(3L,state.accumulatedSamples());
        state.sampleTarget(3);viewport.render();assertEquals("complete",state.samplingStatus());
        // Re-entering after rendering the editor also preserves the stationary viewport's mean.
        injector.get(TextureEditor.class).render();viewport.render();assertEquals(3L,state.accumulatedSamples());
        profiler.close();
    }
    public static void main(String[] args) { SuiteRunner.runThis(); }
}
