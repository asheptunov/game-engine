package rendering;

import misc.monads.Result;

public interface RasterSerializer {
    Result<byte[], Exception> serialize(Raster raster);

    Result<Raster, Exception> deserialize(byte[] bytes);
}
