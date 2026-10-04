package profiling;

/** Exact per-trace metadata, separate from statistical CPU samples and exclusive frame timings. */
public final class TraceProfile {
    private TraceProfile() {}
    public record Stats(long cpuNanos, long allocatedBytes, long primaryTests, long shadowTests,
                        String scene, int depth, int samplesPerPixel, long continuationRays) {
        public Stats(long cpuNanos, long allocatedBytes, long primaryTests, long shadowTests) {
            this(cpuNanos, allocatedBytes, primaryTests, shadowTests, null, 0, 1, 0);
        }
    }
}
