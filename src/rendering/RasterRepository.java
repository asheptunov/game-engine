package rendering;

import misc.monads.Result;

import java.nio.file.Path;

public interface RasterRepository {
    void create(Path path, Raster raster);

    void delete(Path path);

    Result<Raster, Exception> load(Path path);

    Result<Void, Exception> save(Path path, Raster raster);
}
