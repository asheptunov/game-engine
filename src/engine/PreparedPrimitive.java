package engine;

import engine.objects.*;

import math.Vec3;

/** Cached world-space flat geometry; transformed spheres use the inverse affine ray. */
final class PreparedPrimitive {
    final String objectId, materialId;
    final int primitiveId;
    // Assigned once during scene preparation, before sharing with tracing workers.
    int surfaceId;
    final Material material;
    final TraceSurface flat;
    final Sphere sphere;
    final Sphere worldSphere;
    final Transform transform;
    final MeshSurface mesh;
    final RenderPrimitive geometry;

    PreparedPrimitive(SceneInstance object, PreparedGeometry prepared, int index) {
        objectId = object.name();
        materialId = object.material().name();
        primitiveId = index;
        material = object.material();
        transform = object.transform();
        geometry = prepared.primitives().get(index);
        mesh =
                prepared.indexedTriangles() && geometry instanceof Tri t
                        ? new MeshSurface(
                                transform.point(t.a()),
                                transform.point(t.b()),
                                transform.point(t.c()))
                        : null;
        sphere = geometry instanceof Sphere s ? s : null;
        worldSphere =
                sphere != null
                                && transform.scale.x() == transform.scale.y()
                                && transform.scale.y() == transform.scale.z()
                        ? new Sphere(
                                transform.point(sphere.center()),
                                sphere.radius() * transform.scale.x())
                        : null;
        flat =
                switch (geometry) {
                    case Tri t ->
                            new TraceSurface(
                                    new Tri(
                                            transform.point(t.a()),
                                            transform.point(t.b()),
                                            transform.point(t.c())));
                    case Rect r ->
                            new TraceSurface(
                                    new Rect(
                                            transform.point(r.origin()),
                                            transform.vector(r.edge1()),
                                            transform.vector(r.edge2())));
                    case Sphere s -> null;
                };
    }

    Vec3 normalAt(float x, float y, float z) {
        var hit = new DirectRgbTracer.Hit();
        normal(x, y, z, hit);
        return new Vec3(hit.nx, hit.ny, hit.nz);
    }

    float distance(float ox, float oy, float oz, float dx, float dy, float dz) {
        if (mesh != null) return mesh.distance(ox, oy, oz, dx, dy, dz);
        if (flat != null) return flat.distance(ox, oy, oz, dx, dy, dz);
        if (worldSphere != null) return worldSphere.distance(ox, oy, oz, dx, dy, dz);
        float x = ox - transform.position.x(),
                y = oy - transform.position.y(),
                z = oz - transform.position.z();
        return sphere.distance(
                (transform.a * x + transform.d * y + transform.g * z) / transform.scale.x(),
                (transform.b * x + transform.e * y + transform.h * z) / transform.scale.y(),
                (transform.c * x + transform.f * y + transform.i * z) / transform.scale.z(),
                (transform.a * dx + transform.d * dy + transform.g * dz) / transform.scale.x(),
                (transform.b * dx + transform.e * dy + transform.h * dz) / transform.scale.y(),
                (transform.c * dx + transform.f * dy + transform.i * dz) / transform.scale.z());
    }

    void normal(float x, float y, float z, DirectRgbTracer.Hit hit) {
        if (flat != null) {
            Vec3 n = flat.normal();
            hit.nx = n.x();
            hit.ny = n.y();
            hit.nz = n.z();
            return;
        }
        if (worldSphere != null) {
            hit.nx = (x - worldSphere.center().x()) / worldSphere.radius();
            hit.ny = (y - worldSphere.center().y()) / worldSphere.radius();
            hit.nz = (z - worldSphere.center().z()) / worldSphere.radius();
            float inv = 1 / (float) Math.sqrt(hit.nx * hit.nx + hit.ny * hit.ny + hit.nz * hit.nz);
            hit.nx *= inv;
            hit.ny *= inv;
            hit.nz *= inv;
            return;
        }
        x -= transform.position.x();
        y -= transform.position.y();
        z -= transform.position.z();
        float nx =
                ((transform.a * x + transform.d * y + transform.g * z) / transform.scale.x()
                                - sphere.center().x())
                        / transform.scale.x();
        float ny =
                ((transform.b * x + transform.e * y + transform.h * z) / transform.scale.y()
                                - sphere.center().y())
                        / transform.scale.y();
        float nz =
                ((transform.c * x + transform.f * y + transform.i * z) / transform.scale.z()
                                - sphere.center().z())
                        / transform.scale.z();
        float wx = transform.a * nx + transform.b * ny + transform.c * nz,
                wy = transform.d * nx + transform.e * ny + transform.f * nz,
                wz = transform.g * nx + transform.h * ny + transform.i * nz;
        float inv = 1 / (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
        hit.nx = wx * inv;
        hit.ny = wy * inv;
        hit.nz = wz * inv;
    }
}
