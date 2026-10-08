package engine;

/** Immutable scene edit unit; all its primitives share object and material identity. */
public record SceneInstance(String name, GeometryData geometry, Transform transform, Material material) {
    public SceneInstance {
        if (name == null || name.isBlank() || geometry==null || transform == null || material == null) throw new IllegalArgumentException("Invalid instance");
        var capabilities=geometry.capabilities();
        if(material.scattering()>0 && !capabilities.analyticSphere()&&!capabilities.canonicalBoxVolume())
            throw new IllegalArgumentException("Scattering requires a closed sphere or box");
        if(material.emissive() && !capabilities.parallelogramEmitter())
            throw new IllegalArgumentException("Emission requires a single parallelogram surface");
        if (material.kind() == Material.Kind.DIELECTRIC && !capabilities.closedBoundary())
            throw new IllegalArgumentException("Glass requires a closed sphere, box or indexed mesh");
    }
    public SceneInstance withTransform(Transform t) { return new SceneInstance(name,geometry,t,material); }
    public SceneInstance withMaterial(Material m) { return new SceneInstance(name,geometry,transform,m); }
    @Override public boolean equals(Object value){return value instanceof SceneInstance other&&name.equals(other.name)&&transform.equals(other.transform)&&material.equals(other.material)&&GeometryValues.transportEqual(geometry,other.geometry);}
    @Override public int hashCode(){return java.util.Objects.hash(name,transform,material,GeometryValues.transportHash(geometry));}
    /** Canonical local box spans -1..1 and preserves the established face/fan order. */
    public static PolygonMesh box() { return BoxGeometry.UNIT; }
}
