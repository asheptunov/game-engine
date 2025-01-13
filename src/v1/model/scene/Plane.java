package v1.model.scene;

import java.util.List;

public final class Plane implements Shape, Basis {
    private final Vector intercept;
    private final Vector normal;
    private final boolean occludes;
    private final boolean emitsLight;

    public Plane(Vector intercept, Vector normal, boolean occludes, boolean emitsLight) {
        this.intercept = intercept;
        this.normal = normal;
        this.occludes = occludes;
        this.emitsLight = emitsLight;
    }

    @Override
    public List<Vector> vertices() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Basis basis() {
        return this;
    }

    @Override
    public boolean occludes() {
        return occludes;
    }

    @Override
    public boolean emitsLight() {
        return emitsLight;
    }

    public Vector intercept() {
        return intercept;
    }

    public Vector normal() {
        return normal;
    }
}
