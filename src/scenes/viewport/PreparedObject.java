package scenes.viewport;

import math.Vec3;
import scenes.viewport.objects.*;

/** One conservative world-space box for multi-primitive objects; no hierarchy in phase 1. */
final class PreparedObject {
    final PreparedPrimitive[] primitives;
    private float minX=Float.POSITIVE_INFINITY,minY=minX,minZ=minX;
    private float maxX=Float.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
    PreparedObject(SceneInstance instance) {
        primitives=new PreparedPrimitive[instance.geometry().size()];
        for(int i=0;i<primitives.length;i++) {
            primitives[i]=new PreparedPrimitive(instance,i);
            if(primitives.length<2) continue;
            var transform=instance.transform();
            switch(instance.geometry().get(i)) {
                case Tri t -> {include(transform.point(t.a()));include(transform.point(t.b()));include(transform.point(t.c()));}
                case Rect r -> {include(transform.point(r.origin()));include(transform.point(r.origin().add(r.edge1())));
                    include(transform.point(r.origin().add(r.edge2())));include(transform.point(r.origin().add(r.edge1()).add(r.edge2())));}
                case Sphere s -> {
                    for(int x=-1;x<=1;x+=2)for(int y=-1;y<=1;y+=2)for(int z=-1;z<=1;z+=2)
                        include(transform.point(s.center().add(new Vec3(x*s.radius(),y*s.radius(),z*s.radius()))));
                }
            }
        }
        // Conservative padding accommodates intersection roundoff at edges and flat bounds.
        minX-=1e-4f;minY-=1e-4f;minZ-=1e-4f;maxX+=1e-4f;maxY+=1e-4f;maxZ+=1e-4f;
    }
    private void include(Vec3 p) {
        minX=Math.min(minX,p.x());minY=Math.min(minY,p.y());minZ=Math.min(minZ,p.z());
        maxX=Math.max(maxX,p.x());maxY=Math.max(maxY,p.y());maxZ=Math.max(maxZ,p.z());
    }
    boolean overlaps(float ox,float oy,float oz,float dx,float dy,float dz,float maxDistance) {
        float near=0,far=maxDistance;
        if(dx==0) {if(ox<minX||ox>maxX)return false;}
        else {float a=(minX-ox)/dx,b=(maxX-ox)/dx;near=Math.max(near,Math.min(a,b));far=Math.min(far,Math.max(a,b));if(near>far)return false;}
        if(dy==0) {if(oy<minY||oy>maxY)return false;}
        else {float a=(minY-oy)/dy,b=(maxY-oy)/dy;near=Math.max(near,Math.min(a,b));far=Math.min(far,Math.max(a,b));if(near>far)return false;}
        if(dz==0) {if(oz<minZ||oz>maxZ)return false;}
        else {float a=(minZ-oz)/dz,b=(maxZ-oz)/dz;near=Math.max(near,Math.min(a,b));far=Math.min(far,Math.max(a,b));}
        return near<=far;
    }
}
