package engine;

import math.Vec3;

/** Ray-aligned shear with double edge functions: adjacent triangles evaluate the same edge
 * with opposite signs. Inclusive edges and an area-independent parallel test avoid cracks
 * and the legacy absolute determinant cutoff on small/high-detail mesh faces. */
final class MeshSurface {
    private final Vec3 a,b,c;
    MeshSurface(Vec3 a,Vec3 b,Vec3 c){this.a=a;this.b=b;this.c=c;}
    private static double component(Vec3 p,int axis){return axis==0?p.x():axis==1?p.y():p.z();}
    private static double component(float x,float y,float z,int axis){return axis==0?x:axis==1?y:z;}
    float distance(float ox,float oy,float oz,float dx,float dy,float dz) {
        int kz=Math.abs(dx)>=Math.abs(dy)&&Math.abs(dx)>=Math.abs(dz)?0:Math.abs(dy)>=Math.abs(dz)?1:2;
        double direction=component(dx,dy,dz,kz);if(direction==0)return Float.POSITIVE_INFINITY;
        int kx=(kz+1)%3,ky=(kx+1)%3;if(direction<0){int swap=kx;kx=ky;ky=swap;}
        double sx=component(dx,dy,dz,kx)/direction,sy=component(dx,dy,dz,ky)/direction;
        double x=component(ox,oy,oz,kx),y=component(ox,oy,oz,ky),z=component(ox,oy,oz,kz);
        double az=component(a,kz)-z,bz=component(b,kz)-z,cz=component(c,kz)-z;
        double ax=component(a,kx)-x-sx*az,ay=component(a,ky)-y-sy*az;
        double bx=component(b,kx)-x-sx*bz,by=component(b,ky)-y-sy*bz;
        double cx=component(c,kx)-x-sx*cz,cy=component(c,ky)-y-sy*cz;
        double u=cx*by-cy*bx,v=ax*cy-ay*cx,w=bx*ay-by*ax;
        if((u<0||v<0||w<0)&&(u>0||v>0||w>0))return Float.POSITIVE_INFINITY;
        double det=u+v+w;if(det==0)return Float.POSITIVE_INFINITY;
        double t=(u*az+v*bz+w*cz)/(det*direction);
        return t>math.Plane.EPSILON?(float)t:Float.POSITIVE_INFINITY;
    }
}
