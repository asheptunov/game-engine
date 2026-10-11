package engine;

/** Worker-local cursor over immutable object bounds; reset for each nonrecursive query. */
final class ObjectTraversal {
    private PrimitiveBvh hierarchy;
    private int node;
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
        entry = 0;
        end = hierarchy == null ? count : 0;
    }

    /** Return the original scene index, or -1; a shrinking distance prunes later nodes. */
    int next(float distance) {
        if (hierarchy == null) {
            return entry < end ? entry++ : -1;
        }
        while (entry >= end) {
            if (node >= hierarchy.nodes.length) {
                return -1;
            }
            var current = hierarchy.nodes[node];
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
}
