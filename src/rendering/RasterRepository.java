package rendering;

import misc.monads.Result;

import java.io.File;

public interface RasterRepository {
    Result<Raster, Exception> load(File file);

    Result<Void, Exception> save(File file, Raster raster);
}
