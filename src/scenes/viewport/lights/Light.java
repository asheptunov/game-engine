package scenes.viewport.lights;

import math.Ray;

import java.util.List;
import java.util.random.RandomGenerator;

public interface Light {
    /** Emit {@code n} sample rays from this light source. */
    List<Ray> sample(int n, RandomGenerator rng);
}
