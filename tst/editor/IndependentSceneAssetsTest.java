package editor;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertSame;
import static harness.Assertions.assertTrue;

import engine.AnalyticSphere;
import engine.GeometryAsset;
import engine.GeometryComponent;
import engine.GeometryId;
import engine.Material;
import engine.MaterialAsset;
import engine.MaterialId;
import engine.NodeId;
import engine.PolygonMesh;
import engine.SceneFiles;
import engine.SceneNode;
import engine.SceneSnapshot;
import engine.Transform;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class IndependentSceneAssetsTest {
    private static final GeometryId SHARED_GEOMETRY = geometryId(10);
    private static final MaterialId SHARED_MATERIAL = materialId(20);
    private static final Material GRAY = Material.srgb("gray", 0x808080);

    @Test
    void normalizationIsDeterministicIdempotentAndPreservesValues() throws Exception {
        var source =
                sharedScene(
                        List.of(nodeId(3), nodeId(1), nodeId(2)), new AnalyticSphere(Vec3.ZERO, 1));

        var first = IndependentSceneAssets.normalize(source);
        var second = IndependentSceneAssets.normalize(source);
        assertTrue(first.sameContent(second));
        assertSame(first, IndependentSceneAssets.normalize(first));
        assertEquals(3L, referencedGeometryIds(first).stream().distinct().count());
        assertEquals(3L, referencedMaterialIds(first).stream().distinct().count());
        assertEquals(SHARED_GEOMETRY, first.requireNode(nodeId(1)).geometry().geometryId());
        assertEquals(SHARED_MATERIAL, first.requireNode(nodeId(1)).geometry().materialId());
        for (var node : first.nodes()) {
            assertSame(
                    source.requireGeometry(SHARED_GEOMETRY).geometry(),
                    first.requireGeometry(node.geometry().geometryId()).geometry());
            assertMaterialValueEquals(
                    source.requireMaterial(SHARED_MATERIAL).material(),
                    first.requireMaterial(node.geometry().materialId()).material());
        }
    }

    @Test
    void deterministicDerivationSkipsReservedAndGeneratedCollisions() throws Exception {
        var source =
                sharedScene(
                        List.of(nodeId(1), nodeId(2), nodeId(3)), new AnalyticSphere(Vec3.ZERO, 1));
        var collisions = new java.util.concurrent.atomic.AtomicInteger();
        IndependentSceneAssets.IdDeriver deriver =
                (namespace, sourceId, nodeId, salt) -> {
                    if (salt == 0) {
                        collisions.incrementAndGet();
                        return sourceId;
                    }
                    if (salt == 1) {
                        collisions.incrementAndGet();
                        return UUID.nameUUIDFromBytes(
                                (namespace + "forced-generated-collision")
                                        .getBytes(StandardCharsets.UTF_8));
                    }
                    return UUID.nameUUIDFromBytes(
                            (namespace + nodeId + salt).getBytes(StandardCharsets.UTF_8));
                };

        var normalized = IndependentSceneAssets.normalize(source, deriver);
        assertTrue(collisions.get() >= 6);
        assertEquals(3L, referencedGeometryIds(normalized).stream().distinct().count());
        assertEquals(3L, referencedMaterialIds(normalized).stream().distinct().count());
        assertEquals(SHARED_GEOMETRY, normalized.requireNode(nodeId(1)).geometry().geometryId());
        assertEquals(SHARED_MATERIAL, normalized.requireNode(nodeId(1)).geometry().materialId());
    }

    @Test
    void expandedTopologyIsRejectedBeforeEditorPublication() throws Exception {
        int nodeCount = SceneFiles.MAX_GEOMETRY_ASSETS;
        var nodes = new ArrayList<SceneNode>(nodeCount);
        for (int index = 0; index < nodeCount; index++) {
            nodes.add(
                    new SceneNode(
                            nodeId(index + 1),
                            "object",
                            null,
                            Transform.IDENTITY,
                            new GeometryComponent(SHARED_GEOMETRY, SHARED_MATERIAL),
                            null,
                            null));
        }
        var source =
                SceneSnapshot.content(
                        nodes,
                        List.of(
                                new GeometryAsset(
                                        SHARED_GEOMETRY,
                                        "mesh",
                                        0,
                                        PolygonMesh.approximateSphere(
                                                new AnalyticSphere(Vec3.ZERO, 1), 5))),
                        List.of(new MaterialAsset(SHARED_MATERIAL, "gray", 0, GRAY)));

        boolean rejected = false;
        try {
            IndependentSceneAssets.normalize(source);
        } catch (IOException expected) {
            rejected = true;
            assertTrue(expected.getMessage().contains("limit"));
        }
        assertTrue(rejected);
    }

    @Test
    void exactSerializedByteLimitIsCheckedWithoutAnUnboundedBuffer() throws Exception {
        var nodes = new ArrayList<SceneNode>(SceneFiles.MAX_NODES);
        String label = "x".repeat(256);
        for (int index = 0; index < SceneFiles.MAX_NODES; index++) {
            nodes.add(
                    new SceneNode(
                            nodeId(index + 1), label, null, Transform.IDENTITY, null, null, null));
        }
        var source = SceneSnapshot.content(nodes, List.of(), List.of());

        boolean rejected = false;
        try {
            IndependentSceneAssets.normalize(source);
        } catch (IOException expected) {
            rejected = true;
            assertTrue(expected.getMessage().contains("Serialized scene exceeds"));
        }
        assertTrue(rejected);
    }

    private static SceneSnapshot sharedScene(List<NodeId> nodeIds, engine.GeometryData geometry) {
        var nodes =
                nodeIds.stream()
                        .map(
                                id ->
                                        new SceneNode(
                                                id,
                                                "object",
                                                null,
                                                Transform.IDENTITY,
                                                new GeometryComponent(
                                                        SHARED_GEOMETRY, SHARED_MATERIAL),
                                                null,
                                                null))
                        .toList();
        return SceneSnapshot.content(
                nodes,
                List.of(new GeometryAsset(SHARED_GEOMETRY, "shape", 0, geometry)),
                List.of(new MaterialAsset(SHARED_MATERIAL, "gray", 0, GRAY)));
    }

    private static List<GeometryId> referencedGeometryIds(SceneSnapshot snapshot) {
        return snapshot.nodes().stream().map(node -> node.geometry().geometryId()).toList();
    }

    private static List<MaterialId> referencedMaterialIds(SceneSnapshot snapshot) {
        return snapshot.nodes().stream().map(node -> node.geometry().materialId()).toList();
    }

    private static NodeId nodeId(long value) {
        return new NodeId(new UUID(0, value));
    }

    private static GeometryId geometryId(long value) {
        return new GeometryId(new UUID(0, value));
    }

    private static MaterialId materialId(long value) {
        return new MaterialId(new UUID(0, value));
    }

    private static void assertMaterialValueEquals(Material expected, Material actual) {
        assertEquals(expected.color(), actual.color());
        assertEquals(expected.kind(), actual.kind());
        assertEquals(expected.ior(), actual.ior());
        assertEquals(expected.absorption(), actual.absorption());
        assertEquals(expected.roughness(), actual.roughness());
        assertEquals(expected.emission(), actual.emission());
        assertEquals(expected.scattering(), actual.scattering());
        assertEquals(expected.anisotropy(), actual.anisotropy());
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
