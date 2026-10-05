package scenes.viewport;

import math.Vec3;

/** Linear RGB reflectance and interior medium; mirror/glass boundaries are delta events. */
public record Material(String name, Vec3 color, Kind kind, float ior, Vec3 absorption) {
    public enum Kind { DIFFUSE, MIRROR, DIELECTRIC }
    public Material(String name, Vec3 color, Kind kind) { this(name, color, kind, 1.5f, Vec3.ZERO); }
    public Material(String name, Vec3 color) { this(name, color, Kind.DIFFUSE); }
    public Material {
        if (name == null || name.isBlank() || color == null || kind == null) throw new IllegalArgumentException("Invalid material");
        for (float c : new float[]{color.x(), color.y(), color.z()})
            if (!Float.isFinite(c) || c < 0 || c > 1) throw new IllegalArgumentException("Reflectance must be 0..1");
        if (!Float.isFinite(ior) || ior < 1 || ior > 3) throw new IllegalArgumentException("IOR must be 1..3");
        if (absorption == null) throw new IllegalArgumentException("Missing absorption");
        for (float c : new float[]{absorption.x(), absorption.y(), absorption.z()})
            if (!Float.isFinite(c) || c < 0 || c > 100) throw new IllegalArgumentException("Absorption must be 0..100 per scene unit");
    }
    public Material withKind(Kind next) { return new Material(name, color, next, ior, absorption); }
    public Material withColor(Vec3 next) { return new Material(name, next, kind, ior, absorption); }
    public Material withIor(float next) { return new Material(name, color, kind, next, absorption); }
    public Material withAbsorption(Vec3 next) { return new Material(name, color, kind, ior, next); }
    /** Caller-owned scratch. Probability is solid-angle PDF for diffuse, discrete mass for delta events. */
    public static final class Sample {
        public float dx, dy, dz, red, green, blue, probability;
        public Kind event;
        public boolean transmitted;
    }
    /** Normal faces the incoming ray; returns direction and f*cos/pdf (or delta-event weight). */
    public void sample(float dx, float dy, float dz, float nx, float ny, float nz,
                       float u, float v, Sample out) {
        out.event = kind;
        out.transmitted = false;
        if (kind == Kind.DIELECTRIC) throw new IllegalArgumentException("Dielectric sampling requires incident and transmitted IOR");
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
    /** Smooth dielectric, radiance transport: transmission carries (etaIncident/etaExit)^2.
     * Fresnel is also the branch probability, so its coefficient cancels in the weight. */
    public void sampleDielectric(float dx, float dy, float dz, float nx, float ny, float nz,
                                 float incident, float exit, float u, Sample out) {
        float cosine = Math.clamp(-(dx*nx+dy*ny+dz*nz), 0, 1);
        float eta = incident/exit;
        float sinSquared = eta*eta*Math.max(0, 1-cosine*cosine);
        float transmittedCosine = (float)Math.sqrt(Math.max(0,1-sinSquared));
        float fresnel = fresnel(incident, exit, cosine);
        out.event = Kind.DIELECTRIC;
        out.transmitted = (incident == exit || sinSquared < 1) && u >= fresnel;
        if (!out.transmitted) {
            out.dx=dx+2*cosine*nx;out.dy=dy+2*cosine*ny;out.dz=dz+2*cosine*nz;
            out.red=out.green=out.blue=1;out.probability=fresnel;
        } else {
            float normalWeight=eta*cosine-transmittedCosine;
            out.dx=eta*dx+normalWeight*nx;out.dy=eta*dy+normalWeight*ny;out.dz=eta*dz+normalWeight*nz;
            out.red=out.green=out.blue=eta*eta;out.probability=1-fresnel;
        }
    }
    static float fresnel(float incident, float exit, float cosine) {
        if (incident == exit) return 0;
        float eta=incident/exit, sinSquared=eta*eta*Math.max(0,1-cosine*cosine);
        if (sinSquared >= 1) return 1;
        float ct=(float)Math.sqrt(1-sinSquared);
        float parallel=(exit*cosine-incident*ct)/(exit*cosine+incident*ct);
        float perpendicular=(incident*cosine-exit*ct)/(incident*cosine+exit*ct);
        return .5f*(parallel*parallel+perpendicular*perpendicular);
    }
    public static Material srgb(String name, int rgb) {
        return new Material(name, new Vec3(DisplayMapping.linear((rgb >> 16) & 255),
                DisplayMapping.linear((rgb >> 8) & 255), DisplayMapping.linear(rgb & 255)));
    }
}
