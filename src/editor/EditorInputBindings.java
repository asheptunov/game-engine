package editor;

import engine.input.ActionRegistry;
import engine.input.BindingFiles;
import engine.input.KeyBindings;
import engine.input.MouseBindings;
import engine.input.MouseInput;
import platform.awt.input.AwtInputAdapter;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/** One file-backed input router shared by the complete editor surface. */
final class EditorInputBindings implements AutoCloseable {
    private final SceneEditorPanel owner;
    private final KeyBindings keys;
    private final MouseBindings mouse;
    private final KeyEventDispatcher dispatcher = this::dispatchKey;
    private RenderViewPanel mouseTarget;
    private boolean closed;

    EditorInputBindings(SceneEditorPanel owner, EditorController controller) {
        this(owner, controller, Path.of("assets", "bindings", "scene-editor.properties"),
                Path.of("assets", "bindings", "scene-editor-mouse.properties"));
    }

    EditorInputBindings(SceneEditorPanel owner, EditorController controller, Path keyFile, Path mouseFile) {
        this.owner = Objects.requireNonNull(owner);
        var keyActions = new ActionRegistry<Runnable>()
                .register("history.undo", controller::undo)
                .register("history.redo", controller::redo)
                .register("gesture.cancel", owner::cancelActiveGesture);
        keys = BindingFiles.load(keyFile, new KeyBindings(keyActions))
                .validate("scene editor keys");

        var mouseActions = new ActionRegistry<Consumer<MouseInput>>()
                .register("view.orbit", input -> target().orbit(input))
                .register("view.pan", input -> target().pan(input))
                .register("view.zoom", input -> target().zoom(input));
        mouse = BindingFiles.load(mouseFile, new MouseBindings(mouseActions))
                .validate("scene editor mouse");
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher);
    }

    boolean handleMouse(RenderViewPanel target, MouseInput input) {
        if (closed) return false;
        mouseTarget = Objects.requireNonNull(target);
        try { return mouse.handle(input); }
        finally { mouseTarget = null; }
    }

    boolean handleKeyForTest(KeyEvent event) { return dispatchKey(event); }

    private boolean dispatchKey(KeyEvent event) {
        if (closed || !belongsToEditor(event.getComponent())) return false;
        try { return keys.handle(AwtInputAdapter.key(event)); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    private boolean belongsToEditor(Component component) {
        return component == owner || component != null && SwingUtilities.isDescendingFrom(component, owner);
    }

    private RenderViewPanel target() {
        if (mouseTarget == null) throw new IllegalStateException("No editor view owns this mouse input");
        return mouseTarget;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher);
    }
}
