package profiling;

/** Exact per-trace metadata, separate from statistical CPU samples and exclusive frame timings. */
public final class TraceProfile {
    private TraceProfile() {}
    public record Stats(long cpuNanos, long allocatedBytes, long primaryTests, long shadowTests) {}
}
