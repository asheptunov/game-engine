package rendering;

import java.awt.Font;

/** Shared native font choice for raster text and the performance panel. */
public final class AwtText {
    private AwtText() {}

    public static Font monospaced(int size) {
        if (size < 1) throw new IllegalArgumentException("Font size must be positive");
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }
}
