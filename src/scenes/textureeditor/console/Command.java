package scenes.textureeditor.console;

import misc.monads.Result;

public interface Command {
    Result<String, String> run(String... args);
}
