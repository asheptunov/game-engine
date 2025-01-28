package scenes.textureeditor.console;

import misc.monads.Result;
import scenes.textureeditor.model.EditorState;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
import static java.time.temporal.ChronoField.HOUR_OF_DAY;
import static java.time.temporal.ChronoField.MINUTE_OF_HOUR;
import static java.time.temporal.ChronoField.SECOND_OF_MINUTE;

public class CmdLs implements Command {
    private static final DateTimeFormatter LAST_MODIFIED_FORMATTER = new DateTimeFormatterBuilder()
            .append(ISO_LOCAL_DATE)
            .appendLiteral(' ')
            .appendValue(HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(MINUTE_OF_HOUR, 2)
            .optionalStart()
            .appendLiteral(':')
            .appendValue(SECOND_OF_MINUTE, 2)
            .toFormatter();

    private final EditorState state;
    private final Clock       clock;

    public CmdLs(EditorState state, Clock clock) {
        this.state = state;
        this.clock = clock;
    }

    @Override
    public Result<String, String> run(String... args) {
        if (args.length > 2) {
            return Result.failure("Usage: ls [file|dir]");
        }
        var targetPath = args.length == 1
                ? state.workingDir().toPath()
                : state.workingDir().toPath().resolve(Path.of(args[1]));
        File targetFile;
        try {
            targetFile = targetPath.toFile().getCanonicalFile();
        } catch (IOException e) {
            return Result.failure("Unexpected error: " + e.getMessage());
        }
        if (!targetFile.exists()) {
            return Result.failure("No such dir: " + targetFile);
        }
        var lines = new ArrayList<String>();
        if (targetFile.isDirectory()) {
            var list = targetFile.listFiles();
            if (list == null) {
                return Result.failure("Failed to list files under dir: " + targetFile);
            }
            Arrays.stream(list)
                    .sorted(Comparator.comparing(File::isDirectory).reversed()  // directories first
                            .thenComparing(File::getName))
                    .map(this::format)
                    .forEachOrdered(lines::add);
        } else if (targetFile.isFile()) {
            lines.add(format(targetFile));
        }
        return Result.success(lines.isEmpty() ? null : String.join("\n", lines));
    }

    private String format(File f) {
        return "%s %s %s".formatted(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(f.lastModified()), clock.getZone())
                        .format(LAST_MODIFIED_FORMATTER),
                f.isDirectory() ? "d" : f.isFile() ? "f" : "?",
                f.getName()
        );
    }
}
