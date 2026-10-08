package editor.overlay;

import engine.GeometryId;
import engine.Material;
import engine.NodeId;
import engine.PolygonMesh;
import engine.SceneDocument;
import engine.Transform;
import harness.Test;
import math.Vec3;

import java.util.List;
import java.util.UUID;

import static harness.Assertions.*;

public class OverlayGeometryBoundsTest {
    @Test void wireframeTruncationDoesNotConsumeVertexBudgetFromLaterNodes() {
        var scene = scene();
        var overlays = new OverlayGeometry(2, 5);

        var prepared = overlays.prepare(scene.snapshot(), null, OverlayGeometry.ElementMode.VERTEX, null);

        assertEquals(5, prepared.elementVertices().size());
        assertTrue(prepared.elementVertices().stream().anyMatch(vertex -> vertex.nodeId().equals(scene.secondNode())));
        assertTrue(prepared.elementCandidatesTruncated());
    }

    @Test void edgeCandidateCapKeepsLowestCanonicalStableIds() {
        var scene = scene();
        var overlays = new OverlayGeometry(2, 2);

        var prepared = overlays.prepare(scene.snapshot(), null, OverlayGeometry.ElementMode.EDGE, null);

        assertEquals(2, prepared.elementEdges().size());
        assertEdge(prepared.elementEdges().get(0), 10, 20);
        assertEdge(prepared.elementEdges().get(1), 10, 60);
        assertTrue(prepared.elementCandidatesTruncated());
    }

    private static Scene scene() {
        var document = new SceneDocument();
        var firstNode = new NodeId(new UUID(0, 1));
        var secondNode = new NodeId(new UUID(0, 2));
        var geometryId = new GeometryId(new UUID(0, 3));
        document.transact(edit -> {
            var materialId = edit.createMaterial("mat", Material.srgb("mat", 0xffffff));
            var mesh = PolygonMesh.surface(
                    List.of(
                            new PolygonMesh.Vertex(50, new Vec3(-1, -1, 0)),
                            new PolygonMesh.Vertex(60, new Vec3(1, -1, 0)),
                            new PolygonMesh.Vertex(10, new Vec3(1, 1, 0)),
                            new PolygonMesh.Vertex(20, new Vec3(-1, 1, 0))),
                    List.of(new PolygonMesh.Face(70, List.of(50L, 60L, 10L, 20L))),
                    61, 71);
            edit.createGeometry(geometryId, "quad", mesh);
            edit.createNode(firstNode, "first", null, Transform.IDENTITY);
            edit.assignGeometry(firstNode, geometryId, materialId);
            edit.createNode(secondNode, "second", null,
                    new Transform(new Vec3(3, 0, 0), Vec3.ZERO, new Vec3(1, 1, 1)));
            edit.assignGeometry(secondNode, geometryId, materialId);
        });
        return new Scene(document.snapshot(), firstNode, secondNode);
    }

    private static void assertEdge(OverlayGeometry.WorldEdge edge, long first, long second) {
        assertEquals(first, edge.firstVertexId());
        assertEquals(second, edge.secondVertexId());
    }

    private record Scene(engine.SceneSnapshot snapshot, NodeId firstNode, NodeId secondNode) {}

    public static void main(String[] args) {
        harness.SuiteRunner.runThis();
    }
}
