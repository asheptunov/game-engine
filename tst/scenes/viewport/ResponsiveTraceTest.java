package scenes.viewport;

import harness.SuiteRunner;
import harness.Test;
import math.Vec3;
import scenes.viewport.objects.Rect;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import static harness.Assertions.*;

public class ResponsiveTraceTest {
    private static ViewportState state() {
        var s=new ViewportState(new Rect(new Vec3(-.5f,-.5f,0),new Vec3(1,0,0),new Vec3(0,1,0)),67,73);
        ScenePresets.load(s,"glass"); s.workers(1); s.tileSize(8); s.seed(37); return s;
    }
    private static AsyncViewportTrace.Image await(AsyncViewportTrace async,ViewportState s,long samples) throws Exception {
        long deadline=System.nanoTime()+10_000_000_000L;
        while(System.nanoTime()<deadline) {
            synchronized(s) {
                async.request(); var image=async.image();
                if(image!=null && image.generation()==async.progress(image).generation()
                        && image.key().equals(s.renderKey()) && image.samples()>=samples) return image;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("No completed current-generation image");
    }
    @Test void cancellationPreservesCommittedMeanAndSampleStream() {
        var s=state();
        try(var tracer=new DirectRgbTracer(s); var reference=new DirectRgbTracer(state())) {
            tracer.trace(); reference.trace();
            var before=copy(tracer.radianceBuffer());
            var checks=new AtomicInteger();
            tracer.trace(s,()->checks.incrementAndGet()>2);
            assertTrue(tracer.cancelled); assertTrue(tracer.discardedPrimaryRays>0);
            assertEquals(1L,s.accumulatedSamples()); assertEquals(before,tracer.radianceBuffer());
            tracer.trace(s,()->false); reference.trace();
            assertEquals(reference.radianceBuffer(),tracer.radianceBuffer());
        }
    }
    @Test void hardEditsAndPauseCannotPublishObsoleteOrMutableImages() throws Exception {
        var s=state(); s.resolution(900); s.sampleTarget(4);
        try(var async=new AsyncViewportTrace(s,new DirectRgbTracer(s))) {
            synchronized(s) { async.request(); }
            // The main thread can capture/edit state while a native-sized single-worker pass runs.
            synchronized(s) {
                for(int i=0;i<40;i++) { s.eye(new Vec3(i*.01f,0,-1)); async.invalidate(); }
                s.resolution(67,73); ScenePresets.load(s,"volume-room"); s.sampleTarget(4); async.invalidate();
            }
            var image=await(async,s,1); var retained=copy(image.rgb());
            synchronized(s) { s.paused(true); async.invalidate(); }
            await(async,s,1);
            synchronized(s) { s.paused(false); s.exposure(2); async.invalidate(); }
            var completed=await(async,s,4);
            assertEquals(retained,image.rgb()); assertEquals(4L,completed.samples());
            synchronized(s) {
                var eye=s.eye(); s.eye(new Vec3(10,0,-1)); async.invalidate(); s.eye(eye); async.invalidate();
            }
            var restored=await(async,s,4);
            assertEquals(completed.generation()+2,restored.generation());
            assertEquals(completed.rgb(),restored.rgb());
            try(var reference=new DirectRgbTracer(s.renderSnapshot())) {
                for(int i=0;i<4;i++) reference.trace();
                assertEquals(reference.radianceBuffer(),completed.rgb());
            }
            synchronized(s) {
                s.restart(); s.paused(true); async.invalidate();
            }
            var reset=await(async,s,0); assertEquals(0L,reset.samples());
            synchronized(s) { s.paused(false); s.sampleTarget(1); async.invalidate(); }
            assertEquals(1L,await(async,s,1).samples());
        }
    }
    @Test void suspendAndCloseDrainWithoutPublishingOldWork() throws Exception {
        var s=state(); s.resolution(900);
        var async=new AsyncViewportTrace(s,new DirectRgbTracer(s));
        synchronized(s) { async.request(); async.suspend(); }
        Thread.sleep(30); assertNull(async.image());
        synchronized(s) { s.resolution(64); s.sampleTarget(1); }
        assertEquals(1L,await(async,s,1).samples());
        async.close();
        synchronized(s) { async.request(); }
    }
    @Test void sustainedCameraMotionPublishesCompletePreviewsWithoutBorrowingSamples() throws Exception {
        var s=state(); s.resolution(512); s.samplesPerFrame(8);
        var previews=new ArrayList<AsyncViewportTrace.Image>();
        try(var async=new AsyncViewportTrace(s,new DirectRgbTracer(s))) {
            long deadline=System.nanoTime()+10_000_000_000L;
            int edits=0;
            while(previews.size()<3 && System.nanoTime()<deadline) {
                synchronized(s) {
                    s.eye(new Vec3((float)Math.sin(++edits*.01)*.1f,0,-1));
                    async.invalidate(); async.request();
                    // Force the active snapshot to lag before leaving the monitor, every iteration.
                    s.eye(new Vec3((float)Math.sin(++edits*.01)*.1f,0,-1)); async.invalidate();
                    var image=async.image();
                    if(image!=null && (previews.isEmpty() || previews.getLast()!=image)) {
                        assertFalse(image.key().equals(s.renderKey()));
                        assertEquals(0L,s.accumulatedSamples());
                        assertEquals(1,image.stats().samplesPerPixel());
                        previews.add(image);
                    }
                }
                Thread.sleep(1);
            }
            assertEquals(3,previews.size()); // No pause in mouse edits was needed for these images.
            var preview=previews.getFirst(); var referenceState=state();
            referenceState.resolution(512); referenceState.eye(preview.key().eye());
            referenceState.cameraSensor(preview.key().sensor());
            try(var reference=new DirectRgbTracer(referenceState)) {
                reference.trace(); assertEquals(reference.radianceBuffer(),preview.rgb());
            }
            synchronized(s) { s.samplesPerFrame(1); s.sampleTarget(2); async.invalidate(); }
            var settled=await(async,s,2);
            try(var reference=new DirectRgbTracer(s.renderSnapshot())) {
                reference.trace(); reference.trace();
                assertEquals(reference.radianceBuffer(),settled.rgb());
            }
        }
    }
    private static float[][][] copy(float[][][] rgb) {
        var result=new float[3][][];
        for(int c=0;c<3;c++) { result[c]=new float[rgb[c].length][];
            for(int y=0;y<rgb[c].length;y++) result[c][y]=rgb[c][y].clone(); }
        return result;
    }
    public static void main(String[] args) { SuiteRunner.runThis(); }
}
