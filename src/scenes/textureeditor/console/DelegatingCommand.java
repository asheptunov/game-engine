package scenes.textureeditor.console;

import misc.monads.Result;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class DelegatingCommand implements Command {
    private final Map<String, Command> delegates;

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Map<String, Command> map = Collections.synchronizedMap(new HashMap<>());

        private Builder() {}

        public Builder withCommand(String name, Command command) {
            if (command == null) {
                throw new IllegalArgumentException(name);
            }
            map.compute(name, (k, v) -> {
                if (v == null) {
                    return command;
                }
                throw new IllegalArgumentException("There is already a command with name " + k);
            });
            return this;
        }

        public DelegatingCommand build() {
            return new DelegatingCommand(new HashMap<>(map));
        }
    }

    private DelegatingCommand(Map<String, Command> delegates) {
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
