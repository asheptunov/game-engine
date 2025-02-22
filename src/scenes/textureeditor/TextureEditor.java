package scenes.textureeditor;

import logging.LogManager;
import logging.Logger;
import misc.monads.Result;
import rendering.ArgbSerializer;
import rendering.BlendMode;
import rendering.ChainRasterSerializer;
import rendering.Color;
import rendering.FileSystemRasterRepository;
import rendering.Font;
import rendering.FsFontLoader;
import rendering.Painter;
import rendering.PixelRaster;
import rendering.Printer;
import rendering.Raster;
import rendering.RasterFilter;
import rendering.RasterPainter;
import rendering.RasterPrinter;
import rendering.RasterRepository;
import rendering.Renderer;
import rendering.RgbSerializer;
import scenes.Scene;
import scenes.textureeditor.console.Console;
import scenes.textureeditor.model.Coordinates;
import scenes.textureeditor.model.EditorState;
import scenes.textureeditor.model.Mode;
import ui.KeyAction;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.io.File;
import java.nio.file.Path;
import java.time.Clock;

import static rendering.Color.NamedColor;
import static scenes.textureeditor.model.Mode.BOX_SELECT;
import static scenes.textureeditor.model.Mode.BRUSH;
import static scenes.textureeditor.model.Mode.COLOR_PICKER;
import static scenes.textureeditor.model.Mode.COMMAND_ENTRY;
import static scenes.textureeditor.model.Mode.FILL;
import static scenes.textureeditor.model.Mode.LASSO_SELECT;
import static scenes.textureeditor.model.Mode.PIXEL_SELECT;
import static scenes.textureeditor.model.Selection.BoxSelection;
import static scenes.textureeditor.model.Selection.LassoSelection;
import static scenes.textureeditor.model.Selection.PixelSelection;

// not thread safe. stateful. evil. thriving.
public class TextureEditor implements
        Scene,
        Renderer,
        KeyListener, MouseListener, MouseMotionListener, MouseWheelListener {
    private static final Logger LOG          = LogManager.instance().getThis();
    private static final Mode   DEFAULT_MODE = BRUSH;

    private static final Painter.LineSampler SELECTION_PATTERN
            = (i, _, _) -> (i / 8) % 2 == 0 ? NamedColor.BLACK : NamedColor.WHITE;

    private final RasterRepository repo;
    private final Raster           display;
    private final Painter          painter;
    private final Font             font;
    private final Printer          printer;
    private final Clock            clock;
    private final EditorState      state;
    private final ToolCard         toolCard;
    private final ColorPicker      colorPicker;
    private final Console          console;

    public TextureEditor(Raster display, Clock clock, int width, int height) {
        this.repo = new FileSystemRasterRepository(clock, ChainRasterSerializer.of(
                ArgbSerializer.INSTANCE,
                RgbSerializer.INSTANCE));
        this.display = display;
        this.painter = new RasterPainter(display);
        this.font = FsFontLoader.builder()
                .repository(repo)
                .clock(clock)
                .fontPath("assets/fonts/test")
                .fontDimensions(16)
                .filter(RasterFilter.antiAlias())
                .build()
                .load();
        this.printer = new RasterPrinter(display, font);
        this.clock = clock;
        this.state = new EditorState(
                DEFAULT_MODE,
                Path.of("assets/fonts/test/standard").toFile(),
                new PixelRaster(width, height, NamedColor.NONE),
                100);
        this.toolCard = new ToolCard(this);
        this.colorPicker = new ColorPicker(this, NamedColor.WHITE);
        this.console = new Console(this, 500);
    }

    @Override
    public void keyTyped(KeyEvent e) {
        LOG.trace("Handling %s", e);
    }

    @Override
    public void keyPressed(KeyEvent e) {
        LOG.trace("Handling %s", e);
        var action = KeyAction.fromAwt(e);
        switch (state.mode()) {
            case PIXEL_SELECT:
            case LASSO_SELECT:
            case BOX_SELECT:
            case BRUSH:
            case FILL:
                state.mode(switch (action.raw()) {
                    case KeyAction.Key.LOWER_Q -> PIXEL_SELECT;
                    case KeyAction.Key.LOWER_W -> BOX_SELECT;
                    case KeyAction.Key.LOWER_E -> LASSO_SELECT;
                    case KeyAction.Key.LOWER_R -> BRUSH;
                    case KeyAction.Key.LOWER_T -> FILL;
                    case KeyAction.Key.LOWER_C -> COLOR_PICKER;
                    case KeyAction.Key.FORWARD_SLASH -> COMMAND_ENTRY;
                    default -> state.mode();
                });
                handleGlobalActions(action);
                break;
            case COLOR_PICKER:
                colorPicker.accept(action);
                break;
            case COMMAND_ENTRY:
                console.accept(action);
                break;
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        LOG.trace("Handling %s", KeyAction.fromAwt(e));
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
        int x = c.x();
        int y = c.y();
        // todo lasso select
        switch (state.mode()) {
            case PIXEL_SELECT -> {
                LOG.info("Selected pixel %s", c);
                state.selection(new PixelSelection(c));
            }
            case BOX_SELECT -> {
                LOG.debug("Started box selection at %s", c);
                state.selection(new BoxSelection(x, y));
                state.boxStart(new Coordinates(x, y));
            }
            case LASSO_SELECT -> throw new UnsupportedOperationException("start lasso select");
            case BRUSH -> state.selection().ifPresentOrElse(s -> {  // selection acts as a mask
                switch (s) {
                    case PixelSelection px -> {
                        if (px.is(x, y)) {
                            state.texture().pixel(x, y, colorPicker.getColor());
                        }
                    }
                    case BoxSelection box -> {
                        if (box.contains(x, y)) {
                            state.texture().pixel(x, y, colorPicker.getColor());
                        }
                    }
                    case LassoSelection lasso -> {
                        if (lasso.contains(c)) {
                            state.texture().pixel(x, y, colorPicker.getColor());
                        }
                    }
                }
            }, () -> state.texture().pixel(x, y, colorPicker.getColor()));
            case FILL -> state.selection().ifPresentOrElse(s -> {  // selection acts as an invert toggle
                        switch (s) {
                            case PixelSelection px -> {
                                fillPixel(px, colorPicker.getColor(), !px.is(x, y));
                                saveToHistory();
                            }
                            case BoxSelection box -> {
                                fillBox(box, colorPicker.getColor(), !box.contains(x, y));
                                saveToHistory();
                            }
                            case LassoSelection lasso -> {
                                fillLasso(lasso, colorPicker.getColor(), !lasso.contains(c));
                                saveToHistory();
                            }
                        }
                    }, () -> {
                        fillEverything(colorPicker.getColor());
                        saveToHistory();
                    }
            );
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
        int x = c.x();
        int y = c.y();
        switch (state.mode()) {
            case BOX_SELECT -> {
                var box = (BoxSelection) state.selection().orElseThrow();
                var boxStart = state.boxStart().orElseThrow();
                box.update(boxStart.x(), boxStart.y(), x, y);
                LOG.info("Finished box selection from [%d, %d] at [%d, %d]", boxStart.x(), boxStart.y(), x, y);
                state.clearBoxStart();
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
        int x = c.x();
        int y = c.y();
        switch (state.mode()) {
            case BOX_SELECT -> {
                var box = (BoxSelection) state.selection().orElseThrow();
                var boxStart = state.boxStart().orElseThrow();
                box.update(boxStart.x(), boxStart.y(), x, y);
                LOG.debug("Updating box selection to %s", box);
            }
            case LASSO_SELECT -> throw new UnsupportedOperationException("update lasso selection");
            case BRUSH -> state.selection().ifPresentOrElse(s -> {
                switch (s) {
                    case PixelSelection px -> {
                        if (px.is(x, y)) {
                            state.texture().pixel(x, y, colorPicker.getColor());
                        }
                    }
                    case BoxSelection box -> {
                        if (box.contains(x, y)) {
                            state.texture().pixel(x, y, colorPicker.getColor());
                        }
                    }
                    case LassoSelection lasso -> {
                        if (lasso.contains(c)) {
                            state.texture().pixel(x, y, colorPicker.getColor());
                        }
                    }
                }
            }, () -> state.texture().pixel(x, y, colorPicker.getColor()));
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
        switch (state().mode()) {
            case COMMAND_ENTRY -> console.accept(e);
        }
    }

    @Override
    public void render() {
        LOG.trace("Rendering");
        // todo zoom / pan
        renderTexture();
        renderSelection();
        if (state.isToolCardShown()) {
            toolCard.render();
        }
        if (COLOR_PICKER.equals(state.mode())) {
            colorPicker.render();
        }
        if (COMMAND_ENTRY.equals(state.mode())) {
            console.render();
        }
    }

    public void escape() {
        state.mode(DEFAULT_MODE);
    }

    public Result<?, Exception> saveToFile(File file) {
        return repo.save(state.workingDir().toPath().resolve(file.toPath()).toFile(), state.texture());
    }

    public Result<Raster, Exception> loadFromFile(File file) {
        return repo.load(state.workingDir().toPath().resolve(file.toPath()).toFile())
                .ifSuccess(raster -> {
                    state.texture(raster);
                    saveToHistory();
                });
    }

    public RasterRepository repo() {
        return repo;
    }

    public Raster display() {
        return display;
    }

    public Painter painter() {
        return painter;
    }

    public Font font() {
        return font;
    }

    public Printer printer() {
        return printer;
    }

    public int fontSize() {
        return 16;
    }

    public int charSpacing() {
        return -4;
    }

    public int lineSpacing() {
        return 0;
    }

    public Clock clock() {
        return clock;
    }

    public EditorState state() {
        return state;
    }

    public ColorPicker colorPicker() {
        return colorPicker;
    }

    private void renderTexture() {
        var scaledTexture = state.texture().scale(display.width(), display.height());
        painter.drawImg(0, 0, scaledTexture, BlendMode.OVER_PRE);
    }

    private void renderSelection() {
        var xScale = 1. * display.width() / state.texture().width();
        var yScale = 1. * display.height() / state.texture().height();
        state.selection().ifPresentOrElse(s -> {
            switch (s) {
                case PixelSelection px -> {
                    int l = (int) (px.px().x() * xScale);
                    int t = (int) (px.px().y() * yScale);
                    int r = (int) ((px.px().x() + 1) * xScale) - 1;
                    int b = (int) ((px.px().y() + 1) * yScale) - 1;
                    painter.drawLine(l, t, r, t, SELECTION_PATTERN, BlendMode.SUBTRACT);
                    painter.drawLine(l, t, l, b, SELECTION_PATTERN, BlendMode.SUBTRACT);
                    painter.drawLine(r, t, r, b, SELECTION_PATTERN, BlendMode.SUBTRACT);
                    painter.drawLine(l, b, r, b, SELECTION_PATTERN, BlendMode.SUBTRACT);
                }
                case BoxSelection box -> {
                    int l = (int) (box.tl().x() * xScale);
                    int t = (int) (box.tl().y() * yScale);
                    int r = (int) ((box.br().x() + 1) * xScale) - 1;
                    int b = (int) ((box.br().y() + 1) * yScale) - 1;
                    painter.drawLine(l, t, r, t, SELECTION_PATTERN, BlendMode.SUBTRACT);
                    painter.drawLine(l, t, l, b, SELECTION_PATTERN, BlendMode.SUBTRACT);
                    painter.drawLine(r, t, r, b, SELECTION_PATTERN, BlendMode.SUBTRACT);
                    painter.drawLine(l, b, r, b, SELECTION_PATTERN, BlendMode.SUBTRACT);
                }
                default -> throw new UnsupportedOperationException(state.selection().getClass().getName());
            }
        }, () -> {});
    }

    private Coordinates normalize(MouseEvent e) {
        // todo zoom / pan
        int x = (int) (1. * Math.min(e.getX(), display.width() - 1) / display.width() * state.texture().width());
        int y = (int) (1. * Math.min(e.getY(), display.height() - 1) / display.height() * state.texture().height());
        return new Coordinates(x, y);
    }

    private void handleGlobalActions(KeyAction keyAction) {
        switch (keyAction.action()) {
            case PRESS -> {
                switch (keyAction.raw()) {
                    case KeyAction.Key.ESCAPE -> {
                        if (state.selection().isPresent()) {
                            LOG.info("Erased selection %s", state.selection().get());
                            state.clearSelection();
                        }
                    }
                    case KeyAction.Key.F1 -> toggleHelp();
                    case KeyAction.Key.F2 -> state.toggleToolCard();
                    case KeyAction.Key.F5 -> saveToFile(state.workingFile().orElseThrow());
                    case KeyAction.Key.F9 -> loadFromFile(state.workingFile().orElseThrow());
                    case KeyAction.Key.LOWER_Z -> {
                        switch (keyAction.mods()) {
                            case KeyAction.Modifiers m when m.ctrl() && m.shift() -> redo();
                            case KeyAction.Modifiers m when m.ctrl() -> undo();
                            default -> {}
                        }
                    }
                    case KeyAction.Key.LOWER_A -> {
                        switch (keyAction.mods()) {
                            case KeyAction.Modifiers m when m.ctrlOnly() -> state.selection(
                                    new BoxSelection(0, 0, state.texture().width() - 1, state.texture().height() - 1));
                            default -> {}
                        }
                    }
                }
            }
        }
    }

    private void toggleHelp() {
        LOG.info("Current state: %s.", state)
                .info("Keymap:")
                .info("F1: show help")
                .info("F2: toggle tool card")
                .info("F5: save to file")
                .info("q: %s", PIXEL_SELECT)
                .info("w: %s", BOX_SELECT)
                .info("e: %s", LASSO_SELECT)
                .info("r: %s", BRUSH)
                .info("t: %s", FILL)
                .info("c: open color picker")
                .info("/: command entry")
                .info("CTRL+z: undo")
                .info("CTRL+SHIFT+z: redo");
        // todo actually implement this
    }

    private void saveToHistory() {
        state.snapshot();
    }

    private void undo() {
        var prev = state.rollback();
        if (prev.isPresent()) {
            state.texture(prev.get().clone());
            LOG.info("Undid last action");
        } else {
            LOG.info("At undo limit");
        }
    }

    private void redo() {
        var next = state.rollforward();
        if (next.isPresent()) {
            state.texture(next.get().clone());
            LOG.info("Redid last undone action");
        } else {
            LOG.info("At redo limit");
        }
    }

    private void fillEverything(Color color) {
        LOG.info("Filling everything with color 0x%s", color);
        for (int r = 0; r < state.texture().height(); ++r) {
            for (int c = 0; c < state.texture().width(); ++c) {
                state.texture().pixel(c, r, color);
            }
        }
    }

    private void fillPixel(PixelSelection px, Color color, boolean inverse) {
        LOG.info("Filling %s pixel %s with color %s", inverse ? "everything outside" : "only", px, color);
        if (inverse) {
            for (int r = 0; r < state.texture().height(); ++r) {
                for (int c = 0; c < state.texture().width(); ++c) {
                    if (px.px().x() != c && px.px().y() != r) {
                        state.texture().pixel(c, r, color);
                    }
                }
            }
        } else {
            state.texture().pixel(px.px().x(), px.px().y(), color);
        }
    }

    private void fillBox(BoxSelection box, Color color, boolean inverse) {
        LOG.info("Filling everything %s box %s with color %s", inverse ? "outside" : "inside", box, color);
        int l = box.tl().x();
        int r = box.br().x();
        int t = box.tl().y();
        int b = box.br().y();
        if (inverse) {
            for (int y = 0; y < t; ++y) {
                for (int x = 0; x < state.texture().width(); ++x) {
                    state.texture().pixel(x, y, color);
                }
            }
            for (int y = t; y <= b; ++y) {
                for (int x = 0; x < l; ++x) {
                    state.texture().pixel(x, y, color);
                }
                for (int x = r + 1; x < state.texture().width(); ++x) {
                    state.texture().pixel(x, y, color);
                }
            }
            for (int y = b + 1; y < state.texture().height(); ++y) {
                for (int x = 0; x < state.texture().width(); ++x) {
                    state.texture().pixel(x, y, color);
                }
            }
        } else {
            for (int y = t; y <= b; ++y) {
                for (int x = l; x <= r; ++x) {
                    state.texture().pixel(x, y, color);
                }
            }
        }
    }

    private void fillLasso(LassoSelection lasso, Color color, boolean inverse) {
        LOG.info("Filling everything %s lasso %s with color %s", inverse ? "outside" : "inside", lasso, color);
        if (inverse) {
            for (int r = 0; r < state.texture().height(); ++r) {
                for (int c = 0; c < state.texture().width(); ++c) {
                    if (lasso.contains(new Coordinates(c, r))) {
                        state.texture().pixel(c, r, color);
                    }
                }
            }
        } else {
            for (Coordinates px : lasso.all()) {
                state.texture().pixel(px.x(), px.y(), color);
            }
        }
    }
}
