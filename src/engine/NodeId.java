package engine;

import java.util.Objects;
import java.util.UUID;

/** Stable scene-node identity. Human-readable labels are deliberately separate. */
public record NodeId(UUID value) {
    public NodeId {
        Objects.requireNonNull(value, "value");
    }

    public static NodeId random() {
        return new NodeId(UUID.randomUUID());
    }

    public static NodeId parse(String value) {
        return new NodeId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
