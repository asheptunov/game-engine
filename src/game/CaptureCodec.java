package game;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Versioned capture schemas choose Java types in code, never from names in an input file. */
final class CaptureCodec {
    private static final int MAX_BYTES = 96 * 1024 * 1024;

    private CaptureCodec() {}

    static void save(Path path, Record value) throws IOException {
        save(path, value, new Budget());
    }

    static void save(Path path, Record value, Budget budget) throws IOException {
        var properties = new Properties();
        write(properties, "data", value);
        var text = new StringWriter();
        properties.store(text, "Game diagnostic data; schema version is in capture.properties");
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > budget.remaining) {
            throw new IOException("Decoded capture exceeds 96 MiB");
        }
        budget.remaining -= bytes.length;
        try (var file = Files.newOutputStream(path);
                var output = path.toString().endsWith(".gz") ? new GZIPOutputStream(file) : file) {
            output.write(bytes);
        }
    }

    static final class Budget {
        private int remaining = MAX_BYTES;

        void consume(long bytes) throws IOException {
            if (bytes < 0 || bytes > remaining) {
                throw new IOException("Decoded capture exceeds 96 MiB");
            }
            remaining -= (int) bytes;
        }
    }

    static <T> T load(Path path, Class<T> schema) throws IOException {
        return load(path, schema, new Budget());
    }

    static <T> T load(Path path, Class<T> schema, Budget budget) throws IOException {
        var properties = new Properties();
        try (var file = Files.newInputStream(path);
                var input = path.toString().endsWith(".gz") ? new GZIPInputStream(file) : file) {
            byte[] bytes = input.readNBytes(budget.remaining + 1);
            if (bytes.length > budget.remaining) {
                throw new IOException("Decoded capture exceeds 96 MiB");
            }
            budget.remaining -= bytes.length;
            properties.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
        }
        try {
            return schema.cast(read(properties, "data", schema));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IOException("Invalid " + path.getFileName() + ": " + failure, failure);
        }
    }

    private static void write(Properties properties, String key, Object value) throws IOException {
        if (value == null) {
            properties.setProperty(key, "null");
            return;
        }
        if (value instanceof List<?> list) {
            properties.setProperty(key, Integer.toString(list.size()));
            for (int i = 0; i < list.size(); i++) {
                write(properties, key + "." + i, list.get(i));
            }
        } else if (value instanceof Record record) {
            properties.setProperty(key, "record");
            for (var component : record.getClass().getRecordComponents()) {
                try {
                    write(
                            properties,
                            key + "." + component.getName(),
                            component.getAccessor().invoke(record));
                } catch (ReflectiveOperationException failure) {
                    throw new IOException(failure);
                }
            }
        } else {
            properties.setProperty(key, value.toString());
        }
    }

    private static Object read(Properties p, String key, Type type)
            throws ReflectiveOperationException {
        String value = java.util.Objects.requireNonNull(p.getProperty(key), "Missing " + key);
        if (value.equals("null") && type != String.class) {
            return null;
        }
        if (type instanceof ParameterizedType generic) {
            int size = Integer.parseInt(value);
            if (size < 0 || size > 250_000) {
                throw new IllegalArgumentException("Invalid list length at " + key);
            }
            var values = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                values.add(read(p, key + "." + i, generic.getActualTypeArguments()[0]));
            }
            return List.copyOf(values);
        }
        var clazz = (Class<?>) type;
        defaultCharacterState(p, key, clazz);
        if (!clazz.isRecord()) {
            return scalar(value, clazz);
        }
        // Version-one captures predating the optional GPU quality control use the reference path.
        if (clazz == engine.RenderSettings.class) {
            defaultRenderSettings(p, key);
        }
        if (clazz == engine.Material.class) {
            p.putIfAbsent(key + ".leaf", "null");
        }
        var components = clazz.getRecordComponents();
        var types = new Class<?>[components.length];
        var values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] =
                    read(p, key + "." + components[i].getName(), components[i].getGenericType());
        }
        return clazz.getDeclaredConstructor(types).newInstance(values);
    }

    private static void defaultCharacterState(Properties p, String key, Class<?> clazz) {
        if (clazz == GameNavigation.State.class && !p.containsKey(key + ".weapon")) {
            p.setProperty(key + ".weapon", "record");
            p.setProperty(key + ".weapon.combat", "false");
            p.setProperty(key + ".weapon.time", "0");
            p.setProperty(key + ".weapon.recoil", "0");
        }
        if (clazz == engine.CharacterBody.State.class) {
            p.putIfAbsent(key + ".height", "1.8");
            p.putIfAbsent(key + ".targetHeight", "1.8");
        }
    }

    private static void defaultRenderSettings(Properties p, String key) {
        p.putIfAbsent(key + ".bloom", "OFF");
        p.putIfAbsent(key + ".lightingScale", "1.0");
        p.putIfAbsent(key + ".denoiser", "BUILT_IN");
        p.putIfAbsent(key + ".fogDownsample", "1");
        p.putIfAbsent(key + ".fogLightingGrid", "false");
        p.putIfAbsent(key + ".fogJitter", "false");
        p.putIfAbsent(key + ".foveated", "false");
        p.putIfAbsent(key + ".smoothFoveation", "false");
        if (!p.containsKey(key + ".foveationQuality")) {
            p.setProperty(key + ".foveationQuality", "record");
            p.setProperty(key + ".foveationQuality.centerFraction", "0.5");
            p.setProperty(key + ".foveationQuality.edgeSpacing", "6");
        }
    }

    private static Object scalar(String value, Class<?> type) {
        if (type == String.class) {
            return value;
        }
        if (type == boolean.class || type == Boolean.class) {
            if (!value.equals("true") && !value.equals("false")) {
                throw new IllegalArgumentException("Invalid boolean");
            }
            return Boolean.valueOf(value);
        }
        if (type == int.class || type == Integer.class) {
            return Integer.valueOf(value);
        }
        if (type == long.class || type == Long.class) {
            return Long.valueOf(value);
        }
        if (type == float.class || type == Float.class) {
            return finiteFloat(value);
        }
        if (type == double.class || type == Double.class) {
            return finiteDouble(value);
        }
        return enumValue(value, type);
    }

    private static Object enumValue(String value, Class<?> type) {
        for (Object constant : type.getEnumConstants()) {
            if (constant.toString().equals(value)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Unknown value " + value + " for " + type);
    }

    private static float finiteFloat(String value) {
        float result = Float.parseFloat(value);
        if (!Float.isFinite(result)) {
            throw new IllegalArgumentException("Nonfinite float");
        }
        return result;
    }

    private static double finiteDouble(String value) {
        double result = Double.parseDouble(value);
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException("Nonfinite double");
        }
        return result;
    }
}
