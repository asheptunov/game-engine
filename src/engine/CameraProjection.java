package engine;

/**
 * Optional temporal geometry for pinhole and orthographic cameras; worker-local projection scratch.
 */
final class CameraProjection {
    final int w, h;
    final boolean orthographic;
    final double ex,
            ey,
            ez,
            ox,
            oy,
            oz,
            ax,
            ay,
            az,
            bx,
            by,
            bz,
            nx,
            ny,
            nz,
            plane,
            aa,
            ab,
            bb,
            det,
            focal,
            pixel;
    double px, py, pz;

    CameraProjection(ViewportState.RenderKey key) {
        if (key.camera().mode() == Camera.Mode.LENS)
            throw new IllegalArgumentException("Finite aperture has no temporal projection");
        if (key.camera().aperture() > 0)
            throw new IllegalArgumentException("Finite aperture has no temporal projection");
        this(key.camera(), key.width(), key.height());
    }

    /** Reference geometry remains usable for picking even with finite aperture. */
    CameraProjection(Camera camera) {
        this(camera.identity(), 1, 1);
    }

    private CameraProjection(Camera.Identity camera, int width, int height) {
        w = width;
        h = height;
        var e = camera.eye();
        var s = camera.sensor();
        orthographic = camera.mode() == Camera.Mode.ORTHOGRAPHIC;
        ex = e.x();
        ey = e.y();
        ez = e.z();
        ox = s.origin().x();
        oy = s.origin().y();
        oz = s.origin().z();
        ax = s.edge1().x();
        ay = s.edge1().y();
        az = s.edge1().z();
        bx = s.edge2().x();
        by = s.edge2().y();
        bz = s.edge2().z();
        double cx = ay * bz - az * by,
                cy = az * bx - ax * bz,
                cz = ax * by - ay * bx,
                inv = 1 / Math.sqrt(cx * cx + cy * cy + cz * cz);
        nx = cx * inv;
        ny = cy * inv;
        nz = cz * inv;
        plane = nx * (ox - ex) + ny * (oy - ey) + nz * (oz - ez);
        focal = Math.abs(plane);
        aa = ax * ax + ay * ay + az * az;
        ab = ax * bx + ay * by + az * bz;
        bb = bx * bx + by * by + bz * bz;
        det = aa * bb - ab * ab;
        pixel = Math.max(Math.sqrt(aa) / w, Math.sqrt(bb) / h);
    }

    boolean near(double x, double y, double z, float targetU, float targetV) {
        double dx = x - ex, dy = y - ey, dz = z - ez, denominator = nx * dx + ny * dy + nz * dz;
        if (denominator <= 0 || det <= 0) return false;
        double t = orthographic ? 0 : plane / denominator;
        double qx = orthographic ? x - ox : ex + dx * t - ox,
                qy = orthographic ? y - oy : ey + dy * t - oy,
                qz = orthographic ? z - oz : ez + dz * t - oz;
        double qa = qx * ax + qy * ay + qz * az, qb = qx * bx + qy * by + qz * bz;
        return Math.abs((qa * bb - qb * ab) / det - targetU) <= .02
                && Math.abs((qb * aa - qa * ab) / det - targetV) <= .02;
    }

    void point(int x, int y, float depth) {
        double u = (x + .5) / w,
                v = (y + .5) / h,
                dx = ox + ax * u + bx * v - ex,
                dy = oy + ay * u + by * v - ey,
                dz = oz + az * u + bz * v - ez;
        if (orthographic) {
            px = ox + ax * u + bx * v + nx * depth;
            py = oy + ay * u + by * v + ny * depth;
            pz = oz + az * u + bz * v + nz * depth;
            return;
        }
        double scale = depth / Math.sqrt(dx * dx + dy * dy + dz * dz);
        px = ex + dx * scale;
        py = ey + dy * scale;
        pz = ez + dz * scale;
    }

    int project(double x, double y, double z) {
        double dx = x - ex, dy = y - ey, dz = z - ez, denominator = nx * dx + ny * dy + nz * dz;
        if ((orthographic ? denominator <= 0 : plane * denominator <= 0) || det <= 0) return -1;
        double t = orthographic ? 0 : plane / denominator;
        double qx = orthographic ? x - ox : ex + dx * t - ox,
                qy = orthographic ? y - oy : ey + dy * t - oy,
                qz = orthographic ? z - oz : ez + dz * t - oz;
        double qa = qx * ax + qy * ay + qz * az, qb = qx * bx + qy * by + qz * bz;
        double u = (qa * bb - qb * ab) / det, v = (qb * aa - qa * ab) / det;
        if (u < 0 || u >= 1 || v < 0 || v >= 1) return -1;
        return (int) (v * h) * w + (int) (u * w);
    }

    double footprint(float depth) {
        return orthographic ? pixel : depth * pixel / Math.max(1e-8, focal);
    }

    boolean cut(CameraProjection old) {
        double dx = ex - old.ex, dy = ey - old.ey, dz = ez - old.ez;
        double scale = orthographic ? Math.min(aa, bb) : focal * focal;
        double oldScale = old.orthographic ? Math.min(old.aa, old.bb) : old.focal * old.focal;
        return orthographic != old.orthographic
                || dx * dx + dy * dy + dz * dz > .0625 * Math.min(scale, oldScale)
                || nx * old.nx + ny * old.ny + nz * old.nz < .98
                || (!orthographic && Math.abs(focal - old.focal) > focal * .01)
                || Math.abs(aa - old.aa) > aa * .01
                || Math.abs(bb - old.bb) > bb * .01
                || (ax * old.ax + ay * old.ay + az * old.az) / Math.sqrt(aa * old.aa) < .98;
    }
}
