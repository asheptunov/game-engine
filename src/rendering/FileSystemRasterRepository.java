package rendering;

import logging.LogManager;
import logging.Logger;
import misc.monads.Result;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

public class FileSystemRasterRepository implements RasterRepository {
    private static final Logger LOG = LogManager.instance().getThis();

    private final Clock            clock;
    private final RasterSerializer serializer;

    public FileSystemRasterRepository(Clock clock, RasterSerializer serializer) {
        this.clock = clock;
        this.serializer = serializer;
    }

    @Override
    public Result<Raster, Exception> load(File file) {
        var start = clock.instant();
        if (file.exists()) {
            if (file.isDirectory()) {
                return fail("Failed to load from file; %s is a directory, not a file", file);
            }
        } else {
            return fail("Failed to load from file; %s does not exist", file);
        }
        try (var fis = new FileInputStream(file)) {
            return serializer.deserialize(fis.readAllBytes());
        } catch (IOException e) {
            return Result.failure(e);
        } finally {
            var end = clock.instant();
            LOG.info("Loaded from %s in %s", file, Duration.between(start, end));
        }
    }

    private <T> Result<T, Exception> fail(Exception e, String fmt, Object... args) {
        return Result.failure(new RuntimeException(fmt.formatted(args), e));
    }

    private <T> Result<T, Exception> fail(String fmt, Object... args) {
        return Result.failure(new RuntimeException(fmt.formatted(args)));
    }

    @Override
    public Result<?, Exception> save(File file, Raster raster) {
        var start = clock.instant();
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
            return serializer.serialize(raster)
                    .ifSuccess(fos::write);
        } catch (IOException e) {
            return Result.failure(e);
        } finally {
            var end = clock.instant();
            LOG.info("Saved to %s in %s", file, Duration.between(start, end));
        }
    }
}
