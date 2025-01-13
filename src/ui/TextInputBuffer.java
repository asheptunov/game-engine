package ui;

import java.awt.event.KeyEvent;

public interface TextInputBuffer {
    void accept(KeyEvent keystroke);

    void set(String buffer);

    void clear();
}
