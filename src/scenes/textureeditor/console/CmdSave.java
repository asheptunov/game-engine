package scenes.textureeditor.console;

import logging.LogManager;
import logging.Logger;
import misc.monads.Result;
import rendering.RasterRepository;
import scenes.textureeditor.model.EditorState;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public class CmdSave implements Command {
    private final Logger LOG = LogManager.instance().getThis();

    private final EditorState      state;
    private final RasterRepository repo;

    public CmdSave(EditorState state, RasterRepository repo) {
        this.state = state;
        this.repo = repo;
    }

    @Override
    public Result<String, String> run(String... args) {
        if (args.length > 2) {
            return Result.failure("Usage: save [<file>.tx]");
        }
        Path target;
        if (args.length == 1) {
            var file = state.workingFile();
            if (file.isEmpty()) {
                return Result.failure("No active file; you must specify a file to save to");
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
        return repo.save(targetFile, state.texture())
                .ifFailure(e -> LOG.error(e, "Failed to save to %s", targetFile))
                .mapFailure(Throwable::getMessage)
                .ifSuccess(_ -> state.workingFile(targetFile))
                .mapSuccess(_ -> "Saved and set active file to " + targetFile);
    }
}
