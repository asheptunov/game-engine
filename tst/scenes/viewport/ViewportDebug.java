package scenes.viewport;

import math.Vec3;
import scenes.viewport.lights.PointLight;
import scenes.viewport.objects.Rect;
import scenes.viewport.objects.Tri;

/** One-off debug harness — backward ray-trace the default viewport scene and ASCII-render it. */
public class ViewportDebug {
    public static void main(String[] args) {
        var sensor = new Rect(
                new Vec3(-0.5f, -0.5f, 0),
                new Vec3(1, 0, 0),
                new Vec3(0, 1, 0));
        int W = 100, H = 100;
        var st = new ViewportState(sensor, W, H);
        st.eye(new Vec3(0, 0, -1));
        st.addObject(new Tri(new Vec3(-2, -2, 10), new Vec3(2, -2, 10), new Vec3(0, 2, 10)));
        st.addLight(new PointLight(new Vec3(0, 0, 5)));

        var tracer = new BackwardRayTracer(st);
        var buf = tracer.trace();

        float max = 0;
        for (var row : buf) {
            for (float v : row) {
                if (v > max) max = v;
            }
        }
        double ms = tracer.traceNanos() / 1e6;
        double mraysPerSec = tracer.primaryRays() / (tracer.traceNanos() / 1e3);
        System.out.printf("traced in %.2f ms (%.1f Mrays/s)%n", ms, mraysPerSec);
        System.out.printf("primary rays: %d, hits: %d%n", tracer.primaryRays(), tracer.primaryHits());
        System.out.printf("shadow rays: %d, occluded: %d%n", tracer.shadowRays(), tracer.shadowsOccluded());
        System.out.printf("lit pixels: %d / %d%n", tracer.litPixels(), W * H);
        System.out.printf("max intensity: %.3f%n", max);

        int down = 5;
        int gridW = W / down, gridH = H / down;
        System.out.println("--- preview (" + gridW + "x" + gridH + ", '@' = max, ' ' = 0) ---");
        for (int y = gridH - 1; y >= 0; y--) {
            for (int x = 0; x < gridW; x++) {
                float sum = 0;
                for (int yy = y * down; yy < (y + 1) * down; yy++) {
                    for (int xx = x * down; xx < (x + 1) * down; xx++) {
                        sum += buf[yy][xx];
                    }
                }
                float avg = sum / (down * down);
                float norm = max > 0 ? avg / max : 0;
                char c = " .:-=+*#%@".charAt(Math.min(9, (int) (norm * 10)));
                System.out.print(c);
            }
            System.out.println();
        }
    }
}
