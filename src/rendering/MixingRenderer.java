package rendering;

import logging.LogManager;
import logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class MixingRenderer implements Renderer {
    private static final Logger LOG = LogManager.instance().getThis();

    private final List<Channel<?>> channels;

    private MixingRenderer(List<Channel<?>> channels) {
        this.channels = channels;
    }

    private record Channel<T extends Renderer>(Predicate<T> state, T target) {
        boolean isEnabled() {
            return state.test(target);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<Channel<?>> channels = new ArrayList<>();

        public <T extends Renderer> Builder withChannel(Predicate<T> state, T target) {
            channels.add(new Channel<>(state, target));
            return this;
        }

        public MixingRenderer build() {
            return new MixingRenderer(List.copyOf(channels));
        }
    }

    @Override
    public void render(Context context) {
        for (Channel<?> channel : channels) {
            if (channel.isEnabled()) {
                channel.target().render(context);
            }
        }
    }
}
