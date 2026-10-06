package scenes.viewport;

import harness.Test;
import harness.SuiteRunner;
import static harness.Assertions.*;

public class TemporalBudgetTest {
    @Test void budgetPreflightRejectsMissingExpiredEditedAndCutHistory() {
        var s=TemporalReconstructionTest.plane();var t=new TemporalReconstruction();long now=1_000_000_000L;
        assertFalse(t.compatibleHistory(s.renderKey(),now));
        t.reconstruct(TemporalReconstructionTest.noise(64,64,1),TemporalReconstructionTest.planeGuide(s),s.renderKey(),4,now);
        assertFalse(t.compatibleHistory(s.renderKey(),now)); // No frozen prior view for same-camera refinement.
        TemporalReconstructionTest.move(s,.02f);assertTrue(t.compatibleHistory(s.renderKey(),now+10_000_000L));
        assertFalse(t.compatibleHistory(s.renderKey(),now+600_000_000L));
        s.seed(2);assertFalse(t.compatibleHistory(s.renderKey(),now+10_000_000L));s.seed(1);
        TemporalReconstructionTest.move(s,1);assertFalse(t.compatibleHistory(s.renderKey(),now+10_000_000L));
        s.resolution(80,64);assertFalse(t.compatibleHistory(s.renderKey(),now+10_000_000L));
    }
    @Test void controlsSnapshotFallbackAndRequestedSettings() {
        var s=TemporalReconstructionTest.plane();s.resolution(800,500);var cmd=new ViewportCommand(s);
        assertFalse(s.temporalBudget());assertEquals(1.,s.motionScale());
        assertTrue(cmd.run("view","temporal","budget","on").isSuccess());assertFalse(s.temporalBudgetSupported());
        assertTrue(cmd.run("view","temporal","on").isSuccess());assertTrue(s.temporalBudgetSupported());
        assertTrue(cmd.run("view","temporal","budget","samples","2").isSuccess());
        assertTrue(cmd.run("view","temporal","budget","scale","0.5").isSuccess());
        assertFalse(cmd.run("view","temporal","budget","samples","0").isSuccess());
        assertFalse(cmd.run("view","temporal","budget","scale","NaN").isSuccess());
        assertFalse(cmd.run("view","temporal","budget","scale","0.1").isSuccess());
        assertFalse(cmd.run("view","temporal","budget","on","extra").isSuccess());
        assertTrue(cmd.help("temporal","budget").isSuccess());
        var copy=s.renderSnapshot();assertTrue(copy.temporalBudget());assertEquals(2,copy.motionSamples());assertEquals(.5,copy.motionScale());
        var policy=new InteractiveResolution();long now=1_000_000_000L;policy.observe(s,now);
        TemporalReconstructionTest.move(s,.02f);policy.observe(s,now);policy.choose(s,now);
        assertEquals(400,s.sampledWidth());assertEquals(250,s.sampledHeight());assertEquals(800,s.sensorPixelsW());
        policy.choose(s,now+InteractiveResolution.SETTLE_NANOS);assertEquals(800,s.sampledWidth());
        ScenePresets.load(s,"volume-room");assertFalse(s.temporalBudgetSupported());
        TemporalReconstructionTest.move(s,.02f);policy.observe(s,++now);policy.choose(s,now);assertEquals(800,s.sampledWidth());
        s.temporal(false);assertFalse(s.temporalBudgetSupported());
    }
    @Test void smallGridsAndInteractiveBoundsRemainAuthoritative() {
        var s=TemporalReconstructionTest.plane();s.temporal(true);s.temporalBudget(true);s.motionScale(.25);
        s.resolution(100,64);var c=new InteractiveResolution();long now=1_000_000_000L;c.observe(s,now);
        TemporalReconstructionTest.move(s,.02f);c.observe(s,now);c.choose(s,now);
        assertEquals(100,s.sampledWidth());assertEquals(64,s.sampledHeight());
        s.resolution(800,500);s.interactive(true);s.interactiveMinimum(400,250);c.choose(s,now);
        assertEquals(400,s.sampledWidth());assertEquals(250,s.sampledHeight());
        s.paused(true);c.choose(s,now+1_000_000_000L);assertEquals(400,s.sampledWidth());
        s.paused(false);c.choose(s,now+1_000_000_000L);assertEquals(800,s.sampledWidth());
    }
    @Test void asyncMovingBatchAndGridRestoreThenConvergeExactly() throws Exception {
        var s=TemporalReconstructionTest.plane();s.resolution(256,160);s.samplesPerFrame(4);s.sampleTarget(8);
        s.temporal(true);s.temporalBudget(true);s.motionScale(.5);
        try(var async=new AsyncViewportTrace(s,new DirectRgbTracer(s))) {
            synchronized(s){async.request();TemporalReconstructionTest.move(s,.02f);async.invalidate();}
            long deadline=System.nanoTime()+5_000_000_000L,last=-1;int moving=0;
            while(moving<4 && System.nanoTime()<deadline) {
                synchronized(s) {
                    TemporalReconstructionTest.move(s,.001f);async.invalidate();async.request();
                    var image=async.image();async.presented(image);
                    if(image!=null && image.motionBudget() && image.plannedBatch()==1 && image.requestedNanos()!=last) {
                        last=image.requestedNanos();moving++;
                        assertEquals(128,image.key().width());assertEquals(80,image.key().height());
                        assertEquals(4,image.requestedBatch());assertEquals(1,image.plannedBatch());
                        assertEquals(1,image.stats().samplesPerPixel());
                    }
                }
                Thread.sleep(3);
            }
            assertEquals(4,moving);assertEquals(4,s.samplesPerFrame());
            AsyncViewportTrace.Image settled=null;deadline=System.nanoTime()+5_000_000_000L;
            while(System.nanoTime()<deadline) {
                synchronized(s) {
                    async.request();var image=async.image();async.presented(image);
                    if(image!=null && image.key().equals(s.renderKey()) && image.key().width()==256 && image.samples()==8) {
                        settled=async.retain(image);break;
                    }
                }
                Thread.sleep(3);
            }
            assertNotNull(settled);assertFalse(settled.motionBudget());assertEquals(4,settled.plannedBatch());
            var reference=s.renderSnapshot();reference.temporal(false);reference.samplesPerFrame(1);
            try(var tracer=new DirectRgbTracer(reference)) {
                for(int i=0;i<8;i++)tracer.trace();assertEquals(tracer.radianceBuffer(),settled.rgb());
            }
            synchronized(s){async.release(settled);TemporalReconstructionTest.move(s,.02f);async.invalidate();
                s.temporal(false);async.request();}
            deadline=System.nanoTime()+5_000_000_000L;
            while(System.nanoTime()<deadline) {
                synchronized(s){async.request();if(s.sampledWidth()==256)break;}
                Thread.sleep(3);
            }
            assertEquals(256,s.sampledWidth());assertEquals(4,s.samplesPerFrame());
        }
    }
    public static void main(String[] args){SuiteRunner.runThis();}
}
