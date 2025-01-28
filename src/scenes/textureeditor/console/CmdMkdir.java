package scenes.textureeditor.console;

import misc.monads.Result;
import scenes.textureeditor.model.EditorState;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public class CmdMkdir implements Command {
    private final EditorState state;

    public CmdMkdir(EditorState state) {
        this.state = state;
    }

    @Override
    public Result<String, String> run(String... args) {
        if (args.length != 2) {
            return usage();
        }
        var target = state.workingDir().toPath().resolve(Path.of(args[1]));
        File targetFile;
        try {
            targetFile = target.toFile().getCanonicalFile();
        } catch (IOException e) {
            return Result.failure("Unexpected error: " + e.getMessage());
        }
        if (targetFile.exists() && targetFile.isDirectory()) {
            return Result.failure("Dir already exists: " + targetFile);
        }
        if (!targetFile.mkdir()) {
            return Result.failure("Failed to delete " + targetFile);
        }
        return Result.success("Created dir: " + targetFile);
    }

    private static Result<String, String> usage() {
        return Result.failure("Usage: mkdir <dir name>");
    }
}
