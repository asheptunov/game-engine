package editor;

import engine.input.*;

import platform.awt.input.AwtInputAdapter;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.*;

/** Live, file-backed input router shared by the complete editor surface. */
final class EditorInputBindings implements AutoCloseable {
    private record Compiled(
            KeyBindings keys, MouseBindings mouse, List<EditorBindingProfile.Entry> entries) {}

    private final SceneEditorPanel owner;
    private final EditorController controller;
    private final EditorBindingProfile profile;
    private final List<Runnable> listeners = new ArrayList<>();
    private final EnumSet<KeyCode> heldKeys = EnumSet.noneOf(KeyCode.class);
    private final KeyEventDispatcher dispatcher = this::dispatchKey;
    private KeyBindings keys;
    private MouseBindings mouse;
    private List<EditorBindingProfile.Entry> defaults, effective;
    private RenderViewPanel mouseTarget;
    private String startupError;
    private boolean dialogActive, closed;

    EditorInputBindings(SceneEditorPanel owner, EditorController controller) {
        this(
                owner,
                controller,
                Path.of("assets", "bindings", "scene-editor.properties"),
                Path.of("assets", "bindings", "scene-editor-mouse.properties"),
                Path.of("config", "scene-editor-bindings.properties"));
    }

    EditorInputBindings(
            SceneEditorPanel owner, EditorController controller, Path keyFile, Path mouseFile) {
        this(
                owner,
                controller,
                keyFile,
                mouseFile,
                keyFile.toAbsolutePath()
                        .resolveSibling("scene-editor-bindings.override.properties"));
    }

    EditorInputBindings(
            SceneEditorPanel owner,
            EditorController controller,
            Path keyFile,
            Path mouseFile,
            Path userFile) {
        this.owner = Objects.requireNonNull(owner);
        this.controller = Objects.requireNonNull(controller);
        profile = new EditorBindingProfile(keyFile, mouseFile, userFile);
        var loaded = profile.load();
        defaults = loaded.defaults();
        install(compile(loaded.effective()));
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher);
        startupError = loaded.error();
        if (startupError != null) owner.bindingStatus(startupError);
    }

    boolean handleMouse(RenderViewPanel target, MouseInput input) {
        if (closed || dialogActive) return false;
        mouseTarget = Objects.requireNonNull(target);
        try {
            return mouse.handle(input);
        } finally {
            mouseTarget = null;
        }
    }

    MouseInput mouseInput(MouseGesture gesture, MouseEvent event) {
        return AwtInputAdapter.mouse(gesture, event, "viewport").withHeldKeys(Set.copyOf(heldKeys));
    }

    void addChangeListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    List<EditorBindingProfile.Entry> entries() {
        return effective;
    }

    List<EditorBindingProfile.Entry> defaults() {
        return defaults;
    }

    String navigationHelp() {
        var orbit = gestures("view.orbit");
        var pan = gestures("view.pan");
        var zoom = gestures("view.zoom");
        var parts = new ArrayList<String>();
        if (!orbit.isEmpty()) parts.add(join(orbit) + " orbit");
        if (!pan.isEmpty()) parts.add(join(pan) + " pan");
        if (!zoom.isEmpty()) parts.add(join(zoom) + " zoom");
        return parts.isEmpty() ? "No navigation gestures bound" : String.join(" · ", parts);
    }

    void apply(List<EditorBindingProfile.Entry> entries) {
        install(compile(entries));
    }

    void saveAndApply(List<EditorBindingProfile.Entry> entries) {
        var compiled = compile(entries);
        profile.save(compiled.entries());
        install(compiled);
        startupError = null;
        owner.bindingStatus(null);
    }

    void dialogActive(boolean value) {
        dialogActive = value;
        clearTransient();
    }

    void clearTransient() {
        heldKeys.clear();
    }

    String startupError() {
        return startupError;
    }

    boolean handleKeyForTest(KeyEvent event) {
        return dispatchKey(event);
    }

    private Compiled compile(Collection<EditorBindingProfile.Entry> requested) {
        var normalized = profile.normalize(requested);
        var keyActions =
                new ActionRegistry<Runnable>()
                        .register("history.undo", controller::undo)
                        .register("history.redo", controller::redo)
                        .register("gesture.cancel", owner::cancelActiveGesture);
        var mouseActions =
                new ActionRegistry<Consumer<MouseInput>>()
                        .register("view.orbit", input -> target().orbit(input))
                        .register("view.pan", input -> target().pan(input))
                        .register("view.zoom", input -> target().zoom(input));
        var keyBindings = new KeyBindings(keyActions);
        var mouseBindings = new MouseBindings(mouseActions);
        for (var entry : normalized)
            if (entry.kind() == EditorBindingProfile.Kind.KEY)
                keyBindings.bindParsed(entry.chord(), entry.action());
            else mouseBindings.bindParsed(entry.chord(), entry.action());
        keyBindings.validate("scene editor keys");
        mouseBindings.validate("scene editor mouse");
        return new Compiled(keyBindings, mouseBindings, List.copyOf(normalized));
    }

    private void install(Compiled compiled) {
        keys = compiled.keys();
        mouse = compiled.mouse();
        effective = compiled.entries();
        clearTransient();
        listeners.forEach(Runnable::run);
    }

    private boolean dispatchKey(KeyEvent event) {
        if (closed || !belongsToEditor(event.getComponent())) return false;
        KeyInput input;
        try {
            input = AwtInputAdapter.key(event);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        if (!input.pressed()) heldKeys.remove(input.key());
        if (dialogActive) return false;
        if (input.pressed()
                && event.getComponent() instanceof RenderViewPanel
                && !modifier(input.key())
                && input.key() != KeyCode.UNKNOWN) heldKeys.add(input.key());
        else if (input.pressed() && !(event.getComponent() instanceof RenderViewPanel))
            heldKeys.remove(input.key());
        return keys.handle(input);
    }

    private boolean belongsToEditor(Component component) {
        return component == owner
                || component != null && SwingUtilities.isDescendingFrom(component, owner);
    }

    private RenderViewPanel target() {
        if (mouseTarget == null)
            throw new IllegalStateException("No editor view owns this mouse input");
        return mouseTarget;
    }

    private List<String> gestures(String action) {
        return effective.stream()
                .filter(
                        e ->
                                e.kind() == EditorBindingProfile.Kind.MOUSE
                                        && e.action().equals(action))
                .map(e -> friendly(MouseChord.parse(e.chord())))
                .toList();
    }

    private static String join(List<String> values) {
        return String.join(" or ", values);
    }

    private static String friendly(MouseChord chord) {
        var prefix = new ArrayList<String>();
        chord.heldKeys().stream().sorted().forEach(k -> prefix.add(title(k.token())));
        if (chord.modifiers().ctrl()) prefix.add("Ctrl");
        if (chord.modifiers().alt()) prefix.add("Alt");
        if (chord.modifiers().shift()) prefix.add("Shift");
        if (chord.modifiers().meta()) prefix.add("Meta");
        String gesture =
                chord.gesture() == MouseGesture.WHEEL
                        ? "Wheel"
                        : title(chord.button().name()) + "-click and drag";
        prefix.add(gesture);
        return String.join("+", prefix);
    }

    private static String title(String value) {
        var lower = value.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static boolean modifier(KeyCode key) {
        return switch (key) {
            case L_SHIFT, R_SHIFT, L_CTRL, R_CTRL, L_ALT, R_ALT, L_META, R_META, L_WIN, R_WIN ->
                    true;
            default -> false;
        };
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        clearTransient();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher);
    }
}
