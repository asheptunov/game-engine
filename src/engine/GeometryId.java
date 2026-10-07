package engine;

import java.util.Objects;
import java.util.UUID;

/** Stable geometry-asset identity. */
public record GeometryId(UUID value) {
    public GeometryId { Objects.requireNonNull(value, "value"); }
    public static GeometryId random() { return new GeometryId(UUID.randomUUID()); }
    public static GeometryId parse(String value) { return new GeometryId(UUID.fromString(value)); }
    @Override public String toString() { return value.toString(); }
}
