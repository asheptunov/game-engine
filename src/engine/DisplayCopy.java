package engine;

import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/** Bounded display conversion; all worker reads finish before returning to the lease owner. */
public final class DisplayCopy {
    private DisplayCopy() {}

    public static void interleaved(
            float[] source, int[] destination, int width, int height, float exposure) {
        if (destination.length != width * height || source.length != width * height * 3) {
            throw new IllegalArgumentException("Display destination size mismatch");
        }
        int strips =
                destination.length < 262144
                        ? 1
                        : Math.min(4, Runtime.getRuntime().availableProcessors());
        var pending = new ArrayList<Future<?>>(strips - 1);
        Throwable failure = null;
        try {
            for (int strip = 1; strip < strips; strip++) {
                int first = height * strip / strips;
                int last = height * (strip + 1) / strips;
                Runnable work =
                        () -> rows(source, destination, width, height, first, last, exposure);
                try {
                    pending.add(DirectRgbTracer.submitReconstruction(work));
                } catch (RejectedExecutionException busy) {
                    work.run();
                }
            }
            rows(source, destination, width, height, 0, height / strips, exposure);
        } catch (RuntimeException | Error problem) {
            failure = problem;
        }
        awaitWorkers(pending, failure);
    }

    // Even interruption or a failed strip must not leave workers reading a released lease.
    private static void awaitWorkers(ArrayList<Future<?>> pending, Throwable failure) {
        boolean interrupted = false;
        for (var task : pending) {
            boolean done = false;
            while (!done) {
                try {
                    task.get();
                    done = true;
                } catch (InterruptedException retry) {
                    interrupted = true;
                } catch (ExecutionException problem) {
                    failure = problem.getCause();
                    done = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw new IllegalStateException("Display conversion failed", failure);
        }
    }

    private static void rows(
            float[] source,
            int[] destination,
            int width,
            int height,
            int first,
            int last,
            float exposure) {
        for (int y = first; y < last; y++) {
            int input = y * width * 3;
            int output = (height - 1 - y) * width;
            for (int x = 0; x < width; x++, input += 3) {
                destination[output + x] =
                        DisplayMapping.pack(
                                source[input], source[input + 1], source[input + 2], exposure);
            }
        }
    }
}
