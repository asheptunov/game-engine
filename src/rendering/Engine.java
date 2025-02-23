package rendering;

import timing.PeriodicExecutor;

import java.time.Clock;

public class Engine {
    private final Renderer.Context renderContext;
    private final Renderer         renderer;
    private final Display          display;
    private final PeriodicExecutor tickExecutor;
    private final PeriodicExecutor viewExecutor;

    public Engine(int width, int height) {
        var raster = new PixelRaster(width, height);
        var clock = Clock.systemDefaultZone();
        var font = FsFontLoader.builder()
                .repository(new FileSystemRasterRepository(clock, ChainRasterSerializer.of(
                        ArgbSerializer.INSTANCE,
                        RgbSerializer.INSTANCE)))
                .clock(clock)
                .fontPath("assets/fonts/test")
                .fontDimensions(16)
                .filter(RasterFilter.antiAlias())
                .build()
                .load();
        var context = new Context(raster, new RasterPainter(raster), new RasterPrinter(raster, font));
        this.tickExecutor = new PeriodicExecutor(20, clock, this::tick);
        this.viewExecutor = new PeriodicExecutor(144, clock, this::view);
    }

    public void start() throws InterruptedException {
        viewExecutor.execute();
    }

    private void tick() {}

    private void view() {
        renderer.render(context);
        display.display(raster);
    }

    private record Context(Raster raster, Painter painter, Printer printer) implements Renderer.Context {}
}
