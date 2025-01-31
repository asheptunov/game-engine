package scenes.textureeditor.console;

import logging.LogManager;
import logging.Logger;
import misc.spliterators.ReversedSpliterator;
import scenes.textureeditor.History;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Stream;

public class LoggingHistory<T> implements History<T> {
    private static final Logger LOG = LogManager.instance().getThis();

    private final History<T>   history;
    private final Logger.Level level;

    public LoggingHistory(Logger.Level level, History<T> history) {
        this.history = history;
        this.level = level;
        log();
    }

    @Override
    public void record(T current) {
        history.record(current);
        log();
    }

    @Override
    public Optional<T> goBack() {
        Optional<T> res = history.goBack();
        log();
        return res;
    }

    @Override
    public Optional<T> goForward() {
        Optional<T> res = history.goForward();
        log();
        return res;
    }

    @Override
    public Collection<T> getPast() {
        return history.getPast();
    }

    @Override
    public Collection<T> getFuture() {
        return history.getFuture();
    }

    @Override
    public int size() {
        return history.size();
    }

    @Override
    public int maxSize() {
        return history.maxSize();
    }

    private void log() {
        /*
                                  history.past  -> newest (cur) to oldest (actual oldest)
                          reverse(history.past) -> oldest (actual oldest) to newest (cur)
                 history.future                 -> newest (actual newest) to oldest (cur+1)
         reverse(history.future)                -> oldest (cur+1) to newest (actual newest)
                 history.future + history.past  -> newest (actual newest) to oldest (actual oldest)
         reverse(history.future + history.past) -> oldest (actual oldest) to newest (actual newest)
        */
        var future = history.getFuture();
        var past = history.getPast();
        var all = ReversedSpliterator.reverse(Stream.concat(future.stream(), past.stream())).stream().toList();
        int cur = past.size() - 1;
        int newest = all.size() - 1;
        int i = 0;
        var elements = new ArrayList<>();
        for (T t : all) {
            var traits = new ArrayList<>();
            traits.add(i);
            if (i == 0) {
                traits.add("oldest");
            }
            if (i == cur) {
                traits.add("cur");
            }
            if (i == newest) {
                traits.add("newest");
            }
            elements.add(traits + " " + t);
            ++i;
        }
        assert elements.size() == history.size();
        LOG.log(level, "History (size=%d, maxSize=%d): %s", elements.size(), history.maxSize(), elements);
    }
}
