package scenes.viewport;

/** Window-free query/cache cost, separate from transport profiling. */
public final class FocusQueryBenchmark {
    public static void main(String[] args) {
        for(String preset:new String[]{"playground","mesh-room"}) {
            var state=TemporalReconstructionTest.plane();ScenePresets.load(state,preset);
            var resolver=new CameraFocus();var scene=CameraFocus.Scene.capture(state);
            for(int i=0;i<100;i++)resolver.resolve(new FocusTargetSource.Screen(.25f+(i%11)*.05f,.5f),state.camera(),scene,0,System.nanoTime(),System::nanoTime);
            long prepared=resolver.preparations,tests=resolver.primitiveTests;var times=new long[1000];int old=0;
            for(int i=0;i<times.length;i++) {
                long captured=System.nanoTime();var result=resolver.resolve(new FocusTargetSource.Screen(.25f+(i%11)*.05f,.5f),state.camera(),scene,0,captured,System::nanoTime);
                times[i]=System.nanoTime()-captured;if(result.completed()-result.captured()>100_000_000)old++;
            }
            java.util.Arrays.sort(times);
            System.out.printf(java.util.Locale.ROOT,"%s queries=1000 median=%.4fms p95=%.4fms preparations=%d additional=%d primitive-tests=%d over-100ms=%d%n",
                    preset,times[500]/1e6,times[950]/1e6,prepared,resolver.preparations-prepared,resolver.primitiveTests-tests,old);
        }
    }
}
