package scenes.viewport.lights;

import harness.SuiteRunner;
import harness.Test;
import math.Vec3;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertTrue;

public class PointLightTest {
    private static final float EPS = 1e-4f;

    private static RandomGenerator seeded(long seed) {
        return RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
    }

    @Test
    void sampleCountMatchesN() {
        var light = new PointLight(new Vec3(1, 2, 3));
        var rays = light.sample(50, seeded(42));
        assertEquals(50, rays.size());
    }

    @Test
    void sampleCountZero() {
        var light = new PointLight(Vec3.ZERO);
        assertEquals(0, light.sample(0, seeded(1)).size());
    }

    @Test
    void allOriginsAtPosition() {
        var pos = new Vec3(5, -3, 7);
        var light = new PointLight(pos);
        for (var r : light.sample(100, seeded(0))) {
            assertEquals(pos, r.origin());
        }
    }

    @Test
    void allDirectionsAreUnit() {
        var light = new PointLight(Vec3.ZERO);
        for (var r : light.sample(200, seeded(123))) {
            float len = r.direction().length();
            assertTrue(Math.abs(len - 1f) < EPS);
        }
    }

    @Test
    void centroidApproachesOrigin() {
        // Many samples averaged → directional centroid near zero (uniform distribution on sphere).
        var light = new PointLight(Vec3.ZERO);
        var rays = light.sample(10000, seeded(7));
        var sum = Vec3.ZERO;
        for (var r : rays) {
            sum = sum.add(r.direction());
        }
        var centroid = sum.scale(1f / rays.size());
        // 10k samples, std error ~ 1/sqrt(N) ~ 0.01; allow generous tolerance.
        assertTrue(centroid.length() < 0.05f);
    }

    @Test
    void deterministicWithSameSeed() {
        var light = new PointLight(Vec3.ZERO);
        var a = light.sample(10, seeded(99));
        var b = light.sample(10, seeded(99));
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i), b.get(i));
        }
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
