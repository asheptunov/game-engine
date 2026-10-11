package engine;

/**
 * Worker-local cursor over immutable object or primitive bounds; reset for each nonrecursive query.
 */
final class ObjectTraversal {
    private final boolean nearFirst;
    private PrimitiveBvh hierarchy;
    private int node;

    ObjectTraversal() {
        this(!Boolean.getBoolean("engine.bvhEscapeReference"));
    }

    ObjectTraversal(boolean nearFirst) {
        this.nearFirst = nearFirst;
    }

    // Median splitting an int-sized range has depth at most 31. One deferred sibling
    // per level fits here, for both object and primitive trees, without per-ray allocation.
    private final int[] pending = new int[32];
    private final float[] entries = new float[32];
    private int size;
    long boundsTests;
    long nodeVisits;
    private int entry;
    private int end;
    private float ox;
    private float oy;
    private float oz;
    private float dx;
    private float dy;
    private float dz;

    void reset(
            PrimitiveBvh hierarchy,
            int count,
            float ox,
            float oy,
            float oz,
            float dx,
            float dy,
            float dz) {
        this.hierarchy = hierarchy;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        node = 0;
        size = 1;
        pending[0] = 0;
        entries[0] = 0;
        entry = 0;
        end = hierarchy == null ? count : 0;
    }

    /** Return the original scene index, or -1; a shrinking distance prunes later nodes. */
    int next(float distance) {
        if (hierarchy == null) {
            return entry < end ? entry++ : -1;
        }
        if (!nearFirst) {
            return nextEscape(distance);
        }
        return nextNear(distance);
    }

    private int nextNear(float distance) {
        while (entry >= end) {
            if (size == 0) {
                return -1;
            }
            int node = pending[--size];
            nodeVisits++;
            if (entries[size] > distance) {
                continue;
            }
            var current = hierarchy.nodes[node];
            if (node == 0 && entryDistance(current, distance) == Float.POSITIVE_INFINITY) {
                continue;
            }
            if (current.leaf()) {
                entry = current.from;
                end = current.to;
            } else {
                queueChildren(node, current, distance);
            }
        }
        return hierarchy.order[entry++];
    }

    private void queueChildren(int node, PrimitiveBvh.Node current, float distance) {
        int left = node + 1;
        int right = current.right;
        float leftEntry = entryDistance(hierarchy.nodes[left], distance);
        float rightEntry = entryDistance(hierarchy.nodes[right], distance);
        // Push farther first; equal entries retain the original left-first order.
        if (leftEntry <= rightEntry) {
            push(right, rightEntry);
            push(left, leftEntry);
        } else {
            push(left, leftEntry);
            push(right, rightEntry);
        }
    }

    private int nextEscape(float distance) {
        while (entry >= end) {
            if (node >= hierarchy.nodes.length) {
                return -1;
            }
            var current = hierarchy.nodes[node];
            nodeVisits++;
            boundsTests++;
            if (!current.bounds.overlaps(ox, oy, oz, dx, dy, dz, distance)) {
                node = current.escape;
            } else {
                node++;
                if (current.leaf()) {
                    entry = current.from;
                    end = current.to;
                }
            }
        }
        return hierarchy.order[entry++];
    }

    private float entryDistance(PrimitiveBvh.Node current, float distance) {
        boundsTests++;
        return current.bounds.entryDistance(ox, oy, oz, dx, dy, dz, distance);
    }

    private void push(int node, float distance) {
        if (distance == Float.POSITIVE_INFINITY) {
            return;
        }
        pending[size] = node;
        entries[size++] = distance;
    }
}
