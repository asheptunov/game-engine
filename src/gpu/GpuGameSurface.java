package gpu;

import static org.lwjgl.system.jawt.JAWTFunctions.JAWT_DrawingSurface_FreeDrawingSurfaceInfo;
import static org.lwjgl.system.jawt.JAWTFunctions.JAWT_DrawingSurface_GetDrawingSurfaceInfo;
import static org.lwjgl.system.jawt.JAWTFunctions.JAWT_DrawingSurface_Lock;
import static org.lwjgl.system.jawt.JAWTFunctions.JAWT_DrawingSurface_Unlock;
import static org.lwjgl.system.jawt.JAWTFunctions.JAWT_FreeDrawingSurface;
import static org.lwjgl.system.jawt.JAWTFunctions.JAWT_GetAWT;
import static org.lwjgl.system.jawt.JAWTFunctions.JAWT_GetDrawingSurface;
import static org.lwjgl.system.jawt.JAWTFunctions.JAWT_LOCK_ERROR;
import static org.lwjgl.system.jawt.JAWTFunctions.JAWT_VERSION_1_4;

import game.GameDisplay;
import game.GameRenderer;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.jawt.JAWT;
import org.lwjgl.system.jawt.JAWTWin32DrawingSurfaceInfo;

import java.awt.Canvas;
import java.awt.Graphics;

/** One coalesced pending presentation; AWT only owns the Canvas peer and event delivery. */
public final class GpuGameSurface implements GameDisplay {
    private record Request(
            GameRenderer.Frame frame,
            int width,
            int height,
            int[] overlay,
            java.util.function.Consumer<GameRenderer.Frame> shown) {}

    private final Canvas canvas;
    private final java.util.function.Consumer<Throwable> failure;
    private final java.util.concurrent.ExecutorService worker =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    r -> {
                        var thread = new Thread(r, "game-native-present");
                        thread.setDaemon(true);
                        return thread;
                    });
    private volatile long hwnd;
    private volatile long hinstance;
    private volatile boolean closed;
    private Request pending;
    private boolean scheduled;
    private GpuSwapchain swapchain;
    private GpuRenderSession session;

    public GpuGameSurface(Runnable repaint, java.util.function.Consumer<Throwable> failure) {
        this.failure = failure;
        canvas =
                new Canvas() {
                    @Override
                    public void addNotify() {
                        super.addNotify();
                        try {
                            attach();
                        } catch (Throwable error) {
                            failure.accept(error);
                        }
                    }

                    @Override
                    public void removeNotify() {
                        GpuGameSurface.this.close();
                        super.removeNotify();
                    }

                    @Override
                    public void paint(Graphics draw) {
                        repaint.run();
                    }

                    @Override
                    public void update(Graphics draw) {
                        paint(draw);
                    }
                };
        canvas.setFocusable(false);
        canvas.setBackground(java.awt.Color.BLACK);
    }

    private void attach() {
        try (var stack = MemoryStack.stackPush()) {
            var awt = JAWT.calloc(stack).version(JAWT_VERSION_1_4);
            if (!JAWT_GetAWT(awt)) {
                throw new IllegalStateException("JAWT unavailable");
            }
            var surface = JAWT_GetDrawingSurface(canvas, awt.GetDrawingSurface());
            if (surface == null) {
                throw new IllegalStateException("Canvas has no native surface");
            }
            try {
                int locked = JAWT_DrawingSurface_Lock(surface, surface.Lock());
                if ((locked & JAWT_LOCK_ERROR) != 0) {
                    throw new IllegalStateException("Cannot lock Canvas surface");
                }
                try {
                    var info =
                            JAWT_DrawingSurface_GetDrawingSurfaceInfo(
                                    surface, surface.GetDrawingSurfaceInfo());
                    if (info == null) {
                        throw new IllegalStateException("Canvas surface information unavailable");
                    }
                    try {
                        hwnd = JAWTWin32DrawingSurfaceInfo.create(info.platformInfo()).hwnd();
                        hinstance =
                                org.lwjgl.system.windows.User32.GetWindowLongPtr(
                                        stack.ints(0), hwnd, -6);
                        if (hwnd == 0 || hinstance == 0) {
                            throw new IllegalStateException("Invalid Canvas native handles");
                        }
                    } finally {
                        JAWT_DrawingSurface_FreeDrawingSurfaceInfo(
                                info, surface.FreeDrawingSurfaceInfo());
                    }
                } finally {
                    JAWT_DrawingSurface_Unlock(surface, surface.Unlock());
                }
            } finally {
                JAWT_FreeDrawingSurface(surface, awt.FreeDrawingSurface());
            }
        }
    }

    public java.awt.Component component() {
        return canvas;
    }

    public boolean ready() {
        return hwnd != 0 && !closed && canvas.isShowing();
    }

    public synchronized void submit(
            GameRenderer.Frame frame,
            int width,
            int height,
            int[] overlay,
            java.util.function.Consumer<GameRenderer.Frame> shown) {
        if (closed || !(frame.devicePixels() instanceof GpuDevicePixels)) {
            return;
        }
        var next = new Request(frame.retain(), width, height, overlay, shown);
        if (pending != null) {
            pending.frame().release();
        }
        pending = next;
        if (!scheduled) {
            scheduled = true;
            worker.execute(this::drain);
        }
    }

    private void drain() {
        for (; ; ) {
            Request request;
            synchronized (this) {
                request = pending;
                pending = null;
                if (request == null) {
                    scheduled = false;
                    return;
                }
            }
            try {
                if (closed) {
                    continue;
                }
                boolean shown = present(request);
                if (shown && !closed) {
                    request.shown().accept(request.frame());
                }
            } catch (Throwable error) {
                if (!closed) {
                    failure.accept(error);
                }
                close();
            } finally {
                request.frame().release();
            }
        }
    }

    private boolean present(Request request) {
        var pixels = (GpuDevicePixels) request.frame().devicePixels();
        session = pixels.session;
        return session.onOwner(
                () -> {
                    if (closed) {
                        return false;
                    }
                    if (swapchain == null) {
                        swapchain = new GpuSwapchain(pixels.gpu, hwnd, hinstance);
                    }
                    return swapchain.present(
                            pixels,
                            request.frame().displayExposure(),
                            request.width(),
                            request.height(),
                            request.overlay(),
                            request.frame().displayBloom());
                });
    }

    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (pending != null) {
            pending.frame().release();
            pending = null;
        }
        worker.execute(
                () -> {
                    if (session != null && !session.closed()) {
                        try {
                            session.onOwner(
                                    () -> {
                                        if (swapchain != null) {
                                            swapchain.close();
                                        }
                                        return null;
                                    });
                        } catch (IllegalStateException closing) {
                            /* Session retirement owns the surface now. */
                        }
                    }
                });
        worker.shutdown();
    }
}
