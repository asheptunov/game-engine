package v1.model.scene;

import java.util.Arrays;
import java.util.Collection;

public class Scene {
    private final Camera camera;
    private final Collection<Shape> shapes;
    private final Collection<Shape> lights;

    public Scene(Camera camera, Shape... shapes) {
        this.camera = camera;
        this.shapes = Arrays.asList(shapes);
        this.lights = Arrays.stream(shapes).filter(Shape::emitsLight).toList();
    }

    public Camera camera() {
        return camera;
    }

    public Collection<Shape> shapes() {
        return shapes;
    }

    public Collection<Shape> lights() {
        return lights;
    }
}
