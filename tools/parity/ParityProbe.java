package game;

import engine.RenderEngine;
import java.nio.file.Files;
import java.nio.file.Path;

/** The first output line is machine-readable; details remain available for diagnostics. */
public final class ParityProbe {
    public static void main(String[] args) throws Exception {
        var availability = RenderEngine.probe("gpu");
        Files.writeString(Path.of(args[0]), availability.state() + "\n" + availability.detail() + "\n");
        System.out.println(availability);
    }
}
