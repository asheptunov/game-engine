package game;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;
import java.util.Arrays;

/**
 * Controlled window-free timing: Windows normally may ignore an invisible process's timer request.
 * This wrapper optionally honors that request in this process, then restores its original policy.
 */
public final class DeliveryRouteBenchmark {
    public static void main(String[] args) throws Throwable {
        if (!args[0].equals("system") && !args[0].equals("honor"))
            throw new IllegalArgumentException("Timer policy must be system or honor");
        boolean honor = args[0].equals("honor");
        try (var arena = Arena.ofConfined()) {
            var library = SymbolLookup.libraryLookup("kernel32.dll", arena);
            var descriptor =
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT);
            var linker = Linker.nativeLinker();
            var get =
                    linker.downcallHandle(
                            library.find("GetProcessInformation").orElseThrow(), descriptor);
            var set =
                    linker.downcallHandle(
                            library.find("SetProcessInformation").orElseThrow(), descriptor);
            var saved = arena.allocate(12, 4);
            saved.set(ValueLayout.JAVA_INT, 0, 1);
            call(get, saved);
            var next = arena.allocate(12, 4);
            next.copyFrom(saved);
            // PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION = 4. Preserve all other flags.
            next.set(ValueLayout.JAVA_INT, 4, saved.get(ValueLayout.JAVA_INT, 4) | 4);
            next.set(ValueLayout.JAVA_INT, 8, saved.get(ValueLayout.JAVA_INT, 8) & ~4);
            if (honor) call(set, next);
            try {
                System.out.println("timerPolicy=" + args[0]);
                LightingRouteBenchmark.main(Arrays.copyOfRange(args, 1, args.length));
                try (var files = Files.walk(Path.of(args[1]))) {
                    for (var file :
                            files.filter(p -> p.getFileName().toString().endsWith("-settings.txt"))
                                    .toList())
                        Files.writeString(
                                file, "timerPolicy=" + args[0] + "\n", StandardOpenOption.APPEND);
                }
            } finally {
                if (honor) call(set, saved);
            }
        }
    }

    private static void call(MethodHandle function, MemorySegment state) throws Throwable {
        // Pseudo-handle for the current process; ProcessPowerThrottling information class = 4.
        if ((int) function.invokeExact(MemorySegment.ofAddress(-1), 4, state, 12) == 0)
            throw new IllegalStateException("Windows timer-policy query/update failed");
    }
}
