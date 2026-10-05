package scenes.viewport;

import harness.SuiteRunner;
import harness.Test;
import math.Ray;
import math.Vec3;
import scenes.viewport.objects.*;
import java.util.*;
import static harness.Assertions.*;

public class MeshAccelerationTest {
    private static ViewportState state(int size){return new ViewportState(new Rect(new Vec3(-.5f,-.5f,0),new Vec3(1,0,0),new Vec3(0,1,0)),size,size);}
    private static final Material WHITE=Material.srgb("white",0xffffff);
    private static void close(float a,float b){assertTrue(Math.abs(a-b)<3e-4f);}
    private static void rejected(Runnable action){boolean failed=false;try{action.run();}catch(IllegalArgumentException|UnsupportedOperationException e){failed=true;}assertTrue(failed);}
    @Test void indexedBoundaryIsClosedOutwardAndImmutable() {
        for(int detail:new int[]{4,12,64}) {
            var mesh=IndexedMesh.sphere(detail);assertEquals(4*detail*(detail-1),mesh.size());
            var ids=mesh.indices();ids[0]=-1;assertTrue(mesh.indices()[0]>=0);
            rejected(()->mesh.set(0,mesh.get(0)));rejected(()->mesh.vertices().clear());
            for(var p:mesh){var t=(Tri)p;assertTrue(t.b().sub(t.a()).cross(t.c().sub(t.a())).dot(t.a().add(t.b()).add(t.c()))>0);}
        }
        var mesh=IndexedMesh.sphere(4);var ids=mesh.indices();
        rejected(()->new IndexedMesh(mesh.vertices(),Arrays.copyOf(ids,ids.length-3)));
        int swap=ids[0];ids[0]=ids[1];ids[1]=swap;rejected(()->new IndexedMesh(mesh.vertices(),ids));
        rejected(()->IndexedMesh.sphere(65));
    }
    @Test void bvhMatchesBruteAndIndependentTrianglesIncludingInsideAndSharedEdges() {
        var s=state(1);var mesh=IndexedMesh.sphere(16);
        var t=new Transform(new Vec3(.2f,-.3f,5),new Vec3(20,45,15),new Vec3(1,2,.7f));
        s.instances().add(new SceneInstance("mesh",mesh,t,WHITE));var tracer=new DirectRgbTracer(s);var rng=new Random(4721);
        var world=mesh.stream().map(p->{var tri=(Tri)p;return new Tri(t.point(tri.a()),t.point(tri.b()),t.point(tri.c()));}).toList();
        var rays=new ArrayList<Ray>();
        for(int i=0;i<1200;i++)rays.add(new Ray(i%3==0?t.position:new Vec3(0,0,-1),new Vec3(rng.nextFloat()*2-1,rng.nextFloat()*2-1,rng.nextFloat()*2-1).normalized()));
        for(var vertex:mesh.vertices())rays.add(new Ray(t.position,t.vector(vertex).normalized()));
        for(var direction:List.of(new Vec3(0,0,1),new Vec3(0,1,0),new Vec3(1,0,0)))rays.add(new Ray(t.position,direction));
        long accelerated=0,brute=0;int rayIndex=0;
        for(var ray:rays) {
            s.acceleration(true);long before=tracer.primaryTests;var a=tracer.intersect(ray);accelerated+=tracer.primaryTests-before;
            s.acceleration(false);before=tracer.primaryTests;var b=tracer.intersect(ray);brute+=tracer.primaryTests-before;
            if(rayIndex++<1200) {
                math.Intersection expected=null;for(var tri:world){var h=tri.intersect(ray);if(h.isPresent()&&(expected==null||h.get().distance()<expected.distance()))expected=h.get();}
                if(expected==null)assertNull(a);else{assertNotNull(a);close(expected.distance(),a.distance);}
            }
            if(a==null){assertNull(b);continue;}
            assertNotNull(b);assertEquals(b.distance,a.distance);assertEquals(b.primitive.primitiveId,a.primitive.primitiveId);assertEquals(b.frontFace,a.frontFace);
            close(b.nx,a.nx);close(b.ny,a.ny);close(b.nz,a.nz);
        }
        System.out.printf("Mesh intersection tests: BVH %d; brute %d%n",accelerated,brute);assertTrue(accelerated<brute/10);
    }
    @Test void boundedVisibilityMatchesBruteAndOtherTrianglesOfSameObjectBlock() {
        var s=state(1);s.instances().add(new SceneInstance("mesh",IndexedMesh.sphere(12),Transform.IDENTITY,WHITE));var tracer=new DirectRgbTracer(s);
        var hit=tracer.intersect(new Ray(new Vec3(.1f,.15f,0),new Vec3(0,0,1)));assertFalse(hit.frontFace);
        assertTrue(tracer.occluded(hit.x,hit.y,hit.z-.002f,0,0,-1,3,hit.primitive));
        assertFalse(tracer.occluded(hit.x,hit.y,hit.z-.002f,0,0,-1,.2f,hit.primitive));
        var rng=new Random(91);
        for(int i=0;i<2000;i++) {
            float x=rng.nextFloat()*4-2,y=rng.nextFloat()*4-2,z=rng.nextFloat()*4-2;
            var d=new Vec3(rng.nextFloat()*2-1,rng.nextFloat()*2-1,rng.nextFloat()*2-1).normalized();float max=rng.nextFloat()*5;
            s.acceleration(true);boolean a=tracer.occluded(x,y,z,d.x(),d.y(),d.z(),max,null);
            s.acceleration(false);assertEquals(a,tracer.occluded(x,y,z,d.x(),d.y(),d.z(),max,null));
        }
    }
    @Test void finestDetailSmallFacesAndSeamsRemainHittable() {
        var mesh=IndexedMesh.sphere(64);var s=state(1);
        s.instances().add(new SceneInstance("tiny",mesh,new Transform(Vec3.ZERO,Vec3.ZERO,new Vec3(.01f,.02f,.03f)),WHITE));
        var tracer=new DirectRgbTracer(s);var t=s.instances().getFirst().transform();
        for(int i=0;i<mesh.size();i+=127) {
            var tri=(Tri)mesh.get(i);var center=t.point(tri.a().add(tri.b()).add(tri.c()).scale(1f/3));
            var hit=tracer.intersect(new Ray(Vec3.ZERO,center.normalized()));assertNotNull(hit);close(center.length(),hit.distance);assertFalse(hit.frontFace);
        }
        for(var direction:List.of(new Vec3(0,1,0),new Vec3(0,-1,0),new Vec3(0,0,1),new Vec3(0,0,-1),new Vec3(1,0,0))) {
            var h=tracer.intersect(new Ray(Vec3.ZERO,direction));assertNotNull(h);assertFalse(h.frontFace);
        }
    }
    @Test void glassMeshEntriesExitsAndInsideCameraKeepOneMedium() {
        var s=state(1);var glass=new Material("glass",new Vec3(1,1,1),Material.Kind.DIELECTRIC,1,new Vec3(.4f,.2f,.1f));
        s.instances().add(new SceneInstance("solid",IndexedMesh.sphere(12),new Transform(new Vec3(0,0,4),Vec3.ZERO,new Vec3(1,1,1)),glass));
        s.instances().add(new SceneInstance("emitter",List.of(new Rect(new Vec3(-3,-3,8),new Vec3(0,6,0),new Vec3(6,0,0))),Transform.IDENTITY,new Material("emitter",Vec3.ZERO).withEmission(new Vec3(2,2,2))));
        s.pathDepth(2);var tracer=new DirectRgbTracer(s);var enter=tracer.intersect(new Ray(new Vec3(.1f,.15f,0),new Vec3(0,0,1)));
        assertTrue(enter.frontFace);var exit=tracer.intersect(new Ray(new Vec3(.1f,.15f,4),new Vec3(0,0,1)));assertFalse(exit.frontFace);
        assertTrue(enter.object==exit.object);assertTrue(enter.primitive.primitiveId!=exit.primitive.primitiveId);
        float thickness=4+exit.distance-enter.distance;
        var rgb=tracer.radiance(new Ray(new Vec3(.1f,.15f,0),new Vec3(0,0,1)),1);
        assertTrue(Math.abs(rgb[0]-2*Math.exp(-.4f*thickness))<.002);assertEquals(2L,tracer.dielectricTransmissions);
        s.pathDepth(1);rgb=tracer.radiance(new Ray(new Vec3(.1f,.15f,4),new Vec3(0,0,1)),1);
        assertTrue(Math.abs(rgb[0]-2*Math.exp(-.4f*exit.distance))<.002);
        s.pathDepth(0);assertEquals(0f,tracer.radiance(new Ray(new Vec3(.1f,.15f,4),new Vec3(0,0,1)),1)[0]);
    }
    private static void ok(ViewportCommand c,String... args){assertTrue(c.run(args).isSuccess());}
    @Test void nestedMeshMediaAndCentralSeamsPreserveAbsorptionAndIor() {
        var s=state(1);var mesh=IndexedMesh.sphere(12);
        s.instances().add(new SceneInstance("outer",mesh,new Transform(Vec3.ZERO,Vec3.ZERO,new Vec3(2,2,2)),
                new Material("outer",new Vec3(1,1,1),Material.Kind.DIELECTRIC,1,new Vec3(.4f,.4f,.4f))));
        s.instances().add(new SceneInstance("inner",mesh,Transform.IDENTITY,
                new Material("inner",new Vec3(1,1,1),Material.Kind.DIELECTRIC,1,new Vec3(.2f,.2f,.2f))));
        s.instances().add(new SceneInstance("emitter",List.of(new Rect(new Vec3(-3,-3,8),new Vec3(0,6,0),new Vec3(6,0,0))),Transform.IDENTITY,new Material("emitter",Vec3.ZERO).withEmission(new Vec3(2,2,2))));
        s.pathDepth(4);var tracer=new DirectRgbTracer(s);var rgb=tracer.radiance(new Ray(new Vec3(0,0,-3),new Vec3(0,0,1)),1);
        assertTrue(Math.abs(rgb[0]-2*Math.exp(-1.2))<.003);assertEquals(4L,tracer.dielectricTransmissions);
        s.pathDepth(2);rgb=tracer.radiance(new Ray(Vec3.ZERO,new Vec3(0,0,1)),1);
        assertTrue(Math.abs(rgb[0]-2*Math.exp(-.6))<.003);
        s.instances().remove(1);s.instances().set(0,s.instances().getFirst().withMaterial(new Material("ior",new Vec3(1,1,1),Material.Kind.DIELECTRIC,1.5f,Vec3.ZERO)));
        s.pathDepth(1);double sum=0;for(int i=0;i<10000;i++)sum+=tracer.radiance(new Ray(Vec3.ZERO,new Vec3(0,0,1)),i)[0];
        // Central +Z lies on the UV seam; inside camera still starts in glass and exits to air.
        // Facet normals slightly tilt incidence, so compare to their exact Fresnel value.
        var h=tracer.intersect(new Ray(Vec3.ZERO,new Vec3(0,0,1)));
        float fresnel=Material.fresnel(1.5f,1,Math.abs(h.nz));
        assertTrue(Math.abs(sum/10000-2*2.25*(1-fresnel))<.05);
    }
    @Test void editsReuseIndexedGeometryUpdateBoundsAndInvalidateOnlyWhenNeeded() {
        var s=state(16);ScenePresets.load(s,"mesh-room");var c=new ViewportCommand(s);ok(c,"view","select","mesh-diffuse");
        assertTrue(s.instances().get(0).geometry()==s.instances().get(3).geometry());
        var tracer=new DirectRgbTracer(s);tracer.trace();long builds=tracer.geometryBuilds;tracer.trace();assertEquals(builds,tracer.geometryBuilds);
        ok(c,"view","exposure","1");tracer.trace();assertEquals(3L,s.accumulatedSamples());assertEquals(builds,tracer.geometryBuilds);
        ok(c,"view","mesh","detail","20");tracer.trace();assertEquals(1L,s.accumulatedSamples());assertEquals(builds+1,tracer.geometryBuilds);assertEquals(1520,s.instances().getFirst().geometry().size());
        ok(c,"view","copy","copy");assertTrue(s.instances().getLast().geometry()==s.instances().getFirst().geometry());
        ok(c,"view","move","0","0","3");ok(c,"view","scale","1","2",".5");ok(c,"view","rotate","15","30","20");
        var copy=s.instances().getLast();var d=copy.transform().vector(new Vec3(.1f,.15f,1)).normalized();var h=tracer.intersect(new Ray(copy.transform().position,d));assertNotNull(h);assertEquals("copy",h.primitive.objectId);assertFalse(h.frontFace);
        var tri=(Tri)copy.geometry().get(h.primitive.primitiveId);var expected=copy.transform().normal(tri.b().sub(tri.a()).cross(tri.c().sub(tri.a())).normalized());close(expected.x(),h.nx);close(expected.y(),h.ny);close(expected.z(),h.nz);
        var original=List.copyOf(s.instances());assertTrue(c.run("view","mesh","detail","100").isFailure());assertEquals(original,s.instances());assertTrue(c.run("view","copy","copy").isFailure());
        ok(c,"view","remove","copy");ok(c,"view","acceleration","brute");tracer.trace();assertEquals(1L,s.accumulatedSamples());
    }
    @Test void seededImagesAgreeAndPreviewShowsMeshMaterials() throws Exception {
        var a=state(64);var b=state(64);ScenePresets.load(a,"mesh-room");ScenePresets.load(b,"mesh-room");b.acceleration(false);
        a.sampleTarget(4);b.sampleTarget(4);var ta=new DirectRgbTracer(a);var tb=new DirectRgbTracer(b);
        while(a.accumulatedSamples()<4)ta.trace();while(b.accumulatedSamples()<4)tb.trace();assertEquals(ta.trace(),tb.trace());
        a.sampleTarget(5);b.sampleTarget(5);var ar=ta.trace();var br=tb.trace();assertEquals(ar,br);
        long fast=ta.primaryTests+ta.continuationTests+ta.shadowTests,slow=tb.primaryTests+tb.continuationTests+tb.shadowTests;
        System.out.printf("Mesh image tests: BVH %d; brute %d%n",fast,slow);assertTrue(fast<slow/10);
        var s=state(200);ScenePresets.load(s,"mesh-room");s.sampleTarget(64);s.samplesPerFrame(8);var tracer=new DirectRgbTracer(s);
        while(s.accumulatedSamples()<64)tracer.trace();var rgb=tracer.trace();var image=new java.awt.image.BufferedImage(200,200,java.awt.image.BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<200;y++)for(int x=0;x<200;x++){int color=0;for(int c=0;c<3;c++)color=(color<<8)|Byte.toUnsignedInt(DisplayMapping.encode(rgb[c][199-y][x],1));image.setRGB(x,y,color);}
        javax.imageio.ImageIO.write(image,"png",new java.io.File("out/cli/mesh-room-preview-64.png"));
    }
    public static void main(String[] args){SuiteRunner.runThis();}
}
