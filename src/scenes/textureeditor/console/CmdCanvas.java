package scenes.textureeditor.console;

import logging.LogManager;
import logging.Logger;
import misc.monads.Result;
import rendering.Color;
import rendering.PixelRaster;
import scenes.textureeditor.model.EditorState;

public class CmdCanvas implements Command {
    private static final Logger LOG = LogManager.instance().getThis();

    private final EditorState state;

    public CmdCanvas(EditorState state) {
        this.state = state;
    }

    @Override
    public Result<String, String> run(String... args) {
        if (args.length > 3) {
            return usage();
        }
        if (args.length == 1) {
            var tx = state.texture();
            return Result.success("[w=%d, h=%d]".formatted(tx.width(), tx.height()));
        }
        if (args.length == 2) {
            return parseInt(args[1]).flatMapSuccess(dim -> resize(dim, dim));
        }
        var width = parseInt(args[1]);
        var height = parseInt(args[2]);
        if (width.isFailure() && height.isFailure()) {
            return Result.failure(width.getFailure() + "\n" + height.getFailure());
        }
        if (width.isFailure()) {
            return Result.failure(width.getFailure());
        }
        if (height.isFailure()) {
            return Result.failure(height.getFailure());
        }
        return resize(width.getSuccess(), height.getSuccess());
    }

    private Result<Integer, String> parseInt(String arg) {
        try {
            int dim = Integer.parseInt(arg);
            if (dim < 1) {
                return Result.failure("Size must be positive: " + dim);
            }
            return Result.success(dim);
        } catch (NumberFormatException e) {
            LOG.warn(e, "Not an integer: " + arg);
            return Result.failure("Not an integer: " + arg);
        }
    }

    private Result<String, String> resize(int width, int height) {
        var tx = state.texture();
        int oldWidth = tx.width();
        int oldHeight = tx.height();
        if (width < oldWidth || height < oldHeight) {
            return Result.failure("Target canvas dimensions [w=%d, h=%d] must be no smaller than current: [w=%d, h=%d]"
                    .formatted(width, height, oldWidth, oldHeight));
        }
        state.texture(new PixelRaster(width, height, (_, x, y)
                -> x >= oldWidth || y >= oldHeight
                ? Color.NamedColor.BLACK
                : tx.pixel(x, y)));
        state.snapshot();
        return Result.success("Expanded canvas to [w=%d, h=%d]".formatted(width, height));
    }

    private static Result<String, String> usage() {
        return Result.failure("Usage: canvas [<dim>|<width> <height>]");
    }
}
