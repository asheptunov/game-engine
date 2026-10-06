package scenes.viewport;

import harness.SuiteRunner;
import harness.Test;
import math.Vec3;
import scenes.viewport.objects.Rect;
import scenes.viewport.lights.PointLight;
import java.util.List;
import static harness.Assertions.*;

public class TemporalReconstructionTest {
    @Test void sharedCornersReduceQueriesAndRemainIndependentOfTilesAndWorkers() {
        SurfaceGuide expected=null;float[][][] expectedRgb=null;
        for(int tile:new int[]{8,32,256}) for(int workers:new int[]{1,4}) {
            var s=plane();s.temporal(true);s.tileSize(tile);s.workers(workers);
            try(var tracer=new DirectRgbTracer(s)) {
                var raw=copy(tracer.trace());var g=tracer.surfaceGuide();
                // A full diffuse plane needs one center ray per pixel and one ray per tile vertex.
                int tiles=(64+tile-1)/tile;
                long vertices=(long)(64+tiles)*(64+tiles);
                assertEquals(4096L+vertices,tracer.guideRays);
                assertTrue(tracer.guideRays<3L*4096); // Previously five rays per suitable pixel.
                if(expected==null) {expected=new SurfaceGuide(64,64);expected.copyFrom(g);expectedRgb=raw;}
                else {
                    assertEquals(expectedRgb,raw);
                    for(int i=0;i<4096;i++) {
                        assertEquals(expected.surface[i],g.surface[i]);assertEquals(expected.depth[i],g.depth[i]);
                        assertEquals(expected.nx[i],g.nx[i]);assertEquals(expected.ny[i],g.ny[i]);assertEquals(expected.nz[i],g.nz[i]);
                    }
                }
                s.temporal(false);tracer.trace();assertEquals(0L,tracer.guideRays);
            }
        }
    }
    @Test void parallelReconstructionMatchesScalarAndCancellationDiscardsPartialHistory() {
        var s=plane();var serial=new TemporalReconstruction();var parallel=new TemporalReconstruction();
        int workers=Math.min(14,Runtime.getRuntime().availableProcessors());long now=1_000_000_000L;
        for(int frame=0;frame<6;frame++) {
            move(s,.02f);var raw=noise(64,64,frame);var guide=planeGuide(s);
            assertEquals(serial.reconstruct(raw,guide,s.renderKey(),1,now,1),parallel.reconstruct(raw,guide,s.renderKey(),1,now,workers));
            now+=10_000_000;
        }
        move(s,.02f);var raw=noise(64,64,9);var checks=new java.util.concurrent.atomic.AtomicInteger();
        assertSame(raw,parallel.reconstruct(raw,planeGuide(s),s.renderKey(),1,now,workers,()->checks.incrementAndGet()>2));
        assertEquals(0L,parallel.stats().bytes());
        assertEquals(raw,parallel.reconstruct(raw,planeGuide(s),s.renderKey(),1,now,workers));assertEquals(0,parallel.stats().reused());
    }
    @Test void cornerCacheRejectsSilhouettesAndSeamsAcrossTileBoundaries() {
        SurfaceGuide expected=null;
        for(int tile:new int[]{8,32,256}) for(int workers:new int[]{1,4}) {
            var s=plane();s.temporal(true);s.pathDepth(0);s.tileSize(tile);s.workers(workers);
            s.instances().addFirst(new SceneInstance("strip",List.of(new Rect(new Vec3(.035f,-1,4),
                    new Vec3(.25f,2,0),new Vec3(.02f,0,0))),Transform.IDENTITY,new Material("strip",new Vec3(1,0,0))));
            try(var tracer=new DirectRgbTracer(s)) {
                tracer.trace();var g=tracer.surfaceGuide();int rejected=0,accepted=0;
                for(int id:g.surface) {if(id==0) rejected++;else accepted++;}
                assertTrue(rejected>0);assertTrue(accepted>3000);
                if(expected==null) {expected=new SurfaceGuide(64,64);expected.copyFrom(g);}
                else for(int i=0;i<4096;i++) {
                    assertEquals(expected.surface[i],g.surface[i]);
                    if(g.surface[i]!=0) {
                        assertEquals(expected.depth[i],g.depth[i]);assertEquals(expected.nz[i],g.nz[i]);
                    }
                }
            }
        }
    }
    static ViewportState plane() {
        var s=new ViewportState(new Rect(new Vec3(-.5f,-.5f,0),new Vec3(1,0,0),new Vec3(0,1,0)),64,64);
        s.instances().add(new SceneInstance("wall",List.of(new Rect(new Vec3(-10,-10,5),new Vec3(0,20,0),new Vec3(20,0,0))),
                Transform.IDENTITY,new Material("wall",new Vec3(.5f,.5f,.5f))));
        s.lights().add(new PointLight(new Vec3(0,3,0),new Vec3(1,1,1),50));s.workers(1);s.pathDepth(2);
        return s;
    }
    static void move(ViewportState s,float x) {
        var delta=new Vec3(x,0,0);s.eye(s.eye().add(delta));var r=s.cameraSensor();
        s.cameraSensor(new Rect(r.origin().add(delta),r.edge1(),r.edge2()));
    }
    static SurfaceGuide planeGuide(ViewportState s) {
        var g=new SurfaceGuide(s.sensorPixelsW(),s.sensorPixelsH());var r=s.cameraSensor();var e=s.eye();
        for(int y=0;y<g.height;y++) for(int x=0;x<g.width;x++) {
            var d=r.origin().add(r.edge1().scale((x+.5f)/g.width)).add(r.edge2().scale((y+.5f)/g.height)).sub(e).normalized();
            int i=y*g.width+x;g.surface[i]=1;g.depth[i]=(5-e.z())/d.z();g.nz[i]=-1;
        }
        return g;
    }
    static float[][][] noise(int w,int h,int phase) {
        var rgb=new float[3][h][w];var random=new java.util.Random(phase);
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) for(int c=0;c<3;c++) rgb[c][y][x]=random.nextBoolean()?.75f:.25f;
        return rgb;
    }
    static float[][][] copy(float[][][] rgb) {
        var out=new float[3][rgb[0].length][rgb[0][0].length];
        for(int c=0;c<3;c++) for(int y=0;y<out[c].length;y++) out[c][y]=rgb[c][y].clone();return out;
    }
    static double error(float[][][] rgb) {
        double sum=0;for(var channel:rgb) for(var row:channel) for(float v:row) sum+=(v-.5)*(v-.5);return sum;
    }
    @Test void reprojectsReducesNoiseAndDoesNotCountStationaryHistoryTwice() {
        var s=plane();var t=new TemporalReconstruction();long now=1_000_000_000L;
        var first=noise(64,64,1);assertEquals(first,t.reconstruct(first,planeGuide(s),s.renderKey(),1,now));
        move(s,.08f);var raw=noise(64,64,2);var saved=copy(raw);
        var result=copy(t.reconstruct(raw,planeGuide(s),s.renderKey(),1,now+10_000_000));
        assertTrue(t.stats().reused()>3000);assertTrue(t.stats().confidence()>.4);
        assertTrue(error(result)<error(raw)*.65);assertEquals(saved,raw);
        assertEquals(result,t.reconstruct(raw,planeGuide(s),s.renderKey(),1,now+20_000_000));
        t.reconstruct(raw,planeGuide(s),s.renderKey(),20,now+30_000_000);
        assertTrue(t.stats().confidence()<.05); // Raw refinement progressively dominates the frozen source.
        t.clear();assertEquals(0L,t.stats().bytes());
    }
    @Test void rejectsDisocclusionDepthNormalEdgesCutsEditsAndExpiredHistory() {
        var s=plane();var t=new TemporalReconstruction();long now=1_000_000_000L;
        var raw=noise(64,64,1);t.reconstruct(raw,planeGuide(s),s.renderKey(),1,now);move(s,.01f);
        var g=planeGuide(s);
        for(int y=20;y<44;y++) for(int x=20;x<44;x++) g.surface[y*64+x]=2;
        var result=t.reconstruct(raw,g,s.renderKey(),1,now+10_000_000);
        assertEquals(raw[0][32][32],result[0][32][32]);assertTrue(t.stats().reused()>0);
        // Full-frame correspondence failures must leave raw output exact.
        t.clear();t.reconstruct(raw,planeGuide(s),s.renderKey(),1,now);move(s,.01f);g=planeGuide(s);
        for(int i=0;i<g.surface.length;i++)g.depth[i]+=1;
        assertEquals(raw,t.reconstruct(raw,g,s.renderKey(),1,now+10_000_000));assertEquals(0,t.stats().reused());
        t.clear();t.reconstruct(raw,planeGuide(s),s.renderKey(),1,now);move(s,.01f);g=planeGuide(s);
        java.util.Arrays.fill(g.nz,1);
        assertEquals(raw,t.reconstruct(raw,g,s.renderKey(),1,now+10_000_000));assertEquals(0,t.stats().reused());
        move(s,1);assertEquals(raw,t.reconstruct(raw,planeGuide(s),s.renderKey(),1,now+20_000_000));
        assertEquals("camera cut",t.stats().reason());
        s.lights().set(0,new PointLight(new Vec3(0,3,0),new Vec3(1,1,1),100));move(s,.01f);
        assertEquals(raw,t.reconstruct(raw,planeGuide(s),s.renderKey(),1,now+30_000_000));assertEquals("scene/grid edit",t.stats().reason());
        move(s,.01f);assertEquals(raw,t.reconstruct(raw,planeGuide(s),s.renderKey(),1,now+1_000_000_000));
        assertEquals("expired",t.stats().reason());
        s.resolution(80,64);raw=noise(80,64,3);
        assertEquals(raw,t.reconstruct(raw,planeGuide(s),s.renderKey(),1,now+1_010_000_000));assertEquals(0,t.stats().reused());
        assertEquals(72L*80*64,t.stats().bytes());
    }
    @Test void sustainedForwardBackwardMotionKeepsStableHistoryAndBoundedStorage() {
        var s=plane();var t=new TemporalReconstruction();long now=1_000_000_000L;
        double previous=0;
        for(int frame=0;frame<96;frame++) {
            var delta=new Vec3(0,0,frame<48?.01f:-.01f);var sensor=s.cameraSensor();
            s.eye(s.eye().add(delta));s.cameraSensor(new Rect(sensor.origin().add(delta),sensor.edge1(),sensor.edge2()));
            t.reconstruct(noise(64,64,frame),planeGuide(s),s.renderKey(),1,now+frame*40_000_000L);
            assertEquals(72L*64*64,t.stats().bytes());
            if(frame>=5) {
                assertTrue(t.stats().reused()>3000);assertTrue(t.stats().confidence()>.65);
                assertTrue(Math.abs(t.stats().confidence()-previous)<.04);
            }
            previous=t.stats().confidence();
        }
        var raw=noise(64,64,1);assertSame(raw,t.reconstruct(raw,null,s.renderKey(),1,now));assertEquals(0L,t.stats().bytes());
    }
    @Test void oldRadianceFadesGraduallyAcrossTheFormerResetBoundary() {
        var s=plane();var a=new TemporalReconstruction();var b=new TemporalReconstruction();long now=1_000_000_000L;
        var raw=noise(64,64,1);raw[0][32][32]=.5f;raw[0][32][31]=.25f;raw[0][32][33]=.75f;
        var first=copy(raw);var second=copy(raw);first[0][32][32]=.75f;second[0][32][32]=.25f;
        a.reconstruct(first,planeGuide(s),s.renderKey(),1,now);b.reconstruct(second,planeGuide(s),s.renderKey(),1,now);
        double previous=.5;
        for(int frame=1;frame<=32;frame++) {
            move(s,.001f);var guide=planeGuide(s);
            double delta=a.reconstruct(raw,guide,s.renderKey(),1,now+frame*40_000_000L)[0][32][32]
                    -b.reconstruct(raw,guide,s.renderKey(),1,now+frame*40_000_000L)[0][32][32];
            assertTrue(delta>0 && delta<previous);
            if(frame>=4)assertTrue(Math.abs(delta-previous*.8)<1e-6);
            if(frame==9)assertTrue(delta>.01); // Previously dropped straight to zero here.
            previous=delta;
        }
        assertTrue(previous<.001); // Old information still fades; retaining buffers does not preserve its full weight.
    }
    @Test void guidesNeverChangeRawStreamsAndGuardThinSurfacesAndMedia() {
        for(String preset:List.of("bounce-room","glass","glass-inside","rough-room","mesh-room","volume-room")) {
            var s=plane();ScenePresets.load(s,preset);s.resolution(64);s.temporal(true);
            var reference=s.renderSnapshot();reference.temporal(false);
            try(var tracer=new DirectRgbTracer(s);var other=new DirectRgbTracer(reference)) {
                for(int pass=0;pass<3;pass++) {
                    assertEquals(other.trace(),tracer.trace());
                    assertEquals(other.primaryTests,tracer.primaryTests);assertEquals(other.continuationTests,tracer.continuationTests);
                    assertEquals(other.shadowTests,tracer.shadowTests);assertEquals(other.primaryRays,tracer.primaryRays);
                }
                assertEquals(reference.accumulatedSamples(),s.accumulatedSamples());
                if(preset.equals("volume-room"))assertNull(tracer.surfaceGuide());
                else assertNotNull(tracer.surfaceGuide());
                // The center of glass-inside sees only a boundary, which must reject history.
                if(preset.equals("glass-inside")) assertEquals(0,tracer.surfaceGuide().surface[32*64+32]);
            }
        }
        var s=plane();s.temporal(true);s.pathDepth(0);
        s.instances().addFirst(new SceneInstance("thin",List.of(new Rect(new Vec3(.035f,-1,4),new Vec3(0,2,0),new Vec3(.02f,0,0))),
                Transform.IDENTITY,new Material("red",new Vec3(1,0,0))));
        try(var tracer=new DirectRgbTracer(s)) {
            tracer.trace();var guide=tracer.surfaceGuide();
            assertEquals(0,guide.surface[32*64+32]); // Center ray hits a strip narrower than one pixel.
        }
        for(var kind:List.of(Material.Kind.MIRROR,Material.Kind.DIELECTRIC)) {
            s=plane();s.temporal(true);s.instances().clear();
            s.instances().add(new SceneInstance("solid",SceneInstance.box(),
                    new Transform(new Vec3(0,0,5),Vec3.ZERO,new Vec3(10,10,.1f)),new Material("solid",new Vec3(1,1,1),kind)));s.pathDepth(0);
            try(var tracer=new DirectRgbTracer(s)) {
                tracer.trace();for(int id:tracer.surfaceGuide().surface)assertEquals(0,id);
            }
        }
    }
    private static AsyncViewportTrace.Image await(AsyncViewportTrace async,ViewportState s) throws Exception {
        long deadline=System.nanoTime()+8_000_000_000L;
        while(System.nanoTime()<deadline) {
            synchronized(s) {
                async.request();var image=async.image();
                if(image!=null && image.key().equals(s.renderKey()) && image.temporalVersion()==s.temporalVersion()
                        && image.samples()>=s.effectiveTarget())return async.retain(image);
            }
            Thread.sleep(1);
        }
        throw new AssertionError("No complete matching temporal publication");
    }
    @Test void asyncTogglePauseLeaseAndSceneEditsPreserveRawOutput() throws Exception {
        var s=plane();s.sampleTarget(1);var cmd=new ViewportCommand(s);
        assertTrue(cmd.help("temporal").isSuccess());assertFalse(cmd.run("view","temporal","bad").isSuccess());
        assertFalse(cmd.run("view","temporal","on","extra").isSuccess());
        try(var async=new AsyncViewportTrace(s,new DirectRgbTracer(s))) {
            var raw=await(async,s);var initial=copy(raw.rgb());s.paused(true);
            assertTrue(cmd.run("view","temporal","on").isSuccess());var enabled=await(async,s);
            assertEquals(initial,enabled.rgb());assertEquals(1L,s.accumulatedSamples());assertNotNull(enabled.reconstructed());
            synchronized(s) {async.release(raw);async.release(enabled);s.paused(false);move(s,.02f);async.invalidate();}
            var resumed=await(async,s);synchronized(s){async.release(resumed);move(s,.02f);async.invalidate();}
            var reused=await(async,s);assertTrue(reused.history().reused()>0);var frozen=copy(reused.reconstructed());
            for(int i=0;i<4;i++) {
                synchronized(s){move(s,.01f);async.invalidate();}
                var next=await(async,s);synchronized(s){async.release(next);}
            }
            assertEquals(frozen,reused.reconstructed());
            synchronized(s){async.release(reused);s.restart();async.invalidate();}
            var reset=await(async,s);assertEquals(0,reset.history().reused());synchronized(s){async.release(reset);}
            // Off/on can happen before the coordinator sees either edit. An old completed publication is insufficient.
            long version=s.temporalVersion();s.temporal(false);s.temporal(true);
            var toggled=await(async,s);assertEquals(version+2,toggled.temporalVersion());assertEquals(0,toggled.history().reused());
            synchronized(s){async.release(toggled);}
            assertTrue(cmd.run("view","temporal","off").isSuccess());var off=await(async,s);
            assertNull(off.reconstructed());assertEquals(0L,off.history().bytes());assertEquals(1L,s.accumulatedSamples());
            synchronized(s){async.release(off);}
        }
    }
    public static void main(String[] args) { SuiteRunner.runThis(); }
}
