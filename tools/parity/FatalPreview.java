package game;

import engine.*;

/** Manual-only real-window preview: capture the mouse before the failure arms after ten seconds. */
public final class FatalPreview {
    public static void main(String[] args) throws Exception {
        String backend = args.length == 0 ? "cpu" : args[0];
        CpuFaultSession.armed = false;
        Class<?> gpu = backend.equals("gpu") ? Class.forName("gpu.GpuFaultSession") : null;
        if (gpu != null) gpu.getField("armed").setBoolean(null, false);
        var arm = new Thread(() -> {
            try {
                Thread.sleep(10000);
                CpuFaultSession.armed = true;
                if (gpu != null) gpu.getField("armed").setBoolean(null, true);
            } catch (Exception error) { throw new IllegalStateException(error); }
        }, "manual-fault-arm");
        arm.setDaemon(true);
        arm.start();
        SampleGameMain.launch(new String[] {"world", backend, "480", "300"}, (world, view, settings, selected) -> {
            if (gpu == null) return CpuFaultSession.open(world, view, settings);
            try {
                return (RenderSession) gpu.getMethod("open", WorldSnapshot.class, RenderView.class, RenderSettings.class, String.class)
                        .invoke(null, world, view, settings, "gpu-execution");
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        });
    }
}
