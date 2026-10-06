package scenes.viewport;

import harness.SuiteRunner;
import harness.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashMap;
import math.Vec3;
import static harness.Assertions.*;

public class FocusLifecycleTest {
    @Test void slowQueriesBoundedLatestSourceCloseAndStrictOneShot() throws Exception {
        var f=FocusControllerTest.fixture();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        var resolver=new CameraFocus();
        try(var service=new FocusQueryService((source,camera,scene,epoch,captured,clock)->{
            calls.incrementAndGet();entered.countDown();try {release.await();}catch(InterruptedException ignored) {}
            return resolver.resolve(source,camera,scene,epoch,captured,clock);
        })) {
            f.focus().service(service);f.focus().delay(0);f.focus().duration(0);f.focus().mode("auto");f.focus().tick();
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            for(int i=0;i<30;i++) {f.focus().source(new FocusTargetSource.Screen(.5f,.3f+i*.01f));f.advance(50);}
            assertEquals(1,calls.get());assertTrue(service.busy());assertEquals(5f,f.state().camera().focus());
            f.focus().mode("manual");release.countDown();awaitDone(service);f.focus().tick();assertEquals(5f,f.state().camera().focus());
            assertTrue(f.focus().request(FocusTargetSource.CENTER,false));f.focus().tick();awaitDone(service);
            TemporalReconstructionTest.move(f.state(),.2f);f.focus().tick();assertEquals(5f,f.state().camera().focus());
            assertTrue(f.focus().status().contains("changed"));
            f.focus().close();assertFalse(service.submit(FocusTargetSource.CENTER,f.state().camera(),CameraFocus.Scene.capture(f.state()),0,0,System::nanoTime));
        } finally {release.countDown();f.focus().close();}
    }
    @Test void cadenceSuspendResumeAndZeroAperturePreservesAccumulationHistory() throws Exception {
        var f=FocusControllerTest.fixture();var s=f.state();var count=new AtomicInteger();var resolver=new CameraFocus();
        try(var service=new FocusQueryService((source,camera,scene,epoch,captured,clock)->{
            count.incrementAndGet();return resolver.resolve(source,camera,scene,epoch,captured,clock);
        });var tracer=new DirectRgbTracer(s)) {
            s.temporal(true);s.sampleTarget(3);do {tracer.trace();}while(s.accumulatedSamples()<3);
            var raw=TemporalReconstructionTest.copy(tracer.radianceBuffer());var key=s.renderKey();long version=s.cameraHistoryVersion();
            var history=new TemporalReconstruction();var guide=tracer.surfaceGuide();
            history.reconstruct(raw,guide,key,3,f.clock().now,1);
            f.focus().service(service);f.focus().delay(0);f.focus().duration(0);f.focus().mode("auto");
            f.focus().tick();awaitDone(service);f.focus().tick();assertEquals(6f,s.camera().focus());
            for(int i=0;i<49;i++)f.advance(1);assertEquals(1,count.get());f.advance(1);awaitDone(service);assertEquals(2,count.get());
            f.focus().tick();assertEquals(key,s.renderKey());assertEquals(version,s.cameraHistoryVersion());assertEquals(3L,s.accumulatedSamples());
            assertEquals(raw,tracer.trace());assertNotNull(tracer.surfaceGuide());
            var control=new TemporalReconstruction();control.reconstruct(raw,guide,key,3,1_000_000_000L,1);
            assertEquals(control.reconstruct(raw,guide,key,3,f.clock().now+50_000_000,1),
                    history.reconstruct(raw,guide,s.renderKey(),3,f.clock().now+50_000_000,1));
            assertEquals(control.stats().reason(),history.stats().reason());assertEquals(control.stats().bytes(),history.stats().bytes());
            assertEquals(control.stats().reused(),history.stats().reused());assertTrue(history.stats().bytes()>0);
            f.focus().suspend(true);f.advance(1000);assertEquals(2,count.get());f.focus().suspend(false);f.focus().tick();awaitDone(service);f.focus().tick();assertEquals(3,count.get());
        } finally {f.focus().close();}
    }
    @Test void finiteAutofocusCapturedPreviewsSettleRestoreGridAndConverge() throws Exception {
        for(String projection:new String[]{"perspective","orthographic"}) {
            var s=TemporalReconstructionTest.plane();s.camera(s.camera().withProjection(projection).withAperture(.25f));
            s.resolution(128,128);s.interactive(true);s.pathDepth(2);s.sampleTarget(3);
            var clock=new FocusControllerTest.Clock();var focus=new FocusController(s,clock);focus.delay(0);focus.duration(300);focus.mode("auto");
            var captured=new HashMap<ViewportState.RenderKey,ViewportState>();int previews=0;long last=-1;
            try(var async=new AsyncViewportTrace(s,new DirectRgbTracer(s))) {
                long deadline=System.nanoTime()+5_000_000_000L;
                for(int i=0;i<60;i++) {
                    synchronized(s) {
                        clock.advance(10);focus.tick();
                        focus.accept(new CameraFocus.Measurement(focus.source(),new CameraFocus.Subject("moving","moving"),null,3+i*.04f,
                                clock.now,clock.now,focus.epoch(),CameraFocus.Scene.capture(s),s.camera(),null));
                        async.request();captured.put(s.renderKey(),s.renderSnapshot());
                        var image=async.acquireImage();
                        if(image!=null && image.requestedNanos()!=last) {
                            last=image.requestedNanos();previews++;
                            assertEquals(image.key().camera(),image.camera().identity());
                            var snapshot=captured.get(image.key());assertNotNull(snapshot);snapshot.sampleTarget(image.samples());
                            try(var reference=new DirectRgbTracer(snapshot)) {
                                do {reference.trace();}while(snapshot.accumulatedSamples()<image.samples());assertEquals(reference.radianceBuffer(),image.rgb());
                            }
                        }
                        async.release(image);
                    }
                    Thread.sleep(3);
                }
                assertTrue(previews>2);
                synchronized(s) {clock.advance(300);focus.tick();assertFalse(focus.pulling());}
                AsyncViewportTrace.Image settled=null;
                while(System.nanoTime()<deadline) {
                    synchronized(s) {
                        async.request();var image=async.image();
                        if(image!=null && image.key().equals(s.renderKey()) && image.samples()==3 && s.sampledWidth()==128) {settled=async.retain(image);break;}
                    }
                    Thread.sleep(2);
                }
                assertNotNull(settled);assertEquals(3L,s.accumulatedSamples());assertNull(settled.reconstructed());
                var snapshot=s.renderSnapshot();
                try(var reference=new DirectRgbTracer(snapshot)) {
                    do {reference.trace();}while(snapshot.accumulatedSamples()<3);assertEquals(reference.radianceBuffer(),settled.rgb());
                }
                synchronized(s) {async.release(settled);}
            } finally {focus.close();}
        }
    }
    @Test void staleSceneSourceProjectionAndFocusWritesDoNotSelfInvalidate() {
        var f=FocusControllerTest.fixture();f.focus().mode("auto");f.focus().delay(0);f.focus().duration(0);
        var resolver=new CameraFocus();var result=resolver.resolve(f.focus().source(),f.state().camera(),CameraFocus.Scene.capture(f.state()),f.focus().epoch(),f.clock().now,f.clock());
        var original=f.state().instances().getFirst();
        f.state().instances().set(0,original.withTransform(new Transform(new Vec3(0,0,2),Vec3.ZERO,new Vec3(1,1,1))));
        f.focus().accept(result);assertEquals(5f,f.state().camera().focus());
        f.state().instances().set(0,original);f.focus().accept(result);assertEquals(5f,f.state().camera().focus());
        result=resolver.resolve(f.focus().source(),f.state().camera(),CameraFocus.Scene.capture(f.state()),f.focus().epoch(),f.clock().now,f.clock());
        f.focus().accept(result);assertEquals(6f,f.state().camera().focus());
        long epoch=f.focus().epoch();f.advance(10);assertEquals(epoch,f.focus().epoch());
        f.focus().source(new FocusTargetSource.Screen(.2f,.5f));f.focus().accept(result);assertEquals(6f,f.state().camera().focus());
        f.focus().framingChanged();f.focus().accept(result);assertEquals(6f,f.state().camera().focus());f.focus().close();
    }
    private static void awaitDone(FocusQueryService service) throws Exception {
        long deadline=System.nanoTime()+2_000_000_000L;
        while(service.busy()&&System.nanoTime()<deadline)Thread.sleep(1);
        assertFalse(service.busy());
    }
    public static void main(String[] args) {SuiteRunner.runThis();}
}
