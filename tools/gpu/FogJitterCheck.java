package gpu;

import engine.*;
import game.*;
import math.Vec3;
import java.util.Arrays;
import java.util.List;

/** Device checks for reproducible stratified fog samples independent of surface sampling. */
public final class FogJitterCheck {
    public static void main(String[] args) throws Exception {
        var clock=new DayNightCycle();clock.hour(6.396);
        var light=clock.atmosphericLighting(Atmosphere.Parameters.clearAir());
        var world=new WorldSnapshot(0,RuinsMist.scenery(AdventureWorld.create().instances(),true,false,0),
                List.of(),List.of(light.sun()),light.sky().withFog(List.of(RuinsMist.region(0),RuinsMist.region(1))));
        var navigation=new GameNavigation();navigation.fixedResolution(197,131);
        navigation.pose(new Vec3(10.879049f,2.7514815f,22.884184f),1.251,.079);
        var view=navigation.view();var settings=RenderSettings.defaults().withPathDepth(0).withFogLightingGrid(true);
        var fixed=new float[197*131*3];var first=new float[fixed.length];var next=new float[fixed.length];
        try(var kernel=new GpuKernel()) {
            for(int divisor:new int[]{1,2,4}) {
                var mode=settings.withFogDownsample(divisor);
                kernel.trace(world,view,mode,0,fixed);
                kernel.trace(world,view,mode.withFogJitter(true),0,first);
                if(Arrays.equals(fixed,first)) throw new AssertionError("Jitter has no effect");
                kernel.trace(world,view,mode.withFogJitter(true),0,next);
                if(!Arrays.equals(first,next)) throw new AssertionError("Replay is not deterministic");
                kernel.trace(world,view,mode.withFogJitter(true),1,next);
                if(Arrays.equals(first,next)) throw new AssertionError("Samples do not vary");
                for(float value:next) if(!Float.isFinite(value)||value<0) throw new AssertionError("Invalid radiance");
                kernel.trace(world,view,mode.withFogJitter(true).withSeed(2),0,next);
                if(Arrays.equals(first,next)) throw new AssertionError("Seed ignored");
                kernel.trace(world,view,mode,0,next);
                if(!Arrays.equals(fixed,next)) throw new AssertionError("Off failed to restore reference");
            }
            var clear=new WorldSnapshot(1,world.instances(),List.of(),world.lights(),world.sky().withFog(List.of()));
            kernel.trace(clear,view,settings.withPathDepth(2),0,fixed);
            kernel.trace(clear,view,settings.withPathDepth(2).withFogJitter(true),0,next);
            if(!Arrays.equals(fixed,next)) throw new AssertionError("Jitter changed non-fog transport");
        }
        try(var session=new GpuRenderSession(world,view,settings)) {
            GpuCheck.waitImage(session,1);
            try(var held=session.acquireImage()) {
                long generation=held.generation();float value=held.rawPixels().value(0,20,20);
                session.update(world,view,settings.withFogJitter(true));GpuCheck.waitImage(session,1);
                try(var fresh=session.acquireImage()) {
                    if(fresh.generation()==generation||fresh.samples()!=1) throw new AssertionError("Jitter did not restart accumulation");
                }
                if(value!=held.rawPixels().value(0,20,20)) throw new AssertionError("Jitter overwrote held image");
            }
        }
        System.out.println("PASS fog jitter replay, sample/seed variation, full/half/quarter, off restoration, no-fog streams and leases");
    }
}
