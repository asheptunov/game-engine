package engine;

/** Content comparison includes metadata that the primitive List view intentionally omits. */
final class GeometryValues {
    private GeometryValues(){}
    static boolean equal(GeometryData a,GeometryData b){
        if(a==b)return true;if(a==null||b==null||a.getClass()!=b.getClass())return false;
        if(a instanceof PolygonMesh mesh)return mesh.sameDefinition((PolygonMesh)b);
        return a.equals(b);
    }
    static boolean transportEqual(GeometryData a,GeometryData b){
        if(a==b)return true;if(a==null||b==null||a.getClass()!=b.getClass())return false;
        if(a instanceof PolygonMesh mesh)return mesh.sameTransport((PolygonMesh)b);
        return a.equals(b);
    }
    static int transportHash(GeometryData value){return value instanceof PolygonMesh mesh?mesh.transportHash():value.hashCode();}
}
