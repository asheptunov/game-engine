package engine;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertFalse;
import static harness.Assertions.assertTrue;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

/** Compare the position-only path with independently reconstructed, fully validated meshes. */
public class IncrementalMeshTest {
    @Test
    void vertexEdgeAndFaceMovesMatchFullValidation() {
        var random = new Random(4321);
        var meshes =
                List.of(
                        PolygonMesh.unitBox(),
                        PolygonMesh.parallelogram(
                                new Vec3(-1, -1, 0), new Vec3(2, 0, 0), new Vec3(0, 2, 0)),
                        PolygonMesh.approximateSphere(new AnalyticSphere(Vec3.ZERO, 1), 8));
        int accepted = 0;
        int rejected = 0;
        for (var mesh : meshes) {
            for (int iteration = 0; iteration < 100; iteration++) {
                float amplitude = iteration % 2 == 0 ? .01f : 3f;
                var delta =
                        new Vec3(
                                (random.nextFloat() - .5f) * amplitude,
                                (random.nextFloat() - .5f) * amplitude,
                                (random.nextFloat() - .5f) * amplitude);
                var vertex =
                        mesh.editableVertices().get(random.nextInt(mesh.editableVertices().size()));
                var edge = mesh.edges().get(random.nextInt(mesh.edges().size()));
                var face = mesh.faces().get(random.nextInt(mesh.faces().size()));
                boolean[] results = {
                    compare(
                            mesh,
                            Set.of(vertex.id()),
                            delta,
                            () -> mesh.translateVertex(vertex.id(), delta)),
                    compare(
                            mesh,
                            Set.of(edge.firstVertexId(), edge.secondVertexId()),
                            delta,
                            () ->
                                    mesh.translateEdge(
                                            edge.secondVertexId(), edge.firstVertexId(), delta)),
                    compare(
                            mesh,
                            new HashSet<>(face.vertexIds()),
                            delta,
                            () -> mesh.translateFace(face.id(), delta))
                };
                for (boolean result : results) {
                    if (result) {
                        accepted++;
                    } else {
                        rejected++;
                    }
                }
            }
        }
        assertTrue(accepted > 100);
        assertTrue(rejected > 0);
    }

    @Test
    void chainedMovesAndExtrusionKeepStableTopologyAndIndependentPreparation() {
        var original = PolygonMesh.unitBox();
        var oldPrepared = original.preparedGeometry();
        var oldPrimitives = oldPrepared.primitives();
        var current = original;
        for (int iteration = 0; iteration < 20; iteration++) {
            var delta = new Vec3(.001f, .002f, .003f);
            var source = current;
            compare(source, Set.of(0L), delta, () -> source.translateVertex(0, delta));
            current = source.translateVertex(0, delta);
        }
        assertTrue(original.faces() == current.faces());
        assertTrue(original.edges() == current.edges());
        assertTrue(original.requireVertex(1) == current.requireVertex(1));
        assertFalse(original.requireVertex(0) == current.requireVertex(0));
        assertTrue(original.preparedGeometry() == oldPrepared);
        assertEquals(oldPrimitives, original.preparedGeometry().primitives());
        assertFalse(current.preparedGeometry() == oldPrepared);
        var rebuilt = reconstruct(current, Set.of(), Vec3.ZERO);
        assertTrue(GeometryValues.equal(rebuilt.extrude(1, .1f), current.extrude(1, .1f)));
        assertFalse(current.capabilities().canonicalBoxVolume());
        assertTrue(original.capabilities().canonicalBoxVolume());
    }

    @Test
    void nonplanarFacesAndVolumeInversionUseTheSameAcceptanceRules() {
        var surface = PolygonMesh.parallelogram(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(0, 1, 0));
        assertTrue(
                compare(
                        surface,
                        Set.of(0L),
                        new Vec3(0, 0, .1f),
                        () -> surface.translateVertex(0, new Vec3(0, 0, .1f))));
        var tetra =
                PolygonMesh.triangleClosedSolid(
                        List.of(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)),
                        new int[] {0, 2, 1, 0, 1, 3, 0, 3, 2, 1, 2, 3});
        var delta = new Vec3(0, 0, -2);
        assertFalse(compare(tetra, Set.of(3L), delta, () -> tetra.translateVertex(3, delta)));
        assertTrue(surface == surface.translateVertex(0, Vec3.ZERO));
    }

    private static boolean compare(
            PolygonMesh mesh, Set<Long> moved, Vec3 delta, Supplier<PolygonMesh> incremental) {
        PolygonMesh expected;
        try {
            expected = reconstruct(mesh, moved, delta);
        } catch (IllegalArgumentException rejected) {
            try {
                incremental.get();
            } catch (IllegalArgumentException alsoRejected) {
                return false;
            }
            throw new AssertionError(
                    "Incremental path accepted a move rejected by full validation", rejected);
        }
        var actual = incremental.get();
        assertTrue(GeometryValues.equal(expected, actual));
        assertEquals(expected.capabilities(), actual.capabilities());
        assertEquals(
                expected.preparedGeometry().primitives(), actual.preparedGeometry().primitives());
        for (var face : expected.faces()) {
            assertEquals(expected.faceNormal(face.id()), actual.faceNormal(face.id()));
            assertEquals(expected.adjacentFaceIds(face.id()), actual.adjacentFaceIds(face.id()));
        }
        return true;
    }

    private static PolygonMesh reconstruct(PolygonMesh source, Set<Long> moved, Vec3 delta) {
        var vertices =
                source.editableVertices().stream()
                        .map(
                                vertex ->
                                        new PolygonMesh.Vertex(
                                                vertex.id(),
                                                moved.contains(vertex.id())
                                                        ? vertex.position().add(delta)
                                                        : vertex.position()))
                        .toList();
        return source.closedBoundary()
                ? PolygonMesh.closedSolid(
                        vertices, source.faces(), source.nextVertexId(), source.nextFaceId())
                : PolygonMesh.surface(
                        vertices, source.faces(), source.nextVertexId(), source.nextFaceId());
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
