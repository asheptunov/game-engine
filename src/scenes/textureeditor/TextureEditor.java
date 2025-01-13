package scenes.textureeditor;

import logging.LogManager;
import logging.Logger;
import scenes.Scene;
import rendering.CloneableRaster;
import rendering.Painter;
import rendering.Raster;
import rendering.RasterFactory;
import rendering.RasterPainter;
import rendering.Renderer;
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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// not thread safe. stateful. evil. thriving.
public class TextureEditor implements
        Scene,
        Renderer,
        KeyListener, MouseListener, MouseMotionListener, MouseWheelListener {
    private static final Logger  LOG                = LogManager.instance().getThis();
    private static final State   DEFAULT_STATE      = State.BRUSH;
    private static final int     HISTORY_SIZE       = 100;
    private static final Pattern COLOR_PATTERN      = Pattern.compile("^(0x|0X|)([0-9a-fA-F]{1,6})$");
    private static final Pattern COLOR_CHAR_PATTERN = Pattern.compile("[0-9a-fA-FxX]+");
    private static final Pattern DIR_PATTERN        = Pattern.compile("[a-zA-Z0-9-_/]+");
    private static final Pattern TX_PATTERN         = Pattern.compile("[a-zA-Z0-9-_]+\\.tx");
    private static final Pattern CMD_PATTERN        = Pattern
            .compile("^status$|^(cd|ls)( (%s))?$|^(load|save)( (%s))?$|^(touch|rm) (%s)$"
                    .formatted(DIR_PATTERN.pattern(), TX_PATTERN.pattern(), TX_PATTERN.pattern()));
    private static final Pattern CMD_CHAR_PATTERN   = Pattern.compile("[a-zA-Z0_/. ]+");
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

    private final Raster                   display;
    private final Painter                  displayPainter;
    private       CloneableRaster          texture;
    private final History<CloneableRaster> history;
    private       State                    state           = DEFAULT_STATE;
    private       Path                     dirName         = Path.of("assets/fonts/test/");
    private       Path                     filename        = Path.of("a.tx");
    private final Clock                    clock;
    private       Selection                selection;
    private       Coordinates              boxStart;
    private       int                      color           = 0;
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

    public TextureEditor(Raster display, Raster texture, Clock clock) {
        this.display = display;
        this.displayPainter = new RasterPainter(display);
        this.texture = RasterFactory.cloneable(texture);
        this.history = new CircularBufferHistoryImpl<>(this.texture.clone(), HISTORY_SIZE);
        this.clock = clock;
    }

    @Override
    public void keyTyped(KeyEvent e) {
        logEvent("typed", e);
    }

    @Override
    public void keyPressed(KeyEvent e) {
        logEvent("pressed", e);
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
        logEvent("released", e);
    }

    private static final Map<Integer, String> KEY_MOD_STRINGS = new LinkedHashMap<>() {
        {
            put(KeyEvent.CTRL_DOWN_MASK, "CTRL");
            put(KeyEvent.SHIFT_DOWN_MASK, "SHIFT");
            put(KeyEvent.ALT_DOWN_MASK, "ALT");
            put(KeyEvent.META_DOWN_MASK, "META");
            put(KeyEvent.ALT_GRAPH_DOWN_MASK, "ALT_GR");
            put(KeyEvent.BUTTON1_DOWN_MASK, "MB1");
            put(KeyEvent.BUTTON2_DOWN_MASK, "MB2");
            put(KeyEvent.BUTTON3_DOWN_MASK, "MB3");
        }
    };

    private void logEvent(String type, KeyEvent e) {
        var modStr = KEY_MOD_STRINGS.entrySet().stream()
                .filter(entry -> (e.getModifiersEx() & entry.getKey()) != 0)
                .map(Map.Entry::getValue)
                .collect(Collectors.joining(" + "));
        LOG.debug("Handling key %s (code=%d, char=%c, modifiers=%s)",
                type, e.getKeyCode(), (char) e.getKeyCode(), modStr.isEmpty() ? "none" : modStr);
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        logEvent("clicked", e);
    }

    @Override
    public void mousePressed(MouseEvent e) {
        logEvent("pressed", e);
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
            case LASSO_SELECT -> {
                throw new UnsupportedOperationException("start lasso select");
            }
            case BRUSH -> {
                // selection acts as a mask
                switch (selection) {
                    case null -> {
                        texture.setPixel(x, y, color);
                    }
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
        logEvent("released", e);
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
            case LASSO_SELECT -> {
                throw new UnsupportedOperationException("terminate lasso select");
            }
            case BRUSH -> saveToHistory();
        }
    }

    @Override
    public void mouseEntered(MouseEvent e) {
        logEvent("entered", e);
    }

    @Override
    public void mouseExited(MouseEvent e) {
        logEvent("exited", e);
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        logEvent("dragged", e);
        var c = normalize(e);
        int x = c.x;
        int y = c.y;
        switch (state) {
            case BOX_SELECT -> {
                var box = (BoxSelection) selection;
                box.update(boxStart.x, boxStart.y, x, y);
                LOG.debug("Updating box selection to %s", box);
            }
            case LASSO_SELECT -> {
                throw new UnsupportedOperationException("update lasso selection");
            }
            case BRUSH -> {
                LOG.debug("Painting pixel [%d, %d]", x, y);
                switch (selection) {
                    case null -> {
                        texture.setPixel(x, y, color);
                    }
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
        logEvent("moved", e);
    }

    private static final Map<Integer, String> MOUSE_MOD_STRINGS = new LinkedHashMap<>() {
        {
            put(MouseEvent.CTRL_DOWN_MASK, "CTRL");
            put(MouseEvent.SHIFT_DOWN_MASK, "SHIFT");
            put(MouseEvent.ALT_DOWN_MASK, "ALT");
            put(MouseEvent.META_DOWN_MASK, "META");
            put(MouseEvent.ALT_GRAPH_DOWN_MASK, "ALT_GR");
            put(MouseEvent.BUTTON1_DOWN_MASK, "MB1");
            put(MouseEvent.BUTTON2_DOWN_MASK, "MB2");
            put(MouseEvent.BUTTON3_DOWN_MASK, "MB3");
        }
    };

    private void logEvent(String type, MouseEvent e) {
        var c = normalize(e);
        var buttonStr = switch (e.getButton()) {
            case MouseEvent.NOBUTTON -> "none";
            case MouseEvent.BUTTON1 -> "MB1";
            case MouseEvent.BUTTON2 -> "MB2";
            case MouseEvent.BUTTON3 -> "MB3";
            default -> "unknown (code=" + e.getButton() + ")";
        };
        var modStr = MOUSE_MOD_STRINGS.entrySet().stream()
                .filter(entry -> (e.getModifiersEx() & entry.getKey()) != 0)
                .map(Map.Entry::getValue)
                .collect(Collectors.joining(" + "));
        LOG.debug("Handling mouse %s (raw=%s, tx=%s, button=%s, modifiers=%s)",
                type, new Coordinates(e.getX(), e.getY()), c, buttonStr, modStr.isEmpty() ? "none" : modStr);
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        // todo zoom / pan
    }

    @Override
    public void render() {
        // todo zoom / pan
        renderTexture();
        renderSelection();
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
            case KeyEvent.VK_F5 -> saveToFile();
            case KeyEvent.VK_F9 -> loadFromFile();
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

    private void saveToFile() {
        var start = clock.instant();
        var dir = dirName.toFile();
        var file = dirName.resolve(filename).toFile();
        if (dir.exists()) {
            if (!dir.isDirectory()) {
                LOG.error("Failed to save; %s is a file, not a directory", dir);
                return;
            }
        } else {
            if (!dir.mkdirs()) {
                LOG.error("Failed to make dirs for path %s", dir);
                return;
            }
        }
        if (file.exists()) {
            if (file.isDirectory()) {
                LOG.error("Failed to save; %s is a directory, not a file", file);
                return;
            }
        } else {
            try {
                if (!file.createNewFile()) {
                    LOG.error("Failed to create file %s", file);
                    return;
                }
            } catch (IOException e) {
                LOG.error(e, "Failed to create file %s", file);
                return;
            }
        }
        try (var fos = new FileOutputStream(file)) {
            // width - BE int32
            fos.write(intToBytes(texture.width()));
            // height - BE int32
            fos.write(intToBytes(texture.height()));
            // pixel data - BE int24 array
            fos.write(pixelsToBytes(texture.pixels()));
        } catch (FileNotFoundException e) {
            LOG.error(e, "Could not find file after creating it: %s", file);
            return;
        } catch (IOException e) {
            LOG.error(e, "Could not close file %s", file);
            return;
        }
        var end = clock.instant();
        LOG.info("Saved to %s in %s", file, Duration.between(start, end));
    }

    private byte[] intToBytes(int i) {
        // big-endian
        return new byte[]{
                (byte) ((i & 0xff000000) >> 24),
                (byte) ((i & 0xff0000) >> 16),
                (byte) ((i & 0xff00) >> 8),
                (byte) (i & 0xff)};
    }

    private int intFromBytes(byte[] b) {
        return (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | (b[3]);
    }

    private byte[] pixelsToBytes(int[] pixels) {
        var res = new byte[pixels.length];
        for (int i = 0; i < pixels.length; ++i) {
            res[i] = (byte) pixels[i];
        }
        return res;
    }

    private int[] pixelsFromBytes(byte[] pixels) {
        var res = new int[pixels.length];
        for (int i = 0; i < pixels.length; ++i) {
            res[i] = pixels[i];
        }
        return res;
    }

    private void loadFromFile() {
        var start = clock.instant();
        var file = dirName.resolve(filename).toFile();
        if (file.exists()) {
            if (file.isDirectory()) {
                LOG.error("Failed to load from file; %s is a directory, not a file", file);
                return;
            }
        } else {
            LOG.error("Failed to load from file; %s does not exist", file);
            return;
        }
        try (var fis = new FileInputStream(file)) {
            var intBuf = new byte[4];
            // width - BE int32
            if (4 != fis.read(intBuf)) {
                LOG.error("Error reading texture width from file %s (unexpected end of buffer)", file);
                return;
            }
            int width = intFromBytes(intBuf);
            // height - BE int32
            if (4 != fis.read(intBuf)) {
                LOG.error("Error reading texture height from file %s (unexpected end of buffer)", file);
                return;
            }
            int height = intFromBytes(intBuf);
            // pixel data - BE int24 array
            var pxBuf = new byte[width * height * 3];
            if (width * height * 3 != fis.read(pxBuf)) {
                LOG.error("Error reading texture pixel data from file %s (unexpected end of buffer)", file);
                return;
            }
            int[] pixels = pixelsFromBytes(pxBuf);
            texture = RasterFactory.cloneable(RasterFactory.create(width, height, pixels));
        } catch (FileNotFoundException e) {
            LOG.error(e, "Could not find file after creating it: %s", file);
            return;
        } catch (IOException e) {
            LOG.error(e, "Could not close file %s", file);
            return;
        }
        var end = clock.instant();
        LOG.info("Loaded from %s in %s", file, Duration.between(start, end));
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
        var match = matcher.group(2);
        color = Integer.parseInt(match, 16);
        LOG.info("Setting color to 0x%x", color);
        resetState();
    }

    private void parseCommand(Matcher matcher) {
        var command = matcher.group();
        // todo actually implement these with on-screen stuff
        if (command.startsWith("status")) {
            LOG.info("Working dir: %s, open file: %s, width: %d, height: %s, color: 0x%x",
                    dirName, filename, texture.width(), texture.height(), color);
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
            if (!this.filename.equals(targetFilename)) {
                LOG.info("Set active file to %s", targetFilename);
                this.filename = targetFilename;
            }
            switch (matcher.group(4)) {
                case "load" -> loadFromFile();
                case "save" -> saveToFile();
            }
        } else if (command.startsWith("touch ")
                || command.startsWith("rm ")) {
            var targetFilename = Path.of(matcher.group(8));
            var targetFile = this.dirName.resolve(targetFilename).toFile();
            switch (matcher.group(7)) {
                case "touch" -> {
                    if (targetFile.exists()) {
                        LOG.error("Target already exists: %s", targetFilename);
                        return;
                    }
                    try {
                        if (!targetFile.createNewFile()) {
                            LOG.error("Failed to create %s", targetFilename);
                            return;
                        }
                    } catch (IOException e) {
                        LOG.error(e, "Failed to create %s", targetFilename);
                        return;
                    }
                    LOG.info("Created %s", targetFilename);
                }
                case "rm" -> {
                    if (!targetFile.exists()) {
                        LOG.error("Target does not exist: %s", targetFilename);
                        return;
                    }
                    if (!targetFile.isFile()) {
                        LOG.error("Target is not a file: %s", targetFilename);
                        return;
                    }
                    if (!targetFile.delete()) {
                        LOG.error("Failed to delete %s", targetFilename);
                        return;
                    }
                    LOG.info("Deleted %s", targetFilename);
                }
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
