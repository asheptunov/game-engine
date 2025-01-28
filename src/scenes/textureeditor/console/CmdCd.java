package scenes.textureeditor.console;

import misc.monads.Result;
import scenes.textureeditor.model.EditorState;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

public class CmdCd implements Command {
    private final EditorState    state;
    private final Supplier<Path> root;

    public CmdCd(EditorState state, Supplier<Path> root) {
        this.state = state;
        this.root = root;
    }

    @Override
    public Result<String, String> run(String... args) {
        if (args.length > 2) {
            return Result.failure("Usage: cd [dir]");
        }
        var targetPath = args.length == 1
                ? root.get()
                : state.workingDir().toPath().resolve(Path.of(args[1]));
        File targetFile;
        try {
            targetFile = targetPath.toFile().getCanonicalFile();
        } catch (IOException e) {
            return Result.failure("Unexpected error: " + e.getMessage());
        }
        if (!targetFile.exists()) {
            return Result.failure("No such dir: " + targetFile);
        }
        if (!targetFile.isDirectory()) {
            return Result.failure("Not a dir: " + targetFile);
        }
        state.workingDir(targetFile);
        return Result.success(null);
    }
}
