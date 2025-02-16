package rendering;

import logging.LogManager;
import logging.Logger;
import misc.monads.Result;

import java.util.Arrays;
import java.util.List;

public class ChainRasterSerializer implements RasterSerializer {
    private static final Logger LOG = LogManager.instance().getThis();

    private final List<? extends Named<? extends RasterSerializer>> chain;

    private ChainRasterSerializer(List<? extends RasterSerializer> chain) {
        this.chain = chain.stream().map(rs -> new Named<>(rs.toString(), rs)).toList();
    }

    public static ChainRasterSerializer of(RasterSerializer... chain) {
        return new ChainRasterSerializer(Arrays.asList(chain));
    }

    private record Named<T>(String name, T t) {}

    @Override
    public Result<byte[], Exception> serialize(Raster raster) {
        return chain.stream()
                .map(nrs -> new Named<>(nrs.name(), nrs.t().serialize(raster)))
                .filter(nrs -> nrs.t().isSuccess())
                .limit(1)
                .peek(nrs -> LOG.debug("Serialized using %s", nrs.name()))
                .findFirst()
                .map(Named::t)
                .orElseGet(() -> Result.failure(new RuntimeException("No applicable serializers")));
    }

    @Override
    public Result<Raster, Exception> deserialize(byte[] bytes) {
        return chain.stream()
                .map(nrs -> new Named<>(nrs.name(), nrs.t().deserialize(bytes)))
                .filter(nrs -> nrs.t().isSuccess())
                .limit(1)
                .peek(nrs -> LOG.debug("Deserialized using %s", nrs.name()))
                .findFirst()
                .map(Named::t)
                .orElseGet(() -> Result.failure(new RuntimeException("No applicable deserializers")));
    }
}
