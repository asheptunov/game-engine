package engine;

import scenes.viewport.*;
import harness.SuiteRunner;
import harness.Test;
import math.Vec3;
import engine.objects.Rect;
import static harness.Assertions.*;

/** Raw float-bit fixtures captured before camera extraction, including skewed/off-center geometry. */
public class CameraCompatibilityTest {
    static long digest(String preset, int depth, boolean skew) {
        var s=new ViewportState(new Rect(new Vec3(-.5f,-.5f,0),new Vec3(1,0,0),new Vec3(0,1,0)),64,67);
        ScenePresets.load(s,preset);s.pathDepth(depth);s.workers(1);s.sampleTarget(3);s.seed(812);
        if(skew) s.cameraSensor(new Rect(new Vec3(-.4f,-.6f,0),new Vec3(1,.1f,.02f),new Vec3(.15f,1,.03f)));
        try(var t=new DirectRgbTracer(s)) {
            while(s.accumulatedSamples()<3)t.trace();
            long hash=0xcbf29ce484222325L;
            for(var channel:t.radianceBuffer())for(var row:channel)for(float v:row)
                hash=(hash ^ Integer.toUnsignedLong(Float.floatToIntBits(v)))*0x100000001b3L;
            return hash;
        }
    }
    @Test void pinholeFixtures() {
        assertEquals(EXPECTED, new long[]{digest("triangle",0,false),digest("bounce-room",3,false),
                digest("glass-inside",8,false),digest("volume-room",12,false),digest("bounce-room",3,true)});
    }
    private static final long[] EXPECTED={-5924632961343853205L,-8259721888742259155L,6641113345330945053L,-4155009229478006010L,2447037077638854822L};
    public static void main(String[] args) {
        if(args.length>0)System.out.println(java.util.Arrays.toString(new long[]{digest("triangle",0,false),
                digest("bounce-room",3,false),digest("glass-inside",8,false),digest("volume-room",12,false),digest("bounce-room",3,true)}));
        else SuiteRunner.runThis();
    }
}
