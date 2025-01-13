package v1.model.scene;

public sealed interface Basis extends Shape permits Point, Line, Plane {
}
