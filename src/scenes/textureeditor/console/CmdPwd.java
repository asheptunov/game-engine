package scenes.textureeditor.console;

import misc.monads.Result;
import scenes.textureeditor.model.EditorState;

import java.io.IOException;

public class CmdPwd implements Command {
    private final EditorState state;

    public CmdPwd(EditorState state) {
        this.state = state;
    }

    @Override
    public Result<String, String> run(String... args) {
        if (args.length > 1) {
            return Result.failure("Usage: pwd");
        }
        try {
            return Result.success(state.workingDir().toPath().toFile().getCanonicalPath());
        } catch (IOException e) {
            return Result.failure("Unexpected error: " + e.getMessage());
        }
    }
}
