package profiling;

/** Paint-thread-owned, bounded two-second rate. Cached redraws never count as fresh images. */
public final class FreshPaintRate {
    private static final long WINDOW_NANOS = 2_000_000_000L;
    private final long[] times = new long[4096];
    private long latestPublication = Long.MIN_VALUE;
    private int start;
    private int count;

    public void painted(long publication, long now) {
        expire(now);
        if (publication <= latestPublication) {
            return;
        }
        latestPublication = publication;
        if (count == times.length) {
            start = (start + 1) % times.length;
            count--;
        }
        times[(start + count) % times.length] = now;
        count++;
    }

    /** Count over a fixed window; the first two seconds include startup, and idle rates decay. */
    public double perSecond(long now) {
        expire(now);
        return count / 2.;
    }

    private void expire(long now) {
        while (count > 0 && now - times[start] >= WINDOW_NANOS) {
            start = (start + 1) % times.length;
            count--;
        }
    }
}
