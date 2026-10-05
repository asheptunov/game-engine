package ui.console;

import misc.monads.Result;

public interface Command {
    Result<String, String> run(String... args);

    default String helpText() { return "No additional help available."; }

    default Result<String, String> help(String... path) {
        return path.length == 0 ? Result.success(helpText()) : Result.failure("Unknown help topic: " + String.join(" ", path));
    }
}
