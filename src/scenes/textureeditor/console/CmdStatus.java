package scenes.textureeditor.console;

import misc.monads.Result;
import rendering.Color;
import scenes.textureeditor.ColorPicker;
import scenes.textureeditor.model.EditorState;

import java.io.File;

public class CmdStatus implements Command {
    private final EditorState state;
    private final ColorPicker colorPicker;

    public CmdStatus(EditorState state, ColorPicker colorPicker) {
        this.state = state;
        this.colorPicker = colorPicker;
    }

    @Override
    public Result<String, String> run(String... args) {
        if (args.length != 1) {
            return Result.failure("Usage: status");
        }
        var texture = state.texture();
        var color = colorPicker.getColor();
        return Result.success("""
                Working dir: %s
                Open file: %s
                Width: %d
                Height: %d
                Color: %s%#08X%s
                """.formatted(
                state.workingDir().getAbsolutePath(),
                state.workingFile().map(File::getAbsolutePath).orElse("<not set>"),
                texture.width(),
                texture.height(),
                Color.AnsiColor.formatted(color),
                color.argbInt32(),
                Color.AnsiColor.NONE.formatted()));
    }
}
