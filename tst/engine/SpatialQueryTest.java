package engine;

import engine.objects.Rect;
import harness.SuiteRunner;
import harness.Test;
import math.Vec3;
import java.util.*;
import static harness.Assertions.*;

public class SpatialQueryTest {
    private static final Material MAT=new Material("m",new Vec3(.7f,.7f,.7f));
    private record Built(SceneDocument document,NodeId first,NodeId second){}
    private static Built scene(PolygonMesh mesh) {
        var d=new SceneDocument();var ids=new Object[4];d.transact(e->{ids[0]=e.createGeometry("mesh",mesh);ids[1]=e.createMaterial("mat",MAT);ids[2]=e.createNode("same",null,new Transform(new Vec3(0,0,5),Vec3.ZERO,new Vec3(1,1,1)));e.assignGeometry((NodeId)ids[2],(GeometryId)ids[0],(MaterialId)ids[1]);ids[3]=e.createNode("same",null,new Transform(new Vec3(0,0,5),Vec3.ZERO,new Vec3(1,1,1)));e.assignGeometry((NodeId)ids[3],(GeometryId)ids[0],(MaterialId)ids[1]);});return new Built(d,(NodeId)ids[2],(NodeId)ids[3]);
    }
    @Test void openMeshesUseRobustDistancesIdentityAndDeterministicTies() {
        var vertices=new ArrayList<Vec3>();var indices=new ArrayList<Integer>();var faces=new ArrayList<Long>();
        vertices.add(new Vec3(0,0,0));vertices.add(new Vec3(1,0,0));vertices.add(new Vec3(1,1,0));vertices.add(new Vec3(0,1,0));
        Collections.addAll(indices,0,1,2,0,2,3);faces.add(100L);faces.add(200L);
        for(int i=0;i<14;i++){float x=10+i;int b=vertices.size();vertices.add(new Vec3(x,0,0));vertices.add(new Vec3(x+1,0,0));vertices.add(new Vec3(x,1,0));Collections.addAll(indices,b,b+1,b+2);faces.add(300L+i);}
        var mesh=PolygonMesh.triangleSurface(vertices,indices.stream().mapToInt(Integer::intValue).toArray(),faces.stream().mapToLong(Long::longValue).toArray());
        var built=scene(mesh);var query=SpatialQuery.prepare(built.document().snapshot());var hit=query.nearest(new Vec3(.5f,.5f,0),new Vec3(0,0,Float.MAX_VALUE)).orElseThrow();
        assertEquals(built.first(),hit.nodeId());assertEquals(0,hit.primitiveIndex());assertEquals(100L,hit.sourceFaceId());assertTrue(Math.abs(hit.distance()-5)<1e-5);assertTrue(hit.triangleCoordinates().isPresent());
        var tinyDirection=query.nearest(new Vec3(.5f,.5f,0),new Vec3(0,0,Float.MIN_VALUE)).orElseThrow();assertTrue(Math.abs(tinyDirection.distance()-5)<1e-5);
        var excluded=query.nearest(new RayQuery(new Vec3(.5f,.5f,0),new Vec3(0,0,1),0,10,Set.of(built.first()))).orElseThrow();assertEquals(built.second(),excluded.nodeId());
        var brute=SpatialQuery.prepare(built.document().snapshot(),false).nearest(new Vec3(.5f,.5f,0),new Vec3(0,0,1)).orElseThrow();assertEquals(hit.nodeId(),brute.nodeId());assertEquals(hit.sourceFaceId(),brute.sourceFaceId());assertEquals(hit.distance(),brute.distance());
    }
    @Test void transformedNormalsScreenPickingAndStaleRevisionsAreExplicit() {
        var mesh=PolygonMesh.triangleSurface(List.of(new Vec3(-1,-1,0),new Vec3(1,-1,0),new Vec3(0,1,0)),new int[]{0,1,2},new long[]{7});var built=scene(mesh);var snapshot=built.document().snapshot();
        var camera=new Camera(new Vec3(0,0,0),new Rect(new Vec3(-1,-1,1),new Vec3(2,0,0),new Vec3(0,2,0)));
        var query=SpatialQuery.prepare(snapshot);var hit=query.pick(camera,.5f,.5f).orElseThrow();assertEquals(snapshot.revision(),hit.sceneRevision());assertTrue(Math.abs(hit.distance()-5)<1e-5);assertTrue(Math.abs(hit.worldNormal().z()-1)<1e-5);
        built.document().transact(e->e.renameNode(built.first(),"renamed"));assertNotEquals(built.document().snapshot().revision(),hit.sceneRevision());
        var ray=SpatialQuery.screenRay(camera,.5f,0);assertTrue(ray.direction().y()<0);assertTrue(Math.abs(ray.direction().length()-1)<1e-5);
    }
    @Test void openMeshCannotBecomeDielectricOrVolumeBoundary() {
        var open=PolygonMesh.triangleSurface(List.of(new Vec3(0,0,0),new Vec3(1,0,0),new Vec3(0,1,0)),new int[]{0,1,2});var d=new SceneDocument();boolean dielectric=false;
        try{d.transact(e->{var g=e.createGeometry("open",open);var m=e.createMaterial("glass",new Material("glass",new Vec3(1,1,1),Material.Kind.DIELECTRIC));var n=e.createNode("open",null,Transform.IDENTITY);e.assignGeometry(n,g,m);});}catch(IllegalArgumentException expected){dielectric=true;}
        assertTrue(dielectric);assertEquals(0,d.snapshot().nodes().size());
    }
    @Test void explicitlyValidatedClosedMeshCanBeDielectric() {
        var vertices=List.of(new Vec3(0,0,0),new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(0,0,1));
        var solid=PolygonMesh.triangleClosedSolid(vertices,new int[]{0,2,1,0,1,3,0,3,2,1,2,3});var d=new SceneDocument();var node=new NodeId[1];
        d.transact(e->{var g=e.createGeometry("tetra",solid);var m=e.createMaterial("glass",new Material("glass",new Vec3(1,1,1),Material.Kind.DIELECTRIC));node[0]=e.createNode("solid",null,new Transform(new Vec3(0,0,4),Vec3.ZERO,new Vec3(1,1,1)));e.assignGeometry(node[0],g,m);});
        var hit=SpatialQuery.prepare(d.snapshot()).nearest(new Vec3(.1f,.1f,0),new Vec3(0,0,1)).orElseThrow();assertEquals(node[0],hit.nodeId());assertTrue(solid.closedBoundary());
    }
    public static void main(String[] args){SuiteRunner.runThis();}
}
