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
    @Test void temporalToggleChangesPresentationImmediatelyAndReportsSeparateHistory() throws Exception {
        var module=new MainModule();var injector=Injector.create(module);module.registerScenes(injector);
        try(var viewport=injector.get(Viewport.class);var profiler=injector.get(FrameProfiler.class)) {
            var state=viewport.state();state.resolution(160,100);scenes.viewport.ScenePresets.load(state,"bounce-room");
            state.sampleTarget(1);state.temporal(true);
            var raster=injector.get(rendering.Raster.class);profiler.toggle();
            long deadline=System.nanoTime()+8_000_000_000L;
            while(state.accumulatedSamples()<1 && System.nanoTime()<deadline) {
                profiler.beginFrame();viewport.render();profiler.endFrame();Thread.sleep(2);
            }
            assertEquals(1L,state.accumulatedSamples());
            // Move once, wait for a complete matching camera, then save the composed F3 preview.
            state.eye(state.eye().add(new math.Vec3(.02f,0,0)));
            profiler.beginFrame();viewport.render();profiler.endFrame();
            deadline=System.nanoTime()+8_000_000_000L;
            while(state.accumulatedSamples()<1 && System.nanoTime()<deadline) {
                profiler.beginFrame();viewport.render();profiler.endFrame();Thread.sleep(2);
            }
            profiler.beginFrame();viewport.render();injector.get(PerformanceOverlay.class).render();profiler.endFrame();
            assertTrue(profiler.viewportHistory().contains("blend"));
            assertTrue(profiler.viewportQuality().contains("160x100"));
            var preview=new java.awt.image.BufferedImage(raster.width(),raster.height(),java.awt.image.BufferedImage.TYPE_INT_RGB);
            for(int y=0;y<raster.height();y++)for(int x=0;x<raster.width();x++)preview.setRGB(x,y,raster.pixel(x,y).rgbInt24());
            javax.imageio.ImageIO.write(preview,"png",new java.io.File("out/cli/p5-f3-preview.png"));
            var key=state.renderKey();state.temporal(false);
            profiler.beginFrame();viewport.render();profiler.endFrame();
            assertEquals("History off (raw)",profiler.viewportHistory());assertEquals(key,state.renderKey());assertEquals(1L,state.accumulatedSamples());
            var snapshot=state.renderSnapshot();snapshot.temporal(false);
            try(var reference=new scenes.viewport.DirectRgbTracer(snapshot)) {
                var expected=raster.clone();var converter=new scenes.viewport.DisplayConverter(raster.width(),raster.height());
                converter.convert(reference.trace(),1);converter.paint(expected);
                for(int i=0;i<(raster.height()-60)*raster.width();i++) {
                    assertEquals(expected.red()[i],raster.red()[i]);assertEquals(expected.green()[i],raster.green()[i]);assertEquals(expected.blue()[i],raster.blue()[i]);
                }
            }
            var editor=injector.get(TextureEditor.class);profiler.beginFrame();editor.render();profiler.endFrame();assertNull(profiler.viewportHistory());
        }
    }
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
