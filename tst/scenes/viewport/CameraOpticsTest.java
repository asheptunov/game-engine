package scenes.viewport;

import harness.SuiteRunner;
import harness.Test;
import math.Vec3;
import math.Ray;
import scenes.viewport.objects.*;
import java.util.List;
import static harness.Assertions.*;

public class CameraOpticsTest {
    static void near(double expected,double actual,double error) {
        if(Math.abs(expected-actual)>error)throw new AssertionError(expected+" != "+actual);
    }
    static void command(ViewportCommand command,String text) {
        var result=command.run(text.split(" "));if(result.isFailure())throw new AssertionError(result.getFailure());
    }
    private static Camera.RaySample ray(Camera camera,float u,float v,float a,float b) {
        var ray=new Camera.RaySample();camera.compile().sample(u,v,a,b,ray);return ray;
    }
    @Test void independentProjectionApertureAndShortcutMemory() {
        var s=TemporalReconstructionTest.plane();var c=new ViewportCommand(s);var pinhole=s.renderKey();
        command(c,"view camera aperture .3");assertNotEquals(pinhole,s.renderKey());
        command(c,"view camera projection orthographic");assertEquals(.3f,s.camera().aperture());assertFalse(s.camera().temporalSupported());
        float height=s.camera().height();command(c,"view camera focus distance 11");assertEquals(height,s.camera().height());
        command(c,"view camera projection perspective");assertEquals(.3f,s.camera().aperture());
        command(c,"view camera mode orthographic");assertEquals(0f,s.camera().aperture());assertEquals(.3f,s.camera().rememberedAperture());
        command(c,"view camera mode lens");assertEquals(.3f,s.camera().aperture());
        command(c,"view camera aperture 0");command(c,"view camera mode lens");assertEquals(pinhole,s.renderKey());
        command(c,"view camera projection orthographic");var key=s.renderKey();long version=s.cameraHistoryVersion();
        command(c,"view camera aperture 0");command(c,"view camera projection orthographic");command(c,"view camera focus distance 6");
        assertEquals(key,s.renderKey());assertEquals(version,s.cameraHistoryVersion());
    }
    @Test void translatedBundlesAnalyticBlurNoBreathingAndDiskCoverage() {
        var c=TemporalReconstructionTest.plane().camera().withMode("orthographic").withHeight(4).withFocus(7).withAperture(.4f);
        var a=ray(c,.2f,.3f,1,0);var b=ray(c,.8f,.9f,1,0);
        assertEquals(a.dx,b.dx);assertEquals(a.dy,b.dy);assertEquals(a.dz,b.dz);
        near(2.4,b.ox-a.ox,1e-6); // Would fail for a single common pupil.
        for(float u:new float[]{.1f,.5f,.9f})for(float v:new float[]{.1f,.8f}) {
            var reference=ray(c.withAperture(0),u,v,0,0);
            for(float z:new float[]{2,7,13})for(float angle:new float[]{0,.125f,.4f,.7f}) {
                var r=ray(c,u,v,1,angle);float t=(c.eye().z()+z-r.oz)/r.dz;
                double dx=r.ox+r.dx*t-reference.ox,dy=r.oy+r.dy*t-reference.oy;
                near(.4*Math.abs(1-z/7.0),Math.hypot(dx,dy),2e-6);
            }
        }
        var random=new java.util.Random(1);double x=0,y=0,r2=0;
        for(int i=0;i<20000;i++) {
            var r=ray(c,.5f,.5f,random.nextFloat(),random.nextFloat());
            x+=r.ox;y+=r.oy;r2+=r.ox*r.ox+r.oy*r.oy;
        }
        near(0,x/20000,.005);near(0,y/20000,.005);near(.08,r2/20000,.002);
        for(float focus:new float[]{2,7,12}) {
            var d=c.withFocus(focus);assertEquals(c.imagePlane(),d.imagePlane());
            // Symmetric pupil samples keep mean x/y at the reference origin at every depth.
            for(float z:new float[]{1,4,12}) {
                var p=ray(d,.8f,.6f,1,0);var q=ray(d,.8f,.6f,1,.5f);var r=ray(d.withAperture(0),.8f,.6f,0,0);
                near(r.ox,(p.ox+p.dx*z/p.dz+q.ox+q.dx*z/q.dz)/2,2e-6);
            }
        }
    }
    @Test void orthographicApertureExposureDeterminismAndNestedMedia() {
        var s=TemporalReconstructionTest.plane();s.camera(s.camera().withMode("orthographic").withHeight(2));
        s.instances().clear();s.lights().clear();s.pathDepth(0);s.sampleTarget(3);
        s.instances().add(new SceneInstance("light",List.of(new Rect(new Vec3(-20,-20,5),new Vec3(0,40,0),new Vec3(40,0,0))),
                Transform.IDENTITY,new Material("light",Vec3.ZERO).withEmission(new Vec3(2,2,2))));
        var reference=completed(s);s.camera(s.camera().withAperture(.8f));assertEquals(reference,completed(s));
        s.pathDepth(8);s.sampleTarget(1);
        s.instances().add(new SceneInstance("outer",List.of(new Sphere(new Vec3(0,0,-1),.8f)),Transform.IDENTITY,
                new Material("outer",new Vec3(1,1,1),Material.Kind.DIELECTRIC,1,new Vec3(1,1,1))));
        s.instances().add(new SceneInstance("inner",List.of(new Sphere(new Vec3(0,0,-1),.3f)),Transform.IDENTITY,
                new Material("inner",new Vec3(1,1,1),Material.Kind.DIELECTRIC,1,new Vec3(2,.5f,1))));
        try(var tracer=new DirectRgbTracer(s)) {
            var image=TemporalReconstructionTest.copy(tracer.trace());var sampler=new DirectRgbTracer.Sampler();var aperture=new DirectRgbTracer.Sampler();
            long seed=DirectRgbTracer.cameraSeed(s.seed(),s.camera().identity());int inside=0,outside=0;
            for(int y:new int[]{4,20,32,50})for(int x:new int[]{4,20,32,50}) {
                long pixel=y*64+x;sampler.reset(seed,pixel,0);aperture.reset(seed^0x4150455254555245L,pixel,0);
                var r=ray(s.camera(),(x+sampler.next())/64,(y+sampler.next())/64,aperture.next(),aperture.next());
                if(r.ox*r.ox+r.oy*r.oy<.64f)inside++;else outside++;
                var exact=tracer.radiance(new Ray(new Vec3(r.ox,r.oy,r.oz),new Vec3(r.dx,r.dy,r.dz)),0);
                for(int channel=0;channel<3;channel++)near(exact[channel],image[channel][y][x],2e-5);
            }
            assertTrue(inside>0);assertTrue(outside>0);
        }
        s.sampleTarget(3);var serial=completed(s);s.workers(Math.min(4,Runtime.getRuntime().availableProcessors()));s.tileSize(8);s.samplesPerFrame(3);
        assertEquals(serial,completed(s));
        var snapshot=s.renderSnapshot();snapshot.accumulatedSamples(0);var calls=new java.util.concurrent.atomic.AtomicInteger();
        try(var t=new DirectRgbTracer(snapshot)) {
            t.trace(snapshot,()->calls.incrementAndGet()>5);assertTrue(t.cancelled);
            do {t.trace();}while(snapshot.accumulatedSamples()<3);assertEquals(serial,t.radianceBuffer());
        }
    }
    private static float[][][] completed(ViewportState state) {
        try(var tracer=new DirectRgbTracer(state)) {do {tracer.trace();}while(state.accumulatedSamples()<state.sampleTarget());return TemporalReconstructionTest.copy(tracer.radianceBuffer());}
    }
    public static void main(String[] args) {SuiteRunner.runThis();}
}
