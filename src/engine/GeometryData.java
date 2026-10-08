package engine;

/** Canonical immutable geometry stored by a shared asset, separate from renderer primitives. */
public sealed interface GeometryData permits AnalyticSphere, PolygonMesh {
    GeometryCapabilities capabilities();
}
