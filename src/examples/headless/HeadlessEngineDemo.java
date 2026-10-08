package examples.headless;

import engine.*;
import engine.lights.PointLight;
import engine.objects.Rect;
import engine.objects.Sphere;
import math.Vec3;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Window-free consumer using only public engine values and services. */
public final class HeadlessEngineDemo {
    private HeadlessEngineDemo(){}

    public static void main(String[] args) throws Exception {
        Path output=Path.of(args.length==0?"out/engine/headless-engine.png":args[0]);
        var camera=new Camera(new Vec3(0,0,-1),new Rect(new Vec3(-.8f,-.5f,0),
                new Vec3(1.6f,0,0),new Vec3(0,1,0)));
        var blue=Material.srgb("blue",0x3b82f6);
        var floor=Material.srgb("floor",0xb8c0cc);
        var world=WorldSnapshot.of(List.of(
                new SceneInstance("sphere",new AnalyticSphere(new Vec3(0,0,3),1),Transform.IDENTITY,blue),
                new SceneInstance("floor",PolygonMesh.parallelogram(new Vec3(-4,-1,1),new Vec3(0,0,8),new Vec3(8,0,0)),
                        Transform.IDENTITY,floor)),
                List.of(new PointLight(new Vec3(-2,3,-1),new Vec3(1,1,1),80)));
        var view=new RenderView(camera,160,100);
        var settings=RenderSettings.defaults().withPathDepth(2).withSeed(812)
                .withSamplesPerBatch(2).withSampleTarget(4);
        try(var session=RenderEngine.openSession(world,view,settings);
            var image=await(session,4)) {
            Files.createDirectories(output.toAbsolutePath().getParent());
            write(image.rawPixels(),output);
            System.out.println("Wrote "+output+" at "+image.samples()+" spp");
        }
    }

    private static RenderImage await(RenderSession session,long samples) throws Exception {
        long deadline=System.nanoTime()+15_000_000_000L;
        while(System.nanoTime()<deadline) {
            session.request();
            var image=session.acquireImage();
            if(image!=null) {
                var progress=session.progress(image);
                if(image.generation()==progress.requestedGeneration() && image.samples()>=samples)return image;
                image.close();
            }
            Thread.sleep(1);
        }
        throw new IllegalStateException("Timed out waiting for render");
    }

    private static void write(RgbPixels rgb,Path output) throws Exception {
        int height=rgb.height(),width=rgb.width();
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            int r=Byte.toUnsignedInt(DisplayMapping.encode(rgb.value(0,x,y),1));
            int g=Byte.toUnsignedInt(DisplayMapping.encode(rgb.value(1,x,y),1));
            int b=Byte.toUnsignedInt(DisplayMapping.encode(rgb.value(2,x,y),1));
            image.setRGB(x,height-1-y,(r<<16)|(g<<8)|b);
        }
        if(!ImageIO.write(image,"png",output.toFile()))throw new IllegalStateException("PNG writer unavailable");
    }
}
