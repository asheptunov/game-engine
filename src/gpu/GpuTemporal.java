package gpu;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;

import engine.Camera;
import engine.RenderSettings;
import engine.RenderView;
import engine.SceneInstance;
import engine.WorldSnapshot;

import math.Vec3;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** GPU-owned diffuse histories. A refining view reads a frozen prior view, never its own mean. */
final class GpuTemporal implements AutoCloseable {
    private record Surface(SceneInstance instance, int identity) {}

    private final VulkanDevice gpu;
    private GpuTemporalPass pass;
    GpuNrd nrd;
    private final VulkanDevice.Buffer[] guides = new VulkanDevice.Buffer[2];
    private final VulkanDevice.Buffer[] history = new VulkanDevice.Buffer[2];
    VulkanDevice.Buffer motion;
    VulkanDevice.Buffer presented;
    private int current;
    private int nextIdentity = 1;
    private Map<String, Surface> surfaces = Map.of();
    private Map<String, Surface> priorSurfaces = Map.of();
    private WorldSnapshot world;
    private RenderView view;
    private RenderSettings settings;
    private RenderView priorView;
    private long lastFrame;
    private long priorFrame;
    private engine.Transform[] uploadedTransforms = new engine.Transform[0];
    private int[] uploadedIdentities = new int[0];
    private boolean[] uploadedValidity = new boolean[0];
    private boolean valid;
    boolean active;
    String label = "raw GPU";

    GpuTemporal(VulkanDevice gpu) {
        this.gpu = gpu;
        motion = gpu.buffer(128, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
        guides[0] = gpu.buffer(96, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
        guides[1] = gpu.buffer(96, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
        motion.mapped.putFloat(0, 0);
    }

    static String fallback(RenderView view, RenderSettings settings) {
        if (!settings.temporal()) {
            return "raw GPU";
        }
        if (view.camera().aperture() > 0) {
            return "GPU temporal: pinhole required";
        }
        if (settings.foveated()) {
            return "GPU temporal: uniform sampling required";
        }
        if (settings.fogDownsample() != 1) {
            return "GPU temporal: full atmosphere required";
        }
        if (settings.pathDepth() == 0) {
            return "GPU temporal: path depth must be positive";
        }
        if (settings.denoiser() == engine.Denoiser.NRD_RELAX
                && Math.abs(
                                view.camera()
                                        .sensor()
                                        .edge1()
                                        .normalized()
                                        .dot(view.camera().sensor().edge2().normalized()))
                        > 1e-4f) {
            return "NRD: orthogonal sensor axes required";
        }
        return null;
    }

    void reset() {
        world = null;
        surfaces = Map.of();
        priorSurfaces = Map.of();
        valid = false;
    }

    boolean prepare(
            WorldSnapshot nextWorld, RenderView nextView, RenderSettings next, long sample) {
        boolean useNrd = next.denoiser() == engine.Denoiser.NRD_RELAX;
        label = fallback(nextView, next);
        active = label == null;
        if (!active) {
            motion.mapped.putFloat(0, 0);
            motion.mapped.putFloat(96, 0);
            if (nrd != null) {
                nrd.close();
                nrd = null;
            }
            reset();
            return false;
        }
        label = useNrd ? "NRD RELAX diffuse" : "GPU diffuse temporal";
        if (!useNrd && nrd != null) {
            nrd.close();
            nrd = null;
        }
        prepareImages(nextView);
        long now = System.nanoTime();
        if (sample == 0 || world == null || useNrd) {
            beginView(nextWorld, nextView, next, now, sample);
        }
        lastFrame = now;
        valid &= now - priorFrame < 500_000_000L;
        writeMotion(nextWorld, nextView);
        motion.mapped.putFloat(96, useNrd ? 1 : 0);
        if (useNrd) {
            if (nrd == null) {
                nrd = new GpuNrd(gpu);
                valid = false;
            }
            nrd.prepare(nextView, priorView, valid);
            motion.mapped.putInt(100, nrd.frameIndex);
        }
        return true; // The trace descriptor must follow the guide ping-pong and motion buffer.
    }

    private void prepareImages(RenderView nextView) {
        if (pass == null) {
            pass = new GpuTemporalPass(gpu);
        }
        long bytes = (long) nextView.width() * nextView.height() * 16;
        if (guides[0].size != bytes * 6) {
            releaseImages();
            for (int i = 0; i < 2; i++) {
                guides[i] =
                        gpu.buffer(
                                bytes * 6,
                                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                                        | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                                false);
                history[i] = gpu.buffer(bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
            }
            presented =
                    gpu.buffer(
                            bytes,
                            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                            false);
            reset();
        }
    }

    private void beginView(
            WorldSnapshot nextWorld,
            RenderView nextView,
            RenderSettings next,
            long now,
            long sample) {
        // Identities are stored as exactly representable floats in GPU guides.
        if (nextIdentity > 8_000_000) {
            reset();
            nextIdentity = 1;
        }
        valid =
                world != null
                        && compatible(nextWorld, nextView, next)
                        && now - lastFrame < 500_000_000L;
        // Repeated sample zero of the identical scene is the same estimator, not fresh history.
        valid &=
                world != null
                        && (next.denoiser() == engine.Denoiser.NRD_RELAX && sample > 0
                                || !world.equals(nextWorld)
                                || !view.equals(nextView));
        priorView = view;
        priorFrame = lastFrame;
        priorSurfaces = surfaces;
        current = 1 - current;
        surfaces = world == nextWorld ? priorSurfaces : identifySurfaces(nextWorld);
        world = nextWorld;
        view = nextView;
        settings = next;
    }

    private Map<String, Surface> identifySurfaces(WorldSnapshot nextWorld) {
        var identities = new HashMap<String, Surface>();
        var duplicateNames = new java.util.HashSet<String>();
        for (var object : nextWorld.instances()) {
            var old = priorSurfaces.get(object.name());
            boolean unchanged = old != null && sameSurface(old.instance(), object);
            if (identities.containsKey(object.name())) {
                duplicateNames.add(object.name());
            }
            identities.put(
                    object.name(),
                    unchanged && object == old.instance()
                            ? old
                            : new Surface(object, unchanged ? old.identity() : nextIdentity++));
        }
        // Ambiguous external input cannot establish stable object correspondence.
        for (var name : duplicateNames) {
            identities.put(name, new Surface(identities.get(name).instance(), 0));
        }
        return Map.copyOf(identities);
    }

    private void writeMotion(WorldSnapshot nextWorld, RenderView nextView) {
        long motionBytes = 128L + nextWorld.instances().size() * 64L;
        if (motion.size < motionBytes) {
            long capacity = Math.max(motionBytes, motion.size * 2);
            motion.close();
            motion = gpu.buffer(capacity, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
            uploadedTransforms = new engine.Transform[Math.toIntExact((capacity - 128) / 64)];
            uploadedIdentities = new int[uploadedTransforms.length];
            uploadedValidity = new boolean[uploadedTransforms.length];
        }
        var data = motion.mapped;
        data.putFloat(0, 1);
        var previous = priorView == null ? nextView.camera() : priorView.camera();
        put(data, 16, previous.eye(), 0);
        put(data, 32, previous.sensor().origin(), 0);
        put(data, 48, previous.sensor().edge1(), 0);
        put(data, 64, previous.sensor().edge2(), 0);
        data.putFloat(80, pixelAngle(previous, nextView));
        for (int i = 0; i < nextWorld.instances().size(); i++) {
            var object = nextWorld.instances().get(i);
            var old = priorSurfaces.get(object.name());
            boolean match =
                    valid
                            && old != null
                            && old.identity() != 0
                            && surfaces.get(object.name()).identity() != 0
                            && sameSurface(old.instance(), object);
            var transform = match ? old.instance().transform() : object.transform();
            int identity = surfaces.get(object.name()).identity();
            if (uploadedTransforms[i] == transform
                    && uploadedIdentities[i] == identity
                    && uploadedValidity[i] == match) {
                continue;
            }
            uploadedTransforms[i] = transform;
            uploadedIdentities[i] = identity;
            uploadedValidity[i] = match;
            var x = transform.vector(new Vec3(1, 0, 0));
            var y = transform.vector(new Vec3(0, 1, 0));
            var z = transform.vector(new Vec3(0, 0, 1));
            var p = transform.position;
            int offset = 128 + i * 64;
            put(data, offset, new Vec3(x.x(), y.x(), z.x()), p.x());
            put(data, offset + 16, new Vec3(x.y(), y.y(), z.y()), p.y());
            put(data, offset + 32, new Vec3(x.z(), y.z(), z.z()), p.z());
            put(data, offset + 48, new Vec3(identity, match ? 1 : 0, 0), 0);
        }
    }

    private boolean compatible(WorldSnapshot nextWorld, RenderView nextView, RenderSettings next) {
        return view.width() == nextView.width()
                && view.height() == nextView.height()
                && world.sky().equals(nextWorld.sky())
                && world.lights().equals(nextWorld.lights())
                && settings.denoiser() == next.denoiser()
                && settings.pathRoulette() == next.pathRoulette()
                && settings.seed() == next.seed()
                && settings.pathDepth() == next.pathDepth()
                && settings.restartRevision() == next.restartRevision()
                && settings.temporalRevision() == next.temporalRevision()
                && settings.fogLightingGrid() == next.fogLightingGrid()
                && settings.fogJitter() == next.fogJitter();
    }

    private static boolean sameSurface(SceneInstance before, SceneInstance after) {
        return before.geometry() == after.geometry() && before.material().equals(after.material());
    }

    private static float pixelAngle(Camera camera, RenderView view) {
        var plane = camera.sensor();
        float distance =
                Math.abs(
                        plane.origin()
                                .sub(camera.eye())
                                .dot(plane.edge1().cross(plane.edge2()).normalized()));
        return Math.max(
                        plane.edge1().length() / view.width(),
                        plane.edge2().length() / view.height())
                / distance;
    }

    private static void put(ByteBuffer data, int offset, Vec3 value, float w) {
        data.putFloat(offset, value.x())
                .putFloat(offset + 4, value.y())
                .putFloat(offset + 8, value.z())
                .putFloat(offset + 12, w);
    }

    VulkanDevice.Buffer guide() {
        return guides[current];
    }

    VulkanDevice.Buffer record(
            VulkanDevice.Buffer raw,
            VulkanDevice.Buffer albedo,
            int width,
            int height,
            long sample) {
        if (!active) {
            return raw;
        }
        if (nrd != null) {
            nrd.record(raw, guide(), albedo, presented);
            return presented;
        }
        pass.record(
                new VulkanDevice.Buffer[] {
                    raw,
                    guide(),
                    guides[1 - current],
                    history[1 - current],
                    history[current],
                    presented,
                    motion
                },
                width,
                height,
                Math.toIntExact(sample + 1),
                valid);
        return presented;
    }

    private void releaseImages() {
        for (var buffer : guides) {
            if (buffer != null) {
                buffer.close();
            }
        }
        for (var buffer : history) {
            if (buffer != null) {
                buffer.close();
            }
        }
        if (presented != null) {
            presented.close();
        }
    }

    @Override
    public void close() {
        releaseImages();
        motion.close();
        if (nrd != null) {
            nrd.close();
            nrd = null;
        }
        if (pass != null) {
            pass.close();
        }
    }
}
