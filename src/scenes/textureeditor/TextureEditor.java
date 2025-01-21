package scenes.textureeditor;

import logging.LogManager;
import logging.Logger;
import misc.monads.Result;
import rendering.ChromaKey;
import rendering.Color;
import rendering.ColorPicker;
import rendering.FileSystemRasterRepository;
import rendering.Painter;
import rendering.PixelRaster;
import rendering.Printer;
import rendering.Raster;
import rendering.RasterPainter;
import rendering.RasterPrinter;
import rendering.RasterRepository;
import rendering.Renderer;
import scenes.Scene;
import ui.FilteringInputBuffer;
import ui.TextInputBuffer;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// not thread safe. stateful. evil. thriving.
public class TextureEditor implements
        Scene,
        Renderer,
        KeyListener, MouseListener, MouseMotionListener, MouseWheelListener {
    private static final Logger  LOG                  = LogManager.instance().getThis();
    private static final State   DEFAULT_STATE        = State.BRUSH;
    private static final int     RASTER_HISTORY_SIZE  = 100;
    private static final int     COMMAND_HISTORY_SIZE = 500;
    private static final int     FONT_SIZE            = 24;
    private static final Pattern COLOR_PATTERN        = Pattern.compile("^(?<cmd>%s)$|^(0x|0X|)(?<hex>[0-9a-fA-F]{6})$"
            .formatted(String.join("|", Color.NamedColor.names())));
    private static final Pattern COLOR_CHAR_PATTERN   = Pattern.compile("[0-9a-zA-Z]+");
    private static final Pattern DIR_PATTERN          = Pattern.compile("[a-zA-Z0-9-_/]+");
    private static final Pattern TX_PATTERN           = Pattern.compile("[a-zA-Z0-9-_]+\\.tx");
    // todo resize command
    private static final Pattern CMD_PATTERN          = Pattern
            .compile("^\\/?status$|^\\/?(cd|ls)( (%s))?$|^\\/?(load|save)( (%s))?$|^\\/?(touch|rm) (%s)$"
                    .formatted(DIR_PATTERN.pattern(), TX_PATTERN.pattern(), TX_PATTERN.pattern()));
    private static final Pattern CMD_CHAR_PATTERN     = Pattern.compile("[ -~]+");
    private static final BiFunction<Integer, Double, Color>
                                 SELECTION_PATTERN    = (i, _) -> (i / 8) % 2 == 0 ? Color.BLACK : Color.WHITE;

    private final RasterRepository repo;
    private       Path             dirName  = Path.of("assets/fonts/test/standard");
    private       Path             filename = Path.of("");
    private       Raster           texture;
    private final Raster           display;
    private final Painter          displayPainter;
    private final Printer          displayPrinter;
    private final History<Raster>  rasterHistory;
    private final History<String>  commandHistory;
    private       State            state    = DEFAULT_STATE;
    private final Clock            clock;
    private       Selection        selection;
    private       Coordinates      boxStart;
    private final ColorPicker      colorPicker;
    private final TextInputBuffer  colorInputBuf;
    private final TextInputBuffer  commandInputBuf;

    private enum State {
        PIXEL_SELECT,
        BOX_SELECT,
        LASSO_SELECT,
        BRUSH,
        FILL,
        COLOR_PICKER,
        COMMAND_ENTRY
    }

    public TextureEditor(Raster display, Clock clock, int width, int height) {
        this.repo = new FileSystemRasterRepository(clock);
        this.texture = new PixelRaster(width, height, (_, _) -> Color.BLACK);
        this.display = display;
        this.displayPainter = new RasterPainter(display);
        this.displayPrinter = RasterPrinter.builder()
                .raster(display)
                .repository(repo)
                .clock(clock)
                .fontPath("assets/fonts/test")
                .fontDimensions(16)
                .filter(ChromaKey.of(Color.BLACK))
                .build();
        this.rasterHistory = new CircularBufferHistoryImpl<>(this.texture.clone(), RASTER_HISTORY_SIZE);
        this.commandHistory = new CircularBufferHistoryImpl<>("", COMMAND_HISTORY_SIZE);
        this.clock = clock;
        this.colorPicker = new ColorPicker(display, Color.WHITE);
        this.colorInputBuf = new FilteringInputBuffer("color input",
                COLOR_PATTERN,
                COLOR_CHAR_PATTERN,
                (_, str) -> {
                    LOG.error("Invalid color code: %s", str);
                    // retain buffer on failure; give user a chance to correct it
                },
                (buf, matcher) -> {
                    colorPicker.set(getColor(matcher).orElseThrow());
                    LOG.info("Setting color to %s", colorPicker.get());
                    resetState();
                    buf.clear();  // retain buffer on success; speed up editing later
                },
                buf -> {
                    resetState();
                    buf.set("0x%s".formatted(colorPicker.get().rgbInt24()));  // reset to active color on escape
                }
        ) {{
            set("0x%s".formatted(colorPicker.get().rgbInt24()));
        }};
        this.commandInputBuf = new FilteringInputBuffer("command line",
                CMD_PATTERN,
                CMD_CHAR_PATTERN,
                (buf, str) -> {
                    commandHistory.record(str);
                    LOG.error("Invalid command: %s", str);
                    buf.clear(); // clear buffer on failure, like in a standard terminal
                },
                (buf, matcher) -> {
                    commandHistory.record(matcher.group());
                    parseCommand(matcher);
                    buf.clear();  // clear buffer on success, like in a standard terminal
                },
                _ -> resetState()  // retain buffer on escape; give user a chance to continue later
        );
    }

    @Override
    public void keyTyped(KeyEvent e) {
        LOG.trace("Handling %s", e);
        switch (state) {
            case COLOR_PICKER:
                colorInputBuf.accept(e);
                break;
            case COMMAND_ENTRY:
                commandInputBuf.accept(e);
                break;
        }
    }

    @Override
    public void keyPressed(KeyEvent e) {
        LOG.trace("Handling %s", e);
        switch (state) {
            case PIXEL_SELECT:
            case LASSO_SELECT:
            case BOX_SELECT:
            case BRUSH:
            case FILL:
                switchState(switch (e.getKeyCode()) {
                    case KeyEvent.VK_Q -> State.PIXEL_SELECT;
                    case KeyEvent.VK_W -> State.BOX_SELECT;
                    case KeyEvent.VK_E -> State.LASSO_SELECT;
                    case KeyEvent.VK_R -> State.BRUSH;
                    case KeyEvent.VK_T -> State.FILL;
                    case KeyEvent.VK_C -> {
                        colorInputBuf.set("0x%x".formatted(colorPicker.get().rgbInt24()));
                        yield State.COLOR_PICKER;
                    }
                    case KeyEvent.VK_SLASH -> State.COMMAND_ENTRY;
                    default -> this.state;
                });
                handleGlobalActions(e);
                break;
            case COLOR_PICKER:
                colorInputBuf.accept(e);
                break;
            case COMMAND_ENTRY:
                commandInputBuf.accept(e);
                break;
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        LOG.trace("Handling %s", e);
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        LOG.trace("Handling %s", e);
    }

    @Override
    public void mousePressed(MouseEvent e) {
        LOG.trace("Handling %s", e);
        if (e.getButton() != MouseEvent.BUTTON1) {
            return;
        }
        var c = normalize(e);
        int x = c.x;
        int y = c.y;
        // todo lasso select
        switch (state) {
            case PIXEL_SELECT -> {
                LOG.info("Selected pixel %s", c);
                selection = new PixelSelection(c);
            }
            case BOX_SELECT -> {
                LOG.info("Started box selection at %s", c);
                selection = new BoxSelection(x, y);
                boxStart = new Coordinates(x, y);
            }
            case LASSO_SELECT -> throw new UnsupportedOperationException("start lasso select");
            case BRUSH -> {
                // selection acts as a mask
                switch (selection) {
                    case null -> texture.setPixel(x, y, colorPicker.get());
                    case PixelSelection px -> {
                        if (px.is(x, y)) {
                            texture.setPixel(x, y, colorPicker.get());
                        }
                    }
                    case BoxSelection box -> {
                        if (box.contains(x, y)) {
                            texture.setPixel(x, y, colorPicker.get());
                        }
                    }
                    case LassoSelection lasso -> {
                        if (lasso.contains(c)) {
                            texture.setPixel(x, y, colorPicker.get());
                        }
                    }
                }
            }
            case FILL -> {
                // selection acts as an invert toggle
                switch (selection) {
                    case null -> {
                        fillEverything(colorPicker.get());
                        saveToHistory();
                    }
                    case PixelSelection px -> {
                        fillPixel(px, colorPicker.get(), !px.is(x, y));
                        saveToHistory();
                    }
                    case BoxSelection box -> {
                        fillBox(box, colorPicker.get(), !box.contains(x, y));
                        saveToHistory();
                    }
                    case LassoSelection lasso -> {
                        fillLasso(lasso, colorPicker.get(), !lasso.contains(c));
                        saveToHistory();
                    }
                }
            }
            case COLOR_PICKER -> colorPicker.accept(e);
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        LOG.trace("Handling %s", e);
        if (e.getButton() != MouseEvent.BUTTON1) {
            return;
        }
        var c = normalize(e);
        int x = c.x;
        int y = c.y;
        switch (state) {
            case BOX_SELECT -> {
                var box = (BoxSelection) selection;
                box.update(boxStart.x, boxStart.y, x, y);
                LOG.info("Finished box selection from [%d, %d] at [%d, %d]", boxStart.x, boxStart.y, x, y);
                boxStart = null;
            }
            case LASSO_SELECT -> throw new UnsupportedOperationException("terminate lasso select");
            case BRUSH -> saveToHistory();
            case COLOR_PICKER -> colorPicker.accept(e);
        }
    }

    @Override
    public void mouseEntered(MouseEvent e) {
        LOG.trace("Handling %s", e);
    }

    @Override
    public void mouseExited(MouseEvent e) {
        LOG.trace("Handling %s", e);
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        LOG.trace("Handling %s", e);
        var c = normalize(e);
        int x = c.x;
        int y = c.y;
        switch (state) {
            case BOX_SELECT -> {
                var box = (BoxSelection) selection;
                box.update(boxStart.x, boxStart.y, x, y);
                LOG.debug("Updating box selection to %s", box);
            }
            case LASSO_SELECT -> throw new UnsupportedOperationException("update lasso selection");
            case BRUSH -> {
                LOG.debug("Painting pixel [%d, %d]", x, y);
                switch (selection) {
                    case null -> texture.setPixel(x, y, colorPicker.get());
                    case PixelSelection px -> {
                        if (px.is(x, y)) {
                            texture.setPixel(x, y, colorPicker.get());
                        }
                    }
                    case BoxSelection box -> {
                        if (box.contains(x, y)) {
                            texture.setPixel(x, y, colorPicker.get());
                        }
                    }
                    case LassoSelection lasso -> {
                        if (lasso.contains(c)) {
                            texture.setPixel(x, y, colorPicker.get());
                        }
                    }
                }
            }
            case COLOR_PICKER -> colorPicker.accept(e);
        }
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        LOG.trace("Handling %s", e);
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        LOG.trace("Handling %s", e);
        // todo zoom / pan
    }

    @Override
    public void render() {
        LOG.trace("Rendering");
        // todo zoom / pan
        renderTexture();
        renderSelection();
        if (state == State.COLOR_PICKER) {
            colorPicker.render();
            renderColorBuffer();
        }
        if (state == State.COMMAND_ENTRY) {
            renderConsole();
        }
    }

    private void renderTexture() {
        var scaledTexture = texture.scale(display.width(), display.height());
        displayPainter.drawImg(0, 0, scaledTexture);
    }

    private void renderSelection() {
        var xScale = 1. * display.width() / texture.width();
        var yScale = 1. * display.height() / texture.height();
        switch (selection) {
            case null -> {}
            case PixelSelection px -> {
                int l = (int) (px.px.x * xScale);
                int t = (int) (px.px.y * yScale);
                int r = (int) ((px.px.x + 1) * xScale) - 1;
                int b = (int) ((px.px.y + 1) * yScale) - 1;
                displayPainter.drawLine(l, t, r, t, SELECTION_PATTERN);
                displayPainter.drawLine(l, t, l, b, SELECTION_PATTERN);
                displayPainter.drawLine(r, t, r, b, SELECTION_PATTERN);
                displayPainter.drawLine(l, b, r, b, SELECTION_PATTERN);
            }
            case BoxSelection box -> {
                int l = (int) (box.tl.x * xScale);
                int t = (int) (box.tl.y * yScale);
                int r = (int) ((box.br.x + 1) * xScale) - 1;
                int b = (int) ((box.br.y + 1) * yScale) - 1;
                displayPainter.drawLine(l, t, r, t, SELECTION_PATTERN);
                displayPainter.drawLine(l, t, l, b, SELECTION_PATTERN);
                displayPainter.drawLine(r, t, r, b, SELECTION_PATTERN);
                displayPainter.drawLine(l, b, r, b, SELECTION_PATTERN);
            }
            default -> throw new UnsupportedOperationException(selection.getClass().getName());
        }
    }

    private void renderConsole() {
        int maxLineWidth = this.display.width() / FONT_SIZE;

        // newest -> oldest
        var lines = Stream.concat(Stream.of(commandInputBuf.get()), commandHistory.getPast().stream()).toList();
        int x = 0;
        int y = display.height() - FONT_SIZE;
        for (var line : lines) {
            var linesWithoutNewlines = line.split("\n", -1);  // apply newlines
            for (var lineWithoutNewlines : linesWithoutNewlines) {
                var lineWithoutCarriageReturns = renderCarriageReturns(lineWithoutNewlines);
                var chars = lineWithoutCarriageReturns.toCharArray();
                int n = chars.length;
                if (n == 0) {  // empty line (edge case)
                    y -= FONT_SIZE;
                    continue;
                }
                int rem = n % maxLineWidth;
                var sb = new StringBuilder();
                for (int i = n - rem; i < n; ++i) {  // print trailing line
                    sb.append(chars[i]);
                }
                if (!sb.isEmpty()) {
                    displayPrinter.print(sb.toString(), x, y, Printer.Size.of(FONT_SIZE));
                    y -= FONT_SIZE;
                    sb.setLength(0);
                }
                for (int i = n - rem - maxLineWidth; i >= 0; i -= maxLineWidth) {  // print wrapped lines in reverse
                    for (int j = i; j < i + maxLineWidth; ++j) {
                        sb.append(chars[j]);
                    }
                    displayPrinter.print(sb.toString(), x, y, Printer.Size.of(FONT_SIZE));
                    y -= FONT_SIZE;
                    sb.setLength(0);
                }
            }
        }
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

    private void renderColorBuffer() {
        int y = display.height() - FONT_SIZE;
        var color = getColor(colorInputBuf.matcher()).orElse(Color.WHITE);
        displayPrinter.print(colorInputBuf.get(), 0, y, Printer.Size.of(FONT_SIZE), Printer.Color.of(color));
    }

    private Coordinates normalize(MouseEvent e) {
        // todo zoom / pan
        int x = (int) (1. * Math.min(e.getX(), display.width() - 1) / display.width() * texture.width());
        int y = (int) (1. * Math.min(e.getY(), display.height() - 1) / display.height() * texture.height());
        return new Coordinates(x, y);
    }

    private static class Coordinates {
        private int x;
        private int y;

        private Coordinates(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public String toString() {
            return "[" + x + ", " + y + "]";
        }
    }

    public sealed interface Selection permits PixelSelection, BoxSelection, LassoSelection {}

    record PixelSelection(Coordinates px) implements Selection {
        boolean is(int x, int y) {
            return x == px.x && y == px.y;
        }
    }

    record BoxSelection(Coordinates tl, Coordinates br) implements Selection {
        BoxSelection(int x, int y) {
            this(new Coordinates(x, y), new Coordinates(x, y));
        }

        BoxSelection(int x1, int y1, int x2, int y2) {
            this(x1, y1);
            update(x1, y1, x2, y2);
        }

        void update(int x1, int y1, int x2, int y2) {
            int xMin, yMin, xMax, yMax;
            if (x1 < x2) {
                xMin = x1;
                xMax = x2;
            } else {
                xMin = x2;
                xMax = x1;
            }
            if (y1 < y2) {
                yMin = y1;
                yMax = y2;
            } else {
                yMin = y2;
                yMax = y1;
            }
            tl.x = xMin;
            tl.y = yMin;
            br.x = xMax;
            br.y = yMax;
        }

        boolean contains(int x, int y) {
            return x >= tl.x && x <= br.x && y >= tl.y && y <= br.y;
        }
    }

    record LassoSelection(Set<Coordinates> all) implements Selection {
        boolean contains(Coordinates c) {
            return all.contains(c);
        }

        @Override
        public String toString() {
            return "LassoSelection (size=" + all.size() + ")";
        }
    }

    private void handleGlobalActions(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_ESCAPE -> {
                if (selection != null) {
                    LOG.info("Erasing selection %s", selection);
                    selection = null;
                }
            }
            case KeyEvent.VK_F1 -> toggleHelp();
            case KeyEvent.VK_F5 -> saveToFile(this.filename);
            case KeyEvent.VK_F9 -> loadFromFile(this.filename);
            case KeyEvent.VK_Z -> {
                switch (e.getModifiersEx()) {
                    case KeyEvent.CTRL_DOWN_MASK -> undo();
                    case KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK -> redo();
                }
            }
            case KeyEvent.VK_A -> {
                if (e.getModifiersEx() == KeyEvent.CTRL_DOWN_MASK) {
                    LOG.info("Selecting all");
                    selection = new BoxSelection(0, 0, texture.width() - 1, texture.height() - 1);
                }
            }
        }
    }

    private void toggleHelp() {
        LOG.info("Current state: %s.", state)
                .info("Keymap:")
                .info("F1: show help")
                .info("F5: save to file (current path: %s)", dirName.resolve(filename))
                .info("q: %s", State.PIXEL_SELECT)
                .info("w: %s", State.BOX_SELECT)
                .info("e: %s", State.LASSO_SELECT)
                .info("r: %s", State.BRUSH)
                .info("t: %s", State.FILL)
                .info("c: input color (current input buffer: [%s])", colorInputBuf.toString())
                .info("/: command entry")
                .info("CTRL+z: undo")
                .info("CTRL+SHIFT+z: redo");
        // todo actually implement this
    }

    private Result<Void, Exception> saveToFile(Path filename) {
        return repo.save(dirName.resolve(filename), texture);
    }

    private Result<Raster, Exception> loadFromFile(Path filename) {
        return repo.load(dirName.resolve(filename))
                .ifSuccess(raster -> {
                    this.texture = raster;
                    saveToHistory();
                });
    }

    private void saveToHistory() {
        rasterHistory.record(texture.clone());
    }

    private void undo() {
        var prev = rasterHistory.goBack();
        if (prev.isPresent()) {
            texture = prev.get().clone();
            LOG.info("Undid last action");
        } else {
            LOG.info("At undo limit");
        }
    }

    private void redo() {
        var next = rasterHistory.goForward();
        if (next.isPresent()) {
            texture = next.get().clone();
            LOG.info("Redid last undone action");
        } else {
            LOG.info("At redo limit");
        }
    }

    private void fillEverything(Color color) {
        LOG.info("Filling everything with color 0x%s", color);
        for (int r = 0; r < texture.height(); ++r) {
            for (int c = 0; c < texture.width(); ++c) {
                texture.setPixel(c, r, color);
            }
        }
    }

    private void fillPixel(PixelSelection px, Color color, boolean inverse) {
        LOG.info("Filling %s pixel %s with color %s", inverse ? "everything outside" : "only", px, color);
        if (inverse) {
            for (int r = 0; r < texture.height(); ++r) {
                for (int c = 0; c < texture.width(); ++c) {
                    if (px.px.x != c && px.px.y != r) {
                        texture.setPixel(c, r, color);
                    }
                }
            }
        } else {
            texture.setPixel(px.px.x, px.px.y, color);
        }
    }

    private void fillBox(BoxSelection box, Color color, boolean inverse) {
        LOG.info("Filling everything %s box %s with color %s", inverse ? "outside" : "inside", box, color);
        int l = box.tl.x;
        int r = box.br.x;
        int t = box.tl.y;
        int b = box.br.y;
        if (inverse) {
            for (int y = 0; y < t; ++y) {
                for (int x = 0; x < texture.width(); ++x) {
                    texture.setPixel(x, y, color);
                }
            }
            for (int y = t; y <= b; ++y) {
                for (int x = 0; x < l; ++x) {
                    texture.setPixel(x, y, color);
                }
                for (int x = r + 1; x < texture.width(); ++x) {
                    texture.setPixel(x, y, color);
                }
            }
            for (int y = b + 1; y < texture.height(); ++y) {
                for (int x = 0; x < texture.width(); ++x) {
                    texture.setPixel(x, y, color);
                }
            }
        } else {
            for (int y = t; y <= b; ++y) {
                for (int x = l; x <= r; ++x) {
                    texture.setPixel(x, y, color);
                }
            }
        }
    }

    private void fillLasso(LassoSelection lasso, Color color, boolean inverse) {
        LOG.info("Filling everything %s lasso %s with color %s", inverse ? "outside" : "inside", lasso, color);
        if (inverse) {
            for (int r = 0; r < texture.height(); ++r) {
                for (int c = 0; c < texture.width(); ++c) {
                    if (lasso.contains(new Coordinates(c, r))) {
                        texture.setPixel(c, r, color);
                    }
                }
            }
        } else {
            for (Coordinates px : lasso.all) {
                texture.setPixel(px.x, px.y, color);
            }
        }
    }

    private Optional<Color> getColor(Matcher matcher) {
        return !matcher.hasMatch()
                ? Optional.empty()
                : Optional.of(Optional.ofNullable(matcher.group("cmd"))
                .<Color>flatMap(Color.NamedColor::of)
                .orElseGet(() -> Color.RgbInt24Color.of(Integer.parseInt(matcher.group("hex"), 16))));
    }

    private void parseCommand(Matcher matcher) {
        var command = matcher.group();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        // todo actually implement these with on-screen stuff
        if (command.startsWith("status")) {
            LOG.info("Working dir: %s, open file: %s, width: %d, height: %s, color: %s",
                    dirName, filename, texture.width(), texture.height(), colorPicker);
        } else if (command.startsWith("cd") || command.startsWith("ls")) {
            var root = Path.of(".");
            var targetDirName = Optional.ofNullable(matcher.group(3))
                    .map(Path::of)
                    .map(this.dirName::resolve)
                    .orElse(switch (matcher.group(1)) {
                        case "cd" -> root;
                        case "ls" -> this.dirName;
                        default -> throw new IllegalStateException(matcher.group(1));
                    });
            var dir = targetDirName.toFile();
            if (!dir.exists()) {
                LOG.error("No such dir: %s", dir);
                return;
            }
            if (!dir.isDirectory()) {
                LOG.error("Not a dir: %s", dir);
                return;
            }
            try {
                var rootDir = root.toFile().getCanonicalPath();
                try {
                    if (!dir.getCanonicalPath().startsWith(rootDir)) {
                        LOG.error("Dir must be under at root dir: %s", dir);
                        return;
                    }
                } catch (IOException e) {
                    LOG.error("Failed to get canonical path for dir: %s", dir);
                    return;
                }
            } catch (IOException e) {
                LOG.error(e, "Failed to get canonical path for current working dir");
                return;
            }
            switch (matcher.group(1)) {
                case "cd" -> {
                    LOG.info("Set dir to %s", targetDirName);
                    this.dirName = targetDirName;
                }
                case "ls" -> {
                    Arrays.stream(Optional.ofNullable(targetDirName.toFile()
                                            .listFiles(f -> f.isDirectory() && DIR_PATTERN.matcher(f.getName()).find()))
                                    .orElseGet(() -> {
                                        LOG.error("Failed to list sub dirs of %s", targetDirName);
                                        return new File[0];
                                    }))
                            .sorted()
                            .forEach(subDir -> LOG.info("%s d %s",
                                    LocalDateTime.ofInstant(
                                            Instant.ofEpochMilli(subDir.lastModified()), clock.getZone()),
                                    subDir.getName()));
                    Arrays.stream(Optional.ofNullable(targetDirName.toFile()
                                            .listFiles(f -> f.isFile() && TX_PATTERN.matcher(f.getName()).find()))
                                    .orElseGet(() -> {
                                        LOG.info("Failed to list files under %s", targetDirName);
                                        return new File[0];
                                    }))
                            .sorted()
                            .forEach(texture -> LOG.info("%s f %s",
                                    LocalDateTime.ofInstant(
                                            Instant.ofEpochMilli(texture.lastModified()), clock.getZone()),
                                    texture.getName()));
                }
            }
        } else if (command.startsWith("load")
                || command.startsWith("save")) {
            var targetFilename = Optional.ofNullable(matcher.group(6))
                    .map(Path::of)
                    .orElse(this.filename);
            switch (matcher.group(4)) {
                case "load" -> loadFromFile(targetFilename)
                        .ifSuccess(_ -> {
                            if (!this.filename.equals(targetFilename)) {
                                LOG.info("Set active file to %s", targetFilename);
                                this.filename = targetFilename;
                            }
                        });
                case "save" -> saveToFile(targetFilename)
                        .ifSuccess(_ -> {
                            if (!this.filename.equals(targetFilename)) {
                                LOG.info("Set active file to %s", targetFilename);
                                this.filename = targetFilename;
                            }
                        });
            }
        } else if (command.startsWith("touch ")
                || command.startsWith("rm ")) {
            var targetFilename = Path.of(matcher.group(8));
            targetFilename = this.dirName.resolve(targetFilename);
            switch (matcher.group(7)) {
                case "touch" -> repo.create(targetFilename,
                        new PixelRaster(texture.width(), texture.height(), (_, _) -> Color.BLACK));
                case "rm" -> repo.delete(targetFilename);
            }
        } else {
            throw new UnsupportedOperationException(command);
        }
    }

    private void switchState(State state) {
        if (this.state != state) {
            LOG.info("Switching states from %s to %s", this.state, state);
        }
        this.state = state;
    }

    private void resetState() {
        switchState(DEFAULT_STATE);
    }
}
