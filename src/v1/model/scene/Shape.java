package v1.model.scene;

import java.util.List;

public interface Shape {
    List<Vector> vertices();

    Basis basis();

    boolean occludes();

    boolean emitsLight();
}
