package engine;

import engine.objects.Rect;
import math.Vec3;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Immutable sharp-reference projection for editor overlays and picking. Coordinates are
 * normalized to the camera image with {@code v=0} at the bottom. Finite-aperture cameras
 * deliberately use the same zero-aperture reference geometry as {@link SpatialQuery#screenRay}.
 */
public final class CameraProjector {
    public record ProjectedPoint(double u, double v, double axialDepth) {
        public boolean insideViewport() { return u >= 0 && u <= 1 && v >= 0 && v <= 1; }
    }
    public record ProjectedSegment(ProjectedPoint start, ProjectedPoint end) {
        public ProjectedSegment { Objects.requireNonNull(start); Objects.requireNonNull(end); }
    }

    private final Camera camera;
    private final boolean orthographic;
    private final double ex,ey,ez,ox,oy,oz,ax,ay,az,bx,by,bz,nx,ny,nz;
    private final double aa,ab,bb,det,plane,near,screenHeight;

    private CameraProjector(Camera camera) {
        this.camera=Objects.requireNonNull(camera,"camera").validated();
        Rect image=camera.imagePlane();var eye=camera.eye();
        orthographic=camera.projection()==Camera.Projection.ORTHOGRAPHIC;
        ex=eye.x();ey=eye.y();ez=eye.z();ox=image.origin().x();oy=image.origin().y();oz=image.origin().z();
        ax=image.edge1().x();ay=image.edge1().y();az=image.edge1().z();
        bx=image.edge2().x();by=image.edge2().y();bz=image.edge2().z();
        double cx=ay*bz-az*by,cy=az*bx-ax*bz,cz=ax*by-ay*bx;
        double inverse=1/Math.sqrt(cx*cx+cy*cy+cz*cz);nx=cx*inverse;ny=cy*inverse;nz=cz*inverse;
        aa=ax*ax+ay*ay+az*az;ab=ax*bx+ay*by+az*bz;bb=bx*bx+by*by+bz*bz;det=aa*bb-ab*ab;
        plane=nx*(ox-ex)+ny*(oy-ey)+nz*(oz-ez);screenHeight=Math.sqrt(bb);
        double reference=orthographic?screenHeight:Math.abs(plane);
        near=Math.max(1e-7,reference*1e-6);
        if(!(det>0) || !(screenHeight>0) || !orthographic && !(plane>0))
            throw new IllegalArgumentException("Camera projection is degenerate");
    }

    public static CameraProjector of(Camera camera){return new CameraProjector(camera);}
    public Camera camera(){return camera;}
    public double nearDistance(){return near;}

    /** A sharp reference ray exactly congruent with public spatial picking. */
    public RayQuery referenceRay(double u,double v) {
        if(!Double.isFinite(u)||!Double.isFinite(v)||u<0||u>1||v<0||v>1)
            throw new IllegalArgumentException("Screen coordinates must be 0..1");
        return SpatialQuery.screenRay(camera,(float)u,(float)v);
    }

    /** Project a point in front of the near plane. Points outside the image remain projectable. */
    public Optional<ProjectedPoint> project(Vec3 point) {
        Objects.requireNonNull(point,"point");
        double x=point.x(),y=point.y(),z=point.z();
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))
            throw new IllegalArgumentException("Projected point must be finite");
        double depth=depth(x,y,z);if(!(depth>=near))return Optional.empty();
        return Optional.of(project(x,y,z,depth));
    }

    /** Clip a world segment against the camera near plane and normalized image rectangle. */
    public Optional<ProjectedSegment> clipAndProject(Vec3 start,Vec3 end) {
        Objects.requireNonNull(start,"start");Objects.requireNonNull(end,"end");
        double[] a={start.x(),start.y(),start.z()},b={end.x(),end.y(),end.z()};
        for(double value:a)if(!Double.isFinite(value))throw new IllegalArgumentException("Segment must be finite");
        for(double value:b)if(!Double.isFinite(value))throw new IllegalArgumentException("Segment must be finite");
        double da=depth(a[0],a[1],a[2]),db=depth(b[0],b[1],b[2]);
        if(da<near&&db<near)return Optional.empty();
        if(da<near||db<near) {
            double t=(near-da)/(db-da);
            double[] clipped={a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t,a[2]+(b[2]-a[2])*t};
            if(da<near){a=clipped;da=near;}else{b=clipped;db=near;}
        }
        var pa=project(a[0],a[1],a[2],da);var pb=project(b[0],b[1],b[2],db);
        double[] range={0,1};
        if(!clip(- (pb.u-pa.u),pa.u,range)||!clip(pb.u-pa.u,1-pa.u,range)
                ||!clip(- (pb.v-pa.v),pa.v,range)||!clip(pb.v-pa.v,1-pa.v,range))return Optional.empty();
        return Optional.of(new ProjectedSegment(interpolate(pa,pb,range[0]),interpolate(pa,pb,range[1])));
    }

    /** World height represented by the full normalized screen height at this point's depth. */
    public OptionalDouble screenHeightAt(Vec3 point) {
        Objects.requireNonNull(point,"point");double depth=depth(point.x(),point.y(),point.z());
        if(!(depth>=near))return OptionalDouble.empty();
        return OptionalDouble.of(orthographic?screenHeight:screenHeight*depth/plane);
    }

    private double depth(double x,double y,double z){return nx*(x-ex)+ny*(y-ey)+nz*(z-ez);}
    private ProjectedPoint project(double x,double y,double z,double depth) {
        double qx,qy,qz;
        if(orthographic){qx=x-ox;qy=y-oy;qz=z-oz;}
        else {double t=plane/depth;qx=ex+(x-ex)*t-ox;qy=ey+(y-ey)*t-oy;qz=ez+(z-ez)*t-oz;}
        double qa=qx*ax+qy*ay+qz*az,qb=qx*bx+qy*by+qz*bz;
        return new ProjectedPoint((qa*bb-qb*ab)/det,(qb*aa-qa*ab)/det,depth);
    }
    private static boolean clip(double p,double q,double[] range) {
        if(Math.abs(p)<1e-15)return q>=0;
        double r=q/p;
        if(p<0){if(r>range[1])return false;if(r>range[0])range[0]=r;}
        else {if(r<range[0])return false;if(r<range[1])range[1]=r;}
        return true;
    }
    private ProjectedPoint interpolate(ProjectedPoint a,ProjectedPoint b,double t) {
        double depth=orthographic?a.axialDepth+(b.axialDepth-a.axialDepth)*t
                :1/((1-t)/a.axialDepth+t/b.axialDepth);
        return new ProjectedPoint(a.u+(b.u-a.u)*t,a.v+(b.v-a.v)*t,depth);
    }
}
