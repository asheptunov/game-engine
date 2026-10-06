import di.Injector;
import math.Vec3;
import profiling.FrameProfiler;
import scenes.viewport.*;
import java.util.Locale;

/** Window-free software fresh-image measurements on an identical elapsed-time camera path. */
public class TemporalBudgetLiveBenchmark {
    public static void main(String[] args) throws Exception {
        String preset=args.length>0?args[0]:"bounce-room";
        System.out.println(preset+" 800x500, batch3, target8, seed1, 2s warmup/3s measured motion, nominal144Hz; excludes AWT/overlay");
        System.out.println("mode,fresh_fps,interval_mean_ms,p95_ms,age_p95_ms,min_width,max_width,grid_changes,restore_ms,final_spp");
        for(String mode:new String[]{"raw3","budget1","p4","budget-p4","budget-low","budget-p4","p4","budget1","raw3"}) {
            var module=new MainModule();var injector=Injector.create(module);module.registerScenes(injector);
            try(var viewport=injector.get(Viewport.class);var profiler=injector.get(FrameProfiler.class)) {
                var s=viewport.state();s.resolution(800,500);ScenePresets.load(s,preset);s.samplesPerFrame(3);s.sampleTarget(8);
                boolean budget=mode.contains("budget");s.temporal(budget);s.temporalBudget(budget);s.interactive(mode.contains("p4"));
                if(mode.equals("budget-low"))s.motionScale(.5);
                var initial=s.eye();var sensor=s.cameraSensor();long start=System.nanoTime();
                int previous=800,changes=0,min=800,max=0;double restore=-1;FrameProfiler.ImageSnapshot moving=null;
                do {
                    long before=System.nanoTime();double seconds=(before-start)/1e9;
                    if(seconds<5) synchronized(s) {
                        var delta=new Vec3((float)Math.sin(seconds*1.5)*.1f,0,0);s.eye(initial.add(delta));
                        s.cameraSensor(new engine.objects.Rect(sensor.origin().add(delta),sensor.edge1(),sensor.edge2()));
                    }
                    profiler.beginFrame();viewport.render();profiler.endFrame();
                    if(seconds<5) {
                        int width=s.sampledWidth();if(seconds>=2){min=Math.min(min,width);max=Math.max(max,width);}
                        if(width!=previous)changes++;previous=width;
                    }
                    if(seconds>=5 && moving==null)moving=profiler.imageSnapshot();
                    var progress=profiler.renderProgress();var frame=profiler.latestFrame();
                    if(seconds>=5 && restore<0 && progress!=null && progress.generation()==progress.shownGeneration()
                            && frame!=null && frame.rays()!=null && frame.rays().width()==800 && s.sampledWidth()==800
                            && progress.completedSamples()>0)restore=(System.nanoTime()-start)/1e6-5000;
                    if(seconds>=6 && s.sampledWidth()==800 && s.accumulatedSamples()>=8)break;
                    long wait=6_944_444-(System.nanoTime()-before);if(wait>0)Thread.sleep(wait/1_000_000,(int)(wait%1_000_000));
                }while(System.nanoTime()-start<10_000_000_000L);
                System.out.printf(Locale.ROOT,"%s,%.2f,%.2f,%.2f,%.2f,%d,%d,%d,%.2f,%d%n",mode,moving.fps(),moving.meanMs(),moving.p95Ms(),moving.ageP95Ms(),min,max,changes,restore,s.accumulatedSamples());
            }
        }
    }
}
