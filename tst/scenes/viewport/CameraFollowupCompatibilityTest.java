package engine;

import scenes.viewport.*;
import harness.SuiteRunner;
import harness.Test;
import static harness.Assertions.*;

/** C3 fixtures captured before decoupling projection and aperture. */
public class CameraFollowupCompatibilityTest {
    static long digest(String mode) {
        var s=TemporalReconstructionTest.plane();ScenePresets.load(s,"playground");
        s.resolution(64,67);s.workers(1);s.seed(812);s.pathDepth(3);s.sampleTarget(3);
        s.camera(s.camera().withFocus(7).withMode(mode));
        if(mode.equals("lens"))s.camera(s.camera().withAperture(.2f));
        try(var t=new DirectRgbTracer(s)) {
            do {t.trace();}while(s.accumulatedSamples()<3);
            long hash=0xcbf29ce484222325L;
            for(var channel:t.radianceBuffer())for(var row:channel)for(float v:row)
                hash=(hash^Integer.toUnsignedLong(Float.floatToIntBits(v)))*0x100000001b3L;
            return hash;
        }
    }
    @Test void deliveredOptics() {assertEquals(EXPECTED,new long[]{digest("lens"),digest("orthographic")});}
    private static final long[] EXPECTED={2544894998670422241L,-5477149993092030197L};
    public static void main(String[] args) {
        if(args.length>0)System.out.println(digest("lens")+", "+digest("orthographic"));
        else SuiteRunner.runThis();
    }
}
