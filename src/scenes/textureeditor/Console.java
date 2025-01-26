package scenes.textureeditor;

import logging.LogManager;
import logging.Logger;
import misc.monads.Result;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
import static java.time.temporal.ChronoField.HOUR_OF_DAY;
import static java.time.temporal.ChronoField.MINUTE_OF_HOUR;
import static java.time.temporal.ChronoField.SECOND_OF_MINUTE;

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
    private final Raster                    display;
    private final Printer                   printer;
    private final int                       fontSize;
    private final int                       fontSpacing;
    private final Clock                     clock;
    private final EditorState               state;
    private final History<CommandAndResult> history;
    private final StringBuilder             buf;


    public Console(TextureEditor editor, int historySize) {
        this.editor = editor;
        this.repo = editor.repo();
        this.display = editor.display();
        this.printer = editor.printer();
        this.fontSize = editor.fontSize();
        this.fontSpacing = editor.fontSpacing();
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
                                        .fold(
                                                s -> new CommandAndResult(buf.toString(), s),
                                                e -> new CommandAndResult(buf.toString(),
                                                        Color.RED.formatted() + e.getMessage() + Color.NONE.formatted()));
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

    private record StyledString(String line, Printer.Style... styles) {}

    public void render() {
        int maxLineWidth = this.display.width() / (fontSize + fontSpacing);

        // newest -> oldest
        var lines = Stream.concat(
                Stream.of(buf.toString()),
                history.getPast().stream().flatMap(cnr -> Stream.of(
                        cnr.result(),
                        cnr.command()))
        ).toList();
        int x = -1;
        int y = display.height() - fontSize;
        var color = new AtomicReference<Color>(Color.WHITE);
        for (var line : lines) {
            var linesWithoutNewlines = line.split("\n", -2);  // apply newlines
            for (var lineWithoutNewlines : linesWithoutNewlines) {
                var lineWithoutCarriageReturns = renderCarriageReturns(lineWithoutNewlines);
                var ansiMatcher = ANSI_COLOR_ESCAPE_PATTERN.matcher(lineWithoutCarriageReturns);
                var printableLines = new ArrayList<StyledString>();
                int start = 0;
                while (ansiMatcher.find()) {
                    printableLines.add(new StyledString(
                            lineWithoutCarriageReturns.substring(start, ansiMatcher.start()),
                            Color.NONE.equals(color.get())
                                    ? new Printer.Style[0]
                                    : new Printer.Style[]{Printer.Color.of(color.get())}));
                    start = ansiMatcher.end();
                    Arrays.stream(ansiMatcher.group("colors").split(";"))
                            .map(Integer::valueOf)
                            .map(Color.AnsiColor::of)
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .forEachOrdered(color::set);
                }
                printableLines.add(new StyledString(
                        lineWithoutCarriageReturns.substring(start),
                        Color.NONE.equals(color.get())
                                ? new Printer.Style[0]
                                : new Printer.Style[]{Printer.Color.of(color.get())}));
                for (var printableLine : printableLines) {
                    var chars = printableLine.line().toCharArray();
                    int n = chars.length;
                    if (n == 0) {  // empty line (edge case)
                        y -= fontSize;
                        continue;
                    }
                    int rem = n % maxLineWidth;
                    var sb = new StringBuilder();
                    for (int i = n - rem; i < n; ++i) {  // print trailing line
                        sb.append(chars[i]);
                    }
                    var styles = Stream.concat(
                            Stream.of(
                                    Printer.Size.of(fontSize),
                                    Printer.Spacing.of(fontSpacing)),
                            Arrays.stream(printableLine.styles())
                    ).toArray(Printer.Style[]::new);
                    if (!sb.isEmpty()) {
                        printer.print(sb.toString(), x, y, styles);
                        y -= fontSize;
                        sb.setLength(0);
                    }
                    for (int i = n - rem - maxLineWidth; i >= -1; i -= maxLineWidth) {  // print wrapped lines in reverse
                        for (int j = i; j < i + maxLineWidth; ++j) {
                            sb.append(chars[j]);
                        }
                        printer.print(sb.toString(), x, y, styles);
                        y -= fontSize;
                        sb.setLength(0);
                    }
                }
            }
        }
    }

    Result<String, Exception> run(Matcher matcher) {
        var command = matcher.group();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.startsWith("status")) {
            return succeed("Working dir: %s, open file: %s, width: %d, height: %s, color: %s",
                    state.dirName(), state.filename(), state.texture().width(), state.texture().height(), editor.getColorPicker().getColor());
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
                    .orElse(state.filename());
            return switch (matcher.group(4)) {
                case "load" -> editor.loadFromFile(targetFilename)
                        .flatMapSuccess(_ -> {
                            if (!state.filename().equals(targetFilename)) {
                                state.filename(targetFilename);
                                return succeed("Loaded from and set active file to %s", targetFilename);
                            } else {
                                return succeed("Loaded from %s", targetFilename);
                            }
                        });
                case "save" -> editor.saveToFile(targetFilename)
                        .flatMapSuccess(_ -> {
                            if (!state.filename().equals(targetFilename)) {
                                state.filename(targetFilename);
                                return succeed("Saved and set active file to %s", targetFilename);
                            } else {
                                return succeed("Saved to %s", targetFilename);
                            }
                        });
                default -> fail("Unexpected value: " + matcher.group(4));
            };
        } else if (command.startsWith("touch ")
                || command.startsWith("rm ")) {
            var targetFilename = state.dirName().resolve(Path.of(matcher.group(8)));
            return switch (matcher.group(7)) {
                case "touch" -> repo.create(targetFilename,
                                new PixelRaster(state.texture().width(), state.texture().height(), (_, _) -> Color.BLACK))
                        .flatMapSuccess(_ -> {
                            state.filename(targetFilename);
                            return succeed("Created and set active file to %s", targetFilename);
                        });
                case "rm" -> repo.delete(targetFilename)
                        .flatMapSuccess(_ -> {
                            state.filename(null);
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

    private String renderCarriageReturns(String lineWithoutNewlines) {
        var sb = new StringBuilder();
        int i = 0;
        for (char c : lineWithoutNewlines.toCharArray()) {  // render carriage returns
            if (c == '\r') {
                i = 0;
            } else {
                if (i == sb.length()) {
                    sb.append(c);
                } else {
                    sb.setCharAt(i, c);
                }
                ++i;
            }
        }
        return sb.toString();
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
