package engine;

import java.util.Objects;

/** Camera stored in node-local coordinates; rendering dimensions/settings remain external. */
public record CameraComponent(Camera camera) {
    public CameraComponent {
        Objects.requireNonNull(camera, "camera").validated();
    }
}
