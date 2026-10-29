package gpu;
import game.*;
import engine.input.*;
import java.lang.management.ManagementFactory;
/** Window-free resident-frame ownership and stationary/moving resource probe. */
public final class ResidentWalkProbe {
 public static void main(String[] args) throws Exception {
  var nav = new GameNavigation();
  if(args.length > 0) nav.fixedResolution(Integer.parseInt(args[0]), Integer.parseInt(args[0])*5/8);
  try(var renderer = new GameRenderer(nav, AdventureWorld.create(), "gpu")) {
   renderer.devicePresentation(); renderer.dayCycle(true); renderer.enableShooter(); nav.speed(5); nav.walking(true); nav.capture();
   long deadline = System.nanoTime()+90_000_000_000L;
   while(renderer.frame()==null && System.nanoTime()<deadline) Thread.sleep(20);
   var displayed=GameRenderer.class.getDeclaredMethod("displayed", GameRenderer.Frame.class); displayed.setAccessible(true); long lastPublication=0; int frames=0; long start=System.nanoTime(), report=start;
   int phase=-1;
   while(System.nanoTime()-start<65_000_000_000L) {
    double elapsed=(System.nanoTime()-start)/1e9;
    int next=(int)(elapsed/10);
    if(next!=phase) {
     nav.key(new KeyInput(KeyCode.W,false,Modifiers.NONE)); nav.key(new KeyInput(KeyCode.S,false,Modifiers.NONE));
     if(next==1 || next==3 || next==5) nav.key(KeyInput.press(KeyCode.W,Modifiers.NONE));
     if(next==2 || next==4) nav.key(KeyInput.press(KeyCode.S,Modifiers.NONE));
     phase=next;
    }
    var frame=renderer.acquireFrame();
    if(frame!=null) {
     try {
      displayed.invoke(renderer,frame);
      if(frame.publicationNanos()!=lastPublication) {lastPublication=frame.publicationNanos(); frames++;}
      if(System.nanoTime()-report>1_000_000_000L) {
       var pixels=(GpuDevicePixels)frame.devicePixels();
       String memory=pixels.session.onOwner(()->"deviceMiB="+pixels.gpu.allocatedBytes/1048576+" resources="+pixels.gpu.resources.size());
       System.out.printf("t=%.1f phase=%d fps=%d heapMiB=%d %s eye=%s %s%n",elapsed,phase,frames,ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()/1048576,memory,nav.snapshot().position(),renderer.backendStatus());
       frames=0;report=System.nanoTime();
      }
     } finally {frame.release();}
    }
    Thread.sleep(10);
   }
   nav.release();
  }
 }
}
