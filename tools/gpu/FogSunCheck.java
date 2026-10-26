package gpu;

import engine.*;
import game.*;
import math.Vec3;
import java.util.List;

/** Real-device reference comparison and cache invalidation, using a continuous session owner. */
public final class FogSunCheck {
    public static void main(String[] args) throws Exception { check(0); check(2); }
    private static void check(int depth) throws Exception {
        var navigation=new GameNavigation(); navigation.fixedResolution(96,64);
        navigation.pose(new Vec3(10.879049f,2.7514815f,22.884184f),1.251,.079);
        var settings=RenderSettings.defaults().withPathDepth(depth).withSampleTarget(1);
        var geometry=RuinsMist.scenery(AdventureWorld.create().instances(),true,false,0);
        var output=new float[96*64*3]; var reference=new float[output.length];
        try(var cache=new GpuKernel(); var direct=new GpuKernel()) {
            for(double hour:new double[]{6,7,7.01,12,17.8,18.1}) {
                var clock=new DayNightCycle();clock.hour(hour);
                var lighting=clock.atmosphericLighting(Atmosphere.Parameters.clearAir());
                var world=new WorldSnapshot(0,geometry,List.of(),List.of(lighting.sun()),lighting.sky().withFog(List.of(RuinsMist.region(0),RuinsMist.region(1))));
                cache.trace(world,navigation.view(),settings,0,output);
                long builds=cache.fogBuilds;
                cache.trace(world,navigation.view(),settings,0,output);
                if(cache.fogBuilds!=builds || cache.fogBuildNanos!=0) throw new AssertionError("Unchanged cache rebuilt");
                var shifted=new RenderView(navigation.view().camera(),128,64);
                var shiftedPixels=new float[128*64*3];
                cache.trace(world,shifted,settings,0,shiftedPixels);
                if(cache.fogBuilds!=builds) throw new AssertionError("Resolution rebuilt lighting table");
                cache.trace(world,navigation.view(),settings,0,output);
                System.setProperty("engine.fogSunReference","true");
                try { direct.trace(world,navigation.view(),settings,0,reference); }
                finally { System.clearProperty("engine.fogSunReference"); }
                double maximum=0;
                for(int i=0;i<output.length;i++) {
                    double error=Math.abs(output[i]-reference[i]); maximum=Math.max(maximum,error);
                    if(error>.015+.05*Math.abs(reference[i])) throw new AssertionError("Radiance error="+error+" at hour="+hour);
                }
                var cpu=GpuCheck.cpu(world,navigation.view(),settings);
                double parity=GpuCheck.rmse(cpu,output);
                if(parity>.004) throw new AssertionError("CPU/GPU mismatch="+parity);
                System.out.println("PASS depth="+depth+" hour="+hour+" referenceRMSE="+GpuCheck.rmse(reference,output)+" max="+maximum+" cpuGpuRMSE="+parity+" builds="+cache.fogBuilds);
                var empty=new WorldSnapshot(1,geometry,List.of(),world.lights(),world.sky().withFog(List.of()));
                cache.trace(empty,navigation.view(),settings,0,output);
                cache.trace(world,navigation.view(),settings,0,output);
                if(cache.fogBuilds!=builds+(FogSunTable.layout(world.sky())==null?0:1)) throw new AssertionError("Fog disable/enable resurrected old cache");
            }
        }
    }
}
