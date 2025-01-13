package ui;

import java.awt.event.KeyEvent;

public interface TextInputBuffer {
    String get();

    void accept(KeyEvent keystroke);

    void set(String buffer);

    void clear();
}
