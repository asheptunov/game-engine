package ui.console;

import misc.monads.Result;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;

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
        if (name.equals("help")) return help(Arrays.copyOfRange(args, 1, args.length));
        if (!delegates.containsKey(name)) {
            return Result.failure("Unknown command: " + name);
        }
        var command = delegates.get(name);
        if (args.length > 1 && args[1].equals("help")) return command.help(Arrays.copyOfRange(args, 2, args.length));
        if (args.length > 1 && (args[args.length - 1].equals("help") || args[args.length - 1].equals("--help")))
            return command.help(Arrays.copyOfRange(args, 1, args.length - 1));
        return command.run(args);
    }

    @Override public Result<String, String> help(String... path) {
        if (path.length == 0) return Result.success("Commands:\n  " + String.join("\n  ", new java.util.TreeSet<>(delegates.keySet()))
                + "\nUse help <command> or <command> help.\nUp/Down recalls commands; Esc closes the console.");
        var command = delegates.get(path[0]);
        return command == null ? Result.failure("Unknown command: " + path[0])
                : command.help(Arrays.copyOfRange(path, 1, path.length));
    }
}
