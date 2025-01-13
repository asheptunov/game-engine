package v1.model.scene;

import java.util.List;

public final class Line implements Shape, Basis {
    private final Vector intercept;
    private final Vector parallel;
    private final boolean occludes;
    private final boolean emitsLight;

    public Line(Vector intercept, Vector parallel, boolean occludes, boolean emitsLight) {
        this.intercept = intercept;
        this.parallel = parallel;
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

    public Vector parallel() {
        return parallel;
    }
}
