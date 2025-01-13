package v1;

import v1.model.scene.Camera;
import v1.model.scene.Point;
import v1.model.scene.Scene;
import v1.model.scene.Vector;

import javax.swing.JFrame;
import java.time.Clock;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

public class Main {
    public static final int WIDTH = 400;
    public static final int HEIGHT = 300;
    public static final Scene SCENE = new Scene(
            new Camera(
                    new Vector(-1, 1, 0),
                    new Vector(1, 1, 0),
                    new Vector(1, -1, 0)
            ),
            new Point(
                    new Vector(0, 0, 1),
                    false, true)
//            new Trapezoid(
//                    new Vector(0, 0, 1),
//                    new Vector(1, 0, 1),
//                    new Vector(1, 1, 1),
//                    new Vector(0, 1, 1),
//                    true, false)
    );

    public static void main(String[] args) {
        var frame = new JFrame();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(WIDTH, HEIGHT);
        frame.setResizable(false);
        frame.setVisible(true);
        frame.createBufferStrategy(2);
        var rasterizer = new SceneRasterizer(100, WIDTH, HEIGHT, new Random());
        var renderer = new AwtRenderer(frame.getBufferStrategy(), WIDTH, HEIGHT);
        // ticker writes to raster, drawer reads from raster;
        // these actions are independent to decouple tick rate from frame rate
        var raster = new AtomicReference<>(new int[WIDTH * HEIGHT]);
        var ticker = new TimedExecutor(1, Clock.systemUTC(), () -> raster.set(rasterizer.rasterize(SCENE)));
        var drawer = new TimedExecutor(1, Clock.systemUTC(), () -> renderer.render(raster.get()));
        new Thread(ticker::execute).start();
        new Thread(drawer::execute).start();
    }
}
