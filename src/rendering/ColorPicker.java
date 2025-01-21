package rendering;

import logging.LogManager;
import logging.Logger;

import java.awt.event.MouseEvent;

public class ColorPicker implements Renderer {
    private static final Logger LOG = LogManager.instance().getThis();

    private static final float ONE_SIXTH   = (float) 1 / 6;
    private static final float ONE_THIRD   = (float) 1 / 3;
    private static final float ONE_HALF    = (float) 1 / 2;
    private static final float TWO_THIRDS  = (float) 2 / 3;
    private static final float FIVE_SIXTHS = (float) 5 / 6;

    private final RasterPainter painter;
    private final int           width;
    private final int           minX;
    private final int           maxX;
    private final int           hueSliderHeight;
    private final int           hueSliderY;
    private final int           shadePickerHeight;
    private final int           shadePickerY;
    private final int           minY;
    private final int           maxY;
    private       boolean       selectingHue   = false;
    private       boolean       selectingShade = false;
    private       Color         color;

    public ColorPicker(Raster display, Color initial) {
        this.painter = new RasterPainter(display);
        this.width = (int) (.5 * display.width());
        this.minX = display.width() - width;
        this.maxX = minX + width;
        this.hueSliderHeight = 20;
        this.hueSliderY = display.height() - hueSliderHeight;
        this.shadePickerHeight = (int) (.33 * display.height());
        this.shadePickerY = hueSliderY - shadePickerHeight;
        this.minY = Math.min(hueSliderY, shadePickerY);
        this.maxY = Math.max(hueSliderY + hueSliderHeight, shadePickerY + shadePickerHeight);
        this.color = initial;
    }

    public void set(Color color) {
        this.color = color;
    }

    public Color get() {
        return color;
    }

    public void accept(MouseEvent e) {
        switch (e.getID()) {
            case MouseEvent.MOUSE_PRESSED -> {
                if (e.getX() < minX || e.getX() > maxX) {
                    return;
                }
                if (e.getY() < minY || e.getY() > maxY) {
                    return;
                }
                if (e.getY() >= hueSliderY && e.getY() <= hueSliderY + hueSliderHeight) {
                    // hue slider
                    LOG.info("Selecting hue");
                    selectingHue = true;
                    color = getColorOnHueSlider(e.getX() - minX);
                } else {
                    // shade picker
                    LOG.info("Selecting shade");
                    selectingShade = true;
                    color = getColorOnShadePicker(e.getX() - minX, e.getY() - shadePickerY);
                }
            }
            case MouseEvent.MOUSE_DRAGGED -> {
                if (!(selectingHue || selectingShade)) {
                    return;
                }
                if (e.getX() < minX || e.getX() > maxX) {
                    return;
                }
                if (e.getY() < minY || e.getY() > maxY) {
                    return;
                }
                if (selectingHue) {
                    color = getColorOnHueSlider(e.getX() - minX);
                } else {
                    // selecting shade
                    color = getColorOnShadePicker(e.getX() - minX, e.getY() - shadePickerY);
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
        renderHueSlider();
        renderShadePicker();
    }

    private void renderHueSlider() {
        var hueSlider = new PixelRaster(width, hueSliderHeight,
                (col, _) -> getColorOnHueSlider((float) col));
        painter.drawImg(minX, hueSliderY, hueSlider);
    }

    private void renderShadePicker() {
        var shadePicker = new PixelRaster(width, shadePickerHeight,
                (col, row) -> getColorOnShadePicker((float) col, (float) row));
        painter.drawImg(minX, shadePickerY, shadePicker);
    }

    @SuppressWarnings("DuplicateBranchesInSwitch")
    private Color.RgbInt24Color getColorOnHueSlider(float col) {
        // https://www.desmos.com/calculator/wfwjzctarh
        float fraction = col / width;
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
        var whiteFraction = 1f - col / width;
        var blackFraction = 1f - row / shadePickerHeight;
        return blend(blackFraction, Color.BLACK,
                blend(whiteFraction, Color.WHITE, color));
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
