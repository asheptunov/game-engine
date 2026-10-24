package gpu;

import engine.*;
import engine.lights.DirectionalLight;
import engine.objects.Rect;
import game.*;
import math.Vec3;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

/** Real-device checks; assertions throw on failure and never open a window. */
public final class GpuCheck {
    private GpuCheck() {}
    private static int checks;
    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }
    static WorldSnapshot landscape() {
        var blocks = BlockWorld.landscape().snapshot();
        var lighting = GameLighting.defaults();
        return new WorldSnapshot(0, blocks.instances(), java.util.List.of(), java.util.List.of(lighting.sun()), lighting.sky());
    }
    static float[] cpu(WorldSnapshot world, RenderView view, RenderSettings settings) throws Exception {
        try (var session = RenderEngine.openSession(world, view, settings)) {
            return waitImage(session, settings.sampleTarget());
        }
    }
    static float[] waitImage(RenderSession session, long samples) throws Exception {
        long deadline = System.nanoTime()+180_000_000_000L;
        while (System.nanoTime()<deadline) {
            session.request();
            try (var image = session.acquireImage()) {
                if (image != null && image.generation() == session.progress(null).requestedGeneration() && image.samples() >= samples) {
                    var output = new float[image.width()*image.height()*3];
                    for (int y=0;y<image.height();y++) for (int x=0;x<image.width();x++) for (int c=0;c<3;c++)
                        output[(y*image.width()+x)*3+c] = image.rawPixels().value(c,x,y);
                    return output;
                }
            }
            Thread.sleep(1);
        }
        throw new AssertionError("Timed out: " + session.status());
    }
    static double rmse(float[] a, float[] b) {
        double sum=0;
        for (int i=0;i<a.length;i++) { double d=a[i]-b[i]; sum+=d*d; }
        return Math.sqrt(sum/a.length);
    }
    static void image(float[] pixels, int width, int height, String name) throws Exception {
        var image = new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
            int i=(y*width+x)*3;
            int rgb=(Byte.toUnsignedInt(DisplayMapping.encode(pixels[i],1))<<16)
                    |(Byte.toUnsignedInt(DisplayMapping.encode(pixels[i+1],1))<<8)
                    |Byte.toUnsignedInt(DisplayMapping.encode(pixels[i+2],1));
            image.setRGB(x,height-1-y,rgb);
        }
        ImageIO.write(image,"png",Path.of("out/gpu/"+name+".png").toFile());
    }
    private static void numericGeometry() throws Exception {
        var white = new Material("white", new Vec3(.7f,.5f,.3f));
        var mesh = BoxGeometry.UNIT;
        var objects = java.util.List.of(
                new SceneInstance("first", mesh, new Transform(new Vec3(0,0,3),Vec3.ZERO,new Vec3(1,1,1)),white),
                new SceneInstance("touching", mesh, new Transform(new Vec3(2,0,3),Vec3.ZERO,new Vec3(1,1,1)),white),
                new SceneInstance("rotated", mesh, new Transform(new Vec3(-2,0,4),new Vec3(15,35,0),new Vec3(.65f,.8f,.4f)),white));
        var world = new WorldSnapshot(0,objects,java.util.List.of(),java.util.List.of(new DirectionalLight(new Vec3(-1,2,-3),new Vec3(1,1,1),1)),Sky.BLACK);
        var view = new RenderView(new Camera(new Vec3(0,0,-2),new Rect(new Vec3(-1,-1,0),new Vec3(2,0,0),new Vec3(0,2,0))),65,65);
        var settings = RenderSettings.defaults().withSampleTarget(1);
        try(var kernel = new GpuKernel()) {
            var actual=new float[65*65*3];
            kernel.trace(world,view,settings,0,actual);
            require(rmse(actual,cpu(world,view,settings))<0.002,"touching faces, center seams and transformed boxes match CPU");
            var recolored = new java.util.ArrayList<>(objects);
            recolored.set(0,new SceneInstance("first",mesh,objects.get(0).transform(),new Material("red",new Vec3(1,0,0))));
            var edited=new WorldSnapshot(1,recolored,java.util.List.of(),world.lights(),Sky.BLACK);
            var originalScene=kernel.scene;
            kernel.trace(edited,view,settings,0,actual);
            require(kernel.scene==originalScene,"material edit retains geometry acceleration");
            require(rmse(actual,cpu(edited,view,settings))<0.002,"material edit updates GPU radiance");
            var inside = new RenderView(new Camera(new Vec3(0,0,3),new Rect(new Vec3(-1,-1,4),new Vec3(2,0,0),new Vec3(0,2,0))),65,65);
            kernel.trace(world,inside,settings,0,actual);
            require(rmse(actual,cpu(world,inside,settings))<0.002,"inside opaque geometry matches CPU");
            var tied=new WorldSnapshot(2,java.util.List.of(objects.get(0),new SceneInstance("duplicate",mesh,objects.get(0).transform(),new Material("blue",new Vec3(0,0,1)))),java.util.List.of(),world.lights(),Sky.BLACK);
            kernel.trace(tied,view,settings,0,actual);
            require(rmse(actual,cpu(tied,view,settings))<0.002,"equal-distance instances retain original order");
        }
    }

    private static void batchesAndBounds(WorldSnapshot world, RenderView view) throws Exception {
        var settings=RenderSettings.defaults().withPathDepth(2).withSampleTarget(8);
        float[] single;
        var first=new GpuRenderSession(world,view,settings);
        try {single=waitImage(first,8);} finally {first.close();require(first.awaitClosed(30),"single-sample session retires");}
        var batch=new GpuRenderSession(world,view,settings.withSamplesPerBatch(8));
        try {
            require(java.util.Arrays.equals(single,waitImage(batch,8)),"one/eight-sample batches match exactly");
            var resized=new RenderView(view.camera(),128,80);
            batch.update(world,resized,settings.withRestartRevision(1));
            var pixels=waitImage(batch,8);
            require(pixels.length==128*80*3,"resize publishes only the requested grid");
            batch.update(world,resized,settings.withRestartRevision(2).withPaused(true));
            batch.request();Thread.sleep(30);
            require(!batch.progress(null).running(),"paused session submits no GPU work");
        } finally {batch.close();require(batch.awaitClosed(30),"resized session retires");}
        var material=new Material("tiny",new Vec3(.8f,.4f,.2f));
        var mesh=PolygonMesh.surface(java.util.List.of(
                new PolygonMesh.Vertex(0,new Vec3(-.0001f,-.0001f,0)),
                new PolygonMesh.Vertex(1,new Vec3(.0001f,-.0001f,0)),
                new PolygonMesh.Vertex(2,new Vec3(0,.0001f,0))),
                java.util.List.of(new PolygonMesh.Face(0,java.util.List.of(0L,1L,2L))),3,1);
        var tiny=new WorldSnapshot(0,java.util.List.of(new SceneInstance("tiny",mesh,Transform.IDENTITY,material)),java.util.List.of(),
                java.util.List.of(new DirectionalLight(new Vec3(0,0,-1),new Vec3(1,1,1),1)),Sky.BLACK);
        var camera=new Camera(new Vec3(0,0,-1),new Rect(new Vec3(-.0002f,-.0002f,0),new Vec3(.0004f,0,0),new Vec3(0,.0004f,0)));
        var tinyView=new RenderView(camera,65,65);
        var direct=RenderSettings.defaults();
        try(var kernel=new GpuKernel()) {
            var pixels=new float[65*65*3];kernel.trace(tiny,tinyView,direct,0,pixels);
            require(rmse(pixels,cpu(tiny,tinyView,direct))<0.002,"tiny triangle CPU/GPU comparison");
            var original=pixels.clone();
            kernel.trace(tiny,tinyView,direct,999_999_999L,pixels);
            require(java.util.Arrays.equals(original,pixels),"double means retain constant radiance at the billion-sample limit");
            var near=new RenderView(new Camera(new Vec3(0,0,-.00005f),camera.sensor()),65,65);
            kernel.trace(tiny,near,direct,0,pixels);
            require(pixels[(32*65+32)*3]==0,"near-surface ray cutoff matches CPU");
            require(rmse(pixels,cpu(tiny,near,direct))<0.002,"near-surface image matches CPU");
        }
    }

    private static void motionAndFailure(WorldSnapshot world, RenderView view) throws Exception {
        var settings=RenderSettings.defaults().withPathDepth(2).withSampleTarget(0);
        var moving=new GpuRenderSession(world,view,settings);
        try {
            waitImage(moving,1);
            var navigation=new GameNavigation();
            navigation.fixedResolution(view.width(),view.height());
            long last=0; int publications=0;
            for(int i=0;i<100;i++) {
                navigation.pose(new Vec3(i*.005f,2.4f,-6),0,-.14);
                moving.update(world,navigation.view(),settings);
                moving.request();
                Thread.sleep(3);
                try(var image=moving.acquireImage()) {
                    if(image!=null && image.publicationNanos()!=last) { last=image.publicationNanos();publications++; }
                }
            }
            require(publications>3,"sustained camera edits publish coherent previews");
            moving.update(world,navigation.view(),settings.withSampleTarget(4));
            var finalImage=waitImage(moving,4);
            try(var kernel=new GpuKernel()) {
                var expected=new float[finalImage.length];
                for(int sample=0;sample<4;sample++) kernel.trace(world,navigation.view(),settings,sample,expected);
                require(rmse(finalImage,expected)<0.000001,"motion settles to exact captured-camera accumulation");
            }
        } finally { moving.close(); require(moving.awaitClosed(30),"moving session retires"); }
        var deviceFailure=new GpuRenderSession(world,view,settings,()->new GpuKernel(()->{throw new IllegalStateException("injected device failure");}));
        try {
            deviceFailure.request();
            long deadline=System.nanoTime()+10_000_000_000L;
            while(!deviceFailure.status().contains("failed") && System.nanoTime()<deadline) Thread.sleep(1);
            require(deviceFailure.status().contains("injected device failure"),"device failure is retained as an explicit error");
        } finally {deviceFailure.close();require(deviceFailure.awaitClosed(30),"device failure retires native resources");}
        var failed=new GpuRenderSession(world,view,settings,()->{throw new IllegalStateException("injected initialization failure");});
        try {
            failed.request();
            long deadline=System.nanoTime()+2_000_000_000L;
            while(!failed.status().contains("failed") && System.nanoTime()<deadline) Thread.sleep(1);
            boolean rejected=false;
            try { failed.request(); } catch(IllegalStateException error) { rejected=error.getMessage().contains("-Backend cpu"); }
            require(rejected,"initialization failure reports reason and explicit CPU recovery");
        } finally { failed.close(); require(failed.awaitClosed(30),"failed session retires"); }
    }

    private static void depthOfField(WorldSnapshot world, RenderView view) throws Exception {
        var pinhole=view.camera();
        var settings=RenderSettings.defaults().withPathDepth(0).withSampleTarget(32);
        try(var kernel=new GpuKernel()) {
            float[] actual=new float[view.width()*view.height()*3];
            kernel.trace(world,view,settings,0,actual);
            var original=actual.clone();
            var inactive=new RenderView(pinhole.withFocus(11).withAperture(0),view.width(),view.height());
            kernel.trace(world,inactive,settings,0,actual);
            require(java.util.Arrays.equals(original,actual),"zero aperture preserves exact pinhole pixels with inactive focus edits");
            for(int depth:new int[]{0,2}) {
                settings=settings.withPathDepth(depth);
                for(float focus:new float[]{3,9}) {
                    var lens=new RenderView(pinhole.withFocus(focus).withAperture(.25f),view.width(),view.height());
                    for(int sample=0;sample<32;sample++) kernel.trace(world,lens,settings,sample,actual);
                    var reference=cpu(world,lens,settings);
                    double error=rmse(actual,reference);
                    System.out.println("DOF depth="+depth+" focus="+focus+" RMSE="+error);
                    require(error<.003,"finite aperture matches CPU at depth "+depth+" and focus "+focus);
                    require(rmse(original,actual)>.001,"finite aperture changes the image");
                    if(depth==2 && focus==9) {
                        image(actual,view.width(),view.height(),"dof-gpu");
                        image(reference,view.width(),view.height(),"dof-cpu");
                    }
                }
            }
        }
        var lens=new RenderView(pinhole.withFocus(8).withAperture(.2f),view.width(),view.height());
        settings=settings.withPathDepth(2).withSampleTarget(8);
        float[] single;
        var first=new GpuRenderSession(world,lens,settings);
        try {single=waitImage(first,8);} finally {first.close();require(first.awaitClosed(30),"lens single-sample session retires");}
        var batch=new GpuRenderSession(world,lens,settings.withSamplesPerBatch(8));
        try {
            require(java.util.Arrays.equals(single,waitImage(batch,8)),"lens one/eight-sample batches agree exactly");
            try(var held=batch.acquireImage()) {
                float preserved=held.rawPixels().value(0,20,20);
                var edited=new RenderView(pinhole.withFocus(3).withAperture(.4f),view.width(),view.height());
                batch.update(world,edited,settings);
                var changed=waitImage(batch,8);
                require(rmse(changed,cpu(world,edited,settings))<.003,"focus and aperture edits reset accumulation to the captured optics");
                require(held.rawPixels().value(0,20,20)==preserved,"lens edits preserve acquired image leases");
                batch.update(world,lens,settings);
                require(java.util.Arrays.equals(single,waitImage(batch,8)),"returning to lens settings reproduces the original samples");
            }
            batch.update(world,view,settings);
            require(rmse(waitImage(batch,8),cpu(world,view,settings))<.003,"closing aperture restores CPU-equivalent pinhole output");
        } finally {batch.close();require(batch.awaitClosed(30),"lens batch session retires");}
    }

    public static void main(String[] args) throws Exception {
        var world=landscape();
        var navigation=new GameNavigation();
        navigation.fixedResolution(96,64);
        var view=navigation.view();
        var settings=RenderSettings.defaults().withPathDepth(0).withSampleTarget(1);
        try (var kernel=new GpuKernel()) {
            System.out.println("DEVICE " + kernel.gpu.name);
            var actual=new float[96*64*3];
            kernel.trace(world,view,settings,0,actual);
            var expected=cpu(world,view,settings);
            image(actual,96,64,"direct-gpu"); image(expected,96,64,"direct-cpu");
            double error=rmse(actual,expected);
            System.out.println("Direct RMSE="+error+" GPU ms="+kernel.executeNanos/1e6);
            require(error<0.002,"depth-zero CPU/GPU radiance RMSE below 0.002");
            var copy=actual.clone(); kernel.trace(world,view,settings,0,actual);
            require(java.util.Arrays.equals(copy,actual),"repeat dispatch exactly reproduces sample zero");
            settings=settings.withPathDepth(2).withSampleTarget(64);
            for(int sample=0;sample<64;sample++) kernel.trace(world,view,settings,sample,actual);
            expected=cpu(world,view,settings);
            image(actual,96,64,"landscape-gpu"); image(expected,96,64,"landscape-cpu");
            error=rmse(actual,expected);
            System.out.println("64 spp RMSE="+error+" GPU ms="+kernel.executeNanos/1e6);
            require(error<0.04,"64-spp landscape CPU/GPU radiance RMSE below 0.04");
        }
        depthOfField(world,view);
        numericGeometry();
        settings=settings.withSampleTarget(2);
        var session=new GpuRenderSession(world,view,settings);
        try {
            waitImage(session,2);
            try(var held=session.acquireImage()) {
                float original=held.rawPixels().value(0,20,20);
                session.update(world,view,settings.withRestartRevision(1));
                waitImage(session,2);
                require(held.rawPixels().value(0,20,20)==original,"held image unchanged across restart");
                var pixels=held.rawPixels();
                held.close();
                boolean rejected=false;
                try { pixels.value(0,20,20); } catch(IllegalStateException failure) { rejected=true; }
                require(rejected,"pixels reject access after lease close");
            }
            session.suspend();
            session.update(world,view,settings.withRestartRevision(2));
            waitImage(session,2);
            require(session.progress(null).samples()==2,"resume converges to requested target");
            boolean rejected=false;
            try { session.update(world,view,settings.withPathDepth(3)); } catch(IllegalArgumentException failure) { rejected=true; }
            require(rejected,"unsupported depth rejected");
        } finally { session.close(); }
        batchesAndBounds(world, view);
        motionAndFailure(world, view);
        require(session.awaitClosed(30),"GPU owner and native resources retire after close");
        System.out.println("GPU checks passed: " + checks);
    }
}
