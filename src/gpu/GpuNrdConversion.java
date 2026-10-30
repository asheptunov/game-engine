package gpu;

import engine.Camera;

import org.lwjgl.system.MemoryStack;

import java.nio.file.Path;

/** Packs and composes the NRD signal textures without a CPU readback. */
final class GpuNrdConversion implements AutoCloseable {
    private final GpuImagePass pass;

    GpuNrdConversion(VulkanDevice gpu) {
        pass = new GpuImagePass(gpu, Path.of("out/gpu/nrd.spv"), 5, 6, 48);
    }

    void bind(VulkanDevice.Buffer[] buffers, GpuImage[] images) {
        pass.bind(buffers, images);
    }

    void record(int width, int height, Camera camera, int compose, float confidence) {
        try (var stack = MemoryStack.stackPush()) {
            var push = stack.calloc(48).putInt(0, width).putInt(4, height).putInt(8, compose);
            var eye = camera.eye();
            var forward = GpuNrdCamera.forward(camera);
            push.putFloat(16, eye.x()).putFloat(20, eye.y()).putFloat(24, eye.z());
            push.putFloat(32, forward.x()).putFloat(36, forward.y()).putFloat(40, forward.z());
            push.putFloat(44, confidence);
            pass.record(width, height, push);
        }
    }

    public void close() {
        pass.close();
    }
}
