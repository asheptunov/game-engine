package editor;

import engine.GeometryAsset;
import engine.GeometryComponent;
import engine.GeometryId;
import engine.MaterialAsset;
import engine.MaterialId;
import engine.NodeId;
import engine.SceneFiles;
import engine.SceneSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Applies the editor's one-geometry-and-material-identity-per-object ownership policy. */
final class IndependentSceneAssets {
    private static final String GEOMETRY_NAMESPACE = "editor-independent-geometry-v1";
    private static final String MATERIAL_NAMESPACE = "editor-independent-material-v1";
    private static final int MAX_DERIVATION_ATTEMPTS =
            SceneFiles.MAX_GEOMETRY_ASSETS + SceneFiles.MAX_MATERIAL_ASSETS + 1;

    @FunctionalInterface
    interface IdDeriver {
        UUID derive(String namespace, UUID sourceAssetId, UUID nodeId, int salt);
    }

    private static final IdDeriver DEFAULT_DERIVER =
            (namespace, sourceAssetId, nodeId, salt) -> {
                String input = namespace + "\n" + sourceAssetId + "\n" + nodeId + "\n" + salt;
                return UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8));
            };

    private IndependentSceneAssets() {}

    static SceneSnapshot normalize(SceneSnapshot source) throws IOException {
        return normalize(source, DEFAULT_DERIVER);
    }

    static SceneSnapshot normalize(SceneSnapshot source, IdDeriver deriver) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(deriver, "deriver");

        var geometryNodes =
                source.nodes().stream()
                        .filter(node -> node.geometry() != null)
                        .sorted(Comparator.comparing(node -> node.id().value()))
                        .toList();
        int geometryCopies =
                duplicateReferenceCount(
                        geometryNodes.stream().map(node -> node.geometry().geometryId()).toList());
        int materialCopies =
                duplicateReferenceCount(
                        geometryNodes.stream().map(node -> node.geometry().materialId()).toList());
        preflightAssetCounts(source, geometryCopies, materialCopies);

        var geometries = new ArrayList<>(source.geometryAssets());
        var materials = new ArrayList<>(source.materialAssets());
        var reservedGeometryIds = new HashSet<GeometryId>();
        for (var asset : source.geometryAssets()) {
            reservedGeometryIds.add(asset.id());
        }
        var reservedMaterialIds = new HashSet<MaterialId>();
        for (var asset : source.materialAssets()) {
            reservedMaterialIds.add(asset.id());
        }
        var firstGeometryOwner = new HashSet<GeometryId>();
        var firstMaterialOwner = new HashSet<MaterialId>();
        var componentsByNode = new HashMap<NodeId, GeometryComponent>();

        for (var node : geometryNodes) {
            var sourceComponent = node.geometry();
            GeometryId geometryId = sourceComponent.geometryId();
            if (!firstGeometryOwner.add(geometryId)) {
                var sourceAsset = source.requireGeometry(geometryId);
                geometryId =
                        nextGeometryId(deriver, sourceAsset.id(), node.id(), reservedGeometryIds);
                geometries.add(
                        new GeometryAsset(
                                geometryId, sourceAsset.label(), 0, sourceAsset.geometry()));
            }

            MaterialId materialId = sourceComponent.materialId();
            if (!firstMaterialOwner.add(materialId)) {
                var sourceAsset = source.requireMaterial(materialId);
                materialId =
                        nextMaterialId(deriver, sourceAsset.id(), node.id(), reservedMaterialIds);
                materials.add(
                        new MaterialAsset(
                                materialId, sourceAsset.label(), 0, sourceAsset.material()));
            }
            componentsByNode.put(node.id(), new GeometryComponent(geometryId, materialId));
        }

        SceneSnapshot normalized = source;
        if (geometryCopies > 0 || materialCopies > 0) {
            var nodes =
                    source.nodes().stream()
                            .map(
                                    node ->
                                            node.geometry() == null
                                                    ? node
                                                    : node.withGeometry(
                                                            componentsByNode.get(node.id())))
                            .toList();
            normalized = SceneSnapshot.content(nodes, geometries, materials);
        }
        SceneFiles.validateForSave(normalized);
        return normalized;
    }

    private static int duplicateReferenceCount(List<?> assetIds) {
        var distinct = new HashSet<>(assetIds);
        return assetIds.size() - distinct.size();
    }

    private static void preflightAssetCounts(
            SceneSnapshot source, int geometryCopies, int materialCopies) throws IOException {
        long geometryCount = (long) source.geometryAssets().size() + geometryCopies;
        if (geometryCount > SceneFiles.MAX_GEOMETRY_ASSETS) {
            throw new IOException(
                    "Independent objects exceed geometry asset limit "
                            + SceneFiles.MAX_GEOMETRY_ASSETS);
        }
        long materialCount = (long) source.materialAssets().size() + materialCopies;
        if (materialCount > SceneFiles.MAX_MATERIAL_ASSETS) {
            throw new IOException(
                    "Independent objects exceed material asset limit "
                            + SceneFiles.MAX_MATERIAL_ASSETS);
        }
    }

    private static GeometryId nextGeometryId(
            IdDeriver deriver, GeometryId sourceId, NodeId nodeId, Set<GeometryId> reservedIds)
            throws IOException {
        for (int salt = 0; salt < MAX_DERIVATION_ATTEMPTS; salt++) {
            var candidate =
                    new GeometryId(
                            deriver.derive(
                                    GEOMETRY_NAMESPACE, sourceId.value(), nodeId.value(), salt));
            if (reservedIds.add(candidate)) {
                return candidate;
            }
        }
        throw new IOException("Could not derive a collision-free geometry identity");
    }

    private static MaterialId nextMaterialId(
            IdDeriver deriver, MaterialId sourceId, NodeId nodeId, Set<MaterialId> reservedIds)
            throws IOException {
        for (int salt = 0; salt < MAX_DERIVATION_ATTEMPTS; salt++) {
            var candidate =
                    new MaterialId(
                            deriver.derive(
                                    MATERIAL_NAMESPACE, sourceId.value(), nodeId.value(), salt));
            if (reservedIds.add(candidate)) {
                return candidate;
            }
        }
        throw new IOException("Could not derive a collision-free material identity");
    }
}
