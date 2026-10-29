package engine;

import math.Vec3;

/** Thin-sheet transport. Tint is normal-incidence transmittance through one millimetre. */
public record LeafOptics(
        float transmission, Vec3 tint, float thicknessMm, float forwardFraction, float exponent) {
    public LeafOptics {
        if (!Float.isFinite(transmission)
                || transmission < 0
                || transmission > 1
                || !Float.isFinite(thicknessMm)
                || thicknessMm < 0
                || thicknessMm > 10
                || !Float.isFinite(forwardFraction)
                || forwardFraction < 0
                || forwardFraction > 1
                || !Float.isFinite(exponent)
                || exponent < 0
                || exponent > 128) {
            throw new IllegalArgumentException("Invalid thin-leaf optical parameters");
        }
        java.util.Objects.requireNonNull(tint);
        for (float c : new float[] {tint.x(), tint.y(), tint.z()}) {
            if (!Float.isFinite(c) || c < 0 || c > 1) {
                throw new IllegalArgumentException("Leaf transmittance must be 0..1");
            }
        }
    }

    /** Reciprocal finite-width forward lobe plus diffuse transmission; returns f*cos and PDF. */
    public void evaluate(float ci, float co, float alignment, Vec3 color, Material.Sample out) {
        out.red = out.green = out.blue = out.probability = 0;
        if (ci <= 0 || co == 0) {
            return;
        }
        if (co > 0) {
            out.probability = (1 - transmission) * co / (float) Math.PI;
            out.red = color.x() * out.probability;
            out.green = color.y() * out.probability;
            out.blue = color.z() * out.probability;
            return;
        }
        float power = alignment > 0 ? (float) Math.pow(alignment, exponent) : 0;
        float diffuse = (1 - forwardFraction) / (float) Math.PI;
        float lobe = forwardFraction * power / (2 * (float) Math.PI);
        float weight = transmission * -co * (diffuse + (exponent + 2) * lobe);
        out.probability = transmission * (-co * diffuse + (exponent + 1) * lobe);
        // Symmetric optical path avoids a one-way angular tint and remains energy absorbing.
        float thickness = thicknessMm * .5f * (1 / Math.max(.02f, ci) + 1 / Math.max(.02f, -co));
        out.red = weight * (float) Math.pow(tint.x(), thickness);
        out.green = weight * (float) Math.pow(tint.y(), thickness);
        out.blue = weight * (float) Math.pow(tint.z(), thickness);
    }

    void sample(float u, float v, Vec3 color, Material.Sample out) {
        boolean transmit = u < transmission;
        float local = transmit ? u / transmission : (u - transmission) / (1 - transmission);
        boolean forward = transmit && local < forwardFraction;
        float axisX = out.nx;
        float axisY = out.ny;
        float axisZ = out.nz;
        float cosine;
        if (forward) {
            local /= forwardFraction;
            cosine = (float) Math.pow(local, 1 / (exponent + 1));
            axisX = out.inDx;
            axisY = out.inDy;
            axisZ = out.inDz;
        } else {
            if (transmit) {
                local = (local - forwardFraction) / (1 - forwardFraction);
                axisX = -axisX;
                axisY = -axisY;
                axisZ = -axisZ;
            }
            cosine = (float) Math.sqrt(Math.max(0, 1 - local));
        }
        direction(axisX, axisY, axisZ, cosine, v, out);
        float co = out.nx * out.dx + out.ny * out.dy + out.nz * out.dz;
        float ci = -(out.nx * out.inDx + out.ny * out.inDy + out.nz * out.inDz);
        float alignment = out.inDx * out.dx + out.inDy * out.dy + out.inDz * out.dz;
        evaluate(ci, co, alignment, color, out);
        // Reject forward samples outside the transmission hemisphere, without resampling.
        if (transmit != (co < 0) || out.probability == 0) {
            out.red = out.green = out.blue = 0;
        } else {
            out.red /= out.probability;
            out.green /= out.probability;
            out.blue /= out.probability;
        }
        out.transmitted = transmit;
    }

    private static void direction(
            float x, float y, float z, float cosine, float v, Material.Sample out) {
        float tx = Math.abs(z) < .999f ? -y : 0;
        float ty = Math.abs(z) < .999f ? x : -z;
        float tz = Math.abs(z) < .999f ? 0 : y;
        float inverse = 1 / (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        tx *= inverse;
        ty *= inverse;
        tz *= inverse;
        float sine = (float) Math.sqrt(Math.max(0, 1 - cosine * cosine));
        float a = sine * (float) Math.cos(2 * Math.PI * v);
        float b = sine * (float) Math.sin(2 * Math.PI * v);
        out.dx = tx * a + (y * tz - z * ty) * b + x * cosine;
        out.dy = ty * a + (z * tx - x * tz) * b + y * cosine;
        out.dz = tz * a + (x * ty - y * tx) * b + z * cosine;
    }
}
