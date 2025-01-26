package scenes.textureeditor.model;

import java.util.Set;

import static scenes.textureeditor.model.Selection.BoxSelection;
import static scenes.textureeditor.model.Selection.LassoSelection;
import static scenes.textureeditor.model.Selection.PixelSelection;

public sealed interface Selection permits PixelSelection, BoxSelection, LassoSelection {
    record PixelSelection(Coordinates px) implements Selection {
        public boolean is(int x, int y) {
            return x == px.x() && y == px.y();
        }
    }

    record BoxSelection(Coordinates tl, Coordinates br) implements Selection {
        public BoxSelection(int x, int y) {
            this(new Coordinates(x, y), new Coordinates(x, y));
        }

        public BoxSelection(int x1, int y1, int x2, int y2) {
            this(x1, y1);
            update(x1, y1, x2, y2);
        }

        public void update(int x1, int y1, int x2, int y2) {
            int xMin, yMin, xMax, yMax;
            if (x1 < x2) {
                xMin = x1;
                xMax = x2;
            } else {
                xMin = x2;
                xMax = x1;
            }
            if (y1 < y2) {
                yMin = y1;
                yMax = y2;
            } else {
                yMin = y2;
                yMax = y1;
            }
            tl.x(xMin);
            tl.y(yMin);
            br.x(xMax);
            br.y(yMax);
        }

        public boolean contains(int x, int y) {
            return x >= tl.x() && x <= br.x() && y >= tl.y() && y <= br.y();
        }
    }

    record LassoSelection(Set<Coordinates> all) implements Selection {
        public boolean contains(Coordinates c) {
            return all.contains(c);
        }

        @Override
        public String toString() {
            return "LassoSelection (size=" + all.size() + ")";
        }
    }
}
