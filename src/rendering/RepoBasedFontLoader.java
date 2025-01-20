package rendering;

import logging.LogManager;
import logging.Logger;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class RepoBasedFontLoader implements Font.Loader {
    private static final Logger                 LOG         = LogManager.instance().getThis();
    private static final Map<Character, String> FONT_FS_MAP = new HashMap<>() {{
        for (char c = 'a'; c <= 'z'; ++c) {
            put(c, Character.toString(c));
        }
        for (char c = '0'; c <= '9'; ++c) {
            put(c, Character.toString(c));
        }
        put('\0', "nil");
        put(' ', "space");
        put('!', "bang");
        put('"', "quotation");
        put('#', "hash");
        put('$', "dollar");
        put('%', "percent");
        put('&', "ampersand");
        put('\'', "apostrophe");
        put('(', "left_paren");
        put(')', "right_paren");
        put('*', "star");
        put('+', "plus");
        put(',', "comma");
        put('-', "hyphen");
        put('.', "dot");
        put('/', "slash");
        put(':', "colon");
        put(';', "semicolon");
        put('<', "less_than");
        put('=', "equal");
        put('>', "greater_than");
        put('?', "question");
        put('@', "at");
        put('[', "left_square_bracket");
        put('\\', "backslash");
        put(']', "right_square_bracket");
        put('^', "caret");
        put('_', "underscore");
        put('`', "grave");
        put('{', "left_curly_brace");
        put('|', "pipe");
        put('}', "right_curly_brace");
        put('~', "tilde");
        forEach((c, filename) -> put(c, filename + ".tx"));
    }};

    private final Clock            clock;
    private final RasterRepository repo;
    private final Path             path;

    public RepoBasedFontLoader(Clock clock, RasterRepository repo, Path path) {
        this.clock = clock;
        this.repo = repo;
        this.path = path;
    }

    @Override
    public Font load() {
        var res = new HashMap<Character, Raster>();
        var start = clock.instant();
        FONT_FS_MAP.forEach((c, filename) -> {
            var assetPath = path.resolve(filename);
            var loadResult = repo.load(assetPath);
            loadResult
                    .ifFailure(ex -> LOG.warn(ex, "Failed to read asset file for char '%c': %s", c, filename))
                    .mapFailure(Exception::getMessage)
                    .ifFailure(LOG::warn)
                    .ifSuccess(asset -> {
                        LOG.debug("Loaded asset for '%c' from %s", c, filename);
                        res.put(c, asset);
                    });
        });
        // nil must be loadable (to render missing textures)
        if (!res.containsKey('\0')) {
            throw new IllegalArgumentException("Font must have a nil texture");
        }
        var dimSet = res.values().stream().map(r -> new Dimensions(r.width(), r.height()))
                .collect(Collectors.groupingBy(d -> d, Collectors.counting()));
        if (dimSet.size() != 1) {
            LOG.warn("Font has non-uniform dimensions: " + dimSet);
        }
        var end = clock.instant();
        LOG.info("Loaded font in %s", Duration.between(start, end));
        return new InMemoryFont(res);
    }

    private record Dimensions(int width, int height) {}
}
