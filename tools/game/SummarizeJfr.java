import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import jdk.jfr.consumer.RecordingFile;

/** Read a game JFR recording without loading all events into memory. */
public class SummarizeJfr {
    public static void main(String[] args) throws Exception {
        Map<String, Long> counts = new HashMap<>();
        long samples = 0;
        try (var recording = new RecordingFile(Path.of(args[0]))) {
            while (recording.hasMoreEvents()) {
                var event = recording.readEvent();
                String type = event.getEventType().getName();
                if (type.equals("jdk.GCPhasePause")) {
                    counts.merge("gcPauseNanos,total", event.getDuration().toNanos(), Long::sum);
                }
                if (type.equals("jdk.ObjectAllocationSample")) {
                    counts.merge("allocationWeight," + event.getClass("objectClass").getName(),
                            event.getLong("weight"), Long::sum);
                }
                if (!type.equals("jdk.ExecutionSample") || event.getStackTrace() == null) continue;
                samples++;
                var frames = event.getStackTrace().getFrames();
                var seen = new HashSet<String>();
                String category = "other";
                for (int i = 0; i < frames.size(); i++) {
                    var method = frames.get(i).getMethod();
                    String name = method.getType().getName() + "." + method.getName();
                    if (i == 0) {
                        counts.merge("leaf," + name, 1L, Long::sum);
                        counts.merge("leafLine," + name + ":" + frames.get(i).getLineNumber(),
                                1L, Long::sum);
                    }
                    if (seen.add(name)) counts.merge("inclusive," + name, 1L, Long::sum);
                    if (category.equals("other")) {
                        if (name.contains("DirectRgbTracer$Worker.nearest")) category = "nearest query";
                        else if (name.contains("DirectRgbTracer$Worker.occluded")) category = "shadow query";
                        else if (name.equals("game.GameRenderer.copyImage")) category = "game image conversion";
                    }
                }
                counts.merge("category," + category, 1L, Long::sum);
                var thread = event.getThread("sampledThread");
                counts.merge("thread," + thread.getJavaName(), 1L, Long::sum);
            }
        }
        System.out.println("kind,name,value");
        System.out.println("samples,total," + samples);
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(entry -> System.out.println(entry.getKey() + "," + entry.getValue()));
    }
}
