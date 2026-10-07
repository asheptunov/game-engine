package engine;

import engine.objects.SceneObject;
import engine.objects.Tri;
import math.Vec3;
import java.util.*;

/**
 * General immutable indexed triangle mesh. V1 implements positions and faceted geometric
 * normals only; normals, UVs and material slots are rejected by the scene schema rather
 * than silently ignored. Use {@link #closedSolid} to opt into boundary validation.
 */
public final class TriangleMesh extends AbstractList<SceneObject>
        implements RandomAccess, MeshGeometry, GeometryData {
    private final List<Vec3> vertices;
    private final int[] indices;
    private final long[] sourceFaces;
    private final List<SceneObject> triangles;
    private final boolean closed;

    public static TriangleMesh surface(List<Vec3> vertices, int[] indices) {
        return new TriangleMesh(vertices, indices, sequential(indices), false);
    }
    public static TriangleMesh surface(List<Vec3> vertices, int[] indices, long[] sourceFaces) {
        return new TriangleMesh(vertices, indices, sourceFaces, false);
    }
    public static TriangleMesh closedSolid(List<Vec3> vertices, int[] indices) {
        return new TriangleMesh(vertices, indices, sequential(indices), true);
    }
    public static TriangleMesh closedSolid(List<Vec3> vertices, int[] indices, long[] sourceFaces) {
        return new TriangleMesh(vertices, indices, sourceFaces, true);
    }
    private static long[] sequential(int[] indices) {
        Objects.requireNonNull(indices, "indices");
        var ids = new long[indices.length / 3];
        for (int i=0;i<ids.length;i++) ids[i]=i;
        return ids;
    }
    private TriangleMesh(List<Vec3> vertices, int[] indices, long[] sourceFaces, boolean closed) {
        this.vertices=List.copyOf(Objects.requireNonNull(vertices,"vertices"));
        this.indices=Objects.requireNonNull(indices,"indices").clone();
        this.sourceFaces=Objects.requireNonNull(sourceFaces,"sourceFaces").clone();
        this.closed=closed;
        if(this.indices.length<3 || this.indices.length%3!=0)
            throw new IllegalArgumentException("Mesh needs one or more triangle index triples");
        if(this.sourceFaces.length!=this.indices.length/3)
            throw new IllegalArgumentException("Mesh needs one source face ID per triangle");
        for(var v:this.vertices) if(!finite(v)) throw new IllegalArgumentException("Mesh vertices must be finite");
        var built=new ArrayList<SceneObject>(this.sourceFaces.length);
        for(int i=0;i<this.indices.length;i+=3) {
            int a=this.indices[i],b=this.indices[i+1],c=this.indices[i+2];
            if(a<0||b<0||c<0||a>=this.vertices.size()||b>=this.vertices.size()||c>=this.vertices.size())
                throw new IllegalArgumentException("Mesh index out of range");
            var va=this.vertices.get(a);var vb=this.vertices.get(b);var vc=this.vertices.get(c);
            float area=vb.sub(va).cross(vc.sub(va)).lengthSq();
            if(!Float.isFinite(area)||area==0)throw new IllegalArgumentException("Degenerate mesh triangle");
            built.add(new Tri(va,vb,vc));
        }
        if(closed) validateBoundary();
        triangles=List.copyOf(built);
    }
    private static boolean finite(Vec3 v) {
        return v!=null&&Float.isFinite(v.x())&&Float.isFinite(v.y())&&Float.isFinite(v.z());
    }
    private void validateBoundary() {
        var edges=new HashMap<Long,ArrayList<Integer>>();double volume=0;
        var neighbors=new ArrayList<ArrayList<Integer>>();
        for(int i=0;i<sourceFaces.length;i++)neighbors.add(new ArrayList<>());
        for(int i=0;i<indices.length;i+=3) {
            int a=indices[i],b=indices[i+1],c=indices[i+2];
            var va=vertices.get(a);var vb=vertices.get(b);var vc=vertices.get(c);
            volume+=(double)va.x()*(vb.y()*vc.z()-vb.z()*vc.y())
                    +(double)va.y()*(vb.z()*vc.x()-vb.x()*vc.z())
                    +(double)va.z()*(vb.x()*vc.y()-vb.y()*vc.x());
            edge(edges,a,b,i/3);edge(edges,b,c,i/3);edge(edges,c,a,i/3);
        }
        for(var e:edges.values()) {
            if(e.size()!=4||e.get(0).equals(e.get(2)))
                throw new IllegalArgumentException("Closed mesh needs exactly two oppositely wound faces per edge");
            neighbors.get(e.get(1)).add(e.get(3));neighbors.get(e.get(3)).add(e.get(1));
        }
        var seen=new boolean[sourceFaces.length];var queue=new ArrayDeque<Integer>();queue.add(0);seen[0]=true;int count=0;
        while(!queue.isEmpty()){int i=queue.remove();count++;for(int n:neighbors.get(i))if(!seen[n]){seen[n]=true;queue.add(n);}}
        if(count!=sourceFaces.length||!(volume>0))
            throw new IllegalArgumentException("Closed mesh must be one connected outward-facing solid");
    }
    private static void edge(Map<Long,ArrayList<Integer>> edges,int a,int b,int triangle) {
        long key=((long)Math.min(a,b)<<32)|(Math.max(a,b)&0xffffffffL);
        var list=edges.computeIfAbsent(key,k->new ArrayList<>());list.add(a<b?1:-1);list.add(triangle);
    }
    @Override public List<Vec3> vertices(){return vertices;}
    @Override public int[] indices(){return indices.clone();}
    public long[] sourceFaceIds(){return sourceFaces.clone();}
    @Override public long sourceFaceId(int primitiveIndex){return sourceFaces[primitiveIndex];}
    @Override public boolean closedBoundary(){return closed;}
    @Override public List<SceneObject> primitives(){return this;}
    @Override public SceneObject get(int index){return triangles.get(index);}
    @Override public int size(){return triangles.size();}
    boolean sameDefinition(TriangleMesh mesh){return mesh!=null&&closed==mesh.closed&&vertices.equals(mesh.vertices)&&Arrays.equals(indices,mesh.indices)&&Arrays.equals(sourceFaces,mesh.sourceFaces);}
}
