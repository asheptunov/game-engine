package scenes.viewport;

import harness.SuiteRunner;
import harness.Test;
import math.Ray;
import math.Vec3;
import scenes.viewport.objects.*;
import scenes.viewport.lights.PointLight;
import java.util.List;
import java.util.Random;
import static harness.Assertions.*;

public class MaterialPlaygroundTest {
    private static final Material WHITE=Material.srgb("white",0xffffff);
    private static ViewportState state(int size) {
        return new ViewportState(new Rect(new Vec3(-.5f,-.5f,0),new Vec3(1,0,0),new Vec3(0,1,0)),size,size);
    }
    private static void close(float expected,float actual) {assertTrue(Math.abs(expected-actual)<2e-4f);}
    private static SceneInstance instance(String name,List<SceneObject> geometry,Transform t) {
        return new SceneInstance(name,geometry,t,WHITE);
    }
    @Test void sphereOutsideInsideTangentMissAndUnnormalizedRay() {
        var sphere=new Sphere(Vec3.ZERO,1);
        close(2,sphere.distance(0,0,-3,0,0,1));
        close(1,sphere.distance(0,0,0,0,0,1));
        close(3,sphere.distance(1,0,-3,0,0,1));
        close(.5f,sphere.distance(0,0,0,0,0,2));
        assertEquals(Float.POSITIVE_INFINITY,sphere.distance(2,0,-3,0,0,1));
        assertEquals(Float.POSITIVE_INFINITY,sphere.distance(0,0,-3,0,0,-1));
    }
    @Test void transformedSphereKeepsDistanceNormalAndExitIdentity() {
        var st=state(1);
        var t=new Transform(new Vec3(1,2,6),new Vec3(20,35,10),new Vec3(2,1,.5f));
        st.instances().add(instance("ellipsoid",List.of(new Sphere(Vec3.ZERO,1)),t));
        var tracer=new DirectRgbTracer(st);
        var origin=t.point(new Vec3(0,0,-3));var direction=t.vector(new Vec3(0,0,1)).normalized();
        var enter=tracer.intersect(new Ray(origin,direction));
        close(1,enter.distance);assertTrue(enter.frontFace);
        var expected=t.normal(new Vec3(0,0,-1));
        close(expected.x(),enter.nx);close(expected.y(),enter.ny);close(expected.z(),enter.nz);
        var exit=tracer.intersect(new Ray(t.position,direction));
        close(.5f,exit.distance);assertFalse(exit.frontFace);
        assertEquals("ellipsoid",exit.primitive.objectId);assertEquals("white",exit.primitive.materialId);
        assertEquals(enter.primitive.primitiveId,exit.primitive.primitiveId);
    }
    @Test void boxIsClosedAndOutwardWithNonuniformScale() {
        var st=state(1);var t=new Transform(new Vec3(0,0,6),new Vec3(10,30,15),new Vec3(1,2,3));
        st.instances().add(instance("box",SceneInstance.box(),t));var tracer=new DirectRgbTracer(st);
        for(var axis:List.of(new Vec3(1,0,0),new Vec3(-1,0,0),new Vec3(0,1,0),new Vec3(0,-1,0),new Vec3(0,0,1),new Vec3(0,0,-1))) {
            var direction=t.vector(axis).normalized();
            var h=tracer.intersect(new Ray(t.position,direction));assertNotNull(h);assertFalse(h.frontFace);
            var n=t.normal(axis);close(n.x(),h.nx);close(n.y(),h.ny);close(n.z(),h.nz);
            var entry=tracer.intersect(new Ray(t.point(axis.scale(3)),direction.negate()));assertTrue(entry.frontFace);
        }
    }
    @Test void preparedHitsMatchIndependentTransformedReference() {
        var st=state(1);var random=new Random(4721);
        var t=new Transform(new Vec3(.2f,-.3f,5),new Vec3(20,45,15),new Vec3(1,2,.7f));
        var geometry=SceneInstance.box();st.instances().add(instance("box",geometry,t));
        var world=geometry.stream().map(p->{var tri=(Tri)p;return new Tri(t.point(tri.a()),t.point(tri.b()),t.point(tri.c()));}).toList();
        var tracer=new DirectRgbTracer(st);
        for(int i=0;i<2000;i++) {
            var ray=new Ray(new Vec3(0,0,-1),new Vec3(random.nextFloat()*2-1,random.nextFloat()*2-1,1).normalized());
            math.Intersection expected=null;
            for(var p:world) {var hit=p.intersect(ray);if(hit.isPresent()&&(expected==null||hit.get().distance()<expected.distance())) expected=hit.get();}
            var actual=tracer.intersect(ray);
            if(expected==null) assertNull(actual);
            else {assertNotNull(actual);close(expected.distance(),actual.distance);close(expected.normal().x(),actual.nx);close(expected.normal().y(),actual.ny);close(expected.normal().z(),actual.nz);}
        }
    }
    @Test void rgbLambertianInverseSquareAndFixedExposure() {
        var st=state(1);
        st.instances().add(new SceneInstance("target",List.of(new Rect(new Vec3(-2,-2,3),new Vec3(4,0,0),new Vec3(0,4,0))),
                Transform.IDENTITY,new Material("red",new Vec3(.5f,.25f,0))));
        st.addLight(new PointLight(new Vec3(0,0,1),new Vec3(1,.5f,1),(float)(4*Math.PI)));
        var tracer=new DirectRgbTracer(st);var rgb=tracer.trace();close(.5f,rgb[0][0][0]);close(.125f,rgb[1][0][0]);close(0,rgb[2][0][0]);
        byte pixel=DisplayMapping.encode(rgb[0][0][0],1);
        st.instances().add(instance("bright",List.of(new Sphere(new Vec3(10,10,5),1)),Transform.IDENTITY));
        close(.5f,tracer.trace()[0][0][0]);assertEquals(pixel,DisplayMapping.encode(rgb[0][0][0],1));
        st.lights().set(0,new PointLight(new Vec3(0,0,-1),new Vec3(1,.5f,1),(float)(4*Math.PI)));
        close(.125f,tracer.trace()[0][0][0]);
        assertTrue(Byte.toUnsignedInt(DisplayMapping.encode(.5f,2))>Byte.toUnsignedInt(pixel));
        close(.21586f,DisplayMapping.linear(128));
        st.exposure(4);close(.125f,tracer.trace()[0][0][0]);
    }
    @Test void shadowsBoundedAndSphereMustShadowItsInterior() {
        var st=state(1);st.instances().add(instance("sphere",List.of(new Sphere(Vec3.ZERO,2)),Transform.IDENTITY));
        st.eye(Vec3.ZERO);st.cameraSensor(new Rect(new Vec3(-.5f,-.5f,1),new Vec3(1,0,0),new Vec3(0,1,0)));
        st.addLight(new PointLight(new Vec3(0,0,-5),new Vec3(1,1,1),100));
        var tracer=new DirectRgbTracer(st);assertEquals(0f,tracer.trace()[0][0][0]);assertEquals(1,tracer.shadowsOccluded);
        st.instances().clear();st.eye(new Vec3(0,0,-1));
        st.instances().add(instance("target",List.of(new Rect(new Vec3(-2,-2,3),new Vec3(4,0,0),new Vec3(0,4,0))),Transform.IDENTITY));
        st.lights().set(0,new PointLight(new Vec3(2,0,1),new Vec3(1,1,1),100));
        st.instances().add(instance("blocker",List.of(new Sphere(new Vec3(1,0,2),.3f)),Transform.IDENTITY));
        assertEquals(0f,tracer.trace()[0][0][0]);
        st.instances().set(1,instance("beyond",List.of(new Sphere(new Vec3(3,0,0),.3f)),Transform.IDENTITY));
        assertTrue(tracer.trace()[0][0][0]>0);
    }
    @Test void trianglesWithinSameBoxMustShadowEachOther() {
        var st=state(1);
        st.eye(Vec3.ZERO);
        st.cameraSensor(new Rect(new Vec3(-.5f,-.5f,1),new Vec3(1,0,0),new Vec3(0,1,0)));
        st.instances().add(instance("box",SceneInstance.box(),new Transform(Vec3.ZERO,Vec3.ZERO,new Vec3(2,2,2))));
        st.addLight(new PointLight(new Vec3(0,0,-5),new Vec3(1,1,1),100));
        var tracer=new DirectRgbTracer(st);
        assertEquals(0f,tracer.trace()[0][0][0]);assertEquals(1,tracer.shadowsOccluded);
        assertTrue(tracer.shadowTests>0);
    }
    @Test void liveCommandsValidateAndRefreshCachedScene() {
        var st=state(30);ScenePresets.load(st,"playground");var cmd=new ViewportCommand(st);var tracer=new DirectRgbTracer(st);
        var before=deepCopy(tracer.trace());
        assertTrue(cmd.run("view","color","00ff00").isSuccess());assertNotEquals(before,tracer.trace());
        assertTrue(cmd.run("view","select","box").isSuccess());
        assertTrue(cmd.run("view","move","0","0","4").isSuccess());
        assertTrue(cmd.run("view","rotate","10","50","0").isSuccess());
        assertTrue(cmd.run("view","scale","1","2",".5").isSuccess());
        var original=st.instances().get(1);
        assertTrue(cmd.run("view","scale","-1","2","1").isFailure());assertSame(original,st.instances().get(1));
        assertTrue(cmd.run("view","light","intensity","NaN").isFailure());
        assertTrue(cmd.run("view","exposure","999").isFailure());assertTrue(cmd.run("view","select","missing").isFailure());
        assertTrue(cmd.run("view","material","gold").isSuccess());
        assertTrue(cmd.run("view","preset","triangle").isSuccess());assertEquals("triangle",st.preset());
        assertTrue(cmd.run("view","reset").isSuccess());assertTrue(cmd.run("view","preset","bad").isFailure());
        assertEquals("triangle",st.preset());
        assertTrue(cmd.run("view","help").getSuccess().contains("exposure"));
    }
    private static float[][][] deepCopy(float[][][] src) {
        var out=new float[src.length][][];
        for(int c=0;c<src.length;c++){out[c]=new float[src[c].length][];for(int y=0;y<src[c].length;y++)out[c][y]=src[c][y].clone();}
        return out;
    }
    @Test void explicitResolutionEditsRebuildSensorStorage() {
        var st=state(80);ScenePresets.load(st,"playground");var tracer=new DirectRgbTracer(st);var command=new ViewportCommand(st);
        assertEquals(80,tracer.trace()[0].length);
        assertTrue(command.run("view","resolution","64").isSuccess());
        var actual=tracer.trace();assertEquals(64,actual[0].length);assertEquals(4096,tracer.primaryRays);
        var reference=state(64);ScenePresets.load(reference,"playground");assertEquals(new DirectRgbTracer(reference).trace(),actual);
        assertTrue(command.run("view","resolution","0").isFailure());assertEquals(64,st.sensorPixelsW());
    }
    @Test void rectangularResolutionMatchesReferenceAndRestartsSampling() {
        var st=state(80);ScenePresets.load(st,"playground");st.presentationAspect(1.6f);
        var camera=st.cameraSensor();var tracer=new DirectRgbTracer(st);
        var command=new ViewportCommand(st,1440,900);
        tracer.trace();
        assertTrue(command.run("view","resolution","128","80").isSuccess());
        var actual=tracer.trace();
        assertEquals(80,actual[0].length);assertEquals(128,actual[0][0].length);
        assertEquals(128*80,tracer.primaryRays);assertEquals(camera,st.cameraSensor());
        var reference=new ViewportState(camera,128,80);ScenePresets.load(reference,"playground");reference.presentationAspect(1.6f);
        assertEquals(new DirectRgbTracer(reference).trace(),actual);
        st.pathDepth(1);tracer.trace();tracer.trace();assertEquals(2L,st.accumulatedSamples());
        assertTrue(command.run("view","resolution","80","128").isSuccess());
        actual=tracer.trace();assertEquals(128,actual[0].length);assertEquals(80,actual[0][0].length);
        assertEquals(1L,st.accumulatedSamples());assertEquals(camera,st.cameraSensor());
        assertEquals(128,st.accumulator().length);assertEquals(80,st.accumulator()[0].length);
    }

    @Test void resolutionPresetsAndMultipliersUseFixedWindowReferenceAndRejectInvalidEdits() {
        var st=state(80);ScenePresets.load(st,"playground");var command=new ViewportCommand(st,1440,900);
        assertTrue(command.run("view","resolution","native").isSuccess());
        assertEquals(1440,st.sensorPixelsW());assertEquals(900,st.sensorPixelsH());
        for(String value:new String[]{"half","0.5x","0.5x"}) {
            assertTrue(command.run("view","resolution",value).isSuccess());
            assertEquals(720,st.sensorPixelsW());assertEquals(450,st.sensorPixelsH());
        }
        assertTrue(command.run("view","resolution","quarter").isSuccess());
        assertEquals(360,st.sensorPixelsW());assertEquals(225,st.sensorPixelsH());
        assertTrue(command.run("view","resolution","0.333x").isSuccess());
        assertEquals(480,st.sensorPixelsW());assertEquals(300,st.sensorPixelsH());
        assertTrue(command.run("view","preset","triangle").isSuccess());
        assertEquals(480,st.sensorPixelsW());assertEquals(300,st.sensorPixelsH());
        var storage=st.accumulator();
        for(String[] args:new String[][]{{"0x"},{"-1x"},{"NaNx"},{"Infinityx"},{"2x"},{"0.001x"},
                {"800","0"},{"1601","1000"},{"800","bad"},{"800","500","extra"},{}}) {
            var input=new String[args.length+2];input[0]="view";input[1]="resolution";System.arraycopy(args,0,input,2,args.length);
            assertTrue(command.run(input).isFailure());assertSame(storage,st.accumulator());
            assertEquals(480,st.sensorPixelsW());assertEquals(300,st.sensorPixelsH());
        }
    }

    @Test void playgroundDeterministicPreview() throws Exception {
        var st=state(400);ScenePresets.load(st,"playground");var tracer=new DirectRgbTracer(st);var first=deepCopy(tracer.trace());
        assertEquals(first,tracer.trace());assertTrue(tracer.primaryHits>0);assertTrue(tracer.shadowsOccluded>0);
        var image=new java.awt.image.BufferedImage(400,400,java.awt.image.BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<400;y++)for(int x=0;x<400;x++) {
            int rgb=0;for(int c=0;c<3;c++)rgb=(rgb<<8)|Byte.toUnsignedInt(DisplayMapping.encode(first[c][399-y][x],1));
            image.setRGB(x,y,rgb);
        }
        javax.imageio.ImageIO.write(image,"png",new java.io.File("out/cli/material-playground.png"));
    }
    public static void main(String[] args) {SuiteRunner.runThis();}
}
