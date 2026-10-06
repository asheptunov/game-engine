package engine;

import math.Vec3;
import engine.objects.SceneObject;
import engine.objects.Tri;
import java.util.*;

/** Immutable, reusable indexed boundary. Triangle indices are stable primitive identities.
 * Closed meshes must be connected, consistently wound manifolds with positive signed volume.
 * Self intersections are not supported (nor detected); callers must supply embedded solids. */
public final class IndexedMesh extends AbstractList<SceneObject> implements RandomAccess {
    private final List<Vec3> vertices;
    private final int[] indices;
    private final List<SceneObject> triangles;
    public IndexedMesh(List<Vec3> vertices, int[] indices) {
        this.vertices=List.copyOf(vertices);this.indices=indices.clone();
        vertices=this.vertices;indices=this.indices;
        if(indices.length<12 || indices.length%3!=0)throw new IllegalArgumentException("Mesh needs triangle indices");
        for(var v:vertices)if(!Float.isFinite(v.x())||!Float.isFinite(v.y())||!Float.isFinite(v.z()))
            throw new IllegalArgumentException("Mesh vertices must be finite");
        var edges=new HashMap<Long,ArrayList<Integer>>();
        var tris=new ArrayList<SceneObject>();double volume=0;
        for(int i=0;i<indices.length;i+=3) {
            int a=indices[i],b=indices[i+1],c=indices[i+2];
            if(a<0||b<0||c<0||a>=vertices.size()||b>=vertices.size()||c>=vertices.size())throw new IllegalArgumentException("Mesh index out of range");
            var va=vertices.get(a);var vb=vertices.get(b);var vc=vertices.get(c);
            if(vb.sub(va).cross(vc.sub(va)).lengthSq()==0)throw new IllegalArgumentException("Degenerate mesh triangle");
            tris.add(new Tri(va,vb,vc));
            volume+=(double)va.x()*(vb.y()*vc.z()-vb.z()*vc.y())+(double)va.y()*(vb.z()*vc.x()-vb.x()*vc.z())+(double)va.z()*(vb.x()*vc.y()-vb.y()*vc.x());
            edge(edges,a,b,i/3);edge(edges,b,c,i/3);edge(edges,c,a,i/3);
        }
        var neighbors=new ArrayList<ArrayList<Integer>>();for(int i=0;i<tris.size();i++)neighbors.add(new ArrayList<>());
        for(var e:edges.values()) {
            if(e.size()!=4||e.get(0).equals(e.get(2)))throw new IllegalArgumentException("Mesh must be closed with opposite edge winding");
            neighbors.get(e.get(1)).add(e.get(3));neighbors.get(e.get(3)).add(e.get(1));
        }
        var seen=new boolean[tris.size()];var queue=new ArrayDeque<Integer>();queue.add(0);seen[0]=true;int count=0;
        while(!queue.isEmpty()){int i=queue.remove();count++;for(int n:neighbors.get(i))if(!seen[n]){seen[n]=true;queue.add(n);}}
        if(count!=tris.size()||!(volume>0))throw new IllegalArgumentException("Mesh must be one outward-facing closed solid");
        triangles=List.copyOf(tris);
    }
    private static void edge(Map<Long,ArrayList<Integer>> edges,int a,int b,int triangle) {
        long key=((long)Math.min(a,b)<<32)|(Math.max(a,b)&0xffffffffL);
        var list=edges.computeIfAbsent(key,k->new ArrayList<>());list.add(a<b?1:-1);list.add(triangle);
    }
    public List<Vec3> vertices(){return vertices;}
    public int[] indices(){return indices.clone();}
    @Override public SceneObject get(int index){return triangles.get(index);}
    @Override public int size(){return triangles.size();}

    /** Faceted unit sphere, 4..64 latitude divisions and twice as many longitude divisions. */
    public static IndexedMesh sphere(int detail) {
        if(detail<4||detail>64)throw new IllegalArgumentException("Mesh detail must be 4..64");
        int slices=2*detail;var v=new ArrayList<Vec3>();v.add(new Vec3(0,1,0));
        for(int r=1;r<detail;r++)for(int s=0;s<slices;s++) {
            double theta=Math.PI*r/detail,phi=2*Math.PI*s/slices;
            v.add(new Vec3((float)(Math.sin(theta)*Math.cos(phi)),(float)Math.cos(theta),(float)(Math.sin(theta)*Math.sin(phi))));
        }
        int bottom=v.size();v.add(new Vec3(0,-1,0));var ids=new ArrayList<Integer>();
        for(int s=0;s<slices;s++) {
            int n=(s+1)%slices;triangle(v,ids,0,1+s,1+n);
            for(int r=0;r<detail-2;r++) {
                int a=1+r*slices+s,b=1+r*slices+n,c=a+slices,d=b+slices;
                triangle(v,ids,a,c,b);triangle(v,ids,b,c,d);
            }
            triangle(v,ids,bottom,1+(detail-2)*slices+n,1+(detail-2)*slices+s);
        }
        return new IndexedMesh(v,ids.stream().mapToInt(Integer::intValue).toArray());
    }
    private static void triangle(List<Vec3> v,List<Integer> ids,int a,int b,int c) {
        if(v.get(b).sub(v.get(a)).cross(v.get(c).sub(v.get(a))).dot(v.get(a).add(v.get(b)).add(v.get(c)))<0){int swap=b;b=c;c=swap;}
        ids.add(a);ids.add(b);ids.add(c);
    }
}
