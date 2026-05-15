package ui;

import logging.LogManager;
import logging.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Loads a {@link Bindings} layer from a {@code .properties} file. Each line is a {@code chord = action_id}
 * pair, where {@code chord} parses via the layer's own {@link Bindings#bindParsed} (key or mouse syntax).
 * Lines starting with {@code #} are comments. Action ids must already be registered in the
 * {@link ActionRegistry} the bindings wrap; call {@link Bindings#validate(String)} after loading to enforce that.
 */
public final class BindingsLoader {
    private static final Logger LOG = LogManager.instance().getThis();

    private BindingsLoader() {}

    public static <B extends Bindings> B loadInto(Path path, B bindings) {
        try (Reader r = Files.newBufferedReader(path)) {
            return loadInto(r, bindings);
        } catch (NoSuchFileException e) {
            throw new IllegalStateException("Bindings file not found: " + path.toAbsolutePath(), e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read bindings file: " + path.toAbsolutePath(), e);
        }
    }

    public static <B extends Bindings> B loadInto(Reader reader, B bindings) {
        var props = new Properties();
        try {
            props.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse bindings", e);
        }
        for (var name : props.stringPropertyNames()) {
            var actionId = props.getProperty(name).trim();
            if (actionId.isEmpty()) {
                throw new IllegalStateException("Empty action id for chord '" + name + "'");
            }
            bindings.bindParsed(name, actionId);
        }
        LOG.info("Loaded %d binding(s)", props.size());
        return bindings;
    }
}
