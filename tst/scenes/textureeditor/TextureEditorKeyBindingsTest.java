package scenes.textureeditor;

import harness.SuiteRunner;
import harness.Test;
import misc.monads.Result;
import rendering.Color.NamedColor;
import rendering.Font;
import rendering.InMemoryFont;
import rendering.PixelRaster;
import rendering.Raster;
import rendering.RasterRepository;
import scenes.Scene;
import scenes.textureeditor.model.Mode;
import scenes.textureeditor.model.Selection.BoxSelection;

import java.awt.Canvas;
import java.awt.event.KeyEvent;
import java.io.File;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertInstanceOf;
import static harness.Assertions.assertTrue;

/**
 * End-to-end regression tests for the TextureEditor key bindings refactor. Each test feeds a real
 * {@link KeyEvent} through {@link TextureEditor#keyPressed(KeyEvent)} — the exact same entry point
 * AWT uses — and asserts the resulting state mutation. Covers the full path:
 * AWT KeyEvent → KeyAction.fromAwt → mode switch → InputBindings.handle → action runnable.
 */
public class TextureEditorKeyBindingsTest {
    private static final Canvas SRC = new Canvas();
    private static final int    TW  = 32;
    private static final int    TH  = 32;

    private static TextureEditor newEditor() {
        Raster display = new PixelRaster(64, 64);
        Font font = new InMemoryFont(Map.of('\0', new PixelRaster(1, 1)), 16);
        RasterRepository repo = new RasterRepository() {
            @Override public Result<Raster, Exception> load(File f) {
                return Result.failure(new UnsupportedOperationException());
            }
            @Override public Result<?, Exception> save(File f, Raster r) {
                return Result.failure(new UnsupportedOperationException());
            }
        };
        return new TextureEditor(display, Clock.systemUTC(), font, repo, TW, TH,
                Map.of(), new AtomicReference<Scene>());
    }

    private static KeyEvent press(int vk, char ch, int extMods) {
        return new KeyEvent(SRC, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), extMods, vk, ch,
                KeyEvent.KEY_LOCATION_STANDARD);
    }

    private static KeyEvent press(int vk, char ch) {
        return press(vk, ch, 0);
    }

    // ---- mode-switch keys ----

    @Test
    void qSwitchesToPixelSelect() {
        var e = newEditor();
        e.keyPressed(press(KeyEvent.VK_Q, 'q'));
        assertEquals(Mode.PIXEL_SELECT, e.state().mode());
    }

    @Test
    void wSwitchesToBoxSelect() {
        var e = newEditor();
        e.keyPressed(press(KeyEvent.VK_W, 'w'));
        assertEquals(Mode.BOX_SELECT, e.state().mode());
    }

    @Test
    void eSwitchesToLassoSelect() {
        var e = newEditor();
        e.keyPressed(press(KeyEvent.VK_E, 'e'));
        assertEquals(Mode.LASSO_SELECT, e.state().mode());
    }

    @Test
    void rSwitchesToBrush() {
        var e = newEditor();
        e.keyPressed(press(KeyEvent.VK_W, 'w'));
        e.keyPressed(press(KeyEvent.VK_R, 'r'));
        assertEquals(Mode.BRUSH, e.state().mode());
    }

    @Test
    void tSwitchesToFill() {
        var e = newEditor();
        e.keyPressed(press(KeyEvent.VK_T, 't'));
        assertEquals(Mode.FILL, e.state().mode());
    }

    @Test
    void cSwitchesToColorPicker() {
        var e = newEditor();
        e.keyPressed(press(KeyEvent.VK_C, 'c'));
        assertEquals(Mode.COLOR_PICKER, e.state().mode());
    }

    @Test
    void slashSwitchesToCommandEntry() {
        var e = newEditor();
        e.keyPressed(press(KeyEvent.VK_SLASH, '/'));
        assertEquals(Mode.COMMAND_ENTRY, e.state().mode());
    }

    // ---- global actions ----

    @Test
    void escapeClearsSelection() {
        var e = newEditor();
        e.state().selection(new BoxSelection(1, 1, 5, 5));
        e.keyPressed(press(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED));
        assertTrue(e.state().selection().isEmpty());
    }

    @Test
    void f2TogglesToolCard() {
        var e = newEditor();
        boolean before = e.state().isToolCardShown();
        e.keyPressed(press(KeyEvent.VK_F2, KeyEvent.CHAR_UNDEFINED));
        assertEquals(!before, e.state().isToolCardShown());
    }

    @Test
    void ctrlASelectsAll() {
        var e = newEditor();
        e.keyPressed(press(KeyEvent.VK_A, 'a', KeyEvent.CTRL_DOWN_MASK));
        var sel = e.state().selection().orElseThrow();
        var box = assertInstanceOf(BoxSelection.class, sel);
        assertEquals(0, box.tl().x());
        assertEquals(0, box.tl().y());
        assertEquals(TW - 1, box.br().x());
        assertEquals(TH - 1, box.br().y());
    }

    @Test
    void ctrlZRevertsPixelToPreviousSnapshot() {
        var e = newEditor();
        e.state().texture().pixel(0, 0, NamedColor.BLACK);
        e.state().snapshot();
        e.state().texture().pixel(0, 0, NamedColor.WHITE);
        e.state().snapshot();
        e.keyPressed(press(KeyEvent.VK_Z, 'z', KeyEvent.CTRL_DOWN_MASK));
        assertEquals(NamedColor.BLACK.argbInt32(), e.state().texture().pixel(0, 0).argbInt32());
    }

    @Test
    void ctrlShiftZRestoresPixelAfterUndo() {
        var e = newEditor();
        e.state().texture().pixel(0, 0, NamedColor.BLACK);
        e.state().snapshot();
        e.state().texture().pixel(0, 0, NamedColor.WHITE);
        e.state().snapshot();
        e.keyPressed(press(KeyEvent.VK_Z, 'z', KeyEvent.CTRL_DOWN_MASK));
        assertEquals(NamedColor.BLACK.argbInt32(), e.state().texture().pixel(0, 0).argbInt32());
        e.keyPressed(press(KeyEvent.VK_Z, 'z', KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK));
        assertEquals(NamedColor.WHITE.argbInt32(), e.state().texture().pixel(0, 0).argbInt32());
    }

    // ---- modal delegation: bindings must NOT fire in COLOR_PICKER / COMMAND_ENTRY ----

    @Test
    void inColorPickerModeQDoesNotSwitchToPixelSelect() {
        var e = newEditor();
        e.state().mode(Mode.COLOR_PICKER);
        e.keyPressed(press(KeyEvent.VK_Q, 'q'));
        assertEquals(Mode.COLOR_PICKER, e.state().mode());
    }

    @Test
    void inCommandEntryModeQDoesNotSwitchToPixelSelect() {
        var e = newEditor();
        e.state().mode(Mode.COMMAND_ENTRY);
        e.keyPressed(press(KeyEvent.VK_Q, 'q'));
        assertEquals(Mode.COMMAND_ENTRY, e.state().mode());
    }

    // ---- strict modifier matching ----

    @Test
    void shiftQIsNoOp() {
        var e = newEditor();
        var startMode = e.state().mode();
        e.keyPressed(press(KeyEvent.VK_Q, 'Q', KeyEvent.SHIFT_DOWN_MASK));
        assertEquals(startMode, e.state().mode());
    }

    @Test
    void plainZIsNoOp() {
        var e = newEditor();
        e.state().texture().pixel(0, 0, NamedColor.BLACK);
        e.state().snapshot();
        e.state().texture().pixel(0, 0, NamedColor.WHITE);
        e.state().snapshot();
        e.keyPressed(press(KeyEvent.VK_Z, 'z'));
        assertEquals(NamedColor.WHITE.argbInt32(), e.state().texture().pixel(0, 0).argbInt32());
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
