package engine;

import math.Vec3;
import engine.objects.Rect;

/** One-sided rectangular emitter. Radiance stays fixed when its world-space area changes. */
final class PreparedEmitter {
    final PreparedObject object;
    final Vec3 origin, edge1, edge2, normal;
    final float area;
    PreparedEmitter(PreparedObject object, SceneInstance instance) {
        this.object=object;
        var rect=(Rect)instance.geometry().getFirst();var transform=instance.transform();
        origin=transform.point(rect.origin());edge1=transform.vector(rect.edge1());edge2=transform.vector(rect.edge2());
        var cross=edge1.cross(edge2);area=cross.length();normal=cross.normalized();
        if(!Float.isFinite(area) || area<=0)throw new IllegalArgumentException("Invalid emitter area");
    }
    float pdf(float x,float y,float z,float hx,float hy,float hz) {
        float dx=hx-x,dy=hy-y,dz=hz-z,d2=dx*dx+dy*dy+dz*dz;
        float cosine=-(normal.x()*dx+normal.y()*dy+normal.z()*dz)/(float)Math.sqrt(d2);
        return cosine>0?d2/(area*cosine):0;
    }
}
