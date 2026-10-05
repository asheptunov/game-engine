package scenes.viewport;

import math.Vec3;

/** Linear RGB reflectance, GGX surface roughness, interior medium and emitted radiance. */
public record Material(String name, Vec3 color, Kind kind, float ior, Vec3 absorption, float roughness, Vec3 emission) {
    public enum Kind { DIFFUSE, MIRROR, DIELECTRIC }
    public Material(String name, Vec3 color, Kind kind) { this(name, color, kind, 1.5f, Vec3.ZERO); }
    public Material(String name, Vec3 color, Kind kind, float ior, Vec3 absorption) { this(name,color,kind,ior,absorption,0,Vec3.ZERO); }
    public Material(String name, Vec3 color) { this(name, color, Kind.DIFFUSE); }
    public Material {
        if (name == null || name.isBlank() || color == null || kind == null) throw new IllegalArgumentException("Invalid material");
        for (float c : new float[]{color.x(), color.y(), color.z()})
            if (!Float.isFinite(c) || c < 0 || c > 1) throw new IllegalArgumentException("Reflectance must be 0..1");
        if (!Float.isFinite(ior) || ior < 1 || ior > 3) throw new IllegalArgumentException("IOR must be 1..3");
        if (absorption == null) throw new IllegalArgumentException("Missing absorption");
        for (float c : new float[]{absorption.x(), absorption.y(), absorption.z()})
            if (!Float.isFinite(c) || c < 0 || c > 100) throw new IllegalArgumentException("Absorption must be 0..100 per scene unit");
        if (!Float.isFinite(roughness) || roughness < 0 || roughness > 1) throw new IllegalArgumentException("Roughness must be 0..1");
        if (emission == null) throw new IllegalArgumentException("Missing emission");
        for (float c : new float[]{emission.x(), emission.y(), emission.z()})
            if (!Float.isFinite(c) || c < 0 || c > 10000) throw new IllegalArgumentException("Emission must be 0..10000 linear radiance");
    }
    public Material withKind(Kind next) { return new Material(name, color, next, ior, absorption,roughness,emission); }
    public Material withColor(Vec3 next) { return new Material(name, next, kind, ior, absorption,roughness,emission); }
    public Material withIor(float next) { return new Material(name, color, kind, next, absorption,roughness,emission); }
    public Material withAbsorption(Vec3 next) { return new Material(name, color, kind, ior, next,roughness,emission); }
    public Material withRoughness(float next) { return new Material(name,color,kind,ior,absorption,next,emission); }
    public Material withEmission(Vec3 next) { return new Material(name,color,kind,ior,absorption,roughness,next); }
    public boolean emissive() { return emission.x()+emission.y()+emission.z()>0; }
    public boolean delta(float incident, float exit) { return kind!=Kind.DIFFUSE && (roughness==0 || kind==Kind.DIELECTRIC && incident==exit); }
    /** Caller-owned scratch. Probability is solid-angle PDF for diffuse, discrete mass for delta events. */
    public static final class Sample {
        public float dx, dy, dz, red, green, blue, probability;
        public Kind event;
        public boolean transmitted;
        public boolean delta;
    }
    /** Normal faces the incoming ray; returns direction and f*cos/pdf (or delta-event weight). */
    public void sample(float dx, float dy, float dz, float nx, float ny, float nz,
                       float u, float v, Sample out) {
        out.event = kind;
        out.transmitted = false;
        out.delta = kind == Kind.MIRROR;
        if (kind == Kind.DIELECTRIC) throw new IllegalArgumentException("Dielectric sampling requires incident and transmitted IOR");
        out.red = color.x(); out.green = color.y(); out.blue = color.z();
        if (kind == Kind.MIRROR) {
            float twice = 2*(dx*nx + dy*ny + dz*nz);
            out.dx = dx - twice*nx; out.dy = dy - twice*ny; out.dz = dz - twice*nz;
            out.probability = 1;
            return;
        }
        // Cosine-weighted hemisphere: f*cos/pdf = reflectance.
        float r = (float)Math.sqrt(u), phi = (float)(2*Math.PI*v);
        float a = r*(float)Math.cos(phi), b = r*(float)Math.sin(phi);
        float c = (float)Math.sqrt(Math.max(0, 1-u));
        float tx, ty, tz;
        if (Math.abs(nz) < .999f) {
            float inv = 1/(float)Math.sqrt(nx*nx + ny*ny);
            tx = -ny*inv; ty = nx*inv; tz = 0;
        } else {
            float inv = 1/(float)Math.sqrt(ny*ny + nz*nz);
            tx = 0; ty = -nz*inv; tz = ny*inv;
        }
        out.dx = tx*a + (ny*tz - nz*ty)*b + nx*c;
        out.dy = ty*a + (nz*tx - nx*tz)*b + ny*c;
        out.dz = tz*a + (nx*ty - ny*tx)*b + nz*c;
        out.probability = c/(float)Math.PI;
    }
    /** Smooth dielectric, radiance transport: transmission carries (etaIncident/etaExit)^2.
     * Fresnel is also the branch probability, so its coefficient cancels in the weight. */
    public void sampleDielectric(float dx, float dy, float dz, float nx, float ny, float nz,
                                 float incident, float exit, float u, Sample out) {
        float cosine = Math.clamp(-(dx*nx+dy*ny+dz*nz), 0, 1);
        float eta = incident/exit;
        float sinSquared = eta*eta*Math.max(0, 1-cosine*cosine);
        float transmittedCosine = (float)Math.sqrt(Math.max(0,1-sinSquared));
        float fresnel = fresnel(incident, exit, cosine);
        out.event = Kind.DIELECTRIC;
        out.delta = true;
        out.transmitted = (incident == exit || sinSquared < 1) && u >= fresnel;
        if (!out.transmitted) {
            out.dx=dx+2*cosine*nx;out.dy=dy+2*cosine*ny;out.dz=dz+2*cosine*nz;
            out.red=out.green=out.blue=1;out.probability=fresnel;
        } else {
            float normalWeight=eta*cosine-transmittedCosine;
            out.dx=eta*dx+normalWeight*nx;out.dy=eta*dy+normalWeight*ny;out.dz=eta*dz+normalWeight*nz;
            out.red=out.green=out.blue=eta*eta;out.probability=1-fresnel;
        }
    }
    /** Unified scattering entry point. GGX uses alpha=max(1e-4,roughness^2);
     * sampling rejected microfacets contributes zero, rather than resampling a biased PDF. */
    public void scatter(float dx,float dy,float dz,float nx,float ny,float nz,
                        float incident,float exit,float u,float v,float branch,Sample out) {
        if (kind==Kind.DIFFUSE || delta(incident,exit)) {
            if(kind==Kind.DIELECTRIC) sampleDielectric(dx,dy,dz,nx,ny,nz,incident,exit,branch,out);
            else sample(dx,dy,dz,nx,ny,nz,u,v,out);
            return;
        }
        float alpha=Math.max(1e-4f,roughness*roughness);
        double denominator=1-(double)u+(double)alpha*alpha*u;
        float cos=(float)Math.sqrt((1-u)/denominator);
        float sin=(float)Math.sqrt((double)alpha*alpha*u/denominator),phi=(float)(2*Math.PI*v);
        float tx,ty,tz;
        if(Math.abs(nz)<.999f) {float inv=1/(float)Math.sqrt(nx*nx+ny*ny);tx=-ny*inv;ty=nx*inv;tz=0;}
        else {float inv=1/(float)Math.sqrt(ny*ny+nz*nz);tx=0;ty=-nz*inv;tz=ny*inv;}
        float a=sin*(float)Math.cos(phi),b=sin*(float)Math.sin(phi);
        float hx=tx*a+(ny*tz-nz*ty)*b+nx*cos;
        float hy=ty*a+(nz*tx-nx*tz)*b+ny*cos;
        float hz=tz*a+(nx*ty-ny*tx)*b+nz*cos;
        float ih=-(dx*hx+dy*hy+dz*hz);
        out.red=out.green=out.blue=out.probability=0;out.transmitted=false;out.delta=false;out.event=kind;
        if(ih<=0) return;
        if(kind==Kind.DIELECTRIC) sampleDielectric(dx,dy,dz,hx,hy,hz,incident,exit,branch,out);
        else {out.dx=dx+2*ih*hx;out.dy=dy+2*ih*hy;out.dz=dz+2*ih*hz;}
        out.delta=false;
        float co=nx*out.dx+ny*out.dy+nz*out.dz;
        if((out.transmitted && co>=0) || (!out.transmitted && co<=0)) {out.red=out.green=out.blue=out.probability=0;return;}
        evaluate(dx,dy,dz,out.dx,out.dy,out.dz,nx,ny,nz,incident,exit,out);
        if(out.probability>0) {out.red/=out.probability;out.green/=out.probability;out.blue/=out.probability;}
        else out.red=out.green=out.blue=0;
    }
    /** Returns f*abs(n.wo) in RGB and the matching solid-angle PDF, in caller scratch. */
    public void evaluate(float dx,float dy,float dz,float ox,float oy,float oz,float nx,float ny,float nz,
                         float incident,float exit,Sample out) {
        out.red=out.green=out.blue=out.probability=0;
        float ci=-(dx*nx+dy*ny+dz*nz),co=ox*nx+oy*ny+oz*nz;
        if(ci<=0 || co==0 || delta(incident,exit)) return;
        if(kind==Kind.DIFFUSE) {
            if(co<0)return;
            float f=co/(float)Math.PI;out.red=color.x()*f;out.green=color.y()*f;out.blue=color.z()*f;out.probability=f;return;
        }
        boolean transmit=co<0;
        if(transmit && kind!=Kind.DIELECTRIC)return;
        float hx=-dx+(transmit?exit/incident:1)*ox,hy=-dy+(transmit?exit/incident:1)*oy,hz=-dz+(transmit?exit/incident:1)*oz;
        float length=(float)Math.sqrt(hx*hx+hy*hy+hz*hz);if(length<1e-12f)return;
        hx/=length;hy/=length;hz/=length;
        if(hx*nx+hy*ny+hz*nz<0) {hx=-hx;hy=-hy;hz=-hz;}
        float ih=-(dx*hx+dy*hy+dz*hz),oh=ox*hx+oy*hy+oz*hz,nh=hx*nx+hy*ny+hz*nz;
        if(ih<=0 || nh<=0 || transmit && oh>=0 || !transmit && oh<=0)return;
        float alpha=Math.max(1e-4f,roughness*roughness),a2=alpha*alpha;
        // Use |n cross h|^2 rather than 1-(n.h)^2: polished normals round n.h to 1.
        float cx=ny*hz-nz*hy,cy=nz*hx-nx*hz,cz=nx*hy-ny*hx;
        float denominator=cx*cx+cy*cy+cz*cz+a2*nh*nh;
        float distribution=a2/((float)Math.PI*denominator*denominator);
        float geometry=smith(ci,a2)*smith(Math.abs(co),a2);
        float fresnel=kind==Kind.DIELECTRIC?fresnel(incident,exit,ih):1;
        float f,p;
        if(!transmit) {f=fresnel*distribution*geometry/(4*ci);p=fresnel*distribution*nh/(4*ih);}
        else {
            float sum=incident*ih+exit*oh;
            if(Math.abs(sum)<1e-12f)return;
            f=(1-fresnel)*distribution*geometry*Math.abs(ih*oh)*incident*incident/(ci*sum*sum);
            p=(1-fresnel)*distribution*nh*exit*exit*Math.abs(oh)/(sum*sum);
        }
        out.red=f*(kind==Kind.MIRROR?color.x():1);out.green=f*(kind==Kind.MIRROR?color.y():1);out.blue=f*(kind==Kind.MIRROR?color.z():1);out.probability=p;
    }
    private static float smith(float cosine,float a2) { return 2*cosine/(cosine+(float)Math.sqrt(a2+(1-a2)*cosine*cosine)); }
    static float fresnel(float incident, float exit, float cosine) {
        if (incident == exit) return 0;
        float eta=incident/exit, sinSquared=eta*eta*Math.max(0,1-cosine*cosine);
        if (sinSquared >= 1) return 1;
        float ct=(float)Math.sqrt(1-sinSquared);
        float parallel=(exit*cosine-incident*ct)/(exit*cosine+incident*ct);
        float perpendicular=(incident*cosine-exit*ct)/(incident*cosine+exit*ct);
        return .5f*(parallel*parallel+perpendicular*perpendicular);
    }
    public static Material srgb(String name, int rgb) {
        return new Material(name, new Vec3(DisplayMapping.linear((rgb >> 16) & 255),
                DisplayMapping.linear((rgb >> 8) & 255), DisplayMapping.linear(rgb & 255)));
    }
}
