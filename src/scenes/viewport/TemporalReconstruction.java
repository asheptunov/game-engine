package scenes.viewport;

/** Bounded diffuse-only presentation history; never modifies raw radiance or raw spp. */
final class TemporalReconstruction {
    record Stats(int eligible, int reused, double confidence, long nanos, long bytes, String reason) {
        String label() {
            return String.format(java.util.Locale.ROOT,"History %d/%d px | blend %.0f%% | %.2fms | %.1fMiB | %s",
                    reused,eligible,confidence*100,nanos/1e6,bytes/1048576.,reason);
        }
    }
    private static final class History {
        final SurfaceGuide guide;
        final float[][][] rgb;
        final float[] weight;
        ViewportState.RenderKey key;
        CameraProjection camera;
        long time;
        History(int w,int h) {
            guide=new SurfaceGuide(w,h);rgb=new float[3][h][w];weight=new float[w*h];
        }
    }
    // Frozen source camera and current reconstruction. Repeated raw refinement does not count itself twice.
    private History source, current;
    private Stats stats=new Stats(0,0,0,0,0,"off");
    Stats stats() { return stats; }
    /** Coordinator-only preflight; pixel correspondence is still checked during reconstruction. */
    boolean compatibleHistory(ViewportState.RenderKey key,long now) {
        if(current==null || current.key==null || stats.eligible()==0) return false;
        var previous=key.equals(current.key)?source:current;
        return previous!=null && previous.key!=null && key.sameTransport(previous.key)
                && now-previous.time<=500_000_000L && !new CameraProjection(key).cut(previous.camera);
    }
    void clear() { source=current=null;stats=new Stats(0,0,0,0,0,"off"); }
    float[][][] reconstruct(float[][][] raw,SurfaceGuide guide,ViewportState.RenderKey key,long samples,long now) {
        return reconstruct(raw,guide,key,samples,now,1);
    }
    float[][][] reconstruct(float[][][] raw,SurfaceGuide guide,ViewportState.RenderKey key,long samples,long now,int workers) {
        return reconstruct(raw,guide,key,samples,now,workers,()->false);
    }
    float[][][] reconstruct(float[][][] raw,SurfaceGuide guide,ViewportState.RenderKey key,long samples,long now,int workers,
                           java.util.function.BooleanSupplier cancel) {
        long start=System.nanoTime();
        if(guide==null || samples==0) {
            clear(); stats=new Stats(0,0,0,System.nanoTime()-start,0,"raw fallback");return raw;
        }
        int w=key.width(),h=key.height();
        if(current==null || current.guide.width!=w || current.guide.height!=h) {
            source=new History(w,h);current=new History(w,h);
        } else if(!key.equals(current.key)) {
            var swap=source;source=current;current=swap;
        }
        var camera=new CameraProjection(key);
        String reason=source.key==null?"new history":!key.sameTransport(source.key)?"scene/grid edit"
                :now-source.time>500_000_000L?"expired":camera.cut(source.camera)?"camera cut":"diffuse";
        boolean compatible=reason.equals("diffuse");
        int count=Math.min(workers,Math.max(1,h/16));
        var parts=new Part[count];
        for(int i=0;i<count;i++) parts[i]=new Part(raw,guide,key,samples,compatible,i*h/count,(i+1)*h/count,cancel);
        if(count==1) parts[0].run();
        else {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            Throwable failure=null;boolean interrupted=false;
            try { for(var part:parts) futures.add(DirectRgbTracer.submitReconstruction(part)); }
            catch(RuntimeException error) { failure=error; }
            for(var future:futures) {
                boolean done=false;
                while(!done) try {future.get();done=true;}
                catch(InterruptedException error) { interrupted=true; }
                catch(java.util.concurrent.ExecutionException error) {failure=error.getCause();done=true;}
            }
            if(interrupted) Thread.currentThread().interrupt();
            if(failure!=null || interrupted) {clear();throw new IllegalStateException("Reconstruction failed",failure);}
        }
        if(cancel.getAsBoolean()) { clear();return raw; }
        int eligible=0,reused=0;double confidence=0;
        for(var part:parts) {eligible+=part.eligible;reused+=part.reused;confidence+=part.confidence;}
        current.guide.copyFrom(guide);current.key=key;current.camera=camera;current.time=now;
        // Two histories at 36 B/pixel (guide 20, RGB 12, weight 4), excluding row/header overhead.
        stats=new Stats(eligible,reused,eligible==0?0:confidence/eligible,System.nanoTime()-start,72L*w*h,reason);
        return current.rgb;
    }
    private final class Part implements Runnable {
        final float[][][] raw;
        final SurfaceGuide guide;
        final ViewportState.RenderKey key;
        final long samples;
        final boolean compatible;
        final int from,to;
        final java.util.function.BooleanSupplier cancel;
        int eligible,reused;
        double confidence;
        Part(float[][][] raw,SurfaceGuide guide,ViewportState.RenderKey key,long samples,boolean compatible,int from,int to,
             java.util.function.BooleanSupplier cancel) {
            this.raw=raw;this.guide=guide;this.key=key;this.samples=samples;this.compatible=compatible;this.from=from;this.to=to;
            this.cancel=cancel;
        }
        @Override public void run() {
            int w=key.width();var camera=new CameraProjection(key);var oldCamera=compatible?new CameraProjection(source.key):null;
            for(int y=from;y<to;y++) {
                if(cancel.getAsBoolean()) return;
                for(int x=0;x<w;x++) {
                    int i=y*w+x,old=-1;
                    if(guide.surface[i]!=0) {
                        eligible++;
                        if(compatible && interior(guide,x,y)) {
                            camera.point(x,y,guide.depth[i]);
                            old=oldCamera.project(camera.px,camera.py,camera.pz);
                            if(old>=0 && (source.guide.surface[old]!=guide.surface[i]
                                    || !interior(source.guide,old%w,old/w)
                                    || !matches(guide,i,source.guide,old,camera,oldCamera))) old=-1;
                        }
                    }
                    float history=old<0?0:Math.min(4,source.weight[old]);
                    // At least 20% fresh radiance: old contributions fade by <=0.8 per camera transition.
                    // No synchronized age reset; valid surfaces retain a stable blend during sustained motion.
                    double blend=history/(samples+history);
                    for(int c=0;c<3;c++) {
                        float value=raw[c][y][x];
                        if(old>=0) {
                            // Clamp to current local radiance range to limit trails from moving shadows/highlights.
                            float lo=value,hi=value;
                            for(int yy=y-1;yy<=y+1;yy++) for(int xx=x-1;xx<=x+1;xx++) {
                                lo=Math.min(lo,raw[c][yy][xx]);hi=Math.max(hi,raw[c][yy][xx]);
                            }
                            float prior=Math.clamp(source.rgb[c][old/w][old%w],lo,hi);
                            value=(float)(value+(prior-value)*blend);
                        }
                        current.rgb[c][y][x]=value;
                    }
                    current.weight[i]=(float)Math.min(5,samples+history);
                    if(old>=0) { reused++;confidence+=blend; }
                }
            }
        }
    }
    private static boolean interior(SurfaceGuide guide,int x,int y) {
        if(x==0 || y==0 || x==guide.width-1 || y==guide.height-1) return false;
        int id=guide.surface[y*guide.width+x];
        for(int yy=y-1;yy<=y+1;yy++) for(int xx=x-1;xx<=x+1;xx++)
            if(guide.surface[yy*guide.width+xx]!=id) return false;
        return true;
    }
    private static boolean matches(SurfaceGuide a,int i,SurfaceGuide b,int j,CameraProjection camera,CameraProjection old) {
        if(a.nx[i]*b.nx[j]+a.ny[i]*b.ny[j]+a.nz[i]*b.nz[j]<.995f) return false;
        old.point(j%b.width,j/b.width,b.depth[j]);
        double dx=camera.px-old.px,dy=camera.py-old.py,dz=camera.pz-old.pz;
        double footprint=Math.max(camera.footprint(a.depth[i]),old.footprint(b.depth[j]));
        // Tangent displacement may span neighboring pixel centers; separation off the surface may not.
        return dx*dx+dy*dy+dz*dz<=2.25*footprint*footprint
                && Math.abs(dx*a.nx[i]+dy*a.ny[i]+dz*a.nz[i])<=.1*footprint+1e-4;
    }
}
