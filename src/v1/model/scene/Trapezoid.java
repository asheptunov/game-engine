package v1.model.scene;

import java.util.ArrayList;
import java.util.List;

public class Trapezoid implements Shape {
    private final ArrayList<Vector> verts;
    private final boolean occludes;
    private final boolean emitsLight;
    private Plane basis;

    public Trapezoid(Vector v1, Vector v2, Vector v3, Vector v4, boolean occludes, boolean emitsLight) {
        this.verts = new ArrayList<>();
        verts.add(v1);
        verts.add(v2);
        verts.add(v3);
        verts.add(v4);
        this.occludes = occludes;
        this.emitsLight = emitsLight;
    }

    @Override
    public List<Vector> vertices() {
        return verts;
    }

    @Override
    public Basis basis() {
        if (basis == null) {
            basis = new Plane(
                    verts.getFirst(),
                    verts.get(3).minus(verts.get(0)).cross(verts.get(1).minus(verts.get(0))),
                    false, false);
        }
        return basis;
    }

    @Override
    public boolean occludes() {
        return occludes;
    }

    @Override
    public boolean emitsLight() {
        return emitsLight;
    }
}
