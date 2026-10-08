package engine;

import scenes.viewport.*;
import harness.SuiteRunner;
import harness.Test;
import math.Ray;
import math.Vec3;
import engine.objects.*;
import engine.lights.PointLight;
import java.util.List;
import static harness.Assertions.*;

public class ProgressivePathTest {
    private static final Vec3 WHITE = new Vec3(1,1,1);
    private static ViewportState state(int size) {
        return new ViewportState(new Rect(new Vec3(-.5f,-.5f,0),new Vec3(1,0,0),new Vec3(0,1,0)),size,size);
    }
    private static void close(float expected, float actual) {
        if (Math.abs(expected-actual)>2e-5f) throw new AssertionError(expected+" != "+actual);
    }
    private static void add(ViewportState st, String name, RenderPrimitive shape, Material material) {
        st.instances().add(new SceneInstance(name,PreparedGeometry.canonical(shape),Transform.IDENTITY,material));
    }
    private static float[][][] copy(float[][][] source) {
        var result = new float[3][][];
        for(int c=0;c<3;c++) { result[c]=new float[source[c].length][];
            for(int y=0;y<source[c].length;y++) result[c][y]=source[c][y].clone(); }
        return result;
    }
    @Test void materialSamplingReportsCorrectDensityDirectionAndWeight() {
        var material=new Material("diffuse",new Vec3(.5f,.25f,.75f));var out=new Material.Sample();
        var sampler=new DirectRgbTracer.Sampler();sampler.reset(57,12,0);
        float n=(float)(1/Math.sqrt(3));double sum=0;
        for(int i=0;i<20000;i++) {
            material.sample(-n,-n,-n,n,n,n,sampler.next(),sampler.next(),out);
            float cosine=(out.dx+out.dy+out.dz)*n;
            assertTrue(cosine>=0);close(1,out.dx*out.dx+out.dy*out.dy+out.dz*out.dz);
            close(cosine/(float)Math.PI,out.probability);
            close(.5f,out.red);close(.25f,out.green);close(.75f,out.blue);
            assertEquals(Material.Kind.DIFFUSE,out.event);sum+=cosine;
        }
        assertTrue(Math.abs(sum/20000 - 2./3)<.01);
        material.withKind(Material.Kind.MIRROR).sample(.6f,0,-.8f,0,0,1,.1f,.3f,out);
        close(.6f,out.dx);close(0,out.dy);close(.8f,out.dz);close(1,out.probability);
        assertEquals(Material.Kind.MIRROR,out.event);
    }
    @Test void mirrorNeedsContinuationAndFinalDiffuseVertexStillGetsLight() {
        var st=state(1);
        add(st,"mirror",new Rect(new Vec3(-4,-4,3),new Vec3(8,0,0),new Vec3(0,8,0)),
                new Material("mirror",new Vec3(.8f,.5f,.2f),Material.Kind.MIRROR));
        add(st,"target",new Rect(new Vec3(-4,-4,-3),new Vec3(8,0,0),new Vec3(0,8,0)),
                new Material("target",new Vec3(.5f,.5f,.5f)));
        st.addLight(new PointLight(Vec3.ZERO,WHITE,(float)(9*Math.PI)));
        var ray=new Ray(st.eye(),new Vec3(0,0,1));var tracer=new DirectRgbTracer(st);
        assertEquals(new float[]{0,0,0},tracer.radiance(ray,0));
        st.pathDepth(1);var color=tracer.radiance(ray,0);
        close(.4f,color[0]);close(.25f,color[1]);close(.1f,color[2]);
        assertEquals(1L,tracer.continuationRays);
        // A point light never supplies a spurious diffuse highlight to a delta mirror.
        st.instances().remove(1);assertEquals(new float[]{0,0,0},tracer.radiance(ray,0));
    }
    @Test void diffuseThroughputAndDepthMatchAnalyticEnclosure() {
        // Every inward hit on this sphere sees the central light at cos=1, distance=10.
        // Cosine/pdf cancellation gives the finite geometric series rho + ... + rho^(N+1).
        var st=state(1);st.eye(Vec3.ZERO);
        add(st,"enclosure",new Sphere(Vec3.ZERO,10),new Material("tint",new Vec3(.5f,.25f,.75f)));
        st.addLight(new PointLight(Vec3.ZERO,WHITE,(float)(100*Math.PI)));
        var tracer=new DirectRgbTracer(st);var ray=new Ray(Vec3.ZERO,new Vec3(0,0,1));
        for(int n=0;n<=5;n++) {
            st.pathDepth(n);var color=tracer.radiance(ray,43);
            float[] rho={.5f,.25f,.75f};
            for(int c=0;c<3;c++) {
                float expected=0,power=1;
                for(int i=0;i<=n;i++){power*=rho[c];expected+=power;}
                close(expected,color[c]);
            }
        }
    }
    @Test void mirrorLoopHasBoundedDepthAndNoInventedLight() {
        var st=state(1);st.eye(Vec3.ZERO);
        st.cameraSensor(new Rect(new Vec3(-.5f,-.5f,1),new Vec3(1,0,0),new Vec3(0,1,0)));
        st.instances().add(new SceneInstance("mirrors",SceneInstance.box(),Transform.IDENTITY,
                new Material("mirror",WHITE,Material.Kind.MIRROR)));
        st.addLight(new PointLight(Vec3.ZERO,WHITE,100));st.pathDepth(32);
        var tracer=new DirectRgbTracer(st);assertEquals(0f,tracer.trace()[0][0][0]);
        assertEquals(32L,tracer.continuationRays);assertEquals(0,tracer.shadowRays);
        assertTrue(tracer.continuationTests>0);assertEquals(1,tracer.primaryHits);
    }
    @Test void seededRunsAndBatchSizesProduceExactlyTheSameAverage() {
        var a=state(12);var b=state(12);ScenePresets.load(a,"bounce-room");ScenePresets.load(b,"bounce-room");
        a.seed(812);b.seed(812);a.sampleTarget(24);b.sampleTarget(24);b.samplesPerFrame(8);
        var ta=new DirectRgbTracer(a);var tb=new DirectRgbTracer(b);
        float[][][] ca=null,cb=null;
        while(a.accumulatedSamples()<24) ca=ta.trace();
        while(b.accumulatedSamples()<24) cb=tb.trace();
        assertEquals(ca,cb);assertEquals("complete",b.samplingStatus());
        assertEquals(24L,b.accumulatedSamples());
        tb.trace();assertEquals(0,tb.primaryRays);assertEquals(0,tb.profile.samplesPerPixel());
        assertEquals(24L,b.accumulatedSamples());
    }
    @Test void displayAndBatchEditsKeepSamplesButTransportEditsReset() {
        var st=state(12);ScenePresets.load(st,"bounce-room");var tracer=new DirectRgbTracer(st);
        tracer.trace();tracer.trace();assertEquals(2L,st.accumulatedSamples());
        st.exposure(2);tracer.trace();assertEquals(3L,st.accumulatedSamples());
        st.samplesPerFrame(2);tracer.trace();assertEquals(5L,st.accumulatedSamples());st.samplesPerFrame(1);
        st.paused(true);var before=copy(tracer.trace());tracer.trace();assertEquals(before,tracer.trace());
        assertEquals(5L,st.accumulatedSamples());assertEquals("paused",st.samplingStatus());st.paused(false);
        st.pathDepth(2);tracer.trace();assertEquals(1L,st.accumulatedSamples());
        st.eye(new Vec3(0,0,-.5f));tracer.trace();assertEquals(1L,st.accumulatedSamples());
        st.cameraSensor(new Rect(new Vec3(-.5f,-.5f,.5f),new Vec3(1,0,0),new Vec3(0,1,0)));
        tracer.trace();assertEquals(1L,st.accumulatedSamples());
        st.lights().set(0,new PointLight(new Vec3(0,2,5),WHITE,90));tracer.trace();assertEquals(1L,st.accumulatedSamples());
        var object=st.instances().getFirst();
        st.instances().set(0,object.withMaterial(new Material("changed",new Vec3(.3f,.3f,.3f))));
        tracer.trace();assertEquals(1L,st.accumulatedSamples());
        st.instances().set(0,object.withTransform(new Transform(new Vec3(1,0,0),Vec3.ZERO,WHITE)));
        tracer.trace();assertEquals(1L,st.accumulatedSamples());
        st.objects().add(new Sphere(new Vec3(20,0,0),1));tracer.trace();assertEquals(1L,st.accumulatedSamples());
        st.seed(25);tracer.trace();assertEquals(1L,st.accumulatedSamples());
        st.restart();tracer.trace();assertEquals(1L,st.accumulatedSamples());
        st.resolution(64);tracer.trace();assertEquals(1L,st.accumulatedSamples());
        assertEquals(4096,tracer.primaryRays);
        // Also invalidate while paused: display must not retain samples from the previous scene.
        st.paused(true);st.instances().clear();st.objects().clear();
        assertEquals(0f,tracer.trace()[0][0][0]);assertEquals(0L,st.accumulatedSamples());
    }
    @Test void commandControlsValidateAndPreserveMirrorColorEdits() {
        var st=state(8);ScenePresets.load(st,"bounce-room");var cmd=new ViewportCommand(st);
        for(var args:List.of(new String[]{"depth","2"},new String[]{"samples","4"},new String[]{"seed","87"},
                new String[]{"target","32"},new String[]{"pause"},new String[]{"resume"},new String[]{"restart"},
                new String[]{"select","mirror-sphere"},new String[]{"color","ffeecc"})) {
            var full=new String[args.length+1];full[0]="view";System.arraycopy(args,0,full,1,args.length);
            assertTrue(cmd.run(full).isSuccess());
        }
        assertEquals(Material.Kind.MIRROR,st.instances().getFirst().material().kind());
        assertTrue(cmd.run("view","type","diffuse").isSuccess());
        assertEquals(Material.Kind.DIFFUSE,st.instances().getFirst().material().kind());
        for(var args:List.of(new String[]{"depth","-1"},new String[]{"depth","33"},new String[]{"samples","0"},
                new String[]{"samples","9"},new String[]{"target","-1"},new String[]{"type","glass"},new String[]{"seed","bad"}))
            assertTrue(cmd.run("view",args[0],args[1]).isFailure());
        assertEquals(2,st.pathDepth());assertTrue(cmd.run("view","status").getSuccess().contains("seed=87"));
    }
    private static double error(float[][][] actual,float[][][] reference) {
        double result=0;
        for(int c=0;c<3;c++)for(int y=0;y<actual[c].length;y++)for(int x=0;x<actual[c][y].length;x++) {
            double d=actual[c][y][x]-reference[c][y][x];result+=d*d;
        }
        return result;
    }
    private static void image(float[][][] rgb,String filename) throws Exception {
        int size=rgb[0].length;var image=new java.awt.image.BufferedImage(size,size,java.awt.image.BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<size;y++)for(int x=0;x<size;x++) {
            int color=0;for(int c=0;c<3;c++)color=(color<<8)|Byte.toUnsignedInt(DisplayMapping.encode(rgb[c][size-1-y][x],1));
            image.setRGB(x,y,color);
        }
        javax.imageio.ImageIO.write(image,"png",new java.io.File("out/cli/"+filename));
    }
    @Test void roomConvergesAndShowsColoredIndirectLight() throws Exception {
        var st=state(64);ScenePresets.load(st,"bounce-room");st.pathDepth(2);st.samplesPerFrame(8);
        var tracer=new DirectRgbTracer(st);st.sampleTarget(1);var first=copy(tracer.trace());
        st.sampleTarget(64);while(st.accumulatedSamples()<64) tracer.trace();var converged=copy(tracer.trace());
        st.sampleTarget(512);while(st.accumulatedSamples()<512) tracer.trace();var reference=copy(tracer.trace());
        assertTrue(error(converged,reference)<error(first,reference)*.15);
        double bleeding=0;
        st.pathDepth(0);st.sampleTarget(1);var direct=tracer.trace();
        // Floor strip close to the colored side walls; direct white-light response is neutral.
        for(int y=0;y<12;y++)for(int x=0;x<64;x++) {
            bleeding+=Math.abs(reference[0][y][x]-reference[1][y][x])
                    -Math.abs(direct[0][y][x]-direct[1][y][x]);
        }
        assertTrue(bleeding>1);
        image(first,"bounce-room-1.png");image(converged,"bounce-room-64.png");image(reference,"bounce-room-512.png");
        System.out.printf("Room squared error: 1 spp %.6f; 64 spp %.6f; floor color difference %.6f%n",
                error(first,reference),error(converged,reference),bleeding);
    }
    @Test void roomViewportPreview() throws Exception {
        var st=state(320);ScenePresets.load(st,"bounce-room");var tracer=new DirectRgbTracer(st);
        image(tracer.trace(),"bounce-room-preview-1.png");
        st.sampleTarget(128);st.samplesPerFrame(8);
        while(st.accumulatedSamples()<128) tracer.trace();
        image(tracer.trace(),"bounce-room-preview-128.png");
    }
    public static void main(String[] args) {SuiteRunner.runThis();}
}
