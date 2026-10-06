package engine;

import math.Vec3;
import engine.objects.Rect;

/** Immutable optics and pose. The legacy plane remains authoritative for perspective framing. */
public record Camera(Vec3 eye, Rect sensor, Projection projection, Mode mode, float focus, float aperture, float height, float rememberedAperture) {
    public enum Projection { PERSPECTIVE, ORTHOGRAPHIC }
    /** Compatibility shortcut label, not an optics discriminator. */
    public enum Mode { PERSPECTIVE, ORTHOGRAPHIC, LENS }
    public Camera {
        if(projection!=projectionOf(mode))throw new IllegalArgumentException("Camera shortcut and projection disagree");
    }
    private static Projection projectionOf(Mode mode) {return mode==Mode.ORTHOGRAPHIC?Projection.ORTHOGRAPHIC:Projection.PERSPECTIVE;}
    private Camera(Vec3 eye, Rect sensor, Mode mode, float focus, float aperture, float height, float rememberedAperture) {
        this(eye,sensor,projectionOf(mode),mode,focus,aperture,height,rememberedAperture);
    }
    public Camera(Vec3 eye, Rect sensor, Mode mode, float focus, float aperture, float height) {
        this(eye,sensor,mode,focus,mode==Mode.LENS?aperture:0,height,aperture);
    }
    public Camera(Vec3 eye, Rect sensor) { this(eye,sensor,Mode.PERSPECTIVE,5,0,0); }
    /** Validate a camera assembled through the canonical record constructor. */
    public Camera validated() {
        if(eye==null || sensor==null || projection==null || mode==null)
            throw new IllegalArgumentException("Camera fields cannot be null");
        positive(focus,"Focus distance");
        if(!Float.isFinite(aperture) || aperture<0 || !Float.isFinite(rememberedAperture) || rememberedAperture<0)
            throw new IllegalArgumentException("Aperture radius must be finite and nonnegative");
        if(projection==Projection.ORTHOGRAPHIC)positive(height,"Height");
        else if(!Float.isFinite(height) || height<0)throw new IllegalArgumentException("Remembered height must be finite and nonnegative");
        return checked(this);
    }
    /** Only active optics identify the estimator. A closed lens is exactly a pinhole. */
    public record Identity(Vec3 eye, Rect sensor, Mode mode, float focus, float aperture) {}
    Mode effectiveMode() { return projection==Projection.ORTHOGRAPHIC?Mode.ORTHOGRAPHIC:aperture>0?Mode.LENS:Mode.PERSPECTIVE; }
    public Identity identity() {
        return new Identity(eye,imagePlane(),effectiveMode(),
                aperture>0?focus:0,aperture);
    }
    public Vec3 forward() { return sensor.edge1().cross(sensor.edge2()).normalized(); }
    private Vec3 center() { return sensor.origin().add(sensor.edge1().scale(.5f)).add(sensor.edge2().scale(.5f)); }
    public float planeDistance() { return center().sub(eye).dot(forward()); }
    public float fov() { return (float)Math.toDegrees(2*Math.atan(sensor.edge2().length()/(2*planeDistance()))); }
    public boolean variableOrigin() { return projection==Projection.ORTHOGRAPHIC || aperture>0; }
    public boolean temporalSupported() { return aperture==0; }
    public Rect imagePlane() {
        if(projection!=Projection.ORTHOGRAPHIC)return sensor;
        var vertical=sensor.edge2().normalized().scale(height);
        var horizontal=sensor.edge1().normalized().scale(height*sensor.edge1().length()/sensor.edge2().length());
        return new Rect(eye.sub(horizontal.scale(.5f)).sub(vertical.scale(.5f)),horizontal,vertical);
    }
    public Camera withPose(Vec3 position, Rect plane) { return new Camera(position,plane,mode,focus,aperture,height,rememberedAperture); }
    private void standard() {
        var offset=center().sub(eye);float d=planeDistance();
        if(d<=0 || Math.abs(sensor.edge1().normalized().dot(sensor.edge2().normalized()))>1e-5
                || offset.sub(forward().scale(d)).length()>Math.max(1,d)*1e-5)
            throw new IllegalArgumentException("This off-center/skewed camera cannot be converted; reset camera first");
    }
    public Camera withMode(String name) {
        Mode next;
        try { next=Mode.valueOf(name.toUpperCase(java.util.Locale.ROOT)); }
        catch(IllegalArgumentException e) {throw new IllegalArgumentException("Camera mode must be perspective, orthographic, or lens");}
        float radius=next==Mode.LENS?rememberedAperture:0;
        if(next==mode && radius==aperture)return this;
        if(next!=Mode.PERSPECTIVE)standard();
        float span=height;
        if(next==Mode.ORTHOGRAPHIC && span==0)span=focus*sensor.edge2().length()/planeDistance();
        return checked(new Camera(eye,sensor,next,focus,radius,span,rememberedAperture));
    }
    /** Canonical projection control retains active aperture rather than applying a mode shortcut. */
    public Camera withProjection(String name) {
        if(!name.equals("perspective") && !name.equals("orthographic"))
            throw new IllegalArgumentException("Projection must be perspective or orthographic");
        Mode next=name.equals("orthographic")?Mode.ORTHOGRAPHIC:Mode.PERSPECTIVE;
        if(projectionOf(next)==projection)return this;
        standard();
        float span=next==Mode.ORTHOGRAPHIC && height==0?focus*sensor.edge2().length()/planeDistance():height;
        return checked(new Camera(eye,sensor,next,focus,aperture,span,rememberedAperture));
    }
    public Camera withFov(float degrees) {
        if(mode==Mode.ORTHOGRAPHIC)throw new IllegalArgumentException("Use view camera height in orthographic mode");
        if(!Float.isFinite(degrees) || degrees<1 || degrees>179)throw new IllegalArgumentException("FOV must be 1..179 degrees");
        if(degrees==fov())return this;
        standard();
        float scale=(float)(2*planeDistance()*Math.tan(Math.toRadians(degrees)*.5))/sensor.edge2().length();
        var a=sensor.edge1().scale(scale);var b=sensor.edge2().scale(scale);
        var plane=new Rect(center().sub(a.scale(.5f)).sub(b.scale(.5f)),a,b);
        return checked(new Camera(eye,plane,mode,focus,aperture,height,rememberedAperture));
    }
    public Camera withHeight(float value) {
        if(mode!=Mode.ORTHOGRAPHIC)throw new IllegalArgumentException("Use view camera fov in perspective/lens mode");
        positive(value,"Height");
        return value==height?this:checked(new Camera(eye,sensor,mode,focus,aperture,value,rememberedAperture));
    }
    public Camera withFocus(float value) {
        positive(value,"Focus distance");
        return value==focus?this:checked(new Camera(eye,sensor,mode,value,aperture,height,rememberedAperture));
    }
    public Camera withAperture(float value) {
        if(!Float.isFinite(value) || value<0)throw new IllegalArgumentException("Aperture radius must be finite and nonnegative");
        if(value>0)standard();
        return value==aperture && value==rememberedAperture?this:checked(new Camera(eye,sensor,mode,focus,value,height,value));
    }
    private static void positive(float value,String label) {
        if(!Float.isFinite(value) || value<=0)throw new IllegalArgumentException(label+" must be finite and positive");
    }
    private static boolean finite(Vec3 v) {return Float.isFinite(v.x())&&Float.isFinite(v.y())&&Float.isFinite(v.z());}
    private static Camera checked(Camera c) {
        var p=c.imagePlane();
        if(!finite(p.origin()) || !finite(p.edge1()) || !finite(p.edge2())
                || !Float.isFinite(p.edge1().lengthSq()) || !Float.isFinite(p.edge2().lengthSq())
                || p.edge1().lengthSq()==0 || p.edge2().lengthSq()==0
                || p.origin().add(p.edge1()).equals(p.origin()) || p.origin().add(p.edge2()).equals(p.origin())
                || !finite(c.eye.add(c.forward().scale(c.focus)))
                || c.eye.add(c.forward().scale(c.focus)).equals(c.eye)
                || c.aperture>0 && (!finite(c.eye.add(p.edge1().normalized().scale(c.aperture)))
                || c.eye.add(p.edge1().normalized().scale(c.aperture)).equals(c.eye)))
            throw new IllegalArgumentException("Camera geometry is not numerically representable");
        var compiled=c.compile();var ray=new RaySample();
        for(float u:new float[]{0,1})for(float v:new float[]{0,1})for(float angle:new float[]{0,.25f,.5f,.75f}) {
            compiled.sample(u,v,1,angle,ray);
            float length=ray.dx*ray.dx+ray.dy*ray.dy+ray.dz*ray.dz;
            if(!Float.isFinite(ray.ox) || !Float.isFinite(ray.oy) || !Float.isFinite(ray.oz)
                    || !Float.isFinite(length) || length<.99f || length>1.01f)
                throw new IllegalArgumentException("Camera geometry is not numerically representable");
        }
        return c;
    }
    public String summary() {
        return String.format(java.util.Locale.ROOT,"camera=%s %s focus=%.4g units aperture=%.4g units dof=%s",
                mode.name().toLowerCase(java.util.Locale.ROOT),mode==Mode.ORTHOGRAPHIC?
                        String.format(java.util.Locale.ROOT,"height=%.4g units",height):
                        String.format(java.util.Locale.ROOT,"fov=%.4g degrees",fov()),focus,aperture,
                aperture>0?"finite":"infinite")+" projection="+projection().name().toLowerCase(java.util.Locale.ROOT);
    }
    /** Compile once per captured job; all scalar ray scratch belongs to the worker. */
    Compiled compile() { return new Compiled(this); }
    static final class RaySample { float ox,oy,oz,dx,dy,dz; }
    static final class Compiled {
        final Camera camera;
        final Vec3 eye,right,up,forward;
        final Rect plane;
        Compiled(Camera camera) {
            this.camera=camera;eye=camera.eye;plane=camera.imagePlane();
            right=camera.sensor.edge1().normalized();up=camera.sensor.edge2().normalized();forward=camera.forward();
        }
        void reference(float u,float v,RaySample r) { sample(u,v,0,0,r,false); }
        void sample(float u,float v,float apertureU,float apertureV,RaySample r) { sample(u,v,apertureU,apertureV,r,true); }
        private void sample(float u,float v,float apertureU,float apertureV,RaySample r,boolean lens) {
            r.ox=eye.x();r.oy=eye.y();r.oz=eye.z();
            if(camera.projection==Projection.ORTHOGRAPHIC) {
                r.ox=plane.origin().x()+plane.edge1().x()*u+plane.edge2().x()*v;
                r.oy=plane.origin().y()+plane.edge1().y()*u+plane.edge2().y()*v;
                r.oz=plane.origin().z()+plane.edge1().z()*u+plane.edge2().z()*v;
                r.dx=forward.x();r.dy=forward.y();r.dz=forward.z();
                if(lens && camera.aperture>0) {
                    float radius=camera.aperture*(float)Math.sqrt(apertureU);
                    double angle=2*Math.PI*apertureV;
                    float a=radius*(float)Math.cos(angle),b=radius*(float)Math.sin(angle);
                    float lx=right.x()*a+up.x()*b,ly=right.y()*a+up.y()*b,lz=right.z()*a+up.z()*b;
                    r.ox+=lx;r.oy+=ly;r.oz+=lz;
                    float dx=forward.x()*camera.focus-lx,dy=forward.y()*camera.focus-ly,dz=forward.z()*camera.focus-lz;
                    float inv=1/(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
                    r.dx=dx*inv;r.dy=dy*inv;r.dz=dz*inv;
                }
                return;
            }
            // Keep the original float operations and normalization order exactly.
            float dx=plane.origin().x()+plane.edge1().x()*u+plane.edge2().x()*v-eye.x();
            float dy=plane.origin().y()+plane.edge1().y()*u+plane.edge2().y()*v-eye.y();
            float dz=plane.origin().z()+plane.edge1().z()*u+plane.edge2().z()*v-eye.z();
            float inverseLength=1/(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
            dx*=inverseLength;dy*=inverseLength;dz*=inverseLength;
            if(lens && camera.aperture>0) {
                float distance=camera.focus/(dx*forward.x()+dy*forward.y()+dz*forward.z());
                float radius=camera.aperture*(float)Math.sqrt(apertureU);
                double angle=2*Math.PI*apertureV;
                float a=radius*(float)Math.cos(angle),b=radius*(float)Math.sin(angle);
                float lx=right.x()*a+up.x()*b,ly=right.y()*a+up.y()*b,lz=right.z()*a+up.z()*b;
                r.ox+=lx;r.oy+=ly;r.oz+=lz;
                dx=dx*distance-lx;dy=dy*distance-ly;dz=dz*distance-lz;
                inverseLength=1/(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
                dx*=inverseLength;dy*=inverseLength;dz*=inverseLength;
            }
            r.dx=dx;r.dy=dy;r.dz=dz;
        }
    }
}
