package game;

import java.nio.file.*;
import java.util.*;

/** Alternating frozen-route comparison through the actual game owner and copy path, no window. */
public final class GpuRouteComparison {
    private GpuRouteComparison() {}
    private static final class Measurements {
        final List<Double> intervals=new ArrayList<>(), ages=new ArrayList<>(), copies=new ArrayList<>(), traces=new ArrayList<>();
        final StringBuilder rows=new StringBuilder("seconds,width,height,spp,generation,traceMs,copyMs,ageMs,pickupMs\n");
        double last, recovery=-1;
        long firstSamples, finalSamples;
        int fresh;
        void accept(GameRenderer.Frame frame,double seconds,engine.Camera finalCamera) {
            if(seconds<0 || seconds>30) return;
            var m=frame.metrics();
            rows.append(String.format(Locale.ROOT,"%.6f,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6f%n",seconds,frame.image().getWidth(),frame.image().getHeight(),frame.samples(),frame.generation(),m.traceNanos()/1e6,m.copyNanos()/1e6,m.ageNanos()/1e6,m.pickupNanos()/1e6));
            if(seconds<=25) {
                intervals.add((seconds-last)*1000);last=seconds;fresh++;
                ages.add(m.ageNanos()/1e6);copies.add(m.copyNanos()/1e6);traces.add(m.traceNanos()/1e6);
            } else if(frame.camera().equals(finalCamera)) {
                if(recovery<0) {recovery=seconds-25;firstSamples=frame.samples();}
                finalSamples=frame.samples();
            }
        }
        double quantile(List<Double> values,double q) {
            if(values.isEmpty()) return Double.NaN;
            var sorted=new ArrayList<>(values);Collections.sort(sorted);
            return sorted.get(Math.min(sorted.size()-1,(int)Math.ceil(sorted.size()*q)-1));
        }
        String summary(String backend,int width,int height,int run) {
            intervals.add((25-last)*1000);
            return String.format(Locale.ROOT,"%s,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d%n",backend,width,height,run,fresh/25.0,quantile(intervals,.5),quantile(intervals,.95),quantile(ages,.95),quantile(copies,.5),quantile(traces,.5),recovery,finalSamples);
        }
    }
    private static long allocatedBytes() {
        var bean=java.lang.management.ManagementFactory.getThreadMXBean();
        if(!(bean instanceof com.sun.management.ThreadMXBean extended) || !extended.isThreadAllocatedMemorySupported()) return -1;
        if(!extended.isThreadAllocatedMemoryEnabled()) extended.setThreadAllocatedMemoryEnabled(true);
        long total=0;
        for(long value:extended.getThreadAllocatedBytes(extended.getAllThreadIds())) if(value>=0) total+=value;
        return total;
    }
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args.length==0?"out/gpu/route":args[0]);Files.createDirectories(output);
        var route=CameraRoute.load(Path.of("benchmarks/game/camera-route.csv"));
        Files.copy(Path.of("benchmarks/game/camera-route.csv"),output.resolve("camera-route.csv"),StandardCopyOption.REPLACE_EXISTING);
        var summary=new StringBuilder("backend,width,height,run,freshPerSecond,medianIntervalMs,p95IntervalMs,p95AgeMs,medianCopyMs,medianTraceHostMs,recoverySeconds,finalSpp\n");
        for(int width:new int[]{320,960}) {
            int height=width*5/8;
            var cpuNavigation=new GameNavigation();cpuNavigation.fixedResolution(width,height);cpuNavigation.active(false);
            var gpuNavigation=new GameNavigation();gpuNavigation.fixedResolution(width,height);gpuNavigation.active(false);
            try(var cpu=new GameRenderer(cpuNavigation,BlockWorld.landscape(),"cpu");var gpu=new GameRenderer(gpuNavigation,BlockWorld.landscape(),"gpu")) {
                for(int run=0;run<=3;run++) {
                    for(String backend:run%2==0?new String[]{"cpu","gpu"}:new String[]{"gpu","cpu"}) {
                        var renderer=backend.equals("cpu")?cpu:gpu;
                        var navigation=backend.equals("cpu")?cpuNavigation:gpuNavigation;
                        route.apply(navigation,0);renderer.restart();navigation.active(true);
                        var measurements=new Measurements();
                        var finalNavigation=new GameNavigation();route.apply(finalNavigation,25);var finalCamera=finalNavigation.view().camera();
                        for(var pool:java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
                            if(pool.getType()==java.lang.management.MemoryType.HEAP) pool.resetPeakUsage();
                        }
                        long allocatedStart=allocatedBytes();
                        long start=System.nanoTime();
                        renderer.observePublications(frame->measurements.accept(frame,(frame.publicationNanos()-start)/1e9,finalCamera));
                        while((System.nanoTime()-start)<30_000_000_000L) {
                            route.apply(navigation,(System.nanoTime()-start)/1e9);
                            if(renderer.error()!=null) throw new AssertionError(renderer.error());
                            Thread.sleep(2);
                        }
                        renderer.observePublications(null);navigation.active(false);
                        long deadline=System.nanoTime()+30_000_000_000L;
                        while((!renderer.suspended() || renderer.tracing()) && System.nanoTime()<deadline) Thread.sleep(2);
                        if(renderer.tracing()) throw new AssertionError("Previous backend did not drain");
                        long allocatedEnd=allocatedBytes();
                        long peakHeap=0;
                        for(var pool:java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
                            if(pool.getType()==java.lang.management.MemoryType.HEAP) peakHeap+=pool.getPeakUsage().getUsed();
                        }
                        String prefix=backend+"-"+width+"-"+run;
                        Files.writeString(output.resolve(prefix+"-publications.csv"),measurements.rows);
                        Files.writeString(output.resolve(prefix+"-metadata.txt"),"backend="+backend+"\nJDK="+System.getProperty("java.runtime.version")+"\nsettings="+renderer.settings()+"\nwindow=960x600 reference; no native window\nstatus="+renderer.backendStatus()+"\nsumHeapPoolPeaksBytes="+peakHeap+"\nactiveThreadAllocatedDeltaBytes="+(allocatedStart<0||allocatedEnd<0?-1:allocatedEnd-allocatedStart)+"\n");
                        String row=measurements.summary(backend,width,height,run);
                        System.out.print((run==0?"WARMUP ":"MEASURE ")+row);
                        if(run>0) { summary.append(row);Files.writeString(output.resolve("summary.csv"),summary); }
                    }
                }
            }
        }
    }
}
