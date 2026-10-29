package game;

import engine.CapsuleCollision;
import math.Vec3;

/** Identical forest contact queries through linear and accelerated collision. */
public final class CollisionBenchmark {
    private static volatile float sink;

    public static void main(String[] args) {
        var solids = AdventureWorld.create().instances().stream()
                .filter(instance -> !instance.name().startsWith("Foliage ")).toList();
        var linear = new CapsuleCollision(solids, false);
        var accelerated = new CapsuleCollision(solids);
        var positions = new Vec3[2000];
        for (int i = 0; i < positions.length; i++) {
            float x = (i % 41 - 20) * .1f;
            float z = -8 + (i % 200) * .05f;
            positions[i] = new Vec3(x, AdventureWorld.height(x, z) - .05f, z);
        }
        for (int warmup = 0; warmup < 3; warmup++) {
            run(linear, positions);
            run(accelerated, positions);
        }
        for (int round = 0; round < 5; round++) {
            double before = run(linear, positions);
            double after = run(accelerated, positions);
            System.out.printf("round=%d queries=%d linearMs=%.3f bvhMs=%.3f speedup=%.2f%n",
                    round, positions.length, before, after, before / after);
        }
    }

    private static double run(CapsuleCollision collision, Vec3[] positions) {
        long start = System.nanoTime();
        float sum = 0;
        for (var position : positions) {
            var contact = collision.contact(position, .28f, 1.8f);
            if (contact != null) sum += contact.depth();
        }
        sink = sum;
        return (System.nanoTime() - start) / 1e6;
    }
}
