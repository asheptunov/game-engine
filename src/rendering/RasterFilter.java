package rendering;

public interface RasterFilter {
    Raster apply(Raster input);

    RasterFilter NO_OP = r -> r;

    static RasterFilter antiAlias() {
        return raster -> {
            var opacity = PixelFilter.opacity(1. / 9);
            var over = BlendMode.OVER_PRE;
            var res = raster.clone();
            res.write((_, x, y) -> {
                if (raster.pixel(x, y).alpha() != 0) {
                    return res.pixel(x, y);
                }
                var c = opacity.apply(res.pixel(x, y));
                c = over.apply(opacity.apply(res.pixel(x - 1, y - 1)), c);
                c = over.apply(opacity.apply(res.pixel(x - 1, y + 1)), c);
                c = over.apply(opacity.apply(res.pixel(x, y - 1)), c);
                c = over.apply(opacity.apply(res.pixel(x, y)), c);
                c = over.apply(opacity.apply(res.pixel(x, y + 1)), c);
                c = over.apply(opacity.apply(res.pixel(x + 1, y - 1)), c);
                c = over.apply(opacity.apply(res.pixel(x + 1, y)), c);
                c = over.apply(opacity.apply(res.pixel(x + 1, y + 1)), c);
                return c;
            });
            return res;
        };
    }
}
