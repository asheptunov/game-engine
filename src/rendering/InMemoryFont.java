package rendering;

import java.util.Map;
import java.util.stream.Collectors;

public class InMemoryFont implements Font {
    private final Map<Character, Raster> font;

    public InMemoryFont(Map<Character, Raster> font) {
        this.font = font.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().factory().cloneable(e.getValue()).clone()));
    }

    @Override
    public Raster getNil() {
        return font.get('\0');
    }

    @Override
    public Raster getChar(char c) {
        return font.computeIfAbsent(c, $ -> getNil());
    }
}
