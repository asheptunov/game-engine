package scenes.textureeditor.console;

import logging.LogManager;
import logging.Logger;
import misc.monads.Result;
import scenes.textureeditor.model.EditorState;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public class CmdRm implements Command {
    private static final Logger LOG = LogManager.instance().getThis();

    private final EditorState state;

    public CmdRm(EditorState state) {
        this.state = state;
    }

    @Override
    public Result<String, String> run(String... args) {
        if (args.length < 2 || args.length > 3) {
            return usage();
        }
        var recursive = false;
        var path = (Path) null;
        for (int i = 0; i < args.length; ++i) {
            if (i == 0) {
                continue;
            }
            var arg = args[i];
            if (arg.startsWith("-")) {  // option cluster
                if ("-r".equals(arg)) {
                    recursive = true;
                } else {
                    return Result.failure("Unknown option: " + arg);
                }
            } else {  // target
                path = Path.of(arg);
            }
        }
        if (path == null) {
            return usage();
        }
        var targetPath = state.workingDir().toPath().resolve(path);
        File targetFile;
        try {
            targetFile = targetPath.toFile().getCanonicalFile();
        } catch (IOException e) {
            return Result.failure("Unexpected error: " + e.getMessage());
        }
        if (!targetFile.exists()) {
            return Result.failure("No such file: " + targetFile);
        }
        return delete(targetFile, recursive)
                .ifFailure(LOG::error)
                .mapSuccess(_ -> {
                    if (state.workingFile().filter(targetFile::equals).isPresent()) {
                        state.clearWorkingFile();
                        return "Deleted and un-set active file: " + targetFile;
                    } else {
                        return "Deleted file: " + targetFile;
                    }
                });
    }

    private Result<String, String> delete(File file, boolean recursive) {
        if (file.isDirectory()) {
            if (!recursive) {
                return Result.failure("Target is a directory: " + file);
            }
            var contents = file.listFiles();
            if (contents == null) {
                return Result.failure("Failed to list files under " + file);
            }
            for (var c : contents) {
                var res = delete(c, true);
                if (res.isFailure()) {
                    return res;
                }
            }
        }
        if (!file.delete()) {
            return Result.failure("Failed to delete " + file);
        }
        return Result.success("Deleted " + file);
    }

    private static Result<String, String> usage() {
        return Result.failure("Usage: rm [-r] <file|dir>");
    }
}
