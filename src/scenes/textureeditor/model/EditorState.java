package scenes.textureeditor.model;

import rendering.Raster;

import java.nio.file.Path;
import java.util.Optional;

public class EditorState {
    private Mode        mode;
    private Path        dirName;
    private Path        filename;
    private Raster      texture;
    private Selection   selection;
    private Coordinates boxStart;

    public EditorState(Mode mode,
                       Path dirName,
                       Raster texture) {
        this.mode = mode;
        this.dirName = dirName;
        this.texture = texture;
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
        if (dirName == null) {
            throw new IllegalArgumentException();
        }
        this.dirName = dirName;
    }

    public Optional<Path> filename() {
        return Optional.ofNullable(filename);
    }

    public void filename(Path filename) {
        if (filename == null) {
            throw new IllegalArgumentException();
        }
        this.filename = filename;
    }

    public void clearFilename() {
        this.filename = null;
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
}
