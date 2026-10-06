package engine;

import scenes.viewport.*;
import harness.SuiteRunner;
import harness.Test;
import math.Vec3;
import engine.objects.Rect;
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
                if(image!=null && image.generation()==async.progress(image).requestedGeneration()
                        && image.key().equals(s.renderKey()) && image.samples()>=samples) return async.retain(image);
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
            var paused=await(async,s,1); synchronized(s) { async.release(paused); }
            synchronized(s) { s.paused(false); s.exposure(2); async.invalidate(); }
            var completed=await(async,s,4);
            assertEquals(retained,image.rgb()); assertEquals(4L,completed.samples());
            synchronized(s) { async.release(image); }
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
                async.release(completed); async.release(restored);
                s.restart(); s.paused(true); async.invalidate();
            }
            var reset=await(async,s,0); assertEquals(0L,reset.samples());
            synchronized(s) { async.release(reset); }
            synchronized(s) { s.paused(false); s.sampleTarget(1); async.invalidate(); }
            var finalImage=await(async,s,1); assertEquals(1L,finalImage.samples());
            synchronized(s) { async.release(finalImage); }
        }
    }
    @Test void suspendAndCloseDrainWithoutPublishingOldWork() throws Exception {
        var s=state(); s.resolution(900);
        var async=new AsyncViewportTrace(s,new DirectRgbTracer(s));
        synchronized(s) { async.request(); async.suspend(); }
        Thread.sleep(30); assertNull(async.image());
        synchronized(s) { s.resolution(64); s.sampleTarget(1); }
        var finalImage=await(async,s,1); assertEquals(1L,finalImage.samples());
        synchronized(s) { async.release(finalImage); }
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
                        if(previews.isEmpty()) async.retain(image);
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
            synchronized(s) { async.release(preview); s.samplesPerFrame(1); s.sampleTarget(2); async.invalidate(); }
            var settled=await(async,s,2);
            try(var reference=new DirectRgbTracer(s.renderSnapshot())) {
                reference.trace(); reference.trace();
                assertEquals(reference.radianceBuffer(),settled.rgb());
            }
            synchronized(s) { async.release(settled); }
        }
    }
    @Test void publicationPoolReusesStorageAndCannotOverwriteALeasedImage() throws Exception {
        var s=state(); s.resolution(64); s.sampleTarget(1);
        var storage=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<float[][][],Boolean>());
        try(var async=new AsyncViewportTrace(s,new DirectRgbTracer(s))) {
            var first=await(async,s,1); var retained=copy(first.rgb()); storage.add(first.rgb());
            for(int spp=2;spp<=12;spp++) {
                synchronized(s) { s.sampleTarget(spp); async.invalidate(); }
                var next=await(async,s,spp); storage.add(next.rgb());
                assertEquals(retained,first.rgb());
                synchronized(s) { async.release(next); }
            }
            assertTrue(storage.size()<=3);
            synchronized(s) { async.release(first); s.resolution(67,73); s.restart(); s.sampleTarget(1); }
            var resized=await(async,s,1);
            assertEquals(73,resized.rgb()[0].length); assertEquals(67,resized.rgb()[0][0].length);
            synchronized(s) { async.release(resized); }
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
