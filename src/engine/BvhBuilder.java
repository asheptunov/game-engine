package engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/** Preparation-only builder. Sixteen centroid bins estimate child surface-area traversal cost. */
final class BvhBuilder {
    static final int MAX_DEPTH = 30;
    private static final int BIN_COUNT = 16;

    private record Split(int axis, int boundary, float minimum, double span, double cost) {}

    private final PrimitiveBvh.Bounds[] bounds;
    private final int leafSize;
    private final boolean surfaceArea;
    private final Integer[] scratch;
    private final PrimitiveBvh.Bounds[] bins;
    private final int[] counts;
    private final int[] prefixCounts;
    private final double[] prefixAreas;
    private final PrimitiveBvh.Bounds cumulative;
    int maximumDepth;

    BvhBuilder(PrimitiveBvh.Bounds[] bounds, int leafSize, boolean surfaceArea) {
        this.bounds = bounds;
        this.leafSize = leafSize;
        this.surfaceArea = surfaceArea;
        scratch = surfaceArea ? new Integer[bounds.length] : null;
        bins = surfaceArea ? bins() : null;
        counts = surfaceArea ? new int[BIN_COUNT] : null;
        prefixCounts = surfaceArea ? new int[BIN_COUNT] : null;
        prefixAreas = surfaceArea ? new double[BIN_COUNT] : null;
        cumulative = surfaceArea ? new PrimitiveBvh.Bounds() : null;
    }

    private static PrimitiveBvh.Bounds[] bins() {
        var result = new PrimitiveBvh.Bounds[BIN_COUNT];
        for (int i = 0; i < result.length; i++) {
            result[i] = new PrimitiveBvh.Bounds();
        }
        return result;
    }

    void build(ArrayList<PrimitiveBvh.Node> nodes, Integer[] ids, int from, int to, int depth) {
        maximumDepth = Math.max(maximumDepth, depth);
        var combined = new PrimitiveBvh.Bounds();
        for (int i = from; i < to; i++) {
            combined.include(bounds[ids[i]]);
        }
        // The cap bounds both preparation recursion and P8's 32-entry traversal stacks.
        boolean leaf = to - from <= leafSize || depth >= MAX_DEPTH;
        var node = new PrimitiveBvh.Node(combined, from, leaf ? to : -1);
        nodes.add(node);
        if (!leaf) {
            int middle = partition(ids, from, to, combined);
            build(nodes, ids, from, middle, depth + 1);
            node.right = nodes.size();
            build(nodes, ids, middle, to, depth + 1);
        }
        node.escape = nodes.size();
    }

    private int partition(Integer[] ids, int from, int to, PrimitiveBvh.Bounds combined) {
        Split split = surfaceArea ? chooseSplit(ids, from, to) : null;
        if (split != null) {
            int middle = partitionBins(ids, from, to, split);
            if (middle > from && middle < to) {
                return middle;
            }
        }
        float x = combined.maxX - combined.minX;
        float y = combined.maxY - combined.minY;
        float z = combined.maxZ - combined.minZ;
        int axis = x >= y && x >= z ? 0 : y >= z ? 1 : 2;
        Arrays.sort(ids, from, to, Comparator.comparingDouble(i -> bounds[i].center(axis)));
        return (from + to) >>> 1;
    }

    private Split chooseSplit(Integer[] ids, int from, int to) {
        Split best = null;
        for (int axis = 0; axis < 3; axis++) {
            float minimum = Float.POSITIVE_INFINITY;
            float maximum = Float.NEGATIVE_INFINITY;
            for (int i = from; i < to; i++) {
                float center = bounds[ids[i]].center(axis);
                minimum = Math.min(minimum, center);
                maximum = Math.max(maximum, center);
            }
            double span = (double) maximum - minimum;
            if (!(span > 0) || !Double.isFinite(span)) {
                continue;
            }
            fillBins(ids, from, to, axis, minimum, span);
            Split candidate = evaluateBins(axis, minimum, span);
            if (candidate != null && (best == null || candidate.cost < best.cost)) {
                best = candidate;
            }
        }
        return best;
    }

    private void fillBins(Integer[] ids, int from, int to, int axis, float minimum, double span) {
        Arrays.fill(counts, 0);
        for (var bin : bins) {
            bin.clear();
        }
        for (int i = from; i < to; i++) {
            var bound = bounds[ids[i]];
            int bin = bin(bound.center(axis), minimum, span);
            counts[bin]++;
            bins[bin].include(bound);
        }
    }

    private Split evaluateBins(int axis, float minimum, double span) {
        cumulative.clear();
        int leftCount = 0;
        for (int i = 0; i < BIN_COUNT; i++) {
            cumulative.include(bins[i]);
            leftCount += counts[i];
            prefixCounts[i] = leftCount;
            prefixAreas[i] = cumulative.surfaceArea();
        }
        cumulative.clear();
        int rightCount = 0;
        Split best = null;
        for (int i = BIN_COUNT - 1; i > 0; i--) {
            cumulative.include(bins[i]);
            rightCount += counts[i];
            double cost =
                    prefixAreas[i - 1] * prefixCounts[i - 1]
                            + cumulative.surfaceArea() * rightCount;
            if (prefixCounts[i - 1] > 0
                    && rightCount > 0
                    && Double.isFinite(cost)
                    && (best == null || cost < best.cost)) {
                best = new Split(axis, i - 1, minimum, span, cost);
            }
        }
        return best;
    }

    private int partitionBins(Integer[] ids, int from, int to, Split split) {
        int next = from;
        for (int i = from; i < to; i++) {
            if (bin(bounds[ids[i]].center(split.axis), split.minimum, split.span)
                    <= split.boundary) {
                scratch[next++] = ids[i];
            }
        }
        int middle = next;
        for (int i = from; i < to; i++) {
            if (bin(bounds[ids[i]].center(split.axis), split.minimum, split.span)
                    > split.boundary) {
                scratch[next++] = ids[i];
            }
        }
        System.arraycopy(scratch, from, ids, from, to - from);
        return middle;
    }

    private static int bin(float center, float minimum, double span) {
        int index = (int) (((double) center - minimum) / span * BIN_COUNT);
        return Math.max(0, Math.min(BIN_COUNT - 1, index));
    }
}
