package engine;

/** Fixed exposure in stops, Reinhard tone mapping, then sRGB encoding. */
public final class DisplayMapping {
    private DisplayMapping() {}
    private static final byte[] SRGB = new byte[65536];
    static {
        for (int i = 0; i < SRGB.length; i++) {
            double c = i / 65535.;
            SRGB[i] = (byte) Math.round(255 * (c <= .0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - .055));
        }
    }
    public static float linear(int channel) {
        double c = channel / 255.;
        return (float) (c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4));
    }
    public static byte encode(float radiance, float exposureMultiplier) {
        float c = Math.max(0, radiance * exposureMultiplier);
        float mapped = Float.isInfinite(c) ? 1 : c / (1 + c);
        return SRGB[Math.min(65535, Math.round(mapped * 65535))];
    }
}
