package engine;

import java.util.Objects;
import java.util.UUID;

/** Stable material-asset identity. */
public record MaterialId(UUID value) {
    public MaterialId {
        Objects.requireNonNull(value, "value");
    }

    public static MaterialId random() {
        return new MaterialId(UUID.randomUUID());
    }

    public static MaterialId parse(String value) {
        return new MaterialId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
