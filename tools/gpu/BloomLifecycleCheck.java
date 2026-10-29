package game;
import engine.Bloom;
import java.util.Arrays;
/** Exercise presentation-only edits after transport has reached its sample target. */
public final class BloomLifecycleCheck {
    public static void main(String[] args) throws Exception {
        for(String backend:new String[]{"cpu","gpu"}) for(boolean resident:new boolean[]{false,true}) {
            if(resident && backend.equals("cpu")) continue;
            var nav=new GameNavigation(); nav.fixedResolution(96,64);
            try(var renderer=new GameRenderer(nav,BlockWorld.gallery(),backend)) {
                renderer.configure(s->s.withSampleTarget(1));
                if(resident) renderer.devicePresentation();
                var initial=await(renderer,0,Bloom.OFF);
                int[] before=pixels(initial); long generation=initial.generation(); long samples=initial.samples();
                renderer.configure(s->s.withBloom(Bloom.OPTICAL));
                var optical=await(renderer,initial.publicationNanos(),Bloom.OPTICAL);
                if(Arrays.equals(before,pixels(optical))) throw new AssertionError("Bloom did not change image");
                if(optical.generation()!=generation || optical.samples()!=samples) throw new AssertionError("Bloom reset transport");
                renderer.configure(s->s.withBloom(Bloom.OFF));
                var off=await(renderer,optical.publicationNanos(),Bloom.OFF);
                if(!Arrays.equals(before,pixels(off))) throw new AssertionError("OFF did not restore original");
                if(!Arrays.equals(before,pixels(initial))) throw new AssertionError("Retained frame mutated");
                initial.release(); optical.release(); off.release();
                System.out.println("PASS converged bloom toggles, retained frame, raw generation: "+backend+" resident="+resident);
            }
        }
    }
    private static GameRenderer.Frame await(GameRenderer renderer,long previous,Bloom mode) throws Exception {
        long deadline=System.nanoTime()+20_000_000_000L;
        while(System.nanoTime()<deadline) {
            if(renderer.error()!=null) throw new AssertionError(renderer.error());
            var frame=renderer.acquireFrame();
            if(frame!=null) {
                if(frame.publicationNanos()!=previous && frame.context()!=null && frame.context().settings().bloom()==mode) return frame;
                frame.release();
            }
            Thread.sleep(5);
        }
        throw new AssertionError("No frame after bloom toggle "+mode);
    }
    private static int[] pixels(GameRenderer.Frame frame) {
        return frame.image().getRGB(0,0,frame.width(),frame.height(),null,0,frame.width());
    }
}
