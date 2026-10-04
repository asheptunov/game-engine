package scenes.viewport;

import math.Vec3;

/** Positive scale, then Euler rotations X/Y/Z in degrees, then translation. */
public final class Transform {
    public static final Transform IDENTITY = new Transform(Vec3.ZERO, Vec3.ZERO, new Vec3(1, 1, 1));
    public final Vec3 position, rotation, scale;
    // Orthonormal rotation matrix. Inverse transforms preserve the ray parameter: never normalize local rays.
    final float a,b,c,d,e,f,g,h,i;
    public Transform(Vec3 position, Vec3 rotation, Vec3 scale) {
        for (var v : new Vec3[]{position, rotation, scale})
            if (!Float.isFinite(v.x()) || !Float.isFinite(v.y()) || !Float.isFinite(v.z()))
                throw new IllegalArgumentException("Transform must be finite");
        if (scale.x() < .01f || scale.y() < .01f || scale.z() < .01f)
            throw new IllegalArgumentException("Scale must be >= 0.01 on every axis");
        this.position = position; this.rotation = rotation; this.scale = scale;
        double x = Math.toRadians(rotation.x()), y = Math.toRadians(rotation.y()), z = Math.toRadians(rotation.z());
        float sx=(float)Math.sin(x), cx=(float)Math.cos(x), sy=(float)Math.sin(y), cy=(float)Math.cos(y), sz=(float)Math.sin(z), cz=(float)Math.cos(z);
        a=cz*cy; b=cz*sy*sx-sz*cx; c=cz*sy*cx+sz*sx;
        d=sz*cy; e=sz*sy*sx+cz*cx; f=sz*sy*cx-cz*sx;
        g=-sy; h=cy*sx; i=cy*cx;
    }
    public Vec3 point(Vec3 p) { return vector(p).add(position); }
    public Vec3 vector(Vec3 p) {
        float x=p.x()*scale.x(), y=p.y()*scale.y(), z=p.z()*scale.z();
        return new Vec3(a*x+b*y+c*z, d*x+e*y+f*z, g*x+h*y+i*z);
    }
    public Vec3 inversePoint(Vec3 p) { return inverseVector(p.sub(position)); }
    public Vec3 inverseVector(Vec3 p) {
        return new Vec3((a*p.x()+d*p.y()+g*p.z())/scale.x(),
                (b*p.x()+e*p.y()+h*p.z())/scale.y(), (c*p.x()+f*p.y()+i*p.z())/scale.z());
    }
    public Vec3 normal(Vec3 n) {
        float x=n.x()/scale.x(), y=n.y()/scale.y(), z=n.z()/scale.z();
        return new Vec3(a*x+b*y+c*z,d*x+e*y+f*z,g*x+h*y+i*z).normalized();
    }
    @Override public String toString() { return "position="+position+" rotation="+rotation+" scale="+scale; }
}
