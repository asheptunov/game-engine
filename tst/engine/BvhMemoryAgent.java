package engine;

import java.lang.instrument.Instrumentation;

/** Optional benchmark agent measuring this JVM's shallow sizes of BVH-owned objects. */
public final class BvhMemoryAgent {
    private static Instrumentation instrumentation;

    private BvhMemoryAgent() {}

    public static void premain(String arguments, Instrumentation supplied) {
        instrumentation = supplied;
    }

    static long retainedBytes(PrimitiveBvh tree) {
        if (instrumentation == null) {
            return -1;
        }
        long bytes =
                instrumentation.getObjectSize(tree)
                        + instrumentation.getObjectSize(tree.nodes)
                        + instrumentation.getObjectSize(tree.order);
        for (var node : tree.nodes) {
            bytes +=
                    instrumentation.getObjectSize(node)
                            + instrumentation.getObjectSize(node.bounds);
        }
        return bytes;
    }
}
