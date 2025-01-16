package scenes.textureeditor;

import logging.LogManager;
import logging.Logger;
import misc.monads.Result;
import rendering.CloneableRaster;
import rendering.Color;
import rendering.FileSystemRasterRepository;
import rendering.Painter;
import rendering.Printer;
import rendering.Raster;
import rendering.RasterFactory;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static misc.spliterators.ChunkedSpliterator.chunk;

// not thread safe. stateful. evil. thriving.
public class TextureEditor implements
        Scene,
        Renderer,
        KeyListener, MouseListener, MouseMotionListener, MouseWheelListener {
    private static final Logger  LOG                = LogManager.instance().getThis();
    private static final State   DEFAULT_STATE      = State.BRUSH;
    private static final int     HISTORY_SIZE       = 100;
    private static final Pattern COLOR_PATTERN      = Pattern.compile("^(?<cmd>%s)$|^(0x|0X|)(?<hex>[0-9a-fA-F]{1,6})$"
            .formatted(Arrays.stream(Color.values())
                    .map(Color::getCmdName)
                    .collect(Collectors.joining("|"))));
    private static final Pattern COLOR_CHAR_PATTERN = Pattern.compile("[0-9a-zA-Z]+");
    private static final Pattern DIR_PATTERN        = Pattern.compile("[a-zA-Z0-9-_/]+");
    private static final Pattern TX_PATTERN         = Pattern.compile("[a-zA-Z0-9-_]+\\.tx");
    // todo resize command
    private static final Pattern CMD_PATTERN        = Pattern
            .compile("^\\/?status$|^\\/?(cd|ls)( (%s))?$|^\\/?(load|save)( (%s))?$|^\\/?(touch|rm) (%s)$"
                    .formatted(DIR_PATTERN.pattern(), TX_PATTERN.pattern(), TX_PATTERN.pattern()));
    private static final Pattern CMD_CHAR_PATTERN   = Pattern.compile("[ -~]+");
    private static final BiFunction<Integer, Double, Integer>
                                 SELECTION_PATTERN  = (i, d) -> (i / 8) % 2 == 0 ? 0 : 0xffffff;

    private enum State {
        PIXEL_SELECT,
        BOX_SELECT,
        LASSO_SELECT,
        BRUSH,
        FILL,
        COLOR_ENTRY,
        COMMAND_ENTRY
    }

    private final RasterRepository         repo;
    private       Path                     dirName         = Path.of("assets/fonts/test/standard");
    private       Path                     filename        = Path.of("");
    private       CloneableRaster          texture;
    private final Raster                   display;
    private final Painter                  displayPainter;
    private final Printer                  displayPrinter;
    private final History<CloneableRaster> history;
    private       State                    state           = DEFAULT_STATE;
    private final Clock                    clock;
    private       Selection                selection;
    private       Coordinates              boxStart;
    private       int                      color           = Color.WHITE.getRgbValue();
    private final TextInputBuffer          colorInputBuf   = new FilteringInputBuffer("color input",
            COLOR_PATTERN,
            COLOR_CHAR_PATTERN,
            (buf, str) -> {
                LOG.error("Invalid color code: %s", str);
                // retain buffer on failure; give user a chance to correct it
            },
            (buf, matcher) -> {
                setColor(matcher);
                buf.clear();  // retain buffer on success; speed up editing later
            },
            buf -> {
                resetState();
                buf.set("0x%s".formatted(color));  // reset to active color on escape
            }
    ) {{
        set("0x%s".formatted(color));
    }};
    private final TextInputBuffer          commandInputBuf = new FilteringInputBuffer("command line",
            CMD_PATTERN,
            CMD_CHAR_PATTERN,
            (buf, str) -> {
                LOG.error("Invalid command: %s", str);
                buf.clear(); // clear buffer on failure, like in a standard terminal
            },
            (buf, matcher) -> {
                parseCommand(matcher);
                buf.clear();  // clear buffer on success, like in a standard terminal
            },
            buf -> resetState()  // retain buffer on escape; give user a chance to continue later
    );

    public TextureEditor(Raster display, Clock clock, int width, int height) {
        this.repo = new FileSystemRasterRepository(clock);
        this.texture = RasterFactory.cloneable(RasterFactory.create(width, height));
        this.display = display;
        this.displayPainter = new RasterPainter(display);
        this.displayPrinter = RasterPrinter.builder()
                .raster(display)
                .repository(repo)
                .clock(clock)
                .fontPath("assets/fonts/test")
                .fontDimensions(16, 16)
                .build();
        this.history = new CircularBufferHistoryImpl<>(this.texture.clone(), HISTORY_SIZE);
        this.clock = clock;
    }

    @Override
    public void keyTyped(KeyEvent e) {
        LOG.trace("Handling %s", e);
        switch (state) {
            case COLOR_ENTRY:
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
                        colorInputBuf.set("0x%x".formatted(color));
                        yield State.COLOR_ENTRY;
                    }
                    case KeyEvent.VK_SLASH -> State.COMMAND_ENTRY;
                    default -> this.state;
                });
                handleGlobalActions(e);
                break;
            case COLOR_ENTRY:
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
                selection = new TextureEditor.PixelSelection(c);
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
                    case null -> texture.setPixel(x, y, color);
                    case PixelSelection px -> {
                        if (px.is(x, y)) {
                            texture.setPixel(x, y, color);
                        }
                    }
                    case BoxSelection box -> {
                        if (box.contains(x, y)) {
                            texture.setPixel(x, y, color);
                        }
                    }
                    case LassoSelection lasso -> {
                        if (lasso.contains(c)) {
                            texture.setPixel(x, y, color);
                        }
                    }
                }
            }
            case FILL -> {
                // selection acts as an invert toggle
                switch (selection) {
                    case null -> {
                        fillEverything(color);
                        saveToHistory();
                    }
                    case PixelSelection px -> {
                        fillPixel(px, color, !px.is(x, y));
                        saveToHistory();
                    }
                    case BoxSelection box -> {
                        fillBox(box, color, !box.contains(x, y));
                        saveToHistory();
                    }
                    case LassoSelection lasso -> {
                        fillLasso(lasso, color, !lasso.contains(c));
                        saveToHistory();
                    }
                }
            }
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
                    case null -> texture.setPixel(x, y, color);
                    case PixelSelection px -> {
                        if (px.is(x, y)) {
                            texture.setPixel(x, y, color);
                        }
                    }
                    case BoxSelection box -> {
                        if (box.contains(x, y)) {
                            texture.setPixel(x, y, color);
                        }
                    }
                    case LassoSelection lasso -> {
                        if (lasso.contains(c)) {
                            texture.setPixel(x, y, color);
                        }
                    }
                }
            }
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
        if (state == State.COMMAND_ENTRY) {
            renderConsole();
        }
    }

    private void renderTexture() {
        var scaledTexture = RasterFactory.scalable(texture).scale(display.width(), display.height());
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
        int fontSize = 24;
        int maxLineWidth = this.display.width() / fontSize;
        var buffer = commandInputBuf.get();
        var lines = chunk(buffer.chars().boxed().iterator(), maxLineWidth, ArrayList::new)
                .stream()
                .map(line -> line.stream()
                        .map(i -> (Character) (char) (int) i)
                        .collect(StringBuilder::new, (sb, c) -> sb.append((char) c), StringBuilder::append)
                        .toString())
                .toList();
        int i = 0;
        for (var line : lines) {
            displayPrinter.print(line, 0, display.height() - ((lines.size() - i) * fontSize), fontSize);
            i++;
        }
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

    private Result<CloneableRaster, Exception> loadFromFile(Path filename) {
        return repo.load(dirName.resolve(filename))
                .mapSuccess(RasterFactory::cloneable)
                .ifSuccess(raster -> {
                    this.texture = raster;
                    saveToHistory();
                });
    }

    private void saveToHistory() {
        history.record(texture.clone());
    }

    private void undo() {
        var prev = history.goBack();
        if (prev.isPresent()) {
            texture = prev.get().clone();
            LOG.info("Undid last action");
        } else {
            LOG.info("At undo limit");
        }
    }

    private void redo() {
        var next = history.goForward();
        if (next.isPresent()) {
            texture = next.get().clone();
            LOG.info("Redid last undone action");
        } else {
            LOG.info("At redo limit");
        }
    }

    private void fillEverything(int color) {
        LOG.info("Filling everything with color 0x%s", color);
        for (int r = 0; r < texture.height(); ++r) {
            for (int c = 0; c < texture.width(); ++c) {
                texture.setPixel(c, r, color);
            }
        }
    }

    private void fillPixel(PixelSelection px, int color, boolean inverse) {
        LOG.info("Filling %s pixel %s with color 0x%x", inverse ? "everything outside" : "only", px, color);
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

    private void fillBox(BoxSelection box, int color, boolean inverse) {
        LOG.info("Filling everything %s box %s with color 0x%x", inverse ? "outside" : "inside", box, color);
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

    private void fillLasso(LassoSelection lasso, int color, boolean inverse) {
        LOG.info("Filling everything %s lasso %s with color 0x%x", inverse ? "outside" : "inside", lasso, color);
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

    private void setColor(Matcher matcher) {
        this.color = Optional.ofNullable(matcher.group("cmd"))
                .flatMap(Color::fromCmdName)
                .map(Color::getRgbValue)
                .orElseGet(() -> Integer.parseInt(matcher.group("hex"), 16));
        LOG.info("Setting color to 0x%x", color);
        resetState();
    }

    private void parseCommand(Matcher matcher) {
        var command = matcher.group();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        // todo actually implement these with on-screen stuff
        if (command.startsWith("status")) {
            LOG.info("Working dir: %s, open file: %s, width: %d, height: %s, color: 0x%x%s",
                    dirName, filename,
                    texture.width(), texture.height(),
                    color, Color.fromRgbValue(color).map(c -> " (" + c.getCmdName() + ")").orElse(""));
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
                        .ifSuccess($ -> {
                            if (!this.filename.equals(targetFilename)) {
                                LOG.info("Set active file to %s", targetFilename);
                                this.filename = targetFilename;
                            }
                        });
                case "save" -> saveToFile(targetFilename)
                        .ifSuccess($ -> {
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
                case "touch" -> repo.create(targetFilename, RasterFactory.create(texture.width(), texture.height()));
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
