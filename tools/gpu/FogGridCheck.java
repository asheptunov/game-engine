package gpu;

import engine.*;
import game.*;
import math.Vec3;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Real-device quality and lifecycle checks for shared world-space fog lighting. */
public final class FogGridCheck {
    private static WorldSnapshot world(double hour, boolean closed) {
        var clock=new DayNightCycle(); clock.hour(hour);
        var light=clock.atmosphericLighting(Atmosphere.Parameters.clearAir());
        return new WorldSnapshot(0,RuinsMist.scenery(AdventureWorld.create().instances(),true,closed,0),
                List.of(),List.of(light.sun()),light.sky().withFog(List.of(RuinsMist.region(0),RuinsMist.region(1))));
    }
    public static void main(String[] args) throws Exception {
        if(args.length>0) { capture(args[0]); return; }
        var navigation=new GameNavigation(); navigation.fixedResolution(197,131);
        navigation.pose(new Vec3(10.879049f,2.7514815f,22.884184f),1.251,.079);
        var view=navigation.view();
        var settings=RenderSettings.defaults().withSampleTarget(1);
        try(var kernel=new GpuKernel()) {
            for(int depth:new int[]{0,2}) for(double hour:new double[]{7,7.08,12,17.8}) {
                var scene=world(hour,false);
                compare(kernel,scene,view,settings.withPathDepth(depth),"depth="+depth+" hour="+hour);
            }
            compare(kernel,world(7,true),view,settings,"closed sunlight gap");
            navigation.pose(new Vec3(11,2.8f,23),1.255,.08);
            compare(kernel,world(7,false),navigation.view(),settings,"camera movement");
            var scene=world(7,false);
            var empty=new WorldSnapshot(1,scene.instances(),List.of(),scene.lights(),scene.sky().withFog(List.of()));
            exactFallback(kernel,empty,view,settings,"no fog");
            exactFallback(kernel,world(6,false),view,settings,"horizon");
            var dense=new WorldSnapshot(1,scene.instances(),List.of(),scene.lights(),scene.sky().withFog(List.of(
                    new FogRegion(new Vec3(20,3,28),new Vec3(13,5,12),0,new Vec3(.2f,.2f,.2f),new Vec3(.1f,.1f,.1f),3,1.5f,.65f,.45f,2,.2f))));
            exactFallback(kernel,dense,view,settings,"dense fog");
            compare(kernel,scene,new RenderView(view.camera().withAperture(.1f),197,131),settings,"aperture");
            var hazy=air(scene,Atmosphere.Parameters.hazyMorning());
            compare(kernel,hazy,view,settings.withPathDepth(2),"hazy air");
            var colored=scene.sky().withFog(List.of(new FogRegion(new Vec3(20,3,28),new Vec3(13,5,12),25,
                    new Vec3(.025f,.035f,.045f),new Vec3(.003f,.002f,.001f),1,1.5f,.65f,.45f,2,.2f)));
            compare(kernel,new WorldSnapshot(2,scene.instances(),List.of(),scene.lights(),colored),view,settings.withPathDepth(2),"rotated colored fog");
            var legacy=new WorldSnapshot(3,scene.instances(),List.of(),List.of(new engine.lights.DirectionalLight(new Vec3(0,1,0),new Vec3(1,.8f,.7f),.4f)),scene.sky());
            compare(kernel,legacy,view,settings,"additional directional light");
            var p=Atmosphere.Parameters.clearAir();
            var large=new Atmosphere.Parameters(1000,p.groundY(),p.radiusKm(),p.heightKm(),p.molecularHeightKm(),p.aerosolHeightKm(),p.molecularScattering(),p.aerosolScattering(),p.absorption(),p.anisotropy());
            exactFallback(kernel,air(scene,large),view,settings,"large world-to-air mapping");
            session(scene,view,settings);
        }
        System.out.println("PASS fog quality, odd dimensions, current lighting, exact fallback and session toggles");
    }
    private static WorldSnapshot air(WorldSnapshot world,Atmosphere.Parameters parameters) {
        var original=world.sky().atmosphere();
        var sky=world.sky().withAtmosphere(new Atmosphere(parameters,original.solarDirection(),original.solarIrradiance()));
        return new WorldSnapshot(1,world.instances(),List.of(),world.lights(),sky);
    }
    private static void compare(GpuKernel kernel,WorldSnapshot world,RenderView view,RenderSettings settings,String label) {
        var full=new float[view.width()*view.height()*3];var actual=new float[full.length];
        kernel.trace(world,view,settings,0,full);
        long referenceBytes=kernel.gpu.allocatedBytes;
        for(int divisor:new int[]{1,2,4}) {
            kernel.trace(world,view,settings.withFogDownsample(divisor).withFogLightingGrid(true),0,actual);
            double maximum=0; int outside=0;
            for(int i=0;i<actual.length;i++) {
                if(!Float.isFinite(actual[i]) || actual[i]<0) throw new AssertionError("Invalid radiance");
                double difference=Math.abs(actual[i]-full[i]); maximum=Math.max(maximum,difference);
                if(difference>.015+.05*Math.abs(full[i])) outside++;
            }
            double error=GpuCheck.rmse(full,actual);
            System.out.println(label+" divisor="+divisor+" rmse="+error+" max="+maximum+" outside="+outside+" reused="+kernel.fogReusedPixels);
            if(error>.004 || outside>0) throw new AssertionError("Lighting grid exceeds radiance quality budget");
            if(!kernel.lightingGrid) throw new AssertionError("Lighting grid not exercised");
        }
        kernel.trace(world,view,settings,0,actual);
        if(!Arrays.equals(full,actual)) throw new AssertionError("Full mode did not restore exact reference");
        if(kernel.gpu.allocatedBytes!=referenceBytes) throw new AssertionError("Quality buffers not released on Full");
    }
    private static void exactFallback(GpuKernel kernel,WorldSnapshot world,RenderView view,RenderSettings settings,String label) {
        var full=new float[view.width()*view.height()*3];var actual=new float[full.length];
        kernel.trace(world,view,settings,0,full);
        kernel.trace(world,view,settings.withFogLightingGrid(true),0,actual);
        if(kernel.lightingGrid || kernel.fogDownsample!=1 || kernel.fogPrimaryNanos!=0 || !Arrays.equals(full,actual))
            throw new AssertionError(label+" must retain exact integration");
    }
    private static void session(WorldSnapshot world,RenderView view,RenderSettings settings) throws Exception {
        try(var session=new GpuRenderSession(world,view,settings)) {
            GpuCheck.waitImage(session,1);
            try(var held=session.acquireImage()) {
                long generation=held.generation();float value=held.rawPixels().value(0,20,20);
                session.update(world,view,settings.withFogLightingGrid(true));
                GpuCheck.waitImage(session,1);
                try(var next=session.acquireImage()) {
                    if(next.generation()==generation || next.samples()!=1) throw new AssertionError("Quality edit failed to restart");
                }
                if(held.rawPixels().value(0,20,20)!=value) throw new AssertionError("Quality edit overwrote lease");
                session.update(world,new RenderView(view.camera(),201,133),settings.withFogDownsample(2).withFogLightingGrid(true));
                GpuCheck.waitImage(session,1);
                session.update(world,view,settings);GpuCheck.waitImage(session,1);
            }
        }
    }
    private static void capture(String path) throws Exception {
        var capture=GameCapture.load(Path.of(path));var shown=capture.manifest().displayed();
        var world=capture.world(shown.world());var view=shown.view();
        var full=new float[view.width()*view.height()*3];var actual=new float[full.length];
        try(var kernel=new GpuKernel()) {
            for(int mode:new int[]{0,1,2,4}) {
                var settings=shown.settings().withFogDownsample(Math.max(1,mode)).withFogLightingGrid(mode!=0);
                for(int sample=0;sample<16;sample++) kernel.trace(world,view,settings,sample,actual);
                if(mode==0) System.arraycopy(actual,0,full,0,actual.length);
                GpuCheck.image(actual,view.width(),view.height(),"fog-lighting-grid-"+mode);
                double maximum=0;int outside=0;
                for(int i=0;i<actual.length;i++) {
                    double error=Math.abs(full[i]-actual[i]);maximum=Math.max(maximum,error);
                    if(error>.015+.05*Math.abs(full[i])) outside++;
                }
                System.out.println("capture mode="+mode+" linearRMSE="+GpuCheck.rmse(full,actual)+" maximum="+maximum+" outside="+outside+" buildMs="+kernel.lightingBuildNanos/1e6);
                if(GpuCheck.rmse(full,actual)>.004 || outside>0) throw new AssertionError("Capture radiance quality budget exceeded");
            }
        }
    }
}
