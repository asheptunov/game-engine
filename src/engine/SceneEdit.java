package engine;

import math.Vec3;

import java.util.*;
import java.util.function.UnaryOperator;

/** Mutable transaction draft; only SceneDocument publishes it. */
public final class SceneEdit {
    private final SceneSnapshot base;
    private final ArrayList<SceneNode> nodes;
    private final ArrayList<GeometryAsset> geometries;
    private final ArrayList<MaterialAsset> materials;
    SceneEdit(SceneSnapshot base){this.base=base;nodes=new ArrayList<>(base.nodes());geometries=new ArrayList<>(base.geometryAssets());materials=new ArrayList<>(base.materialAssets());}

    public NodeId createNode(String label,NodeId parentId,Transform localTransform){return createNode(NodeId.random(),label,parentId,localTransform);}
    public NodeId createNode(NodeId id,String label,NodeId parentId,Transform localTransform){
        if(findNode(id)>=0)throw new IllegalArgumentException("Duplicate node ID: "+id);
        nodes.add(new SceneNode(id,label,parentId,localTransform,null,null,null));return id;
    }
    public GeometryId createGeometry(String label,GeometryData geometry){return createGeometry(GeometryId.random(),label,geometry);}
    public GeometryId createGeometry(GeometryId id,String label,GeometryData geometry){
        if(findGeometry(id)>=0)throw new IllegalArgumentException("Duplicate geometry ID: "+id);
        geometries.add(new GeometryAsset(id,label,0,geometry));return id;
    }
    public MaterialId createMaterial(String label,Material material){return createMaterial(MaterialId.random(),label,material);}
    public MaterialId createMaterial(MaterialId id,String label,Material material){
        if(findMaterial(id)>=0)throw new IllegalArgumentException("Duplicate material ID: "+id);
        materials.add(new MaterialAsset(id,label,0,material));return id;
    }
    public void renameNode(NodeId id,String label){updateNode(id,n->n.withLabel(label));}
    public void setLocalTransform(NodeId id,Transform transform){updateNode(id,n->n.withLocalTransform(transform));}
    /** Change parent while intentionally preserving the node's local, not world, transform. */
    public void reparentKeepingLocal(NodeId id,NodeId parentId){updateNode(id,n->n.withParent(parentId));}
    public void assignGeometry(NodeId id,GeometryId geometryId,MaterialId materialId){updateNode(id,n->n.withGeometry(new GeometryComponent(geometryId,materialId)));}
    public void clearGeometry(NodeId id){updateNode(id,n->n.withGeometry(null));}
    public void setPointLight(NodeId id,PointLightComponent light){updateNode(id,n->n.withLight(light));}
    public void setCamera(NodeId id,CameraComponent camera){updateNode(id,n->n.withCamera(camera));}
    public void renameGeometry(GeometryId id,String label){int i=requireGeometry(id);var a=geometries.get(i);geometries.set(i,new GeometryAsset(id,label,a.revision(),a.geometry()));}
    public void replaceGeometry(GeometryId id,GeometryData geometry){int i=requireGeometry(id);var a=geometries.get(i);geometries.set(i,new GeometryAsset(id,a.label(),a.revision(),geometry));}
    /** Replace the parameters of one shared analytic sphere asset. */
    public void setAnalyticSphere(GeometryId id, Vec3 center, float radius) {
        int index = requireGeometry(id);
        var asset = geometries.get(index);
        if (!(asset.geometry() instanceof AnalyticSphere)) {
            throw new IllegalArgumentException("Geometry is not an analytic sphere: " + id);
        }
        geometries.set(index, new GeometryAsset(
                id, asset.label(), asset.revision(), new AnalyticSphere(center, radius)));
    }

    /** Approximate one shared analytic sphere at an explicit bounded detail. */
    public void approximateGeometryAsMesh(GeometryId id, int detail) {
        int index = requireGeometry(id);
        var asset = geometries.get(index);
        if (!(asset.geometry() instanceof AnalyticSphere sphere)) {
            throw new IllegalArgumentException("Geometry is not an analytic sphere: " + id);
        }
        geometries.set(index, new GeometryAsset(
                id, asset.label(), asset.revision(), PolygonMesh.approximateSphere(sphere, detail)));
    }
    /** Extrude one stable face on an already-editable shared asset. */
    public void extrudeFace(GeometryId id,long faceId,float distance){int i=requireGeometry(id);var a=geometries.get(i);if(!(a.geometry() instanceof PolygonMesh mesh))throw new IllegalArgumentException("Geometry is not an editable mesh: "+id);geometries.set(i,new GeometryAsset(id,a.label(),a.revision(),mesh.extrude(faceId,distance)));}
    /** Translate one stable polygon vertex in asset-local units. */
    public void translateVertex(GeometryId id, long vertexId, Vec3 localDelta) {
        int index = requireGeometry(id);
        var asset = geometries.get(index);
        if (!(asset.geometry() instanceof PolygonMesh mesh)) {
            throw new IllegalArgumentException("Geometry is not a polygon mesh: " + id);
        }
        geometries.set(index, new GeometryAsset(
                id, asset.label(), asset.revision(), mesh.translateVertex(vertexId, localDelta)));
    }
    /** Translate both endpoints of one canonical polygon edge in asset-local units. */
    public void translateEdge(GeometryId id, long firstVertexId, long secondVertexId, Vec3 localDelta) {
        int index = requireGeometry(id);
        var asset = geometries.get(index);
        if (!(asset.geometry() instanceof PolygonMesh mesh)) {
            throw new IllegalArgumentException("Geometry is not a polygon mesh: " + id);
        }
        geometries.set(index, new GeometryAsset(
                id, asset.label(), asset.revision(), mesh.translateEdge(firstVertexId, secondVertexId, localDelta)));
    }
    /** Translate every stable boundary vertex of one polygon face in asset-local units. */
    public void translateFace(GeometryId id, long faceId, Vec3 localDelta) {
        int index = requireGeometry(id);
        var asset = geometries.get(index);
        if (!(asset.geometry() instanceof PolygonMesh mesh)) {
            throw new IllegalArgumentException("Geometry is not a polygon mesh: " + id);
        }
        geometries.set(index, new GeometryAsset(
                id, asset.label(), asset.revision(), mesh.translateFace(faceId, localDelta)));
    }
    public void renameMaterial(MaterialId id,String label){int i=requireMaterial(id);var a=materials.get(i);materials.set(i,new MaterialAsset(id,label,a.revision(),a.material()));}
    public void replaceMaterial(MaterialId id,Material material){int i=requireMaterial(id);var a=materials.get(i);materials.set(i,new MaterialAsset(id,a.label(),a.revision(),material));}
    public GeometryId makeGeometryUnique(NodeId nodeId){
        int ni=requireNode(nodeId);var node=nodes.get(ni);if(node.geometry()==null)throw new IllegalArgumentException("Node has no geometry");
        var source=geometries.get(requireGeometry(node.geometry().geometryId()));var id=GeometryId.random();
        geometries.add(new GeometryAsset(id,source.label(),0,source.geometry()));
        nodes.set(ni,node.withGeometry(new GeometryComponent(id,node.geometry().materialId())));return id;
    }
    public MaterialId makeMaterialUnique(NodeId nodeId){
        int ni=requireNode(nodeId);var node=nodes.get(ni);if(node.geometry()==null)throw new IllegalArgumentException("Node has no material");
        var source=materials.get(requireMaterial(node.geometry().materialId()));var id=MaterialId.random();
        materials.add(new MaterialAsset(id,source.label(),0,source.material()));
        nodes.set(ni,node.withGeometry(new GeometryComponent(node.geometry().geometryId(),id)));return id;
    }
    /** Duplicate an entire subtree with fresh node IDs while preserving shared asset references. */
    public NodeId duplicateSubtree(NodeId rootId){
        var root=nodes.get(requireNode(rootId));var descendants=subtree(rootId);
        var ids=new LinkedHashMap<NodeId,NodeId>();for(var old:descendants)ids.put(old.id(),NodeId.random());
        for(var old:descendants){NodeId parent=old.id().equals(rootId)?old.parentId():ids.get(old.parentId());nodes.add(new SceneNode(ids.get(old.id()),old.label(),parent,old.localTransform(),old.geometry(),old.light(),old.camera()));}
        return ids.get(rootId);
    }
    public void deleteSubtree(NodeId rootId){var remove=subtree(rootId).stream().map(SceneNode::id).collect(java.util.stream.Collectors.toSet());nodes.removeIf(n->remove.contains(n.id()));}
    private List<SceneNode> subtree(NodeId root) {
        requireNode(root);var seen=new LinkedHashSet<NodeId>();var queue=new ArrayDeque<NodeId>();seen.add(root);queue.add(root);
        while(!queue.isEmpty()) {
            var parent=queue.remove();
            for(var child:nodes)if(parent.equals(child.parentId())) {
                if(!seen.add(child.id()))throw new IllegalArgumentException("Scene hierarchy contains a cycle at "+child.id());
                queue.add(child.id());
            }
        }
        return nodes.stream().filter(n->seen.contains(n.id())).toList();
    }
    private void updateNode(NodeId id,UnaryOperator<SceneNode> update){int i=requireNode(id);nodes.set(i,Objects.requireNonNull(update.apply(nodes.get(i))));}
    private int findNode(NodeId id){for(int i=0;i<nodes.size();i++)if(nodes.get(i).id().equals(id))return i;return -1;}
    private int findGeometry(GeometryId id){for(int i=0;i<geometries.size();i++)if(geometries.get(i).id().equals(id))return i;return -1;}
    private int findMaterial(MaterialId id){for(int i=0;i<materials.size();i++)if(materials.get(i).id().equals(id))return i;return -1;}
    private int requireNode(NodeId id){int i=findNode(id);if(i<0)throw new IllegalArgumentException("Unknown node: "+id);return i;}
    private int requireGeometry(GeometryId id){int i=findGeometry(id);if(i<0)throw new IllegalArgumentException("Unknown geometry asset: "+id);return i;}
    private int requireMaterial(MaterialId id){int i=findMaterial(id);if(i<0)throw new IllegalArgumentException("Unknown material asset: "+id);return i;}

    SceneSnapshot candidate(){return new SceneSnapshot(base.revision(),base.transportRevision(),nodes,geometries,materials);}
    SceneSnapshot publish(long revision,long transportRevision) {
        var oldG=new HashMap<GeometryId,GeometryAsset>();for(var a:base.geometryAssets())oldG.put(a.id(),a);
        var oldM=new HashMap<MaterialId,MaterialAsset>();for(var a:base.materialAssets())oldM.put(a.id(),a);
        var gs=new ArrayList<GeometryAsset>();for(var a:geometries){var old=oldG.get(a.id());long r=old!=null&&old.label().equals(a.label())&&GeometryValues.equal(old.geometry(),a.geometry())?old.revision():revision;gs.add(a.withRevision(r));}
        var ms=new ArrayList<MaterialAsset>();for(var a:materials){var old=oldM.get(a.id());long r=old!=null&&old.label().equals(a.label())&&old.material().equals(a.material())?old.revision():revision;ms.add(a.withRevision(r));}
        return new SceneSnapshot(revision,transportRevision,nodes,gs,ms);
    }
}
