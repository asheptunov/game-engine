package scenes.textureeditor.console;

import logging.LogManager;
import logging.Logger;
import misc.monads.Result;
import rendering.Raster;
import rendering.RasterRepository;
import scenes.textureeditor.model.EditorState;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

public class CmdTouch implements Command {
    private static final Logger LOG = LogManager.instance().getThis();

    private final EditorState      state;
    private final RasterRepository repo;
    private final Supplier<Raster> emptyRasterFactory;

    public CmdTouch(EditorState state, RasterRepository repo, Supplier<Raster> emptyRasterFactory) {
        this.state = state;
        this.repo = repo;
        this.emptyRasterFactory = emptyRasterFactory;
    }

    @Override
    public Result<String, String> run(String... args) {
        if (args.length != 2) {
            return Result.failure("Usage: touch <file>.tx");
        }
        if (!args[1].endsWith(".tx")) {
            return Result.failure("Unsupported extension");
        }
        var targetPath = state.workingDir().toPath().resolve(Path.of(args[1]));
        File targetFile;
        try {
            targetFile = targetPath.toFile().getCanonicalFile();
        } catch (IOException e) {
            return Result.failure("Unexpected error: " + e.getMessage());
        }
        if (targetFile.exists()) {
            return Result.failure("Already exists: " + targetFile);
        }
        try {
            if (!targetFile.createNewFile()) {
                return Result.failure("Failed to create " + targetFile);
            }
        } catch (IOException e) {
            return Result.failure("Failed to create " + targetFile);
        }
        return repo.save(targetFile, emptyRasterFactory.get())
                .ifFailure(e -> LOG.error(e, "Failed to create %s", targetFile))
                .mapFailure(Throwable::getMessage)
                .mapSuccess(_ -> "Created empty file " + targetFile);
    }
}
