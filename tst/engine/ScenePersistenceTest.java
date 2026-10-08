package engine;

import engine.objects.Rect;
import harness.SuiteRunner;
import harness.Test;
import math.Vec3;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.*;
import static harness.Assertions.*;

public class ScenePersistenceTest {
    private record Sample(SceneDocument document,NodeId camera,NodeId secondCamera,NodeId sharedA,NodeId sharedB){}
    private static Sample sample() {
        var d=new SceneDocument();var ids=new Object[12];
        d.transact(e->{
            ids[0]=e.createGeometry("sphere",new AnalyticSphere(Vec3.ZERO,1));
            ids[1]=e.createGeometry("open",PolygonMesh.triangleSurface(List.of(new Vec3(-2,-1,0),new Vec3(2,-1,0),new Vec3(0,2,0)),new int[]{0,1,2},new long[]{91}));
            ids[2]=e.createGeometry("box",BoxGeometry.UNIT);
            ids[3]=e.createMaterial("blue",Material.srgb("ignored",0x3b82f6));
            ids[4]=e.createMaterial("glass",new Material("ignored",new Vec3(1,1,1),Material.Kind.DIELECTRIC,1.4f,new Vec3(.1f,.2f,.3f),0,Vec3.ZERO,.2f,.1f));
            ids[5]=e.createNode("group",null,Transform.IDENTITY);
            ids[6]=e.createNode("duplicate label",(NodeId)ids[5],new Transform(new Vec3(-1,0,5),Vec3.ZERO,new Vec3(1,1,1)));e.assignGeometry((NodeId)ids[6],(GeometryId)ids[0],(MaterialId)ids[3]);
            ids[7]=e.createNode("duplicate label",(NodeId)ids[5],new Transform(new Vec3(1,0,6),Vec3.ZERO,new Vec3(1,1,1)));e.assignGeometry((NodeId)ids[7],(GeometryId)ids[0],(MaterialId)ids[3]);
            var floor=e.createNode("open floor",null,new Transform(new Vec3(0,-2,6),new Vec3(90,0,0),new Vec3(1,1,1)));e.assignGeometry(floor,(GeometryId)ids[1],(MaterialId)ids[3]);
            var box=e.createNode("cloud box",null,new Transform(new Vec3(3,0,7),Vec3.ZERO,new Vec3(1,1,1)));e.assignGeometry(box,(GeometryId)ids[2],(MaterialId)ids[4]);
            var light=e.createNode("light",null,new Transform(new Vec3(-2,3,0),Vec3.ZERO,new Vec3(1,1,1)));e.setPointLight(light,new PointLightComponent(new Vec3(1,.9f,.8f),80));
            var localCamera=new Camera(new Vec3(0,0,0),new Rect(new Vec3(-.8f,-.5f,1),new Vec3(1.6f,0,0),new Vec3(0,1,0)));
            ids[8]=e.createNode("camera",null,Transform.IDENTITY);e.setCamera((NodeId)ids[8],new CameraComponent(localCamera));
            ids[9]=e.createNode("camera",null,new Transform(new Vec3(2,0,0),Vec3.ZERO,new Vec3(1,1,1)));e.setCamera((NodeId)ids[9],new CameraComponent(localCamera));
        });
        return new Sample(d,(NodeId)ids[8],(NodeId)ids[9],(NodeId)ids[6],(NodeId)ids[7]);
    }
    private static Path temp()throws Exception {var root=Path.of("out/cli");Files.createDirectories(root);return Files.createTempDirectory(root,"scene-persistence-");}

    @Test void strictRoundTripPreservesIdentityGraphSharingLightsAndCameras()throws Exception {
        var sample=sample();var before=sample.document().snapshot();var path=temp().resolve("sample.scene.xml");SceneFiles.save(path,before);var loaded=SceneFiles.load(path);
        assertEquals(0L,loaded.revision());assertEquals(before.nodes(),loaded.nodes());assertEquals(before.geometryAssets().size(),loaded.geometryAssets().size());assertEquals(before.materialAssets().stream().map(MaterialAsset::id).toList(),loaded.materialAssets().stream().map(MaterialAsset::id).toList());
        assertEquals(loaded.requireNode(sample.sharedA()).geometry(),loaded.requireNode(sample.sharedB()).geometry());
        assertEquals(before.camera(sample.camera()),loaded.camera(sample.camera()));assertNotEquals(loaded.camera(sample.camera()),loaded.camera(sample.secondCamera()));
        assertEquals(before.toWorldSnapshot().lights(),loaded.toWorldSnapshot().lights());
        var mesh=(PolygonMesh)loaded.geometryAssets().stream().filter(a->a.geometry() instanceof PolygonMesh).findFirst().orElseThrow().geometry();assertEquals(91L,mesh.preparedGeometry().sourceFaceId(0));assertFalse(mesh.closedBoundary());
    }

    @Test void freshJvmLoadProducesMatchingSeededRender()throws Exception {
        var sample=sample();var path=temp().resolve("fresh.scene.xml");SceneFiles.save(path,sample.document().snapshot());var expected=SceneTestSupport.renderHash(sample.document().snapshot(),sample.camera());var output=path.resolveSibling("hash.txt");var log=path.resolveSibling("child.log");
        var java=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();var process=new ProcessBuilder(java,"--enable-preview","-Djava.awt.headless=true","-cp",System.getProperty("java.class.path"),"engine.ScenePersistenceProcess",path.toString(),sample.camera().toString(),output.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertEquals(0,process.waitFor());assertEquals(expected,Files.readString(output));
    }

    @Test void canonicalV3RoundTripPreservesTopologyCountersAndDerivedFaceMapping()throws Exception {
        var document=new SceneDocument();var geometry=new GeometryId[1];var node=new NodeId[1];
        document.transact(edit->{geometry[0]=edit.createGeometry("editable",BoxGeometry.UNIT.extrude(1,.75f));var material=edit.createMaterial("blue",Material.srgb("ignored",0x3b82f6));node[0]=edit.createNode("mesh",null,new Transform(new Vec3(0,0,5),Vec3.ZERO,new Vec3(1,1,1)));edit.assignGeometry(node[0],geometry[0],material);});
        var path=temp().resolve("editable.scene.xml");SceneFiles.save(path,document.snapshot());var xml=Files.readString(path);assertTrue(xml.contains("version=\"3\""));assertTrue(xml.contains("<polygon-mesh"));assertFalse(xml.contains("<triangles>"));
        var loaded=SceneFiles.load(path);var mesh=(PolygonMesh)loaded.requireGeometry(geometry[0]).geometry();assertEquals(12L,mesh.nextVertexId());assertEquals(10L,mesh.nextFaceId());assertEquals(10,mesh.faces().size());assertEquals(List.of(8L,9L,10L,11L),mesh.requireFace(1).vertexIds());assertTrue(mesh.closedBoundary());
        var hit=SpatialQuery.prepare(loaded).nearest(new Vec3(0,0,10),new Vec3(0,0,-1)).orElseThrow();assertEquals(node[0],hit.nodeId());assertEquals(1L,hit.sourceFaceId());
    }

    @Test void analyticParametersAndChosenApproximationRoundTripAsCanonicalV3()throws Exception {
        var document = new SceneDocument();
        var geometry = new GeometryId[1];
        var first = new NodeId[1];
        var second = new NodeId[1];
        document.transact(edit -> {
            geometry[0] = edit.createGeometry(
                    "shared analytic", new AnalyticSphere(new Vec3(1, 2, 3), 2));
            var material = edit.createMaterial("gray", Material.srgb("ignored", 0x808080));
            first[0] = edit.createNode("first", null, Transform.IDENTITY);
            second[0] = edit.createNode("second", null,
                    new Transform(new Vec3(4, 0, 0), Vec3.ZERO, new Vec3(1, 1, 1)));
            edit.assignGeometry(first[0], geometry[0], material);
            edit.assignGeometry(second[0], geometry[0], material);
        });
        var changed = new AnalyticSphere(new Vec3(-.5f, .25f, 1.5f), 1.25f);
        document.transact(edit ->
                edit.setAnalyticSphere(geometry[0], changed.center(), changed.radius()));

        var directory = temp();
        var analyticPath = directory.resolve("analytic-v3.scene.xml");
        SceneFiles.save(analyticPath, document.snapshot());
        var analyticXml = Files.readString(analyticPath);
        assertTrue(analyticXml.contains("version=\"3\""));
        assertTrue(analyticXml.contains("<sphere"));
        var loadedAnalytic = SceneFiles.load(analyticPath);
        assertEquals(changed, loadedAnalytic.requireGeometry(geometry[0]).geometry());
        assertEquals(geometry[0], loadedAnalytic.requireNode(first[0]).geometry().geometryId());
        assertEquals(geometry[0], loadedAnalytic.requireNode(second[0]).geometry().geometryId());

        document.transact(edit -> edit.approximateGeometryAsMesh(geometry[0], 16));
        var approximationPath = directory.resolve("approximation-v3.scene.xml");
        SceneFiles.save(approximationPath, document.snapshot());
        var approximationXml = Files.readString(approximationPath);
        assertTrue(approximationXml.contains("version=\"3\""));
        assertTrue(approximationXml.contains("<polygon-mesh"));
        assertFalse(approximationXml.contains("<sphere"));
        var loadedApproximation = SceneFiles.load(approximationPath);
        var mesh = (PolygonMesh) loadedApproximation.requireGeometry(geometry[0]).geometry();
        assertEquals(4 * 16 * 15, mesh.faces().size());
        assertEquals((long) mesh.faces().size(), mesh.nextFaceId());
        assertEquals(geometry[0], loadedApproximation.requireNode(first[0]).geometry().geometryId());
        assertEquals(geometry[0], loadedApproximation.requireNode(second[0]).geometry().geometryId());
    }

    @Test void invalidAndHostileFilesDoNotReplaceActiveDocument()throws Exception {
        var sample=sample();var before=sample.document().snapshot();var dir=temp();
        var dangling=dir.resolve("dangling.scene.xml");Files.writeString(dangling,"""
                <scene format="ray-tracing-engine-scene" version="1"><geometry-assets/><material-assets/><nodes>
                <node id="00000000-0000-0000-0000-000000000001" label="n"><transform position-x="0" position-y="0" position-z="0" rotation-x="0" rotation-y="0" rotation-z="0" scale-x="1" scale-y="1" scale-z="1"/><geometry asset="00000000-0000-0000-0000-000000000002" material="00000000-0000-0000-0000-000000000003"/></node>
                </nodes></scene>""");
        boolean rejected=false;try{SceneFiles.loadInto(dangling,sample.document());}catch(SceneFormatException expected){rejected=true;}assertTrue(rejected);assertSame(before,sample.document().snapshot());
        var doctype=dir.resolve("doctype.scene.xml");Files.writeString(doctype,"<!DOCTYPE scene [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><scene format='ray-tracing-engine-scene' version='1'><geometry-assets/><material-assets/><nodes/></scene>");rejected=false;try{SceneFiles.load(doctype);}catch(SceneFormatException expected){rejected=true;}assertTrue(rejected);
        var external=dir.resolve("external.scene.xml");Files.writeString(external,"<scene format='ray-tracing-engine-scene' version='1'><geometry-assets><external id='00000000-0000-0000-0000-000000000001' label='x' uri='x'/></geometry-assets><material-assets/><nodes/></scene>");rejected=false;try{SceneFiles.load(external);}catch(SceneFormatException expected){rejected=true;}assertTrue(rejected);
        var huge=dir.resolve("huge.scene.xml");try(var file=new RandomAccessFile(huge.toFile(),"rw")){file.setLength(SceneFiles.MAX_FILE_BYTES+1);}rejected=false;try{SceneFiles.load(huge);}catch(SceneFormatException expected){rejected=true;}assertTrue(rejected);
    }

    @Test void elementLimitAndUnknownVersionAreRejected()throws Exception {
        var dir=temp();var many=dir.resolve("elements.scene.xml");var xml=new StringBuilder("<scene format='ray-tracing-engine-scene' version='1'><geometry-assets>");for(int i=0;i<SceneFiles.MAX_ELEMENTS;i++)xml.append("<x/>");xml.append("</geometry-assets><material-assets/><nodes/></scene>");Files.writeString(many,xml);boolean rejected=false;try{SceneFiles.load(many);}catch(SceneFormatException expected){rejected=true;}assertTrue(rejected);
        var version=dir.resolve("version.scene.xml");Files.writeString(version,"<scene format='ray-tracing-engine-scene' version='4'><geometry-assets/><material-assets/><nodes/></scene>");rejected=false;try{SceneFiles.load(version);}catch(SceneFormatException expected){rejected=true;}assertTrue(rejected);
    }
    @Test void invalidEditableVersionsReferencesCountersAndBoundariesAreRejected()throws Exception {
        var dir=temp();var body="""
                <geometry-assets><editable-mesh id='00000000-0000-0000-0000-000000000001' label='m' boundary='surface' next-vertex-id='3' next-face-id='1'>
                <vertices><v id='0' x='0' y='0' z='0'/><v id='1' x='1' y='0' z='0'/><v id='2' x='0' y='1' z='0'/></vertices>
                <faces><face id='0'><vertex id='0'/><vertex id='1'/><vertex id='2'/></face></faces></editable-mesh></geometry-assets><material-assets/><nodes/>
                """;
        var underV1=dir.resolve("editable-v1.scene.xml");Files.writeString(underV1,"<scene format='ray-tracing-engine-scene' version='1'>"+body+"</scene>");rejectLoad(underV1);
        var dangling=dir.resolve("editable-dangling.scene.xml");Files.writeString(dangling,"<scene format='ray-tracing-engine-scene' version='2'>"+body.replace("<vertex id='2'/></face>","<vertex id='9'/></face>")+"</scene>");rejectLoad(dangling);
        var counter=dir.resolve("editable-counter.scene.xml");Files.writeString(counter,"<scene format='ray-tracing-engine-scene' version='2'>"+body.replace("next-vertex-id='3'","next-vertex-id='2'")+"</scene>");rejectLoad(counter);
        var duplicate=dir.resolve("editable-duplicate.scene.xml");Files.writeString(duplicate,"<scene format='ray-tracing-engine-scene' version='2'>"+body.replace("<v id='2'", "<v id='1'")+"</scene>");rejectLoad(duplicate);
        var forgedClosed=dir.resolve("editable-open-closed.scene.xml");Files.writeString(forgedClosed,"<scene format='ray-tracing-engine-scene' version='2'>"+body.replace("boundary='surface'","boundary='closed-solid'")+"</scene>");rejectLoad(forgedClosed);
    }
    @Test void legacyRepeatedFacesReconstructAndV3RejectsLegacyGeometryTags()throws Exception {
        var dir=temp();var mesh="""
                <mesh id='00000000-0000-0000-0000-000000000001' label='quad' boundary='surface'><vertices>
                <v x='0' y='0' z='0'/><v x='1' y='0' z='0'/><v x='1' y='1' z='0'/><v x='0' y='1' z='0'/>
                </vertices><triangles><t a='0' b='1' c='2' face='17'/><t a='0' b='2' c='3' face='17'/></triangles></mesh>
                """;
        var legacy=dir.resolve("legacy-group.scene.xml");Files.writeString(legacy,"<scene format='ray-tracing-engine-scene' version='1'><geometry-assets>"+mesh+"</geometry-assets><material-assets/><nodes/></scene>");
        var polygon=(PolygonMesh)SceneFiles.load(legacy).geometryAssets().getFirst().geometry();assertEquals(List.of(0L,1L,2L,3L),polygon.requireFace(17).vertexIds());
        var largeRect=dir.resolve("legacy-large-rect.scene.xml");Files.writeString(largeRect,"""
                <scene format='ray-tracing-engine-scene' version='1'><geometry-assets>
                <rect id='00000000-0000-0000-0000-000000000001' label='large' origin-x='-524865.9375' origin-y='0' origin-z='0' edge1-x='415.4455261' edge1-y='100' edge1-z='0' edge2-x='454.0234375' edge2-y='-60' edge2-z='0'/>
                </geometry-assets><material-assets/><nodes/></scene>
                """);
        var migrated=(PolygonMesh)SceneFiles.load(largeRect).geometryAssets().getFirst().geometry();assertTrue(migrated.capabilities().parallelogramEmitter());assertInstanceOf(Rect.class,migrated.preparedGeometry().primitives().getFirst());
        var invalid=dir.resolve("legacy-disconnected.scene.xml");Files.writeString(invalid,"<scene format='ray-tracing-engine-scene' version='1'><geometry-assets>"+mesh.replace("<t a='0' b='2' c='3' face='17'/>","<t a='1' b='2' c='3' face='18'/>").replace("face='18'","face='17'")+"</geometry-assets><material-assets/><nodes/></scene>");
        // A repeated group with same-direction shared edge is not representable as one polygon.
        Files.writeString(invalid,Files.readString(invalid).replace("a='1' b='2' c='3'","a='0' b='1' c='3'"));rejectLoad(invalid);
        for(String tag:List.of("<rect id='00000000-0000-0000-0000-000000000001' label='r' origin-x='0' origin-y='0' origin-z='0' edge1-x='1' edge1-y='0' edge1-z='0' edge2-x='0' edge2-y='1' edge2-z='0'/>","<box id='00000000-0000-0000-0000-000000000001' label='b'/>",mesh)){
            var path=dir.resolve("strict-v3-"+Math.abs(tag.hashCode())+".scene.xml");Files.writeString(path,"<scene format='ray-tracing-engine-scene' version='3'><geometry-assets>"+tag+"</geometry-assets><material-assets/><nodes/></scene>");rejectLoad(path);
        }
    }
    @Test void nestedLeafInvalidValuesAndDeepXmlAreFormatErrors()throws Exception {
        var dir=temp();var nested=dir.resolve("nested.scene.xml");Files.writeString(nested,"<scene format='ray-tracing-engine-scene' version='1'><geometry-assets><sphere id='00000000-0000-0000-0000-000000000001' label='s' center-x='0' center-y='0' center-z='0' radius='1'><unsupported/></sphere></geometry-assets><material-assets/><nodes/></scene>");boolean rejected=false;try{SceneFiles.load(nested);}catch(SceneFormatException expected){rejected=true;}assertTrue(rejected);
        var invalid=dir.resolve("invalid.scene.xml");Files.writeString(invalid,"<scene format='ray-tracing-engine-scene' version='1'><geometry-assets/><material-assets><material id='00000000-0000-0000-0000-000000000001' label='m' kind='diffuse' red='2' green='0' blue='0' ior='1' absorption-red='0' absorption-green='0' absorption-blue='0' roughness='0' emission-red='0' emission-green='0' emission-blue='0' scattering='0' anisotropy='0'/></material-assets><nodes/></scene>");rejected=false;try{SceneFiles.load(invalid);}catch(SceneFormatException expected){rejected=true;}assertTrue(rejected);
        var deep=dir.resolve("deep.scene.xml");var xml=new StringBuilder("<scene format='ray-tracing-engine-scene' version='1'>");for(int i=0;i<SceneFiles.MAX_XML_DEPTH+2;i++)xml.append("<x>");for(int i=0;i<SceneFiles.MAX_XML_DEPTH+2;i++)xml.append("</x>");xml.append("</scene>");Files.writeString(deep,xml);rejected=false;try{SceneFiles.load(deep);}catch(SceneFormatException expected){rejected=true;}assertTrue(rejected);
    }
    @Test void failedSavePreservesPreviousValidBytes()throws Exception {
        var sample=sample();var path=temp().resolve("preserved.scene.xml");SceneFiles.save(path,sample.document().snapshot());var before=Files.readAllBytes(path);var nodes=new ArrayList<SceneNode>();for(int i=0;i<=SceneFiles.MAX_NODES;i++)nodes.add(new SceneNode(new NodeId(new UUID(0,i+1)),"n",null,Transform.IDENTITY,null,null,null));var tooMany=new SceneSnapshot(0,0,nodes,List.of(),List.of());boolean rejected=false;try{SceneFiles.save(path,tooMany);}catch(java.io.IOException expected){rejected=true;}assertTrue(rejected);assertEquals(before,Files.readAllBytes(path));assertNotNull(SceneFiles.load(path));
    }
    private static void rejectLoad(Path path)throws Exception{boolean rejected=false;try{SceneFiles.load(path);}catch(SceneFormatException expected){rejected=true;}assertTrue(rejected);}
    public static void main(String[] args){SuiteRunner.runThis();}
}
