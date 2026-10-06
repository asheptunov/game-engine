package engine;

/** Homogeneous scalar scattering with RGB absorption; all distances are world-space units. */
final class Volume {
    private Volume() {}
    static float distance(float density, float u) {
        return density == 0 ? Float.POSITIVE_INFINITY : (float)(-Math.log1p(-u)/density);
    }
    static float phase(float cosine, float g) {
        double d=1+(double)g*g-2*g*Math.clamp(cosine,-1,1);
        return (float)((1-(double)g*g)/(4*Math.PI*d*Math.sqrt(d)));
    }
    /** HG about the incoming travel direction; sampling PDF equals phase, so weight is one. */
    static void sample(float dx,float dy,float dz,float g,float u,float v,Material.Sample out) {
        float cosine;
        if(Math.abs(g)<1e-4f) cosine=2*u-1;
        else {
            double s=(1-(double)g*g)/(1-g+2*g*u);
            cosine=(float)((1+(double)g*g-s*s)/(2*g));
        }
        cosine=Math.clamp(cosine,-1,1);
        float r=(float)Math.sqrt(Math.max(0,1-cosine*cosine)),phi=(float)(2*Math.PI*v);
        float a=r*(float)Math.cos(phi),b=r*(float)Math.sin(phi);
        float tx,ty,tz;
        if(Math.abs(dz)<.999f){float inv=1/(float)Math.sqrt(dx*dx+dy*dy);tx=-dy*inv;ty=dx*inv;tz=0;}
        else {float inv=1/(float)Math.sqrt(dy*dy+dz*dz);tx=0;ty=-dz*inv;tz=dy*inv;}
        out.dx=tx*a+(dy*tz-dz*ty)*b+dx*cosine;
        out.dy=ty*a+(dz*tx-dx*tz)*b+dy*cosine;
        out.dz=tz*a+(dx*ty-dy*tx)*b+dz*cosine;
        out.probability=phase(cosine,g);out.delta=false;out.transmitted=false;
        out.red=out.green=out.blue=1;
    }
}
