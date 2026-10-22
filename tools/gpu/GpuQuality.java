package gpu;

import engine.*;
import game.GameNavigation;
import java.nio.file.*;
import java.util.Locale;

/** Stationary equal-time radiance comparison. Preparation is warmed outside the timed interval. */
public final class GpuQuality {
    private GpuQuality() {}
    record Sample(float[] pixels,long samples,double elapsedMillis) {}
    static Sample timed(RenderSession session,WorldSnapshot world,RenderView view,RenderSettings settings) throws Exception {
        GpuCheck.waitImage(session,2);
        var next=settings.withSampleTarget(0).withRestartRevision(1);
        session.update(world,view,next);
        long generation=session.progress(null).requestedGeneration();
        long start=System.nanoTime(),deadline=start+500_000_000L,last=0;
        float[] accepted=null;
        long samples=0;
        double elapsed=0;
        while(System.nanoTime()<deadline) {
            session.request();
            try(var image=session.acquireImage()) {
                if(image!=null && image.generation()==generation && image.publicationNanos()!=last) {
                    last=image.publicationNanos();
                    var pixels=new float[view.width()*view.height()*3];
                    for(int y=0;y<view.height();y++) for(int x=0;x<view.width();x++) for(int c=0;c<3;c++)
                        pixels[(y*view.width()+x)*3+c]=image.rawPixels().value(c,x,y);
                    long now=System.nanoTime();
                    if(now<=deadline) {accepted=pixels;samples=image.samples();elapsed=(now-start)/1e6;}
                }
            }
            Thread.sleep(1);
        }
        session.suspend();
        if(accepted==null) throw new AssertionError("No completed image inside 500 ms budget");
        return new Sample(accepted,samples,elapsed);
    }
    public static void main(String[] args) throws Exception {
        var world=GpuCheck.landscape();
        var output=Path.of("out/gpu/quality.csv");
        var rows=new StringBuilder("width,height,backend,seed,budgetMs,lastCopiedMs,spp,linearRmse,referenceDeltaRmse\n");
        for(int width:new int[]{320,960}) {
            int height=width*5/8;
            var navigation=new GameNavigation();navigation.fixedResolution(width,height);var view=navigation.view();
            var referenceSettings=RenderSettings.defaults().withPathDepth(2).withSeed(73).withSampleTarget(2048).withSamplesPerBatch(8);
            var reference=GpuCheck.cpu(world,view,referenceSettings);
            var half=GpuCheck.cpu(world,view,referenceSettings.withSampleTarget(1024));
            double referenceDelta=GpuCheck.rmse(reference,half);
            GpuCheck.image(reference,width,height,"reference-"+width);
            for(int seed=1;seed<=3;seed++) {
                var settings=RenderSettings.defaults().withPathDepth(2).withSeed(seed).withSampleTarget(2);
                for(String backend:seed%2==1?new String[]{"cpu","gpu"}:new String[]{"gpu","cpu"}) {
                    Sample sample;
                    var session=RenderEngine.openSession(world,view,settings,backend);
                    try {sample=timed(session,world,view,settings);} finally {
                        session.close();
                        if(session instanceof GpuRenderSession gpu && !gpu.awaitClosed(30)) throw new AssertionError("GPU close timeout");
                    }
                    double error=GpuCheck.rmse(sample.pixels(),reference);
                    rows.append(String.format(Locale.ROOT,"%d,%d,%s,%d,500,%.4f,%d,%.8f,%.8f%n",width,height,backend,seed,sample.elapsedMillis(),sample.samples(),error,referenceDelta));
                    Files.writeString(output,rows);
                    System.out.printf(Locale.ROOT,"QUALITY %dx%d %s seed %d: %d spp RMSE %.6f ref delta %.6f%n",width,height,backend,seed,sample.samples(),error,referenceDelta);
                    if(seed==1) GpuCheck.image(sample.pixels(),width,height,"quality-"+backend+"-"+width);
                }
            }
        }
    }
}
