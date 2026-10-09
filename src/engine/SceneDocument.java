package engine;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Single-writer atomic scene publisher. Create/edit it on an application update thread; immutable
 * snapshots may be read concurrently by render and query workers.
 */
public final class SceneDocument {
    private final Thread owner = Thread.currentThread();
    private volatile SceneSnapshot snapshot;
    private long epoch;
    private long transportEpoch;
    private boolean activeEdit;

    public SceneDocument() {
        this(SceneSnapshot.empty());
    }

    public SceneDocument(SceneSnapshot initial) {
        snapshot = Objects.requireNonNull(initial, "initial");
        epoch = initial.revision();
        transportEpoch = initial.transportRevision();
    }

    public SceneSnapshot snapshot() {
        return snapshot;
    }

    public SceneSnapshot transact(Consumer<SceneEdit> operation) {
        owner();
        Objects.requireNonNull(operation, "operation");
        begin();
        try {
            var base = snapshot;
            var edit = new SceneEdit(base);
            operation.accept(edit);
            var candidate = edit.candidate();
            if (base.sameContent(candidate)) return base;
            long next = nextEpoch();
            long transport =
                    base.sameTransport(candidate) ? base.transportRevision() : nextTransportEpoch();
            var published = edit.publish(next, transport);
            published.inheritPreparedQueries(base);
            snapshot = published;
            return published;
        } finally {
            activeEdit = false;
        }
    }

    /**
     * Replace all content after callers have fully parsed/validated it. Runtime revisions are
     * fresh.
     */
    public SceneSnapshot replace(SceneSnapshot content) {
        owner();
        Objects.requireNonNull(content, "content");
        begin();
        try {
            var base = snapshot;
            long next = nextEpoch();
            var draft =
                    new SceneSnapshot(
                            base.revision(),
                            base.transportRevision(),
                            content.nodes(),
                            content.geometryAssets(),
                            content.materialAssets());
            long transport =
                    base.sameTransport(draft) ? base.transportRevision() : nextTransportEpoch();
            // Treat every loaded/restored asset as newly published in this document.
            var gs =
                    content.geometryAssets().stream()
                            .map(a -> new GeometryAsset(a.id(), a.label(), next, a.geometry()))
                            .toList();
            var ms =
                    content.materialAssets().stream()
                            .map(a -> new MaterialAsset(a.id(), a.label(), next, a.material()))
                            .toList();
            var restored = new SceneSnapshot(next, transport, content.nodes(), gs, ms);
            restored.inheritPreparedQueries(base);
            snapshot = restored;
            return snapshot;
        } finally {
            activeEdit = false;
        }
    }

    private void begin() {
        if (activeEdit)
            throw new IllegalStateException("Nested SceneDocument edits are not allowed");
        activeEdit = true;
    }

    private long nextEpoch() {
        if (epoch == Long.MAX_VALUE) throw new IllegalStateException("Scene revision exhausted");
        return ++epoch;
    }

    private long nextTransportEpoch() {
        if (transportEpoch == Long.MAX_VALUE)
            throw new IllegalStateException("Transport revision exhausted");
        return ++transportEpoch;
    }

    private void owner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("SceneDocument edits belong to its creating thread");
    }
}
