package ui.console;

import static harness.Assertions.*;

import engine.ViewportState;
import engine.objects.Rect;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

import misc.monads.Result;

import rendering.PixelRaster;

import scenes.viewport.ScenePresets;
import scenes.viewport.ViewportCommand;

import ui.KeyAction;

import java.util.ArrayList;
import java.util.List;

public class ConsoleInteractionTest {
    private static void key(Console console, KeyAction.Key key) {
        console.accept(
                new KeyAction(
                        key,
                        key,
                        KeyAction.Action.PRESS,
                        new KeyAction.Modifiers(
                                false, false, false, false, false, false, false, false)));
    }

    private static void type(Console console, String text) {
        for (char c : text.toCharArray()) {
            var k =
                    java.util.Arrays.stream(KeyAction.Key.values())
                            .filter(v -> v.character().orElse('\0') == c)
                            .findFirst()
                            .orElseThrow();
            key(console, k);
        }
    }

    private static void enter(Console console, String text) {
        type(console, text);
        key(console, KeyAction.Key.ENTER);
    }

    @Test
    void historyRestoresDraftAndEditsDoNotOverwriteSubmittedCommands() {
        var submitted = new ArrayList<String>();
        var console =
                Console.withAwtText(
                        new PixelRaster(500, 300),
                        () -> {},
                        10,
                        args -> {
                            submitted.add(String.join(" ", args));
                            return Result.success("ok");
                        });
        key(console, KeyAction.Key.UP);
        key(console, KeyAction.Key.DOWN);
        enter(console, "one");
        enter(console, "two");
        type(console, "draft");
        key(console, KeyAction.Key.UP);
        key(console, KeyAction.Key.UP);
        key(console, KeyAction.Key.UP);
        key(console, KeyAction.Key.DOWN);
        key(console, KeyAction.Key.DOWN);
        key(console, KeyAction.Key.DOWN);
        key(console, KeyAction.Key.ENTER);
        assertEquals(List.of("one", "two", "draft"), submitted);
        key(console, KeyAction.Key.UP);
        key(console, KeyAction.Key.BACKSPACE);
        type(console, "x");
        key(console, KeyAction.Key.ENTER);
        key(console, KeyAction.Key.UP);
        key(console, KeyAction.Key.UP);
        key(console, KeyAction.Key.ENTER);
        assertEquals(List.of("one", "two", "draft", "drafx", "draft"), submitted);
        enter(console, "   ");
        assertEquals(5, submitted.size());
    }

    @Test
    void historyCapacityAndFailedCommandsAreRecallable() {
        var submitted = new ArrayList<String>();
        var console =
                Console.withAwtText(
                        new PixelRaster(500, 300),
                        () -> {},
                        2,
                        args -> {
                            submitted.add(String.join(" ", args));
                            return Result.failure("failed");
                        });
        enter(console, "one");
        enter(console, "two");
        enter(console, "three");
        key(console, KeyAction.Key.UP);
        key(console, KeyAction.Key.UP);
        key(console, KeyAction.Key.UP);
        key(console, KeyAction.Key.ENTER);
        assertEquals(List.of("one", "two", "three", "two"), submitted);
    }

    @Test
    void helpRoutingNeverExecutesCommandsAndHasFocusedPages() {
        var st =
                new ViewportState(
                        new Rect(new Vec3(-.5f, -.5f, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)),
                        80,
                        80);
        ScenePresets.load(st, "playground");
        var view = new ViewportCommand(st, 1440, 900);
        var root =
                DelegatingCommand.builder()
                        .withCommand("view", view)
                        .withCommand("exit", new CmdExit())
                        .build();
        assertTrue(root.run("help").getSuccess().contains("view"));
        assertFalse(root.run("help").getSuccess().contains("absorption"));
        String resolution = root.run("view", "resolution", "help").getSuccess();
        assertEquals(resolution, root.run("help", "view", "resolution").getSuccess());
        assertEquals(resolution, root.run("view", "help", "resolution").getSuccess());
        assertEquals(resolution, root.run("view", "resolution", "--help").getSuccess());
        assertTrue(resolution.contains("0.5x"));
        assertFalse(resolution.contains("scattering"));
        assertTrue(root.run("view", "light", "help").getSuccess().contains("position"));
        assertTrue(
                root.run("view", "light", "position", "help")
                        .getSuccess()
                        .startsWith("Usage: view light position"));
        assertTrue(root.run("exit", "help").isSuccess()); // Must never invoke System.exit.
        assertTrue(root.run("help", "view", "unknown").isFailure());
        assertEquals(80, st.sensorPixelsW());
        assertEquals(0, st.pathDepth());
        assertEquals(
                "Updated resolution to 720x450.",
                root.run("view", "resolution", "half").getSuccess());
        assertEquals("Updated depth to 3.", root.run("view", "depth", "3").getSuccess());
        assertFalse(root.run("view", "light", "intensity", "120").getSuccess().contains("\n"));
        assertTrue(root.run("view", "status").getSuccess().contains("Objects="));
        String usage = root.run("view", "depth").getFailure();
        assertTrue(usage.contains("Usage: view depth"));
        assertFalse(usage.contains("absorption"));
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
