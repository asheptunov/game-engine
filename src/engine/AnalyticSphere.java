package engine;

import math.Vec3;

/** Analytic sphere geometry. */
public record AnalyticSphere(Vec3 center, float radius) implements GeometryData {
    public AnalyticSphere { if(center==null||!Float.isFinite(center.x())||!Float.isFinite(center.y())||!Float.isFinite(center.z())||!Float.isFinite(radius)||radius<=0)throw new IllegalArgumentException("Analytic sphere must be finite with positive radius"); }
    @Override public GeometryCapabilities capabilities(){return GeometryCapabilities.ANALYTIC_SPHERE;}
}
