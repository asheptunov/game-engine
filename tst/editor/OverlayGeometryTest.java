package editor;

import editor.overlay.OverlayGeometry;
import engine.*;
import engine.objects.Rect;
import harness.SuiteRunner;
import harness.Test;
import math.Vec3;

import java.util.List;

import static harness.Assertions.*;

public class OverlayGeometryTest {
    private record Built(SceneSnapshot snapshot,NodeId group,NodeId mesh,NodeId box,NodeId light,NodeId camera){}

    @Test void selectedMeshAndGroupDescendantsProduceTransformedCachedWireframes() {
        var built=scene();var overlays=new OverlayGeometry();
        var mesh=overlays.prepare(built.snapshot,built.mesh);assertEquals(5,mesh.wireframe().size());
        assertEquals(1,overlays.cachedAssetCount());
        var again=overlays.prepare(built.snapshot,built.mesh);assertEquals(mesh.wireframe(),again.wireframe());assertEquals(1,overlays.cachedAssetCount());
        var group=overlays.prepare(built.snapshot,built.group);assertEquals(17,group.wireframe().size());assertEquals(2,overlays.cachedAssetCount());
        assertFalse(group.wireframeTruncated());
        assertTrue(group.wireframe().stream().filter(l->l.nodeId().equals(built.box)).allMatch(l->l.start().z()>=3&&l.end().z()>=3));
        assertEquals(2,group.markers().size());
    }

    @Test void markersAndHandlesProjectAndPickFromDisplayedGeometry() {
        var built=scene();var overlays=new OverlayGeometry();var camera=viewCamera();var prepared=overlays.prepare(built.snapshot,built.group);
        var frame=overlays.project(prepared,camera,OverlayGeometry.GizmoMode.TRANSLATE,800,600,80);
        assertTrue(frame.wireframe().stream().allMatch(l->l.style()==OverlayGeometry.Style.WIREFRAME_XRAY));
        assertEquals(2,frame.markers().size());assertEquals(3,frame.handles().size());
        var line=frame.handles().getFirst().lines().getFirst();double u=(line.u1()+line.u2())*.5,v=(line.v1()+line.v2())*.5;
        var handle=frame.pick(u,v,800,600,5).orElseThrow();assertEquals(OverlayGeometry.HitKind.HANDLE,handle.kind());assertEquals(built.group,handle.nodeId());
        var marker=frame.markers().stream().filter(m->m.nodeId().equals(built.light)).findFirst().orElseThrow();
        var markerHit=frame.pick(marker.u(),marker.v(),800,600,4).orElseThrow();assertEquals(OverlayGeometry.HitKind.MARKER,markerHit.kind());assertEquals(built.light,markerHit.nodeId());
        var rings=overlays.project(prepared,camera,OverlayGeometry.GizmoMode.ROTATE,800,600,80);
        assertEquals(3,rings.handles().size());assertTrue(rings.handles().stream().allMatch(h->!h.lines().isEmpty()&&h.lines().size()<=OverlayGeometry.RING_SEGMENTS));
    }

    @Test void geometrySelectionDoesNotImplicitlyIncludeChildrenAndCameraMarkerUsesOpticalEye() {
        var built=scene();var overlays=new OverlayGeometry();var prepared=overlays.prepare(built.snapshot,built.mesh);
        assertEquals(5,prepared.wireframe().size());
        var cameraMarker=prepared.markers().stream().filter(m->m.nodeId().equals(built.camera)).findFirst().orElseThrow();
        assertEquals(new Vec3(1,0,6),cameraMarker.position());
    }

    @Test void editableFaceBoundaryProjectsIndependentlyOfObjectGizmos() {
        var document=new SceneDocument();var ids=new Object[3];document.transact(edit->{
            ids[0]=edit.createMaterial("mat",Material.srgb("mat",0xffffff));
            ids[1]=edit.createGeometry("editable box",EditableMeshGeometry.from(BoxGeometry.UNIT));
            ids[2]=edit.createNode("box",null,new Transform(new Vec3(0,0,5),new Vec3(8,17,3),new Vec3(1,1,1)));
            edit.assignGeometry((NodeId)ids[2],(GeometryId)ids[1],(MaterialId)ids[0]);
        });
        var snapshot=document.snapshot();var mesh=(EditableMeshGeometry)snapshot.requireGeometry((GeometryId)ids[1]).geometry();
        long faceId=mesh.faces().getFirst().id();var overlays=new OverlayGeometry();
        var prepared=overlays.prepare(snapshot,(NodeId)ids[2],new OverlayGeometry.FaceSelection((NodeId)ids[2],(GeometryId)ids[1],faceId));
        assertEquals(mesh.requireFace(faceId).vertexIds().size(),prepared.selectedFace().size());
        var frame=overlays.project(prepared,viewCamera(),OverlayGeometry.GizmoMode.NONE,800,600,80);
        assertEquals(prepared.selectedFace().size(),frame.selectedFace().size()); assertTrue(frame.handles().isEmpty());
        assertTrue(frame.selectedFace().stream().allMatch(line->line.style()==OverlayGeometry.Style.FACE_SELECTION_XRAY));
    }

    @Test void immutableAssetCacheIsBoundedAcrossManySelections() {
        var document=new SceneDocument();var nodes=new NodeId[OverlayGeometry.MAX_CACHED_ASSETS+20];document.transact(e->{
            var material=e.createMaterial("mat",Material.srgb("mat",0xffffff));
            for(int i=0;i<nodes.length;i++){var geometry=e.createGeometry("box "+i,BoxGeometry.UNIT);nodes[i]=e.createNode("node "+i,null,new Transform(new Vec3(0,0,5),Vec3.ZERO,new Vec3(1,1,1)));e.assignGeometry(nodes[i],geometry,material);}
        });
        var overlays=new OverlayGeometry();for(var node:nodes)overlays.prepare(document.snapshot(),node);
        assertEquals(OverlayGeometry.MAX_CACHED_ASSETS,overlays.cachedAssetCount());
        assertTrue(overlays.cachedSegmentCount()<=OverlayGeometry.MAX_CACHED_SEGMENTS);
    }

    private static Built scene() {
        var document=new SceneDocument();var ids=new Object[7];document.transact(e->{
            ids[0]=e.createMaterial("mat",Material.srgb("mat",0xffffff));
            ids[1]=e.createNode("group",null,new Transform(new Vec3(0,0,5),Vec3.ZERO,new Vec3(1,1,1)));
            var mesh=TriangleMesh.surface(List.of(new Vec3(-1,-1,0),new Vec3(1,-1,0),new Vec3(1,1,0),new Vec3(-1,1,0)),new int[]{0,1,2,0,2,3});
            var mg=e.createGeometry("mesh",mesh);ids[2]=e.createNode("mesh",(NodeId)ids[1],new Transform(Vec3.ZERO,new Vec3(0,0,90),new Vec3(2,1,1)));e.assignGeometry((NodeId)ids[2],mg,(MaterialId)ids[0]);
            var bg=e.createGeometry("box",BoxGeometry.UNIT);ids[3]=e.createNode("box",(NodeId)ids[1],new Transform(new Vec3(0,0,-1),Vec3.ZERO,new Vec3(.5f,.5f,.5f)));e.assignGeometry((NodeId)ids[3],bg,(MaterialId)ids[0]);
            ids[4]=e.createNode("light",null,new Transform(new Vec3(-1,0,5),Vec3.ZERO,new Vec3(1,1,1)));e.setPointLight((NodeId)ids[4],new PointLightComponent(new Vec3(1,1,1),5));
            ids[5]=e.createNode("camera",null,new Transform(new Vec3(1,0,6),Vec3.ZERO,new Vec3(1,1,1)));e.setCamera((NodeId)ids[5],new CameraComponent(viewCamera()));
        });return new Built(document.snapshot(),(NodeId)ids[1],(NodeId)ids[2],(NodeId)ids[3],(NodeId)ids[4],(NodeId)ids[5]);
    }
    private static Camera viewCamera(){return new Camera(Vec3.ZERO,new Rect(new Vec3(-1,-.75f,1),new Vec3(2,0,0),new Vec3(0,1.5f,0))).validated();}
    public static void main(String[] args){SuiteRunner.runThis();}
}
