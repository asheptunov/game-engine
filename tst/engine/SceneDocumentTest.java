package engine;

import engine.objects.Rect;
import harness.SuiteRunner;
import harness.Test;
import math.Vec3;
import java.util.concurrent.atomic.AtomicReference;

import static harness.Assertions.*;

public class SceneDocumentTest {
    private static final Material GRAY=new Material("ignored",new Vec3(.5f,.5f,.5f));
    private static final Camera CAMERA=new Camera(new Vec3(0,0,0),new Rect(new Vec3(-.5f,-.5f,1),new Vec3(1,0,0),new Vec3(0,1,0)));
    private static Transform at(float x,float y,float z){return new Transform(new Vec3(x,y,z),Vec3.ZERO,new Vec3(1,1,1));}

    @Test void transactionsSharingSubtreesAndUniqueAssetsAreAtomic() {
        var document=new SceneDocument();var ids=new Object[5];
        var first=document.transact(edit->{
            ids[0]=edit.createGeometry("sphere",new SphereGeometry(Vec3.ZERO,1));
            ids[1]=edit.createMaterial("gray",GRAY);
            ids[2]=edit.createNode("root",null,at(0,0,3));
            ids[3]=edit.createNode("part",(NodeId)ids[2],Transform.IDENTITY);
            edit.assignGeometry((NodeId)ids[3],(GeometryId)ids[0],(MaterialId)ids[1]);
        });
        assertEquals(1L,first.revision());assertEquals(1L,first.transportRevision());
        var renamed=document.transact(e->e.renameNode((NodeId)ids[3],"duplicate label"));
        assertEquals(2L,renamed.revision());assertEquals(1L,renamed.transportRevision());
        var cameraOnly=document.transact(e->e.setCamera((NodeId)ids[2],new CameraComponent(CAMERA)));
        assertEquals(1L,cameraOnly.transportRevision());
        var history=new UndoHistory(document,2);long transport=cameraOnly.transportRevision();
        var moved=history.edit("move",e->e.setLocalTransform((NodeId)ids[3],at(.25f,0,0)));assertTrue(moved.transportRevision()>transport);
        long movedTransport=moved.transportRevision();history.undo();assertTrue(document.snapshot().transportRevision()>movedTransport);
        var before=document.snapshot();boolean failed=false;
        try{document.transact(e->e.reparentKeepingLocal((NodeId)ids[2],(NodeId)ids[3]));}catch(IllegalArgumentException expected){failed=true;}
        assertTrue(failed);assertSame(before,document.snapshot());
        var duplicated=document.transact(e->ids[4]=e.duplicateSubtree((NodeId)ids[2]));
        assertEquals(4,duplicated.nodes().size());
        var duplicateChild=duplicated.nodes().stream().filter(n->ids[4].equals(n.parentId())).findFirst().orElseThrow();
        assertEquals(((SceneNode)duplicated.requireNode((NodeId)ids[3])).geometry(),duplicateChild.geometry());
        document.transact(e->e.makeMaterialUnique(duplicateChild.id()));
        assertNotEquals(document.snapshot().requireNode((NodeId)ids[3]).geometry().materialId(),document.snapshot().requireNode(duplicateChild.id()).geometry().materialId());
        document.transact(e->e.makeGeometryUnique(duplicateChild.id()));
        assertNotEquals(document.snapshot().requireNode((NodeId)ids[3]).geometry().geometryId(),document.snapshot().requireNode(duplicateChild.id()).geometry().geometryId());
        document.transact(e->e.deleteSubtree((NodeId)ids[2]));
        assertEquals(2,document.snapshot().nodes().size());
    }

    @Test void failedNestedAndThrowingCallbacksNeverPublish() {
        var document=new SceneDocument();var before=document.snapshot();boolean nested=false;
        try{document.transact(e->document.transact(inner->inner.createNode("bad",null,Transform.IDENTITY)));}catch(IllegalStateException expected){nested=true;}
        assertTrue(nested);assertSame(before,document.snapshot());
        boolean replaced=false;
        try{document.transact(e->document.replace(SceneSnapshot.empty()));}catch(IllegalStateException expected){replaced=true;}
        assertTrue(replaced);assertSame(before,document.snapshot());
        boolean thrown=false;
        try{document.transact(e->{e.createNode("unpublished",null,Transform.IDENTITY);throw new IllegalArgumentException("stop");});}catch(IllegalArgumentException expected){thrown=true;}
        assertTrue(thrown);assertSame(before,document.snapshot());
        document.transact(e->e.createNode("later",null,Transform.IDENTITY));assertEquals(1,document.snapshot().nodes().size());
    }

    @Test void historyIsOptionalBoundedFreshAndInvalidatedByDirectEdits() throws Exception {
        var document=new SceneDocument();var history=new UndoHistory(document,2);var id=new NodeId[1];
        history.edit("create",e->id[0]=e.createNode("one",null,Transform.IDENTITY));
        history.edit("rename",e->e.renameNode(id[0],"two"));
        long editedRevision=document.snapshot().revision();history.undo();
        assertTrue(document.snapshot().revision()>editedRevision);assertEquals("one",document.snapshot().requireNode(id[0]).label());
        history.redo();assertEquals("two",document.snapshot().requireNode(id[0]).label());
        document.transact(e->e.renameNode(id[0],"outside"));assertFalse(history.canUndo());assertFalse(history.canRedo());
        var zero=new UndoHistory(document,0);zero.edit("zero",e->e.renameNode(id[0],"zero"));assertFalse(zero.canUndo());

        var owned=new UndoHistory(document,2);owned.edit("owner",e->e.renameNode(id[0],"owner"));
        int undoBefore=owned.undoSize();var content=document.snapshot();var error=new AtomicReference<Throwable>();
        var thread=new Thread(()->{try{owned.undo();}catch(Throwable t){error.set(t);}});thread.start();thread.join();
        assertInstanceOf(IllegalStateException.class,error.get());assertSame(content,document.snapshot());assertEquals(undoBefore,owned.undoSize());
    }

    @Test void hierarchyRejectsShearAndDepthWithoutPartialPublication() {
        var document=new SceneDocument();var ids=new NodeId[2];document.transact(e->{ids[0]=e.createNode("scaled",null,new Transform(Vec3.ZERO,Vec3.ZERO,new Vec3(2,1,1)));ids[1]=e.createNode("child",ids[0],Transform.IDENTITY);});
        var before=document.snapshot();boolean shear=false;
        try{document.transact(e->e.setLocalTransform(ids[1],new Transform(Vec3.ZERO,new Vec3(0,0,45),new Vec3(1,1,1))));}catch(IllegalArgumentException expected){shear=true;}
        assertTrue(shear);assertSame(before,document.snapshot());

        var deep=new SceneDocument();boolean bounded=false;
        try{deep.transact(e->{NodeId parent=null;for(int i=0;i<=SceneSnapshot.MAX_HIERARCHY_DEPTH;i++)parent=e.createNode("n",parent,Transform.IDENTITY);});}
        catch(IllegalArgumentException expected){bounded=true;}
        assertTrue(bounded);assertEquals(0,deep.snapshot().nodes().size());
    }

    @Test void transformCompositionMatchesSequentialPointsAndVectorsAtGimbalPoles() {
        for(float pitch:new float[]{90,-90}) {
            var parent=new Transform(new Vec3(2,-1,3),new Vec3(10,20,30),new Vec3(2,2,2));
            var local=new Transform(new Vec3(1,2,3),new Vec3(30,pitch,0),new Vec3(.5f,.5f,.5f));
            var composed=Transform.compose(parent,local);var point=new Vec3(.3f,-.4f,.8f);var vector=new Vec3(-.2f,.7f,.1f);
            near(parent.point(local.point(point)),composed.point(point));near(parent.vector(local.vector(vector)),composed.vector(vector));
        }
    }

    @Test void duplicateAcceptsMaximumLengthLabels() {
        var label="x".repeat(256);var document=new SceneDocument();var id=new NodeId[1];document.transact(e->id[0]=e.createNode(label,null,Transform.IDENTITY));
        document.transact(e->e.duplicateSubtree(id[0]));assertEquals(2,document.snapshot().nodes().size());assertEquals(label,document.snapshot().nodes().getLast().label());
    }
    private static void near(Vec3 expected,Vec3 actual){assertTrue(expected.sub(actual).length()<2e-4f);}
    public static void main(String[] args){SuiteRunner.runThis();}
}
