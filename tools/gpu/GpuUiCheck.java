package game;

import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Exercise the real game panel without native windows, pointer capture or focus changes. */
public final class GpuUiCheck {
    private GpuUiCheck() {}
    public static void main(String[] args) throws Exception {
        var navigation=new GameNavigation();navigation.fixedResolution(960,600);
        try(var renderer=new GameRenderer(navigation,BlockWorld.landscape(),"gpu")) {
            long deadline=System.nanoTime()+30_000_000_000L;
            while(renderer.frame()==null && System.nanoTime()<deadline) {
                if(renderer.error()!=null) throw new AssertionError(renderer.error());
                Thread.sleep(5);
            }
            if(renderer.frame()==null) throw new AssertionError("No GPU game image");
            final GamePanel[] holder=new GamePanel[1];
            SwingUtilities.invokeAndWait(()->{
                var panel=new GamePanel(navigation,renderer);holder[0]=panel;panel.setSize(960,600);
                panel.dispatchEvent(new MouseEvent(panel,MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,30,175,1,false,MouseEvent.BUTTON1));
                if(renderer.adaptive()) throw new AssertionError("GPU adaptive button changed fixed grid");
                var before=renderer.lighting();
                panel.dispatchEvent(new MouseEvent(panel,MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,30,130,1,false,MouseEvent.BUTTON1));
                if(renderer.lighting().equals(before)) throw new AssertionError("Sun control did not change lighting");
                panel.renderingActive(false);panel.renderingActive(true);
            });
            Thread.sleep(300);
            var image=new BufferedImage(960,600,BufferedImage.TYPE_INT_RGB);
            SwingUtilities.invokeAndWait(()->{
                var graphics=image.createGraphics();
                try {holder[0].paint(graphics);} finally {graphics.dispose();holder[0].close();}
            });
            ImageIO.write(image,"png",Path.of("out/gpu/game-panel.png").toFile());
            System.out.println("PASS offscreen game panel, fixed-grid control, lighting and focus lifecycle");
            System.out.println(renderer.backendStatus());
        }
    }
}
