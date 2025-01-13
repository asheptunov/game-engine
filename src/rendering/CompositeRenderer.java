package rendering;

import logging.LogManager;
import logging.Logger;

import java.util.Collection;

public class CompositeRenderer implements Renderer {
    private static final Logger LOG = LogManager.instance().getThis();

    private final Collection<Renderer> delegates;

    public CompositeRenderer(Collection<Renderer> delegates) {
        this.delegates = delegates;
    }

    @Override
    public void render() {
        int i = 0;
        for (Renderer delegate : delegates) {
            LOG.debug("Renderer %s (%d of %d) rendering...", delegate, i, delegates.size());
            delegate.render();
            ++i;
        }
    }
}
