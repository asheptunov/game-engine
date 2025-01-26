package scenes.textureeditor;

import logging.LogManager;
import logging.Logger;
import misc.monads.Result;
import rendering.ChromaKey;
import rendering.Color;
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
import scenes.textureeditor.model.Coordinates;
import scenes.textureeditor.model.EditorState;
import scenes.textureeditor.model.Mode;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.nio.file.Path;
import java.time.Clock;
import java.util.function.BiFunction;

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

    private static final BiFunction<Integer, Double, Color>
            SELECTION_PATTERN = (i, _) -> (i / 8) % 2 == 0 ? NamedColor.BLACK : NamedColor.WHITE;

    private final RasterRepository repo;
    private final Raster           display;
    private final Painter          painter;
    private final Printer          printer;
    private final Clock            clock;
    private final EditorState      state;
    private final History<Raster>  rasterHistory;
    private final ColorPicker      colorPicker;
    private final Console          console;

    public TextureEditor(Raster display, Clock clock, int width, int height) {
        this.repo = new FileSystemRasterRepository(clock);
        this.display = display;
        this.painter = new RasterPainter(display);
        this.printer = RasterPrinter.builder()
                .raster(display)
                .repository(repo)
                .clock(clock)
                .fontPath("assets/fonts/test")
                .fontDimensions(16)
                .filter(ChromaKey.of(NamedColor.BLACK))
                .build();
        this.clock = clock;
        this.state = new EditorState(
                DEFAULT_MODE,
                Path.of("assets/fonts/test/standard"),
                new PixelRaster(width, height, (_, _) -> NamedColor.BLACK));
        this.rasterHistory = new CircularBufferHistoryImpl<>(state.texture().clone(), 100);
        this.colorPicker = new ColorPicker(this, NamedColor.WHITE);
        this.console = new Console(this, 500);
    }

    @Override
    public void keyTyped(KeyEvent e) {
        LOG.trace("Handling %s", e);
        switch (state.mode()) {
            case COLOR_PICKER:
                colorPicker.accept(e);
                break;
            case COMMAND_ENTRY:
                console.accept(e);
                break;
        }
    }

    @Override
    public void keyPressed(KeyEvent e) {
        LOG.trace("Handling %s", e);
        switch (state.mode()) {
            case PIXEL_SELECT:
            case LASSO_SELECT:
            case BOX_SELECT:
            case BRUSH:
            case FILL:
                state.mode(switch (e.getKeyCode()) {
                    case KeyEvent.VK_Q -> PIXEL_SELECT;
                    case KeyEvent.VK_W -> BOX_SELECT;
                    case KeyEvent.VK_E -> LASSO_SELECT;
                    case KeyEvent.VK_R -> BRUSH;
                    case KeyEvent.VK_T -> FILL;
                    case KeyEvent.VK_C -> COLOR_PICKER;
                    case KeyEvent.VK_SLASH -> COMMAND_ENTRY;
                    default -> state.mode();
                });
                handleGlobalActions(e);
                break;
            case COLOR_PICKER:
                colorPicker.accept(e);
                break;
            case COMMAND_ENTRY:
                console.accept(e);
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
        int x = c.x();
        int y = c.y();
        // todo lasso select
        switch (state.mode()) {
            case PIXEL_SELECT -> {
                LOG.info("Selected pixel %s", c);
                state.selection(new PixelSelection(c));
            }
            case BOX_SELECT -> {
                LOG.info("Started box selection at %s", c);
                state.selection(new BoxSelection(x, y));
                state.boxStart(new Coordinates(x, y));
            }
            case LASSO_SELECT -> throw new UnsupportedOperationException("start lasso select");
            case BRUSH -> state.selection().ifPresentOrElse(s -> {  // selection acts as a mask
                switch (s) {
                    case PixelSelection px -> {
                        if (px.is(x, y)) {
                            state.texture().setPixel(x, y, colorPicker.getColor());
                        }
                    }
                    case BoxSelection box -> {
                        if (box.contains(x, y)) {
                            state.texture().setPixel(x, y, colorPicker.getColor());
                        }
                    }
                    case LassoSelection lasso -> {
                        if (lasso.contains(c)) {
                            state.texture().setPixel(x, y, colorPicker.getColor());
                        }
                    }
                }
            }, () -> state.texture().setPixel(x, y, colorPicker.getColor()));
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
                            state.texture().setPixel(x, y, colorPicker.getColor());
                        }
                    }
                    case BoxSelection box -> {
                        if (box.contains(x, y)) {
                            state.texture().setPixel(x, y, colorPicker.getColor());
                        }
                    }
                    case LassoSelection lasso -> {
                        if (lasso.contains(c)) {
                            state.texture().setPixel(x, y, colorPicker.getColor());
                        }
                    }
                }
            }, () -> state.texture().setPixel(x, y, colorPicker.getColor()));
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
        if (COLOR_PICKER.equals(state.mode())) {
            colorPicker.render();
        }
        if (COMMAND_ENTRY.equals(state.mode())) {
            console.render();
        }
    }

    void escape() {
        state.mode(DEFAULT_MODE);
    }

    Result<Void, Exception> saveToFile(Path filename) {
        return repo.save(state.dirName().resolve(filename), state.texture());
    }

    Result<Raster, Exception> loadFromFile(Path filename) {
        return repo.load(state.dirName().resolve(filename))
                .ifSuccess(raster -> {
                    state.texture(raster);
                    saveToHistory();
                });
    }

    RasterRepository repo() {
        return repo;
    }

    Raster display() {
        return display;
    }

    Painter painter() {
        return painter;
    }

    Printer printer() {
        return printer;
    }

    int fontSize() {
        return 16;
    }

    int charSpacing() {
        return -2;
    }

    int lineSpacing() {
        return -2;
    }

    Clock clock() {
        return clock;
    }

    EditorState state() {
        return state;
    }

    ColorPicker colorPicker() {
        return colorPicker;
    }

    private void renderTexture() {
        var scaledTexture = state.texture().scale(display.width(), display.height());
        painter.drawImg(0, 0, scaledTexture);
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
                    painter.drawLine(l, t, r, t, SELECTION_PATTERN);
                    painter.drawLine(l, t, l, b, SELECTION_PATTERN);
                    painter.drawLine(r, t, r, b, SELECTION_PATTERN);
                    painter.drawLine(l, b, r, b, SELECTION_PATTERN);
                }
                case BoxSelection box -> {
                    int l = (int) (box.tl().x() * xScale);
                    int t = (int) (box.tl().y() * yScale);
                    int r = (int) ((box.br().x() + 1) * xScale) - 1;
                    int b = (int) ((box.br().y() + 1) * yScale) - 1;
                    painter.drawLine(l, t, r, t, SELECTION_PATTERN);
                    painter.drawLine(l, t, l, b, SELECTION_PATTERN);
                    painter.drawLine(r, t, r, b, SELECTION_PATTERN);
                    painter.drawLine(l, b, r, b, SELECTION_PATTERN);
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

    private void handleGlobalActions(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_ESCAPE -> {
                if (state.selection().isPresent()) {
                    LOG.info("Erasing selection %s", state.selection().get());
                    state.clearSelection();
                }
            }
            case KeyEvent.VK_F1 -> toggleHelp();
            case KeyEvent.VK_F5 -> saveToFile(state.filename().orElseThrow());
            case KeyEvent.VK_F9 -> loadFromFile(state.filename().orElseThrow());
            case KeyEvent.VK_Z -> {
                switch (e.getModifiersEx()) {
                    case KeyEvent.CTRL_DOWN_MASK -> undo();
                    case KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK -> redo();
                }
            }
            case KeyEvent.VK_A -> {
                if (e.getModifiersEx() == KeyEvent.CTRL_DOWN_MASK) {
                    LOG.info("Selecting all");
                    state.selection(new BoxSelection(0, 0, state.texture().width() - 1, state.texture().height() - 1));
                }
            }
        }
    }

    private void toggleHelp() {
        LOG.info("Current state: %s.", state)
                .info("Keymap:")
                .info("F1: show help")
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
        rasterHistory.record(state.texture().clone());
    }

    private void undo() {
        var prev = rasterHistory.goBack();
        if (prev.isPresent()) {
            state.texture(prev.get().clone());
            LOG.info("Undid last action");
        } else {
            LOG.info("At undo limit");
        }
    }

    private void redo() {
        var next = rasterHistory.goForward();
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
                state.texture().setPixel(c, r, color);
            }
        }
    }

    private void fillPixel(PixelSelection px, Color color, boolean inverse) {
        LOG.info("Filling %s pixel %s with color %s", inverse ? "everything outside" : "only", px, color);
        if (inverse) {
            for (int r = 0; r < state.texture().height(); ++r) {
                for (int c = 0; c < state.texture().width(); ++c) {
                    if (px.px().x() != c && px.px().y() != r) {
                        state.texture().setPixel(c, r, color);
                    }
                }
            }
        } else {
            state.texture().setPixel(px.px().x(), px.px().y(), color);
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
                    state.texture().setPixel(x, y, color);
                }
            }
            for (int y = t; y <= b; ++y) {
                for (int x = 0; x < l; ++x) {
                    state.texture().setPixel(x, y, color);
                }
                for (int x = r + 1; x < state.texture().width(); ++x) {
                    state.texture().setPixel(x, y, color);
                }
            }
            for (int y = b + 1; y < state.texture().height(); ++y) {
                for (int x = 0; x < state.texture().width(); ++x) {
                    state.texture().setPixel(x, y, color);
                }
            }
        } else {
            for (int y = t; y <= b; ++y) {
                for (int x = l; x <= r; ++x) {
                    state.texture().setPixel(x, y, color);
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
                        state.texture().setPixel(c, r, color);
                    }
                }
            }
        } else {
            for (Coordinates px : lasso.all()) {
                state.texture().setPixel(px.x(), px.y(), color);
            }
        }
    }
}
