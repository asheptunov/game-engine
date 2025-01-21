package rendering;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class InMemoryFont implements Font {
    private final Map<Character, Raster> font;
    private final Raster                 nil;
    private final int                    size;

    public InMemoryFont(Map<Character, Raster> font, int size) {
        this.font = Collections.synchronizedMap(new HashMap<>(font));
        this.nil = font.get('\0');
        this.size = size;
    }

    @Override
    public Raster getChar(char c) {
        return font.computeIfAbsent(c, _ -> nil);
    }

    @Override
    public Raster getNil() {
        return nil;
    }

    @Override
    public int size() {
        return size;
    }
}
