package scenes.textureeditor.model;

import logging.LogManager;
import logging.Logger;
import rendering.Raster;
import scenes.textureeditor.CircularBufferHistoryImpl;
import scenes.textureeditor.History;
import scenes.textureeditor.console.LoggingHistory;

import java.io.File;
import java.util.Optional;

public class EditorState {
    private static final Logger LOG = LogManager.instance().getThis();

    private       Mode            mode;
    private       File            workingDir;
    private       File            workingFile;
    private       Raster          texture;
    private final History<Raster> textureHistory;
    private       Selection       selection;
    private       Coordinates     boxStart;
    private       boolean         isToolCardShown;

    public EditorState(Mode mode,
                       File workingDir,
                       Raster texture,
                       int textureHistorySize) {
        this.mode = mode;
        this.workingDir = workingDir;
        this.texture = texture;
        this.textureHistory = new LoggingHistory<>(Logger.Level.TRACE,
                new CircularBufferHistoryImpl<>(textureHistorySize, texture.clone()));
        this.isToolCardShown = false;
    }

    public Mode mode() {
        return mode;
    }

    public void mode(Mode mode) {
        var oldMode = this.mode;
        if (oldMode != mode) {
            this.mode = mode;
            LOG.info("Switched mode from %s to %s", oldMode, mode);
        }
    }

    public File workingDir() {
        return workingDir;
    }

    public void workingDir(File workingDir) {
        if (workingDir == null) {
            throw new IllegalArgumentException();
        }
        this.workingDir = workingDir;
    }

    public Optional<File> workingFile() {
        return Optional.ofNullable(workingFile);
    }

    public void workingFile(File workingFile) {
        if (workingFile == null) {
            throw new IllegalArgumentException();
        }
        this.workingFile = workingFile;
    }

    public void clearWorkingFile() {
        this.workingFile = null;
    }

    public Raster texture() {
        return texture;
    }

    public void texture(Raster texture) {
        if (texture == null) {
            throw new IllegalArgumentException();
        }
        this.texture = texture;
    }

    public void snapshot() {
        textureHistory.record(texture.clone());
    }

    public Optional<Raster> rollback() {
        return textureHistory.goBack();
    }

    public Optional<Raster> rollforward() {
        return textureHistory.goForward();
    }

    public Optional<Selection> selection() {
        return Optional.ofNullable(selection);
    }

    public void selection(Selection selection) {
        if (selection == null) {
            throw new IllegalArgumentException();
        }
        this.selection = selection;
    }

    public void clearSelection() {
        this.selection = null;
    }

    public Optional<Coordinates> boxStart() {
        return Optional.ofNullable(boxStart);
    }

    public void boxStart(Coordinates boxStart) {
        if (boxStart == null) {
            throw new IllegalArgumentException();
        }
        this.boxStart = boxStart;
    }

    public void clearBoxStart() {
        this.boxStart = null;
    }

    public boolean isToolCardShown() {
        return isToolCardShown;
    }

    public void toggleToolCard() {
        isToolCardShown = !isToolCardShown;
    }

    public void showToolCard() {
        isToolCardShown = false;
    }

    public void hideToolCard() {
        isToolCardShown = false;
    }
}
