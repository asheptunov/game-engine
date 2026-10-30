package gpu;

import static org.lwjgl.vulkan.VK10.*;

import engine.*;

import game.*;

import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Real-device resident ownership, compositor and invisible native-window checks. */
public final class DirectPresentationCheck {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("native")) nativeStartup();
        compositor();
        resident(args.length > 0 && args[0].equals("native"));
    }

    /** JAWT must be allowed to initialize before the render owner's first Vulkan call. */
    private static void nativeStartup() throws Exception {
        var window = new java.awt.Frame();
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                var surface = new GpuGameSurface(() -> {}, error -> { throw new AssertionError(error); });
                window.setUndecorated(true);
                window.add(surface.component());
                window.setSize(64, 64);
                window.addNotify();
                window.validate();
                if (window.isVisible()) throw new AssertionError("Startup check must stay hidden");
            });
            try (var kernel = new GpuKernel()) {
                require(kernel.gpu.presentation, "native device after JAWT initialization");
            }
        } finally {
            javax.swing.SwingUtilities.invokeAndWait(window::dispose);
        }
        System.out.println("PASS native surface initializes before Vulkan without a stack-size JVM override");
    }

    private static void compositor() {
        int w = 64, h = 32;
        try (var gpu = new VulkanDevice();
                var display = new GpuCompositor(gpu);
                var source = gpu.buffer(w * h * 16L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true)) {
            System.out.println("Native presentation supported=" + gpu.presentation);
            for (int i = 0; i < w * h; i++) {
                source.mapped.putFloat(i * 16, (i % w) / 32f);
                source.mapped.putFloat(i * 16 + 4, (i / w) / 16f);
                source.mapped.putFloat(i * 16 + 8, .25f);
            }
            for (int scale : new int[] {1, 2, 4}) {
                int tw = w * scale, th = h * scale;
                gpu.begin();
                display.encode(source, w, h, 1, tw, th, new int[] {0, tw, th, 0});
                gpu.barrier(
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_ACCESS_TRANSFER_READ_BIT);
                display.transfer();
                gpu.barrier(
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_HOST_BIT,
                        VK_ACCESS_HOST_READ_BIT);
                gpu.submit();
                int[] actual = new int[tw * th];
                display.read(actual);
                for (int y = 0; y < th; y++)
                    for (int x = 0; x < tw; x++) {
                        int expected =
                                DisplayMapping.pack(
                                        (x / scale) / 32f, (h - 1 - y / scale) / 16f, .25f, 1);
                        require(
                                actual[y * tw + x] == (expected | 0xff000000),
                                "compositor scale=" + scale + " at " + x + "," + y);
                    }
            }
            for (int[] size : new int[][] {{32, 16}, {80, 40}, {81, 81}, {3840, 2160}}) {
                int tw = size[0], th = size[1];
                gpu.begin();
                display.encode(source, w, h, 1, tw, th, new int[] {0, tw, th, 0});
                gpu.barrier(
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_ACCESS_TRANSFER_READ_BIT);
                display.transfer();
                gpu.barrier(
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_HOST_BIT,
                        VK_ACCESS_HOST_READ_BIT);
                gpu.submit();
                int[] actual = new int[tw * th];
                display.read(actual);
                double scale = Math.min(tw / (double) w, th / (double) h),
                        ox = (tw - w * scale) / 2,
                        oy = (th - h * scale) / 2;
                for (int y = 0; y < th; y++)
                    for (int x = 0; x < tw; x++) {
                        double lx = (x - ox) / scale,
                                hx = (x + 1 - ox) / scale,
                                ly = (y - oy) / scale,
                                hy = (y + 1 - oy) / scale;
                        int expected = 0;
                        if (lx >= 0 && ly >= 0 && hx <= w + .0001 && hy <= h + .0001) {
                            double red = 0, green = 0, weight = 0;
                            for (int sy = Math.max(0, (int) Math.floor(ly));
                                    sy < Math.min(h, (int) Math.ceil(hy));
                                    sy++)
                                for (int sx = Math.max(0, (int) Math.floor(lx));
                                        sx < Math.min(w, (int) Math.ceil(hx));
                                        sx++) {
                                    double area =
                                            (Math.min(hx, sx + 1) - Math.max(lx, sx))
                                                    * (Math.min(hy, sy + 1) - Math.max(ly, sy));
                                    red += area * sx / 32.;
                                    green += area * (h - 1 - sy) / 16.;
                                    weight += area;
                                }
                            expected =
                                    DisplayMapping.pack(
                                            (float) (red / weight),
                                            (float) (green / weight),
                                            .25f,
                                            1);
                        }
                        for (int shift : new int[] {0, 8, 16})
                            require(
                                    Math.abs(
                                                    ((expected >> shift) & 255)
                                                            - ((actual[y * tw + x] >> shift) & 255))
                                            <= 1,
                                    "filtered pixel " + tw + "x" + th + " at " + x + "," + y);
                    }
            }
            gpu.begin();
            display.encode(
                    source, w, h, 1, w, h, new int[] {1, w, h, 0, 0, 0, 1, 1, 9, 0x80ff0000});
            gpu.barrier(
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT);
            display.transfer();
            gpu.barrier(
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT,
                    VK_ACCESS_HOST_READ_BIT);
            gpu.submit();
            int[] blended = new int[w * h];
            display.read(blended);
            int base = DisplayMapping.pack(0, (h - 1) / 16f, .25f, 1);
            require(((blended[0] >> 16) & 255) == 128, "overlay red alpha");
            for (int shift : new int[] {0, 8})
                require(
                        ((blended[0] >> shift) & 255)
                                == Math.round(((base >> shift) & 255) * 127f / 255),
                        "overlay background alpha");
            System.out.println(
                    "PASS compositor exact integer grids; linear shrink/fractional/letterbox/4K;"
                        + " alpha overlays");
        }
    }

    private static void resident(boolean nativeWindow) throws Exception {
        var nav = new GameNavigation();
        nav.fixedResolution(320, 180);
        var source = BlockWorld.landscape().snapshot();
        var light = GameLighting.defaults();
        var world =
                new WorldSnapshot(
                        0,
                        source.instances(),
                        source.legacyObjects(),
                        java.util.List.of(light.sun()),
                        light.sky());
        var settings =
                RenderSettings.defaults()
                        .withPathDepth(0)
                        .withSampleTarget(1)
                        .withInteractive(false, 16.67, 0, 0);
        var ready = new Semaphore(0);
        var session = new GpuRenderSession(world, nav.view(), settings);
        DevicePixels retained = null;
        try {
            session.onImageAvailable(ready::release);
            session.displayExposure(1);
            session.devicePresentation(true);
            session.request();
            await(ready, session);
            int[] expected = new int[320 * 180];
            try (var image = session.acquireImage()) {
                retained = image.copyDevicePixels();
                require(retained != null, "resident publication");
                require(
                        ((GpuDevicePixels) retained).gpu.imageReadbackBytes == 0,
                        "ordinary resident publication has zero image readback");
                image.presentationPixels().copyDisplayTo(expected, 1);
            }
            var resident = (GpuDevicePixels) retained;
            long readback = resident.session.onOwner(() -> resident.gpu.imageReadbackBytes);
            for (int i = 1; i <= 8; i++) {
                session.update(world, nav.view(), settings.withRestartRevision(i));
                session.request();
                await(ready, session);
                try (var image = session.acquireImage();
                        var pixels = image.copyDevicePixels()) {
                    require(pixels.width() == 320, "new independent snapshot");
                }
            }
            require(
                    resident.session.onOwner(() -> resident.gpu.imageReadbackBytes) == readback,
                    "repeated resident frames have zero image readback");
            long settledBytes = resident.session.onOwner(() -> resident.gpu.allocatedBytes);
            for (int i = 9; i <= 24; i++) {
                session.update(world, nav.view(), settings.withRestartRevision(i));
                session.request();
                await(ready, session);
                try (var image = session.acquireImage();
                        var pixels = image.copyDevicePixels()) {
                    require(pixels.height() == 180, "pooled snapshot");
                }
            }
            require(
                    resident.session.onOwner(() -> resident.gpu.allocatedBytes) == settledBytes,
                    "resident storage reaches a fixed allocation plateau");
            int[] actual = new int[expected.length];
            retained.copyDisplayTo(actual, 1);
            require(Arrays.equals(expected, actual), "retained image survived slot reuse");
            readback = resident.session.onOwner(() -> resident.gpu.imageReadbackBytes);
            if (nativeWindow) nativeWindow((GpuDevicePixels) retained);
            require(
                    resident.session.onOwner(() -> resident.gpu.imageReadbackBytes) == readback,
                    "native submission downloads no scene image");
            session.close();
            require(session.awaitClosed(30), "retirement");
            retained.copyDisplayTo(actual, 1);
            require(Arrays.equals(expected, actual), "retained image survived close");
            System.out.println(
                    "PASS resident frames, repeated slot reuse, explicit download and retained"
                        + " close");
        } finally {
            if (retained != null) retained.close();
            session.close();
            require(session.awaitClosed(30), "cleanup");
        }
    }

    private static void nativeWindow(GpuDevicePixels pixels) throws Exception {
        var surface =
                new GpuGameSurface(
                        () -> {},
                        error -> {
                            throw new AssertionError(error);
                        });
        var frame = new java.awt.Frame();
        try {
            java.awt.EventQueue.invokeAndWait(
                    () -> {
                        frame.add(surface.component());
                        frame.setSize(640, 360);
                        frame.addNotify();
                        frame.validate();
                    });
            require(!frame.isVisible(), "test must never show a window");
            var hwndField = GpuGameSurface.class.getDeclaredField("hwnd");
            hwndField.setAccessible(true);
            var instanceField = GpuGameSurface.class.getDeclaredField("hinstance");
            instanceField.setAccessible(true);
            long hwnd = hwndField.getLong(surface), instance = instanceField.getLong(surface);
            require(hwnd != 0 && instance != 0, "hidden JAWT handles");
            var swapchain =
                    pixels.session.onOwner(() -> new GpuSwapchain(pixels.gpu, hwnd, instance));
            try {
                for (int[] size : new int[][] {{640, 360}, {800, 500}, {640, 360}}) {
                    java.awt.EventQueue.invokeAndWait(
                            () -> {
                                frame.setSize(size[0], size[1]);
                                frame.validate();
                            });
                    pixels.session.onOwner(
                            () -> {
                                int submitted = 0;
                                for (int i = 0; i < 12; i++)
                                    if (swapchain.present(
                                            pixels,
                                            1,
                                            size[0],
                                            size[1],
                                            new int[] {0, size[0], size[1], 0})) submitted++;
                                require(submitted > 0, "hidden swapchain submission");
                                System.out.println(
                                        "PASS hidden AWT/JAWT swapchain "
                                                + size[0]
                                                + "x"
                                                + size[1]
                                                + ", submitted="
                                                + submitted);
                                return null;
                            });
                }
            } finally {
                pixels.session.onOwner(
                        () -> {
                            swapchain.close();
                            return null;
                        });
            }

        } finally {
            java.awt.EventQueue.invokeAndWait(frame::dispose);
            surface.close();
        }
    }

    private static void await(Semaphore ready, GpuRenderSession session) throws Exception {
        require(ready.tryAcquire(30, TimeUnit.SECONDS), "publication timeout");
        if (session.failure() != null) throw session.failure();
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
