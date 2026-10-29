package game;

import engine.DevicePixels;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/** Explicit owners are the live frame, displayed frame, history, export and native presenter. */
final class GameFrameImage {
    private final DevicePixels device;
    private final float exposure;
    private final engine.Bloom bloom;
    private BufferedImage image;
    private int owners = 1;

    GameFrameImage(BufferedImage image) {
        this.image = java.util.Objects.requireNonNull(image);
        device = null;
        exposure = 1;
        bloom = engine.Bloom.OFF;
    }

    GameFrameImage(DevicePixels device, float exposure) {
        this(device, exposure, engine.Bloom.OFF);
    }

    GameFrameImage(DevicePixels device, float exposure, engine.Bloom bloom) {
        this.bloom = bloom;
        this.device = java.util.Objects.requireNonNull(device);
        this.exposure = exposure;
    }

    engine.Bloom bloom() {
        return bloom;
    }

    int width() {
        return device == null ? image.getWidth() : device.width();
    }

    int height() {
        return device == null ? image.getHeight() : device.height();
    }

    DevicePixels device() {
        return device;
    }

    float exposure() {
        return exposure;
    }

    synchronized BufferedImage image() {
        if (image == null) {
            if (owners == 0) {
                throw new IllegalStateException("Released device image");
            }
            var converted = new BufferedImage(width(), height(), BufferedImage.TYPE_INT_RGB);
            device.copyDisplayTo(
                    ((DataBufferInt) converted.getRaster().getDataBuffer()).getData(),
                    exposure,
                    bloom);
            image = converted;
        }
        return image;
    }

    synchronized void retain() {
        if (owners == 0) {
            throw new IllegalStateException("Released frame");
        }
        owners++;
    }

    synchronized void release() {
        if (owners <= 0) {
            throw new IllegalStateException("Frame released twice");
        }
        if (--owners == 0 && device != null) {
            device.close();
        }
    }
}
