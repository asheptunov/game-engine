package engine;

import math.Vec3;
import java.util.Optional;

/** Immutable spatial-query result; compare sceneRevision before acting on a later snapshot. */
public record RayHit(long sceneRevision, NodeId nodeId, GeometryId geometryId, long geometryRevision,
                     int primitiveIndex, long sourceFaceId, float distance, Vec3 worldPosition,
                     Vec3 localPosition, Vec3 worldNormal, boolean frontFace,
                     Optional<TriangleCoordinates> triangleCoordinates) {}
