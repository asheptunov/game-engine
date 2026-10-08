package ui;

import java.io.Reader;
import java.nio.file.Path;

/**
 * Loads a {@link Bindings} layer from a {@code .properties} file. Each line is a {@code chord =
 * action_id} pair, where {@code chord} parses via the layer's own {@link Bindings#bindParsed} (key
 * or mouse syntax). Lines starting with {@code #} are comments. Call {@link
 * Bindings#validate(String)} after registering actions and loading to reject unresolved action ids.
 */
public final class BindingsLoader {
    private BindingsLoader() {}

    public static <B extends Bindings> B loadInto(Path path, B bindings) {
        return engine.input.BindingFiles.load(path, bindings);
    }

    public static <B extends Bindings> B loadInto(Reader reader, B bindings) {
        return engine.input.BindingFiles.load(reader, bindings);
    }

    public static void save(Path path, Bindings bindings) {
        engine.input.BindingFiles.save(path, bindings);
    }
}
