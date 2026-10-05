package scenes.viewport;

import math.Vec3;
import scenes.viewport.objects.*;
import java.util.List;

/** Immutable scene edit unit; all its primitives share object and material identity. */
public record SceneInstance(String name, List<SceneObject> geometry, Transform transform, Material material) {
    public SceneInstance {
        if (name == null || name.isBlank() || transform == null || material == null) throw new IllegalArgumentException("Invalid instance");
        geometry = List.copyOf(geometry);
        if (material.kind() == Material.Kind.DIELECTRIC &&
                !(geometry.size() == 1 && geometry.getFirst() instanceof Sphere) && !geometry.equals(box()))
            throw new IllegalArgumentException("Glass requires a closed sphere or box");
    }
    public SceneInstance withTransform(Transform t) { return new SceneInstance(name, geometry, t, material); }
    public SceneInstance withMaterial(Material m) { return new SceneInstance(name, geometry, transform, m); }
    /** Twelve outward-facing triangles, eight shared coordinates; local box spans -1..1. */
    public static List<SceneObject> box() {
        var v = new Vec3[]{new Vec3(-1,-1,-1),new Vec3(1,-1,-1),new Vec3(1,1,-1),new Vec3(-1,1,-1),
                new Vec3(-1,-1,1),new Vec3(1,-1,1),new Vec3(1,1,1),new Vec3(-1,1,1)};
        int[][] faces={{0,3,2,1},{4,5,6,7},{0,4,7,3},{1,2,6,5},{0,1,5,4},{3,7,6,2}};
        var tris=new java.util.ArrayList<SceneObject>();
        for(var face:faces) {
            tris.add(new Tri(v[face[0]],v[face[1]],v[face[2]]));
            tris.add(new Tri(v[face[0]],v[face[2]],v[face[3]]));
        }
        return List.copyOf(tris);
    }
}
