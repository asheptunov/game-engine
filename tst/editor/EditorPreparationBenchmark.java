package editor;

import engine.PolygonMesh;
import engine.SceneDocument;
import engine.SceneSnapshot;
import engine.SpatialQuery;

import math.Vec3;

import java.lang.management.ManagementFactory;
import java.util.Locale;

/** Isolate scene validation and the two views' query preparation, without rendering or a window. */
public final class EditorPreparationBenchmark {
    private static volatile Object observed;
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) {
        int repeats = args.length == 0 ? 3 : Integer.parseInt(args[0]);
        System.out.printf(
                "java=%s os=%s processors=%d sphereDetail=32 warmup=20 samples=60%n",
                System.getProperty("java.version"),
                System.getProperty("os.name"),
                Runtime.getRuntime().availableProcessors());
        for (int repeat = 1; repeat <= repeats; repeat++) {
            snapshotCost(repeat);
            queryCost(repeat, "Teal box");
            queryCost(repeat, "Terracotta sphere");
        }
    }

    private static SceneDocument scene() {
        var document = StarterScene.create();
        var sphere =
                document.snapshot().nodes().stream()
                        .filter(node -> node.label().equals("Terracotta sphere"))
                        .findFirst()
                        .orElseThrow();
        document.transact(
                edit -> edit.approximateGeometryAsMesh(sphere.geometry().geometryId(), 32));
        return document;
    }

    private static void snapshotCost(int repeat) {
        var source = scene().snapshot();
        double millis = 0;
        double bytes = 0;
        for (int sample = -20; sample < 60; sample++) {
            long beforeBytes = allocatedBytes();
            long start = System.nanoTime();
            // SceneDocument currently performs candidate and publication construction separately.
            observed =
                    SceneSnapshot.content(
                            source.nodes(), source.geometryAssets(), source.materialAssets());
            observed =
                    SceneSnapshot.content(
                            source.nodes(), source.geometryAssets(), source.materialAssets());
            long elapsed = System.nanoTime() - start;
            long allocated = allocatedBytes() - beforeBytes;
            if (sample >= 0) {
                millis += elapsed / 1e6;
                bytes += allocated;
            }
        }
        report(repeat, "two-snapshot-constructions", millis, bytes);
    }

    private static void queryCost(int repeat, String editedLabel) {
        var document = scene();
        var node =
                document.snapshot().nodes().stream()
                        .filter(n -> n.label().equals(editedLabel))
                        .findFirst()
                        .orElseThrow();
        var geometryId = node.geometry().geometryId();
        var baseline = (PolygonMesh) document.snapshot().requireGeometry(geometryId).geometry();
        double millis = 0;
        double bytes = 0;
        for (int sample = -20; sample < 60; sample++) {
            var moved =
                    baseline.translateVertex(
                            baseline.editableVertices().getFirst().id(),
                            new Vec3(sample % 2 == 0 ? .0001f : -.0001f, 0, 0));
            var snapshot = document.transact(edit -> edit.replaceGeometry(geometryId, moved));
            long beforeBytes = allocatedBytes();
            long start = System.nanoTime();
            observed = SpatialQuery.prepare(snapshot);
            observed = SpatialQuery.prepare(snapshot);
            long elapsed = System.nanoTime() - start;
            long allocated = allocatedBytes() - beforeBytes;
            if (sample >= 0) {
                millis += elapsed / 1e6;
                bytes += allocated;
            }
        }
        report(repeat, "two-query-preparations-edit=" + editedLabel, millis, bytes);
    }

    private static long allocatedBytes() {
        return THREADS.isThreadAllocatedMemorySupported()
                ? THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId())
                : 0;
    }

    private static void report(int repeat, String workload, double millis, double bytes) {
        System.out.printf(
                Locale.ROOT,
                "repeat=%d workload=%s meanMs=%.3f allocatedKiB=%.3f%n",
                repeat,
                workload,
                millis / 60,
                bytes / 60 / 1024);
    }
}
