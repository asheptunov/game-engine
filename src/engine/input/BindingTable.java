package engine.input;

import java.util.Map;

/** Serializable chord-to-action table implemented by typed binding dispatchers. */
public interface BindingTable {
    void bindParsed(String chord,String actionId);
    Map<String,String> serialized();
    BindingTable validate(String owner);
}
