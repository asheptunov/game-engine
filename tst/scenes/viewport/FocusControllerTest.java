package scenes.viewport;

import harness.SuiteRunner;
import harness.Test;
import math.Vec3;
import scenes.viewport.objects.Rect;
import java.util.List;
import static harness.Assertions.*;
import static scenes.viewport.CameraOpticsTest.near;

public class FocusControllerTest {
    static final class Clock implements java.util.function.LongSupplier {
        long now=1_000_000_000L;
        public long getAsLong() {return now;}
        void advance(double ms) {now+=(long)(ms*1e6);}
    }
    record Fixture(ViewportState state,Clock clock,FocusController focus) {
        void advance(double ms) {clock.advance(ms);focus.tick();}
        void measure(String subject,float distance) {
            focus.accept(new CameraFocus.Measurement(focus.source(),new CameraFocus.Subject(subject,subject.intern()),null,distance,
                    clock.now,clock.now,focus.epoch(),CameraFocus.Scene.capture(state),state.camera(),null));
        }
        void miss() {
            focus.accept(new CameraFocus.Measurement(focus.source(),null,null,0,clock.now,clock.now,focus.epoch(),CameraFocus.Scene.capture(state),state.camera(),"miss"));
        }
    }
    static Fixture fixture() {
        var state=TemporalReconstructionTest.plane();var clock=new Clock();return new Fixture(state,clock,new FocusController(state,clock));
    }
    @Test void reciprocalCurvesEndpointsRetargetPoliciesAndNoOp() {
        for(String curve:new String[]{"linear","smooth"}) {
            var f=fixture();f.focus.curve(curve);f.focus.duration(1000);f.focus.manual(10,true);f.advance(250);
            double t=.25,c=curve.equals("linear")?t:t*t*(3-2*t);near(1/(.2+(.1-.2)*c),f.state.camera().focus(),1e-6);
            float realized=f.state.camera().focus();f.focus.manual(2,true);assertEquals(realized,f.state.camera().focus());
            f.advance(1000);assertEquals(2f,f.state.camera().focus());assertFalse(f.focus.pulling());
            var camera=f.state.camera();f.advance(1000);assertSame(camera,f.state.camera());
            f.focus.manual(10,true);f.advance(250);f.focus.manual(10,true);f.advance(750);assertEquals(10f,f.state.camera().focus());
            f.focus.manual(2,true);f.advance(250);float before=f.state.camera().focus();f.focus.duration(2000);assertEquals(before,f.state.camera().focus());
            f.advance(2000);assertEquals(2f,f.state.camera().focus());f.focus.duration(0);f.focus.manual(7,true);assertEquals(7f,f.state.camera().focus());
        }
    }
    @Test void suspensionExcludesTimeAndManualModeFreezes() {
        var f=fixture();f.focus.duration(1000);f.focus.manual(10,true);f.advance(250);float before=f.state.camera().focus();
        f.focus.suspend(true);f.advance(5000);assertEquals(before,f.state.camera().focus());
        f.focus.suspend(false);f.advance(750);assertEquals(10f,f.state.camera().focus());
        f.focus.manual(2,true);f.advance(200);f.focus.mode("manual");before=f.state.camera().focus();f.advance(3000);assertEquals(before,f.state.camera().focus());
        f.focus.manual(10,true);f.advance(100);f.state.paused(true);f.focus.tick();before=f.state.camera().focus();f.advance(3000);assertEquals(before,f.state.camera().focus());
        f.state.paused(false);f.focus.tick();f.advance(900);assertEquals(10f,f.state.camera().focus());
    }
    @Test void subjectDwellMovingDepthToleranceLossAndReacquisition() {
        var f=fixture();f.focus.duration(0);f.focus.mode("auto");f.measure("wall",10);f.advance(100);f.measure("wall",9);
        assertEquals(5f,f.state.camera().focus());f.advance(50);f.measure("wall",8);assertEquals(8f,f.state.camera().focus());
        f.measure("wall",8.04f);assertEquals(8f,f.focus.target());f.measure("wall",8.1f);assertEquals(8.1f,f.focus.target());
        f.measure("box",2);f.advance(100);f.measure("wall",8.1f);f.advance(100);f.measure("box",2);assertEquals(8.1f,f.state.camera().focus());
        f.advance(150);f.measure("box",3);assertEquals(3f,f.state.camera().focus());f.miss();assertTrue(f.focus.status().contains("lost"));
        f.measure("box",2);f.advance(149);f.measure("box",2);assertEquals(3f,f.state.camera().focus());
        f.advance(1);f.measure("box",2);assertEquals(2f,f.state.camera().focus());
        f.measure("box",Float.NaN);assertEquals(2f,f.state.camera().focus());assertTrue(f.focus.status().contains("lost"));
        f=fixture();f.focus.mode("auto");f.focus.delay(0);f.measure("already-focused",5);
        assertFalse(f.focus.pulling());assertTrue(f.focus.status().contains("settled"));
        f.focus.delay(150);f.measure("interruption",2);assertTrue(f.focus.status().contains("waiting"));
        f.measure("already-focused",5);assertTrue(f.focus.status().contains("settled"));
    }
    @Test void sourceAndEpochStalenessLagAndAlternateProvider() {
        var f=fixture();f.focus.mode("auto");f.focus.delay(0);f.focus.duration(0);
        var resolver=new CameraFocus();var scene=CameraFocus.Scene.capture(f.state);
        var measurement=resolver.resolve(f.focus.source(),f.state.camera(),scene,f.focus.epoch(),f.clock.now,f.clock);
        long epoch=f.focus.epoch();f.state.camera(f.state.camera().withAperture(.2f));assertEquals(epoch,f.focus.epoch());
        TemporalReconstructionTest.move(f.state,.01f);f.focus.accept(measurement);assertEquals(6f,f.state.camera().focus());
        TemporalReconstructionTest.move(f.state,1);f.focus.accept(measurement);assertEquals(6f,f.state.camera().focus());assertTrue(f.focus.status().contains("stale"));
        var fresh=resolver.resolve(f.focus.source(),f.state.camera(),scene,f.focus.epoch(),f.clock.now,f.clock);
        f.advance(101);f.focus.accept(fresh);assertEquals(6f,f.state.camera().focus());assertTrue(f.focus.status().contains("stale"));
        f.focus.source(new FocusTargetSource.Screen(.2f,.7f));f.focus.accept(measurement);assertEquals(6f,f.state.camera().focus());
        var source=f.focus.source();f.focus.source(source);assertSame(source,f.focus.source());
        f.advance(101);f.focus.accept(measurement);assertEquals(6f,f.state.camera().focus());
        record FutureSource(String name) implements FocusTargetSource {}
        f.focus.source(new FutureSource("timeline-test"));f.measure("alternate",4);assertEquals(4f,f.state.camera().focus());
        f.focus.mode("manual");f.measure("alternate",2);assertEquals(4f,f.state.camera().focus());
        for(float bad:new float[]{-.1f,1.1f,Float.NaN}) {
            try {new FocusTargetSource.Screen(bad,.5f);throw new AssertionError("coordinates");}catch(IllegalArgumentException expected) {}
        }
    }
    @Test void resolverAxialCoordinatesGlassIdentityCacheAndMeshBvh() {
        var s=TemporalReconstructionTest.plane();var resolver=new CameraFocus();var source=new FocusTargetSource.Screen(.8f,.2f);
        for(String projection:new String[]{"perspective","orthographic"}) {
            s.camera(s.camera().withProjection(projection).withAperture(.3f));var scene=CameraFocus.Scene.capture(s);
            var a=resolver.resolve(source,s.camera(),scene,0,0,()->0);assertNull(a.error());near(6,a.distance(),1e-5);
            var b=resolver.resolve(FocusTargetSource.CENTER,s.camera(),scene,0,0,()->0);assertEquals(a.subject(),b.subject());
            assertEquals(1L,resolver.preparations);s.resolution(80,100);
            assertEquals(a,resolver.resolve(source,s.camera(),scene,0,0,()->0));
        }
        ScenePresets.load(s,"glass");
        s.instances().add(new SceneInstance("first-glass",List.of(new scenes.viewport.objects.Sphere(new Vec3(0,0,2),.4f)),Transform.IDENTITY,
                new Material("first-glass",new Vec3(1,1,1)).withKind(Material.Kind.DIELECTRIC)));
        var glass=resolver.resolve(FocusTargetSource.CENTER,s.camera(),CameraFocus.Scene.capture(s),0,0,()->0);near(2.6,glass.distance(),1e-5);assertEquals("first-glass",glass.subject().name());
        ScenePresets.load(s,"mesh-room");var scene=CameraFocus.Scene.capture(s);
        for(int i=0;i<5;i++)resolver.resolve(new FocusTargetSource.Screen(.3f+i*.1f,.5f),s.camera(),scene,0,0,()->0);
        long prepared=resolver.preparations;resolver.resolve(FocusTargetSource.CENTER,s.camera(),scene,0,0,()->0);assertEquals(prepared,resolver.preparations);
        assertTrue(resolver.primitiveTests<5L*s.instances().stream().mapToInt(o->o.geometry().size()).sum());
        s.instances().clear();s.objects().clear();s.camera(new Camera(new Vec3(0,0,-1),new Rect(new Vec3(-.5f,-.5f,0),new Vec3(1,0,0),new Vec3(0,1,0))));
        s.instances().add(new SceneInstance("mesh",IndexedMesh.sphere(12),new Transform(new Vec3(0,0,5),Vec3.ZERO,new Vec3(2,2,2)),new Material("mesh",new Vec3(1,1,1))));
        scene=CameraFocus.Scene.capture(s);
        var faceA=resolver.resolve(new FocusTargetSource.Screen(.45f,.5f),s.camera(),scene,0,0,()->0);
        var faceB=resolver.resolve(new FocusTargetSource.Screen(.55f,.5f),s.camera(),scene,0,0,()->0);
        assertNull(faceA.error());assertNull(faceB.error());assertEquals(faceA.subject(),faceB.subject());
    }
    @Test void commandsCenterAliasesManualTakeoverAndFailures() {
        var s=TemporalReconstructionTest.plane();var command=new ViewportCommand(s);
        CameraOpticsTest.command(command,"view camera focus source screen 0.9 0.9");
        s.instances().add(new SceneInstance("foreground",List.of(new Rect(new Vec3(-.2f,-.2f,2),new Vec3(.4f,0,0),new Vec3(0,.4f,0))),
                Transform.IDENTITY,new Material("fg",new Vec3(1,1,1))));
        CameraOpticsTest.command(command,"view camera focus mode auto");CameraOpticsTest.command(command,"view camera focus center");
        assertEquals(3f,s.camera().focus());assertFalse(s.focusController().isAuto());
        CameraOpticsTest.command(command,"view camera focus transition duration 0");CameraOpticsTest.command(command,"view camera focus pull source");near(6,s.camera().focus(),1e-5);
        CameraOpticsTest.command(command,"view camera focus pull center");assertEquals(3f,s.camera().focus());
        CameraOpticsTest.command(command,"view camera focus mode auto");s.instances().clear();var before=s.camera();
        assertTrue(command.run("view","camera","focus","center").isFailure());assertEquals(before,s.camera());assertTrue(s.focusController().isAuto());
        for(String invalid:new String[]{"view camera focus transition duration NaN","view camera focus auto delay -1","view camera focus auto tolerance 26","view camera focus source screen .5 2"})
            assertTrue(command.run(invalid.split(" ")).isFailure());
        for(String[] path:new String[][]{{"camera","projection"},{"camera","focus","source"},{"camera","focus","pull","source"},{"camera","focus","transition","curve"}})
            assertTrue(command.help(path).isSuccess());
    }
    public static void main(String[] args) {SuiteRunner.runThis();}
}
