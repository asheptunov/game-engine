package engine;

import harness.SuiteRunner;
import harness.Test;
import math.Vec3;

import java.util.*;

import static harness.Assertions.*;

public class EditableMeshTest {
    private static final Material GRAY=new Material("ignored",new Vec3(.5f,.5f,.5f));
    private static PolygonMesh.Vertex v(long id,float x,float y,float z){return new PolygonMesh.Vertex(id,new Vec3(x,y,z));}
    private static PolygonMesh.Face f(long id,Long...vertices){return new PolygonMesh.Face(id,List.of(vertices));}

    @Test void stableTopologyAdjacencyAndFanTriangulationAreDeterministic() {
        var mesh=PolygonMesh.surface(
                List.of(v(10,-1,-1,0),v(20,1,-1,0),v(30,1,1,0),v(40,-1,1,0)),
                List.of(f(7,10L,20L,30L,40L)),41,8);
        assertEquals(List.of(10L,20L,30L,40L),mesh.faces().getFirst().vertexIds());assertEquals(1,mesh.preparedGeometry().primitives().size());
        assertEquals(7L,mesh.preparedGeometry().sourceFaceId(0));assertFalse(mesh.preparedGeometry().indexedTriangles());
        assertEquals(4,mesh.edges().size());assertTrue(mesh.edges().stream().allMatch(PolygonMesh.Edge::boundary));
        assertEquals(new Vec3(0,0,1),mesh.faceNormal(7));

        var adjacent=PolygonMesh.surface(
                List.of(v(0,0,0,0),v(1,1,0,0),v(2,0,1,0),v(3,1,1,0)),
                List.of(f(10,0L,1L,2L),f(20,2L,1L,3L)),4,21);
        assertEquals(List.of(20L),adjacent.adjacentFaceIds(10));assertEquals(List.of(10L),adjacent.adjacentFaceIds(20));
        var shared=adjacent.edges().stream().filter(edge->edge.faceIds().size()==2).findFirst().orElseThrow();
        assertEquals(1L,shared.firstVertexId());assertEquals(2L,shared.secondVertexId());assertEquals(List.of(10L,20L),shared.faceIds());
    }

    @Test void invalidPolygonTopologyIdsAndCountersAreRejected() {
        rejects(()->PolygonMesh.surface(List.of(v(0,0,0,0),v(1,2,0,0),v(2,1,.25f,0),v(3,2,1,0),v(4,0,1,0)),List.of(f(0,0L,1L,2L,3L,4L)),5,1),"convex");
        rejects(()->PolygonMesh.surface(List.of(v(0,0,0,0),v(1,1,0,0),v(2,1,1,.1f),v(3,0,1,0)),List.of(f(0,0L,1L,2L,3L)),4,1),"planar");
        rejects(()->PolygonMesh.surface(List.of(v(0,0,0,0),v(1,1,1,0),v(2,0,1,0),v(3,1,0,0)),List.of(f(0,0L,1L,2L,3L)),4,1),"convex");
        rejects(()->PolygonMesh.surface(List.of(v(0,0,0,0),v(1,1,1,0),v(2,1,2,0),v(3,0,1,0),v(4,2,1,0),v(5,0,3,0)),List.of(f(0,0L,1L,2L,3L,4L,5L)),6,1),"self-crossing");
        rejects(()->PolygonMesh.surface(List.of(v(0,0,0,0),v(1,1,0,0),v(2,0,1,0)),List.of(f(0,0L,1L,1L)),3,1),"repeats");
        rejects(()->PolygonMesh.surface(List.of(v(0,0,0,0),v(1,1,0,0),v(2,0,1,0)),List.of(f(0,0L,1L,4L)),5,1),"missing");
        rejects(()->PolygonMesh.surface(List.of(v(0,0,0,0),v(1,1,0,0),v(2,0,1,0),v(3,4,4,4)),List.of(f(0,0L,1L,2L)),4,1),"Unused");
        rejects(()->PolygonMesh.surface(List.of(v(0,0,0,0),v(1,1,0,0),v(2,0,1,0),v(3,1,1,0)),List.of(f(0,0L,1L,2L),f(1,1L,2L,3L)),4,2),"winding");
        rejects(()->PolygonMesh.surface(List.of(v(0,0,0,0),v(1,1,0,0),v(2,0,1,0)),List.of(f(0,0L,1L,2L)),2,1),"Next editable vertex");
        var exhausted=PolygonMesh.surface(List.of(v(0,0,0,0),v(1,1,0,0),v(2,0,1,0)),List.of(f(0,0L,1L,2L)),Long.MAX_VALUE,Long.MAX_VALUE);
        rejects(()->exhausted.extrude(0,1),"exhausted");
    }

    @Test void supportedPrimitiveConversionsPreserveIntentAndSourceFaces() {
        var box=PolygonMesh.from(BoxGeometry.UNIT);assertTrue(box.closedBoundary());assertEquals(6,box.faces().size());assertEquals(12,box.preparedGeometry().primitives().size());
        var rect=PolygonMesh.from(PolygonMesh.parallelogram(new Vec3(2,3,4),new Vec3(2,0,0),new Vec3(0,1,0)));assertFalse(rect.closedBoundary());assertEquals(1,rect.faces().size());assertEquals(1,rect.preparedGeometry().primitives().size());
        var sphere=PolygonMesh.from(new AnalyticSphere(new Vec3(2,0,0),2));assertTrue(sphere.closedBoundary());assertEquals(224,sphere.faces().size());
        var triangles=PolygonMesh.triangleSurface(List.of(new Vec3(0,0,0),new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(1,1,0)),new int[]{0,1,2,2,1,3},new long[]{17,29});
        var converted=PolygonMesh.from(triangles);assertEquals(List.of(17L,29L),converted.faces().stream().map(PolygonMesh.Face::id).toList());assertEquals(30L,converted.nextFaceId());
        assertSame(converted,PolygonMesh.from(converted));
        var merged=PolygonMesh.triangleSurface(triangles.preparedGeometry().vertices(),triangles.preparedGeometry().indices(),new long[]{4,4});assertEquals(1,merged.faces().size());assertEquals(4L,merged.faces().getFirst().id());assertEquals(4,merged.faces().getFirst().vertexIds().size());
        rejects(()->PolygonMesh.from(PolygonMesh.triangleSurface(List.of(new Vec3(0,0,0),new Vec3(1,0,0),new Vec3(0,1,0)),new int[]{0,1,2},new long[]{-1})),"nonnegative");
        rejects(()->PolygonMesh.from(PolygonMesh.triangleSurface(List.of(new Vec3(0,0,0),new Vec3(1,0,0),new Vec3(0,1,0)),new int[]{0,1,2},new long[]{Long.MAX_VALUE})),"allocatable");
    }

    @Test void positiveExtrusionRetainsCapIdentityAddsStableSidesAndCanRepeat() {
        var box=PolygonMesh.from(BoxGeometry.UNIT);var once=box.extrude(1,1);
        assertTrue(once.closedBoundary());assertEquals(12,once.editableVertices().size());assertEquals(10,once.faces().size());assertEquals(20,once.preparedGeometry().primitives().size());
        assertEquals(List.of(8L,9L,10L,11L),once.requireFace(1).vertexIds());assertEquals(6L,once.faces().get(6).id());
        assertEquals(List.of(4L,5L,9L,8L),once.requireFace(6).vertexIds());assertEquals(new Vec3(0,0,1),once.faceNormal(1));
        assertEquals(2f,once.requireVertex(8).position().z());
        var twice=once.extrude(1,.5f);assertEquals(16,twice.editableVertices().size());assertEquals(14,twice.faces().size());assertEquals(2.5f,twice.requireVertex(12).position().z());
        assertEquals(16L,twice.nextVertexId());assertEquals(14L,twice.nextFaceId());
        rejects(()->box.extrude(1,0),"positive");rejects(()->box.extrude(99,1),"Unknown");
    }

    @Test void vertexAndCanonicalEdgeTranslationsPreserveStableTopologyAndValidateWholeMesh() {
        var triangle=PolygonMesh.surface(
                List.of(v(4,0,0,0),v(8,2,0,0),v(12,0,2,0)),
                List.of(f(20,4L,8L,12L)),13,21);

        var movedVertex=triangle.translateVertex(8,new Vec3(.25f,.5f,1));
        assertEquals(new Vec3(2.25f,.5f,1),movedVertex.requireVertex(8).position());
        assertEquals(triangle.requireVertex(4),movedVertex.requireVertex(4));
        assertEquals(triangle.faces(),movedVertex.faces());
        assertEquals(triangle.nextVertexId(),movedVertex.nextVertexId());

        var movedEdge=triangle.translateEdge(12,8,new Vec3(-.5f,0,.25f));
        assertEquals(new Vec3(1.5f,0,.25f),movedEdge.requireVertex(8).position());
        assertEquals(new Vec3(-.5f,2,.25f),movedEdge.requireVertex(12).position());
        assertEquals(new Vec3(0,0,0),movedEdge.requireVertex(4).position());
        rejects(()->triangle.translateEdge(4,99,Vec3.ZERO),"Unknown editable edge");

        var quad=PolygonMesh.parallelogram(Vec3.ZERO,new Vec3(2,0,0),new Vec3(0,2,0));
        rejects(()->quad.translateVertex(0,new Vec3(0,0,1)),"planar");
        assertSame(quad,quad.translateVertex(0,Vec3.ZERO));
    }

    @Test void sharedTransactionsQueriesHistoryAndMaterialValidationStayAtomic() {
        var document=new SceneDocument();var history=new UndoHistory(document,10);var geometry=new GeometryId[1];var first=new NodeId[1];var second=new NodeId[1];
        history.edit("build",edit->{geometry[0]=edit.createGeometry("box",PolygonMesh.from(BoxGeometry.UNIT));var material=edit.createMaterial("gray",GRAY);first[0]=edit.createNode("first",null,new Transform(new Vec3(0,0,5),Vec3.ZERO,new Vec3(1,1,1)));edit.assignGeometry(first[0],geometry[0],material);second[0]=edit.createNode("second",null,new Transform(new Vec3(4,0,5),Vec3.ZERO,new Vec3(1,1,1)));edit.assignGeometry(second[0],geometry[0],material);});
        long before=document.snapshot().revision();history.edit("extrude",edit->edit.extrudeFace(geometry[0],1,1));assertTrue(document.snapshot().revision()>before);
        assertEquals(geometry[0],document.snapshot().requireNode(first[0]).geometry().geometryId());assertEquals(geometry[0],document.snapshot().requireNode(second[0]).geometry().geometryId());
        var query=SpatialQuery.prepare(document.snapshot());var cap=query.nearest(new Vec3(0,0,10),new Vec3(0,0,-1)).orElseThrow();assertEquals(1L,cap.sourceFaceId());assertEquals(first[0],cap.nodeId());
        var sharedCap=query.nearest(new Vec3(4,0,10),new Vec3(0,0,-1)).orElseThrow();assertEquals(1L,sharedCap.sourceFaceId());assertEquals(second[0],sharedCap.nodeId());assertEquals(cap.geometryId(),sharedCap.geometryId());
        var side=query.nearest(new Vec3(0,-5,6.5f),new Vec3(0,1,0)).orElseThrow();assertEquals(6L,side.sourceFaceId());
        long editRevision=document.snapshot().revision();history.undo();assertTrue(document.snapshot().revision()>editRevision);assertEquals(6,((PolygonMesh)document.snapshot().requireGeometry(geometry[0]).geometry()).faces().size());long undoRevision=document.snapshot().revision();history.redo();assertTrue(document.snapshot().revision()>undoRevision);
        document.transact(edit->edit.makeGeometryUnique(second[0]));var unique=document.snapshot().requireNode(second[0]).geometry().geometryId();document.transact(edit->edit.extrudeFace(unique,1,.25f));
        assertEquals(10,((PolygonMesh)document.snapshot().requireGeometry(geometry[0]).geometry()).faces().size());assertEquals(14,((PolygonMesh)document.snapshot().requireGeometry(unique).geometry()).faces().size());

        var emission=new SceneDocument();var emissionGeometry=new GeometryId[1];emission.transact(edit->{emissionGeometry[0]=edit.createGeometry("light",PolygonMesh.parallelogram(Vec3.ZERO,new Vec3(1,0,0),new Vec3(0,1,0)));var material=edit.createMaterial("emit",new Material("ignored",new Vec3(1,1,1),Material.Kind.DIFFUSE,1,Vec3.ZERO,0,new Vec3(2,2,2),0,0));var node=edit.createNode("light",null,Transform.IDENTITY);edit.assignGeometry(node,emissionGeometry[0],material);});
        var unchanged=emission.snapshot();emission.transact(edit->edit.convertGeometryToEditable(emissionGeometry[0]));assertSame(unchanged.geometryAssets().getFirst().geometry(),emission.snapshot().geometryAssets().getFirst().geometry());
        rejects(()->emission.transact(edit->edit.extrudeFace(emissionGeometry[0],0,.25f)),"Emission requires");
        var volume=new SceneDocument();var volumeGeometry=new GeometryId[1];volume.transact(edit->{volumeGeometry[0]=edit.createGeometry("volume box",BoxGeometry.UNIT);var material=edit.createMaterial("cloud",new Material("ignored",new Vec3(1,1,1),Material.Kind.DIELECTRIC,1.2f,Vec3.ZERO,0,Vec3.ZERO,.5f,0));var node=edit.createNode("cloud",null,Transform.IDENTITY);edit.assignGeometry(node,volumeGeometry[0],material);});
        volume.transact(edit->edit.convertGeometryToEditable(volumeGeometry[0]));assertInstanceOf(PolygonMesh.class,volume.snapshot().requireGeometry(volumeGeometry[0]).geometry());var beforeVolumeExtrude=volume.snapshot();rejects(()->volume.transact(edit->edit.extrudeFace(volumeGeometry[0],1,.25f)),"Scattering requires");assertSame(beforeVolumeExtrude,volume.snapshot());
        var open=PolygonMesh.surface(List.of(v(0,0,0,0),v(1,1,0,0),v(2,0,1,0)),List.of(f(0,0L,1L,2L)),3,1);
        var glass=new SceneDocument();rejects(()->glass.transact(edit->{var g=edit.createGeometry("open",open);var m=edit.createMaterial("glass",new Material("ignored",new Vec3(1,1,1),Material.Kind.DIELECTRIC));var n=edit.createNode("open",null,Transform.IDENTITY);edit.assignGeometry(n,g,m);}),"Glass requires");assertEquals(0,glass.snapshot().nodes().size());
    }

    @Test void topologyOnlyCounterChangeIsSceneContentButNotTransport() {
        var document=new SceneDocument();var id=new GeometryId[1];var base=PolygonMesh.from(BoxGeometry.UNIT);document.transact(edit->id[0]=edit.createGeometry("box",base));var before=document.snapshot();
        var changed=PolygonMesh.closedSolid(base.editableVertices(),base.faces(),base.nextVertexId()+10,base.nextFaceId()+10);
        var after=document.transact(edit->edit.replaceGeometry(id[0],changed));assertTrue(after.revision()>before.revision());assertEquals(before.transportRevision(),after.transportRevision());assertTrue(after.requireGeometry(id[0]).revision()>before.requireGeometry(id[0]).revision());
    }

    @Test void capabilitiesRequireExactTinyParallelogramConnectedOutwardSolidAndCanonicalBox() {
        float s=1e-9f;var tiny=PolygonMesh.surface(List.of(v(0,0,0,0),v(1,s,0,0),v(2,.8f*s,s,0),v(3,0,s,0)),List.of(f(0,0L,1L,2L,3L)),4,1);
        assertFalse(tiny.capabilities().parallelogramEmitter());assertEquals(2,tiny.renderPrimitiveCount());
        var offset=PolygonMesh.surface(List.of(v(0,1e8f,0,0),v(1,1e8f+80,0,0),v(2,1e8f+80,80,0),v(3,1e8f+32,80,0)),List.of(f(0,0L,1L,2L,3L)),4,1);
        assertFalse(offset.capabilities().parallelogramEmitter());assertEquals(2,offset.renderPrimitiveCount());
        rejects(()->new SceneInstance("tiny",tiny,Transform.IDENTITY,new Material("emit",Vec3.ZERO).withEmission(new Vec3(1,1,1))),"Emission requires");
        var box=BoxGeometry.UNIT;assertTrue(box.capabilities().canonicalBoxVolume());
        var perturbed=new ArrayList<>(box.editableVertices());perturbed.set(0,new PolygonMesh.Vertex(0,new Vec3(-.9999999f,-1,-1)));var nearBox=PolygonMesh.closedSolid(perturbed,box.faces(),box.nextVertexId(),box.nextFaceId());assertFalse(nearBox.capabilities().canonicalBoxVolume());
        rejects(()->new SceneInstance("near box",nearBox,Transform.IDENTITY,new Material("cloud",new Vec3(1,1,1),Material.Kind.DIELECTRIC,1,Vec3.ZERO,0,Vec3.ZERO,.1f,0)),"Scattering requires");
        var inward=box.faces().stream().map(face->{var ids=new ArrayList<>(face.vertexIds());Collections.reverse(ids);return new PolygonMesh.Face(face.id(),ids);}).toList();
        rejects(()->PolygonMesh.closedSolid(box.editableVertices(),inward,box.nextVertexId(),box.nextFaceId()),"positive outward");
        var vertices=new ArrayList<PolygonMesh.Vertex>();var faces=new ArrayList<PolygonMesh.Face>();
        for(int part=0;part<2;part++){long o=part*4;float x=part*3;vertices.add(v(o,x,0,0));vertices.add(v(o+1,x+1,0,0));vertices.add(v(o+2,x,1,0));vertices.add(v(o+3,x,0,1));faces.add(f(part*4,o,o+2,o+1));faces.add(f(part*4+1,o,o+1,o+3));faces.add(f(part*4+2,o,o+3,o+2));faces.add(f(part*4+3,o+1,o+2,o+3));}
        rejects(()->PolygonMesh.closedSolid(vertices,faces,8,8),"connected");
    }

    @Test void repeatedLegacyFacesReconstructOnlyBoundedConvexDisksFromMinimumVertex() {
        var pentagon=List.of(new Vec3(0,0,0),new Vec3(2,0,0),new Vec3(3,1,0),new Vec3(1.5f,2,0),new Vec3(0,1,0));
        var merged=PolygonMesh.triangleSurface(pentagon,new int[]{0,3,4,0,1,2,0,2,3},new long[]{7,7,7});
        assertEquals(List.of(0L,1L,2L,3L,4L),merged.requireFace(7).vertexIds());
        rejects(()->PolygonMesh.triangleSurface(List.of(new Vec3(0,0,0),new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(3,0,0),new Vec3(4,0,0),new Vec3(3,1,0)),new int[]{0,1,2,3,4,5},new long[]{2,2}),"complete triangulated disk");
        rejects(()->PolygonMesh.triangleSurface(List.of(new Vec3(0,0,0),new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(-1,0,0),new Vec3(0,-1,0)),new int[]{0,1,2,0,3,4},new long[]{2,2}),"complete triangulated disk");
        rejects(()->PolygonMesh.triangleSurface(List.of(new Vec3(0,0,0),new Vec3(1,0,0),new Vec3(1,1,0),new Vec3(0,1,0),new Vec3(.5f,.5f,0)),new int[]{0,1,4,1,2,4,2,3,4,3,0,4},new long[]{3,3,3,3}),"complete triangulated disk");
        rejects(()->PolygonMesh.triangleSurface(List.of(new Vec3(0,0,0),new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(1,1,0)),new int[]{0,1,2,1,2,3},new long[]{4,4}),"winding");
        rejects(()->PolygonMesh.triangleSurface(List.of(new Vec3(0,0,0),new Vec3(1,0,0),new Vec3(0,1,0)),new int[]{0,1,2,0,1,2},new long[]{5,5}),"complete triangulated disk");
        var hostilePoints=new ArrayList<Vec3>();var hostileIndices=new int[600];var hostileFaces=new long[200];for(int i=0;i<600;i++){hostilePoints.add(new Vec3(i%3,i/3,0));hostileIndices[i]=i;}Arrays.fill(hostileFaces,9);
        rejects(()->PolygonMesh.triangleSurface(hostilePoints,hostileIndices,hostileFaces),"complete triangulated disk");
    }

    @Test void topologyIdentityChangesDoNotChangeTransportIdentity() {
        var a=PolygonMesh.surface(List.of(v(0,0,0,0),v(1,1,0,0),v(2,0,1,0)),List.of(f(4,0L,1L,2L)),3,5);
        var b=PolygonMesh.surface(List.of(v(10,0,0,0),v(11,1,0,0),v(12,0,1,0)),List.of(f(40,10L,11L,12L)),99,80);
        var first=new SceneInstance("same",a,Transform.IDENTITY,GRAY);var second=new SceneInstance("same",b,Transform.IDENTITY,GRAY);
        assertEquals(first,second);assertEquals(first.hashCode(),second.hashCode());assertFalse(GeometryValues.equal(a,b));
    }

    private static void rejects(Runnable action,String message) {
        try{action.run();throw new AssertionError("Expected rejection containing: "+message);}catch(IllegalArgumentException|IllegalStateException expected){assertTrue(expected.getMessage().contains(message));}
    }
    public static void main(String[] args){SuiteRunner.runThis();}
}
