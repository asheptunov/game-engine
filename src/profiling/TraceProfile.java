package profiling;

/** Exact per-trace metadata, separate from statistical CPU samples and exclusive frame timings. */
public final class TraceProfile {
    private TraceProfile() {}
    public record Stats(long cpuNanos, long allocatedBytes, long primaryTests, long shadowTests,
                        String scene, int depth, int samplesPerPixel, long continuationRays,
                        long continuationTests, long accumulatedSamples, String samplingStatus, long seed,
                        long dielectricReflections, long dielectricTransmissions, long absorptionSegments,
                        long areaLightSamples, long emitterHits, long roughEvents) {
        public Stats(long cpuNanos, long allocatedBytes, long primaryTests, long shadowTests,
                     String scene, int depth, int samplesPerPixel, long continuationRays,
                     long continuationTests, long accumulatedSamples, String samplingStatus, long seed,
                     long dielectricReflections, long dielectricTransmissions, long absorptionSegments) {
            this(cpuNanos,allocatedBytes,primaryTests,shadowTests,scene,depth,samplesPerPixel,continuationRays,
                    continuationTests,accumulatedSamples,samplingStatus,seed,dielectricReflections,dielectricTransmissions,absorptionSegments,0,0,0);
        }
        public Stats(long cpuNanos, long allocatedBytes, long primaryTests, long shadowTests,
                     String scene, int depth, int samplesPerPixel, long continuationRays,
                     long continuationTests, long accumulatedSamples, String samplingStatus, long seed) {
            this(cpuNanos, allocatedBytes, primaryTests, shadowTests, scene, depth, samplesPerPixel,
                    continuationRays, continuationTests, accumulatedSamples, samplingStatus, seed, 0, 0, 0);
        }
        public Stats(long cpuNanos, long allocatedBytes, long primaryTests, long shadowTests,
                     String scene, int depth, int samplesPerPixel, long continuationRays) {
            this(cpuNanos, allocatedBytes, primaryTests, shadowTests, scene, depth, samplesPerPixel,
                    continuationRays, 0, 0, "direct", 0);
        }
        public Stats(long cpuNanos, long allocatedBytes, long primaryTests, long shadowTests) {
            this(cpuNanos, allocatedBytes, primaryTests, shadowTests, null, 0, 1, 0);
        }
    }
}
