package profiling;

import java.lang.management.ManagementFactory;

/** JVM/OS telemetry only; unsupported counters remain -1 rather than looking like zero load. */
public final class RuntimeMetrics {
    private static final java.lang.management.ThreadMXBean THREADS =
            ManagementFactory.getThreadMXBean();
    private static final com.sun.management.ThreadMXBean ALLOCATIONS =
            THREADS instanceof com.sun.management.ThreadMXBean bean ? bean : null;
    private final com.sun.management.OperatingSystemMXBean os =
            ManagementFactory.getOperatingSystemMXBean()
                            instanceof com.sun.management.OperatingSystemMXBean bean
                    ? bean
                    : null;
    private long previousGcMillis = -1, previousGcCount = -1;

    public record Snapshot(
            int processors,
            double processCpu,
            double systemCpu,
            long heapUsed,
            long heapMax,
            long totalMemory,
            long freeMemory,
            long gcMillis,
            long gcCount) {}

    public static long threadCpu() {
        return THREADS.isCurrentThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled()
                ? THREADS.getCurrentThreadCpuTime()
                : -1;
    }

    public static long allocatedBytes() {
        return ALLOCATIONS != null
                        && ALLOCATIONS.isThreadAllocatedMemorySupported()
                        && ALLOCATIONS.isThreadAllocatedMemoryEnabled()
                ? ALLOCATIONS.getThreadAllocatedBytes(Thread.currentThread().threadId())
                : -1;
    }

    public static long delta(long before, long after) {
        return before < 0 || after < 0 ? -1 : Math.max(0, after - before);
    }

    public Snapshot sample() {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long millis = 0, count = 0;
        boolean supported = false;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionTime() >= 0 && gc.getCollectionCount() >= 0) {
                supported = true;
                millis += gc.getCollectionTime();
                count += gc.getCollectionCount();
            }
        }
        long msDelta = supported ? delta(previousGcMillis, millis) : -1;
        long countDelta = supported ? delta(previousGcCount, count) : -1;
        previousGcMillis = millis;
        previousGcCount = count;
        return new Snapshot(
                Runtime.getRuntime().availableProcessors(),
                os == null ? -1 : os.getProcessCpuLoad(),
                os == null ? -1 : os.getCpuLoad(),
                heap.getUsed(),
                heap.getMax(),
                os == null ? -1 : os.getTotalMemorySize(),
                os == null ? -1 : os.getFreeMemorySize(),
                msDelta,
                countDelta);
    }
}
