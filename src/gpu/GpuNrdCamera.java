package gpu;

import engine.Camera;

import math.Vec3;

/** NRD uses column-major matrices, +Z view depth and top-left texture coordinates. */
final class GpuNrdCamera {
    private GpuNrdCamera() {}

    static Vec3 forward(Camera camera) {
        var normal = camera.sensor().edge1().cross(camera.sensor().edge2()).normalized();
        return normal.dot(camera.sensor().origin().sub(camera.eye())) > 0
                ? normal
                : normal.negate();
    }

    static float[] matrices(Camera camera, Camera previous) {
        float[] result = new float[64];
        projection(result, 0, camera);
        projection(result, 16, previous);
        view(result, 32, camera);
        view(result, 48, previous);
        return result;
    }

    private static void projection(float[] matrix, int offset, Camera camera) {
        var sensor = camera.sensor();
        var delta = sensor.origin().sub(camera.eye());
        float width = sensor.edge1().length();
        float height = sensor.edge2().length();
        float distance = delta.dot(forward(camera));
        matrix[offset] = 2 * distance / width;
        matrix[offset + 5] = 2 * distance / height;
        matrix[offset + 8] = -1 - 2 * delta.dot(sensor.edge1().normalized()) / width;
        matrix[offset + 9] = -1 - 2 * delta.dot(sensor.edge2().normalized()) / height;
        // Finite D3D/Vulkan depth in [0,1], near 0.01 and far 100000 scene units.
        matrix[offset + 10] = 100000f / (100000f - .01f);
        matrix[offset + 11] = 1;
        matrix[offset + 14] = -.01f * matrix[offset + 10];
    }

    private static void view(float[] matrix, int offset, Camera camera) {
        Vec3[] axes = {
            camera.sensor().edge1().normalized(),
            camera.sensor().edge2().normalized(),
            forward(camera)
        };
        for (int row = 0; row < 3; row++) {
            matrix[offset + row] = axes[row].x();
            matrix[offset + 4 + row] = axes[row].y();
            matrix[offset + 8 + row] = axes[row].z();
            matrix[offset + 12 + row] = -axes[row].dot(camera.eye());
        }
        matrix[offset + 15] = 1;
    }
}
