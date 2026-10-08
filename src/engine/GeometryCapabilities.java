package engine;

/** Cheap, validated facts used to reject incompatible materials before scene publication. */
public record GeometryCapabilities(boolean closedBoundary,boolean analyticSphere,
                                   boolean parallelogramEmitter,boolean canonicalBoxVolume) {
    public static final GeometryCapabilities ANALYTIC_SPHERE=new GeometryCapabilities(true,true,false,false);
    public static final GeometryCapabilities OPEN_POLYGON=new GeometryCapabilities(false,false,false,false);
}
