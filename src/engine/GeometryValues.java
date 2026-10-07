package engine;

/** Content comparison includes metadata that the primitive List view intentionally omits. */
final class GeometryValues {
    private GeometryValues(){}
    static boolean equal(GeometryData a,GeometryData b){
        if(a==b)return true;if(a==null||b==null||a.getClass()!=b.getClass())return false;
        if(a instanceof TriangleMesh mesh)return mesh.sameDefinition((TriangleMesh)b);
        if(a instanceof EditableMeshGeometry mesh)return mesh.sameDefinition((EditableMeshGeometry)b);
        return a.equals(b);
    }
}
