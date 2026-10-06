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
    @Test void cameraMatchesWindowAcrossPresetsAndResolutionChanges() {
        var module = new MainModule(); var injector = Injector.create(module); module.registerScenes(injector);
        var state = injector.get(Viewport.class).state();
        var display = injector.get(rendering.Raster.class);
        float aspect = (float) display.width() / display.height();
        assertEquals(1600, state.sensorPixelsW());
        assertEquals(1000, state.sensorPixelsH());
        for (String preset : new String[]{"playground", "glass-inside", "volume-room"}) {
            scenes.viewport.ScenePresets.load(state, preset);
            var sensor = state.cameraSensor();
            assertTrue(Math.abs(sensor.edge1().length() / sensor.edge2().length() - aspect) < 1e-5);
            assertEquals(1f, sensor.edge2().length());
            var center = sensor.origin().add(sensor.edge1().scale(.5f)).add(sensor.edge2().scale(.5f));
            assertTrue(Math.abs(center.sub(state.eye()).length() - 1f) < 1e-5);
            state.resolution(64);
            assertEquals(sensor, state.cameraSensor());
            scenes.viewport.ScenePresets.resetCamera(state);
            assertEquals(sensor.edge1(), state.cameraSensor().edge1());
            assertEquals(sensor.edge2(), state.cameraSensor().edge2());
            assertEquals(64, state.sensorPixelsW());
            assertEquals(64, state.sensorPixelsH());
        }
    }
    @Test void sharedProfilerAndBothScenes() {
        var module = new MainModule();
        var injector = Injector.create(module);
        module.registerScenes(injector);
        var profiler = injector.get(FrameProfiler.class);
        assertSame(profiler, injector.get(FrameProfiler.class));
        var overlay = injector.get(PerformanceOverlay.class);
        assertFalse(profiler.visible());
        profiler.beginFrame();
        profiler.measure(FrameProfiler.Stage.SCENE, injector.get(Viewport.class)::renderBlocking);
        profiler.endFrame(); profiler.beginFrame();
        var viewport = profiler.snapshot().frames().getLast();
        assertEquals(1_600_000, viewport.rays().primary());
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
        viewport.renderBlocking();assertEquals(1L,state.accumulatedSamples());
        profiler.toggle();state.exposure(1);
        viewport.renderBlocking();assertEquals(2L,state.accumulatedSamples());
        profiler.toggle();viewport.renderBlocking();assertEquals(3L,state.accumulatedSamples());
        state.sampleTarget(3);viewport.renderBlocking();assertEquals("complete",state.samplingStatus());
        // Re-entering after rendering the editor also preserves the stationary viewport's mean.
        injector.get(TextureEditor.class).render();viewport.renderBlocking();assertEquals(3L,state.accumulatedSamples());
        profiler.close();
    }
    @Test void cachedViewportRestoresComposedRasterAndExposureInvalidatesOnlyConversion() {
        var module=new MainModule(); var injector=Injector.create(module); module.registerScenes(injector);
        try(var viewport=injector.get(Viewport.class); var profiler=injector.get(FrameProfiler.class)) {
            var state=viewport.state(); state.resolution(64); state.sampleTarget(1);
            var raster=injector.get(rendering.Raster.class);
            viewport.renderBlocking(); var saved=raster.clone();
            profiler.toggle(); injector.get(PerformanceOverlay.class).render();
            profiler.beginFrame(); viewport.renderBlocking(); profiler.endFrame(); profiler.beginFrame();
            assertEquals(0L,profiler.latestFrame().stage(FrameProfiler.Stage.RESAMPLE));
            assertEquals(saved,raster);
            injector.get(TextureEditor.class).render(); viewport.renderBlocking();
            assertEquals(saved,raster);
            state.exposure(2);
            profiler.beginFrame(); viewport.renderBlocking(); profiler.endFrame(); profiler.beginFrame();
            assertTrue(profiler.latestFrame().stage(FrameProfiler.Stage.RESAMPLE)>0);
            assertEquals(1L,state.accumulatedSamples()); assertFalse(saved.equals(raster));
        }
    }
    public static void main(String[] args) { SuiteRunner.runThis(); }
}
