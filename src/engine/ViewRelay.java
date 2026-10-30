package engine;

/**
 * Ideal camera-only telescope. A front-facing primary ray crosses a planar entrance, advances
 * exitDistance world units along its inward normal, and divides its transverse slope by
 * magnification. It preserves radiance without Fresnel sampling or a path-depth charge. Other rays
 * see the material's ordinary diffuse surface. This is a gameplay view transform, not glass.
 */
public record ViewRelay(float magnification, float exitDistance) {
    public ViewRelay {
        if (!Float.isFinite(magnification) || magnification < 1 || magnification > 16) {
            throw new IllegalArgumentException("View magnification must be 1..16");
        }
        if (!Float.isFinite(exitDistance) || exitDistance < 0 || exitDistance > 10) {
            throw new IllegalArgumentException("View exit distance must be 0..10 world units");
        }
    }

    static void validateEntrance(GeometryData geometry) {
        if (!(geometry instanceof PolygonMesh mesh) || mesh.capabilities().closedBoundary()) {
            throw new IllegalArgumentException(
                    "View relays require an open planar polygon entrance");
        }
        float z = mesh.editableVertices().getFirst().position().z();
        for (var vertex : mesh.editableVertices()) {
            if (vertex.position().z() != z) {
                throw new IllegalArgumentException(
                        "View relay entrance must lie in a local XY plane");
            }
        }
        for (var face : mesh.faces()) {
            var ids = face.vertexIds();
            var a = mesh.requireVertex(ids.get(0)).position();
            var b = mesh.requireVertex(ids.get(1)).position();
            var c = mesh.requireVertex(ids.get(2)).position();
            if (b.sub(a).cross(c.sub(a)).z() >= 0) {
                throw new IllegalArgumentException("View relay entrance must face local -Z");
            }
        }
    }
}
