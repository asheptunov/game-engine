package engine;

import java.nio.file.*;

/** Fresh-JVM persistence/render probe invoked by ScenePersistenceTest. */
public final class ScenePersistenceProcess {
    public static void main(String[] args) throws Exception {
        var snapshot = SceneFiles.load(Path.of(args[0]));
        var camera = NodeId.parse(args[1]);
        Files.writeString(Path.of(args[2]), SceneTestSupport.renderHash(snapshot, camera));
    }
}
