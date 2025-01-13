package v1.model.scene;

import java.util.Optional;

public class Ray {
    private final Vector origin;
    private final Vector parallel;

    public Ray(Vector origin, Vector parallel) {
        this.origin = origin;
        this.parallel = parallel;
    }

    public Optional<Vector> intersection(Shape shape, double epsilon) {
        if (!(shape instanceof Basis)) {
            return intersection(shape.basis(), epsilon);
        }
        return switch (shape) {
            case Point point: {
                var dist = (origin.minus(point.coordinate())).minus(parallel.times(parallel.dot(origin.minus(point.coordinate()))));
                yield dist.magnitude() <= epsilon
                        ? Optional.of(origin.minus(parallel.hat().times(parallel.hat().dot(origin.minus(point.coordinate())))))
                        : Optional.empty();
//                var tX = (point.coordinate().x() - origin.x()) / parallel.x();
//                var dY = origin.y() + parallel.y() * tX - point.coordinate().y();
//                var dZ = origin.z() + parallel.z() * tX - point.coordinate().z();
//                var intersects = tX > 0
//                        && Math.abs(dY) < epsilon
//                        && Math.abs(dZ) < epsilon;
//                // intersects -> tX == tY == tZ
//                yield intersects
//                        ? Optional.of(origin.plus(parallel.times(tX)))
//                        : Optional.empty();
            }
            case Line line: {
                var tLine = (0
                        + origin.y() * parallel.x()
                        - origin.x() * parallel.y()
                        + parallel.y() * line.intercept().x()
                        - parallel.x() * line.intercept().y()
                ) / (0
                        + parallel.x() * line.parallel().y()
                        - parallel.y() * line.parallel().x());
                var tRay = (0
                        + line.intercept().x() - origin.x()
                        + line.parallel().x() * tLine
                ) / parallel.x();
                var zLine = line.intercept().z() + line.parallel().z() * tLine;
                var zRay = origin.z() + parallel.z() * tRay;
                var delta = zLine - zRay;
                var intersects = tRay > 0
                        && Math.abs(delta) <= epsilon;
                yield intersects
                        ? Optional.of(origin.plus(parallel.times(tRay)))
                        : Optional.empty();
            }
            case Plane plane: {
                var dot = plane.normal().dot(parallel);
                if (dot == 0) {
                    yield Optional.empty();
                }
                var t = plane.normal().dot(plane.intercept().minus(origin)) / dot;
                yield Optional.of(origin.plus(parallel.times(t)));
            }
            case null:
                throw new IllegalArgumentException();
            default:
                throw new UnsupportedOperationException(shape.getClass().getName());
        };
    }
}
