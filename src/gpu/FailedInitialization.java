package gpu;

/** Failed construction must expose its cause even if native resource retirement stalls. */
final class FailedInitialization {
    private FailedInitialization() {}

    static void retire(Throwable original, Runnable cleanup) {
        var thread =
                new Thread(
                        () -> {
                            try {
                                cleanup.run();
                            } catch (Throwable secondary) {
                                original.addSuppressed(secondary);
                            }
                        },
                        "gpu-failed-initialization-retire");
        thread.setDaemon(true);
        thread.start();
    }
}
