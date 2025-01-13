package v1.model.scene;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import v1.model.scene.Line;
import v1.model.scene.Point;
import v1.model.scene.Ray;
import v1.model.scene.Vector;

import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RayTest {
    static Stream<Arguments> points() {
        return Stream.of(
                Arguments.of(
                        new Vector(0, 0, 0),
                        new Ray(new Vector(0, 0, 0), new Vector(0, 1, 0)),
                        new Vector(0, 0, 0),
                        0),
                Arguments.of(
                        new Vector(0, 1, 0),
                        new Ray(new Vector(0, 0, 0), new Vector(0, 1, 0)),
                        new Vector(0, 1, 0),
                        0),
                Arguments.of(
                        new Vector(0, 1, 0),
                        new Ray(new Vector(0, 0, 0), new Vector(0, 0.99, 0)),
                        null,
                        0
                ),
                Arguments.of(
                        new Vector(0, 1, 0),
                        new Ray(new Vector(0, 0, 0), new Vector(0, 0.99, 0)),
                        null,
                        0.01
                )
        );
    }

    @ParameterizedTest
    @MethodSource("points")
    void testIntersectsPoint(Vector point, Ray ray, Vector intersection, double epsilon) {
        assertEquals(Optional.ofNullable(intersection), ray.intersection(new Point(point, false, false), epsilon));
    }

    @Test
    void testIntersectsLine() {
        var line = new Line(new Vector(0, 0, 0), new Vector(0, 1, 0), false, false);
        var ray = new Ray(new Vector(0, 1, 0), new Vector(1, 1, 1));
        var intersection = new Vector(0, 1, 0);
        var epsilon = 0;
        assertEquals(Optional.ofNullable(intersection), ray.intersection(line, epsilon));
    }

    @Test
    void testIntersectsSegment() {

    }

    @Test
    void testIntersectsPlane() {

    }

    @Test
    void testIntersectsTri() {

    }

    @Test
    void testIntersectsTrapezoid() {

    }

    @Test
    void testIntersectsParallelogram() {

    }
}
