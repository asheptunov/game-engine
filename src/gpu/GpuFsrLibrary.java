package gpu;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/** Owner-thread ABI for the pinned, AMD-signed Vulkan upscaler. It never submits work itself. */
final class GpuFsrLibrary implements AutoCloseable {
    static boolean available() {
        var directory = Path.of("out/fsr/runtime/Release");
        return Files.isRegularFile(directory.resolve("amd_fidelityfx_vk.dll"))
                && Files.isRegularFile(directory.resolve("rte_fsr.dll"));
    }

    private static final class Api {
        static final SymbolLookup LIBRARY = load();
        static final MethodHandle CREATE =
                function(
                        "rteFsrCreate",
                        FunctionDescriptor.of(
                                ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT,
                                JAVA_INT, ADDRESS));
        static final MethodHandle DESTROY =
                function("rteFsrDestroy", FunctionDescriptor.ofVoid(ADDRESS));
        static final MethodHandle VERSION =
                function("rteFsrVersion", FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle MEMORY =
                function("rteFsrMemory", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle JITTER =
                function(
                        "rteFsrJitter",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        static final MethodHandle DISPATCH =
                function(
                        "rteFsrDispatch",
                        FunctionDescriptor.of(
                                JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));

        private static SymbolLookup load() {
            var directory = Path.of("out/fsr/runtime/Release").toAbsolutePath();
            System.load(directory.resolve("amd_fidelityfx_vk.dll").toString());
            return SymbolLookup.libraryLookup(directory.resolve("rte_fsr.dll"), Arena.global());
        }

        private static MethodHandle function(String name, FunctionDescriptor descriptor) {
            return Linker.nativeLinker()
                    .downcallHandle(LIBRARY.find(name).orElseThrow(), descriptor);
        }
    }

    private final Arena arena = Arena.ofConfined();
    private final MemorySegment scratch = arena.allocate(64, 8);
    private final MemorySegment images = arena.allocate(40, 8);
    private final MemorySegment scalars = arena.allocate(28, 4);
    private MemorySegment instance;
    private boolean closed;
    final String version;
    final long memory;
    float jitterX;
    float jitterY;

    GpuFsrLibrary(VulkanDevice gpu, int width, int height, int outputWidth, int outputHeight) {
        try {
            long getProc =
                    org.lwjgl
                            .vulkan
                            .VK
                            .getFunctionProvider()
                            .getFunctionAddress("vkGetDeviceProcAddr");
            instance =
                    (MemorySegment)
                            Api.CREATE.invokeExact(
                                    MemorySegment.ofAddress(gpu.device.address()),
                                    MemorySegment.ofAddress(gpu.physical.address()),
                                    MemorySegment.ofAddress(getProc),
                                    width,
                                    height,
                                    outputWidth,
                                    outputHeight,
                                    scratch);
            if (instance.address() == 0) {
                throw new IllegalStateException("FSR context error " + scratch.get(JAVA_INT, 0));
            }
            var name = (MemorySegment) Api.VERSION.invokeExact(instance);
            version = name.address() == 0 ? "FSR" : name.reinterpret(256).getString(0);
            memory = (long) Api.MEMORY.invokeExact(instance);
        } catch (Throwable failure) {
            close();
            throw new IllegalStateException(
                    "FSR initialization failed; run setup-fsr.ps1", failure);
        }
    }

    void jitter(int frame) {
        try {
            check((int) Api.JITTER.invokeExact(instance, frame, scratch));
            jitterX = scratch.getAtIndex(JAVA_FLOAT, 0);
            jitterY = scratch.getAtIndex(JAVA_FLOAT, 1);
        } catch (Throwable failure) {
            throw new IllegalStateException("FSR jitter", failure);
        }
    }

    void record(long command, long[] handles, float millis, float verticalFov, boolean reset) {
        images.copyFrom(MemorySegment.ofArray(handles));
        scalars.copyFrom(
                MemorySegment.ofArray(
                        new float[] {jitterX, jitterY, millis, .01f, 100000, verticalFov, 1}));
        try {
            check(
                    (int)
                            Api.DISPATCH.invokeExact(
                                    instance,
                                    MemorySegment.ofAddress(command),
                                    images,
                                    scalars,
                                    reset ? 1 : 0));
        } catch (Throwable failure) {
            throw new IllegalStateException("FSR dispatch", failure);
        }
    }

    private static void check(int status) {
        if (status != 0) {
            throw new IllegalStateException("FSR API status " + status);
        }
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (instance != null && instance.address() != 0) {
                Api.DESTROY.invokeExact(instance);
            }
        } catch (Throwable failure) {
            throw new IllegalStateException("FSR destruction", failure);
        } finally {
            instance = null;
            arena.close();
        }
    }
}
