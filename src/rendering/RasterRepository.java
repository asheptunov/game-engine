package rendering;

import misc.monads.Either;

import java.nio.file.Path;

public interface RasterRepository {
    void create(Path path, Raster raster);

    void delete(Path path);

    Either<Raster, Exception> load(Path path);

    Either<Void, Exception> save(Path path, Raster raster);
}
