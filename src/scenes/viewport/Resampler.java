package scenes.viewport;

/**
 * Box-filter resample of a 2D float buffer to a new size. Each output pixel at {@code (dx, dy)} is
 * the area-weighted average of all input pixels overlapping the input-space region that maps to
 * that output pixel.
 *
 * <p>The same routine collapses to:
 * <ul>
 *   <li><b>Upscale</b> (output &gt; input at integer ratio): each output pixel falls fully inside
 *       one input pixel → nearest-neighbor replication.</li>
 *   <li><b>1:1</b>: pure copy.</li>
 *   <li><b>Downscale</b> (input &gt; output): each output pixel averages an input region — the SSAA
 *       path. At integer ratios this is a uniform box average over an N×N block.</li>
 *   <li><b>Non-integer ratios</b>: pixels on region boundaries contribute fractional weights.</li>
 * </ul>
 *
 * <p>No Y-flip is performed; the caller owns the row-0-is-top vs row-0-is-bottom convention.
 */
public final class Resampler {
    private Resampler() {}

    /** Output buffer plus its max value, computed in a single pass to save a second scan. */
    public record Result(float[][] buf, float max) {}

    public static Result resample(float[][] sensor, int dispH, int dispW) {
        int sensorH = sensor.length;
        int sensorW = sensorH == 0 ? 0 : sensor[0].length;
        var out = new float[dispH][dispW];
        if (sensorH == 0 || sensorW == 0) return new Result(out, 0);
        // Integer downsampling (including 1:1) needs no overlap geometry or per-sample weights.
        if (dispH > 0 && dispW > 0 && sensorH % dispH == 0 && sensorW % dispW == 0) {
            int blockH = sensorH / dispH, blockW = sensorW / dispW;
            float max = 0;
            for (int y = 0; y < dispH; y++) {
                for (int x = 0; x < dispW; x++) {
                    float sum = 0;
                    for (int by = 0; by < blockH; by++) {
                        var row = sensor[y * blockH + by];
                        for (int bx = 0; bx < blockW; bx++) sum += row[x * blockW + bx];
                    }
                    float value = sum / (blockH * blockW);
                    out[y][x] = value;
                    max = Math.max(max, value);
                }
            }
            return new Result(out, max);
        }
        float syPerDy = (float) sensorH / dispH;
        float sxPerDx = (float) sensorW / dispW;
        float max = 0;
        for (int dy = 0; dy < dispH; dy++) {
            float syStart = dy * syPerDy;
            float syEnd   = (dy + 1) * syPerDy;
            int   sy0     = (int) Math.floor(syStart);
            int   sy1     = Math.min(sensorH - 1, (int) Math.ceil(syEnd) - 1);
            for (int dx = 0; dx < dispW; dx++) {
                float sxStart     = dx * sxPerDx;
                float sxEnd       = (dx + 1) * sxPerDx;
                int   sx0         = (int) Math.floor(sxStart);
                int   sx1         = Math.min(sensorW - 1, (int) Math.ceil(sxEnd) - 1);
                float weightedSum = 0;
                float totalWeight = 0;
                for (int sy = sy0; sy <= sy1; sy++) {
                    float overlapY = Math.min(sy + 1, syEnd) - Math.max(sy, syStart);
                    for (int sx = sx0; sx <= sx1; sx++) {
                        float overlapX = Math.min(sx + 1, sxEnd) - Math.max(sx, sxStart);
                        float w        = overlapX * overlapY;
                        weightedSum   += sensor[sy][sx] * w;
                        totalWeight   += w;
                    }
                }
                float v = totalWeight > 0 ? weightedSum / totalWeight : 0;
                out[dy][dx] = v;
                if (v > max) max = v;
            }
        }
        return new Result(out, max);
    }
}
