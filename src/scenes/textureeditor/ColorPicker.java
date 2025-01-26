package scenes.textureeditor;

import logging.LogManager;
import logging.Logger;
import rendering.Color;
import rendering.Painter;
import rendering.PixelRaster;
import rendering.Printer;
import rendering.Raster;
import rendering.Renderer;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ColorPicker implements Renderer {
    private static final Logger LOG = LogManager.instance().getThis();

    private static final Pattern HEX_CODE_PATTERN = Pattern.compile("^(0x|0X|)(?<hex>[0-9a-fA-F]{6})$");

    private static final float ONE_SIXTH   = (float) 1 / 6;
    private static final float ONE_THIRD   = (float) 1 / 3;
    private static final float ONE_HALF    = (float) 1 / 2;
    private static final float TWO_THIRDS  = (float) 2 / 3;
    private static final float FIVE_SIXTHS = (float) 5 / 6;

    private final TextureEditor editor;
    private final Raster        display;
    private final Painter       painter;
    private final Printer       printer;
    private final int           fontSize;
    private final int           hueSliderWidth;
    private final int           hueSliderHeight;
    private final int           hueSliderX;
    private final int           hueSliderY;
    private final int           shadePickerWidth;
    private final int           shadePickerHeight;
    private final int           shadePickerX;
    private final int           shadePickerY;
    private final int           previewWidth;
    private final int           previewHeight;
    private final int           previewX;
    private final int           previewY;
    private       boolean       selectingHue   = false;
    private       boolean       selectingShade = false;
    private       Color         hue;
    private       Color         shade;
    private       int           hueX;
    private       int           shadeX;
    private       int           shadeY;

    public ColorPicker(TextureEditor editor, Color initial) {
        this.editor = editor;
        this.display = editor.display();
        this.painter = editor.painter();
        this.printer = editor.printer();
        this.fontSize = editor.fontSize();
        hueSliderWidth = (int) (.3333 * display.width());
        hueSliderHeight = 20;
        hueSliderX = display.width() - hueSliderWidth;
        hueSliderY = display.height() - hueSliderHeight;
        shadePickerWidth = hueSliderWidth;
        shadePickerHeight = (int) (.3333 * display.height());
        shadePickerX = hueSliderX;
        shadePickerY = hueSliderY - shadePickerHeight;
        previewWidth = (int) (.1667 * display.width());
        previewHeight = hueSliderHeight + shadePickerHeight;
        previewX = hueSliderX - previewWidth;
        previewY = shadePickerY;
        set(initial);
    }

    public void set(Color color) {
        if (color.blue() == color.green() && color.green() == color.red()) {  // all shades of gray, from white to black
            this.hue = Color.RED;  // arbitrary
            this.shade = color;
            return;
        }

        // blending formula: blended_with_white = x + (1 - x) * hue
        // 1. un-blend with black
        //   i. take max component, scale all components up by 1 / max
        //      this also yields y! it's just 1 - max
        // 2. un-blend with white
        //   i. solve blending formula for remaining two components:
        //      formula: blend = x + (1 - x) * hue
        //      for the min component, hue is 0! this means we can simplify to:
        //      blend_min = x
        //  ii. for the remaining (med) component, solve by substituting x for blend_min:
        //      hue_med = (blend_med - blend_min) / (1 - blend_min)
        // 3. now that we have hue, solve for x and y
        //   i. solve for x using the blending formula
        //      blend_with_white_only = x + (1 - x) * hue
        //      ...
        //      x = (blend - hue) / (1 - hue)
        //      to solve this, use blend_med:
        //      x = (blend_med - hue_med) / (1 - hue_med)

        float r = ((int) color.red() & 0xff) / 255f;
        float g = ((int) color.green() & 0xff) / 255f;
        float b = ((int) color.blue() & 0xff) / 255f;

        // compute max
        float max = r;
        char argMax = 'r';
        if (g > max) {
            max = g;
            argMax = 'g';
        }
        if (b > max) {
            max = b;
            argMax = 'b';
        }

        // compute min
        float min = b;
        char argMin = 'b';
        if (g < min) {
            min = g;
            argMin = 'g';
        }
        if (r < min) {
            min = r;
            argMin = 'r';
        }

        // compute med
        char argMed = switch (argMax) {
            case 'r' -> argMin == 'g' ? 'b' : 'g';
            case 'g' -> argMin == 'r' ? 'b' : 'r';
            default -> argMin == 'r' ? 'g' : 'r';
        };
        float med = switch (argMed) {
            case 'r' -> r;
            case 'g' -> g;
            default -> b;
        };

        // un-blend black
        float no_blk_min = min / max;
        float no_blk_med = med / max;

        // un-blend white
        float no_white_med = (no_blk_med - no_blk_min) / (1 - no_blk_min);

        // compute shade picker coordinates
        float shadeX = 1 - (no_blk_med - no_white_med) / (1 - no_white_med);
        float shadeY = max;
        this.shadeX = (int) (shadeX * shadePickerWidth);
        this.shadeY = (int) (shadeY * shadePickerHeight);

        // compute hue slider coordinates
        float hueX = switch (argMin) {
            case 'r' -> argMax == 'g'
                    ? (no_white_med + 2) / 6
                    : (4 - no_white_med) / 6;
            case 'g' -> argMax == 'r'
                    ? 1 - no_white_med / 6
                    : (4 + no_white_med) / 6;
            default -> argMax == 'r'
                    ? no_white_med / 6
                    : (2 - no_white_med) / 6;
        };
        this.hueX = (int) (hueX * hueSliderWidth);

        byte hueMin = 0;
        byte hueMed = (byte) (no_white_med * 255);
        byte hueMax = (byte) 0xff;
        this.hue = Color.RgbInt24Color.of(
                'r' == argMin ? hueMin : 'r' == argMed ? hueMed : hueMax,
                'g' == argMin ? hueMin : 'g' == argMed ? hueMed : hueMax,
                'b' == argMin ? hueMin : 'b' == argMed ? hueMed : hueMax
        );
        this.shade = color;
    }

    public Color getColor() {
        return shade;
    }

    public void accept(KeyEvent e) {
        switch (e.getID()) {
            case KeyEvent.KEY_PRESSED -> {
                switch (e.getModifiersEx()) {
                    case 0 -> {
                        switch (e.getKeyCode()) {
                            case KeyEvent.VK_ESCAPE -> editor.escape();
                        }
                    }
                    case KeyEvent.CTRL_DOWN_MASK, KeyEvent.META_DOWN_MASK -> {
                        switch (e.getKeyCode()) {
                            case KeyEvent.VK_C -> {
                                var str = hexCode();
                                var sel = new StringSelection(str);
                                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
                                LOG.info("Copied selected color to clipboard: [%s]", str);
                            }
                            case KeyEvent.VK_V -> {
                                var contents = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(this);
                                if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                                    try (var r = DataFlavor.stringFlavor.getReaderForText(contents)) {
                                        try (var br = new BufferedReader(r)) {
                                            var str = br.lines().collect(Collectors.joining("\n"));
                                            var matcher = HEX_CODE_PATTERN.matcher(str);
                                            if (matcher.find()) {
                                                var hex = matcher.group("hex");
                                                this.set(Color.RgbInt24Color.of(Integer.parseInt(hex, 16)));
                                                LOG.info("Pasted color from clipboard: [%s]", str);
                                            }
                                        }
                                    } catch (UnsupportedFlavorException | IOException ex) {
                                        LOG.error(ex, "Failed to paste color from clipboard");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public void accept(MouseEvent e) {
        switch (e.getID()) {
            case MouseEvent.MOUSE_PRESSED -> {
                if (e.getX() >= hueSliderX
                        && e.getX() <= hueSliderX + hueSliderWidth
                        && e.getY() >= hueSliderY
                        && e.getY() <= hueSliderY + hueSliderHeight) {
                    // hue slider
                    LOG.info("Selecting hue");
                    selectingHue = true;
                    hueX = e.getX() - hueSliderX;
                    hue = getColorOnHueSlider(hueX);
                    shade = getColorOnShadePicker(shadeX, shadeY);
                } else if (e.getX() >= shadePickerX
                        && e.getX() <= shadePickerX + shadePickerWidth
                        && e.getY() >= shadePickerY
                        && e.getY() <= shadePickerY + shadePickerHeight) {
                    // shade picker
                    LOG.info("Selecting shade");
                    selectingShade = true;
                    shadeX = e.getX() - shadePickerX;
                    shadeY = e.getY() - shadePickerY;
                    shade = getColorOnShadePicker(shadeX, shadeY);
                }
            }
            case MouseEvent.MOUSE_DRAGGED -> {
                if (selectingHue) {
                    hueX = Math.max(0, Math.min(hueSliderWidth, e.getX() - hueSliderX));
                    hue = getColorOnHueSlider(hueX);
                    shade = getColorOnShadePicker(shadeX, shadeY);
                } else if (selectingShade) {
                    // selecting shade
                    shadeX = Math.max(0, Math.min(shadePickerWidth, e.getX() - shadePickerX));
                    shadeY = Math.max(0, Math.min(shadePickerHeight, e.getY() - shadePickerY));
                    shade = getColorOnShadePicker(shadeX, shadeY);
                }
            }
            case MouseEvent.MOUSE_RELEASED -> {
                if (selectingHue) {
                    LOG.info("Done selecting hue");
                    selectingHue = false;
                }
                if (selectingShade) {
                    LOG.info("Done selecting shade");
                    selectingShade = false;
                }
            }
        }
    }

    @Override
    public void render() {
        renderPreview();
        renderShadePicker();
        renderHueSlider();
        renderHexCode();
    }

    private void renderPreview() {
        painter.drawImg(previewX, previewY, new PixelRaster(previewWidth, previewHeight, (_, _) -> shade));
    }

    private void renderHexCode() {
        int y = display.height() - fontSize;
        printer.print(hexCode(), 0, y, Printer.Size.of(fontSize), Printer.Color.of(shade));
    }

    private String hexCode() {
        return "%#06X".formatted(shade.rgbInt24());
    }

    private void renderHueSlider() {
        var hueSlider = new PixelRaster(hueSliderWidth, hueSliderHeight,
                (col, _) -> getColorOnHueSlider((float) col));
        painter.drawImg(hueSliderX, hueSliderY, hueSlider);
        painter.drawImg(hueSliderX + hueX - 5, hueSliderY, new PixelRaster(10, hueSliderHeight,
                (col, row)
                        -> (col == 0 || row == 0 || col == 9 || row == hueSliderHeight - 1)  // white border
                        ? Color.WHITE : Color.WHITE.withAlpha(0)));
    }

    private void renderShadePicker() {
        var shadePicker = new PixelRaster(shadePickerWidth, shadePickerHeight,
                (col, row) -> getColorOnShadePicker((float) col, (float) row));
        painter.drawImg(shadePickerX, shadePickerY, shadePicker);
        painter.drawImg(shadePickerX + shadeX - 5, shadePickerY + shadeY - 5, new PixelRaster(10, 10,
                (col, row)
                        -> (col == 0 || row == 0 || col == 9 || row == 9)  // white border
                        ? Color.WHITE : Color.WHITE.withAlpha(0)));
    }

    @SuppressWarnings("DuplicateBranchesInSwitch")
    private Color.RgbInt24Color getColorOnHueSlider(float col) {
        // https://www.desmos.com/calculator/wfwjzctarh
        float fraction = col / hueSliderWidth;
        float red = switch (fraction) {
            case float v when v < ONE_SIXTH -> 1;
            case float v when v < ONE_THIRD -> -6 * (fraction - ONE_THIRD);
            case float v when v < TWO_THIRDS -> 0;
            case float v when v < FIVE_SIXTHS -> 6 * (fraction - TWO_THIRDS);
            default -> 1;
        };
        float green = switch (fraction) {
            case float v when v < ONE_SIXTH -> 6 * fraction;
            case float v when v < ONE_HALF -> 1;
            case float v when v < TWO_THIRDS -> -6 * (fraction - TWO_THIRDS);
            default -> 0;
        };
        float blue = switch (fraction) {
            case float v when v < ONE_THIRD -> 0;
            case float v when v < ONE_HALF -> 6 * (fraction - ONE_THIRD);
            case float v when v < FIVE_SIXTHS -> 1;
            default -> -6 * (fraction - 1);
        };
        return Color.RgbInt24Color.of((byte) (red * 255), (byte) (green * 255), (byte) (blue * 255));
    }

    private Color getColorOnShadePicker(float col, float row) {
        var whiteFraction = 1f - col / shadePickerWidth;
        var blackFraction = 1f - row / shadePickerHeight;
        return blend(blackFraction, Color.BLACK,
                blend(whiteFraction, Color.WHITE, hue));
    }

    private static Color blend(float fraction, Color first, Color second) {
        var r = blend(fraction, first.red(), second.red());
        var g = blend(fraction, first.green(), second.green());
        var b = blend(fraction, first.blue(), second.blue());
        return Color.RgbInt24Color.of(r, g, b);
    }

    private static byte blend(float fraction, byte first, byte second) {
        return (byte) (fraction * ((int) first & 0xff) + (1 - fraction) * ((int) second & 0xff));
    }
}
