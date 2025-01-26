package scenes.textureeditor.model;

import rendering.Raster;

import java.nio.file.Path;

public class EditorState {
    private Mode        mode;
    private Path        dirName;
    private Path        filename;
    private Raster      texture;
    private Selection   selection;
    private Coordinates boxStart;

    public EditorState(Mode mode,
                       Path dirName,
                       Path filename,
                       Raster texture,
                       Selection selection,
                       Coordinates boxStart) {
        this.mode = mode;
        this.dirName = dirName;
        this.filename = filename;
        this.texture = texture;
        this.selection = selection;
        this.boxStart = boxStart;
    }

    public Mode mode() {
        return mode;
    }

    public void mode(Mode mode) {
        this.mode = mode;
    }

    public Path dirName() {
        return dirName;
    }

    public void dirName(Path dirName) {
        this.dirName = dirName;
    }

    public Path filename() {
        return filename;
    }

    public void filename(Path filename) {
        this.filename = filename;
    }

    public Raster texture() {
        return texture;
    }

    public void texture(Raster texture) {
        this.texture = texture;
    }

    public Selection selection() {
        return selection;
    }

    public void selection(Selection selection) {
        this.selection = selection;
    }

    public Coordinates boxStart() {
        return boxStart;
    }

    public void boxStart(Coordinates boxStart) {
        this.boxStart = boxStart;
    }
}
