package game;

import engine.RenderFailure;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Standalone application's fatal policy. No engine code exits the process. */
final class GameCrashHandler {
    static final long CLEANUP_MILLIS = 2000;
    static final long HARD_DEADLINE_MILLIS = 5000;
    private final AtomicBoolean failed = new AtomicBoolean();
    private final Path directory;
    private final String runId =
            Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID();
    private final String startup;
    private final GameDiagnostics diagnostics;
    private volatile Runnable cleanup = () -> {};
    private volatile Runnable releasePointer = () -> {};

    GameCrashHandler(
            String scene,
            String backend,
            int width,
            int height,
            Path directory,
            GameDiagnostics diagnostics) {
        this.directory = directory.toAbsolutePath();
        this.diagnostics = diagnostics;
        startup =
                "run="
                        + runId
                        + "\npid="
                        + ProcessHandle.current().pid()
                        + "\nscene="
                        + scene
                        + "\nbackend="
                        + backend
                        + "\nlaunch=./game.ps1 -Scene "
                        + scene
                        + " -Backend "
                        + backend
                        + " -Width "
                        + width
                        + " -Height "
                        + height
                        + "\nbuild="
                        + buildIdentity()
                        + "\nos="
                        + System.getProperty("os.name")
                        + " "
                        + System.getProperty("os.version")
                        + " "
                        + System.getProperty("os.arch")
                        + "\njvm="
                        + System.getProperty("java.vm.name")
                        + " "
                        + System.getProperty("java.runtime.version")
                        + "\njvmFatalLog="
                        + System.getProperty(
                                        "game.jvmErrorFile",
                                        "unconfigured; JVM default hs_err_pid<PID>.log")
                                .replace("%p", Long.toString(ProcessHandle.current().pid()))
                        + "\nlauncherLogs="
                        + Path.of("out/game/game-stdout.log").toAbsolutePath()
                        + ", "
                        + Path.of("out/game/game-error.log").toAbsolutePath()
                        + "\n"
                        + "sceneCapture=not exported; built-in scene requires matching build,"
                        + " camera and settings below; arbitrary in-memory content cannot be"
                        + " reproduced from this report alone\n";
        // Retained even if native/JVM corruption prevents the fatal handler from executing.
        write(
                this.directory.resolve(runId + "-run.txt"),
                startup + "initialization=before renderer\n");
        diagnostics.event("run context saved; renderer startup pending");
    }

    void cleanup(Runnable value) {
        cleanup = value;
    }

    void releasePointer(Runnable value) {
        releasePointer = value;
    }

    void fatal(RenderFailure failure) {
        if (!failed.compareAndSet(false, true)) {
            return;
        }
        // Start the watchdog first. Reporting, driver teardown and JVM shutdown hooks may hang.
        daemon(
                "game-fatal-deadline",
                () -> {
                    try {
                        Thread.sleep(HARD_DEADLINE_MILLIS);
                    } catch (InterruptedException ignored) {
                    }
                    Runtime.getRuntime().halt(1);
                });
        daemon("game-fatal-report", () -> finish(failure));
    }

    private void finish(RenderFailure failure) {
        var reportPath = directory.resolve(runId + "-crash.txt");
        var stack = new StringWriter();
        failure.printStackTrace(new PrintWriter(stack));
        var state = diagnostics.latest();
        var report =
                startup
                        + "\nfailureTimestamp="
                        + failure.timestamp()
                        + "\nphase="
                        + failure.phase()
                        + "\nbackendContext="
                        + failure.context()
                        + "\ncleanupDeadlineMillis="
                        + CLEANUP_MILLIS
                        + "\nhardExitDeadlineMillis="
                        + HARD_DEADLINE_MILLIS
                        + "\nheapUsedBytes="
                        + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                        + "\nheapMaxBytes="
                        + Runtime.getRuntime().maxMemory()
                        + "\nnativeMemory=unavailable except cached backend counters\n"
                        + describe(state)
                        + "\nrecentEvents:\n"
                        + diagnostics.events()
                        + "\noriginalFailure:\n"
                        + stack;
        write(reportPath, report);
        System.err.println("Fatal renderer report: " + reportPath);
        var done = new CountDownLatch(2);
        var secondary = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        daemon("game-fatal-pointer", () -> clean(releasePointer, done, secondary));
        daemon("game-fatal-cleanup", () -> clean(cleanup, done, secondary));
        boolean completed = false;
        try {
            completed = done.await(CLEANUP_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        stack.getBuffer().setLength(0);
        failure.printStackTrace(new PrintWriter(stack));
        String ending =
                "\ncleanupCompleted="
                        + completed
                        + "\nsecondaryCleanupErrors="
                        + secondary
                        + "\n"
                        + "cleanupCompletionMeaning=host cleanup actions returned; native"
                        + " retirement may still be queued\n"
                        + "failureAfterCleanup:\n"
                        + stack;
        try {
            Files.writeString(reportPath, ending, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            System.err.print(ending);
        }
        System.err.flush();
        Runtime.getRuntime().halt(1);
    }

    private static String describe(GameDiagnostics.State state) {
        if (state == null) {
            return "requested/completed/camera/settings/device=unavailable (before renderer"
                    + " snapshot)\n";
        }
        var world = state.world();
        var frame = state.completed();
        return "lastRequested="
                + state.view()
                + "\nsettings="
                + state.settings()
                + "\nprogress="
                + (state.progress() == null
                        ? "unavailable (before first request)"
                        : state.progress())
                + "\nlastCompleted="
                + (frame == null
                        ? "unavailable (before first frame)"
                        : "generation="
                                + frame.generation()
                                + ", samples="
                                + frame.samples()
                                + ", width="
                                + frame.image().getWidth()
                                + ", height="
                                + frame.image().getHeight()
                                + ", camera="
                                + frame.camera()
                                + ", capturedNanos="
                                + frame.capturedNanos()
                                + ", publicationNanos="
                                + frame.publicationNanos()
                                + ", metrics="
                                + frame.metrics())
                + "\nexposure="
                + state.exposure()
                + "\nlighting="
                + state.lighting()
                + "\nclockHour="
                + state.hour()
                + ", dayDurationSeconds="
                + state.dayDuration()
                + ", dayCycle="
                + state.dayCycle()
                + ", active="
                + state.active()
                + "\nworldRevision="
                + world.revision()
                + ", instanceCount="
                + world.instances().size()
                + ", legacyCount="
                + world.legacyObjects().size()
                + "\nsceneFingerprint=semantic32:"
                + Integer.toHexString(world.instances().hashCode())
                + " (diagnostic hash, not a content export or collision-proof identity)"
                + "\nlights="
                + world.lights()
                + "\nsky="
                + world.sky()
                + "\nlastBackendStatus="
                + state.backendStatus()
                + "\n";
    }

    private void write(Path path, String text) {
        try {
            Files.createDirectories(directory);
            Files.writeString(path, text, java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (Exception failure) {
            System.err.println("Cannot write diagnostic path " + path + ": " + failure);
            System.err.print(text);
        }
    }

    private static void clean(
            Runnable action, CountDownLatch done, java.util.Queue<String> secondary) {
        try {
            action.run();
        } catch (Throwable error) {
            secondary.add(error.toString());
        } finally {
            done.countDown();
        }
    }

    static String buildIdentity() {
        try (var stream = GameCrashHandler.class.getResourceAsStream("/game-build.properties")) {
            if (stream == null) {
                return "unavailable (build did not embed identity)";
            }
            var properties = new Properties();
            properties.load(stream);
            return properties.toString();
        } catch (Exception error) {
            return "unavailable: " + error;
        }
    }

    private static void daemon(String name, Runnable action) {
        var thread = new Thread(action, name);
        thread.setDaemon(true);
        thread.start();
    }
}
