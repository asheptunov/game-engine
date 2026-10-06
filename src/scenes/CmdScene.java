package scenes;

import misc.monads.Result;
import ui.console.Command;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class CmdScene implements Command {
    @Override public String helpText() { return "Usage: scene <name>\n  Available: " + String.join(", ", new java.util.TreeSet<>(scenesByName.keySet())); }
    private final Map<String, Scene>       scenesByName;
    private final AtomicReference<Scene>   activeScene;

    public CmdScene(Map<String, Scene> scenesByName, AtomicReference<Scene> activeScene) {
        this.scenesByName = scenesByName;
        this.activeScene = activeScene;
    }

    @Override
    public Result<String, String> run(String... args) {
        if (args.length != 2) {
            return Result.failure("usage: scene <name>; available: " + scenesByName.keySet());
        }
        var name = args[1];
        var target = scenesByName.get(name);
        if (target == null) {
            return Result.failure("no scene: " + name + "; available: " + scenesByName.keySet());
        }
        var previous = activeScene.get();
        boolean focused=previous==null||previous.windowFocused();
        if (previous != null) previous.suspendInput();
        activeScene.set(target);
        target.windowFocus(focused);
        target.resumeInput();
        return Result.success("switched to " + name);
    }
}
