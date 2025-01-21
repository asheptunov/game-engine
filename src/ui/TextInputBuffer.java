package ui;

import java.awt.event.KeyEvent;
import java.util.regex.Matcher;

public interface TextInputBuffer {
    String get();

    Matcher matcher();

    void accept(KeyEvent keystroke);

    void set(String buffer);

    void clear();
}
