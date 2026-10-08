package engine;

import scenes.viewport.*;
import harness.Test;
import harness.SuiteRunner;
import math.Vec3;
import math.Ray;
import engine.objects.*;
import engine.lights.PointLight;
import java.util.List;
import static harness.Assertions.*;

public class VolumePathTest {
    static final Vec3 WHITE=new Vec3(1,1,1);
    static ViewportState state(int size) {return new ViewportState(new Rect(new Vec3(-.5f,-.5f,0),new Vec3(1,0,0),new Vec3(0,1,0)),size,size);}
    static void close(double expected,double actual,double tolerance) {
        if(Double.compare(expected,actual)==0)return;
        if(!Double.isFinite(actual) || Math.abs(expected-actual)>tolerance)throw new AssertionError(expected+" != "+actual);
    }
    static Material medium(float density) {return new Material("medium",WHITE,Material.Kind.DIELECTRIC).withIor(1).withScattering(density);}
    static void sphere(ViewportState st,float radius,Material m) {st.instances().add(new SceneInstance(m.name(),new AnalyticSphere(Vec3.ZERO,radius),Transform.IDENTITY,m));}
    @Test void exponentialAndPhaseStatistics() {
        var rng=new DirectRgbTracer.Sampler();rng.reset(7,0,0);var sample=new Material.Sample();
        int survive=0;double length=0;
        for(int i=0;i<100000;i++){float d=Volume.distance(2,rng.next());length+=d;if(d>1)survive++;}
        close(.5,length/100000,.005);close(Math.exp(-2),survive/100000.,.003);
        close(Float.POSITIVE_INFINITY,Volume.distance(0,.4f),0);
        for(float g:new float[]{-.8f,0,.8f}) {
            double cosine=0,integral=0;
            for(int i=0;i<100000;i++) {
                Volume.sample(0,0,1,g,rng.next(),rng.next(),sample);cosine+=sample.dz;
                close(1,sample.dx*sample.dx+sample.dy*sample.dy+sample.dz*sample.dz,1e-5);
                close(Volume.phase(sample.dz,g),sample.probability,1e-6);
                integral+=Volume.phase(-1+2*(i+.5f)/100000,g)*4*Math.PI/100000;
            }
            close(g,cosine/100000,.006);close(1,integral,.0001);
        }
    }
    @Test void visibilityIntegratesNestedExtinctionAndBlocksRefractiveOrOpaqueObjects() {
        var st=state(1);sphere(st,2,medium(.5f).withAbsorption(new Vec3(.2f,.3f,.4f)));
        sphere(st,1,new Material("inner",WHITE,Material.Kind.DIELECTRIC).withIor(1).withScattering(1));
        var tracer=new DirectRgbTracer(st);tracer.intersect(new Ray(new Vec3(0,0,-4),new Vec3(0,0,1)));
        assertTrue(tracer.volumeVisibility(0,0,-3,0,0,1,6,null));
        var tr=tracer.visibilityTransmission();
        close(Math.exp(-3.4),tr[0],.0001);close(Math.exp(-3.6),tr[1],.0001);close(Math.exp(-3.8),tr[2],.0001);
        assertTrue(tracer.volumeVisibility(0,0,0,0,0,1,3,null));
        close(Math.exp(-1.7),tracer.visibilityTransmission()[0],.0001);
        st.instances().set(1,st.instances().get(1).withMaterial(st.instances().get(1).material().withIor(1.5f)));
        tracer.intersect(new Ray(new Vec3(0,0,-4),new Vec3(0,0,1)));
        assertTrue(!tracer.volumeVisibility(0,0,-3,0,0,1,6,null));
        st.instances().set(1,st.instances().get(1).withMaterial(new Material("opaque",WHITE)));
        tracer.intersect(new Ray(new Vec3(0,0,-4),new Vec3(0,0,1)));
        assertTrue(!tracer.volumeVisibility(0,0,-3,0,0,1,6,null));
    }
    @Test void singleScatteringMatchesIndependentIntegralAndFinalVertexLighting() {
        var st=state(1);st.pathDepth(0);sphere(st,1,medium(1).withAbsorption(new Vec3(.2f,.2f,.2f)));
        st.addLight(new PointLight(new Vec3(0,0,-2),WHITE,10));
        var tracer=new DirectRgbTracer(st);var ray=new Ray(Vec3.ZERO,new Vec3(0,0,1));
        double average=0;int count=100000;
        for(int i=0;i<count;i++)average+=tracer.radiance(ray,i)[0]/count;
        // Camera distance s in [0,1]; light distance s+2, medium light segment s+1.
        double expected=0;int steps=10000;
        for(int i=0;i<steps;i++){double s=(i+.5)/steps;expected+=Math.exp(-1.2*s)*10/(4*Math.PI*(s+2)*(s+2))*Math.exp(-1.2*(s+1))/steps;}
        close(expected,average,expected*.015);
        assertTrue(tracer.continuationRays==0);
        System.out.printf("Volume single scatter: numeric %.8f sampled %.8f%n",expected,average);
    }
    @Test void surfaceLightCrossesCloudWithFullExtinction() {
        var st=state(1);sphere(st,1,medium(.5f).withAbsorption(new Vec3(.1f,.2f,.3f)));
        st.instances().add(new SceneInstance("receiver",PolygonMesh.parallelogram(new Vec3(-5,-5,3),new Vec3(0,10,0),new Vec3(10,0,0)),Transform.IDENTITY,new Material("white",WHITE)));
        st.addLight(new PointLight(new Vec3(0,0,-3),WHITE,36*(float)Math.PI));
        var tracer=new DirectRgbTracer(st);var ray=new Ray(new Vec3(0,0,2.5f),new Vec3(0,0,1));var rgb=tracer.radiance(ray,0);
        close(Math.exp(-1.2),rgb[0],.0001);close(Math.exp(-1.4),rgb[1],.0001);close(Math.exp(-1.6),rgb[2],.0001);
        assertTrue(tracer.volumeVisibilitySegments>0);
        st.acceleration(false);assertEquals(rgb,tracer.radiance(ray,0));
    }
    @Test void zeroScatteringKeepsBeerAndNoSampledEvents() {
        var st=state(1);st.pathDepth(2);sphere(st,1,medium(0).withAbsorption(new Vec3(.2f,.5f,1)));
        st.instances().add(new SceneInstance("emitter",PolygonMesh.parallelogram(new Vec3(-5,-5,3),new Vec3(0,10,0),new Vec3(10,0,0)),Transform.IDENTITY,new Material("light",Vec3.ZERO).withEmission(WHITE)));
        var tracer=new DirectRgbTracer(st);var ray=new Ray(new Vec3(0,0,-3),new Vec3(0,0,1));var rgb=tracer.radiance(ray,0);
        close(Math.exp(-.2*1.999),rgb[0],1e-5);close(Math.exp(-.5*1.999),rgb[1],1e-5);close(Math.exp(-1.999),rgb[2],1e-5);
        assertEquals(0L,tracer.volumeEvents);assertEquals(0L,tracer.volumeSegments);
    }
    @Test void uniformEmissionEnclosureConservesEnergyWithoutCountingEmitterTwice() {
        var st=state(1);st.pathDepth(32);sphere(st,1,medium(1));
        Vec3[] origins={new Vec3(-3,-3,-3),new Vec3(3,-3,-3),new Vec3(-3,-3,-3),new Vec3(-3,3,-3),new Vec3(-3,-3,-3),new Vec3(-3,-3,3)};
        Vec3[] a={new Vec3(0,6,0),new Vec3(0,0,6),new Vec3(0,0,6),new Vec3(6,0,0),new Vec3(6,0,0),new Vec3(0,6,0)};
        Vec3[] b={new Vec3(0,0,6),new Vec3(0,6,0),new Vec3(6,0,0),new Vec3(0,0,6),new Vec3(0,6,0),new Vec3(6,0,0)};
        for(int i=0;i<6;i++)st.instances().add(new SceneInstance("light"+i,PolygonMesh.parallelogram(origins[i],a[i],b[i]),Transform.IDENTITY,new Material("light"+i,Vec3.ZERO).withEmission(WHITE)));
        var tracer=new DirectRgbTracer(st);var ray=new Ray(Vec3.ZERO,new Vec3(0,0,1));double sum=0;
        for(int i=0;i<100000;i++)sum+=tracer.radiance(ray,i)[0]/100000;
        close(1,sum,.015);System.out.printf("Volume white enclosure: %.6f%n",sum);
    }
    @Test void controlsAreAtomicAndInvalidateSamples() {
        var st=state(64);ScenePresets.load(st,"volume-room");var cmd=new ViewportCommand(st);var tracer=new DirectRgbTracer(st);
        assertTrue(cmd.run("view","select","cloudy-sphere").isSuccess());
        tracer.trace();tracer.trace();assertEquals(2L,st.accumulatedSamples());
        assertTrue(cmd.run("view","scattering",".8").isSuccess());tracer.trace();assertEquals(1L,st.accumulatedSamples());
        assertTrue(cmd.run("view","anisotropy",".7").isSuccess());tracer.trace();assertEquals(1L,st.accumulatedSamples());
        cmd.run("view","exposure","1");tracer.trace();assertEquals(2L,st.accumulatedSamples());
        var before=List.copyOf(st.instances());assertTrue(cmd.run("view","scattering","NaN").isFailure());assertTrue(cmd.run("view","scattering","101").isFailure());assertTrue(cmd.run("view","anisotropy","1").isFailure());assertEquals(before,st.instances());
        assertTrue(tracer.profile.volumeEvents()>0 && tracer.profile.volumeVisibilitySegments()>0);
        cmd.run("view","preset","mesh-room");cmd.run("view","select","mesh-clear");assertTrue(cmd.run("view","scattering","1").isFailure());
    }
    static float[][][] copy(float[][][] a){var b=new float[3][][];for(int c=0;c<3;c++){b[c]=new float[a[c].length][];for(int y=0;y<a[c].length;y++)b[c][y]=a[c][y].clone();}return b;}
    static double error(float[][][] a,float[][][] b){double e=0;for(int c=0;c<3;c++)for(int y=0;y<a[c].length;y++)for(int x=0;x<a[c][y].length;x++){double d=a[c][y][x]-b[c][y][x];e+=d*d;}return e;}
    static void image(float[][][] a,String name)throws Exception{int n=a[0].length;var image=new java.awt.image.BufferedImage(n,n,java.awt.image.BufferedImage.TYPE_INT_RGB);for(int y=0;y<n;y++)for(int x=0;x<n;x++){int rgb=0;for(int c=0;c<3;c++)rgb=(rgb<<8)|Byte.toUnsignedInt(DisplayMapping.encode(a[c][n-1-y][x],1));image.setRGB(x,y,rgb);}javax.imageio.ImageIO.write(image,"png",new java.io.File("out/cli/"+name));}
    @Test void seededBatchesConvergeAndPreview() throws Exception {
        var a=state(64);var b=state(64);ScenePresets.load(a,"volume-room");ScenePresets.load(b,"volume-room");
        a.sampleTarget(1);b.sampleTarget(32);b.samplesPerFrame(8);var ta=new DirectRgbTracer(a);var tb=new DirectRgbTracer(b);
        var first=copy(ta.trace());a.sampleTarget(32);while(a.accumulatedSamples()<32)ta.trace();while(b.accumulatedSamples()<32)tb.trace();
        var converged=copy(ta.trace());assertEquals(converged,tb.trace());a.sampleTarget(256);while(a.accumulatedSamples()<256)ta.trace();
        assertTrue(error(converged,ta.trace())<error(first,ta.trace())*.25);
        var preview=state(200);ScenePresets.load(preview,"volume-room");preview.sampleTarget(128);preview.samplesPerFrame(8);var tracer=new DirectRgbTracer(preview);
        while(preview.accumulatedSamples()<128)tracer.trace();image(tracer.trace(),"volume-room-preview-128.png");
        var cloudy=copy(tracer.trace());preview.instances().replaceAll(o->o.withMaterial(o.material().withScattering(0)));
        do{tracer.trace();}while(preview.accumulatedSamples()<128);
        assertTrue(error(cloudy,tracer.trace())>10);image(tracer.trace(),"volume-room-clear-128.png");
    }
    public static void main(String[] args){SuiteRunner.runThis();}
}
