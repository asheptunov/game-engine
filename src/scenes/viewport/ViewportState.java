package scenes.viewport;

import math.Vec3;
import scenes.viewport.lights.Light;
import scenes.viewport.objects.Rect;
import scenes.viewport.objects.SceneObject;

import java.util.ArrayList;
import java.util.List;

public class ViewportState {
    private       Rect              cameraSensor;
    private       Vec3              eye             = new Vec3(0, 0, -1);
    private final int               sensorPixelsW;
    private final int               sensorPixelsH;
    private final List<SceneObject> objects         = new ArrayList<>();
    private final List<Light>       lights          = new ArrayList<>();
    private       int               samplesPerLight = 1000;
    private       int               maxBounces      = 4;
    private final float[][]         accumulator;

    public ViewportState(Rect cameraSensor, int sensorPixelsW, int sensorPixelsH) {
        this.cameraSensor = cameraSensor;
        this.sensorPixelsW = sensorPixelsW;
        this.sensorPixelsH = sensorPixelsH;
        this.accumulator = new float[sensorPixelsH][sensorPixelsW];
    }

    public float[][] accumulator() { return accumulator; }

    public void clearAccumulator() {
        for (var row : accumulator) {
            java.util.Arrays.fill(row, 0);
        }
    }

    public Rect cameraSensor() { return cameraSensor; }
    public void cameraSensor(Rect r) { this.cameraSensor = r; }
    public Vec3 eye() { return eye; }
    public void eye(Vec3 e) { this.eye = e; }
    public int sensorPixelsW() { return sensorPixelsW; }
    public int sensorPixelsH() { return sensorPixelsH; }
    public List<SceneObject> objects() { return objects; }
    public List<Light> lights() { return lights; }
    public int samplesPerLight() { return samplesPerLight; }
    public void samplesPerLight(int n) { this.samplesPerLight = n; }
    public int maxBounces() { return maxBounces; }
    public void maxBounces(int n) { this.maxBounces = n; }
    public void addObject(SceneObject o) { objects.add(o); }
    public void addLight(Light l) { lights.add(l); }
}
