package ui;

import logging.LogManager;
import logging.Logger;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.KeyEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class FilteringInputBuffer implements TextInputBuffer {
    private static final Logger LOG = LogManager.instance().getThis();

    private final StringBuilder                             buf = new StringBuilder();
    private final String                                    name;
    private final Pattern                                   inputAcceptList;
    private final Pattern                                   charAcceptList;
    private final BiConsumer<FilteringInputBuffer, String>  rejectCallback;
    private final BiConsumer<FilteringInputBuffer, Matcher> acceptCallback;
    private final BiConsumer<FilteringInputBuffer, Matcher> entryCallback;
    private final Consumer<FilteringInputBuffer>            escapeCallback;

    public FilteringInputBuffer(String name,
                                Pattern inputAcceptList,
                                Pattern charAcceptList,
                                BiConsumer<FilteringInputBuffer, String> rejectCallback,
                                BiConsumer<FilteringInputBuffer, Matcher> acceptCallback,
                                BiConsumer<FilteringInputBuffer, Matcher> entryCallback,
                                Consumer<FilteringInputBuffer> escapeCallback) {
        this.name = name;
        this.inputAcceptList = inputAcceptList;
        this.charAcceptList = charAcceptList;
        this.rejectCallback = rejectCallback;
        this.acceptCallback = acceptCallback;
        this.entryCallback = entryCallback;
        this.escapeCallback = escapeCallback;
    }

    @Override
    public String get() {
        return buf.toString();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Override
    public Matcher matcher() {
        var matcher = inputAcceptList.matcher(buf.toString().strip());
        matcher.find();
        return matcher;
    }

    @Override
    public void accept(KeyEvent keystroke) {
        switch (keystroke.getID()) {
            case KeyEvent.KEY_TYPED: {
                fastAppend(keystroke.getKeyChar());
                entryCallback.accept(this, matcher());
            }
            case KeyEvent.KEY_PRESSED: {
                switch (keystroke.getModifiersEx()) {
                    case KeyEvent.CTRL_DOWN_MASK, KeyEvent.META_DOWN_MASK -> {
                        switch (keystroke.getKeyCode()) {
                            case KeyEvent.VK_C -> {
                                var str = buf.toString();
                                var sel = new StringSelection(str);
                                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
                                LOG.info("Copied %s buffer to clipboard: [%s]", name, str);
                            }
                            case KeyEvent.VK_X -> {
                                var str = buf.toString();
                                var sel = new StringSelection(str);
                                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
                                fastClear();
                                entryCallback.accept(this, matcher());
                                LOG.info("Cut %s buffer to clipboard: [%s]", name, str);
                            }
                            case KeyEvent.VK_V -> {
                                var contents = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(this);
                                if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                                    try (var r = DataFlavor.stringFlavor.getReaderForText(contents)) {
                                        try (var br = new BufferedReader(r)) {
                                            var str = br.lines().collect(Collectors.joining("\n"));
                                            if (inputAcceptList.matcher(str).find()) {
                                                set(str);
                                                entryCallback.accept(this, matcher());
                                                LOG.info("Pasted %s buffer from clipboard: [%s]", name, str);
                                            }
                                        }
                                    } catch (UnsupportedFlavorException | IOException ex) {
                                        LOG.error(ex, "Failed to paste %s input from clipboard", name);
                                    }
                                }
                            }
                            case KeyEvent.VK_BACK_SPACE -> {
                                fastClear();
                                entryCallback.accept(this, matcher());
                            }
                        }
                    }
                    case 0 -> {
                        switch (keystroke.getKeyCode()) {
                            case KeyEvent.VK_ESCAPE -> escapeCallback.accept(this);
                            case KeyEvent.VK_ENTER -> {
                                var matcher = matcher();
                                if (!matcher.hasMatch()) {
                                    LOG.warn("Input doesn't match %s regex %s: [%s]",
                                            name, inputAcceptList.pattern(), buf);
                                    rejectCallback.accept(this, buf.toString());
                                    return;
                                }
                                LOG.info("Input matches %s regex %s: [%s]", name, inputAcceptList.pattern(), buf);
                                acceptCallback.accept(this, matcher);
                            }
                            case KeyEvent.VK_BACK_SPACE -> {
                                fastDeleteLast();
                                entryCallback.accept(this, matcher());
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public void set(String buffer) {
        fastSet(buffer);
        LOG.info("Set %s buffer contents: [%s]", name, buffer);
    }

    @Override
    public void clear() {
        if (buf.isEmpty()) {
            LOG.debug("Nothing to clear in %s buffer", name);
        } else {
            LOG.info("Clearing %s buffer: [%s]", name, buf.toString());
            fastClear();
        }
    }

    private void fastSet(String buffer) {
        fastClear();
        fastAppend(buffer);
    }

    private void fastClear() {
        buf.setLength(0);
    }

    private void fastAppend(char c) {
        fastAppend("" + c);
    }

    private void fastAppend(String str) {
        if (charAcceptList.matcher(str).find()) {
            buf.append(str);
        }
    }

    private void fastDeleteLast() {
        if (!buf.isEmpty()) {
            buf.setLength(buf.length() - 1);
        }
    }
}
