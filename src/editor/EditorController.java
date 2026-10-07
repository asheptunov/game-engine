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
    public enum SelectionMode { OBJECT, FACE }
    public record FaceSelection(NodeId nodeId,GeometryId geometryId,long faceId) {
        public FaceSelection { Objects.requireNonNull(nodeId);Objects.requireNonNull(geometryId);if(faceId<0)throw new IllegalArgumentException("Face ID must be nonnegative"); }
    }
    public interface Listener { void changed(State state); }
    public interface Storage {
        SceneSnapshot load(Path path) throws IOException;
        void save(Path path, SceneSnapshot snapshot) throws IOException;
    }
    public record State(SceneSnapshot snapshot, NodeId selection, SelectionMode selectionMode, FaceSelection faceSelection, Path file, boolean dirty,
                        boolean canUndo, boolean canRedo, boolean busy, String status) {}
    private final SceneDocument document;
    private final UndoHistory history;
    private final Storage storage;
    private final ExecutorService io;
    private final List<Listener> listeners = new ArrayList<>();
    private NodeId selection;
    private SelectionMode selectionMode=SelectionMode.OBJECT;
    private FaceSelection faceSelection;
    private Path file;
    private SceneSnapshot clean;
    private String status = "Ready";
    private boolean busy;
    private boolean loading;
    private NodeId transformGesture;
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
    public State state() { requireEdt(); return new State(document.snapshot(), selection, selectionMode, faceSelection, file, dirty(), history.canUndo(), history.canRedo(), busy, status); }
    public SceneSnapshot snapshot() { return document.snapshot(); }
    public NodeId selection() { requireEdt(); return selection; }
    public SelectionMode selectionMode(){requireEdt();return selectionMode;}
    public FaceSelection faceSelection(){requireEdt();return faceSelection;}
    public boolean dirty() { requireEdt(); return !document.snapshot().sameContent(clean); }

    public boolean select(NodeId id) {
        requireEdt();
        selectionIntent++;
        if (transformGesture != null) return fail("Finish or cancel the transform first");
        if (id != null && document.snapshot().findNode(id).isEmpty()) return fail("Selection is no longer in the scene");
        selection = id; faceSelection=null; status = id == null ? "Selection cleared" : "Selected " + document.snapshot().requireNode(id).label(); publish(); return true;
    }
    public boolean setSelectionMode(SelectionMode mode){
        requireEdt();Objects.requireNonNull(mode);
        if(transformGesture!=null)return fail("Finish or cancel the transform first");
        if(selectionMode==mode)return true;
        selectionIntent++;
        selectionMode=mode;faceSelection=null;status=mode==SelectionMode.FACE?"Face selection mode · click editable geometry":"Object selection mode";publish();return true;
    }
    public boolean selectFace(long faceId){
        requireEdt();selectionIntent++;
        if(selectionMode!=SelectionMode.FACE)return fail("Switch to Face selection mode first");
        if(selection==null)return fail("Select a geometry node first");
        try{
            var node=document.snapshot().requireNode(selection);if(node.geometry()==null)return fail("Selected node has no geometry");
            var asset=document.snapshot().requireGeometry(node.geometry().geometryId());if(!(asset.geometry() instanceof EditableMeshGeometry mesh))return fail("Convert selected geometry to an editable mesh first");
            mesh.requireFace(faceId);faceSelection=new FaceSelection(selection,asset.id(),faceId);status="Selected face "+faceId+" on "+node.label();publish();return true;
        }catch(RuntimeException error){return fail(message(error));}
    }
    public boolean acceptPick(RayHit hit, long displayedRevision) {
        requireEdt(); long intent = beginPick(displayedRevision); return intent >= 0 && acceptPick(hit, displayedRevision, intent);
    }
    public long beginPick(long displayedRevision) {
        requireEdt(); long intent = ++selectionIntent;
        if (displayedRevision != document.snapshot().revision()) { fail("View changed; click again"); return -1; }
        return intent;
    }
    public boolean acceptPick(RayHit hit, long displayedRevision, long intent) {
        requireEdt();
        if (intent != selectionIntent) return false;
        if (displayedRevision != document.snapshot().revision() || hit != null && hit.sceneRevision() != displayedRevision)
            return fail("View changed; click again");
        if(selectionMode==SelectionMode.OBJECT)return select(hit == null ? null : hit.nodeId());
        selectionIntent++;
        if(hit==null){faceSelection=null;status="Face selection cleared";publish();return true;}
        var node=document.snapshot().requireNode(hit.nodeId());selection=node.id();faceSelection=null;
        if(node.geometry()==null){status="Selected "+node.label()+"; it has no geometry";publish();return true;}
        var asset=document.snapshot().requireGeometry(node.geometry().geometryId());
        if(!(asset.geometry() instanceof EditableMeshGeometry mesh)){status="Selected "+node.label()+"; use Convert to editable mesh in the Mesh inspector";publish();return true;}
        try{mesh.requireFace(hit.sourceFaceId());faceSelection=new FaceSelection(node.id(),asset.id(),hit.sourceFaceId());status="Selected face "+hit.sourceFaceId()+" on "+node.label();publish();return true;}
        catch(IllegalArgumentException error){return fail("Picked face is no longer editable; click again");}
    }
    public boolean acceptOverlayPick(NodeId id, long displayedRevision) {
        requireEdt(); if (displayedRevision != document.snapshot().revision()) return fail("View changed; click again"); return select(id);
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
                        case SPHERE -> new SphereGeometry(Vec3.ZERO, 1);
                        case PLANE -> new RectGeometry(new Vec3(-1, 0, -1), new Vec3(0, 0, 2), new Vec3(2, 0, 0));
                        default -> throw new IllegalStateException();
                    };
                    var geometryId = e.createGeometry(pretty(primitive) + " geometry", geometry);
                    e.assignGeometry(created[0], geometryId, material);
                }
            }
        });
        if (ok) { selectionIntent++;selection = created[0];faceSelection=null;publish(); }
        return ok;
    }

    public boolean rename(NodeId id, String label) { return edit("Rename node", e -> e.renameNode(id, label)); }
    public boolean applyTransform(NodeId id, Transform transform) { return edit("Apply transform", e -> e.setLocalTransform(id, transform)); }
    public boolean reparent(NodeId id, NodeId parent) { return edit("Reparent (keep local pose)", e -> e.reparentKeepingLocal(id, parent)); }
    public boolean duplicateSelection() {
        requireEdt(); if (selection == null) return fail("Select a node to duplicate");
        var copy = new NodeId[1]; boolean ok = edit("Duplicate subtree", e -> copy[0] = e.duplicateSubtree(selection));
        if (ok) { selectionIntent++;selection = copy[0];faceSelection=null;publish(); } return ok;
    }
    public boolean deleteSelection() {
        requireEdt(); if (selection == null) return fail("Select a node to delete");
        var old = document.snapshot().requireNode(selection); boolean ok = edit("Delete subtree", e -> e.deleteSubtree(selection));
        if (ok) { selectionIntent++;selection = old.parentId();faceSelection=null;publish(); } return ok;
    }
    public boolean assignMaterial(NodeId id, MaterialId material) {
        var node = document.snapshot().requireNode(id); if (node.geometry() == null) return fail("Selected node has no geometry");
        return edit("Assign material", e -> e.assignGeometry(id, node.geometry().geometryId(), material));
    }
    public boolean editSharedMaterial(MaterialId id, Material material) { return edit("Edit shared material", e -> e.replaceMaterial(id, material)); }
    public boolean makeMaterialUnique(NodeId id) { return edit("Make material unique", e -> e.makeMaterialUnique(id)); }
    public boolean makeGeometryUnique(NodeId id){return edit("Make geometry unique",e->e.makeGeometryUnique(id));}
    public boolean convertGeometryToEditable(NodeId id){
        requireEdt();var node=document.snapshot().requireNode(id);if(node.geometry()==null)return fail("Selected node has no geometry");
        return edit("Convert to editable mesh",e->e.convertGeometryToEditable(node.geometry().geometryId()));
    }
    public boolean extrudeSelectedFace(float distance){
        requireEdt();var selectedFace=faceSelection;if(selectionMode!=SelectionMode.FACE||selectedFace==null)return fail("Select an editable face first");
        return edit("Extrude face "+selectedFace.faceId(),e->e.extrudeFace(selectedFace.geometryId(),selectedFace.faceId(),distance));
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
        if(selectionMode!=SelectionMode.OBJECT)return fail("Object handles are unavailable in Face selection mode");
        if (loading || busy) return fail("Wait for file work to finish");
        if (transformGesture != null) return fail("A transform gesture is already active");
        if (displayedRevision != document.snapshot().revision()) return fail("View changed; click again");
        if (!Objects.equals(selection, id)) return fail("Selection changed; drag the current selection");
        try { document.snapshot().requireNode(id); history.beginGroup(label); transformGesture = id; status = label; publish(); return true; }
        catch (RuntimeException error) { return fail(message(error)); }
    }
    public boolean updateTransformGesture(NodeId id, Transform transform) {
        requireEdt(); if (!Objects.equals(transformGesture, id)) return fail("Transform gesture no longer matches the selection");
        try { history.updateGroup(e -> e.setLocalTransform(id, transform)); status = "Adjusting transform"; publish(); return true; }
        catch (RuntimeException error) { return fail(message(error)); }
    }
    public boolean commitTransformGesture() {
        requireEdt(); if (transformGesture == null) return false;
        try { history.commitGroup(); transformGesture = null; status = "Applied transform"; publish(); return true; }
        catch (RuntimeException error) { return fail(message(error)); }
    }
    public boolean cancelTransformGesture() {
        requireEdt(); if (transformGesture == null) return false;
        try { history.cancelGroup(); transformGesture = null; status = "Cancelled transform"; publish(); return true; }
        catch (RuntimeException error) { return fail(message(error)); }
    }
    public boolean transformGestureActive() { requireEdt(); return transformGesture != null; }

    public boolean undo() { requireEdt(); return loading ? fail("Wait for the scene load to finish") : transformGesture != null ? fail("Finish or cancel the transform first") : !history.canUndo() ? fail("Nothing to undo") : fromHistory("Undid edit", history::undo); }
    public boolean redo() { requireEdt(); return loading ? fail("Wait for the scene load to finish") : transformGesture != null ? fail("Finish or cancel the transform first") : !history.canRedo() ? fail("Nothing to redo") : fromHistory("Redid edit", history::redo); }
    private boolean fromHistory(String text, Callable<SceneSnapshot> operation) {
        try { operation.call(); reconcileSelection(); status = text; publish(); return true; }
        catch (Exception error) { return fail(message(error)); }
    }

    public void newScene() {
        requireEdt(); if (busy) { fail("Wait for file work to finish"); return; } if (transformGesture != null) { fail("Finish or cancel the transform first"); return; }
        var fresh = StarterScene.create().snapshot(); history.replace("New scene", fresh); history.clear();
        file = null; fileToken++; clean = document.snapshot();
        selectionIntent++;selection = document.snapshot().nodes().stream().filter(n -> n.geometry() != null).map(SceneNode::id).findFirst().orElse(null);faceSelection=null;
        status = "Created starter scene"; publish();
    }

    public CompletableFuture<Boolean> load(Path path) {
        requireEdt(); if (busy || transformGesture != null) return CompletableFuture.completedFuture(false);
        busy = loading = true; status = "Loading " + path.getFileName() + "…"; publish(); long token = ++fileToken;
        var result = new CompletableFuture<Boolean>();
        io.submit(() -> {
            try {
                var loaded = storage.load(path);
                SwingUtilities.invokeLater(() -> {
                    if (closed || token != fileToken) { result.complete(false); return; }
                    try {
                        history.replace("Load scene", loaded); history.clear(); file = path.toAbsolutePath(); clean = document.snapshot();
                        selectionIntent++;selection = document.snapshot().nodes().stream().findFirst().map(SceneNode::id).orElse(null);faceSelection=null;
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
        requireEdt(); if (busy || transformGesture != null) return CompletableFuture.completedFuture(false);
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
        if(selection!=null&&snapshot.findNode(selection).isEmpty()){selection=null;faceSelection=null;return;}
        if(faceSelection==null)return;
        try{
            var node=snapshot.requireNode(faceSelection.nodeId());if(!Objects.equals(selection,node.id())||node.geometry()==null)throw new IllegalArgumentException();
            var asset=snapshot.requireGeometry(node.geometry().geometryId());if(!(asset.geometry() instanceof EditableMeshGeometry mesh))throw new IllegalArgumentException();
            mesh.requireFace(faceSelection.faceId());faceSelection=new FaceSelection(node.id(),asset.id(),faceSelection.faceId());
        }catch(RuntimeException ignored){faceSelection=null;}
    }
    private void publish() { var value = state(); for (var listener : List.copyOf(listeners)) listener.changed(value); }
    private static String pretty(Primitive value) { var s = value.name().toLowerCase(Locale.ROOT).replace('_', ' '); return Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    private static String message(Throwable error) { return error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage(); }
    private static void requireEdt() { if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("EditorController belongs to the Swing event thread"); }
    @Override public void close() { requireEdt(); if (!closed) { if (transformGesture != null) { cancelTransformGesture(); if (transformGesture != null) return; } closed = true; fileToken++; listeners.clear(); io.shutdownNow(); } }
}
