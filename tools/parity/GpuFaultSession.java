package gpu;

import engine.*;

/** Device faults only exist in this separately compiled verification fixture. */
public final class GpuFaultSession {
    public static volatile boolean armed = true;
    public static RenderSession open(WorldSnapshot world, RenderView view, RenderSettings settings,
            String mode) {
        return new GpuRenderSession(world, view, settings, () -> {
            if (mode.equals("gpu-init")) throw new IllegalStateException("injected GPU initialization failure",
                    new UnsupportedOperationException("original GPU init cause"));
            return new GpuKernel(() -> {
                if (armed) throw new IllegalStateException("injected GPU execution failure",
                        new IllegalStateException("original GPU cause: Vulkan result -4 (device lost)"));
            });
        });
    }
}
