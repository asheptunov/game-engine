package engine;

import scenes.viewport.*;
import harness.SuiteRunner;
import harness.Test;
import math.Ray;
import math.Vec3;
import engine.objects.*;
import java.util.List;
import static harness.Assertions.*;

public class RoughLightingTest {
    private static final Vec3 WHITE=new Vec3(1,1,1);
    private static ViewportState state(int size) {return new ViewportState(new Rect(new Vec3(-.5f,-.5f,0),new Vec3(1,0,0),new Vec3(0,1,0)),size,size);}
    private static void close(double expected,double actual,double tolerance) {
        if(!Double.isFinite(actual) || Math.abs(expected-actual)>tolerance)throw new AssertionError(expected+" != "+actual+" (tol "+tolerance+")");
    }
    private static void add(ViewportState st,String name,RenderPrimitive shape,Material m) {st.instances().add(new SceneInstance(name,PreparedGeometry.canonical(shape),Transform.IDENTITY,m));}
    private static double average(DirectRgbTracer tracer,Ray ray,int samples) {double sum=0;for(int i=0;i<samples;i++)sum+=tracer.radiance(ray,i)[0];return sum/samples;}
    @Test void smoothLimitAndEqualIorRetainDeltaBehavior() {
        var out=new Material.Sample();var expected=new Material.Sample();
        for(var kind:new Material.Kind[]{Material.Kind.MIRROR,Material.Kind.DIELECTRIC}) {
            var m=new Material("m",WHITE,kind);
            for(float branch:new float[]{.01f,.8f}) {
                m.scatter(.6f,0,-.8f,0,0,1,1,1.5f,.2f,.7f,branch,out);
                if(kind==Material.Kind.MIRROR)m.sample(.6f,0,-.8f,0,0,1,.2f,.7f,expected);
                else m.sampleDielectric(.6f,0,-.8f,0,0,1,1,1.5f,branch,expected);
                close(expected.dx,out.dx,0);close(expected.dz,out.dz,0);close(expected.red,out.red,0);assertTrue(out.delta);
            }
        }
        new Material("glass",WHITE,Material.Kind.DIELECTRIC).withRoughness(.8f).scatter(.6f,0,-.8f,0,0,1,1.5f,1.5f,.4f,.2f,.8f,out);
        assertTrue(out.delta && out.transmitted);close(.6,out.dx,1e-6);close(-.8,out.dz,1e-6);close(1,out.red,0);
    }
    @Test void ggxSamplingMatchesIndependentSphereIntegrationAndIsEnergyBounded() {
        for(var kind:new Material.Kind[]{Material.Kind.MIRROR,Material.Kind.DIELECTRIC}) {
            for(boolean inside:new boolean[]{false,true}) {
                var m=new Material("m",WHITE,kind).withRoughness(.7f);var out=new Material.Sample();
                var rng=new java.util.Random(819);int count=250000;
                float incident=inside?1.5f:1,exit=inside?1:1.5f,eta=incident/exit;
                double sampled=0,integrated=0,accepted=0,pdfMass=0;
                // Incoming direction at 37 degrees: integration uses uniform solid angle,
                // entirely independent of the sampled GGX microfacet distribution/Jacobian.
                for(int i=0;i<count;i++) {
                    m.scatter(.6f,0,-.8f,0,0,1,incident,exit,rng.nextFloat(),rng.nextFloat(),rng.nextFloat(),out);
                    if(out.red>0) {close(1,out.dx*out.dx+out.dy*out.dy+out.dz*out.dz,2e-5);accepted++;}
                    sampled+=out.red/(out.transmitted?eta*eta:1);
                    float z=2*rng.nextFloat()-1,phi=(float)(2*Math.PI*rng.nextFloat()),r=(float)Math.sqrt(1-z*z);
                    m.evaluate(.6f,0,-.8f,r*(float)Math.cos(phi),r*(float)Math.sin(phi),z,0,0,1,incident,exit,out);
                    integrated+=out.red*4*Math.PI/(z<0?eta*eta:1);pdfMass+=out.probability*4*Math.PI;
                }
                sampled/=count;integrated/=count;
                System.out.printf("GGX %s inside=%s: energy sampled %.5f integral %.5f; PDF mass %.5f accepted %.5f%n",kind,inside,sampled,integrated,pdfMass/count,accepted/count);
                close(integrated,sampled,.025);close(pdfMass/count,accepted/count,.025);
                assertTrue(sampled>0 && sampled<=1.002);
            }
        }
    }
    @Test void polishedMicrofacetsRemainFiniteAndConvergeToSmoothDirections() {
        var out=new Material.Sample();var rng=new java.util.Random(18);
        for(var kind:new Material.Kind[]{Material.Kind.MIRROR,Material.Kind.DIELECTRIC}) {
            var m=new Material("m",WHITE,kind).withRoughness(.001f);double error=0;
            for(int i=0;i<10000;i++) {
                m.scatter(0,0,-1,0,0,1,1,1.5f,rng.nextFloat(),rng.nextFloat(),rng.nextFloat(),out);
                assertTrue(Float.isFinite(out.red) && out.red>=0 && Float.isFinite(out.probability));
                error+=out.dx*out.dx+out.dy*out.dy;
            }
            assertTrue(error/10000<.0001);
        }
    }
    private static ViewportState litPlane() {
        var st=state(1);
        add(st,"target",new Rect(new Vec3(-20,-20,3),new Vec3(0,40,0),new Vec3(40,0,0)),new Material("target",new Vec3(.6f,.6f,.6f)));
        add(st,"emitter",new Rect(new Vec3(-2,-2,0),new Vec3(4,0,0),new Vec3(0,4,0)),new Material("light",Vec3.ZERO).withEmission(new Vec3(2,2,2)));
        return st;
    }
    @Test void areaLightMisPreservesBrightnessAndFinalVertexLighting() {
        var st=litPlane();var ray=new Ray(new Vec3(0,0,1),new Vec3(0,0,1));var tracer=new DirectRgbTracer(st);
        // Numeric area integral of rho/pi * Le * cos(surface)*cos(light)/distance^2.
        double integral=0;int steps=400;
        for(int y=0;y<steps;y++)for(int x=0;x<steps;x++) {double xx=-2+(x+.5)*4/steps,yy=-2+(y+.5)*4/steps,d2=9+xx*xx+yy*yy;integral+=.6/Math.PI*2*9/(d2*d2)*16/(steps*steps);}
        double direct=average(tracer,ray,40000);st.pathDepth(1);double mis=average(tracer,ray,40000);
        System.out.printf("Area brightness integral %.6f direct %.6f MIS %.6f%n",integral,direct,mis);
        close(integral,direct,.005);close(integral,mis,.005);
        st.pathDepth(4);close(integral,average(tracer,ray,40000),.005);
        // Camera-visible emission is one-sided and evaluated even at the depth limit.
        st.pathDepth(0);close(2,tracer.radiance(new Ray(new Vec3(0,0,1),new Vec3(0,0,-1)),0)[0],0);
        close(0,tracer.radiance(new Ray(new Vec3(0,0,-1),new Vec3(0,0,1)),0)[0],0);
    }
    @Test void deltaMirrorCanSeeEmitterWithoutNeeAndRoughMirrorGetsDirectLight() {
        var st=litPlane();st.pathDepth(1);var object=st.instances().getFirst();
        st.instances().set(0,object.withMaterial(object.material().withKind(Material.Kind.MIRROR)));
        var tracer=new DirectRgbTracer(st);var ray=new Ray(new Vec3(0,0,1),new Vec3(0,0,1));
        close(1.2,tracer.radiance(ray,0)[0],1e-6);assertEquals(0L,tracer.areaLightSamples);
        st.instances().set(0,st.instances().getFirst().withMaterial(object.material().withKind(Material.Kind.MIRROR).withRoughness(.5f)));
        double combined=average(tracer,ray,40000);st.pathDepth(0);double direct=average(tracer,ray,40000);
        assertTrue(direct>0);close(direct,combined,.01);
    }
    @Test void dielectricDeltaEmitterPathsCarryIorWeightsAndRoughNeeUsesOutgoingMedium() {
        for(boolean inside:new boolean[]{false,true}) {
            var st=state(1);st.pathDepth(1);
            var glass=new Material("glass",WHITE,Material.Kind.DIELECTRIC);
            add(st,"glass",new Sphere(inside?Vec3.ZERO:new Vec3(0,0,100),inside?2:100),glass);
            float hitZ=inside?2:0,lightZ=hitZ+3;
            add(st,"emitter",new Rect(new Vec3(-2,-2,lightZ),new Vec3(0,4,0),new Vec3(4,0,0)),new Material("light",Vec3.ZERO).withEmission(new Vec3(2,2,2)));
            var ray=new Ray(new Vec3(0,0,inside?0:-1),new Vec3(0,0,1));var tracer=new DirectRgbTracer(st);
            double eta=inside?1.5:1/1.5;
            close(2*.96*eta*eta,average(tracer,ray,40000),.01);assertEquals(0L,tracer.areaLightSamples);
            glass=glass.withRoughness(.6f).withAbsorption(new Vec3(.1f,0,0));
            st.instances().set(0,st.instances().getFirst().withMaterial(glass));st.pathDepth(0);
            // Independently integrate the rectangular source in area coordinates, including
            // the camera segment inside glass or the transmitted light segment entering it.
            double integral=0;int steps=400;var eval=new Material.Sample();
            for(int y=0;y<steps;y++)for(int x=0;x<steps;x++) {
                float xx=-2+(x+.5f)*4/steps,yy=-2+(y+.5f)*4/steps,d=(float)Math.sqrt(9+xx*xx+yy*yy);
                glass.evaluate(0,0,1,xx/d,yy/d,3/d,0,0,-1,inside?1.5f:1,inside?1:1.5f,eval);
                integral+=eval.red*2*3/(d*d*d)*16/(steps*steps)*Math.exp(-.1*(inside?2:d));
            }
            double sampled=average(tracer,ray,400000);
            System.out.printf("Rough glass inside=%s area integral %.6f sampled %.6f%n",inside,integral,sampled);
            close(integral,sampled,.01);
            assertTrue(tracer.areaLightSamples>0);
        }
    }
    @Test void largerEmitterProducesPenumbraAndTransformsUpdateSampling() {
        var st=state(1);
        add(st,"floor",new Rect(new Vec3(-10,0,-10),new Vec3(0,0,20),new Vec3(20,0,0)),new Material("floor",WHITE));
        st.instances().add(new SceneInstance("blocker",SceneInstance.box(),new Transform(new Vec3(0,2,0),Vec3.ZERO,new Vec3(.3f,.2f,.3f)),new Material("blocker",WHITE)));
        var light=new SceneInstance("area-light",PolygonMesh.parallelogram(new Vec3(-.5f,0,-.5f),new Vec3(1,0,0),new Vec3(0,0,1)),new Transform(new Vec3(0,4,0),Vec3.ZERO,new Vec3(.2f,1,.2f)),new Material("light",Vec3.ZERO).withEmission(WHITE));
        st.instances().add(light);var tracer=new DirectRgbTracer(st);var ray=new Ray(new Vec3(0,.5f,0),new Vec3(0,-1,0));
        close(0,average(tracer,ray,2000),0);
        st.instances().set(2,light.withTransform(new Transform(new Vec3(0,4,0),Vec3.ZERO,new Vec3(3,1,3))));
        double blocked=average(tracer,ray,10000);assertTrue(blocked>0);
        st.instances().remove(1);double clear=average(tracer,ray,10000);assertTrue(blocked<clear*.95);
        System.out.printf("Large light center shadow %.6f vs unobstructed %.6f%n",blocked,clear);
    }
    @Test void controlsValidateAtomicEditsAndInvalidateTransportOnly() {
        var st=state(64);ScenePresets.load(st,"rough-room");var cmd=new ViewportCommand(st);var tracer=new DirectRgbTracer(st);
        assertTrue(cmd.run("view","select","frosted-box").isSuccess());
        assertTrue(cmd.run("view","roughness",".6").isSuccess());
        tracer.trace();tracer.trace();assertEquals(2L,st.accumulatedSamples());
        cmd.run("view","roughness",".2");tracer.trace();assertEquals(1L,st.accumulatedSamples());
        tracer.trace();cmd.run("view","exposure","1");tracer.trace();assertEquals(3L,st.accumulatedSamples());
        assertTrue(cmd.run("view","emission","1","1","1").isFailure());
        assertTrue(cmd.run("view","roughness","NaN").isFailure());assertTrue(cmd.run("view","roughness","1.1").isFailure());
        var before=List.copyOf(st.instances());assertTrue(cmd.run("view","light","size","-1","2").isFailure());assertEquals(before,st.instances());
        assertTrue(cmd.run("view","light","size","1","2").isSuccess());tracer.trace();assertEquals(1L,st.accumulatedSamples());
        assertTrue(cmd.run("view","light","intensity","8").isSuccess());
        assertTrue(cmd.run("view","light","color","ff0000").isSuccess());
        assertTrue(cmd.run("view","select","area-light").isSuccess());assertTrue(cmd.run("view","emission","2","3","4").isSuccess());
        assertTrue(cmd.run("view","status").isSuccess());assertTrue(tracer.trace()[0][32][32]>=0);
        assertTrue(tracer.profile.areaLightSamples()>0 && tracer.profile.roughEvents()>0);
        // A shared material that also references a sphere cannot become an emitter halfway through an edit.
        var sphere=st.instances().getFirst();var light=st.instances().getLast();st.instances().set(0,sphere.withMaterial(new Material("shared",WHITE)));
        st.instances().set(st.instances().size()-1,light.withMaterial(new Material("shared",WHITE)));before=List.copyOf(st.instances());
        assertTrue(cmd.run("view","emission","1","1","1").isFailure());assertEquals(before,st.instances());
    }
    private static float[][][] copy(float[][][] source) {var out=new float[3][][];for(int c=0;c<3;c++){out[c]=new float[source[c].length][];for(int y=0;y<out[c].length;y++)out[c][y]=source[c][y].clone();}return out;}
    private static double error(float[][][] a,float[][][] b) {double sum=0;for(int c=0;c<3;c++)for(int y=0;y<a[c].length;y++)for(int x=0;x<a[c][y].length;x++){double d=a[c][y][x]-b[c][y][x];sum+=d*d;}return sum;}
    private static void image(float[][][] rgb,String filename) throws Exception {
        int size=rgb[0].length;var image=new java.awt.image.BufferedImage(size,size,java.awt.image.BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<size;y++)for(int x=0;x<size;x++){int color=0;for(int c=0;c<3;c++)color=(color<<8)|Byte.toUnsignedInt(DisplayMapping.encode(rgb[c][size-1-y][x],1));image.setRGB(x,y,color);}
        javax.imageio.ImageIO.write(image,"png",new java.io.File("out/cli/"+filename));
    }
    @Test void seededBatchesAgreeConvergeAndPreviewShowsMaterialPairs() throws Exception {
        var a=state(64);var b=state(64);ScenePresets.load(a,"rough-room");ScenePresets.load(b,"rough-room");
        a.sampleTarget(1);b.sampleTarget(64);b.samplesPerFrame(8);var ta=new DirectRgbTracer(a);var tb=new DirectRgbTracer(b);
        var first=copy(ta.trace());a.sampleTarget(64);while(a.accumulatedSamples()<64)ta.trace();while(b.accumulatedSamples()<64)tb.trace();
        var converged=copy(ta.trace());assertEquals(converged,tb.trace());a.sampleTarget(512);while(a.accumulatedSamples()<512)ta.trace();
        double e1=error(first,ta.trace()),e64=error(converged,ta.trace());assertTrue(e64<e1*.2);
        System.out.printf("Rough-room squared error: 1 spp %.6f; 64 spp %.6f%n",e1,e64);
        var preview=state(240);ScenePresets.load(preview,"rough-room");preview.sampleTarget(128);preview.samplesPerFrame(8);var tracer=new DirectRgbTracer(preview);
        while(preview.accumulatedSamples()<128)tracer.trace();image(tracer.trace(),"rough-room-preview-128.png");
        var reference=copy(tracer.trace());preview.instances().replaceAll(o->o.material().kind()==Material.Kind.DIELECTRIC?o.withMaterial(o.material().withRoughness(0)):o);
        do{tracer.trace();}while(preview.accumulatedSamples()<128);
        assertTrue(error(reference,tracer.trace())>10);image(tracer.trace(),"rough-room-smooth-glass-128.png");
    }
    public static void main(String[] args) {SuiteRunner.runThis();}
}
