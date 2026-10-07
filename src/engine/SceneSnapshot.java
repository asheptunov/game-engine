package engine;

import engine.lights.Light;
import engine.lights.PointLight;
import math.Vec3;
import java.util.*;

/** Immutable graph/assets publication consumed by render and query workers. */
public final class SceneSnapshot {
    /** Explicit structural bound for authored hierarchy traversal. */
    public static final int MAX_HIERARCHY_DEPTH=4096;
    record RenderEntry(SceneNode node, GeometryAsset geometry, MaterialAsset material,
                       Transform worldTransform, SceneInstance instance) {}
    private final long revision;
    private final long transportRevision;
    private final List<SceneNode> nodes;
    private final List<GeometryAsset> geometries;
    private final List<MaterialAsset> materials;
    private final Map<NodeId,SceneNode> nodeById;
    private final Map<GeometryId,GeometryAsset> geometryById;
    private final Map<MaterialId,MaterialAsset> materialById;
    private final Map<NodeId,Transform> worldTransforms;
    private final List<RenderEntry> renderEntries;
    private final WorldSnapshot world;

    SceneSnapshot(long revision,long transportRevision, List<SceneNode> nodes, List<GeometryAsset> geometries,
                  List<MaterialAsset> materials) {
        if(revision<0||transportRevision<0)throw new IllegalArgumentException("Scene revisions must be nonnegative");
        this.revision=revision;this.transportRevision=transportRevision;this.nodes=List.copyOf(nodes);this.geometries=List.copyOf(geometries);this.materials=List.copyOf(materials);
        nodeById=unique(this.nodes,SceneNode::id,"node");
        geometryById=unique(this.geometries,GeometryAsset::id,"geometry asset");
        materialById=unique(this.materials,MaterialAsset::id,"material asset");
        worldTransforms=computeWorldTransforms();
        var entries=new ArrayList<RenderEntry>();var lights=new ArrayList<Light>();
        for(var node:this.nodes) {
            var transform=worldTransforms.get(node.id());
            if(node.geometry()!=null) {
                var geometry=requireGeometry(node.geometry().geometryId());
                var material=requireMaterial(node.geometry().materialId());
                var value=materialWithIdentity(material);
                var instance=new SceneInstance(node.id().toString(),geometry.geometry().primitives(),transform,value);
                entries.add(new RenderEntry(node,geometry,material,transform,instance));
            }
            if(node.light()!=null)lights.add(new PointLight(transform.point(Vec3.ZERO),node.light().color(),node.light().intensity()));
            if(node.camera()!=null)node.camera().camera().transformed(transform);
        }
        renderEntries=List.copyOf(entries);
        world=new WorldSnapshot(transportRevision,entries.stream().map(RenderEntry::instance).toList(),List.of(),lights);
    }
    public static SceneSnapshot empty(){return new SceneSnapshot(0,0,List.of(),List.of(),List.of());}
    private static <K,V> Map<K,V> unique(List<V> values, java.util.function.Function<V,K> key, String kind) {
        var result=new LinkedHashMap<K,V>();
        for(var value:values) {
            if(value==null)throw new IllegalArgumentException("Scene cannot contain null "+kind);
            K id=key.apply(value);if(result.put(id,value)!=null)throw new IllegalArgumentException("Duplicate "+kind+" ID: "+id);
        }
        return Collections.unmodifiableMap(result);
    }
    private Map<NodeId,Transform> computeWorldTransforms() {
        var result=new LinkedHashMap<NodeId,Transform>();var depths=new HashMap<NodeId,Integer>();
        for(var start:nodes) {
            if(result.containsKey(start.id()))continue;
            var chain=new ArrayList<SceneNode>();var path=new HashSet<NodeId>();var cursor=start;
            while(!result.containsKey(cursor.id())) {
                if(!path.add(cursor.id()))throw new IllegalArgumentException("Scene hierarchy contains a cycle at "+cursor.id());
                chain.add(cursor);if(chain.size()>MAX_HIERARCHY_DEPTH)throw new IllegalArgumentException("Scene hierarchy exceeds depth limit "+MAX_HIERARCHY_DEPTH);
                if(cursor.parentId()==null)break;
                cursor=nodeById.get(cursor.parentId());
                if(cursor==null)throw new IllegalArgumentException("Missing parent "+chain.getLast().parentId()+" for node "+chain.getLast().id());
            }
            for(int i=chain.size()-1;i>=0;i--) {
                var node=chain.get(i);Transform value=node.localTransform();
                if(node.parentId()!=null)value=Transform.compose(result.get(node.parentId()),value);
                int depth=node.parentId()==null?1:depths.get(node.parentId())+1;
                if(depth>MAX_HIERARCHY_DEPTH)throw new IllegalArgumentException("Scene hierarchy exceeds depth limit "+MAX_HIERARCHY_DEPTH);
                depths.put(node.id(),depth);
                result.put(node.id(),value);
            }
        }
        return Collections.unmodifiableMap(result);
    }
    private static Material materialWithIdentity(MaterialAsset asset) {
        var m=asset.material();
        return new Material(asset.id().toString(),m.color(),m.kind(),m.ior(),m.absorption(),m.roughness(),m.emission(),m.scattering(),m.anisotropy());
    }
    public long revision(){return revision;}
    /** Changes only when flattened geometry/material/light transport content changes. */
    public long transportRevision(){return transportRevision;}
    public List<SceneNode> nodes(){return nodes;}
    public List<GeometryAsset> geometryAssets(){return geometries;}
    public List<MaterialAsset> materialAssets(){return materials;}
    public Optional<SceneNode> findNode(NodeId id){return Optional.ofNullable(nodeById.get(id));}
    public SceneNode requireNode(NodeId id){var value=nodeById.get(id);if(value==null)throw new IllegalArgumentException("Unknown node: "+id);return value;}
    public Optional<GeometryAsset> findGeometry(GeometryId id){return Optional.ofNullable(geometryById.get(id));}
    public GeometryAsset requireGeometry(GeometryId id){var value=geometryById.get(id);if(value==null)throw new IllegalArgumentException("Unknown geometry asset: "+id);return value;}
    public Optional<MaterialAsset> findMaterial(MaterialId id){return Optional.ofNullable(materialById.get(id));}
    public MaterialAsset requireMaterial(MaterialId id){var value=materialById.get(id);if(value==null)throw new IllegalArgumentException("Unknown material asset: "+id);return value;}
    public Transform worldTransform(NodeId id){requireNode(id);return worldTransforms.get(id);}
    public Camera camera(NodeId id){var n=requireNode(id);if(n.camera()==null)throw new IllegalArgumentException("Node has no camera: "+id);return n.camera().camera().transformed(worldTransform(id));}
    public RenderView renderView(NodeId cameraId,int width,int height){return new RenderView(camera(cameraId),width,height);}
    public WorldSnapshot toWorldSnapshot(){return world;}
    List<RenderEntry> renderEntries(){return renderEntries;}

    /** Compare persistent scene content while ignoring runtime publication revisions. */
    public boolean sameContent(SceneSnapshot other) {
        if(other==null||!nodes.equals(other.nodes)||geometries.size()!=other.geometries.size()||materials.size()!=other.materials.size())return false;
        for(int i=0;i<geometries.size();i++){var a=geometries.get(i);var b=other.geometries.get(i);if(!a.id().equals(b.id())||!a.label().equals(b.label())||!GeometryValues.equal(a.geometry(),b.geometry()))return false;}
        for(int i=0;i<materials.size();i++){var a=materials.get(i);var b=other.materials.get(i);if(!a.id().equals(b.id())||!a.label().equals(b.label())||!a.material().equals(b.material()))return false;}
        return true;
    }
    boolean sameTransport(SceneSnapshot other) {
        return other!=null&&world.instances().equals(other.world.instances())&&world.lights().equals(other.world.lights());
    }
}
