package engine;

/** Eligibility of the optional local differential-lighting approximation used by the GPU. */
public final class FogLightingGrid {
    private FogLightingGrid() {}

    public static boolean eligible(Atmosphere atmosphere, FogSunTable.Layout layout) {
        if (atmosphere == null || layout == null) {
            return false;
        }
        var air = atmosphere.parameters();
        float kilometresPerUnit = air.metersPerUnit() * .001f;
        // Keep curvature negligible within the local volume and resolve the steepest air profile.
        float diagonalKm = layout.extent().length() * kilometresPerUnit;
        float verticalCellKm = layout.extent().y() * kilometresPerUnit / (FogSunTable.SIZE - 1);
        return diagonalKm <= Math.min(1, air.radiusKm() * .0002f)
                && verticalCellKm <= Math.min(air.molecularHeightKm(), air.aerosolHeightKm()) / 12;
    }
}
