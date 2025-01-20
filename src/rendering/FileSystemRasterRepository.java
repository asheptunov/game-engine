package rendering;

import logging.LogManager;
import logging.Logger;
import misc.monads.Result;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

public class FileSystemRasterRepository implements RasterRepository {
    private static final Logger LOG = LogManager.instance().getThis();

    private final Clock clock;

    public FileSystemRasterRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void create(Path targetFilename, Raster raster) {
        var start = clock.instant();
        var targetFile = targetFilename.toFile();
        if (targetFile.exists()) {
            LOG.error("Target already exists: %s", targetFilename);
            return;
        }
        try {
            if (!targetFile.createNewFile()) {
                LOG.error("Failed to create %s", targetFilename);
                return;
            }
        } catch (IOException e) {
            LOG.error(e, "Failed to create %s", targetFilename);
            return;
        }
        try (var fos = new FileOutputStream(targetFile)) {
            writeRaster(raster, fos);
        } catch (FileNotFoundException e) {
            LOG.error(e, "Could not find file after creating it: %s", targetFilename);
        } catch (IOException e) {
            LOG.error(e, "Could not close file: %s", targetFilename);
        }
        var end = clock.instant();
        LOG.info("Created %s in %s", targetFilename, Duration.between(start, end));
    }

    @Override
    public void delete(Path targetFilename) {
        var targetFile = targetFilename.toFile();
        if (!targetFile.exists()) {
            LOG.error("Target does not exist: %s", targetFilename);
            return;
        }
        if (!targetFile.isFile()) {
            LOG.error("Target is not a file: %s", targetFilename);
            return;
        }
        if (!targetFile.delete()) {
            LOG.error("Failed to delete %s", targetFilename);
            return;
        }
        LOG.info("Deleted %s", targetFilename);
    }

    @Override
    public Result<Raster, Exception> load(Path filename) {
        var start = clock.instant();
        var file = filename.toFile();
        if (file.exists()) {
            if (file.isDirectory()) {
                return fail("Failed to load from file; %s is a directory, not a file", file);
            }
        } else {
            return fail("Failed to load from file; %s does not exist", file);
        }
        Result<Raster, Exception> res;
        try (var fis = new FileInputStream(file)) {
            res = readRaster(fis, filename);
        } catch (FileNotFoundException e) {
            res = fail(e, "Could not find file after creating it: %s", filename);
        } catch (IOException e) {
            res = fail(e, "Could not close file %s", filename);
        }
        if (res.isFailure()) {
            return res;
        }
        var end = clock.instant();
        LOG.info("Loaded from %s in %s", filename, Duration.between(start, end));
        return res;
    }

    private <T> Result<T, Exception> fail(Exception e, String fmt, Object... args) {
        return Result.failure(new RuntimeException(fmt.formatted(args), e));
    }

    private <T> Result<T, Exception> fail(String fmt, Object... args) {
        return Result.failure(new RuntimeException(fmt.formatted(args)));
    }

    @Override
    public Result<Void, Exception> save(Path filename, Raster raster) {
        var start = clock.instant();
        var file = filename.toFile();
        var dir = file.getParentFile();
        if (dir.exists()) {
            if (!dir.isDirectory()) {
                return fail("Failed to save; %s is a file, not a directory", dir);
            }
        } else {
            if (!dir.mkdirs()) {
                return fail("Failed to make dirs for path: %s", dir);
            }
        }
        if (file.exists()) {
            if (file.isDirectory()) {
                return fail("Failed to save; path is a directory, not a file: %s", file);
            }
        } else {
            try {
                if (!file.createNewFile()) {
                    return fail("Failed to create file: %s", file);
                }
            } catch (IOException e) {
                return fail(e, "Failed to create file: %s", file);
            }
        }
        try (var fos = new FileOutputStream(file)) {
            writeRaster(raster, fos);
        } catch (FileNotFoundException e) {
            return fail(e, "Could not find file after creating it: %s", file);
        } catch (IOException e) {
            return fail(e, "Could not close file: %s", file);
        }
        var end = clock.instant();
        LOG.info("Saved to %s in %s", file, Duration.between(start, end));
        return Result.success(null);
    }

    private void writeRaster(Raster raster, FileOutputStream fos) throws IOException {
        int w = raster.width();
        int h = raster.height();
        fos.write(intToBytes(w));  // width - BE int32
        fos.write(intToBytes(h));  // height - BE int32
        // pixel data - BE int24 array
        int n = w * h;
        var buf = new byte[n * 3];
        var r = raster.red();
        var g = raster.green();
        var b = raster.blue();
        int bufI = 0;
        for (int i = 0; i < n; ++i) {
            var alpha = 1. * raster.alpha()[i] / 0xff;
            buf[bufI++] = (byte) (alpha * r[i]);
            buf[bufI++] = (byte) (alpha * g[i]);
            buf[bufI++] = (byte) (alpha * b[i]);
        }
        fos.write(buf);
    }

    private Result<Raster, Exception> readRaster(FileInputStream fis, Path filename) {
        var intBuf = new byte[4];
        try {
            // width - BE int32
            if (4 != fis.read(intBuf)) {
                return fail("Error reading texture width from file %s (unexpected end of buffer)", filename);
            }
            int w = intFromBytes(intBuf);
            // height - BE int32
            if (4 != fis.read(intBuf)) {
                return fail("Error reading texture height from file %s (unexpected end of buffer)", filename);
            }
            int h = intFromBytes(intBuf);
            // pixel data - BE int24 array
            int n = w * h;
            var buf = new byte[n * 3];
            if (buf.length != fis.read(buf)) {
                return fail("Error reading texture pixel data from file %s (unexpected end of buffer)", filename);
            }
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
            return Result.success(new PixelRaster(w, h, a, r, g, b));
        } catch (IOException ex) {
            return Result.failure(ex);
        }
    }

    private byte[] intToBytes(int i) {
        // big-endian
        return new byte[]{
                (byte) ((i & 0xff000000) >> 24),
                (byte) ((i & 0xff0000) >> 16),
                (byte) ((i & 0xff00) >> 8),
                (byte) (i & 0xff)};
    }

    private int intFromBytes(byte[] b) {
        return (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | (b[3]);
    }

}
