package engine;

import engine.objects.*;
import math.Vec3;

import java.util.*;

/**
 * Immutable editable polygon topology with stable asset-local vertex and face identities.
 * Faces contain 3..{@value #MAX_FACE_VERTICES} vertices and must be finite, planar,
 * simple and strictly convex. Validation uses relative tolerances of 1e-5 for planarity
 * and 1e-6 for turns/intersections, scaled by the polygon extent. Rendering triangulates
 * each face as a deterministic fan from its first listed vertex.
 */
public final class PolygonMesh implements GeometryData {
    public static final int MAX_FACE_VERTICES=1024;
    private static final long MAX_IMPORT_VALIDATION_WORK=8_000_000;

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
    private final GeometryCapabilities capabilities;
    private volatile PreparedGeometry prepared;

    public static PolygonMesh surface(List<Vertex> vertices,List<Face> faces,
                                               long nextVertexId,long nextFaceId) {
        return new PolygonMesh(vertices,faces,nextVertexId,nextFaceId,false);
    }
    public static PolygonMesh closedSolid(List<Vertex> vertices,List<Face> faces,
                                                   long nextVertexId,long nextFaceId) {
        return new PolygonMesh(vertices,faces,nextVertexId,nextFaceId,true);
    }

    private PolygonMesh(List<Vertex> vertices,List<Face> faces,long nextVertexId,
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
        vertexById=Collections.unmodifiableMap(verticesById);faceById=Collections.unmodifiableMap(facesById);normalByFace=Collections.unmodifiableMap(normals);
        var builtEdges=new ArrayList<Edge>();var adjacency=new LinkedHashMap<Long,LinkedHashSet<Long>>();
        for(var face:this.faces)adjacency.put(face.id(),new LinkedHashSet<>());
        for(var entry:edgeUses.entrySet()) {
            var uses=entry.getValue();
            if(closed&&uses.size()!=2)throw new IllegalArgumentException("Closed editable mesh needs exactly two faces per edge");
            var ids=uses.stream().map(EdgeUse::face).toList();builtEdges.add(new Edge(entry.getKey().first(),entry.getKey().second(),ids));
            if(ids.size()==2){adjacency.get(ids.get(0)).add(ids.get(1));adjacency.get(ids.get(1)).add(ids.get(0));}
        }
        if(closed)validateClosedSolid(adjacency);
        edges=List.copyOf(builtEdges);
        var frozenAdjacency=new LinkedHashMap<Long,List<Long>>();adjacency.forEach((id,values)->frozenAdjacency.put(id,List.copyOf(values)));
        adjacentFaces=Collections.unmodifiableMap(frozenAdjacency);
        capabilities=new GeometryCapabilities(closed,false,isParallelogram(),isCanonicalBox());
    }

    public List<Vertex> editableVertices(){return editableVertices;}
    public List<Face> faces(){return faces;}
    public List<Edge> edges(){return edges;}
    public long nextVertexId(){return nextVertexId;}
    public long nextFaceId(){return nextFaceId;}
    /** Number of renderer primitives produced without forcing preparation. */
    public int renderPrimitiveCount(){if(capabilities.parallelogramEmitter())return 1;int count=0;for(var face:faces)count=Math.addExact(count,face.vertexIds().size()-2);return count;}
    public Vertex requireVertex(long id){var value=vertexById.get(id);if(value==null)throw new IllegalArgumentException("Unknown editable vertex: "+id);return value;}
    public Face requireFace(long id){var value=faceById.get(id);if(value==null)throw new IllegalArgumentException("Unknown editable face: "+id);return value;}
    public List<Long> adjacentFaceIds(long faceId){requireFace(faceId);return adjacentFaces.get(faceId);}
    public Vec3 faceNormal(long faceId){requireFace(faceId);return normalByFace.get(faceId);}

    /** Return a validated mesh with one stable vertex translated in asset-local units. */
    public PolygonMesh translateVertex(long vertexId, Vec3 localDelta) {
        requireVertex(vertexId);
        return translateVertices(Set.of(vertexId), localDelta);
    }

    /** Return a validated mesh with both endpoints of one canonical edge translated equally. */
    public PolygonMesh translateEdge(long firstVertexId, long secondVertexId, Vec3 localDelta) {
        long first = Math.min(firstVertexId, secondVertexId);
        long second = Math.max(firstVertexId, secondVertexId);
        if (first == second || edges.stream().noneMatch(edge -> edge.firstVertexId() == first
                && edge.secondVertexId() == second)) {
            throw new IllegalArgumentException("Unknown editable edge: " + first + "-" + second);
        }
        return translateVertices(Set.of(first, second), localDelta);
    }

    private PolygonMesh translateVertices(Set<Long> vertexIds, Vec3 localDelta) {
        Objects.requireNonNull(localDelta, "localDelta");
        if (!finite(localDelta)) {
            throw new IllegalArgumentException("Vertex translation must be finite");
        }
        if (localDelta.equals(Vec3.ZERO)) {
            return this;
        }
        var translated = new ArrayList<Vertex>(editableVertices.size());
        for (var vertex : editableVertices) {
            var position = vertexIds.contains(vertex.id())
                    ? vertex.position().add(localDelta)
                    : vertex.position();
            translated.add(new Vertex(vertex.id(), position));
        }
        return new PolygonMesh(translated, faces, nextVertexId, nextFaceId, closed);
    }

    /** Extrudes along the face's listed-winding normal (outward for a validated closed solid); the cap retains its ID. */
    public PolygonMesh extrude(long faceId,float distance) {
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
        return new PolygonMesh(vertices,resultFaces,finalVertex,finalFace,closed);
    }

    private static long advanced(long start,int count,String message) {
        if(start<0||count<0||start>Long.MAX_VALUE-count)throw new IllegalStateException(message);
        return start+count;
    }

    public boolean closedBoundary(){return closed;}
    @Override public GeometryCapabilities capabilities(){return capabilities;}
    PreparedGeometry preparedGeometry(){var value=prepared;if(value==null)synchronized(this){value=prepared;if(value==null)prepared=value=buildPrepared();}return value;}

    boolean sameDefinition(PolygonMesh other) {
        return other!=null&&closed==other.closed&&nextVertexId==other.nextVertexId&&nextFaceId==other.nextFaceId
                &&editableVertices.equals(other.editableVertices)&&faces.equals(other.faces);
    }
    boolean sameTransport(PolygonMesh other) {
        if(other==null||closed!=other.closed||!capabilities.equals(other.capabilities)||faces.size()!=other.faces.size())return false;
        for(int i=0;i<faces.size();i++){
            var a=faces.get(i).vertexIds();var b=other.faces.get(i).vertexIds();if(a.size()!=b.size())return false;
            for(int j=0;j<a.size();j++)if(!requireVertex(a.get(j)).position().equals(other.requireVertex(b.get(j)).position()))return false;
        }
        return true;
    }
    int transportHash(){int hash=Objects.hash(closed,capabilities);for(var face:faces)for(long id:face.vertexIds())hash=31*hash+requireVertex(id).position().hashCode();return hash;}

    private boolean isParallelogram(){
        if(closed||faces.size()!=1||faces.getFirst().vertexIds().size()!=4)return false;
        var ids=faces.getFirst().vertexIds();var p0=requireVertex(ids.get(0)).position();var p1=requireVertex(ids.get(1)).position();
        var p2=requireVertex(ids.get(2)).position();var p3=requireVertex(ids.get(3)).position();
        // Preparation derives the exact stored Rect from p0/p1/p3. Require its representable
        // fourth corner to equal p2 so capability recognition never changes visible geometry.
        return p0.add(p1.sub(p0)).add(p3.sub(p0)).equals(p2);
    }
    private boolean isCanonicalBox(){
        if(!closed||editableVertices.size()!=8||faces.size()!=6||faces.stream().anyMatch(face->face.vertexIds().size()!=4))return false;
        var cornerById=new HashMap<Long,Integer>();var corners=new HashSet<Integer>();
        for(var vertex:editableVertices){var p=vertex.position();if(!unitCoordinate(p.x())||!unitCoordinate(p.y())||!unitCoordinate(p.z()))return false;
            int corner=(p.x()>0?1:0)|(p.y()>0?2:0)|(p.z()>0?4:0);corners.add(corner);cornerById.put(vertex.id(),corner);}
        if(corners.size()!=8)return false;
        var expected=new HashSet<>(List.of("0,2,3,1","4,5,7,6","0,4,6,2","1,3,7,5","0,1,5,4","2,6,7,3"));
        for(var face:faces){var cycle=face.vertexIds().stream().map(cornerById::get).toList();String canonical=null;for(int start=0;start<4;start++){var text=cycle.get(start)+","+cycle.get((start+1)%4)+","+cycle.get((start+2)%4)+","+cycle.get((start+3)%4);if(canonical==null||text.compareTo(canonical)<0)canonical=text;}if(!expected.remove(canonical))return false;}
        return expected.isEmpty();
    }
    private static boolean unitCoordinate(float value){return value==-1f||value==1f;}

    private void validateClosedSolid(Map<Long,LinkedHashSet<Long>> adjacency){
        var seen=new HashSet<Long>();var queue=new ArrayDeque<Long>();queue.add(faces.getFirst().id());seen.add(faces.getFirst().id());while(!queue.isEmpty())for(long next:adjacency.get(queue.remove()))if(seen.add(next))queue.add(next);
        if(seen.size()!=faces.size())throw new IllegalArgumentException("Closed polygon mesh must be one connected component");
        double volume6=0;for(var face:faces){var ids=face.vertexIds();var a=requireVertex(ids.getFirst()).position();for(int i=1;i+1<ids.size();i++){var b=requireVertex(ids.get(i)).position();var c=requireVertex(ids.get(i+1)).position();volume6+=(double)a.x()*(b.y()*c.z()-b.z()*c.y())+(double)a.y()*(b.z()*c.x()-b.x()*c.z())+(double)a.z()*(b.x()*c.y()-b.y()*c.x());}}
        if(!(volume6>0))throw new IllegalArgumentException("Closed polygon mesh must have positive outward signed volume");
    }

    private PreparedGeometry buildPrepared() {
        if(capabilities.parallelogramEmitter()){
            var face=faces.getFirst();var p0=requireVertex(face.vertexIds().get(0)).position();var p1=requireVertex(face.vertexIds().get(1)).position();var p3=requireVertex(face.vertexIds().get(3)).position();
            return PreparedGeometry.polygon(List.of(new Rect(p0,p1.sub(p0),p3.sub(p0))),List.of(),new int[0],new long[]{face.id()},capabilities);
        }
        var indexById=new HashMap<Long,Integer>();var positions=new ArrayList<Vec3>(editableVertices.size());
        for(int i=0;i<editableVertices.size();i++){var vertex=editableVertices.get(i);indexById.put(vertex.id(),i);positions.add(vertex.position());}
        var indices=new ArrayList<Integer>();var sourceFaces=new ArrayList<Long>();
        for(var face:faces) {
            int first=indexById.get(face.vertexIds().getFirst());
            for(int i=1;i+1<face.vertexIds().size();i++) {
                indices.add(first);indices.add(indexById.get(face.vertexIds().get(i)));indices.add(indexById.get(face.vertexIds().get(i+1)));sourceFaces.add(face.id());
            }
        }
        var packed=indices.stream().mapToInt(Integer::intValue).toArray();var ids=sourceFaces.stream().mapToLong(Long::longValue).toArray();var triangles=new ArrayList<RenderPrimitive>(ids.length);
        for(int i=0;i<packed.length;i+=3)triangles.add(new Tri(positions.get(packed[i]),positions.get(packed[i+1]),positions.get(packed[i+2])));
        // The canonical box retains the established flat-triangle kernel and primitive order.
        // General polygon meshes use the robust indexed-triangle kernel.
        return PreparedGeometry.polygon(triangles,positions,capabilities.canonicalBoxVolume()?new int[0]:packed,ids,capabilities);
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

    public static PolygonMesh parallelogram(Vec3 origin,Vec3 edge1,Vec3 edge2) {
        Objects.requireNonNull(origin);Objects.requireNonNull(edge1);Objects.requireNonNull(edge2);
        // Canonicalize through the stored adjacent corners. At large coordinates, separately
        // adding the original edges can round differently from the Rect kernel's reconstruction.
        // This keeps every factory and migrated legacy Rect exactly emitter-representable.
        var p1=origin.add(edge1);var p3=origin.add(edge2);var p2=origin.add(p1.sub(origin)).add(p3.sub(origin));
        var vertices=List.of(new Vertex(0,origin),new Vertex(1,p1),new Vertex(2,p2),new Vertex(3,p3));
        return surface(vertices,List.of(new Face(0,List.of(0L,1L,2L,3L))),4,1);
    }
    public static PolygonMesh unitBox() {
        var p=List.of(new Vec3(-1,-1,-1),new Vec3(1,-1,-1),new Vec3(1,1,-1),new Vec3(-1,1,-1),
                new Vec3(-1,-1,1),new Vec3(1,-1,1),new Vec3(1,1,1),new Vec3(-1,1,1));
        var vertices=new ArrayList<Vertex>();for(int i=0;i<p.size();i++)vertices.add(new Vertex(i,p.get(i)));
        var faces=List.of(new Face(0,List.of(0L,3L,2L,1L)),new Face(1,List.of(4L,5L,6L,7L)),
                new Face(2,List.of(0L,4L,7L,3L)),new Face(3,List.of(1L,2L,6L,5L)),
                new Face(4,List.of(0L,1L,5L,4L)),new Face(5,List.of(3L,7L,6L,2L)));
        return closedSolid(vertices,faces,8,6);
    }
    public static PolygonMesh approximateSphere(AnalyticSphere sphere,int detail) {
        if(detail<4||detail>64)throw new IllegalArgumentException("Sphere mesh detail must be 4..64");
        int slices=2*detail;var points=new ArrayList<Vec3>();points.add(sphere.center().add(new Vec3(0,sphere.radius(),0)));
        for(int r=1;r<detail;r++)for(int s=0;s<slices;s++) {
            double theta=Math.PI*r/detail,phi=2*Math.PI*s/slices;
            points.add(sphere.center().add(new Vec3((float)(Math.sin(theta)*Math.cos(phi))*sphere.radius(),
                    (float)Math.cos(theta)*sphere.radius(),(float)(Math.sin(theta)*Math.sin(phi))*sphere.radius())));
        }
        int bottom=points.size();points.add(sphere.center().add(new Vec3(0,-sphere.radius(),0)));var indices=new ArrayList<Integer>();
        for(int s=0;s<slices;s++) {
            int n=(s+1)%slices;orientedTriangle(points,indices,0,1+s,1+n,sphere.center());
            for(int r=0;r<detail-2;r++) {
                int a=1+r*slices+s,b=1+r*slices+n,c=a+slices,d=b+slices;
                orientedTriangle(points,indices,a,c,b,sphere.center());orientedTriangle(points,indices,b,c,d,sphere.center());
            }
            orientedTriangle(points,indices,bottom,1+(detail-2)*slices+n,1+(detail-2)*slices+s,sphere.center());
        }
        return triangleData(points,indices.stream().mapToInt(Integer::intValue).toArray(),null,true);
    }
    private static void orientedTriangle(List<Vec3> points,List<Integer> indices,int a,int b,int c,Vec3 center) {
        var va=points.get(a).sub(center);var vb=points.get(b).sub(center);var vc=points.get(c).sub(center);
        if(vb.sub(va).cross(vc.sub(va)).dot(va.add(vb).add(vc))<0){int swap=b;b=c;c=swap;}
        indices.add(a);indices.add(b);indices.add(c);
    }
    public static PolygonMesh triangleSurface(List<Vec3> points,int[] indices){return triangleData(points,indices,null,false);}
    public static PolygonMesh triangleSurface(List<Vec3> points,int[] indices,long[] sourceFaces){return groupedTriangles(points,indices,sourceFaces,false);}
    public static PolygonMesh triangleClosedSolid(List<Vec3> points,int[] indices){return triangleData(points,indices,null,true);}
    public static PolygonMesh triangleClosedSolid(List<Vec3> points,int[] indices,long[] sourceFaces){return groupedTriangles(points,indices,sourceFaces,true);}
    private static PolygonMesh groupedTriangles(List<Vec3> points,int[] indices,long[] sourceFaces,boolean closed){
        Objects.requireNonNull(points);Objects.requireNonNull(indices);Objects.requireNonNull(sourceFaces);
        if(indices.length<3||indices.length%3!=0||sourceFaces.length!=indices.length/3)throw new IllegalArgumentException("Mesh needs matching triangle indices and source face IDs");
        var groups=new LinkedHashMap<Long,List<Integer>>();
        for(int i=0;i<sourceFaces.length;i++){long id=sourceFaces[i];if(id<0||id==Long.MAX_VALUE)throw new IllegalArgumentException("Source face IDs must be nonnegative and allocatable");groups.computeIfAbsent(id,_ ->new ArrayList<>()).add(i);}
        long preflightWork=0;for(var entry:groups.entrySet()){
            var referenced=new HashSet<Integer>();for(int triangle:entry.getValue())for(int corner=0;corner<3;corner++){int index=indices[triangle*3+corner];if(index<0||index>=points.size())throw new IllegalArgumentException("Mesh index out of range");referenced.add(index);}
            if(entry.getValue().size()>1&&entry.getValue().size()!=referenced.size()-2)throw new IllegalArgumentException("Source face "+entry.getKey()+" is not a complete triangulated disk");
            if(referenced.size()>MAX_FACE_VERTICES)throw new IllegalArgumentException("Source face "+entry.getKey()+" exceeds vertex limit "+MAX_FACE_VERTICES);
            try{preflightWork=Math.addExact(preflightWork,Math.multiplyExact((long)referenced.size(),referenced.size()-1)/2);}catch(ArithmeticException e){throw new IllegalArgumentException("Triangle grouping validation work overflow",e);}
            if(preflightWork>MAX_IMPORT_VALIDATION_WORK)throw new IllegalArgumentException("Triangle grouping exceeds validation work limit "+MAX_IMPORT_VALIDATION_WORK);
        }
        var used=new boolean[points.size()];for(int index:indices){if(index<0||index>=points.size())throw new IllegalArgumentException("Mesh index out of range");used[index]=true;}
        for(int i=0;i<used.length;i++)if(!used[i])throw new IllegalArgumentException("Unused imported vertex: "+i);
        var vertices=new ArrayList<Vertex>(points.size());for(int i=0;i<points.size();i++)vertices.add(new Vertex(i,points.get(i)));
        var faces=new ArrayList<Face>();
        for(var group:groups.entrySet()){
            var boundary=group.getValue().size()==1?triangleBoundary(indices,group.getValue().getFirst()):reconstructedBoundary(points,indices,group.getValue(),group.getKey());
            if(boundary.size()>MAX_FACE_VERTICES)throw new IllegalArgumentException("Source face "+group.getKey()+" exceeds vertex limit "+MAX_FACE_VERTICES);
            faces.add(new Face(group.getKey(),boundary));
        }
        if(points.size()-1L==Long.MAX_VALUE)throw new IllegalArgumentException("Imported vertex IDs are exhausted");
        long maxFace=groups.keySet().stream().mapToLong(Long::longValue).max().orElseThrow();
        return new PolygonMesh(vertices,faces,points.size(),maxFace+1,closed);
    }
    private record ImportedEdge(int from,int to,int triangle){}
    private static long edgeKey(int a,int b){return ((long)Math.min(a,b)<<32)|(Math.max(a,b)&0xffffffffL);}
    private static List<Long> triangleBoundary(int[] indices,int triangle){return List.of((long)indices[triangle*3],(long)indices[triangle*3+1],(long)indices[triangle*3+2]);}
    private static List<Long> reconstructedBoundary(List<Vec3> points,int[] indices,List<Integer> triangles,long faceId){
        var edges=new LinkedHashMap<Long,List<ImportedEdge>>();var groupVertices=new LinkedHashSet<Integer>();var duplicateTriangles=new HashSet<String>();
        var neighbors=new HashMap<Integer,LinkedHashSet<Integer>>();var incident=new HashMap<Integer,LinkedHashSet<Integer>>();
        Vec3 origin=null,normal=null;double scale=0,totalArea=0;
        for(int triangle:triangles){
            int a=indices[triangle*3],b=indices[triangle*3+1],c=indices[triangle*3+2];
            if(a==b||b==c||c==a)throw new IllegalArgumentException("Source face "+faceId+" has a degenerate triangle");
            int[] sorted={a,b,c};Arrays.sort(sorted);if(!duplicateTriangles.add(sorted[0]+":"+sorted[1]+":"+sorted[2]))throw new IllegalArgumentException("Source face "+faceId+" repeats a triangle");
            var pa=points.get(a);var pb=points.get(b);var pc=points.get(c);if(!finite(pa)||!finite(pb)||!finite(pc))throw new IllegalArgumentException("Source face "+faceId+" has a non-finite vertex");
            var raw=pb.sub(pa).cross(pc.sub(pa));double length=Math.sqrt(raw.lengthSq());if(!(length>0))throw new IllegalArgumentException("Source face "+faceId+" has a degenerate triangle");
            if(normal==null){origin=pa;normal=raw.scale((float)(1/length));scale=extent(triangles.stream().flatMap(t->java.util.stream.IntStream.of(indices[t*3],indices[t*3+1],indices[t*3+2]).boxed()).distinct().map(points::get).toList());}
            double tolerance=Math.max(1e-12,scale*scale*1e-6);if(raw.dot(normal)<=tolerance)throw new IllegalArgumentException("Source face "+faceId+" has inconsistent or folded triangle winding");
            totalArea+=raw.dot(normal)/2;for(int vertex:new int[]{a,b,c}){groupVertices.add(vertex);incident.computeIfAbsent(vertex,_ ->new LinkedHashSet<>()).add(triangle);}neighbors.computeIfAbsent(triangle,_ ->new LinkedHashSet<>());
            for(var edge:List.of(new ImportedEdge(a,b,triangle),new ImportedEdge(b,c,triangle),new ImportedEdge(c,a,triangle))){var uses=edges.computeIfAbsent(edgeKey(edge.from,edge.to),_ ->new ArrayList<>(2));if(uses.size()==2)throw new IllegalArgumentException("Source face "+faceId+" has a nonmanifold edge");uses.add(edge);}
        }
        double planeTolerance=Math.max(1e-6,scale*1e-5);for(int vertex:groupVertices)if(Math.abs(points.get(vertex).sub(origin).dot(normal))>planeTolerance)throw new IllegalArgumentException("Source face "+faceId+" is not coplanar");
        var next=new HashMap<Integer,Integer>();var incoming=new HashMap<Integer,Integer>();
        for(var uses:edges.values()){
            if(uses.size()==2){var first=uses.get(0);var second=uses.get(1);if(first.from!=second.to||first.to!=second.from)throw new IllegalArgumentException("Source face "+faceId+" has inconsistent triangle winding");neighbors.get(first.triangle).add(second.triangle);neighbors.get(second.triangle).add(first.triangle);continue;}
            var edge=uses.getFirst();if(next.put(edge.from,edge.to)!=null||incoming.put(edge.to,edge.from)!=null)throw new IllegalArgumentException("Source face "+faceId+" has a branched boundary");
        }
        connectedTriangles(triangles,neighbors,faceId);
        for(int vertex:groupVertices)connectedFan(vertex,incident.get(vertex),edges,faceId);
        if(next.size()<3)throw new IllegalArgumentException("Source face "+faceId+" has no polygon boundary");
        int start=next.keySet().stream().mapToInt(Integer::intValue).min().orElseThrow(),cursor=start;var loop=new ArrayList<Long>();
        do{loop.add((long)cursor);var value=next.get(cursor);if(value==null||loop.size()>next.size())throw new IllegalArgumentException("Source face "+faceId+" has disconnected or holed grouping");cursor=value;}while(cursor!=start);
        if(loop.size()!=next.size()||incoming.size()!=next.size())throw new IllegalArgumentException("Source face "+faceId+" has disconnected or holed grouping");
        if(loop.size()!=groupVertices.size())throw new IllegalArgumentException("Source face "+faceId+" contains an unrepresentable interior vertex");
        if(triangles.size()!=loop.size()-2||edges.size()!=2*loop.size()-3)throw new IllegalArgumentException("Source face "+faceId+" is not a complete triangulated disk");
        var boundaryPoints=loop.stream().map(id->points.get(id.intValue())).toList();validatePolygon(faceId,boundaryPoints);
        double boundaryArea=0;var first=boundaryPoints.getFirst();for(int i=1;i+1<boundaryPoints.size();i++)boundaryArea+=boundaryPoints.get(i).sub(first).cross(boundaryPoints.get(i+1).sub(first)).dot(normal)/2;
        if(Math.abs(totalArea-boundaryArea)>Math.max(1e-6,Math.abs(boundaryArea)*1e-5))throw new IllegalArgumentException("Source face "+faceId+" has overlapping or incomplete triangles");
        return loop;
    }
    private static void connectedTriangles(List<Integer> triangles,Map<Integer,LinkedHashSet<Integer>> neighbors,long faceId){
        var seen=new HashSet<Integer>();var queue=new ArrayDeque<Integer>();queue.add(triangles.getFirst());seen.add(triangles.getFirst());while(!queue.isEmpty())for(int next:neighbors.get(queue.remove()))if(seen.add(next))queue.add(next);
        if(seen.size()!=triangles.size())throw new IllegalArgumentException("Source face "+faceId+" has disconnected triangles");
    }
    private static void connectedFan(int vertex,Set<Integer> incident,Map<Long,List<ImportedEdge>> edges,long faceId){
        if(incident.size()<2)return;var neighbors=new HashMap<Integer,Set<Integer>>();for(int triangle:incident)neighbors.put(triangle,new HashSet<>());
        for(var uses:edges.values())if(uses.size()==2&&(uses.getFirst().from==vertex||uses.getFirst().to==vertex)){int a=uses.getFirst().triangle,b=uses.get(1).triangle;if(neighbors.containsKey(a)&&neighbors.containsKey(b)){neighbors.get(a).add(b);neighbors.get(b).add(a);}}
        var seen=new HashSet<Integer>();var queue=new ArrayDeque<Integer>();queue.add(incident.iterator().next());seen.add(queue.getFirst());while(!queue.isEmpty())for(int next:neighbors.get(queue.remove()))if(seen.add(next))queue.add(next);
        if(seen.size()!=incident.size())throw new IllegalArgumentException("Source face "+faceId+" has a pinched vertex "+vertex);
    }
    private static PolygonMesh triangleData(List<Vec3> points,int[] indices,long[] sourceFaces,boolean closed) {
        Objects.requireNonNull(points);Objects.requireNonNull(indices);if(indices.length<3||indices.length%3!=0)throw new IllegalArgumentException("Mesh needs triangle indices");
        if(sourceFaces!=null&&sourceFaces.length!=indices.length/3)throw new IllegalArgumentException("Mesh needs one source face ID per triangle");
        var used=new boolean[points.size()];for(int index:indices){if(index<0||index>=points.size())throw new IllegalArgumentException("Mesh index out of range");used[index]=true;}
        for(int i=0;i<used.length;i++)if(!used[i])throw new IllegalArgumentException("Unused imported vertex: "+i);
        var vertices=new ArrayList<Vertex>();for(int i=0;i<points.size();i++)vertices.add(new Vertex(i,points.get(i)));
        var faces=new ArrayList<Face>();long maxFace=-1;
        var uniqueFaces=new HashSet<Long>();
        for(int i=0;i<indices.length/3;i++) {
            long id=sourceFaces==null?i:sourceFaces[i];if(id<0||id==Long.MAX_VALUE)throw new IllegalArgumentException("Editable source face IDs must be nonnegative and allocatable");
            if(!uniqueFaces.add(id))throw new IllegalArgumentException("Duplicate source face ID: "+id);
            maxFace=Math.max(maxFace,id);faces.add(new Face(id,List.of((long)indices[i*3],(long)indices[i*3+1],(long)indices[i*3+2])));
        }
        return new PolygonMesh(vertices,faces,points.size(),maxFace+1,closed);
    }
}
