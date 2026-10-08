package editor;

import engine.input.*;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Validated editor binding rows plus one atomic user-override file. */
final class EditorBindingProfile {
    enum Kind {
        KEY,
        MOUSE
    }

    record Entry(Kind kind, String chord, String action) {
        Entry {
            Objects.requireNonNull(kind);
            chord = Objects.requireNonNull(chord).trim();
            action = Objects.requireNonNull(action).trim();
        }
    }

    record Loaded(List<Entry> defaults, List<Entry> effective, String error) {}

    static final Map<String, String> KEY_ACTIONS =
            Map.of(
                    "history.undo",
                    "Undo",
                    "history.redo",
                    "Redo",
                    "gesture.cancel",
                    "Cancel active handle drag");
    static final Map<String, String> MOUSE_ACTIONS =
            Map.of("view.orbit", "Orbit view", "view.pan", "Pan view", "view.zoom", "Zoom view");

    private final Path defaultKeys, defaultMouse, userFile;

    EditorBindingProfile(Path defaultKeys, Path defaultMouse, Path userFile) {
        this.defaultKeys = defaultKeys;
        this.defaultMouse = defaultMouse;
        this.userFile = userFile;
    }

    Loaded load() {
        var combined = new ArrayList<Entry>();
        combined.addAll(readBindingFile(defaultKeys, Kind.KEY));
        combined.addAll(readBindingFile(defaultMouse, Kind.MOUSE));
        var defaults = normalize(combined);
        if (!Files.exists(userFile))
            return new Loaded(List.copyOf(defaults), List.copyOf(defaults), null);
        try {
            return new Loaded(List.copyOf(defaults), List.copyOf(readOverride(userFile)), null);
        } catch (RuntimeException error) {
            return new Loaded(
                    List.copyOf(defaults),
                    List.copyOf(defaults),
                    "Bindings override ignored: " + message(error));
        }
    }

    List<Entry> normalize(Collection<Entry> values) {
        var result = new ArrayList<Entry>();
        var keys = new HashSet<KeyChord>();
        var mouse = new HashSet<MouseChord>();
        for (var value : values) {
            if (value.chord().isBlank())
                throw new IllegalArgumentException("A shortcut or gesture is blank");
            if (value.kind() == Kind.KEY) {
                if (!KEY_ACTIONS.containsKey(value.action()))
                    throw new IllegalArgumentException(
                            "Unknown keyboard action: " + value.action());
                var chord = KeyChord.parse(value.chord());
                if (!keys.add(chord))
                    throw new IllegalArgumentException(
                            "Duplicate keyboard shortcut: " + chord.format());
                result.add(new Entry(Kind.KEY, chord.format(), value.action()));
            } else {
                if (!MOUSE_ACTIONS.containsKey(value.action()))
                    throw new IllegalArgumentException("Unknown mouse action: " + value.action());
                var chord = MouseChord.parse(value.chord());
                validateMouse(chord, value.action());
                if (!mouse.add(chord))
                    throw new IllegalArgumentException(
                            "Duplicate mouse gesture: " + chord.format());
                result.add(new Entry(Kind.MOUSE, chord.format(), value.action()));
            }
        }
        if (result.isEmpty())
            throw new IllegalArgumentException("At least one binding is required");
        return result;
    }

    void save(List<Entry> values) {
        var normalized = normalize(values);
        Path absolute = userFile.toAbsolutePath(), parent = absolute.getParent();
        Path temporary = null;
        try {
            if (parent != null) Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
            Files.writeString(
                    temporary,
                    serialize(normalized),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(
                    temporary,
                    absolute,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
        } catch (IOException error) {
            throw new IllegalStateException("Could not save bindings: " + message(error), error);
        } finally {
            if (temporary != null)
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
        }
    }

    private static void validateMouse(MouseChord chord, String action) {
        if (chord.button() == MouseButton.LEFT)
            throw new IllegalArgumentException("Left mouse is reserved for selection and handles");
        if (!chord.mode().equals("viewport"))
            throw new IllegalArgumentException("Editor mouse gestures must end with +viewport");
        if (action.equals("view.zoom")) {
            if (chord.gesture() != MouseGesture.WHEEL)
                throw new IllegalArgumentException("Zoom requires a wheel gesture");
        } else {
            if (chord.gesture() != MouseGesture.DRAG)
                throw new IllegalArgumentException("Orbit and pan require drag gestures");
            if (chord.button() == MouseButton.NONE)
                throw new IllegalArgumentException("Orbit and pan require a mouse button");
        }
    }

    private static List<Entry> readBindingFile(Path path, Kind kind) {
        var values = new ArrayList<Entry>();
        if (kind == Kind.KEY) {
            var actions = new ActionRegistry<Runnable>();
            KEY_ACTIONS.keySet().forEach(id -> actions.register(id, () -> {}));
            var table =
                    BindingFiles.load(path, new KeyBindings(actions))
                            .validate("scene editor defaults");
            table.serialized()
                    .forEach((chord, action) -> values.add(new Entry(kind, chord, action)));
        } else {
            var actions = new ActionRegistry<Consumer<MouseInput>>();
            MOUSE_ACTIONS.keySet().forEach(id -> actions.register(id, _ -> {}));
            var table =
                    BindingFiles.load(path, new MouseBindings(actions))
                            .validate("scene editor defaults");
            table.serialized()
                    .forEach((chord, action) -> values.add(new Entry(kind, chord, action)));
        }
        return values;
    }

    private List<Entry> readOverride(Path path) {
        try {
            var result = new ArrayList<Entry>();
            int lineNumber = 0;
            for (var raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                lineNumber++;
                var line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
                Kind kind;
                String binding;
                if (line.startsWith("key.")) {
                    kind = Kind.KEY;
                    binding = line.substring(4);
                } else if (line.startsWith("mouse.")) {
                    kind = Kind.MOUSE;
                    binding = line.substring(6);
                } else
                    throw new IllegalArgumentException(
                            "Line " + lineNumber + " must start with key. or mouse.");
                int delimiter = binding.indexOf(" = ");
                if (delimiter < 1)
                    throw new IllegalArgumentException("Line " + lineNumber + " must use ' = '");
                var chord = unescape(binding.substring(0, delimiter));
                var action = binding.substring(delimiter + 3).trim();
                result.add(new Entry(kind, chord, action));
            }
            return normalize(result);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Could not read " + path + ": " + message(error), error);
        }
    }

    private static String serialize(List<Entry> values) {
        var keyActions = new ActionRegistry<Runnable>();
        KEY_ACTIONS.keySet().forEach(id -> keyActions.register(id, () -> {}));
        var mouseActions = new ActionRegistry<Consumer<MouseInput>>();
        MOUSE_ACTIONS.keySet().forEach(id -> mouseActions.register(id, _ -> {}));
        var keys = new KeyBindings(keyActions);
        var mouse = new MouseBindings(mouseActions);
        for (var entry : values)
            if (entry.kind() == Kind.KEY) keys.bindParsed(entry.chord(), entry.action());
            else mouse.bindParsed(entry.chord(), entry.action());
        var output =
                new StringBuilder("# Scene editor bindings. Managed by the Bindings dialog.\n");
        append(output, "key.", keys);
        append(output, "mouse.", mouse);
        return output.toString();
    }

    private static void append(StringBuilder output, String prefix, BindingTable table) {
        var writer = new StringWriter();
        BindingFiles.save(writer, table);
        for (var line : writer.toString().lines().toList())
            if (!line.isBlank()) output.append(prefix).append(line).append('\n');
    }

    private static String unescape(String value) {
        var properties = new Properties();
        try {
            properties.load(new StringReader(value + " = value"));
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return properties.stringPropertyNames().stream().findFirst().orElseThrow();
    }

    private static String message(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }
}
