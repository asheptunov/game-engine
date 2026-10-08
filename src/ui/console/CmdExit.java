package ui.console;

import misc.monads.Result;

public class CmdExit implements Command {
    @Override
    public String helpText() {
        return "Usage: exit\n  Close the application.";
    }

    @Override
    public Result<String, String> run(String... args) {
        System.exit(0);
        return Result.success("Ciao");
    }
}
