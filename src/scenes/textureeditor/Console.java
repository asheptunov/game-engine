package scenes.textureeditor;

import logging.LogManager;
import logging.Logger;
import misc.monads.Result;
import misc.spliterators.ChunkedSpliterator;
import misc.spliterators.ReversedSpliterator;
import rendering.Color;
import rendering.PixelRaster;
import rendering.Printer;
import rendering.Raster;
import rendering.RasterRepository;
import scenes.textureeditor.model.EditorState;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.KeyEvent;
import java.io.BufferedReader;
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
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
import static java.time.temporal.ChronoField.HOUR_OF_DAY;
import static java.time.temporal.ChronoField.MINUTE_OF_HOUR;
import static java.time.temporal.ChronoField.SECOND_OF_MINUTE;
import static rendering.Color.AnsiColor;
import static rendering.Color.NamedColor;
import static rendering.Color.RgbInt24Color;

public class Console {
    private static final Logger LOG = LogManager.instance().getThis();

    private static final Pattern DIR_PATTERN               = Pattern.compile("[a-zA-Z0-9-_/]+");
    private static final Pattern TX_PATTERN                = Pattern.compile("[a-zA-Z0-9-_]+\\.tx");
    // todo add resize command
    private static final Pattern CMD_ACCEPT_LIST           = Pattern.compile(
            "^\\/?status$|^\\/?(cd|ls)( (%s))?$|^\\/?(load|save)( (%s))?$|^\\/?(touch|rm) (%s)$".formatted(
                    DIR_PATTERN.pattern(), TX_PATTERN.pattern(), TX_PATTERN.pattern()));
    private static final Pattern CHAR_ACCEPT_LIST          = Pattern.compile("[ -~]+");
    private static final Pattern ANSI_COLOR_ESCAPE_PATTERN = Pattern.compile("\\\\033\\[(?<colors>[0-9]{1,3}(;[0-9]{1,3})*)m");

    private static final DateTimeFormatter LS_FORMATTER = new DateTimeFormatterBuilder()
            .append(ISO_LOCAL_DATE)
            .appendLiteral(' ')
            .appendValue(HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(MINUTE_OF_HOUR, 2)
            .optionalStart()
            .appendLiteral(':')
            .appendValue(SECOND_OF_MINUTE, 2)
            .toFormatter();

    private record CommandAndResult(String command, String result) {
        static CommandAndResult empty() {
            return new CommandAndResult("", "");
        }
    }

    private final TextureEditor             editor;
    private final RasterRepository          repo;
    private final Printer                   printer;
    private final int                       hzStride;
    private final int                       vtStride;
    private final int                       maxLineWidth;
    private final int                       maxLines;
    private final Clock                     clock;
    private final EditorState               state;
    private final History<CommandAndResult> history;
    private final StringBuilder             buf;


    public Console(TextureEditor editor, int historySize) {
        this.editor = editor;
        this.repo = editor.repo();
        Raster display = editor.display();
        this.printer = editor.printer();
        hzStride = editor.fontSize() + editor.charSpacing();
        vtStride = editor.fontSize() + editor.lineSpacing();
        maxLineWidth = display.width() / hzStride;
        maxLines = display.height() / vtStride;
        this.clock = editor.clock();
        this.state = editor.state();
        this.history = new CircularBufferHistoryImpl<>(CommandAndResult.empty(), historySize);
        this.buf = new StringBuilder();
    }

    public void accept(KeyEvent keystroke) {
        switch (keystroke.getID()) {
            case KeyEvent.KEY_TYPED: {
                fastAppend(keystroke.getKeyChar());
            }
            case KeyEvent.KEY_PRESSED: {
                switch (keystroke.getModifiersEx()) {
                    case KeyEvent.CTRL_DOWN_MASK, KeyEvent.META_DOWN_MASK -> {
                        switch (keystroke.getKeyCode()) {
                            case KeyEvent.VK_C -> {
                                var str = buf.toString();
                                var sel = new StringSelection(str);
                                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
                                LOG.info("Copied buffer to clipboard: [%s]", str);
                            }
                            case KeyEvent.VK_X -> {
                                var str = buf.toString();
                                var sel = new StringSelection(str);
                                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
                                fastClear();
                                LOG.info("Cut buffer to clipboard: [%s]", str);
                            }
                            case KeyEvent.VK_V -> {
                                var contents = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(this);
                                if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                                    try (var r = DataFlavor.stringFlavor.getReaderForText(contents)) {
                                        try (var br = new BufferedReader(r)) {
                                            var str = br.lines().collect(Collectors.joining("\n"));
                                            if (CHAR_ACCEPT_LIST.matcher(str).find()) {
                                                fastSet(str);
                                                LOG.info("Pasted buffer from clipboard: [%s]", str);
                                            }
                                        }
                                    } catch (UnsupportedFlavorException | IOException ex) {
                                        LOG.error(ex, "Failed to paste input from clipboard");
                                    }
                                }
                            }
                            case KeyEvent.VK_BACK_SPACE -> fastClear();
                        }
                    }
                    case 0 -> {
                        switch (keystroke.getKeyCode()) {
                            case KeyEvent.VK_ESCAPE -> editor.escape();
                            case KeyEvent.VK_ENTER -> {
                                var res = Result.<Matcher, Exception>success(matcher())
                                        .filter(Matcher::hasMatch, _ -> new Exception("Unknown command: " + buf))
                                        .flatMapSuccess(this::run)
                                        .recover(_ -> buf.toString().isBlank(), _ -> "")
                                        .fold(s -> new CommandAndResult(buf.toString(), s),
                                                e -> new CommandAndResult(buf.toString(),
                                                        AnsiColor.RED.formatted()
                                                                + e.getMessage()
                                                                + AnsiColor.NONE.formatted()));
                                history.record(res);
                                fastClear();
                            }
                            case KeyEvent.VK_BACK_SPACE -> fastDeleteLast();
                        }
                    }
                }
            }
        }
    }

    private record StyledChar(char c, Printer.Style... styles) {}

    private record AnsiSequence(int start, int end, Color color) {}

    private AnsiSequence parseAnsiEscape(Matcher matcher) {
        var colors = Arrays.stream(matcher.group("colors").split(";"))
                .mapToInt(Integer::parseInt)
                .toArray();
        var color = (Color) AnsiColor.NONE;
        if (colors.length == 5 && colors[0] == 38 && colors[1] == 2) {
            color = RgbInt24Color.of((byte) colors[2], (byte) colors[3], (byte) colors[4]);
        }
        if (colors.length == 1) {
            var c = AnsiColor.of(colors[0]);
            if (c.isPresent()) {
                color = c.get();
            }
        }
        return new AnsiSequence(matcher.start(), matcher.end(), color);
    }

    public void render() {
        // TODO mouse scroll
        var allChars = concatAllChars();
        var ansiSequences = computeAnsiSequences(allChars);
        var displayLines = convertToDisplayLines(allChars, ansiSequences);
        renderLines(displayLines);
    }

    private String concatAllChars() {
        return Stream.concat(
                        Stream.of(buf.toString()),
                        history.getPast().stream().flatMap(cnr
                                -> cnr.command().isBlank()
                                ? Stream.of("")
                                : Stream.of(cnr.result().strip(), cnr.command().strip())))
                .collect(Collectors.joining("\n"));
    }

    private ArrayList<AnsiSequence> computeAnsiSequences(String all) {
        var ansiSequences = new ArrayList<AnsiSequence>();
        //noinspection StatementWithEmptyBody
        for (var ansiMatcher = ANSI_COLOR_ESCAPE_PATTERN.matcher(all);
             ansiMatcher.find();
             ansiSequences.addLast(parseAnsiEscape(ansiMatcher))) {}
        return ansiSequences;
    }

    private List<List<StyledChar>> convertToDisplayLines(String all, List<AnsiSequence> ansiSequences) {
        int rowI = 0;
        int allI = -1;
        var style = new Printer.Style[0];
        var lines = new ArrayList<List<StyledChar>>() {{
            add(new ArrayList<>());
        }};
        for (char c : all.toCharArray()) {
            ++allI;
            if (!ansiSequences.isEmpty()
                    && allI >= ansiSequences.getFirst().start()
                    && allI < ansiSequences.getFirst().end()) {
                // within an ANSI escape sequence; ignore it
                continue;
            }
            if (!ansiSequences.isEmpty() && allI == ansiSequences.getFirst().end()) {
                // finished an escape sequence (pointing at the first char right after); update the color
                style = new Printer.Style[]{Printer.Color.of(ansiSequences.removeFirst().color())};
            }
            switch (c) {
                // not close to an escape sequence
                case '\r': {
                    rowI = 0;  // return carriage
                    break;
                }
                case '\n': {
                    if (!lines.getLast().isEmpty()) {  // this check prevents empty lines from being swallowed
                        lines.addAll(
                                ReversedSpliterator.reverse(  // flip wrapped lines (console starts at bottom)
                                                ChunkedSpliterator.chunk(  // wrap the current line before committing it
                                                        lines.removeLast().iterator(), maxLineWidth, ArrayList::new))
                                        .stream().toList());
                    }
                    lines.addLast(new ArrayList<>());  // start next line
                    rowI = 0;  // return carriage
                    break;
                }
                default: {
                    var line = lines.getLast();
                    var sc = new StyledChar(c, style);
                    if (rowI >= line.size()) {  // extend line
                        line.addLast(sc);
                    } else {  // within line (returned carriage earlier)
                        line.set(rowI, sc);
                    }
                    ++rowI;
                }
            }
        }
        return lines;
    }

    private void renderLines(List<List<StyledChar>> lines) {
        int row = 0;
        for (var line : lines) {
            int col = 0;
            int y = (maxLines - 1 - row) * vtStride;
            for (var sc : line) {
                printer.print(sc.c(), col++ * hzStride, y, sc.styles());
            }
//            painter.drawLine(0, y, display.width(), y, NamedColor.WHITE.withAlpha(.5f));
            if (++row > maxLines) {
                break;
            }
        }
    }

    Result<String, Exception> run(Matcher matcher) {
        var command = matcher.group();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.startsWith("status")) {
            return succeed("""
                            Working dir: %s
                            Open file: %s
                            Width: %d
                            Height: %d
                            Color: %s%#08X%s
                            """,
                    state.dirName().toAbsolutePath().toString(),
                    state.filename().map(Path::toAbsolutePath).map(Path::toString).orElse("<not set>"),
                    state.texture().width(),
                    state.texture().height(),
                    AnsiColor.formatted(editor.getColorPicker().getColor()),
                    editor.getColorPicker().getColor().argbInt32(),
                    AnsiColor.NONE.formatted());
        } else if (command.startsWith("cd") || command.startsWith("ls")) {
            var root = Path.of(".");
            var targetDirName = Optional.ofNullable(matcher.group(3))
                    .map(Path::of)
                    .map(state.dirName()::resolve)
                    .orElse(switch (matcher.group(1)) {
                        case "cd" -> root;
                        case "ls" -> state.dirName();
                        default -> throw new IllegalStateException(matcher.group(1));
                    });
            var dir = targetDirName.toFile();
            if (!dir.exists()) {
                return fail("No such dir: %s", dir);
            }
            if (!dir.isDirectory()) {
                return fail("Not a dir: %s", dir);
            }
            try {
                var rootDir = root.toFile().getCanonicalPath();
                try {
                    if (!dir.getCanonicalPath().startsWith(rootDir)) {
                        return fail("Dir must be under at root dir: %s", dir);
                    }
                } catch (IOException e) {
                    return fail("Failed to get canonical path for dir: %s", dir);
                }
            } catch (IOException e) {
                return fail(e, "Failed to get canonical path for current working dir");
            }
            return switch (matcher.group(1)) {
                case "cd" -> {
                    state.dirName(targetDirName);
                    yield succeed("Set dir to %s", targetDirName);
                }
                case "ls" -> {
                    var sb = new StringBuilder();
                    Arrays.stream(Optional.ofNullable(targetDirName.toFile()
                                            .listFiles(f -> f.isDirectory() && DIR_PATTERN.matcher(f.getName()).find()))
                                    .orElseGet(() -> {
                                        LOG.error("Failed to list sub dirs of %s", targetDirName);
                                        return new File[0];
                                    }))
                            .sorted(Comparator.reverseOrder())
                            .forEachOrdered(subDir -> sb.append("%s d %s%n".formatted(
                                    LocalDateTime.ofInstant(
                                                    Instant.ofEpochMilli(subDir.lastModified()), clock.getZone())
                                            .format(LS_FORMATTER),
                                    subDir.getName())));
                    Arrays.stream(Optional.ofNullable(targetDirName.toFile()
                                            .listFiles(f -> f.isFile() && TX_PATTERN.matcher(f.getName()).find()))
                                    .orElseGet(() -> {
                                        LOG.warn("Failed to list files under %s", targetDirName);
                                        return new File[0];
                                    }))
                            .sorted(Comparator.reverseOrder())
                            .forEach(texture -> sb.append("%s f %s%n".formatted(
                                    LocalDateTime.ofInstant(
                                                    Instant.ofEpochMilli(texture.lastModified()), clock.getZone())
                                            .format(LS_FORMATTER),
                                    texture.getName())));
                    yield succeed(sb.toString());
                }
                default -> fail("Unexpected value: " + matcher.group(1));
            };
        } else if (command.startsWith("load")
                || command.startsWith("save")) {
            var targetFilename = Optional.ofNullable(matcher.group(6))
                    .map(Path::of)
                    .orElseGet(() -> state.filename().orElseThrow());
            return switch (matcher.group(4)) {
                case "load" -> editor.loadFromFile(targetFilename)
                        .flatMapSuccess(_ -> {
                            if (state.filename().map(targetFilename::equals).orElse(true)) {
                                return succeed("Loaded from %s", targetFilename);
                            } else {
                                state.filename(targetFilename);
                                return succeed("Loaded from and set active file to %s", targetFilename);
                            }
                        });
                case "save" -> editor.saveToFile(targetFilename)
                        .flatMapSuccess(_ -> {
                            if (state.filename().map(targetFilename::equals).orElse(true)) {
                                return succeed("Saved to %s", targetFilename);
                            } else {
                                state.filename(targetFilename);
                                return succeed("Saved and set active file to %s", targetFilename);
                            }
                        });
                default -> fail("Unexpected value: " + matcher.group(4));
            };
        } else if (command.startsWith("touch ")
                || command.startsWith("rm ")) {
            var targetFilename = state.dirName().resolve(Path.of(matcher.group(8)));
            return switch (matcher.group(7)) {
                case "touch" -> repo.create(targetFilename, new PixelRaster(
                                state.texture().width(),
                                state.texture().height(),
                                (_, _) -> NamedColor.BLACK))
                        .flatMapSuccess(_ -> {
                            state.filename(targetFilename);
                            return succeed("Created and set active file to %s", targetFilename);
                        });
                case "rm" -> repo.delete(targetFilename)
                        .flatMapSuccess(_ -> {
                            state.clearFilename();
                            return succeed("Deleted and reset active file from %s", targetFilename);
                        });
                default -> fail("Unexpected value: " + matcher.group(7));
            };
        } else {
            return fail(new UnsupportedOperationException(command));
        }
    }

    private <T> Result<String, T> succeed(String fmt, Object... args) {
        return Result.success(String.format(fmt, args));
    }

    private <T> Result<T, Exception> fail(Exception e) {
        return Result.failure(e);
    }

    private <T> Result<T, Exception> fail(Exception e, String fmt, Object... args) {
        return Result.failure(new Exception(fmt.formatted(args), e));
    }

    private <T> Result<T, Exception> fail(String fmt, Object... args) {
        return Result.failure(new Exception(fmt.formatted(args)));
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private Matcher matcher() {
        var matcher = CMD_ACCEPT_LIST.matcher(buf.toString().strip());
        matcher.find();
        return matcher;
    }

    private void fastSet(String buffer) {
        fastClear();
        fastAppend(buffer);
    }

    private void fastClear() {
        buf.setLength(0);
    }

    private void fastAppend(char c) {
        fastAppend("" + c);
    }

    private void fastAppend(String str) {
        if (CHAR_ACCEPT_LIST.matcher(str).find()) {
            buf.append(str);
        }
    }

    private void fastDeleteLast() {
        if (!buf.isEmpty()) {
            buf.setLength(buf.length() - 1);
        }
    }
}
