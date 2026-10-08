package scenes.textureeditor;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertInstanceOf;
import static harness.Assertions.assertTrue;

import harness.SuiteRunner;
import harness.Test;

import misc.monads.Result;

import rendering.Font;
import rendering.InMemoryFont;
import rendering.PixelRaster;
import rendering.Raster;
import rendering.RasterRepository;

import scenes.Scene;
import scenes.textureeditor.model.Mode;
import scenes.textureeditor.model.Selection.BoxSelection;
import scenes.textureeditor.model.Selection.PixelSelection;

import java.awt.Canvas;
import java.awt.event.MouseEvent;
import java.io.File;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end regression tests for the TextureEditor mouse bindings refactor. Each test feeds a real
 * {@link MouseEvent} through the same {@code mousePressed/mouseReleased/mouseDragged} entry points
 * AWT uses, and asserts the resulting state mutation. Covers the full path: AWT MouseEvent →
 * MouseChord.from(gesture, event, mode) → MouseBindings.handle → action consumer.
 */
public class TextureEditorMouseBindingsTest {
    private static final Canvas SRC = new Canvas();
    private static final int DISPLAY = 64;
    private static final int TW = 32;
    private static final int TH = 32;

    private static TextureEditor newEditor() {
        Raster display = new PixelRaster(DISPLAY, DISPLAY);
        Font font = new InMemoryFont(Map.of('\0', new PixelRaster(1, 1)), 16);
        RasterRepository repo =
                new RasterRepository() {
                    @Override
                    public Result<Raster, Exception> load(File f) {
                        return Result.failure(new UnsupportedOperationException());
                    }

                    @Override
                    public Result<?, Exception> save(File f, Raster r) {
                        return Result.failure(new UnsupportedOperationException());
                    }
                };
        return new TextureEditor(
                display,
                Clock.systemUTC(),
                font,
                repo,
                TW,
                TH,
                Map.of(),
                new AtomicReference<Scene>());
    }

    private static MouseEvent press(int x, int y, int button) {
        return new MouseEvent(
                SRC,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                0,
                x,
                y,
                1,
                false,
                button);
    }

    private static MouseEvent release(int x, int y, int button) {
        return new MouseEvent(
                SRC,
                MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(),
                0,
                x,
                y,
                1,
                false,
                button);
    }

    private static MouseEvent drag(int x, int y) {
        return new MouseEvent(
                SRC,
                MouseEvent.MOUSE_DRAGGED,
                System.currentTimeMillis(),
                MouseEvent.BUTTON1_DOWN_MASK,
                x,
                y,
                0,
                false,
                MouseEvent.NOBUTTON);
    }

    @Test
    void leftPressInPixelSelectSelectsPixel() {
        var e = newEditor();
        e.state().mode(Mode.PIXEL_SELECT);
        e.mousePressed(press(0, 0, MouseEvent.BUTTON1));
        var sel = e.state().selection().orElseThrow();
        var px = assertInstanceOf(PixelSelection.class, sel);
        assertEquals(0, px.px().x());
        assertEquals(0, px.px().y());
    }

    @Test
    void leftPressInBoxSelectStartsBox() {
        var e = newEditor();
        e.state().mode(Mode.BOX_SELECT);
        e.mousePressed(press(0, 0, MouseEvent.BUTTON1));
        assertInstanceOf(BoxSelection.class, e.state().selection().orElseThrow());
        assertTrue(e.state().boxStart().isPresent());
    }

    @Test
    void dragInBoxSelectUpdatesBox() {
        var e = newEditor();
        e.state().mode(Mode.BOX_SELECT);
        e.mousePressed(press(0, 0, MouseEvent.BUTTON1));
        e.mouseDragged(drag(DISPLAY - 2, DISPLAY - 2));
        var box = (BoxSelection) e.state().selection().orElseThrow();
        assertEquals(0, box.tl().x());
        assertEquals(0, box.tl().y());
        assertEquals(TW - 1, box.br().x());
        assertEquals(TH - 1, box.br().y());
    }

    @Test
    void releaseInBoxSelectClearsBoxStart() {
        var e = newEditor();
        e.state().mode(Mode.BOX_SELECT);
        e.mousePressed(press(0, 0, MouseEvent.BUTTON1));
        e.mouseDragged(drag(DISPLAY - 2, DISPLAY - 2));
        e.mouseReleased(release(DISPLAY - 2, DISPLAY - 2, MouseEvent.BUTTON1));
        assertTrue(e.state().boxStart().isEmpty());
    }

    @Test
    void leftPressInBrushPaintsPixel() {
        var e = newEditor();
        e.state().mode(Mode.BRUSH);
        e.mousePressed(press(0, 0, MouseEvent.BUTTON1));
        assertEquals(
                e.colorPicker().getColor().argbInt32(),
                e.state().texture().pixel(0, 0).argbInt32());
    }

    @Test
    void dragInBrushPaintsPixel() {
        var e = newEditor();
        e.state().mode(Mode.BRUSH);
        e.mouseDragged(drag(0, 0));
        assertEquals(
                e.colorPicker().getColor().argbInt32(),
                e.state().texture().pixel(0, 0).argbInt32());
    }

    @Test
    void leftPressInFillFillsEverything() {
        var e = newEditor();
        e.state().mode(Mode.FILL);
        e.mousePressed(press(0, 0, MouseEvent.BUTTON1));
        int color = e.colorPicker().getColor().argbInt32();
        assertEquals(color, e.state().texture().pixel(0, 0).argbInt32());
        assertEquals(color, e.state().texture().pixel(TW - 1, TH - 1).argbInt32());
    }

    @Test
    void rightPressInPixelSelectIsNoOp() {
        var e = newEditor();
        e.state().mode(Mode.PIXEL_SELECT);
        e.mousePressed(press(0, 0, MouseEvent.BUTTON3));
        assertTrue(e.state().selection().isEmpty());
    }

    @Test
    void pressInCommandEntryModeIsNoOp() {
        var e = newEditor();
        e.state().mode(Mode.COMMAND_ENTRY);
        e.mousePressed(press(0, 0, MouseEvent.BUTTON1));
        assertTrue(e.state().selection().isEmpty());
        assertEquals(Mode.COMMAND_ENTRY, e.state().mode());
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
