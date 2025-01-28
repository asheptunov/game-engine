package scenes.textureeditor.console;

import misc.monads.Result;

import java.util.Arrays;
import java.util.function.Predicate;

public class TrimmingCommand implements Command {
    private final Command delegate;

    public TrimmingCommand(Command delegate) {
        this.delegate = delegate;
    }

    @Override
    public Result<String, String> run(String... args) {
        var trimmedArgs = Arrays.stream(args)
                .map(String::strip)
                .filter(Predicate.not(String::isEmpty))
                .toArray(String[]::new);
        if (trimmedArgs.length == 0) {
            return Result.success(null);
        }
        while (trimmedArgs[0].startsWith("/")) {
            // todo this should not be necessary
            trimmedArgs[0] = trimmedArgs[0].substring(1);
        }
        return delegate.run(trimmedArgs);
    }
}
