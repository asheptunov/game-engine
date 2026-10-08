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
    public static final int MAX_WIREFRAME_SEGMENTS = 100_000;
    public static final int MAX_CACHED_ASSETS=256;
    public static final int MAX_CACHED_SEGMENTS=200_000;
    public static final int MAX_ELEMENT_PICK_CANDIDATES = 100_000;
    public static final int MAX_ELEMENT_CUES = 10_000;
    public static final double VERTEX_PICK_RADIUS_PIXELS = 8;
    public static final double EDGE_PICK_DISTANCE_PIXELS = 6;
    public static final int SPHERE_SEGMENTS=48;
    public static final int RING_SEGMENTS=64;

    public enum Axis { X,Y,Z }
    public enum GizmoMode { NONE,TRANSLATE,ROTATE }
    public enum ElementMode { OBJECT, VERTEX, EDGE, FACE }
    public enum ElementKind { VERTEX, EDGE, FACE }
    public enum MarkerKind { LIGHT,CAMERA }
    public enum Style {
        WIREFRAME_XRAY,
        VERTEX_BOUNDARY_CUE_XRAY,
        EDGE_CUE_XRAY,
        FACE_BOUNDARY_CUE_XRAY,
        EDGE_SELECTION_XRAY,
        FACE_SELECTION_XRAY,
        TRANSLATION_HANDLE_XRAY,
        ROTATION_HANDLE_XRAY
    }
    public enum HitKind { HANDLE,MARKER }

    public record Line(NodeId nodeId,Style style,Axis axis,double u1,double v1,double u2,double v2) {
        public Line { Objects.requireNonNull(nodeId);Objects.requireNonNull(style); }
    }
    public record Marker(NodeId nodeId,MarkerKind kind,double u,double v) {
        public Marker { Objects.requireNonNull(nodeId);Objects.requireNonNull(kind); }
    }
    public record Point(NodeId nodeId, double u, double v) {
        public Point { Objects.requireNonNull(nodeId); }
    }
    public record CuePoint(NodeId nodeId, GeometryId geometryId, ElementKind kind,
                           long elementId, double u, double v) {
        public CuePoint {
            Objects.requireNonNull(nodeId);
            Objects.requireNonNull(geometryId);
            Objects.requireNonNull(kind);
        }
    }
    public record Handle(NodeId nodeId,GizmoMode mode,Axis axis,List<Line> lines) {
        public Handle { Objects.requireNonNull(nodeId);Objects.requireNonNull(mode);Objects.requireNonNull(axis);lines=List.copyOf(lines); }
    }
    public record Hit(NodeId nodeId,HitKind kind,MarkerKind markerKind,GizmoMode mode,Axis axis,double distancePixels) {}
    public record FaceSelection(NodeId nodeId,GeometryId geometryId,long faceId) {
        public FaceSelection { Objects.requireNonNull(nodeId);Objects.requireNonNull(geometryId); }
    }
    public record ElementSelection(NodeId nodeId, GeometryId geometryId, ElementKind kind,
                                   long firstId, long secondId) {
        public ElementSelection {
            Objects.requireNonNull(nodeId);
            Objects.requireNonNull(geometryId);
            Objects.requireNonNull(kind);
            if (firstId < 0 || kind == ElementKind.EDGE && firstId >= secondId) {
                throw new IllegalArgumentException("Element IDs are invalid");
            }
        }
    }
    public record VertexHit(NodeId nodeId,GeometryId geometryId,long vertexId,double distancePixels) {}
    public record EdgeHit(NodeId nodeId,GeometryId geometryId,long firstVertexId,long secondVertexId,double distancePixels) {}
    public record Frame(long sceneRevision,List<Line> wireframe,List<Line> elementCueLines,
                        List<CuePoint> elementCuePoints,List<Line> selectedFace,
                        List<Point> selectedVertices,List<Line> selectedEdges,
                        List<Marker> markers,List<Handle> handles,
                        boolean wireframeTruncated,boolean elementCuesTruncated,
                        boolean elementCandidatesTruncated) {
        public Frame {
            wireframe=List.copyOf(wireframe);elementCueLines=List.copyOf(elementCueLines);
            elementCuePoints=List.copyOf(elementCuePoints);selectedFace=List.copyOf(selectedFace);
            selectedVertices=List.copyOf(selectedVertices);selectedEdges=List.copyOf(selectedEdges);
            markers=List.copyOf(markers);handles=List.copyOf(handles);
        }
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
    public record WorldVertex(NodeId nodeId,GeometryId geometryId,long vertexId,Vec3 position) {}
    public record WorldEdge(NodeId nodeId,GeometryId geometryId,long firstVertexId,long secondVertexId,Vec3 start,Vec3 end) {}
    public record WorldCueLine(NodeId nodeId, GeometryId geometryId, ElementKind kind,
                               long firstId, long secondId, Vec3 start, Vec3 end) {}
    public record WorldCuePoint(NodeId nodeId, GeometryId geometryId, ElementKind kind,
                                long elementId, Vec3 position) {}
    public record Prepared(long sceneRevision,NodeId selection,ElementMode elementMode,ElementSelection elementSelection,
                           Vec3 pivot,Vec3 xAxis,Vec3 yAxis,Vec3 zAxis,List<WorldLine> wireframe,List<WorldLine> selectedFace,
                           List<Vec3> selectedVertices,List<WorldLine> selectedEdges,List<WorldMarker> markers,
                           List<WorldVertex> elementVertices,List<WorldEdge> elementEdges,
                           List<WorldCueLine> elementCueLines,List<WorldCuePoint> elementCuePoints,
                           boolean wireframeTruncated,boolean elementCuesTruncated,
                           boolean elementCandidatesTruncated) {
        public Prepared {
            wireframe=List.copyOf(wireframe);selectedFace=List.copyOf(selectedFace);selectedVertices=List.copyOf(selectedVertices);
            selectedEdges=List.copyOf(selectedEdges);markers=List.copyOf(markers);
            elementVertices=List.copyOf(elementVertices);elementEdges=List.copyOf(elementEdges);
            elementCueLines=List.copyOf(elementCueLines);elementCuePoints=List.copyOf(elementCuePoints);
        }
        public Vec3 axis(Axis axis){return switch(axis){case X->xAxis;case Y->yAxis;case Z->zAxis;};}
    }

    private record LocalLine(Vec3 start,Vec3 end){}
    private record LocalVertex(long id,Vec3 position){}
    private record LocalEdge(long firstId,long secondId,Vec3 start,Vec3 end){}
    private record Cached(List<LocalLine> lines, List<LocalVertex> vertices, List<LocalEdge> edges,
                          boolean wireframeTruncated, boolean vertexCandidatesTruncated,
                          boolean edgeCandidatesTruncated) {
        Cached {
            lines = List.copyOf(lines);
            vertices = List.copyOf(vertices);
            edges = List.copyOf(edges);
        }
    }
    private static final class AssetKey {
        final GeometryId id;final long revision;final GeometryData geometry;final int hash;
        AssetKey(GeometryAsset asset){id=asset.id();revision=asset.revision();geometry=asset.geometry();hash=31*(31*id.hashCode()+Long.hashCode(revision))+System.identityHashCode(geometry);}
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object value){return value instanceof AssetKey key&&revision==key.revision&&id.equals(key.id)&&geometry==key.geometry;}
    }
    private final Map<AssetKey,Cached> cache=new LinkedHashMap<>(16,.75f,true);
    private final int maxWireframeSegments;
    private final int maxElementPickCandidates;
    private final int maxElementCues;
    private int cachedSegments;

    public OverlayGeometry() {
        this(MAX_WIREFRAME_SEGMENTS, MAX_ELEMENT_PICK_CANDIDATES, MAX_ELEMENT_CUES);
    }

    OverlayGeometry(int maxWireframeSegments, int maxElementPickCandidates) {
        this(maxWireframeSegments, maxElementPickCandidates, MAX_ELEMENT_CUES);
    }

    OverlayGeometry(int maxWireframeSegments, int maxElementPickCandidates, int maxElementCues) {
        if (maxWireframeSegments < 1 || maxElementPickCandidates < 1 || maxElementCues < 1) {
            throw new IllegalArgumentException("Overlay limits must be positive");
        }
        this.maxWireframeSegments = maxWireframeSegments;
        this.maxElementPickCandidates = maxElementPickCandidates;
        this.maxElementCues = maxElementCues;
    }

    /** Build world-space overlay data. Selected geometry shows itself; a component-free group shows its subtree. */
    public Prepared prepare(SceneSnapshot snapshot,NodeId selection) {
        return prepare(snapshot,selection,ElementMode.OBJECT,null);
    }
    /** Face selection is camera-independent and becomes part of the immutable prepared context. */
    public Prepared prepare(SceneSnapshot snapshot,NodeId selection,FaceSelection faceSelection) {
        var element = faceSelection == null ? null : new ElementSelection(
                faceSelection.nodeId(), faceSelection.geometryId(), ElementKind.FACE,
                faceSelection.faceId(), -1);
        return prepare(snapshot, selection, ElementMode.FACE, element);
    }
    /** Element candidates are bounded, deterministic, camera-independent x-ray pick data. */
    public Prepared prepare(SceneSnapshot snapshot,NodeId selection,ElementMode elementMode,ElementSelection elementSelection) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(elementMode, "elementMode");
        var wireframe = new ArrayList<WorldLine>();
        var selectedFace = new ArrayList<WorldLine>();
        var selectedVertices = new ArrayList<Vec3>();
        var selectedEdges = new ArrayList<WorldLine>();
        var markers = new ArrayList<WorldMarker>();
        var candidateVertices = new ArrayList<WorldVertex>();
        var candidateEdges = new ArrayList<WorldEdge>();
        var cueLines = new ArrayList<WorldCueLine>();
        var cuePoints = new ArrayList<WorldCuePoint>();
        boolean truncated = false;
        boolean elementTruncated = false;
        for (var node : snapshot.nodes()) {
            var transform = snapshot.worldTransform(node.id());
            if (node.light() != null) {
                markers.add(new WorldMarker(node.id(), MarkerKind.LIGHT, transform.point(Vec3.ZERO)));
            }
            if (node.camera() != null) {
                markers.add(new WorldMarker(node.id(), MarkerKind.CAMERA, snapshot.camera(node.id()).eye()));
            }
        }
        if (elementMode == ElementMode.VERTEX || elementMode == ElementMode.EDGE) {
            var nodes = snapshot.nodes().stream().filter(node -> node.geometry() != null)
                    .filter(node -> snapshot.requireGeometry(node.geometry().geometryId()).geometry() instanceof PolygonMesh)
                    .sorted(Comparator.comparing(node -> node.id().value())).toList();
            for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
                var node = nodes.get(nodeIndex);
                var asset = snapshot.requireGeometry(node.geometry().geometryId());
                var local = cached(asset);
                var transform = snapshot.worldTransform(node.id());
                if (elementMode == ElementMode.VERTEX) {
                    for (var vertex : local.vertices) {
                        if (candidateVertices.size() == maxElementPickCandidates) {
                            elementTruncated = true;
                            break;
                        }
                        candidateVertices.add(new WorldVertex(
                                node.id(), asset.id(), vertex.id(), transform.point(vertex.position())));
                    }
                    elementTruncated |= local.vertexCandidatesTruncated;
                } else {
                    for (var edge : local.edges) {
                        if (candidateEdges.size() == maxElementPickCandidates) {
                            elementTruncated = true;
                            break;
                        }
                        candidateEdges.add(new WorldEdge(
                                node.id(), asset.id(), edge.firstId(), edge.secondId(),
                                transform.point(edge.start()), transform.point(edge.end())));
                    }
                    elementTruncated |= local.edgeCandidatesTruncated;
                }
                int size = elementMode == ElementMode.VERTEX
                        ? candidateVertices.size() : candidateEdges.size();
                if (size == maxElementPickCandidates) {
                    if (nodeIndex + 1 < nodes.size()) elementTruncated = true;
                    break;
                }
            }
        }
        boolean cuesTruncated = prepareElementCues(
                snapshot, selection, elementMode, cueLines, cuePoints);
        Vec3 pivot=null,x=null,y=null,z=null;
        if(selection!=null) {
            var selected=snapshot.requireNode(selection);var world=snapshot.worldTransform(selection);
            x=unit(world.vector(new Vec3(1,0,0)));y=unit(world.vector(new Vec3(0,1,0)));z=unit(world.vector(new Vec3(0,0,1)));
            if(elementMode==ElementMode.OBJECT)pivot=world.point(Vec3.ZERO);
            Set<NodeId> included=selected.geometry()!=null?Set.of(selection):descendants(snapshot,selection);
            outer: for(var node:snapshot.nodes())if(included.contains(node.id())&&node.geometry()!=null) {
                var asset=snapshot.requireGeometry(node.geometry().geometryId());
                var local=cached(asset);
                truncated|=local.wireframeTruncated;
                var transform=snapshot.worldTransform(node.id());
                for(var line:local.lines) {
                    if(wireframe.size()==maxWireframeSegments){truncated=true;break outer;}
                    wireframe.add(new WorldLine(node.id(),transform.point(line.start),transform.point(line.end)));
                }
            }
            if(elementSelection!=null&&elementSelection.nodeId().equals(selection)&&selected.geometry()!=null
                    &&selected.geometry().geometryId().equals(elementSelection.geometryId())) {
                var asset=snapshot.requireGeometry(elementSelection.geometryId());
                if(asset.geometry() instanceof PolygonMesh mesh) {
                    var transform=snapshot.worldTransform(selection);
                    if(elementSelection.kind()==ElementKind.VERTEX){
                        var position=transform.point(mesh.requireVertex(elementSelection.firstId()).position());
                        selectedVertices.add(position);pivot=position;
                    }else if(elementSelection.kind()==ElementKind.EDGE){
                        var a=transform.point(mesh.requireVertex(elementSelection.firstId()).position());
                        var b=transform.point(mesh.requireVertex(elementSelection.secondId()).position());
                        selectedEdges.add(new WorldLine(selection,a,b));pivot=a.add(b).scale(.5f);
                    }else{
                        var face=mesh.requireFace(elementSelection.firstId());
                        pivot = transform.point(faceVertexCentroid(mesh, face));
                        for(int i=0;i<face.vertexIds().size();i++) {
                            var a=mesh.requireVertex(face.vertexIds().get(i)).position();var b=mesh.requireVertex(face.vertexIds().get((i+1)%face.vertexIds().size())).position();
                            selectedFace.add(new WorldLine(selection,transform.point(a),transform.point(b)));
                        }
                    }
                }
            }
        }
        return new Prepared(snapshot.revision(),selection,elementMode,elementSelection,pivot,x,y,z,wireframe,selectedFace,
                selectedVertices,selectedEdges,markers,candidateVertices,candidateEdges,cueLines,cuePoints,
                truncated,cuesTruncated,elementTruncated);
    }

    /**
     * Build a camera-independent, finite cue set. Selection highlight and handles are
     * prepared separately so this display budget can never hide the active element.
     */
    private boolean prepareElementCues(
            SceneSnapshot snapshot, NodeId selectedNode, ElementMode mode,
            List<WorldCueLine> cueLines, List<WorldCuePoint> cuePoints) {
        if (mode == ElementMode.OBJECT) return false;
        var nodes = polygonNodes(snapshot, selectedNode);
        return switch (mode) {
            case VERTEX -> prepareVertexCues(snapshot, nodes, cueLines, cuePoints);
            case EDGE -> prepareEdgeCues(snapshot, nodes, cueLines);
            case FACE -> prepareFaceCues(snapshot, nodes, cueLines, cuePoints);
            case OBJECT -> false;
        };
    }

    private boolean prepareVertexCues(
            SceneSnapshot snapshot, List<SceneNode> nodes,
            List<WorldCueLine> cueLines, List<WorldCuePoint> cuePoints) {
        boolean truncated = false;
        int pointBudget = Math.max(1, maxElementCues * 2 / 3);
        outerPoints:
        for (var node : nodes) {
            var asset = snapshot.requireGeometry(node.geometry().geometryId());
            var local = cached(asset);
            var transform = snapshot.worldTransform(node.id());
            for (var vertex : local.vertices) {
                if (cuePoints.size() == pointBudget) {
                    truncated = true;
                    break outerPoints;
                }
                cuePoints.add(new WorldCuePoint(
                        node.id(), asset.id(), ElementKind.VERTEX, vertex.id(),
                        transform.point(vertex.position())));
            }
        }
        outerLines:
        for (var node : nodes) {
            var asset = snapshot.requireGeometry(node.geometry().geometryId());
            var local = cached(asset);
            var transform = snapshot.worldTransform(node.id());
            for (var edge : local.edges) {
                if (cuePoints.size() + cueLines.size() == maxElementCues) {
                    truncated = true;
                    break outerLines;
                }
                cueLines.add(new WorldCueLine(
                        node.id(), asset.id(), ElementKind.VERTEX,
                        edge.firstId(), edge.secondId(),
                        transform.point(edge.start()), transform.point(edge.end())));
            }
        }
        return truncated;
    }

    private boolean prepareEdgeCues(
            SceneSnapshot snapshot, List<SceneNode> nodes, List<WorldCueLine> cueLines) {
        for (var node : nodes) {
            var asset = snapshot.requireGeometry(node.geometry().geometryId());
            var local = cached(asset);
            var transform = snapshot.worldTransform(node.id());
            for (var edge : local.edges) {
                if (cueLines.size() == maxElementCues) return true;
                cueLines.add(new WorldCueLine(
                        node.id(), asset.id(), ElementKind.EDGE,
                        edge.firstId(), edge.secondId(),
                        transform.point(edge.start()), transform.point(edge.end())));
            }
        }
        return false;
    }

    private boolean prepareFaceCues(
            SceneSnapshot snapshot, List<SceneNode> nodes,
            List<WorldCueLine> cueLines, List<WorldCuePoint> cuePoints) {
        for (var node : nodes) {
            var asset = snapshot.requireGeometry(node.geometry().geometryId());
            var mesh = (PolygonMesh) asset.geometry();
            var transform = snapshot.worldTransform(node.id());
            var faceIds = smallestFaceIds(mesh);
            for (long faceId : faceIds.ids()) {
                var face = mesh.requireFace(faceId);
                int faceCost = face.vertexIds().size() + 1;
                if (cueLines.size() + cuePoints.size() + faceCost > maxElementCues) return true;
                cuePoints.add(new WorldCuePoint(
                        node.id(), asset.id(), ElementKind.FACE, faceId,
                        transform.point(faceSurfaceMarker(mesh, face))));
                for (int index = 0; index < face.vertexIds().size(); index++) {
                    long firstId = face.vertexIds().get(index);
                    long secondId = face.vertexIds().get((index + 1) % face.vertexIds().size());
                    cueLines.add(new WorldCueLine(
                            node.id(), asset.id(), ElementKind.FACE, faceId, -1,
                            transform.point(mesh.requireVertex(firstId).position()),
                            transform.point(mesh.requireVertex(secondId).position())));
                }
            }
            if (faceIds.truncated()) return true;
        }
        return false;
    }

    private List<SceneNode> polygonNodes(SceneSnapshot snapshot, NodeId selectedNode) {
        return snapshot.nodes().stream()
                .filter(node -> node.geometry() != null)
                .filter(node -> snapshot.requireGeometry(
                        node.geometry().geometryId()).geometry() instanceof PolygonMesh)
                .sorted(Comparator
                        .comparing((SceneNode node) -> !Objects.equals(node.id(), selectedNode))
                        .thenComparing(node -> node.id().value()))
                .toList();
    }

    private record FaceIds(List<Long> ids, boolean truncated) {}

    /** Retain only the lowest stable face IDs; no unbounded face cache is created. */
    private FaceIds smallestFaceIds(PolygonMesh mesh) {
        var retained = new PriorityQueue<Long>(maxElementCues, Comparator.reverseOrder());
        boolean truncated = false;
        for (var face : mesh.faces()) {
            if (retained.size() < maxElementCues) {
                retained.add(face.id());
            } else {
                truncated = true;
                if (face.id() < retained.element()) {
                    retained.remove();
                    retained.add(face.id());
                }
            }
        }
        var ordered = new ArrayList<>(retained);
        ordered.sort(Long::compare);
        return new FaceIds(List.copyOf(ordered), truncated);
    }

    /** Boundary centroid is the manipulation pivot, even when the authored face is warped. */
    private static Vec3 faceVertexCentroid(PolygonMesh mesh, PolygonMesh.Face face) {
        double x = 0;
        double y = 0;
        double z = 0;
        for (long vertexId : face.vertexIds()) {
            Vec3 position = mesh.requireVertex(vertexId).position();
            x += position.x();
            y += position.y();
            z += position.z();
        }
        double inverseCount = 1.0 / face.vertexIds().size();
        return new Vec3(
                (float) (x * inverseCount),
                (float) (y * inverseCount),
                (float) (z * inverseCount));
    }

    /**
     * Put the face cue on its rendered surface: largest fan-triangle centroid, with
     * strict comparison so equal-area ties keep the lowest fan index.
     */
    private static Vec3 faceSurfaceMarker(PolygonMesh mesh, PolygonMesh.Face face) {
        Vec3 first = mesh.requireVertex(face.vertexIds().getFirst()).position();
        Vec3 bestSecond = null;
        Vec3 bestThird = null;
        double bestAreaSquared = -1;
        for (int index = 1; index + 1 < face.vertexIds().size(); index++) {
            Vec3 second = mesh.requireVertex(face.vertexIds().get(index)).position();
            Vec3 third = mesh.requireVertex(face.vertexIds().get(index + 1)).position();
            double areaSquared = triangleAreaSquared(first, second, third);
            if (areaSquared > bestAreaSquared) {
                bestAreaSquared = areaSquared;
                bestSecond = second;
                bestThird = third;
            }
        }
        return new Vec3(
                (float) (((double) first.x() + bestSecond.x() + bestThird.x()) / 3.0),
                (float) (((double) first.y() + bestSecond.y() + bestThird.y()) / 3.0),
                (float) (((double) first.z() + bestSecond.z() + bestThird.z()) / 3.0));
    }

    private static double triangleAreaSquared(Vec3 first, Vec3 second, Vec3 third) {
        double ax = (double) second.x() - first.x();
        double ay = (double) second.y() - first.y();
        double az = (double) second.z() - first.z();
        double bx = (double) third.x() - first.x();
        double by = (double) third.y() - first.y();
        double bz = (double) third.z() - first.z();
        double cx = ay * bz - az * by;
        double cy = az * bx - ax * bz;
        double cz = ax * by - ay * bx;
        return cx * cx + cy * cy + cz * cz;
    }

    /** Project prepared data for the exact painted camera and viewport dimensions. */
    public Frame project(Prepared prepared,Camera camera,GizmoMode mode,int pixelWidth,int pixelHeight,double handlePixels) {
        Objects.requireNonNull(prepared,"prepared");Objects.requireNonNull(mode,"mode");
        if(pixelWidth<=0||pixelHeight<=0||!Double.isFinite(handlePixels)||handlePixels<=0)
            throw new IllegalArgumentException("Overlay viewport and handle size must be positive");
        var projector=CameraProjector.of(camera);var lines=new ArrayList<Line>();
        for(var world:prepared.wireframe)projector.clipAndProject(world.start,world.end).ifPresent(p->lines.add(
                line(world.nodeId,Style.WIREFRAME_XRAY,null,p)));
        var cueLines = new ArrayList<Line>();
        for (var world : prepared.elementCueLines) {
            projector.clipAndProject(world.start(), world.end()).ifPresent(projected -> cueLines.add(
                    line(world.nodeId(), cueLineStyle(world.kind()), null, projected)));
        }
        var cuePoints = new ArrayList<CuePoint>();
        for (var world : prepared.elementCuePoints) {
            projector.project(world.position())
                    .filter(CameraProjector.ProjectedPoint::insideViewport)
                    .ifPresent(projected -> cuePoints.add(new CuePoint(
                            world.nodeId(), world.geometryId(), world.kind(), world.elementId(),
                            projected.u(), projected.v())));
        }
        var faceLines=new ArrayList<Line>();
        for(var world:prepared.selectedFace)projector.clipAndProject(world.start,world.end).ifPresent(p->faceLines.add(
                line(world.nodeId,Style.FACE_SELECTION_XRAY,null,p)));
        var selectedVertexPoints=new ArrayList<Point>();
        for(var world:prepared.selectedVertices)projector.project(world).filter(CameraProjector.ProjectedPoint::insideViewport)
                .ifPresent(point->selectedVertexPoints.add(new Point(prepared.selection,point.u(),point.v())));
        var selectedEdgeLines=new ArrayList<Line>();
        for(var world:prepared.selectedEdges)projector.clipAndProject(world.start,world.end).ifPresent(projected->selectedEdgeLines.add(
                line(world.nodeId,Style.EDGE_SELECTION_XRAY,null,projected)));
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
        return new Frame(prepared.sceneRevision,lines,cueLines,cuePoints,faceLines,
                selectedVertexPoints,selectedEdgeLines,markers,handles,
                prepared.wireframeTruncated,prepared.elementCuesTruncated,
                prepared.elementCandidatesTruncated);
    }

    private static Style cueLineStyle(ElementKind kind) {
        return switch (kind) {
            case VERTEX -> Style.VERTEX_BOUNDARY_CUE_XRAY;
            case EDGE -> Style.EDGE_CUE_XRAY;
            case FACE -> Style.FACE_BOUNDARY_CUE_XRAY;
        };
    }

    /** Project bounded vertex candidates only for an explicit click against its captured camera. */
    public Optional<VertexHit> pickVertex(Prepared prepared,Camera camera,double u,double v,int pixelWidth,int pixelHeight){
        checkPick(u,v,pixelWidth,pixelHeight);var projector=CameraProjector.of(camera);VertexHit best=null;
        for(var candidate:prepared.elementVertices){
            var projected=projector.project(candidate.position());
            if(projected.isEmpty()||!projected.get().insideViewport())continue;
            double distance=Math.hypot((u-projected.get().u())*pixelWidth,(v-projected.get().v())*pixelHeight);
            if(distance<=VERTEX_PICK_RADIUS_PIXELS&&better(distance,candidate.nodeId(),candidate.vertexId(),-1,best==null?null:best.nodeId(),
                    best==null?-1:best.vertexId(),-1,best==null?Double.POSITIVE_INFINITY:best.distancePixels()))
                best=new VertexHit(candidate.nodeId(),candidate.geometryId(),candidate.vertexId(),distance);
        }
        return Optional.ofNullable(best);
    }

    /** Project bounded edge candidates only for an explicit click against its captured camera. */
    public Optional<EdgeHit> pickEdge(Prepared prepared,Camera camera,double u,double v,int pixelWidth,int pixelHeight){
        checkPick(u,v,pixelWidth,pixelHeight);var projector=CameraProjector.of(camera);EdgeHit best=null;
        for(var candidate:prepared.elementEdges){
            var projected=projector.clipAndProject(candidate.start(),candidate.end());if(projected.isEmpty())continue;
            var segment=projected.get();double distance=distance(u*pixelWidth,v*pixelHeight,segment.start().u()*pixelWidth,
                    segment.start().v()*pixelHeight,segment.end().u()*pixelWidth,segment.end().v()*pixelHeight);
            if(distance<=EDGE_PICK_DISTANCE_PIXELS&&better(distance,candidate.nodeId(),candidate.firstVertexId(),candidate.secondVertexId(),
                    best==null?null:best.nodeId(),best==null?-1:best.firstVertexId(),best==null?-1:best.secondVertexId(),
                    best==null?Double.POSITIVE_INFINITY:best.distancePixels()))
                best=new EdgeHit(candidate.nodeId(),candidate.geometryId(),candidate.firstVertexId(),candidate.secondVertexId(),distance);
        }
        return Optional.ofNullable(best);
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
    private Cached wireframe(GeometryData geometry) {
        var output = new ArrayList<LocalLine>();
        var vertices = new ArrayList<LocalVertex>();
        var edges = new ArrayList<LocalEdge>();
        boolean wireframeTruncated = false;
        boolean vertexCandidatesTruncated = false;
        boolean edgeCandidatesTruncated = false;
        if (geometry instanceof AnalyticSphere sphere) {
            for (int plane = 0; plane < 3; plane++) for (int i = 0; i < SPHERE_SEGMENTS; i++) {
                double firstAngle = i * 2 * Math.PI / SPHERE_SEGMENTS;
                double secondAngle = (i + 1) * 2 * Math.PI / SPHERE_SEGMENTS;
                output.add(new LocalLine(
                        circle(sphere, plane, firstAngle), circle(sphere, plane, secondAngle)));
            }
        } else if (geometry instanceof PolygonMesh mesh) {
            var byId = new HashMap<Long, Vec3>();
            int retainedElementGeometry = Math.max(maxElementPickCandidates, maxElementCues);
            var stableEdgeOrder = Comparator.comparingLong(PolygonMesh.Edge::firstVertexId)
                    .thenComparingLong(PolygonMesh.Edge::secondVertexId);
            var smallestStableEdges = new PriorityQueue<PolygonMesh.Edge>(
                    retainedElementGeometry, stableEdgeOrder.reversed());
            var sortedVertices = mesh.editableVertices().stream()
                    .sorted(Comparator.comparingLong(PolygonMesh.Vertex::id)).toList();
            for (var vertex : sortedVertices) {
                byId.put(vertex.id(), vertex.position());
                if (vertices.size() < retainedElementGeometry) {
                    vertices.add(new LocalVertex(vertex.id(), vertex.position()));
                }
            }
            vertexCandidatesTruncated = sortedVertices.size() > maxElementPickCandidates;
            for (var edge : mesh.edges()) {
                var start = byId.get(edge.firstVertexId());
                var end = byId.get(edge.secondVertexId());
                if (output.size() < maxWireframeSegments) output.add(new LocalLine(start, end));
                else wireframeTruncated = true;
                if (smallestStableEdges.size() < retainedElementGeometry) {
                    smallestStableEdges.add(edge);
                } else {
                    if (stableEdgeOrder.compare(edge, smallestStableEdges.element()) < 0) {
                        smallestStableEdges.remove();
                        smallestStableEdges.add(edge);
                    }
                }
            }
            var sortedEdges = new ArrayList<>(smallestStableEdges);
            sortedEdges.sort(stableEdgeOrder);
            edgeCandidatesTruncated = mesh.edges().size() > maxElementPickCandidates;
            for (var edge : sortedEdges) {
                edges.add(new LocalEdge(edge.firstVertexId(), edge.secondVertexId(),
                        byId.get(edge.firstVertexId()), byId.get(edge.secondVertexId())));
            }
        } else {
            throw new IllegalArgumentException("Unsupported overlay geometry: " + geometry.getClass().getName());
        }
        return new Cached(output, vertices, edges, wireframeTruncated,
                vertexCandidatesTruncated, edgeCandidatesTruncated);
    }
    private static Vec3 circle(AnalyticSphere sphere,int plane,double angle) {
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
    private static void checkPick(double u,double v,int width,int height){
        if(!Double.isFinite(u)||!Double.isFinite(v)||u<0||u>1||v<0||v>1||width<=0||height<=0)
            throw new IllegalArgumentException("Element pick arguments are invalid");
    }
    private static boolean better(double distance,NodeId node,long first,long second,NodeId bestNode,long bestFirst,long bestSecond,double bestDistance){
        int byDistance=Double.compare(distance,bestDistance);if(byDistance!=0)return byDistance<0;
        if(bestNode==null)return true;int byNode=node.value().compareTo(bestNode.value());if(byNode!=0)return byNode<0;
        int byFirst=Long.compare(first,bestFirst);return byFirst<0||byFirst==0&&Long.compare(second,bestSecond)<0;
    }
}
