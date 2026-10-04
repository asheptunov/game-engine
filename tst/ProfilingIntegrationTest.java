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
    public static void main(String[] args) { SuiteRunner.runThis(); }
}
