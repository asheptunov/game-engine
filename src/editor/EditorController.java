package editor;

import engine.*;
import math.Vec3;
import engine.objects.Rect;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** EDT-owned authoring state. Background tasks only consume immutable snapshots. */
public final class EditorController implements AutoCloseable {
    public enum Primitive { BOX, SPHERE, PLANE, GROUP, POINT_LIGHT, CAMERA }
    public enum SelectionMode { OBJECT, VERTEX, EDGE, FACE }
    public record VertexSelection(NodeId nodeId, GeometryId geometryId, long vertexId) {
        public VertexSelection {
            Objects.requireNonNull(nodeId);
            Objects.requireNonNull(geometryId);
            if (vertexId < 0) throw new IllegalArgumentException("Vertex ID must be nonnegative");
        }
    }
    public record EdgeSelection(NodeId nodeId, GeometryId geometryId, long firstVertexId, long secondVertexId) {
        public EdgeSelection {
            Objects.requireNonNull(nodeId);
            Objects.requireNonNull(geometryId);
            if (firstVertexId < 0 || firstVertexId >= secondVertexId) {
                throw new IllegalArgumentException("Edge IDs must be nonnegative and canonical");
            }
        }
    }
    public record FaceSelection(NodeId nodeId, GeometryId geometryId, long faceId) {
        public FaceSelection {
            Objects.requireNonNull(nodeId);
            Objects.requireNonNull(geometryId);
            if (faceId < 0) throw new IllegalArgumentException("Face ID must be nonnegative");
        }
    }
    public interface Listener { void changed(State state); }
    public interface Storage {
        SceneSnapshot load(Path path) throws IOException;
        void save(Path path, SceneSnapshot snapshot) throws IOException;
    }
    public record State(SceneSnapshot snapshot, NodeId selection, SelectionMode selectionMode,
                        VertexSelection vertexSelection, EdgeSelection edgeSelection, FaceSelection faceSelection, Path file, boolean dirty,
                        boolean canUndo, boolean canRedo, boolean busy, String status) {}
    private record Gesture(NodeId nodeId, GeometryId geometryId, SelectionMode mode,
                           long firstId, long secondId, PolygonMesh baseline,
                           long displayedRevision, String label) {
        boolean element() {
            return mode == SelectionMode.VERTEX || mode == SelectionMode.EDGE;
        }
    }
    private final SceneDocument document;
    private final UndoHistory history;
    private final Storage storage;
    private final ExecutorService io;
    private final List<Listener> listeners = new ArrayList<>();
    private NodeId selection;
    private SelectionMode selectionMode = SelectionMode.OBJECT;
    private VertexSelection vertexSelection;
    private EdgeSelection edgeSelection;
    private FaceSelection faceSelection;
    private Path file;
    private SceneSnapshot clean;
    private String status = "Ready";
    private boolean busy;
    private boolean loading;
    private Gesture gesture;
    private boolean gestureCandidateValid = true;
    private String gestureValidationError;
    private long fileToken;
    private long selectionIntent;
    private boolean closed;

    public EditorController() {
        this(new Storage() {
            @Override public SceneSnapshot load(Path path) throws IOException { return SceneFiles.load(path); }
            @Override public void save(Path path, SceneSnapshot snapshot) throws IOException { SceneFiles.save(path, snapshot); }
        }, Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r, "scene-editor-files"); thread.setDaemon(true); return thread;
        }));
    }

    public EditorController(Storage storage, ExecutorService io) {
        requireEdt();
        this.storage = Objects.requireNonNull(storage); this.io = Objects.requireNonNull(io);
        document = StarterScene.create(); history = new UndoHistory(document, 100);
        clean = document.snapshot();
        selection = document.snapshot().nodes().stream().filter(n -> n.geometry() != null)
                .map(SceneNode::id).findFirst().orElse(null);
    }

    public void addListener(Listener listener) { requireEdt(); listeners.add(Objects.requireNonNull(listener)); listener.changed(state()); }
    public void removeListener(Listener listener) { requireEdt(); listeners.remove(listener); }
    public State state() {
        requireEdt();
        return new State(document.snapshot(), selection, selectionMode, vertexSelection, edgeSelection,
                faceSelection, file, dirty(), history.canUndo(), history.canRedo(), busy, status);
    }
    public SceneSnapshot snapshot() { return document.snapshot(); }
    public NodeId selection() { requireEdt(); return selection; }
    public SelectionMode selectionMode() { requireEdt(); return selectionMode; }
    public VertexSelection vertexSelection() { requireEdt(); return vertexSelection; }
    public EdgeSelection edgeSelection() { requireEdt(); return edgeSelection; }
    public FaceSelection faceSelection() { requireEdt(); return faceSelection; }
    public boolean dirty() { requireEdt(); return !document.snapshot().sameContent(clean); }

    public boolean select(NodeId id) {
        requireEdt();
        selectionIntent++;
        if (gesture != null) return fail("Finish or cancel the transform first");
        if (id != null && document.snapshot().findNode(id).isEmpty()) return fail("Selection is no longer in the scene");
        selection = id;
        clearElementSelection();
        status = id == null ? "Selection cleared" : "Selected " + document.snapshot().requireNode(id).label();
        publish();
        return true;
    }
    public boolean setSelectionMode(SelectionMode mode) {
        requireEdt();
        Objects.requireNonNull(mode);
        if (gesture != null) return fail("Finish or cancel the transform first");
        if (selectionMode == mode) return true;
        selectionIntent++;
        selectionMode = mode;
        clearElementSelection();
        status = switch (mode) {
            case OBJECT -> "Object selection mode";
            case VERTEX -> "Vertex selection mode · x-ray pick radius 8 px";
            case EDGE -> "Edge selection mode · x-ray pick distance 6 px";
            case FACE -> "Face selection mode · click polygon geometry";
        };
        publish();
        return true;
    }
    public boolean selectVertex(long vertexId) {
        requireEdt();
        selectionIntent++;
        if (gesture != null) return fail("Finish or cancel the transform first");
        if (selectionMode != SelectionMode.VERTEX) return fail("Switch to Vertex selection mode first");
        try {
            var mesh = selectedMesh();
            mesh.requireVertex(vertexId);
            var node = document.snapshot().requireNode(selection);
            vertexSelection = new VertexSelection(node.id(), node.geometry().geometryId(), vertexId);
            edgeSelection = null;
            faceSelection = null;
            status = "Selected vertex " + vertexId + " on " + node.label();
            publish();
            return true;
        } catch (RuntimeException error) {
            return fail(message(error));
        }
    }
    public boolean selectEdge(long firstVertexId, long secondVertexId) {
        requireEdt();
        selectionIntent++;
        if (gesture != null) return fail("Finish or cancel the transform first");
        if (selectionMode != SelectionMode.EDGE) return fail("Switch to Edge selection mode first");
        try {
            long first = Math.min(firstVertexId, secondVertexId);
            long second = Math.max(firstVertexId, secondVertexId);
            var mesh = selectedMesh();
            requireEdge(mesh, first, second);
            var node = document.snapshot().requireNode(selection);
            vertexSelection = null;
            edgeSelection = new EdgeSelection(node.id(), node.geometry().geometryId(), first, second);
            faceSelection = null;
            status = "Selected edge " + first + "-" + second + " on " + node.label();
            publish();
            return true;
        } catch (RuntimeException error) {
            return fail(message(error));
        }
    }
    public boolean selectFace(long faceId){
        requireEdt();
        selectionIntent++;
        if (gesture != null) return fail("Finish or cancel the transform first");
        if (selectionMode != SelectionMode.FACE) return fail("Switch to Face selection mode first");
        if (selection == null) return fail("Select a geometry node first");
        try {
            var node = document.snapshot().requireNode(selection);
            if (node.geometry() == null) return fail("Selected node has no geometry");
            var asset = document.snapshot().requireGeometry(node.geometry().geometryId());
            if (!(asset.geometry() instanceof PolygonMesh mesh)) {
                return fail("Selected geometry has no polygon faces");
            }
            mesh.requireFace(faceId);
            vertexSelection = null;
            edgeSelection = null;
            faceSelection = new FaceSelection(selection, asset.id(), faceId);
            status = "Selected face " + faceId + " on " + node.label();
            publish();
            return true;
        } catch (RuntimeException error) {
            return fail(message(error));
        }
    }
    public boolean acceptPick(RayHit hit, long displayedRevision) {
        requireEdt(); long intent = beginPick(displayedRevision); return intent >= 0 && acceptPick(hit, displayedRevision, intent);
    }
    public long beginPick(long displayedRevision) {
        requireEdt();
        return beginPick(displayedRevision, selectionMode, selection,
                vertexSelection, edgeSelection, faceSelection);
    }
    public long beginPick(long displayedRevision,SelectionMode displayedMode,NodeId displayedSelection,
                          VertexSelection displayedVertex,EdgeSelection displayedEdge,FaceSelection displayedFace) {
        requireEdt();
        if (gesture != null) {
            fail("Finish or cancel the transform first");
            return -1;
        }
        long intent = ++selectionIntent;
        if (!selectionContextMatches(displayedRevision, displayedMode, displayedSelection,
                displayedVertex, displayedEdge, displayedFace)) {
            fail("View changed; click again");
            return -1;
        }
        return intent;
    }
    public boolean acceptPick(RayHit hit, long displayedRevision, long intent) {
        requireEdt();
        if (gesture != null) return fail("Finish or cancel the transform first");
        if (intent != selectionIntent) return false;
        if (displayedRevision != document.snapshot().revision() || hit != null && hit.sceneRevision() != displayedRevision)
            return fail("View changed; click again");
        if (selectionMode == SelectionMode.OBJECT) return select(hit == null ? null : hit.nodeId());
        selectionIntent++;
        if (hit == null) {
            clearElementSelection();
            status = elementName(selectionMode) + " selection cleared";
            publish();
            return true;
        }
        var node = document.snapshot().requireNode(hit.nodeId());
        selection = node.id();
        clearElementSelection();
        if (node.geometry() == null) {
            status = "Selected " + node.label() + "; it has no geometry";
            publish();
            return true;
        }
        var asset = document.snapshot().requireGeometry(node.geometry().geometryId());
        if (!(asset.geometry() instanceof PolygonMesh mesh)) {
            status = "Selected " + node.label() + "; this analytic geometry has no mesh elements";
            publish();
            return true;
        }
        if (selectionMode != SelectionMode.FACE) {
            status = "Selected " + node.label() + "; click near a "
                    + elementName(selectionMode).toLowerCase(Locale.ROOT) + " to select it";
            publish();
            return true;
        }
        try {
            mesh.requireFace(hit.sourceFaceId());
            faceSelection = new FaceSelection(node.id(), asset.id(), hit.sourceFaceId());
            status = "Selected face " + hit.sourceFaceId() + " on " + node.label();
            publish();
            return true;
        } catch (IllegalArgumentException error) {
            return fail("Picked face is no longer editable; click again");
        }
    }
    public boolean acceptVertexPick(NodeId nodeId, GeometryId geometryId, long vertexId,
                                    long displayedRevision, long intent) {
        requireEdt();
        if (gesture != null) return fail("Finish or cancel the transform first");
        if (intent != selectionIntent) return false;
        if (selectionMode != SelectionMode.VERTEX || displayedRevision != document.snapshot().revision()) {
            return fail("View changed; click again");
        }
        try {
            var node = document.snapshot().requireNode(nodeId);
            if (node.geometry() == null || !node.geometry().geometryId().equals(geometryId)) {
                throw new IllegalArgumentException();
            }
            var asset = document.snapshot().requireGeometry(geometryId);
            if (!(asset.geometry() instanceof PolygonMesh mesh)) throw new IllegalArgumentException();
            mesh.requireVertex(vertexId);
            selectionIntent++;
            selection = nodeId;
            vertexSelection = new VertexSelection(nodeId, geometryId, vertexId);
            edgeSelection = null;
            faceSelection = null;
            status = "Selected vertex " + vertexId + " on " + node.label();
            publish();
            return true;
        } catch (RuntimeException error) {
            return fail("Picked vertex is no longer available; click again");
        }
    }
    public boolean acceptEdgePick(NodeId nodeId, GeometryId geometryId,
                                  long firstVertexId, long secondVertexId,
                                  long displayedRevision, long intent) {
        requireEdt();
        if (gesture != null) return fail("Finish or cancel the transform first");
        if (intent != selectionIntent) return false;
        if (selectionMode != SelectionMode.EDGE || displayedRevision != document.snapshot().revision()) {
            return fail("View changed; click again");
        }
        try {
            var node = document.snapshot().requireNode(nodeId);
            if (node.geometry() == null || !node.geometry().geometryId().equals(geometryId)) {
                throw new IllegalArgumentException();
            }
            var asset = document.snapshot().requireGeometry(geometryId);
            if (!(asset.geometry() instanceof PolygonMesh mesh)) throw new IllegalArgumentException();
            long first = Math.min(firstVertexId, secondVertexId);
            long second = Math.max(firstVertexId, secondVertexId);
            requireEdge(mesh, first, second);
            selectionIntent++;
            selection = nodeId;
            vertexSelection = null;
            edgeSelection = new EdgeSelection(nodeId, geometryId, first, second);
            faceSelection = null;
            status = "Selected edge " + first + "-" + second + " on " + node.label();
            publish();
            return true;
        } catch (RuntimeException error) {
            return fail("Picked edge is no longer available; click again");
        }
    }
    public boolean acceptOverlayPick(NodeId id, long displayedRevision) {
        requireEdt();long intent=beginPick(displayedRevision);return intent>=0&&acceptOverlayPick(id,displayedRevision,intent);
    }
    public boolean acceptOverlayPick(NodeId id,long displayedRevision,long intent){
        requireEdt();
        if (gesture != null) return fail("Finish or cancel the transform first");
        if (intent != selectionIntent) return false;
        if (displayedRevision != document.snapshot().revision()) return fail("View changed; click again");
        return select(id);
    }

    public boolean create(Primitive primitive) {
        requireEdt(); var created = new NodeId[1];
        boolean ok = edit("Create " + pretty(primitive), e -> {
            created[0] = e.createNode(pretty(primitive), selection, Transform.IDENTITY);
            switch (primitive) {
                case GROUP -> { }
                case POINT_LIGHT -> e.setPointLight(created[0], new PointLightComponent(new Vec3(1, .9f, .75f), 80));
                case CAMERA -> e.setCamera(created[0], new CameraComponent(StarterScene.canonicalCamera()));
                default -> {
                    var first = document.snapshot().materialAssets().stream().findFirst();
                    var material = first.map(MaterialAsset::id)
                            .orElseGet(() -> e.createMaterial("Default", Material.srgb("default", 0xc8ccd2)));
                    GeometryData geometry = switch (primitive) {
                        case BOX -> BoxGeometry.UNIT;
                        case SPHERE -> new AnalyticSphere(Vec3.ZERO, 1);
                        case PLANE -> PolygonMesh.parallelogram(new Vec3(-1, 0, -1), new Vec3(0, 0, 2), new Vec3(2, 0, 0));
                        default -> throw new IllegalStateException();
                    };
                    var geometryId = e.createGeometry(pretty(primitive) + " geometry", geometry);
                    e.assignGeometry(created[0], geometryId, material);
                }
            }
        });
        if (ok) { selectionIntent++;selection = created[0];clearElementSelection();publish(); }
        return ok;
    }

    public boolean rename(NodeId id, String label) { return edit("Rename node", e -> e.renameNode(id, label)); }
    public boolean applyTransform(NodeId id, Transform transform) { return edit("Apply transform", e -> e.setLocalTransform(id, transform)); }
    public boolean reparent(NodeId id, NodeId parent) { return edit("Reparent (keep local pose)", e -> e.reparentKeepingLocal(id, parent)); }
    public boolean duplicateSelection() {
        requireEdt(); if (selection == null) return fail("Select a node to duplicate");
        var copy = new NodeId[1]; boolean ok = edit("Duplicate subtree", e -> copy[0] = e.duplicateSubtree(selection));
        if (ok) { selectionIntent++;selection = copy[0];clearElementSelection();publish(); } return ok;
    }
    public boolean deleteSelection() {
        requireEdt(); if (selection == null) return fail("Select a node to delete");
        var old = document.snapshot().requireNode(selection); boolean ok = edit("Delete subtree", e -> e.deleteSubtree(selection));
        if (ok) { selectionIntent++;selection = old.parentId();clearElementSelection();publish(); } return ok;
    }
    public boolean assignMaterial(NodeId id, MaterialId material) {
        var node = document.snapshot().requireNode(id); if (node.geometry() == null) return fail("Selected node has no geometry");
        return edit("Assign material", e -> e.assignGeometry(id, node.geometry().geometryId(), material));
    }
    public boolean editSharedMaterial(MaterialId id, Material material) { return edit("Edit shared material", e -> e.replaceMaterial(id, material)); }
    public boolean makeMaterialUnique(NodeId id) { return edit("Make material unique", e -> e.makeMaterialUnique(id)); }
    public boolean makeGeometryUnique(NodeId id){return edit("Make geometry unique",e->e.makeGeometryUnique(id));}
    public boolean applyAnalyticSphere(NodeId id, Vec3 center, float radius) {
        requireEdt();
        Objects.requireNonNull(center, "center");
        var node = document.snapshot().requireNode(id);
        if (node.geometry() == null) return fail("Selected node has no geometry");
        return edit("Apply analytic sphere parameters", edit ->
                edit.setAnalyticSphere(node.geometry().geometryId(), center, radius));
    }

    public boolean approximateAnalyticSphere(NodeId id, int detail) {
        requireEdt();
        var node = document.snapshot().requireNode(id);
        if (node.geometry() == null) return fail("Selected node has no geometry");
        return edit("Approximate analytic sphere at detail " + detail, edit ->
                edit.approximateGeometryAsMesh(node.geometry().geometryId(), detail));
    }
    public boolean extrudeSelectedFace(float distance){
        requireEdt();var selectedFace=faceSelection;if(selectionMode!=SelectionMode.FACE||selectedFace==null)return fail("Select an editable face first");
        return edit("Extrude face "+selectedFace.faceId(),e->e.extrudeFace(selectedFace.geometryId(),selectedFace.faceId(),distance));
    }
    public boolean translateSelectedElement(Vec3 localDelta){
        requireEdt();Objects.requireNonNull(localDelta,"localDelta");
        if(selectionMode==SelectionMode.VERTEX&&vertexSelection!=null){
            var selectedVertex=vertexSelection;
            return edit("Move vertex "+selectedVertex.vertexId(),edit->edit.translateVertex(
                    selectedVertex.geometryId(),selectedVertex.vertexId(),localDelta));
        }
        if(selectionMode==SelectionMode.EDGE&&edgeSelection!=null){
            var selectedEdge=edgeSelection;
            return edit("Move edge "+selectedEdge.firstVertexId()+"-"+selectedEdge.secondVertexId(),edit->edit.translateEdge(
                    selectedEdge.geometryId(),selectedEdge.firstVertexId(),selectedEdge.secondVertexId(),localDelta));
        }
        return fail("Select a vertex or edge first");
    }
    public boolean setPointLight(NodeId id, PointLightComponent light) { return edit(light == null ? "Remove point light" : "Apply point light", e -> e.setPointLight(id, light)); }
    public boolean setCamera(NodeId id, CameraComponent camera) { return edit(camera == null ? "Remove camera" : "Apply camera", e -> e.setCamera(id, camera)); }
    public boolean setCameraFromView(NodeId id, Camera worldCamera) {
        requireEdt(); Objects.requireNonNull(worldCamera);
        var snapshot = document.snapshot(); var node = snapshot.requireNode(id);
        if (node.camera() == null) return fail("Selected node has no camera component");
        var parentWorld = node.parentId() == null ? Transform.IDENTITY : snapshot.worldTransform(node.parentId());
        try {
            float parentScale = parentWorld.uniformScale();
            var worldRight = worldCamera.sensor().edge1().normalized();
            var worldForward = worldCamera.forward();
            var worldUp = worldForward.cross(worldRight).normalized();
            var right = parentWorld.inverseVector(worldRight).normalized();
            var up = parentWorld.inverseVector(worldUp).normalized();
            var forward = parentWorld.inverseVector(worldForward).normalized();
            var localTransform = cameraPose(parentWorld.inversePoint(worldCamera.eye()), right, up, forward);
            var toOrigin = worldCamera.sensor().origin().sub(worldCamera.eye());
            var sensor = new Rect(inBasis(toOrigin, worldRight, worldUp, worldForward, parentScale),
                    inBasis(worldCamera.sensor().edge1(), worldRight, worldUp, worldForward, parentScale),
                    inBasis(worldCamera.sensor().edge2(), worldRight, worldUp, worldForward, parentScale));
            var localCamera = new Camera(Vec3.ZERO, sensor, worldCamera.projection(), worldCamera.mode(),
                    worldCamera.focus() / parentScale, worldCamera.aperture() / parentScale,
                    worldCamera.height() / parentScale, worldCamera.rememberedAperture() / parentScale).validated();
            return edit("Set camera from view", e -> { e.setLocalTransform(id, localTransform); e.setCamera(id, new CameraComponent(localCamera)); });
        } catch (RuntimeException error) { return fail(message(error)); }
    }

    private static Vec3 inBasis(Vec3 value, Vec3 right, Vec3 up, Vec3 forward, float scale) {
        return new Vec3(value.dot(right) / scale, value.dot(up) / scale, value.dot(forward) / scale);
    }

    private static Transform cameraPose(Vec3 position, Vec3 right, Vec3 up, Vec3 forward) {
        float ru = Math.abs(right.dot(up)), rf = Math.abs(right.dot(forward)), uf = Math.abs(up.dot(forward));
        if (ru > Transform.COMPOSITION_TOLERANCE || rf > Transform.COMPOSITION_TOLERANCE || uf > Transform.COMPOSITION_TOLERANCE)
            throw new IllegalArgumentException("View camera axes are not orthogonal");
        double y = Math.asin(Math.clamp(-right.z(), -1, 1)), cy = Math.cos(y), x, z;
        if (Math.abs(cy) > 1e-7) { x = Math.atan2(up.z(), forward.z()); z = Math.atan2(right.y(), right.x()); }
        else { z = 0; x = y > 0 ? Math.atan2(up.x(), up.y()) : Math.atan2(-up.x(), up.y()); }
        return new Transform(position, new Vec3((float) Math.toDegrees(x), (float) Math.toDegrees(y), (float) Math.toDegrees(z)), new Vec3(1, 1, 1));
    }

    public boolean beginTransformGesture(NodeId id, String label) {
        requireEdt();
        return beginTransformGesture(id, label, document.snapshot().revision());
    }
    public boolean beginTransformGesture(NodeId id, String label, long displayedRevision) {
        requireEdt();
        if(selectionMode!=SelectionMode.OBJECT)return fail("Object handles are available only in Object selection mode");
        if (loading || busy) return fail("Wait for file work to finish");
        if (gesture != null) return fail("A transform gesture is already active");
        if (displayedRevision != document.snapshot().revision()) return fail("View changed; click again");
        if (!Objects.equals(selection, id)) return fail("Selection changed; drag the current selection");
        try {
            document.snapshot().requireNode(id);history.beginGroup(label);selectionIntent++;
            gesture=new Gesture(id,null,SelectionMode.OBJECT,-1,-1,null,displayedRevision,label);
            gestureCandidateValid=true;gestureValidationError=null;status=label;publish();return true;
        }
        catch (RuntimeException error) { return fail(message(error)); }
    }
    public boolean updateTransformGesture(NodeId id, Transform transform) {
        requireEdt();
        if (gesture == null || gesture.element() || !Objects.equals(gesture.nodeId(), id)) {
            return fail("Transform gesture no longer matches the selection");
        }
        try {
            history.updateGroup(edit -> edit.setLocalTransform(id, transform));
            gestureCandidateValid = true;
            gestureValidationError = null;
            status = "Adjusting transform";
            publish();
            return true;
        } catch (RuntimeException error) {
            gestureCandidateValid = false;
            gestureValidationError = message(error);
            return fail(gestureValidationError);
        }
    }
    public boolean beginElementGesture(long displayedRevision) {
        return beginElementGesture(displayedRevision,selectionMode,selection,vertexSelection,edgeSelection);
    }
    public boolean beginElementGesture(long displayedRevision,SelectionMode displayedMode,NodeId displayedSelection,
                                       VertexSelection displayedVertex,EdgeSelection displayedEdge) {
        requireEdt();
        if (loading || busy) return fail("Wait for file work to finish");
        if (gesture != null) return fail("A transform gesture is already active");
        if (!selectionContextMatches(displayedRevision, displayedMode, displayedSelection,
                displayedVertex, displayedEdge, faceSelection)) {
            return fail("View changed; click again");
        }
        try {
            var mesh = selectedMesh();
            var node = document.snapshot().requireNode(selection);
            long first;
            long second;
            if (selectionMode == SelectionMode.VERTEX && vertexSelection != null) {
                if (!vertexSelection.nodeId().equals(node.id())
                        || !vertexSelection.geometryId().equals(node.geometry().geometryId())) {
                    throw new IllegalArgumentException("Vertex selection changed");
                }
                first = vertexSelection.vertexId();
                second = -1;
                mesh.requireVertex(first);
            } else if (selectionMode == SelectionMode.EDGE && edgeSelection != null) {
                if (!edgeSelection.nodeId().equals(node.id())
                        || !edgeSelection.geometryId().equals(node.geometry().geometryId())) {
                    throw new IllegalArgumentException("Edge selection changed");
                }
                first = edgeSelection.firstVertexId();
                second = edgeSelection.secondVertexId();
                requireEdge(mesh, first, second);
            } else return fail("Select a vertex or edge first");
            String label = selectionMode == SelectionMode.VERTEX
                    ? "Move vertex " + first : "Move edge " + first + "-" + second;
            history.beginGroup(label);
            selectionIntent++;
            gesture = new Gesture(node.id(), node.geometry().geometryId(), selectionMode,
                    first, second, mesh, displayedRevision, label);
            gestureCandidateValid = true;
            gestureValidationError = null;
            status = label;
            publish();
            return true;
        } catch (RuntimeException error) {
            return fail(message(error));
        }
    }
    public boolean updateElementGesture(Vec3 localDelta) {
        requireEdt();
        Objects.requireNonNull(localDelta, "localDelta");
        if (gesture == null || !gesture.element()) return fail("Element gesture is no longer active");
        try {
            PolygonMesh candidate = gesture.mode() == SelectionMode.VERTEX
                    ? gesture.baseline().translateVertex(gesture.firstId(), localDelta)
                    : gesture.baseline().translateEdge(gesture.firstId(), gesture.secondId(), localDelta);
            history.updateGroup(edit -> edit.replaceGeometry(gesture.geometryId(), candidate));
            gestureCandidateValid = true;
            gestureValidationError = null;
            status = "Adjusting " + elementName(gesture.mode()).toLowerCase(Locale.ROOT);
            publish();
            return true;
        } catch (RuntimeException error) {
            gestureCandidateValid = false;
            gestureValidationError = message(error);
            return fail(gestureValidationError);
        }
    }
    public boolean commitTransformGesture() {
        requireEdt();
        if (gesture == null) return false;
        if (!gestureCandidateValid) {
            String error = gestureValidationError;
            try {
                history.cancelGroup();
                gesture = null;
                gestureValidationError = null;
                reconcileSelection();
                status = "Cancelled invalid edit: " + error;
                publish();
                return true;
            } catch (RuntimeException failure) {
                return fail(message(failure));
            }
        }
        try { history.commitGroup(); gesture = null; gestureValidationError = null; status = "Applied transform"; publish(); return true; }
        catch (RuntimeException error) { return fail(message(error)); }
    }
    public boolean cancelTransformGesture() {
        requireEdt(); if (gesture == null) return false;
        try { history.cancelGroup(); gesture = null;gestureValidationError=null;reconcileSelection();status = "Cancelled transform"; publish(); return true; }
        catch (RuntimeException error) { return fail(message(error)); }
    }
    public boolean transformGestureActive() { requireEdt(); return gesture != null; }

    public boolean undo() { requireEdt(); return loading ? fail("Wait for the scene load to finish") : gesture != null ? fail("Finish or cancel the transform first") : !history.canUndo() ? fail("Nothing to undo") : fromHistory("Undid edit", history::undo); }
    public boolean redo() { requireEdt(); return loading ? fail("Wait for the scene load to finish") : gesture != null ? fail("Finish or cancel the transform first") : !history.canRedo() ? fail("Nothing to redo") : fromHistory("Redid edit", history::redo); }
    private boolean fromHistory(String text, Callable<SceneSnapshot> operation) {
        try { operation.call(); reconcileSelection(); status = text; publish(); return true; }
        catch (Exception error) { return fail(message(error)); }
    }

    public void newScene() {
        requireEdt(); if (busy) { fail("Wait for file work to finish"); return; } if (gesture != null) { fail("Finish or cancel the transform first"); return; }
        var fresh = StarterScene.create().snapshot(); history.replace("New scene", fresh); history.clear();
        file = null; fileToken++; clean = document.snapshot();
        selectionIntent++;selection = document.snapshot().nodes().stream().filter(n -> n.geometry() != null).map(SceneNode::id).findFirst().orElse(null);clearElementSelection();
        status = "Created starter scene"; publish();
    }

    public CompletableFuture<Boolean> load(Path path) {
        requireEdt(); if (busy || gesture != null) return CompletableFuture.completedFuture(false);
        busy = loading = true; status = "Loading " + path.getFileName() + "…"; publish(); long token = ++fileToken;
        var result = new CompletableFuture<Boolean>();
        io.submit(() -> {
            try {
                var loaded = storage.load(path);
                SwingUtilities.invokeLater(() -> {
                    if (closed || token != fileToken) { result.complete(false); return; }
                    try {
                        history.replace("Load scene", loaded); history.clear(); file = path.toAbsolutePath(); clean = document.snapshot();
                        selectionIntent++;selection = document.snapshot().nodes().stream().findFirst().map(SceneNode::id).orElse(null);clearElementSelection();
                        busy = loading = false; status = "Loaded " + path.getFileName(); publish(); result.complete(true);
                    } catch (RuntimeException error) { busy = loading = false; fail(message(error)); result.complete(false); }
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> { if (token == fileToken) { busy = loading = false; fail("Load failed: " + message(error)); } result.complete(false); });
            }
        });
        return result;
    }

    public CompletableFuture<Boolean> save(Path path) {
        requireEdt(); if (busy || gesture != null) return CompletableFuture.completedFuture(false);
        var captured = document.snapshot(); var absolute = path.toAbsolutePath();
        long token = ++fileToken; busy = true; status = "Saving " + path.getFileName() + "…"; publish();
        var result = new CompletableFuture<Boolean>();
        io.submit(() -> {
            try {
                storage.save(absolute, captured);
                SwingUtilities.invokeLater(() -> {
                    if (!closed && token == fileToken) {
                        file = absolute; clean = captured;
                        busy = false; status = dirty() ? "Saved; newer edits remain unsaved" : "Saved " + path.getFileName(); publish();
                    }
                    result.complete(true);
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> { if (token == fileToken) { busy = false; fail("Save failed: " + message(error)); } result.complete(false); });
            }
        });
        return result;
    }

    private boolean edit(String label, Consumer<SceneEdit> operation) {
        requireEdt(); if (loading) return fail("Wait for the scene load to finish");
        try { var before = document.snapshot(); var after = history.edit(label, operation); reconcileSelection();status = after == before ? label + " made no change" : label; publish(); return true; }
        catch (RuntimeException error) { return fail(message(error)); }
    }
    private boolean fail(String value) { status = value; publish(); return false; }
    private void reconcileSelection(){
        var snapshot=document.snapshot();
        if(selection!=null&&snapshot.findNode(selection).isEmpty()){selection=null;clearElementSelection();return;}
        if(vertexSelection==null&&edgeSelection==null&&faceSelection==null)return;
        try{
            var node=snapshot.requireNode(selection);if(node.geometry()==null)throw new IllegalArgumentException();
            var asset=snapshot.requireGeometry(node.geometry().geometryId());if(!(asset.geometry() instanceof PolygonMesh mesh))throw new IllegalArgumentException();
            if(vertexSelection!=null){mesh.requireVertex(vertexSelection.vertexId());vertexSelection=new VertexSelection(node.id(),asset.id(),vertexSelection.vertexId());}
            if(edgeSelection!=null){requireEdge(mesh,edgeSelection.firstVertexId(),edgeSelection.secondVertexId());edgeSelection=new EdgeSelection(node.id(),asset.id(),edgeSelection.firstVertexId(),edgeSelection.secondVertexId());}
            if(faceSelection!=null){mesh.requireFace(faceSelection.faceId());faceSelection=new FaceSelection(node.id(),asset.id(),faceSelection.faceId());}
        }catch(RuntimeException ignored){clearElementSelection();}
    }
    private boolean selectionContextMatches(long displayedRevision, SelectionMode displayedMode,
                                            NodeId displayedSelection, VertexSelection displayedVertex,
                                            EdgeSelection displayedEdge, FaceSelection displayedFace) {
        return displayedRevision == document.snapshot().revision()
                && displayedMode == selectionMode
                && Objects.equals(displayedSelection, selection)
                && Objects.equals(displayedVertex, vertexSelection)
                && Objects.equals(displayedEdge, edgeSelection)
                && Objects.equals(displayedFace, faceSelection);
    }
    private PolygonMesh selectedMesh(){
        if(selection==null)throw new IllegalArgumentException("Select a geometry node first");
        var node=document.snapshot().requireNode(selection);
        if(node.geometry()==null)throw new IllegalArgumentException("Selected node has no geometry");
        var geometry=document.snapshot().requireGeometry(node.geometry().geometryId()).geometry();
        if(!(geometry instanceof PolygonMesh mesh))throw new IllegalArgumentException("Selected geometry has no polygon elements");
        return mesh;
    }
    private static void requireEdge(PolygonMesh mesh,long first,long second){
        if(first<0||first>=second||mesh.edges().stream().noneMatch(edge->edge.firstVertexId()==first&&edge.secondVertexId()==second))
            throw new IllegalArgumentException("Unknown editable edge: "+first+"-"+second);
    }
    private void clearElementSelection(){vertexSelection=null;edgeSelection=null;faceSelection=null;}
    private static String elementName(SelectionMode mode){return switch(mode){case OBJECT->"Object";case VERTEX->"Vertex";case EDGE->"Edge";case FACE->"Face";};}
    private void publish() { var value = state(); for (var listener : List.copyOf(listeners)) listener.changed(value); }
    private static String pretty(Primitive value) { var s = value.name().toLowerCase(Locale.ROOT).replace('_', ' '); return Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    private static String message(Throwable error) { return error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage(); }
    private static void requireEdt() { if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("EditorController belongs to the Swing event thread"); }
    @Override public void close() { requireEdt(); if (!closed) { if (gesture != null) { cancelTransformGesture(); if (gesture != null) return; } closed = true; fileToken++; listeners.clear(); io.shutdownNow(); } }
}
