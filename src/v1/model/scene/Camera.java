package v1.model.scene;

public class Camera {
    private final Parallelogram bounds;

    public Camera(Vector v1, Vector v2, Vector v3) {
        this.bounds = new Parallelogram(v1, v2, v3, false, false);
    }

    public Parallelogram bounds() {
        return bounds;
    }
}
