package engine;

/** Content comparison includes metadata that the primitive List view intentionally omits. */
final class GeometryValues {
    private GeometryValues(){}
    static boolean equal(GeometryData a,GeometryData b){
        if(a==b)return true;if(a==null||b==null||a.getClass()!=b.getClass())return false;
        return a instanceof TriangleMesh mesh?mesh.sameDefinition((TriangleMesh)b):a.equals(b);
    }
}
