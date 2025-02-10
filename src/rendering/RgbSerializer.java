package rendering;

import misc.monads.Result;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class RgbSerializer implements RasterSerializer {
    public static final RgbSerializer INSTANCE = new RgbSerializer();

    private RgbSerializer() {}

    @Override
    public Result<byte[], Exception> serialize(Raster raster) {
        int w = raster.width();
        int h = raster.height();
        try (var os = new ByteArrayOutputStream()) {
            os.write(iToB(w));  // width - BE int32
            os.write(iToB(h));  // height - BE int32
            os.write(toBytes(raster));  // pixel data - BE int24 array
            os.flush();
            return Result.success(os.toByteArray());
        } catch (IOException e) {
            return Result.failure(e);
        }
    }

    @Override
    public Result<Raster, Exception> deserialize(byte[] bytes) {
        try (var is = new ByteArrayInputStream(bytes)) {
            var w = readInt(is);  // width - BE int32
            var h = readInt(is);  // height - BE int32
            return w.flatMapSuccess(ww ->
                    h.flatMapSuccess(hh -> readBytes(3 * ww * hh, is)  // pixel data - BE int24 array
                            .mapSuccess(pxBytes -> toRaster(ww, hh, pxBytes))
                            .filter(_ -> is.read() == -1,
                                    _ -> new RuntimeException("Expected end of stream; got more bytes"))));
        } catch (IOException e) {
            return Result.failure(e);
        }
    }

    private byte[] iToB(int i) {
        // big-endian
        return new byte[]{
                (byte) ((i & 0xff000000) >> 24),
                (byte) ((i & 0xff0000) >> 16),
                (byte) ((i & 0xff00) >> 8),
                (byte) (i & 0xff)};
    }

    private int bToI(byte[] b) {
        // big-endian
        return (b[0] << 24)
                | (b[1] << 16)
                | (b[2] << 8)
                | (b[3]);
    }

    private static byte[] toBytes(Raster raster) {
        int n = raster.w() * raster.h();
        var buf = new byte[n * 3];
        var r = raster.red();
        var g = raster.green();
        var b = raster.blue();
        int bufI = 0;
        for (int i = 0; i < n; ++i) {
            // this method is lossy
            var alpha = ((int) raster.alpha()[i] & 0xff) / 255.;
            buf[bufI++] = (byte) (alpha * ((int) r[i] & 0xff));
            buf[bufI++] = (byte) (alpha * ((int) g[i] & 0xff));
            buf[bufI++] = (byte) (alpha * ((int) b[i] & 0xff));
        }
        return buf;
    }

    private static PixelRaster toRaster(int w, int h, byte[] buf) {
        int n = w * h;
        var a = new byte[n];
        var r = new byte[n];
        var g = new byte[n];
        var b = new byte[n];
        int px = 0;
        for (int i = 0; i < n; ++i) {
            a[i] = (byte) 0xff;
            r[i] = buf[px++];
            g[i] = buf[px++];
            b[i] = buf[px++];
        }
        return new PixelRaster(w, h, a, r, g, b);
    }

    private Result<Integer, Exception> readInt(InputStream inputStream) {
        return readBytes(4, inputStream)
                .mapSuccess(this::bToI);
    }

    private Result<byte[], Exception> readBytes(int n, InputStream inputStream) {
        try {
            var res = inputStream.readNBytes(n);
            if (n != res.length) {
                return Result.failure(new RuntimeException(
                        "Unexpected end of buffer (expected %d b; got %d b)".formatted(n, res.length)));
            }
            return Result.success(res);
        } catch (IOException e) {
            return Result.failure(e);
        }
    }
}
