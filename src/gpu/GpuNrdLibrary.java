package gpu;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/** Pinned NRD scheduling ABI. All instance calls belong to one GPU owner thread. */
final class GpuNrdLibrary implements AutoCloseable {
    private boolean closed;

    static boolean available() {
        var directory = Path.of("out/nrd/runtime/Release");
        return java.nio.file.Files.isRegularFile(directory.resolve("NRD.dll"))
                && java.nio.file.Files.isRegularFile(directory.resolve("rte_nrd.dll"));
    }

    private static final class Api {
        static final SymbolLookup LIBRARY = load();
        static final MethodHandle CREATE = function("rteNrdCreate", FunctionDescriptor.of(ADDRESS));
        static final MethodHandle DESTROY =
                function("rteNrdDestroy", FunctionDescriptor.ofVoid(ADDRESS));
        static final MethodHandle INFO =
                function("rteNrdInfo", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        static final MethodHandle PIPELINE =
                function(
                        "rteNrdPipeline",
                        FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        static final MethodHandle TEXTURE =
                function(
                        "rteNrdTexture",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        static final MethodHandle FRAME =
                function(
                        "rteNrdFrame",
                        FunctionDescriptor.of(
                                JAVA_INT,
                                ADDRESS,
                                ADDRESS,
                                JAVA_INT,
                                JAVA_INT,
                                JAVA_INT,
                                JAVA_INT,
                                JAVA_FLOAT));
        static final MethodHandle DISPATCH =
                function(
                        "rteNrdDispatch",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

        private static SymbolLookup load() {
            var directory = Path.of("out/nrd/runtime/Release").toAbsolutePath();
            System.load(directory.resolve("NRD.dll").toString());
            return SymbolLookup.libraryLookup(directory.resolve("rte_nrd.dll"), Arena.global());
        }

        private static MethodHandle function(String name, FunctionDescriptor descriptor) {
            return Linker.nativeLinker()
                    .downcallHandle(LIBRARY.find(name).orElseThrow(), descriptor);
        }
    }

    private final Arena arena = Arena.ofConfined();
    private MemorySegment instance;
    private final MemorySegment scratch = arena.allocate(512, 8);
    private final MemorySegment matrices = arena.allocate(64 * 4, 8);
    final int[] info;

    GpuNrdLibrary() {
        try {
            instance = (MemorySegment) Api.CREATE.invokeExact();
            if (instance.address() == 0) {
                throw new IllegalStateException("NRD instance creation failed");
            }
            Api.INFO.invokeExact(instance, scratch);
            info = ints(14);
            if (info[10] != 2 || info[11] != 4 || info[12] != 18 || info[13] != 0) {
                throw new IllegalStateException("NRD must be pinned v4.18.0 with packed normals");
            }
        } catch (Throwable failure) {
            close();
            throw new IllegalStateException("NRD unavailable; run setup-nrd.ps1", failure);
        }
    }

    record Pipeline(ByteBuffer shader, int textures, int storage, boolean constants) {}

    Pipeline pipeline(int index) {
        try {
            var address = (MemorySegment) Api.PIPELINE.invokeExact(instance, index, scratch);
            var values = ints(4);
            return new Pipeline(
                    address.reinterpret(values[0]).asByteBuffer(),
                    values[1],
                    values[2],
                    values[3] != 0);
        } catch (Throwable failure) {
            throw new IllegalStateException("NRD pipeline metadata", failure);
        }
    }

    int texture(boolean permanent, int index) {
        try {
            return (int) Api.TEXTURE.invokeExact(instance, permanent ? 1 : 0, index);
        } catch (Throwable failure) {
            throw new IllegalStateException("NRD texture metadata", failure);
        }
    }

    int frame(float[] values, int width, int height, int frame, boolean reset, float millis) {
        matrices.copyFrom(MemorySegment.ofArray(values));
        try {
            int count =
                    (int)
                            Api.FRAME.invokeExact(
                                    instance,
                                    matrices,
                                    width,
                                    height,
                                    frame,
                                    reset ? 1 : 0,
                                    millis);
            if (count < 0) {
                throw new IllegalStateException("NRD frame settings rejected: " + count);
            }
            return count;
        } catch (Throwable failure) {
            throw new IllegalStateException("NRD frame dispatch", failure);
        }
    }

    int[] dispatch(int index, ByteBuffer constants) {
        try {
            int result =
                    (int)
                            Api.DISPATCH.invokeExact(
                                    instance, index, scratch, MemorySegment.ofBuffer(constants));
            if (result != 0) {
                throw new IllegalStateException("NRD dispatch resources rejected: " + result);
            }
            return ints(5 + scratch.getAtIndex(JAVA_INT, 3) * 3);
        } catch (Throwable failure) {
            throw new IllegalStateException("NRD dispatch metadata", failure);
        }
    }

    private int[] ints(int count) {
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = scratch.getAtIndex(JAVA_INT, i);
        }
        return values;
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
            throw new IllegalStateException("NRD destruction failed", failure);
        } finally {
            instance = null;
            arena.close();
        }
    }
}
