package scenes.viewport;

import math.Vec3;

/** Linear RGB reflectance; mirrors are delta events and have no diffuse light evaluation. */
public record Material(String name, Vec3 color, Kind kind) {
    public enum Kind { DIFFUSE, MIRROR }
    public Material(String name, Vec3 color) { this(name, color, Kind.DIFFUSE); }
    public Material {
        if (name == null || name.isBlank() || color == null || kind == null) throw new IllegalArgumentException("Invalid material");
        for (float c : new float[]{color.x(), color.y(), color.z()})
            if (!Float.isFinite(c) || c < 0 || c > 1) throw new IllegalArgumentException("Reflectance must be 0..1");
    }
    public Material withKind(Kind next) { return new Material(name, color, next); }
    /** Caller-owned scratch. Probability is solid-angle PDF for diffuse, discrete mass for mirror. */
    public static final class Sample {
        public float dx, dy, dz, red, green, blue, probability;
        public Kind event;
    }
    /** Normal faces the incoming ray; returns direction and f*cos/pdf (or delta-event weight). */
    public void sample(float dx, float dy, float dz, float nx, float ny, float nz,
                       float u, float v, Sample out) {
        out.event = kind;
        out.red = color.x(); out.green = color.y(); out.blue = color.z();
        if (kind == Kind.MIRROR) {
            float twice = 2*(dx*nx + dy*ny + dz*nz);
            out.dx = dx - twice*nx; out.dy = dy - twice*ny; out.dz = dz - twice*nz;
            out.probability = 1;
            return;
        }
        // Cosine-weighted hemisphere: f*cos/pdf = reflectance.
        float r = (float)Math.sqrt(u), phi = (float)(2*Math.PI*v);
        float a = r*(float)Math.cos(phi), b = r*(float)Math.sin(phi);
        float c = (float)Math.sqrt(Math.max(0, 1-u));
        float tx, ty, tz;
        if (Math.abs(nz) < .999f) {
            float inv = 1/(float)Math.sqrt(nx*nx + ny*ny);
            tx = -ny*inv; ty = nx*inv; tz = 0;
        } else {
            float inv = 1/(float)Math.sqrt(ny*ny + nz*nz);
            tx = 0; ty = -nz*inv; tz = ny*inv;
        }
        out.dx = tx*a + (ny*tz - nz*ty)*b + nx*c;
        out.dy = ty*a + (nz*tx - nx*tz)*b + ny*c;
        out.dz = tz*a + (nx*ty - ny*tx)*b + nz*c;
        out.probability = c/(float)Math.PI;
    }
    public static Material srgb(String name, int rgb) {
        return new Material(name, new Vec3(DisplayMapping.linear((rgb >> 16) & 255),
                DisplayMapping.linear((rgb >> 8) & 255), DisplayMapping.linear(rgb & 255)));
    }
}
