package engine;

import engine.objects.*;
import math.Vec3;
import java.util.*;

/** Immutable renderer/query input derived from one canonical geometry value. */
/** Renderer-owned expansion of canonical geometry. Never persisted or supplied by callers. */
final class PreparedGeometry {
    private final List<RenderPrimitive> primitives;
    private final List<Vec3> vertices;
    private final int[] indices;
    private final long[] sourceFaces;
    private final GeometryCapabilities capabilities;

    private PreparedGeometry(List<RenderPrimitive> primitives,List<Vec3> vertices,int[] indices,long[] sourceFaces,
                             GeometryCapabilities capabilities) {
        this.primitives=List.copyOf(Objects.requireNonNull(primitives));this.vertices=List.copyOf(Objects.requireNonNull(vertices));
        this.indices=Objects.requireNonNull(indices).clone();this.sourceFaces=Objects.requireNonNull(sourceFaces).clone();
        this.capabilities=Objects.requireNonNull(capabilities);
        if(this.sourceFaces.length!=this.primitives.size())throw new IllegalArgumentException("Prepared geometry needs one source ID per primitive");
        if(this.indices.length!=0&&this.indices.length!=this.primitives.size()*3)throw new IllegalArgumentException("Prepared triangle indices do not match primitives");
    }
    static PreparedGeometry prepare(GeometryData geometry){
        return switch(geometry){
            case AnalyticSphere sphere->new PreparedGeometry(List.of(new Sphere(sphere.center(),sphere.radius())),List.of(),new int[0],new long[]{0},sphere.capabilities());
            case PolygonMesh mesh->mesh.preparedGeometry();
        };
    }
    static GeometryData canonical(RenderPrimitive primitive){
        return switch(Objects.requireNonNull(primitive)){
            case Sphere sphere->new AnalyticSphere(sphere.center(),sphere.radius());
            case Rect rect->PolygonMesh.parallelogram(rect.origin(),rect.edge1(),rect.edge2());
            case Tri tri->PolygonMesh.triangleSurface(List.of(tri.a(),tri.b(),tri.c()),new int[]{0,1,2});
        };
    }
    static PreparedGeometry polygon(List<RenderPrimitive> primitives,List<Vec3> vertices,int[] indices,long[] sourceFaces,
                                    GeometryCapabilities capabilities){
        return new PreparedGeometry(primitives,vertices,indices,sourceFaces,capabilities);
    }
    List<RenderPrimitive> primitives(){return primitives;}
    List<Vec3> vertices(){return vertices;}
    int[] indices(){return indices.clone();}
    long sourceFaceId(int primitive){return sourceFaces[primitive];}
    boolean indexedTriangles(){return indices.length!=0;}
    GeometryCapabilities capabilities(){return capabilities;}
}
