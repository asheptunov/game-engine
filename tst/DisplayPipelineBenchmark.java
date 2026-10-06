import scenes.viewport.DisplayConverter;
import engine.DisplayMapping;
import engine.RgbPixels;
import engine.Resampler;
import rendering.PixelRaster;
import rendering.RgbPacking;
import profiling.RuntimeMetrics;
import java.util.Arrays;

/** Isolates P3 display work on fixed radiance. No window, tracing, overlays, or scheduler. */
public class DisplayPipelineBenchmark {
    public static void main(String[] args) {
        System.out.println("JDK="+System.getProperty("java.runtime.version")+"; CPU="+System.getenv("PROCESSOR_IDENTIFIER")
                +"; display=1440x900; warmup=50; frames=100; fixed RGB ramp, exposure=1; no AWT presentation");
        for(int[] size:new int[][]{{1440,900},{360,225},{400,400},{1600,1000}}) {
            int w=size[0],h=size[1]; var rgb=new float[3][h][w];
            for(int c=0;c<3;c++) for(int y=0;y<h;y++) for(int x=0;x<w;x++) rgb[c][y][x]=(x+y+c)%127*.125f;
            var raster=new PixelRaster(1440,900); var converter=new DisplayConverter(1440,900);
            var leased=new ArrayPixels(rgb);
            for(String mode:new String[]{"reference","fused","leased","cached"}) {
                converter.convert(rgb,1);
                var times=new long[100]; long allocated=0;
                for(int i=-50;i<100;i++) {
                    long bytes=RuntimeMetrics.allocatedBytes(),start=System.nanoTime();
                    switch(mode) {
                        case "reference" -> reference(rgb,raster);
                        case "fused" -> { converter.convert(rgb,1); converter.paint(raster); }
                        case "leased" -> { converter.convert(leased,1); converter.paint(raster); }
                        case "cached" -> converter.paint(raster);
                    }
                    long elapsed=System.nanoTime()-start,delta=RuntimeMetrics.delta(bytes,RuntimeMetrics.allocatedBytes());
                    if(i>=0) { times[i]=elapsed; allocated+=delta; }
                }
                Arrays.sort(times);
                System.out.printf("sensor=%dx%d %s median/p95=%.3f/%.3f ms; bytes/frame=%d; checksum=%d%n",
                        w,h,mode,times[49]/1e6,times[94]/1e6,allocated/100,Arrays.hashCode(raster.red()));
            }
        }
        var raster=new PixelRaster(1440,900); var pixels=new int[1440*900];
        Arrays.fill(raster.alpha(),(byte)255); Arrays.fill(raster.red(),(byte)193);
        for(boolean optimized:new boolean[]{false,true}) {
            var times=new long[100];
            for(int i=-50;i<100;i++) {
                long start=System.nanoTime();
                if(optimized) RgbPacking.copy(raster,pixels); else packReference(raster,pixels);
                if(i>=0) times[i]=System.nanoTime()-start;
            }
            Arrays.sort(times);
            System.out.printf("opaque AWT packing %s median/p95=%.3f/%.3f ms; checksum=%d%n",
                    optimized?"P3":"reference",times[49]/1e6,times[94]/1e6,Arrays.hashCode(pixels));
        }
    }
    private record ArrayPixels(float[][][] rgb) implements RgbPixels {
        @Override public int width(){return rgb[0][0].length;}
        @Override public int height(){return rgb[0].length;}
        @Override public float value(int channel,int x,int y){return rgb[channel][y][x];}
        @Override public void copyTo(float[][][] destination) {
            for(int c=0;c<3;c++)for(int y=0;y<height();y++)
                System.arraycopy(rgb[c][y],0,destination[c][y],0,width());
        }
    }
    private static void reference(float[][][] rgb,PixelRaster raster) {
        int h=raster.height(),w=raster.width();
        var filtered=new float[][][]{Resampler.resample(rgb[0],h,w).buf(),Resampler.resample(rgb[1],h,w).buf(),
                Resampler.resample(rgb[2],h,w).buf()};
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            int i=y*w+x,sy=h-1-y; raster.alpha()[i]=(byte)255;
            raster.red()[i]=DisplayMapping.encode(filtered[0][sy][x],1);
            raster.green()[i]=DisplayMapping.encode(filtered[1][sy][x],1);
            raster.blue()[i]=DisplayMapping.encode(filtered[2][sy][x],1);
        }
    }
    private static void packReference(PixelRaster raster,int[] pixels) {
        for(int i=0;i<pixels.length;i++) {
            double a=(raster.alpha()[i]&255)/255.;
            pixels[i]=((int)(a*(raster.red()[i]&255))<<16)|((int)(a*(raster.green()[i]&255))<<8)
                    |(int)(a*(raster.blue()[i]&255));
        }
    }
}
