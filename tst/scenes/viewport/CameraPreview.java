package scenes.viewport;

import math.Vec3;
import scenes.viewport.objects.Rect;

/** Reproducible window-free previews of the live playground camera controls. */
public final class CameraPreview {
    public static void main(String[] args) throws Exception {
        String mode=args.length==0?"perspective":args[0];
        float focus=args.length>1?Float.parseFloat(args[1]):7;
        float aperture=args.length>2?Float.parseFloat(args[2]):.5f;
        var s=new ViewportState(new Rect(new Vec3(-.5f,-.5f,0),new Vec3(1,0,0),new Vec3(0,1,0)),400,250);
        ScenePresets.load(s,"playground");s.presentationAspect(1.6f);s.samplesPerFrame(8);s.seed(1);s.sampleTarget(128);
        s.camera(s.camera().withFocus(focus).withMode(mode));
        if(mode.equals("lens") || args.length>2)s.camera(s.camera().withAperture(aperture));
        if(mode.equals("orthographic") && args.length>3)s.camera(s.camera().withHeight(Float.parseFloat(args[3])));
        try(var t=new DirectRgbTracer(s)) {
            while(s.accumulatedSamples()<128)t.trace();var rgb=t.radianceBuffer();
            var image=new java.awt.image.BufferedImage(400,250,java.awt.image.BufferedImage.TYPE_INT_RGB);
            for(int y=0;y<250;y++)for(int x=0;x<400;x++) {
                int color=0;for(int c=0;c<3;c++)color=(color<<8)|Byte.toUnsignedInt(DisplayMapping.encode(rgb[c][249-y][x],1));
                image.setRGB(x,y,color);
            }
            String path="out/cli/camera-"+mode+"-focus-"+focus
                    +(mode.equals("orthographic") && s.camera().aperture()>0?"-aperture-"+aperture+"-height-"+s.camera().height():"")+".png";
            javax.imageio.ImageIO.write(image,"png",new java.io.File(path));
            System.out.println(s.camera().summary()+"; 128 spp; "+path);
        }
    }
}
