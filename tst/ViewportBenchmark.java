import di.Injector;
import profiling.FrameProfiler;
import rendering.Checkerboard;
import rendering.Eraser;
import scenes.viewport.Viewport;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import scenes.Scene;
import math.Vec3;
import engine.objects.Rect;
import scenes.viewport.ScenePresets;
import profiling.RuntimeMetrics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;

/** Headless full-resolution benchmark; timings exclude AWT presentation. */
public class ViewportBenchmark {
    public static void main(String[] args) throws Exception {
        var module = new MainModule();
        var injector = Injector.create(module);
        module.registerScenes(injector);
        var viewport = injector.get(Viewport.class);
        var display=injector.get(rendering.Raster.class);
        int displayWidth=display.width(),displayHeight=display.height();
        boolean close = Arrays.asList(args).contains("close");
        ScenePresets.load(viewport.state(), Arrays.asList(args).contains("volume-room") ? "volume-room"
                : Arrays.asList(args).contains("mesh-room") ? "mesh-room"
                : Arrays.asList(args).contains("rough-room") ? "rough-room"
                : Arrays.asList(args).contains("glass-inside") ? "glass-inside"
                : Arrays.asList(args).contains("glass") ? "glass"
                : Arrays.asList(args).contains("bounce-room") ? "bounce-room"
                : Arrays.asList(args).contains("playground") ? "playground" : "triangle");
        for (String arg : args) {
            if (arg.startsWith("size=")) {
                String size=arg.substring(5);
                if (size.equals("native") || size.equals("half") || size.equals("quarter")) {
                    double scale=size.equals("native")?1:size.equals("half")?.5:.25;
                    viewport.state().resolution((int)Math.round(displayWidth*scale),(int)Math.round(displayHeight*scale));
                } else {
                    var dimensions=size.split("x");
                    viewport.state().resolution(Integer.parseInt(dimensions[0]),Integer.parseInt(dimensions[dimensions.length-1]));
                }
            }
            if (arg.startsWith("workers=")) viewport.state().workers(Integer.parseInt(arg.substring(8)));
            if (arg.startsWith("tile=")) viewport.state().tileSize(Integer.parseInt(arg.substring(5)));
            if (arg.startsWith("depth=")) viewport.state().pathDepth(Integer.parseInt(arg.substring(6)));
            if (arg.startsWith("samples=")) viewport.state().samplesPerFrame(Integer.parseInt(arg.substring(8)));
            if (arg.startsWith("seed=")) viewport.state().seed(Long.parseLong(arg.substring(5)));
            if (arg.equals("brute")) viewport.state().acceleration(false);
            if (arg.startsWith("detail=")) {
                var mesh=engine.PolygonMesh.approximateSphere(new engine.AnalyticSphere(math.Vec3.ZERO,1),Integer.parseInt(arg.substring(7)));
                var instances=viewport.state().instances();
                for(int i=0;i<instances.size();i++){var o=instances.get(i);if(o.geometry() instanceof engine.PolygonMesh)instances.set(i,new engine.SceneInstance(o.name(),mesh,o.transform(),o.material()));}
            }
        }
        int instances=option(args,"instances",0);
        // Configure mode before its dependent optics, regardless of argument order.
        for(String arg:args)if(arg.startsWith("camera="))viewport.state().camera(viewport.state().camera().withMode(arg.substring(7)));
        for(String arg:args) {
            var camera=viewport.state().camera();
            if(arg.startsWith("fov="))viewport.state().camera(camera.withFov(Float.parseFloat(arg.substring(4))));
            if(arg.startsWith("height="))viewport.state().camera(camera.withHeight(Float.parseFloat(arg.substring(7))));
            if(arg.startsWith("focus="))viewport.state().camera(camera.withFocus(Float.parseFloat(arg.substring(6))));
            if(arg.startsWith("aperture="))viewport.state().camera(camera.withAperture(Float.parseFloat(arg.substring(9))));
        }
        if(instances>0) {
            var state=viewport.state();
            if(instances<state.instances().size() || instances>128)throw new IllegalArgumentException("instances must be initial count..128");
            var model=state.instances().stream().filter(o->o.geometry() instanceof engine.PolygonMesh).findFirst().orElseThrow();
            while(state.instances().size()<instances) {
                int index=state.instances().size(); var t=model.transform();
                state.instances().add(new engine.SceneInstance("benchmark-copy-"+index,model.geometry(),
                        new engine.Transform(t.position.add(new Vec3((index%8)*3,0,6+(index/8)*3)),t.rotation,t.scale),model.material()));
            }
        }
        if (close) {
            var state = viewport.state();
            var delta = new Vec3(0, 0, 9);
            state.eye(state.eye().add(delta));
            var sensor = state.cameraSensor();
            state.cameraSensor(new Rect(sensor.origin().add(delta), sensor.edge1(), sensor.edge2()));
        }
        var eraser = injector.get(Eraser.class);
        var checker = injector.get(Checkerboard.class);
        var profiler = injector.get(FrameProfiler.class);
        if (Arrays.asList(args).contains("details")) profiler.toggleTraceDetails();
        var pipeline = module.renderer(List.of(eraser, checker, (rendering.Renderer) viewport::renderBlocking), profiler,
                new AtomicReference<Scene>(viewport));
        int warmup=option(args,"warmup",50), frames=option(args,"frames",100);
        if(warmup<1 || frames<1) throw new IllegalArgumentException("warmup and frames must be positive");
        boolean motion=Arrays.asList(args).contains("motion");
        viewport.state().sampleTarget(0); // Timing frames must never silently become idle frames.
        long[] times = new long[frames];
        long[][] stages=new long[FrameProfiler.Stage.values().length][frames];
        var rows=new ArrayList<String>();
        rows.add("frame,wall_ns,trace_ns,resample_ns,paint_ns,cpu_ns,frame_alloc_bytes,trace_alloc_bytes,actual_spp,accumulated_spp,primary,continuation,shadow,primary_tests,continuation_tests,shadow_tests");
        long cpuTotal=0, allocTotal=0, traceAllocTotal=0, raysTotal=0, traceTotal=0, actualSpp=0;
        long gcCount=0,gcMillis=0;
        var originalEye=viewport.state().eye(); var originalSensor=viewport.state().cameraSensor();
        var metrics=new RuntimeMetrics();
        String metadata="revision="+revision()+"; working-tree source SHA256="+sourceHash()+"; args="+String.join(" ",args)
                +"\nJDK="+System.getProperty("java.runtime.version")+"; VM="+System.getProperty("java.vm.name")
                +"; OS="+System.getProperty("os.name")+" "+System.getProperty("os.arch")
                +"; CPU="+System.getenv("PROCESSOR_IDENTIFIER")+"; processors="+Runtime.getRuntime().availableProcessors()
                +"\ndisplay="+displayWidth+"x"+displayHeight+"; sensor="+viewport.state().sensorPixelsW()+"x"+viewport.state().sensorPixelsH()
                +"; workers="+viewport.state().workers()+"; tile="+viewport.state().tileSize()
                +"; "+viewport.state().camera().summary()+"; camera="+originalEye+"; sensorGeometry="+originalSensor
                +"\npreset="+viewport.state().preset()+"; depth="+viewport.state().pathDepth()+"; seed="+viewport.state().seed()
                +"; requestedSpp="+viewport.state().samplesPerFrame()+"; acceleration="+viewport.state().acceleration()
                +"; instances="+viewport.state().instances().size()+"; primitives="+viewport.state().instances().stream().mapToInt(o->o.geometry() instanceof engine.PolygonMesh mesh?mesh.renderPrimitiveCount():1).sum()
                +"; exposure="+viewport.state().exposure()
                +"; raw uniform samples; reconstruction=off; motion="+motion
                +"\nHeadless: excludes AWT presentation, scheduler idle, and real input delivery. Motion changes the camera before each timed render; event-to-image latency and generation/cancellation metrics apply in P2.";
        System.out.println(metadata);
        for (int i = -warmup; i < times.length; i++) {
            if(i==0) metrics.sample();
            if(motion) {
                var delta=new Vec3((float)Math.sin((i+warmup)*.1)*.2f,0,0);
                viewport.state().eye(originalEye.add(delta));
                viewport.state().cameraSensor(new Rect(originalSensor.origin().add(delta),originalSensor.edge1(),originalSensor.edge2()));
            }
            long cpu=RuntimeMetrics.threadCpu(),bytes=RuntimeMetrics.allocatedBytes();
            long start = System.nanoTime();
            pipeline.render();
            long elapsed=System.nanoTime()-start;
            long frameCpu=sumMetric(RuntimeMetrics.delta(cpu,RuntimeMetrics.threadCpu()),viewport.tracer().workerCpuNanos);
            long frameBytes=sumMetric(RuntimeMetrics.delta(bytes,RuntimeMetrics.allocatedBytes()),viewport.tracer().workerAllocatedBytes);
            profiler.beginFrame(); // Publish before reading; exclude benchmark bookkeeping from wall/alloc counters.
            if (i >= 0) {
                times[i]=elapsed;
                var frame=profiler.latestFrame(); var trace=frame.trace(); var rays=frame.rays();
                if(trace.samplesPerPixel()==0) throw new IllegalStateException("Timing an idle trace");
                for(var stage:FrameProfiler.Stage.values()) stages[stage.ordinal()][i]=frame.stage(stage);
                cpuTotal=sumMetric(cpuTotal,frameCpu); allocTotal=sumMetric(allocTotal,frameBytes);
                traceAllocTotal=sumMetric(traceAllocTotal,trace.allocatedBytes());
                actualSpp+=trace.samplesPerPixel(); traceTotal+=rays.traceNanos();
                raysTotal+=rays.primary()+trace.continuationRays()+rays.shadows();
                rows.add(i+","+elapsed+","+rays.traceNanos()+","+frame.stage(FrameProfiler.Stage.RESAMPLE)+","+frame.stage(FrameProfiler.Stage.PAINT)
                        +","+frameCpu+","+frameBytes+","+trace.allocatedBytes()+","+trace.samplesPerPixel()+","+trace.accumulatedSamples()
                        +","+rays.primary()+","+trace.continuationRays()+","+rays.shadows()+","+trace.primaryTests()+","+trace.continuationTests()+","+trace.shadowTests());
            }
        }
        var gc=metrics.sample(); gcCount=gc.gcCount(); gcMillis=gc.gcMillis();
        Arrays.sort(times);
        System.out.printf("Headless frame: median %.2f ms, p95 %.2f ms%n", percentile(times,.5)/1e6, percentile(times,.95)/1e6);
        for(var stage:new FrameProfiler.Stage[]{FrameProfiler.Stage.TRACE,FrameProfiler.Stage.RESAMPLE,FrameProfiler.Stage.PAINT,FrameProfiler.Stage.BACKGROUND}) {
            var values=stages[stage.ordinal()]; Arrays.sort(values);
            System.out.printf("%s: median %.2f ms, p95 %.2f ms%n",stage,percentile(values,.5)/1e6,percentile(values,.95)/1e6);
        }
        long wallTotal=Arrays.stream(times).sum();
        System.out.printf("Aggregate CPU/wall cores: %.2f; frame allocated bytes/frame: %d; trace bytes/frame: %d; GC count=%d time=%d ms; measured spp=%d%n",
                cpuTotal<0?-1:(double)cpuTotal/wallTotal,allocTotal<0?-1:allocTotal/frames,traceAllocTotal<0?-1:traceAllocTotal/frames,gcCount,gcMillis,actualSpp);
        var frame = profiler.latestFrame();
        System.out.println("Scene: " + viewport.state().preset() + (close ? " close" : "")
                + "; acceleration="+(viewport.state().acceleration()?"bvh":"brute")
                + "; primitives="+viewport.state().instances().stream().mapToInt(o->o.geometry() instanceof engine.PolygonMesh mesh?mesh.renderPrimitiveCount():1).sum()
                + "; depth=" + viewport.state().pathDepth() + "; spp/batch max=" + viewport.state().samplesPerFrame()
                + "; accumulated=" + viewport.state().accumulatedSamples() + "; seed=" + viewport.state().seed()
                + (viewport.state().pathDepth() == 0 ? "; pixel centers" : "; jittered progressive") + "; rays: " + frame.rays());
        System.out.printf("Trace throughput (primary + continuation + visibility): %.2f Mrays/s%n",
                raysTotal*1000./traceTotal);
        System.out.println("Trace profile: " + frame.trace());
        System.out.println("Trace execution samples: " + profiler.traceSamples());
        viewport.state().eye(originalEye); viewport.state().cameraSensor(originalSensor);
        String convergence=convergence(args,viewport,pipeline);
        System.out.print(convergence);
        for(String arg:args) if(arg.startsWith("output=")) {
            var path=Path.of(arg.substring(7)); if(path.getParent()!=null) Files.createDirectories(path.getParent());
            Files.write(path,rows); Files.writeString(Path.of(path+".txt"),metadata+"\nScene settings: "+frame.trace()
                    +"\nframe median_ns="+percentile(times,.5)+" p95_ns="+percentile(times,.95)
                    +"; aggregate_cpu_ns="+cpuTotal+"; frame_alloc_bytes="+allocTotal+"; trace_alloc_bytes="+traceAllocTotal
                    +"; gc_count="+gcCount+"; gc_ms="+gcMillis+"\n"+convergence);
        }
        viewport.close();
        profiler.close();
    }
    private static int option(String[] args,String key,int fallback) {
        for(String arg:args) if(arg.startsWith(key+"=")) return Integer.parseInt(arg.substring(key.length()+1));
        return fallback;
    }
    private static long sumMetric(long a,long b) {return a<0||b<0?-1:a+b;}
    private static long percentile(long[] sorted,double p) {return sorted[Math.max(0,(int)Math.ceil(sorted.length*p)-1)];}
    private static String revision() throws Exception {
        var path=Path.of(".git/HEAD"); if(!Files.isRegularFile(path)) return "unknown (pass revision externally for worktrees)";
        String head=Files.readString(path).strip();
        if(!head.startsWith("ref: "))return head;
        var ref=Path.of(".git",head.substring(5)); return Files.exists(ref)?Files.readString(ref).strip():head;
    }
    private static String sourceHash() throws Exception {
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        try(var paths=Files.walk(Path.of("src"))) {
            for(var path:paths.filter(p->p.toString().endsWith(".java")).sorted().toList()) {
                digest.update(path.toString().replace('\\','/').getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(path));
            }
        }
        digest.update(Files.readAllBytes(Path.of("tst/ViewportBenchmark.java")));
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
    private static float[][][] copy(float[][][] image) {
        var result=new float[3][image[0].length][];
        for(int c=0;c<3;c++)for(int y=0;y<image[c].length;y++)result[c][y]=image[c][y].clone();
        return result;
    }
    private static String convergence(String[] args,Viewport viewport,rendering.Renderer pipeline) {
        int target=option(args,"target",8), millis=option(args,"qualityMillis",0), reference=option(args,"reference",128);
        if(target<1 || millis<0 || reference<1)throw new IllegalArgumentException("Positive target/reference and nonnegative qualityMillis required");
        var state=viewport.state(); state.restart(); state.sampleTarget(target);
        long start=System.nanoTime(); do {pipeline.render();} while(state.accumulatedSamples()<target);
        String result=String.format(java.util.Locale.ROOT,"Stationary time to %d spp (headless frame pipeline): %.2f ms%n",target,(System.nanoTime()-start)/1e6);
        if(millis==0)return result;
        state.restart(); state.sampleTarget(0); start=System.nanoTime();
        do {pipeline.render();}while(System.nanoTime()-start<millis*1_000_000L);
        long elapsed=System.nanoTime()-start, spp=state.accumulatedSamples();
        var candidate=copy(viewport.tracer().radianceBuffer());
        long seed=state.seed(); state.seed(seed^0x632be59bd9b4e019L); state.sampleTarget(reference);
        do {pipeline.render();} while(state.accumulatedSamples()!=reference);
        var truth=viewport.tracer().radianceBuffer(); double error=0,energy=0;
        for(int c=0;c<3;c++)for(int y=0;y<candidate[c].length;y++)for(int x=0;x<candidate[c][y].length;x++) {
            double delta=candidate[c][y][x]-truth[c][y][x];error+=delta*delta;energy+=(double)truth[c][y][x]*truth[c][y][x];
        }
        result+=String.format(java.util.Locale.ROOT,"Quality: requested %d ms, actual %.2f ms (complete-pass overshoot), %d spp; independent reference=%d spp; linear RMSE=%g, relative RMS=%g%n",
                millis,elapsed/1e6,spp,reference,Math.sqrt(error/(3L*state.sensorPixelsW()*state.sensorPixelsH())),energy>0?Math.sqrt(error/energy):error==0?0:Double.POSITIVE_INFINITY);
        state.seed(seed);
        return result;
    }
}
