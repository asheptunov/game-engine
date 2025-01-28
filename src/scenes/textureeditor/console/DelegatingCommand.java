package scenes.textureeditor.console;

import misc.monads.Result;

import java.util.HashMap;
import java.util.Map;

public class DelegatingCommand implements Command {
    private final Map<String, Command> delegates;

    public DelegatingCommand(Map<String, Command> delegates) {
        this.delegates = new HashMap<>(delegates);
    }

    @Override
    public Result<String, String> run(String... args) {
        if (args.length == 0) {
            throw new IllegalArgumentException();
        }
        var name = args[0];
        if (!delegates.containsKey(name)) {
            return Result.failure("Unknown command: " + name);
        }
        return delegates.get(name).run(args);
    }
}
