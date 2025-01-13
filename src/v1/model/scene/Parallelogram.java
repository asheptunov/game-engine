package v1.model.scene;

public class Parallelogram extends Trapezoid {
    public Parallelogram(Vector v1, Vector v2, Vector v3, boolean occludes, boolean emitsLight) {
        super(v1, v2, v3, v1.plus(v3.minus(v2)), occludes, emitsLight);
    }
}
