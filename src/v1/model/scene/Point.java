package v1.model.scene;

import java.util.List;

public final class Point implements Shape, Basis {
    private final Vector coordinate;
    private final boolean occludes;
    private final boolean emitsLight;

    public Point(Vector coordinate, boolean occludes, boolean emitsLight) {
        this.coordinate = coordinate;
        this.occludes = occludes;
        this.emitsLight = emitsLight;
    }

    @Override
    public List<Vector> vertices() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean occludes() {
        return occludes;
    }

    @Override
    public boolean emitsLight() {
        return emitsLight;
    }

    public Basis basis() {
        return this;
    }

    public Vector coordinate() {
        return coordinate;
    }
}
