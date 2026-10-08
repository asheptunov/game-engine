package engine;

import engine.lights.PointLight;
import engine.lights.Light;
import engine.objects.Rect;
import engine.objects.Sphere;
import harness.SuiteRunner;
import harness.Test;
import math.Vec3;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static harness.Assertions.*;

public class EngineSessionTest {
    private static final Camera CAMERA=new Camera(new Vec3(0,0,-1),
            new Rect(new Vec3(-.5f,-.5f,0),new Vec3(1,0,0),new Vec3(0,1,0)));

    private static WorldSnapshot world(long revision,float x) {
        return new WorldSnapshot(revision,List.of(
                new SceneInstance("sphere",new AnalyticSphere(Vec3.ZERO,1),
                        new Transform(new Vec3(x,0,4),Vec3.ZERO,new Vec3(1,1,1)),
                        Material.srgb("blue",0x3b82f6)),
                new SceneInstance("floor",PolygonMesh.parallelogram(new Vec3(-4,-1,1),new Vec3(0,0,8),new Vec3(8,0,0)),
                        Transform.IDENTITY,Material.srgb("floor",0xb8c0cc))),
                List.of(),List.of(new PointLight(new Vec3(-2,3,-1),new Vec3(1,1,1),80)));
    }

    private static RenderImage await(RenderSession session,long samples) throws Exception {
        long deadline=System.nanoTime()+15_000_000_000L;
        while(System.nanoTime()<deadline) {
            session.request();
            var image=session.acquireImage();
            if(image!=null) {
                var progress=session.progress(image);
                if(image.generation()==progress.requestedGeneration() && image.samples()>=samples)return image;
                image.close();
            }
            Thread.sleep(1);
        }
        throw new AssertionError("No completed current-generation image");
    }

    @Test void immutableInputsAndFailedValidationCannotPartiallyReplaceSession() throws Exception {
        var instances=new ArrayList<>(world(0,0).instances());
        var copied=new WorldSnapshot(0,instances,List.of(),world(0,0).lights());
        instances.clear();assertEquals(2,copied.instances().size());
        Light custom=(count,random)->List.of();boolean customRejected=false;
        try {new WorldSnapshot(0,List.of(),List.of(),List.of(custom));}
        catch(IllegalArgumentException expected){customRejected=true;}
        assertTrue(customRejected);
        var settings=RenderSettings.defaults().withWorkers(1).withSampleTarget(1);
        try(var session=RenderEngine.openSession(copied,new RenderView(CAMERA,64,64),settings)) {
            float[][][] before;
            try(var image=await(session,1)){before=copy(image.rawPixels());}
            boolean rejected=false;
            try {
                var invalid=new Camera(CAMERA.eye(),CAMERA.sensor(),Camera.Projection.PERSPECTIVE,
                        Camera.Mode.PERSPECTIVE,-1,0,0,0);
                session.update(world(1,.5f),new RenderView(invalid,64,64),settings);
            } catch(IllegalArgumentException expected){rejected=true;}
            assertTrue(rejected);
            try(var image=await(session,1)){assertEquals(before,copy(image.rawPixels()));}
        }
    }

    @Test void settingsApplyTemporalRevisionAndResetInteractiveMinimum() {
        var base=RenderSettings.defaults().withWorkers(1).withInteractive(true,20,128,96)
                .withTemporal(true,4);
        var session=(DefaultRenderSession)RenderEngine.openSession(world(0,0),new RenderView(CAMERA,256,192),base);
        try(session) {
            assertEquals(9L,session.stateForTests().temporalVersion());
            assertTrue(session.stateForTests().interactiveStatus().contains("min=128x96"));
            var defaults=base.withInteractive(true,20,0,0).withTemporal(true,5);
            session.update(world(0,0),new RenderView(CAMERA,256,192),defaults);
            assertEquals(11L,session.stateForTests().temporalVersion());
            assertTrue(session.stateForTests().interactiveStatus().contains("min=85x64"));
            boolean mixedRejected=false;
            try {base.withInteractive(true,20,0,96);} catch(IllegalArgumentException expected){mixedRejected=true;}
            assertTrue(mixedRejected);
        }
    }

    @Test void twoSessionsKeepResolutionAccumulationAndLeasesIndependentWhileSharingWorld() throws Exception {
        var initial=world(0,0);var edited=world(1,.5f);
        var aSettings=RenderSettings.defaults().withWorkers(1).withSamplesPerBatch(1).withSampleTarget(2);
        var bSettings=RenderSettings.defaults().withWorkers(1).withSampleTarget(1);
        try(var a=RenderEngine.openSession(initial,new RenderView(CAMERA,64,64),aSettings);
            var b=RenderEngine.openSession(initial,new RenderView(CAMERA.withFov(70),80,64),bSettings)) {
            try(var aImage=await(a,2);var bImage=await(b,1)) {
                assertEquals(64,aImage.width());assertEquals(80,bImage.width());
                assertEquals(2L,aImage.samples());assertEquals(1L,bImage.samples());
                var retained=copy(aImage.rawPixels());
                b.update(edited,new RenderView(CAMERA.withFov(70),80,64),bSettings);
                try(var changed=await(b,1)){assertEquals(80,changed.width());}
                assertEquals(retained,copy(aImage.rawPixels()));
            }
            a.update(edited,new RenderView(CAMERA,64,64),aSettings);
            try(var changed=await(a,2)){assertEquals(2L,changed.samples());}
            var lease=await(a,2);lease.close();
            boolean rejected=false;try{lease.rawPixels();}catch(IllegalStateException expected){rejected=true;}
            assertTrue(rejected);
        }
    }

    @Test void fixedUpdatesRunDuringPendingTraceAndFinalPublicationMatchesFreshSession() throws Exception {
        var many=new ArrayList<SceneInstance>();
        for(int i=0;i<48;i++)many.add(new SceneInstance("sphere-"+i,new AnalyticSphere(Vec3.ZERO,.35f),
                new Transform(new Vec3((i%8-3.5f)*.6f,(i/8-2.5f)*.6f,4+(i%3)*.3f),Vec3.ZERO,new Vec3(1,1,1)),
                Material.srgb("gray",0x8899aa)));
        var heavy=new WorldSnapshot(0,many,List.of(),List.of(new PointLight(new Vec3(-2,3,-1),new Vec3(1,1,1),80)));
        var heavyView=new RenderView(CAMERA,600,600);
        var heavySettings=RenderSettings.defaults().withWorkers(1).withTileSize(8).withPathDepth(4).withSampleTarget(1);
        try(var session=RenderEngine.openSession(heavy,heavyView,heavySettings)) {
            session.request();assertTrue(session.progress(null).running());
            var ticks=new CountDownLatch(5);var ranWhileTracing=new AtomicBoolean();
            try(var loop=new FixedStepLoop(Duration.ofMillis(2),tick->{
                if(tick<=5) {
                    ranWhileTracing.compareAndSet(false,session.progress(null).running());
                    session.update(world(tick,tick*.05f),heavyView,heavySettings);
                    ticks.countDown();
                }
            })) {
                assertTrue(ticks.await(2,TimeUnit.SECONDS));
                assertNull(loop.failure());
            }
            assertTrue(ranWhileTracing.get());
            var finalWorld=world(6,.3f);
            var finalView=new RenderView(CAMERA,64,64);
            var finalSettings=RenderSettings.defaults().withWorkers(1).withSampleTarget(1).withSeed(91);
            session.update(finalWorld,finalView,finalSettings);
            try(var actual=await(session,1);
                var reference=RenderEngine.openSession(finalWorld,finalView,finalSettings);
                var expected=await(reference,1)) {
                assertEquals(copy(expected.rawPixels()),copy(actual.rawPixels()));
            }
        }
    }

    @Test void progressReportsExactActiveGenerationAcrossRapidCameraRequests() {
        var settings=RenderSettings.defaults().withWorkers(1).withTileSize(8).withPathDepth(8).withSampleTarget(1);
        try(var session=RenderEngine.openSession(world(0,0),new RenderView(CAMERA,1200,1200),settings)) {
            session.request();var first=session.progress(null);assertTrue(first.running());
            long active=first.activeGeneration();assertEquals(first.requestedGeneration(),active);
            int advanced=0;
            for(int i=0;i<24;i++) {
                session.update(world(0,0),new RenderView(CAMERA.withFov(45+i),1200,1200),settings);session.request();
                var progress=session.progress(null);
                if(!progress.running())break;
                assertEquals(active,progress.activeGeneration());
                if(progress.requestedGeneration()>active)advanced++;
            }
            assertTrue(advanced>16);
        }
    }

    @Test void semanticRepublicationRetainsSamplesButNewRevisionInvalidates() throws Exception {
        var view=new RenderView(CAMERA,64,64);
        var settings=RenderSettings.defaults().withWorkers(1).withSamplesPerBatch(1).withSampleTarget(3);
        try(var session=RenderEngine.openSession(world(0,0),view,settings)) {
            try(var first=await(session,1)){assertEquals(1L,first.samples());}
            session.update(world(0,0),view,settings);
            try(var continued=await(session,2)){assertEquals(2L,continued.samples());}
            session.update(world(1,0),view,settings);
            try(var restarted=await(session,1)){assertEquals(1L,restarted.samples());}
        }
    }

    @Test void temporalHistoryBelongsToEachSession() throws Exception {
        var wall=new WorldSnapshot(0,List.of(new SceneInstance("wall",PolygonMesh.parallelogram(
                new Vec3(-10,-10,5),new Vec3(0,20,0),new Vec3(20,0,0)),
                Transform.IDENTITY,new Material("wall",new Vec3(.5f,.5f,.5f)))),List.of(),
                List.of(new PointLight(new Vec3(0,3,0),new Vec3(1,1,1),50)));
        var initial=new RenderView(CAMERA,64,64);
        var settings=RenderSettings.defaults().withWorkers(1).withPathDepth(2)
                .withSampleTarget(1).withTemporal(true,1);
        try(var a=RenderEngine.openSession(wall,initial,settings);
            var b=RenderEngine.openSession(wall,initial,settings)) {
            try(var firstA=await(a,1);var firstB=await(b,1)) {
                assertTrue(firstA.historyLabel().contains("new history"));
                assertTrue(firstB.historyLabel().contains("new history"));
            }
            var delta=new Vec3(.08f,0,0);var sensor=CAMERA.sensor();
            var movedCamera=CAMERA.withPose(CAMERA.eye().add(delta),
                    new Rect(sensor.origin().add(delta),sensor.edge1(),sensor.edge2()));
            a.update(wall,new RenderView(movedCamera,64,64),settings);
            try(var movedA=await(a,1);var unchangedB=b.acquireImage()) {
                assertTrue(movedA.historyLabel().contains("diffuse"));
                assertNotNull(unchangedB);assertTrue(unchangedB.historyLabel().contains("new history"));
                assertTrue(movedA.generation()>unchangedB.generation());
            }
        }
    }

    private static float[][][] copy(RgbPixels source) {
        var result=new float[3][source.height()][source.width()];
        source.copyTo(result);
        return result;
    }

    public static void main(String[] args){SuiteRunner.runThis();}
}
