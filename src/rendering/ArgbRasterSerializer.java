package rendering;

import misc.monads.Result;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class ArgbRasterSerializer implements RasterSerializer {
    private static final String UUID         = "17E1BD52E4D7A8B5";
    private static final byte[] VERSION_BLOB = ("ARGB." + UUID).getBytes(StandardCharsets.UTF_8);

    public static final ArgbRasterSerializer INSTANCE = new ArgbRasterSerializer();

    private ArgbRasterSerializer() {}

    @Override
    public Result<byte[], Exception> serialize(Raster raster) {
        try (var os = new ByteArrayOutputStream()) {
            os.write(VERSION_BLOB);
            writeInt(raster.w(), os);
            writeInt(raster.h(), os);
            writeArgb(raster.a(), raster.r(), raster.g(), raster.b(), os);
            os.flush();
            return Result.success(os.toByteArray());
        } catch (IOException e) {
            return Result.failure(e);
        }
    }

    @Override
    public Result<Raster, Exception> deserialize(byte[] bytes) {
        try (var is = new ByteArrayInputStream(bytes)) {
            var version = is.readNBytes(VERSION_BLOB.length);
            if (!Arrays.equals(VERSION_BLOB, version)) {
                return Result.failure(new RuntimeException("Bad magic number"));
            }
            var w = readInt(is);
            var h = readInt(is);
            var argb = readArgb(w * h, is);
            int rem = is.readAllBytes().length;
            if (0 != rem) {
                return Result.failure(new RuntimeException(rem + " bytes remaining"));
            }
            return Result.success(new PixelRaster(w, h, argb));
        } catch (IOException e) {
            return Result.failure(e);
        }
    }

    private void writeInt(int i, OutputStream os) throws IOException {
        // little-endian
        os.write((i & 0xff));
        i >>= 8;
        os.write((i & 0xff));
        i >>= 8;
        os.write((i & 0xff));
        i >>= 8;
        os.write((i & 0xff));
    }

    private int readInt(InputStream is) throws IOException {
        // little-endian
        int i = is.read();
        i |= (is.read() << 8);
        i |= (is.read() << 16);
        i |= (is.read() << 24);
        return i;
    }

    private void writeArgb(byte[] a, byte[] r, byte[] g, byte[] b, OutputStream os) throws IOException {
        for (int i = 0; i < a.length; ++i) {
            os.write(a[i]);
            os.write(r[i]);
            os.write(g[i]);
            os.write(b[i]);
        }
    }

    private byte[][] readArgb(int n, InputStream is) throws IOException {
        byte[] a = new byte[n];
        byte[] r = new byte[n];
        byte[] g = new byte[n];
        byte[] b = new byte[n];
        for (int i = 0; i < n; ++i) {
            a[i] = (byte) is.read();
            r[i] = (byte) is.read();
            g[i] = (byte) is.read();
            b[i] = (byte) is.read();
        }
        return new byte[][]{a, r, g, b};
    }
}
