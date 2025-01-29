package scenes.textureeditor.console;

import logging.LogManager;
import logging.Logger;
import misc.monads.Result;
import misc.spliterators.ChunkedSpliterator;
import misc.spliterators.ReversedSpliterator;
import rendering.Color;
import rendering.PixelRaster;
import rendering.Printer;
import scenes.textureeditor.CircularBufferHistoryImpl;
import scenes.textureeditor.History;
import scenes.textureeditor.TextureEditor;
import ui.KeyAction;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseWheelEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static rendering.Color.AnsiColor;
import static rendering.Color.RgbInt24Color;

public class Console {
    private static final Logger LOG = LogManager.instance().getThis();

    // todo add resize command
    private static final Pattern CHAR_ACCEPT_LIST          = Pattern.compile("[ -~]+");
    private static final Pattern ANSI_COLOR_ESCAPE_PATTERN = Pattern.compile("\\\\033\\[(?<colors>[0-9]{1,3}(;[0-9]{1,3})*)m");


    private record CommandAndResult(String command, String result) {
        static CommandAndResult empty() {
            return new CommandAndResult(null, null);
        }
    }

    private final TextureEditor             editor;
    private final Printer                   printer;
    private final int                       hzStride;
    private final int                       vtStride;
    private final int                       maxLineWidth;
    private final int                       maxLines;
    private final History<CommandAndResult> history;
    private final StringBuilder             buf;
    private final Command                   cmd;

    private double vtOffset = 0;

    public Console(TextureEditor editor, int historySize) {
        this.editor = editor;
        this.printer = editor.printer();
        hzStride = editor.fontSize() + editor.charSpacing();
        vtStride = editor.fontSize() + editor.lineSpacing();
        maxLineWidth = editor.display().width() / hzStride;
        maxLines = editor.display().height() / vtStride;
        history = new CircularBufferHistoryImpl<>(CommandAndResult.empty(), historySize);
        buf = new StringBuilder();
        cmd = new TrimmingCommand(new DelegatingCommand(Map.of(
                "cd", new CmdCd(editor.state(), () -> Path.of(".")),
                "exit", new CmdExit(),
                "load", new CmdLoad(editor.state(), editor.repo()),
                "ls", new CmdLs(editor.state(), editor.clock()),
                "mkdir", new CmdMkdir(editor.state()),
                "pwd", new CmdPwd(editor.state()),
                "rm", new CmdRm(editor.state()),
                "save", new CmdSave(editor.state(), editor.repo()),
                "status", new CmdStatus(editor.state(), editor.colorPicker()),
                "touch", new CmdTouch(editor.state(), editor.repo(), () -> new PixelRaster(
                        editor.state().texture().width(),
                        editor.state().texture().height(),
                        (_, _) -> Color.NamedColor.BLACK))
        )));
    }

    public void accept(KeyAction keyAction) {
        vtOffset = 0;
        switch (keyAction.action()) {
            case KeyAction.Action.PRESS -> {
                if (keyAction.mods().none()) {
                    switch (keyAction.raw()) {
                        case ESCAPE -> editor.escape();
                        case BACKSPACE -> fastDeleteLast();
                        case ENTER -> {
                            var res = cmd.run(buf.toString().split(" ")).fold(
                                    s -> new CommandAndResult(buf.toString(), s),
                                    e -> new CommandAndResult(buf.toString(),
                                            AnsiColor.RED.formatted() + e + AnsiColor.NONE.formatted()));
                            history.record(res);
                            fastClear();
                        }
                        default -> keyAction.reified().character().ifPresent(this::fastAppend);
                    }
                } else if (keyAction.mods().ctrl() || keyAction.mods().meta()) {
                    switch (keyAction.raw()) {
                        case LOWER_C -> {
                            var str = buf.toString();
                            var sel = new StringSelection(str);
                            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
                            LOG.info("Copied buffer to clipboard: [%s]", str);
                        }
                        case LOWER_X -> {
                            var str = buf.toString();
                            var sel = new StringSelection(str);
                            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
                            fastClear();
                            LOG.info("Cut buffer to clipboard: [%s]", str);
                        }
                        case LOWER_V -> {
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
                        case BACKSPACE -> fastClear();
                    }
                }
            }
        }
    }

    public void accept(MouseWheelEvent e) {
        vtOffset -= e.getPreciseWheelRotation();
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
        var allChars = concatAllChars();
        var ansiSequences = computeAnsiSequences(allChars);
        var displayLines = convertToDisplayLines(allChars, ansiSequences);
        renderLines(displayLines);
    }

    private String concatAllChars() {
        return Stream.concat(
                // history, oldest first (reverse because history.past() returns newest first)
                ReversedSpliterator.reverse(history.getPast()).stream().flatMap(cnr -> Stream.concat(
                        Optional.ofNullable(cnr.command()).stream(),
                        Optional.ofNullable(cnr.result()).stream()
                )),
                // current buf is last line
                Stream.of(buf.toString())
        ).collect(Collectors.joining("\n"));
    }

    private ArrayList<AnsiSequence> computeAnsiSequences(String all) {
        var ansiSequences = new ArrayList<AnsiSequence>();
        //noinspection StatementWithEmptyBody
        for (var ansiMatcher = ANSI_COLOR_ESCAPE_PATTERN.matcher(all);
             ansiMatcher.find();
             ansiSequences.addLast(parseAnsiEscape(ansiMatcher))) {}
        return ansiSequences;
    }

    private List<List<StyledChar>> convertToDisplayLines(String allChars, List<AnsiSequence> ansiSequences) {
        int rowI = 0;
        int allI = -1;
        var style = new Printer.Style[0];
        var lines = new ArrayList<List<StyledChar>>() {{
            add(new ArrayList<>());
        }};
        for (char c : allChars.toCharArray()) {
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
                                ChunkedSpliterator.chunk(  // wrap the current line before committing it
                                                lines.removeLast().iterator(), maxLineWidth, ArrayList::new)
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
        int n = lines.size();
        for (var line : lines) {
            int col = 0;
            int y = (int) ((row - (n - maxLines) + vtOffset) * vtStride);
//            int y = (int) ((maxLines - 1 - row + vtOffset) * vtStride);
            for (var sc : line) {
                printer.print(sc.c(), col++ * hzStride, y, sc.styles());
            }
            ++row;
//            if (++row > maxLines) {
//                break;
//            }
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
