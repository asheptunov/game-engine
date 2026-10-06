package scenes.viewport;

/** Coordinator-owned, flat first-hit metadata. Zero identity means unsuitable for reuse. */
final class SurfaceGuide {
    final int width, height;
    final int[] surface;
    // Normalized-ray distance, geometric normal facing the camera. Recover positions from the captured camera.
    final float[] depth, nx, ny, nz;
    SurfaceGuide(int width, int height) {
        this.width=width; this.height=height;
        int n=width*height;
        surface=new int[n]; depth=new float[n]; nx=new float[n]; ny=new float[n]; nz=new float[n];
    }
    void copyFrom(SurfaceGuide source) {
        System.arraycopy(source.surface,0,surface,0,surface.length);
        System.arraycopy(source.depth,0,depth,0,depth.length);
        System.arraycopy(source.nx,0,nx,0,nx.length);
        System.arraycopy(source.ny,0,ny,0,ny.length);
        System.arraycopy(source.nz,0,nz,0,nz.length);
    }
}
