package engine;

import java.util.Objects;

/** Diffuse texture binding for a canonical local cube spanning -1..1. */
public record CubeTextures(Texture2D top, Texture2D bottom, Texture2D sides) {
    public CubeTextures {
        Objects.requireNonNull(top, "top");
        Objects.requireNonNull(bottom, "bottom");
        Objects.requireNonNull(sides, "sides");
    }

    /**
     * Side rows point down (+Y at image top). Viewed from outside, columns point right: -Z uses +X,
     * +Z uses -X, +X uses -Z, -X uses +Z. Top columns use +X and rows +Z; bottom columns use +X and
     * rows -Z. Geometric normal selects the face, even at edges.
     */
    public void sampleLocal(
            float x, float y, float z, float nx, float ny, float nz, float[] output) {
        if (Math.abs(ny) >= Math.max(Math.abs(nx), Math.abs(nz))) {
            if (ny > 0) {
                top.sample((x + 1) * .5f, (z + 1) * .5f, output);
            } else {
                bottom.sample((x + 1) * .5f, (1 - z) * .5f, output);
            }
        } else if (Math.abs(nx) >= Math.abs(nz)) {
            sides.sample((nx > 0 ? 1 - z : z + 1) * .5f, (1 - y) * .5f, output);
        } else {
            sides.sample((nz > 0 ? 1 - x : x + 1) * .5f, (1 - y) * .5f, output);
        }
    }
}
