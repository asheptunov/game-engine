package rendering;

import misc.monads.Result;

import java.nio.file.Path;

public interface RasterRepository {
    Result<Void, Exception> create(Path path, Raster raster);

    Result<Void, Exception> delete(Path path);

    Result<Raster, Exception> load(Path path);

    Result<Void, Exception> save(Path path, Raster raster);
}
