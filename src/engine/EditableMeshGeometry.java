package engine;

import engine.objects.SceneObject;
import math.Vec3;

import java.util.*;

/**
 * Immutable editable polygon topology with stable asset-local vertex and face identities.
 * Faces contain 3..{@value #MAX_FACE_VERTICES} vertices and must be finite, planar,
 * simple and strictly convex. Validation uses relative tolerances of 1e-5 for planarity
 * and 1e-6 for turns/intersections, scaled by the polygon extent. Rendering triangulates
 * each face as a deterministic fan from its first listed vertex.
 */
public final class EditableMeshGeometry extends AbstractList<SceneObject>
        implements RandomAccess, MeshGeometry, GeometryData {
    public static final int MAX_FACE_VERTICES=1024;

    public record Vertex(long id,Vec3 position) {
        public Vertex { Objects.requireNonNull(position,"position"); }
    }
    public record Face(long id,List<Long> vertexIds) {
        public Face { vertexIds=List.copyOf(Objects.requireNonNull(vertexIds,"vertexIds")); }
    }
    /** Canonical edge endpoints are ascending; adjacent face IDs follow face order. */
    public record Edge(long firstVertexId,long secondVertexId,List<Long> faceIds) {
        public Edge {
            if(firstVertexId>=secondVertexId)throw new IllegalArgumentException("Edge endpoints must be canonical");
            faceIds=List.copyOf(Objects.requireNonNull(faceIds,"faceIds"));
        }
        public boolean boundary(){return faceIds.size()==1;}
    }

    private record EdgeKey(long first,long second) {
        static EdgeKey of(long a,long b){return a<b?new EdgeKey(a,b):new EdgeKey(b,a);}
    }
    private record EdgeUse(long from,long to,long face) {}

    private final List<Vertex> editableVertices;
    private final List<Face> faces;
    private final List<Edge> edges;
    private final Map<Long,Vertex> vertexById;
    private final Map<Long,Face> faceById;
    private final Map<Long,Vec3> normalByFace;
    private final Map<Long,List<Long>> adjacentFaces;
    private final long nextVertexId;
    private final long nextFaceId;
    private final boolean closed;
    private final TriangleMesh renderMesh;

    public static EditableMeshGeometry surface(List<Vertex> vertices,List<Face> faces,
                                               long nextVertexId,long nextFaceId) {
        return new EditableMeshGeometry(vertices,faces,nextVertexId,nextFaceId,false);
    }
    public static EditableMeshGeometry closedSolid(List<Vertex> vertices,List<Face> faces,
                                                   long nextVertexId,long nextFaceId) {
        return new EditableMeshGeometry(vertices,faces,nextVertexId,nextFaceId,true);
    }

    /** Explicitly converts supported analytic or triangle geometry; editable input is returned unchanged. */
    public static EditableMeshGeometry from(GeometryData geometry) {
        Objects.requireNonNull(geometry,"geometry");
        if(geometry instanceof EditableMeshGeometry editable)return editable;
        if(geometry instanceof BoxGeometry)return box();
        if(geometry instanceof RectGeometry rect)return rectangle(rect);
        if(geometry instanceof SphereGeometry sphere)return sphere(sphere);
        if(geometry instanceof TriangleMesh mesh)return triangles(mesh);
        throw new IllegalArgumentException("Geometry cannot be converted to an editable mesh: "+geometry.getClass().getSimpleName());
    }

    private EditableMeshGeometry(List<Vertex> vertices,List<Face> faces,long nextVertexId,
                                 long nextFaceId,boolean closed) {
        editableVertices=List.copyOf(Objects.requireNonNull(vertices,"vertices"));
        this.faces=List.copyOf(Objects.requireNonNull(faces,"faces"));
        this.nextVertexId=nextVertexId;this.nextFaceId=nextFaceId;this.closed=closed;
        if(editableVertices.isEmpty())throw new IllegalArgumentException("Editable mesh needs vertices");
        if(this.faces.isEmpty())throw new IllegalArgumentException("Editable mesh needs faces");
        var verticesById=new LinkedHashMap<Long,Vertex>();long maxVertex=-1;
        for(var vertex:editableVertices) {
            if(vertex==null||vertex.id()<0)throw new IllegalArgumentException("Editable vertex IDs must be nonnegative");
            if(!finite(vertex.position()))throw new IllegalArgumentException("Editable vertex "+vertex.id()+" must be finite");
            if(verticesById.put(vertex.id(),vertex)!=null)throw new IllegalArgumentException("Duplicate editable vertex ID: "+vertex.id());
            maxVertex=Math.max(maxVertex,vertex.id());
        }
        var facesById=new LinkedHashMap<Long,Face>();long maxFace=-1;
        var normals=new LinkedHashMap<Long,Vec3>();var used=new HashSet<Long>();
        var edgeUses=new LinkedHashMap<EdgeKey,List<EdgeUse>>();
        for(var face:this.faces) {
            if(face==null||face.id()<0)throw new IllegalArgumentException("Editable face IDs must be nonnegative");
            if(facesById.put(face.id(),face)!=null)throw new IllegalArgumentException("Duplicate editable face ID: "+face.id());
            maxFace=Math.max(maxFace,face.id());
            var ids=face.vertexIds();
            if(ids.size()<3)throw new IllegalArgumentException("Editable face "+face.id()+" needs at least three vertices");
            if(ids.size()>MAX_FACE_VERTICES)throw new IllegalArgumentException("Editable face "+face.id()+" exceeds vertex limit "+MAX_FACE_VERTICES);
            var unique=new HashSet<Long>();var points=new ArrayList<Vec3>(ids.size());
            for(var id:ids) {
                if(id==null||!unique.add(id))throw new IllegalArgumentException("Editable face "+face.id()+" repeats vertex "+id);
                var vertex=verticesById.get(id);if(vertex==null)throw new IllegalArgumentException("Editable face "+face.id()+" references missing vertex "+id);
                used.add(id);points.add(vertex.position());
            }
            normals.put(face.id(),validatePolygon(face.id(),points));
            for(int i=0;i<ids.size();i++) {
                long from=ids.get(i),to=ids.get((i+1)%ids.size());var key=EdgeKey.of(from,to);
                var uses=edgeUses.computeIfAbsent(key,_ ->new ArrayList<>(2));
                if(uses.size()==2)throw new IllegalArgumentException("Nonmanifold editable edge "+key.first()+"-"+key.second());
                if(!uses.isEmpty()&&uses.getFirst().from()==from)
                    throw new IllegalArgumentException("Shared editable edge "+key.first()+"-"+key.second()+" has inconsistent winding");
                uses.add(new EdgeUse(from,to,face.id()));
            }
        }
        if(used.size()!=editableVertices.size()) {
            var unused=editableVertices.stream().map(Vertex::id).filter(id->!used.contains(id)).findFirst().orElseThrow();
            throw new IllegalArgumentException("Unused editable vertex: "+unused);
        }
        if(nextVertexId<0||nextVertexId<=maxVertex)throw new IllegalArgumentException("Next editable vertex ID must exceed every vertex ID");
        if(nextFaceId<0||nextFaceId<=maxFace)throw new IllegalArgumentException("Next editable face ID must exceed every face ID");
        var builtEdges=new ArrayList<Edge>();var adjacency=new LinkedHashMap<Long,LinkedHashSet<Long>>();
        for(var face:this.faces)adjacency.put(face.id(),new LinkedHashSet<>());
        for(var entry:edgeUses.entrySet()) {
            var uses=entry.getValue();
            if(closed&&uses.size()!=2)throw new IllegalArgumentException("Closed editable mesh needs exactly two faces per edge");
            var ids=uses.stream().map(EdgeUse::face).toList();builtEdges.add(new Edge(entry.getKey().first(),entry.getKey().second(),ids));
            if(ids.size()==2){adjacency.get(ids.get(0)).add(ids.get(1));adjacency.get(ids.get(1)).add(ids.get(0));}
        }
        edges=List.copyOf(builtEdges);vertexById=Collections.unmodifiableMap(verticesById);
        faceById=Collections.unmodifiableMap(facesById);normalByFace=Collections.unmodifiableMap(normals);
        var frozenAdjacency=new LinkedHashMap<Long,List<Long>>();adjacency.forEach((id,values)->frozenAdjacency.put(id,List.copyOf(values)));
        adjacentFaces=Collections.unmodifiableMap(frozenAdjacency);
        renderMesh=buildRenderMesh();
    }

    public List<Vertex> editableVertices(){return editableVertices;}
    public List<Face> faces(){return faces;}
    public List<Edge> edges(){return edges;}
    public long nextVertexId(){return nextVertexId;}
    public long nextFaceId(){return nextFaceId;}
    public Vertex requireVertex(long id){var value=vertexById.get(id);if(value==null)throw new IllegalArgumentException("Unknown editable vertex: "+id);return value;}
    public Face requireFace(long id){var value=faceById.get(id);if(value==null)throw new IllegalArgumentException("Unknown editable face: "+id);return value;}
    public List<Long> adjacentFaceIds(long faceId){requireFace(faceId);return adjacentFaces.get(faceId);}
    public Vec3 faceNormal(long faceId){requireFace(faceId);return normalByFace.get(faceId);}

    /** Extrudes along the face's listed-winding normal (outward for a validated closed solid); the cap retains its ID. */
    public EditableMeshGeometry extrude(long faceId,float distance) {
        if(!Float.isFinite(distance)||distance<=0)throw new IllegalArgumentException("Extrusion distance must be finite and positive");
        var selected=requireFace(faceId);int count=selected.vertexIds().size();
        long finalVertex=advanced(nextVertexId,count,"Editable vertex ID exhausted");
        long finalFace=advanced(nextFaceId,count,"Editable face ID exhausted");
        var vertices=new ArrayList<>(editableVertices);var translatedIds=new ArrayList<Long>(count);var normal=faceNormal(faceId);
        for(int i=0;i<count;i++) {
            long id=nextVertexId+i;var source=requireVertex(selected.vertexIds().get(i));
            vertices.add(new Vertex(id,source.position().add(normal.scale(distance))));translatedIds.add(id);
        }
        var resultFaces=new ArrayList<Face>(faces.size()+count);
        for(var face:faces)resultFaces.add(face.id()==faceId?new Face(faceId,translatedIds):face);
        for(int i=0;i<count;i++) {
            long oldA=selected.vertexIds().get(i),oldB=selected.vertexIds().get((i+1)%count);
            long newA=translatedIds.get(i),newB=translatedIds.get((i+1)%count);
            resultFaces.add(new Face(nextFaceId+i,List.of(oldA,oldB,newB,newA)));
        }
        return new EditableMeshGeometry(vertices,resultFaces,finalVertex,finalFace,closed);
    }

    private static long advanced(long start,int count,String message) {
        if(start<0||count<0||start>Long.MAX_VALUE-count)throw new IllegalStateException(message);
        return start+count;
    }

    @Override public List<Vec3> vertices(){return renderMesh.vertices();}
    @Override public int[] indices(){return renderMesh.indices();}
    @Override public long sourceFaceId(int primitiveIndex){return renderMesh.sourceFaceId(primitiveIndex);}
    @Override public boolean closedBoundary(){return closed;}
    @Override public List<SceneObject> primitives(){return this;}
    @Override public SceneObject get(int index){return renderMesh.get(index);}
    @Override public int size(){return renderMesh.size();}

    boolean sameDefinition(EditableMeshGeometry other) {
        return other!=null&&closed==other.closed&&nextVertexId==other.nextVertexId&&nextFaceId==other.nextFaceId
                &&editableVertices.equals(other.editableVertices)&&faces.equals(other.faces);
    }

    private TriangleMesh buildRenderMesh() {
        var indexById=new HashMap<Long,Integer>();var positions=new ArrayList<Vec3>(editableVertices.size());
        for(int i=0;i<editableVertices.size();i++){var vertex=editableVertices.get(i);indexById.put(vertex.id(),i);positions.add(vertex.position());}
        var indices=new ArrayList<Integer>();var sourceFaces=new ArrayList<Long>();
        for(var face:faces) {
            int first=indexById.get(face.vertexIds().getFirst());
            for(int i=1;i+1<face.vertexIds().size();i++) {
                indices.add(first);indices.add(indexById.get(face.vertexIds().get(i)));indices.add(indexById.get(face.vertexIds().get(i+1)));sourceFaces.add(face.id());
            }
        }
        var packed=indices.stream().mapToInt(Integer::intValue).toArray();var ids=sourceFaces.stream().mapToLong(Long::longValue).toArray();
        return closed?TriangleMesh.closedSolid(positions,packed,ids):TriangleMesh.surface(positions,packed,ids);
    }

    private static Vec3 validatePolygon(long faceId,List<Vec3> points) {
        double scale=extent(points);if(!(scale>0))throw new IllegalArgumentException("Editable face "+faceId+" is degenerate");
        var a=points.get(0);var b=points.get(1);var c=points.get(2);var raw=b.sub(a).cross(c.sub(a));double length=Math.sqrt(raw.lengthSq());
        double turnTolerance=scale*scale*1e-6;
        if(!(length>turnTolerance))throw new IllegalArgumentException("Editable face "+faceId+" has collinear vertices");
        var normal=raw.scale((float)(1/length));double planeTolerance=scale*1e-5;
        for(var point:points)if(Math.abs(point.sub(a).dot(normal))>planeTolerance)
            throw new IllegalArgumentException("Editable face "+faceId+" is not planar");
        for(int i=0;i<points.size();i++) {
            var p=points.get(i);var q=points.get((i+1)%points.size());var r=points.get((i+2)%points.size());
            double turn=q.sub(p).cross(r.sub(q)).dot(normal);
            if(!(turn>turnTolerance))throw new IllegalArgumentException("Editable face "+faceId+" must be strictly convex with consistent winding");
        }
        int dropped=majorAxis(normal);double intersectionTolerance=scale*scale*1e-6,coordinateTolerance=scale*1e-6;
        for(int i=0;i<points.size();i++)for(int j=i+1;j<points.size();j++) {
            if(j==i+1||(i==0&&j==points.size()-1))continue;
            if(intersects(points.get(i),points.get((i+1)%points.size()),points.get(j),points.get((j+1)%points.size()),dropped,intersectionTolerance,coordinateTolerance))
                throw new IllegalArgumentException("Editable face "+faceId+" is self-crossing");
        }
        return normal;
    }
    private static double extent(List<Vec3> points) {
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        for(var p:points){minX=Math.min(minX,p.x());minY=Math.min(minY,p.y());minZ=Math.min(minZ,p.z());maxX=Math.max(maxX,p.x());maxY=Math.max(maxY,p.y());maxZ=Math.max(maxZ,p.z());}
        return Math.sqrt(sq(maxX-minX)+sq(maxY-minY)+sq(maxZ-minZ));
    }
    private static double sq(double value){return value*value;}
    private static int majorAxis(Vec3 normal){double x=Math.abs(normal.x()),y=Math.abs(normal.y()),z=Math.abs(normal.z());return x>=y&&x>=z?0:y>=z?1:2;}
    private static double x(Vec3 p,int dropped){return dropped==0?p.y():p.x();}
    private static double y(Vec3 p,int dropped){return dropped==2?p.y():p.z();}
    private static double orientation(Vec3 a,Vec3 b,Vec3 c,int dropped){return (x(b,dropped)-x(a,dropped))*(y(c,dropped)-y(a,dropped))-(y(b,dropped)-y(a,dropped))*(x(c,dropped)-x(a,dropped));}
    private static boolean intersects(Vec3 a,Vec3 b,Vec3 c,Vec3 d,int dropped,double tolerance,double coordinateTolerance) {
        double abC=orientation(a,b,c,dropped),abD=orientation(a,b,d,dropped),cdA=orientation(c,d,a,dropped),cdB=orientation(c,d,b,dropped);
        if(((abC>tolerance&&abD< -tolerance)||(abC< -tolerance&&abD>tolerance))
                &&((cdA>tolerance&&cdB< -tolerance)||(cdA< -tolerance&&cdB>tolerance)))return true;
        return Math.abs(abC)<=tolerance&&onSegment(a,b,c,dropped,coordinateTolerance)
                ||Math.abs(abD)<=tolerance&&onSegment(a,b,d,dropped,coordinateTolerance)
                ||Math.abs(cdA)<=tolerance&&onSegment(c,d,a,dropped,coordinateTolerance)
                ||Math.abs(cdB)<=tolerance&&onSegment(c,d,b,dropped,coordinateTolerance);
    }
    private static boolean onSegment(Vec3 a,Vec3 b,Vec3 point,int dropped,double tolerance) {
        double px=x(point,dropped),py=y(point,dropped);
        return px>=Math.min(x(a,dropped),x(b,dropped))-tolerance&&px<=Math.max(x(a,dropped),x(b,dropped))+tolerance
                &&py>=Math.min(y(a,dropped),y(b,dropped))-tolerance&&py<=Math.max(y(a,dropped),y(b,dropped))+tolerance;
    }
    private static boolean finite(Vec3 value){return value!=null&&Float.isFinite(value.x())&&Float.isFinite(value.y())&&Float.isFinite(value.z());}

    private static EditableMeshGeometry rectangle(RectGeometry rect) {
        var vertices=List.of(new Vertex(0,rect.origin()),new Vertex(1,rect.origin().add(rect.edge1())),
                new Vertex(2,rect.origin().add(rect.edge1()).add(rect.edge2())),new Vertex(3,rect.origin().add(rect.edge2())));
        return surface(vertices,List.of(new Face(0,List.of(0L,1L,2L,3L))),4,1);
    }
    private static EditableMeshGeometry box() {
        var p=List.of(new Vec3(-1,-1,-1),new Vec3(1,-1,-1),new Vec3(1,1,-1),new Vec3(-1,1,-1),
                new Vec3(-1,-1,1),new Vec3(1,-1,1),new Vec3(1,1,1),new Vec3(-1,1,1));
        var vertices=new ArrayList<Vertex>();for(int i=0;i<p.size();i++)vertices.add(new Vertex(i,p.get(i)));
        var faces=List.of(new Face(0,List.of(0L,3L,2L,1L)),new Face(1,List.of(4L,5L,6L,7L)),
                new Face(2,List.of(0L,4L,7L,3L)),new Face(3,List.of(1L,2L,6L,5L)),
                new Face(4,List.of(0L,1L,5L,4L)),new Face(5,List.of(3L,7L,6L,2L)));
        return closedSolid(vertices,faces,8,6);
    }
    private static EditableMeshGeometry sphere(SphereGeometry sphere) {
        var mesh=IndexedMesh.sphere(8);var points=mesh.vertices().stream().map(v->sphere.center().add(v.scale(sphere.radius()))).toList();
        return triangleData(points,mesh.indices(),null,true);
    }
    private static EditableMeshGeometry triangles(TriangleMesh mesh) {
        var ids=mesh.sourceFaceIds();var unique=new HashSet<Long>();
        for(long id:ids)if(id<0||!unique.add(id))throw new IllegalArgumentException("Triangle mesh conversion needs unique nonnegative source face IDs");
        return triangleData(mesh.vertices(),mesh.indices(),ids,mesh.closedBoundary());
    }
    private static EditableMeshGeometry triangleData(List<Vec3> points,int[] indices,long[] sourceFaces,boolean closed) {
        var used=new LinkedHashSet<Integer>();for(int index:indices)used.add(index);
        var vertices=new ArrayList<Vertex>();for(int index:used)vertices.add(new Vertex(index,points.get(index)));
        var faces=new ArrayList<Face>();long maxFace=-1;
        for(int i=0;i<indices.length/3;i++) {
            long id=sourceFaces==null?i:sourceFaces[i];if(id<0||id==Long.MAX_VALUE)throw new IllegalArgumentException("Editable source face IDs must be nonnegative and allocatable");
            maxFace=Math.max(maxFace,id);faces.add(new Face(id,List.of((long)indices[i*3],(long)indices[i*3+1],(long)indices[i*3+2])));
        }
        long maxVertex=used.stream().mapToLong(Integer::longValue).max().orElseThrow();
        return new EditableMeshGeometry(vertices,faces,maxVertex+1,maxFace+1,closed);
    }
}
