package profiling;

import rendering.Renderer;

/** Wraps the complete pipeline; the next frame closes the previous frame's idle interval. */
public record ProfiledFrame(FrameProfiler profiler, Renderer pipeline) implements Renderer {
    @Override public void render() {
        profiler.beginFrame();
        pipeline.render();
        profiler.endFrame();
    }
    public static Renderer stage(FrameProfiler profiler, FrameProfiler.Stage stage, Renderer renderer) {
        return () -> profiler.measure(stage, renderer::render);
    }
}
