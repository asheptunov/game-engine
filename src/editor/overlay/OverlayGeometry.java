package editor.overlay;

import engine.*;
import math.Vec3;

import java.util.*;

/**
 * Platform-neutral editor overlay geometry. All output is x-ray screen-space data: it is
 * never inserted into the rendered world and carries no depth-visibility semantics.
 * Expensive local mesh edges are cached by immutable asset content; callers may prepare
 * on a worker and project the result for the exact displayed camera.
 */
public final class OverlayGeometry {
    public static final int MAX_WIREFRAME_SEGMENTS=100_000;
    public static final int MAX_CACHED_ASSETS=256;
    public static final int MAX_CACHED_SEGMENTS=200_000;
    public static final int SPHERE_SEGMENTS=48;
    public static final int RING_SEGMENTS=64;

    public enum Axis { X,Y,Z }
    public enum GizmoMode { NONE,TRANSLATE,ROTATE }
    public enum MarkerKind { LIGHT,CAMERA }
    public enum Style { WIREFRAME_XRAY,FACE_SELECTION_XRAY,TRANSLATION_HANDLE_XRAY,ROTATION_HANDLE_XRAY }
    public enum HitKind { HANDLE,MARKER }

    public record Line(NodeId nodeId,Style style,Axis axis,double u1,double v1,double u2,double v2) {
        public Line { Objects.requireNonNull(nodeId);Objects.requireNonNull(style); }
    }
    public record Marker(NodeId nodeId,MarkerKind kind,double u,double v) {
        public Marker { Objects.requireNonNull(nodeId);Objects.requireNonNull(kind); }
    }
    public record Handle(NodeId nodeId,GizmoMode mode,Axis axis,List<Line> lines) {
        public Handle { Objects.requireNonNull(nodeId);Objects.requireNonNull(mode);Objects.requireNonNull(axis);lines=List.copyOf(lines); }
    }
    public record Hit(NodeId nodeId,HitKind kind,MarkerKind markerKind,GizmoMode mode,Axis axis,double distancePixels) {}
    public record FaceSelection(NodeId nodeId,GeometryId geometryId,long faceId) {
        public FaceSelection { Objects.requireNonNull(nodeId);Objects.requireNonNull(geometryId); }
    }
    public record Frame(long sceneRevision,List<Line> wireframe,List<Line> selectedFace,List<Marker> markers,List<Handle> handles,
                        boolean wireframeTruncated) {
        public Frame { wireframe=List.copyOf(wireframe);selectedFace=List.copyOf(selectedFace);markers=List.copyOf(markers);handles=List.copyOf(handles); }
        /** Handles take precedence over markers; both use exactly the displayed projected geometry. */
        public Optional<Hit> pick(double u,double v,int pixelWidth,int pixelHeight,double tolerancePixels) {
            if(!Double.isFinite(u)||!Double.isFinite(v)||pixelWidth<=0||pixelHeight<=0
                    ||!Double.isFinite(tolerancePixels)||tolerancePixels<0)
                throw new IllegalArgumentException("Overlay pick arguments are invalid");
            Hit best=null;
            for(var handle:handles)for(var line:handle.lines) {
                double distance=distance(u*pixelWidth,v*pixelHeight,line.u1*pixelWidth,line.v1*pixelHeight,
                        line.u2*pixelWidth,line.v2*pixelHeight);
                if(distance<=tolerancePixels&&(best==null||distance<best.distancePixels))
                    best=new Hit(handle.nodeId,HitKind.HANDLE,null,handle.mode,handle.axis,distance);
            }
            if(best!=null)return Optional.of(best);
            for(var marker:markers) {
                double distance=Math.hypot((u-marker.u)*pixelWidth,(v-marker.v)*pixelHeight);
                if(distance<=tolerancePixels&&(best==null||distance<best.distancePixels))
                    best=new Hit(marker.nodeId,HitKind.MARKER,marker.kind,null,null,distance);
            }
            return Optional.ofNullable(best);
        }
    }

    public record WorldLine(NodeId nodeId,Vec3 start,Vec3 end) {
        public WorldLine { Objects.requireNonNull(nodeId);Objects.requireNonNull(start);Objects.requireNonNull(end); }
    }
    public record WorldMarker(NodeId nodeId,MarkerKind kind,Vec3 position) {
        public WorldMarker { Objects.requireNonNull(nodeId);Objects.requireNonNull(kind);Objects.requireNonNull(position); }
    }
    public record Prepared(long sceneRevision,NodeId selection,FaceSelection faceSelection,Vec3 pivot,Vec3 xAxis,Vec3 yAxis,Vec3 zAxis,
                           List<WorldLine> wireframe,List<WorldLine> selectedFace,List<WorldMarker> markers,boolean wireframeTruncated) {
        public Prepared { wireframe=List.copyOf(wireframe);selectedFace=List.copyOf(selectedFace);markers=List.copyOf(markers); }
        public Vec3 axis(Axis axis){return switch(axis){case X->xAxis;case Y->yAxis;case Z->zAxis;};}
    }

    private record LocalLine(Vec3 start,Vec3 end){}
    private record Cached(List<LocalLine> lines,boolean truncated){Cached{lines=List.copyOf(lines);}}
    private static final class AssetKey {
        final GeometryId id;final long revision;final GeometryData geometry;final int hash;
        AssetKey(GeometryAsset asset){id=asset.id();revision=asset.revision();geometry=asset.geometry();hash=31*(31*id.hashCode()+Long.hashCode(revision))+System.identityHashCode(geometry);}
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object value){return value instanceof AssetKey key&&revision==key.revision&&id.equals(key.id)&&geometry==key.geometry;}
    }
    private final Map<AssetKey,Cached> cache=new LinkedHashMap<>(16,.75f,true);
    private int cachedSegments;

    /** Build world-space overlay data. Selected geometry shows itself; a component-free group shows its subtree. */
    public Prepared prepare(SceneSnapshot snapshot,NodeId selection) {
        return prepare(snapshot,selection,null);
    }
    /** Face selection is camera-independent and becomes part of the immutable prepared context. */
    public Prepared prepare(SceneSnapshot snapshot,NodeId selection,FaceSelection faceSelection) {
        Objects.requireNonNull(snapshot,"snapshot");
        var wireframe=new ArrayList<WorldLine>();var selectedFace=new ArrayList<WorldLine>();var markers=new ArrayList<WorldMarker>();boolean truncated=false;
        for(var node:snapshot.nodes()) {
            var transform=snapshot.worldTransform(node.id());
            if(node.light()!=null)markers.add(new WorldMarker(node.id(),MarkerKind.LIGHT,transform.point(Vec3.ZERO)));
            if(node.camera()!=null)markers.add(new WorldMarker(node.id(),MarkerKind.CAMERA,snapshot.camera(node.id()).eye()));
        }
        Vec3 pivot=null,x=null,y=null,z=null;
        if(selection!=null) {
            var selected=snapshot.requireNode(selection);var world=snapshot.worldTransform(selection);
            pivot=world.point(Vec3.ZERO);x=unit(world.vector(new Vec3(1,0,0)));y=unit(world.vector(new Vec3(0,1,0)));z=unit(world.vector(new Vec3(0,0,1)));
            Set<NodeId> included=selected.geometry()!=null?Set.of(selection):descendants(snapshot,selection);
            outer: for(var node:snapshot.nodes())if(included.contains(node.id())&&node.geometry()!=null) {
                var asset=snapshot.requireGeometry(node.geometry().geometryId());
                var local=cached(asset);
                truncated|=local.truncated;
                var transform=snapshot.worldTransform(node.id());
                for(var line:local.lines) {
                    if(wireframe.size()==MAX_WIREFRAME_SEGMENTS){truncated=true;break outer;}
                    wireframe.add(new WorldLine(node.id(),transform.point(line.start),transform.point(line.end)));
                }
            }
            if(faceSelection!=null&&faceSelection.nodeId().equals(selection)&&selected.geometry()!=null
                    &&selected.geometry().geometryId().equals(faceSelection.geometryId())) {
                var asset=snapshot.requireGeometry(faceSelection.geometryId());
                if(asset.geometry() instanceof EditableMeshGeometry mesh) {
                    var face=mesh.requireFace(faceSelection.faceId());var transform=snapshot.worldTransform(selection);
                    for(int i=0;i<face.vertexIds().size();i++) {
                        var a=mesh.requireVertex(face.vertexIds().get(i)).position();var b=mesh.requireVertex(face.vertexIds().get((i+1)%face.vertexIds().size())).position();
                        selectedFace.add(new WorldLine(selection,transform.point(a),transform.point(b)));
                    }
                }
            }
        }
        return new Prepared(snapshot.revision(),selection,faceSelection,pivot,x,y,z,wireframe,selectedFace,markers,truncated);
    }

    /** Project prepared data for the exact painted camera and viewport dimensions. */
    public Frame project(Prepared prepared,Camera camera,GizmoMode mode,int pixelWidth,int pixelHeight,double handlePixels) {
        Objects.requireNonNull(prepared,"prepared");Objects.requireNonNull(mode,"mode");
        if(pixelWidth<=0||pixelHeight<=0||!Double.isFinite(handlePixels)||handlePixels<=0)
            throw new IllegalArgumentException("Overlay viewport and handle size must be positive");
        var projector=CameraProjector.of(camera);var lines=new ArrayList<Line>();
        for(var world:prepared.wireframe)projector.clipAndProject(world.start,world.end).ifPresent(p->lines.add(
                line(world.nodeId,Style.WIREFRAME_XRAY,null,p)));
        var faceLines=new ArrayList<Line>();
        for(var world:prepared.selectedFace)projector.clipAndProject(world.start,world.end).ifPresent(p->faceLines.add(
                line(world.nodeId,Style.FACE_SELECTION_XRAY,null,p)));
        var markers=new ArrayList<Marker>();
        for(var world:prepared.markers)projector.project(world.position).filter(CameraProjector.ProjectedPoint::insideViewport)
                .ifPresent(p->markers.add(new Marker(world.nodeId,world.kind,p.u(),p.v())));
        var handles=new ArrayList<Handle>();
        if(mode!=GizmoMode.NONE&&prepared.selection!=null&&prepared.pivot!=null) {
            var height=projector.screenHeightAt(prepared.pivot);
            if(height.isPresent()) {
                double size=height.getAsDouble()*handlePixels/pixelHeight;
                if(Double.isFinite(size)&&size>0)for(var axis:Axis.values()) {
                    var projected=mode==GizmoMode.TRANSLATE
                            ?translation(projector,prepared,axis,(float)size)
                            :rotation(projector,prepared,axis,(float)(size*.82));
                    if(!projected.isEmpty())handles.add(new Handle(prepared.selection,mode,axis,projected));
                }
            }
        }
        return new Frame(prepared.sceneRevision,lines,faceLines,markers,handles,prepared.wireframeTruncated);
    }

    public synchronized int cachedAssetCount(){return cache.size();}
    public synchronized int cachedSegmentCount(){return cachedSegments;}
    public synchronized void clearCache(){cache.clear();cachedSegments=0;}

    private Cached cached(GeometryAsset asset) {
        var key=new AssetKey(asset);synchronized(this){var found=cache.get(key);if(found!=null)return found;}
        var built=wireframe(asset.geometry());
        synchronized(this) {
            var found=cache.get(key);if(found!=null)return found;
            cache.put(key,built);cachedSegments+=built.lines.size();
            var iterator=cache.entrySet().iterator();
            while((cache.size()>MAX_CACHED_ASSETS||cachedSegments>MAX_CACHED_SEGMENTS)&&cache.size()>1&&iterator.hasNext()) {
                var old=iterator.next();cachedSegments-=old.getValue().lines.size();iterator.remove();
            }
            return built;
        }
    }

    private static List<Line> translation(CameraProjector projector,Prepared prepared,Axis axis,float size) {
        var result=new ArrayList<Line>();var direction=prepared.axis(axis);var side=prepared.axis(axis==Axis.X?Axis.Y:Axis.X);
        var end=prepared.pivot.add(direction.scale(size));
        add(projector,result,prepared.selection,Style.TRANSLATION_HANDLE_XRAY,axis,prepared.pivot,end);
        var back=direction.scale(-size*.18f);var wing=side.scale(size*.09f);
        add(projector,result,prepared.selection,Style.TRANSLATION_HANDLE_XRAY,axis,end,end.add(back).add(wing));
        add(projector,result,prepared.selection,Style.TRANSLATION_HANDLE_XRAY,axis,end,end.add(back).sub(wing));
        return List.copyOf(result);
    }
    private static List<Line> rotation(CameraProjector projector,Prepared prepared,Axis axis,float radius) {
        var result=new ArrayList<Line>();Vec3 a,b;
        switch(axis){case X->{a=prepared.yAxis;b=prepared.zAxis;}case Y->{a=prepared.zAxis;b=prepared.xAxis;}default->{a=prepared.xAxis;b=prepared.yAxis;}}
        Vec3 previous=ring(prepared.pivot,a,b,radius,0);
        for(int i=1;i<=RING_SEGMENTS;i++) {var next=ring(prepared.pivot,a,b,radius,i*(2*Math.PI/RING_SEGMENTS));add(projector,result,prepared.selection,Style.ROTATION_HANDLE_XRAY,axis,previous,next);previous=next;}
        return List.copyOf(result);
    }
    private static Vec3 ring(Vec3 pivot,Vec3 a,Vec3 b,float radius,double angle){return pivot.add(a.scale(radius*(float)Math.cos(angle))).add(b.scale(radius*(float)Math.sin(angle)));}
    private static void add(CameraProjector projector,List<Line> output,NodeId node,Style style,Axis axis,Vec3 a,Vec3 b){projector.clipAndProject(a,b).ifPresent(p->output.add(line(node,style,axis,p)));}
    private static Line line(NodeId node,Style style,Axis axis,CameraProjector.ProjectedSegment segment){return new Line(node,style,axis,segment.start().u(),segment.start().v(),segment.end().u(),segment.end().v());}

    private static Set<NodeId> descendants(SceneSnapshot snapshot,NodeId root) {
        var children=new HashMap<NodeId,List<NodeId>>();
        for(var node:snapshot.nodes())if(node.parentId()!=null)children.computeIfAbsent(node.parentId(),unused->new ArrayList<>()).add(node.id());
        var result=new LinkedHashSet<NodeId>();var queue=new ArrayDeque<NodeId>();result.add(root);queue.add(root);
        while(!queue.isEmpty())for(var child:children.getOrDefault(queue.remove(),List.of()))if(result.add(child))queue.add(child);
        return result;
    }
    private static Cached wireframe(GeometryData geometry) {
        var output=new ArrayList<LocalLine>();boolean truncated=false;
        if(geometry instanceof BoxGeometry) {
            Vec3[] v=new Vec3[8];for(int i=0;i<8;i++)v[i]=new Vec3((i&1)==0?-1:1,(i&2)==0?-1:1,(i&4)==0?-1:1);
            for(int i=0;i<8;i++)for(int bit:new int[]{1,2,4})if((i&bit)==0)output.add(new LocalLine(v[i],v[i|bit]));
        } else if(geometry instanceof RectGeometry rect) {
            var a=rect.origin();var b=a.add(rect.edge1());var c=b.add(rect.edge2());var d=a.add(rect.edge2());
            output.add(new LocalLine(a,b));output.add(new LocalLine(b,c));output.add(new LocalLine(c,d));output.add(new LocalLine(d,a));
        } else if(geometry instanceof SphereGeometry sphere) {
            for(int plane=0;plane<3;plane++)for(int i=0;i<SPHERE_SEGMENTS;i++) {
                double a=i*2*Math.PI/SPHERE_SEGMENTS,b=(i+1)*2*Math.PI/SPHERE_SEGMENTS;
                output.add(new LocalLine(circle(sphere,plane,a),circle(sphere,plane,b)));
            }
        } else if(geometry instanceof EditableMeshGeometry mesh) {
            var vertices=new HashMap<Long,Vec3>();for(var vertex:mesh.editableVertices())vertices.put(vertex.id(),vertex.position());
            for(var edge:mesh.edges()) {
                if(output.size()==MAX_WIREFRAME_SEGMENTS){truncated=true;break;}
                output.add(new LocalLine(vertices.get(edge.firstVertexId()),vertices.get(edge.secondVertexId())));
            }
        } else if(geometry instanceof TriangleMesh mesh) {
            var vertices=mesh.vertices();var indices=mesh.indices();var edges=new HashSet<Long>();
            scan: for(int i=0;i<indices.length;i+=3)for(int e=0;e<3;e++) {
                int a=indices[i+e],b=indices[i+(e+1)%3],lo=Math.min(a,b),hi=Math.max(a,b);long key=((long)lo<<32)|(hi&0xffffffffL);
                if(edges.add(key)) {
                    if(output.size()==MAX_WIREFRAME_SEGMENTS){truncated=true;break scan;}
                    output.add(new LocalLine(vertices.get(a),vertices.get(b)));
                }
            }
        } else throw new IllegalArgumentException("Unsupported overlay geometry: "+geometry.getClass().getName());
        return new Cached(output,truncated);
    }
    private static Vec3 circle(SphereGeometry sphere,int plane,double angle) {
        float a=sphere.radius()*(float)Math.cos(angle),b=sphere.radius()*(float)Math.sin(angle);var c=sphere.center();
        return switch(plane){case 0->c.add(new Vec3(a,b,0));case 1->c.add(new Vec3(a,0,b));default->c.add(new Vec3(0,a,b));};
    }
    private static Vec3 unit(Vec3 value) {
        double x=value.x(),y=value.y(),z=value.z(),scale=Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z)));
        double length=Math.sqrt((x/scale)*(x/scale)+(y/scale)*(y/scale)+(z/scale)*(z/scale));
        return new Vec3((float)(x/scale/length),(float)(y/scale/length),(float)(z/scale/length));
    }
    private static double distance(double px,double py,double ax,double ay,double bx,double by) {
        double dx=bx-ax,dy=by-ay,length=dx*dx+dy*dy;
        if(length==0)return Math.hypot(px-ax,py-ay);
        double t=Math.clamp(((px-ax)*dx+(py-ay)*dy)/length,0,1);
        return Math.hypot(px-(ax+t*dx),py-(ay+t*dy));
    }
}
