package scenes.textureeditor.console;

import logging.LogManager;
import logging.Logger;
import misc.monads.Result;
import rendering.RasterRepository;
import scenes.textureeditor.model.EditorState;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public class CmdLoad implements Command {
    private static final Logger LOG = LogManager.instance().getThis();

    private final EditorState      state;
    private final RasterRepository repo;

    public CmdLoad(EditorState state, RasterRepository repo) {
        this.state = state;
        this.repo = repo;
    }

    @Override
    public Result<String, String> run(String... args) {
        if (args.length > 2) {
            return Result.failure("Usage: load [<file>.tx]");
        }
        Path target;
        if (args.length == 1) {
            var file = state.workingFile();
            if (file.isEmpty()) {
                return Result.failure("No active file; you must specify a file to load from");
            }
            target = state.workingDir().toPath().resolve(file.get().toPath());
        } else {
            if (!args[1].endsWith(".tx")) {
                return Result.failure("Unsupported extension");
            }
            target = state.workingDir().toPath().resolve(Path.of(args[1]));
        }
        File targetFile;
        try {
            targetFile = target.toFile().getCanonicalFile();
        } catch (IOException e) {
            return Result.failure("Unexpected error: " + e.getMessage());
        }
        if (!targetFile.exists()) {
            return Result.failure("No such file: " + targetFile);
        }
        if (!targetFile.isFile()) {
            return Result.failure("Not a file: " + targetFile);
        }
        return repo.load(targetFile)
                .ifFailure(e -> LOG.error(e, "Failed to load from %s", targetFile))
                .mapFailure(Throwable::getMessage)
                .ifSuccess(texture -> {
                    state.texture(texture);
                    state.snapshot();
                    state.workingFile(targetFile);
                })
                .mapSuccess(_ -> "Loaded from and set active file to " + targetFile);
    }
}
