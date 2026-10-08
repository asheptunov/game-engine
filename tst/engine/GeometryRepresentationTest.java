package engine;

import static harness.Assertions.*;

import engine.lights.PointLight;
import engine.objects.Rect;
import engine.objects.Sphere;
import engine.objects.Tri;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Canonical/prepared boundary and raw compatibility locked against the pre-E7 engine. */
public class GeometryRepresentationTest {
    private static ViewportState state() {
        var value =
                new ViewportState(
                        new Rect(new Vec3(-.5f, -.5f, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)),
                        32,
                        32);
        value.addLight(new PointLight(new Vec3(-2, 3, 0), new Vec3(1, 1, 1), 80));
        return value;
    }

    private static String render(GeometryData geometry, Transform transform) throws Exception {
        var value = state();
        value.instances()
                .add(
                        new SceneInstance(
                                "subject", geometry, transform, Material.srgb("white", 0xffffff)));
        return hash(new DirectRgbTracer(value).trace());
    }

    private static String hash(float[][][] pixels) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (var channel : pixels)
            for (var row : channel)
                for (float value : row) {
                    int bits = Float.floatToIntBits(value);
                    digest.update((byte) (bits >>> 24));
                    digest.update((byte) (bits >>> 16));
                    digest.update((byte) (bits >>> 8));
                    digest.update((byte) bits);
                }
        return HexFormat.of().formatHex(digest.digest());
    }

    @Test
    void preparedGeometryPreservesPrimitiveOrderFaceMappingAndExactKernels() {
        var analytic = new AnalyticSphere(Vec3.ZERO, 1);
        var sphere = PreparedGeometry.prepare(analytic);
        assertEquals(1, sphere.primitives().size());
        assertInstanceOf(Sphere.class, sphere.primitives().getFirst());
        assertFalse(sphere.indexedTriangles());
        var plane =
                PolygonMesh.parallelogram(
                        new Vec3(2, 3, 4), new Vec3(2, 0, 0), new Vec3(.5f, 1, 0));
        var preparedPlane = PreparedGeometry.prepare(plane);
        assertEquals(1, preparedPlane.primitives().size());
        assertInstanceOf(Rect.class, preparedPlane.primitives().getFirst());
        assertEquals(0L, preparedPlane.sourceFaceId(0));
        var offsetPlane =
                PolygonMesh.parallelogram(
                        new Vec3(-524865.9375f, 0, 0),
                        new Vec3(415.4455261f, 100, 0),
                        new Vec3(454.0234375f, -60, 0));
        assertTrue(offsetPlane.capabilities().parallelogramEmitter());
        assertEquals(1, PreparedGeometry.prepare(offsetPlane).primitives().size());
        assertInstanceOf(Rect.class, PreparedGeometry.prepare(offsetPlane).primitives().getFirst());
        var box = PreparedGeometry.prepare(BoxGeometry.UNIT);
        assertEquals(12, box.primitives().size());
        assertFalse(box.indexedTriangles());
        assertEquals(
                List.of(0L, 0L, 1L, 1L, 2L, 2L, 3L, 3L, 4L, 4L, 5L, 5L),
                java.util.stream.IntStream.range(0, 12)
                        .mapToLong(box::sourceFaceId)
                        .boxed()
                        .toList());
        var first = (Tri) box.primitives().getFirst();
        assertEquals(new Vec3(-1, -1, -1), first.a());
        assertEquals(new Vec3(-1, 1, -1), first.b());
        assertEquals(new Vec3(1, 1, -1), first.c());
        for (int detail : new int[] {4, 12, 64}) {
            var mesh = PolygonMesh.approximateSphere(analytic, detail);
            var prepared = PreparedGeometry.prepare(mesh);
            assertEquals(4 * detail * (detail - 1), prepared.primitives().size());
            assertTrue(prepared.indexedTriangles());
            for (int i = 0; i < prepared.primitives().size(); i++)
                assertEquals((long) i, prepared.sourceFaceId(i));
        }
    }

    @Test
    void rawSeededRgbMatchesPreCanonicalGeometryRoutes() throws Exception {
        // Hashes measured with base 404090a before the canonical/prepared split.
        assertEquals(
                "06cc7c9a02fb5b506c3fc1072e3b28db47fdaf0dd1623080b0cc26cfe76e72f7",
                render(
                        new AnalyticSphere(Vec3.ZERO, 1),
                        new Transform(new Vec3(0, 0, 5), Vec3.ZERO, new Vec3(1, 1, 1))));
        assertEquals(
                "25882840f7f10b6656d5f12228e15f52888c2d4c8dee171903f40f6e39f9a4e2",
                render(
                        PolygonMesh.parallelogram(
                                new Vec3(-2, -2, 5), new Vec3(0, 4, 0), new Vec3(4, 0, 0)),
                        Transform.IDENTITY));
        assertEquals(
                "824a9c3a01660dca149b053e07c7e6eae397be882860117328a60e5bcc57b536",
                render(
                        BoxGeometry.UNIT,
                        new Transform(
                                new Vec3(0, 0, 5), new Vec3(10, 25, 5), new Vec3(1, 1.5f, .7f))));
        String[] hashes = {
            "e73dce9da122100c6dc0153dae0724f4c0040a05d760ac244ce0a6e3b39335a6",
            "8a9d500445f15ae8b3fc3788e564ceda8a29cbc0ed2e85097c9df37b4f6518fc",
            "b8a9d26c181e46bc9621d098841f11866c9f2c40b7873801ad8ae013617d611c"
        };
        int i = 0;
        for (int detail : new int[] {4, 12, 64})
            assertEquals(
                    hashes[i++],
                    render(
                            PolygonMesh.approximateSphere(new AnalyticSphere(Vec3.ZERO, 1), detail),
                            new Transform(new Vec3(0, 0, 5), Vec3.ZERO, new Vec3(1, 1, 1))));
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
