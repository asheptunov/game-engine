package v1;

import v1.model.scene.Line;
import v1.model.scene.Point;
import v1.model.scene.Ray;
import v1.model.scene.Scene;
import v1.model.scene.Shape;
import v1.model.scene.Vector;

import java.util.Collection;
import java.util.Optional;
import java.util.Random;
import java.util.stream.LongStream;

public class SceneRasterizer implements Rasterizer<Scene> {
    private final long samples;
    private final int width;
    private final int height;

    private final Random random;

    public SceneRasterizer(long samples, int width, int height, Random random) {
        this.samples = samples;
        this.width = width;
        this.height = height;
        this.random = random;
    }

    @Override
    public int[] rasterize(Scene scene) {
        System.out.println("rasterizing");
        var raster = new int[width * height];
        var camera = scene.camera();
        var cameraHz = camera.bounds().vertices().get(1).minus(camera.bounds().vertices().get(0));
        var cameraVt = camera.bounds().vertices().get(3).minus(camera.bounds().vertices().get(0));
        scene.lights()
                .stream()
                .map(this::sampleRays)
                .flatMap(Collection::stream)
                .map(ray -> ray.intersection(camera.bounds(), 0.001))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(i -> {
                    var tHz = i.dot(cameraHz) / cameraHz.dot(cameraHz);
                    var tVt = i.dot(cameraVt) / cameraVt.dot(cameraVt);
                    var col = (int) Math.max(0, Math.min(width-1, tHz * width));
                    var row = (int) Math.max(0, Math.min(height-1, tVt * height));
                    raster[row * width + col] = 1;
                });
        return raster;
    }

    private Collection<Ray> sampleRays(Shape light) {
        return switch (light) {
            case Point p: {
                yield LongStream.range(0, samples).mapToObj(i -> new Ray(p.coordinate(),
                                new Vector(random.nextDouble(), random.nextDouble(), random.nextDouble())))
                        .toList();
            }
            case Line l: {
                yield LongStream.range(0, samples)
                        .mapToObj(i -> new Ray(l.intercept().plus(l.parallel().times(random.nextDouble())),
                            new Vector(random.nextDouble(), random.nextDouble(), random.nextDouble())))
                        .toList();
                }
            case null:
                throw new IllegalArgumentException();
            default:
                throw new UnsupportedOperationException(light.getClass().getName());
        };
    }
}
