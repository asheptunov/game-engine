package game;

import engine.Camera;

import math.Vec3;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Project short physical trail segments through the displayed camera, including scope
 * magnification.
 */
final class TracerOverlay {
    private TracerOverlay() {}

    static void draw(Graphics2D graphics, int width, int height, Camera camera, ShooterGame game) {
        var draw = (Graphics2D) graphics.create();
        try {
            draw.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double now = game.simulationTime();
            for (var trail : game.tracers()) {
                float opacity = (float) Math.clamp((trail.expires() - now) / .12, 0, 1);
                draw.setComposite(java.awt.AlphaComposite.SrcOver.derive(opacity));
                segment(draw, width, height, camera, game, trail);
            }
        } finally {
            draw.dispose();
        }
    }

    private static void segment(
            Graphics2D draw,
            int width,
            int height,
            Camera camera,
            ShooterGame game,
            ShooterGame.Tracer trail) {
        var forward = camera.forward();
        var a = trail.from().sub(camera.eye());
        var b = trail.to().sub(camera.eye());
        float near = .05f;
        float za = a.dot(forward);
        float zb = b.dot(forward);
        if (za < near && zb < near) {
            return;
        }
        if (za < near) {
            a = a.add(b.sub(a).scale((near - za) / (zb - za)));
        }
        if (zb < near) {
            b = b.add(a.sub(b).scale((near - zb) / (a.dot(forward) - zb)));
        }
        var midpoint = camera.eye().add(a.add(b).scale(.5f));
        if (!game.tracerVisible(camera.eye(), midpoint)
                || !game.tracerVisible(camera.eye(), camera.eye().add(a))
                || !game.tracerVisible(camera.eye(), camera.eye().add(b))) {
            return;
        }
        var p = project(camera, a, width, height);
        var q = project(camera, b, width, height);
        draw.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        draw.setColor(trail.player() ? new Color(255, 190, 45, 75) : new Color(255, 65, 20, 90));
        draw.draw(new java.awt.geom.Line2D.Float(p[0], p[1], q[0], q[1]));
        draw.setStroke(new BasicStroke(1.5f));
        draw.setColor(
                trail.player() ? new Color(255, 245, 180, 220) : new Color(255, 145, 85, 220));
        draw.draw(new java.awt.geom.Line2D.Float(p[0], p[1], q[0], q[1]));
    }

    private static float[] project(Camera camera, Vec3 delta, int width, int height) {
        float depth = delta.dot(camera.forward());
        var offset =
                delta.scale(camera.planeDistance() / depth)
                        .sub(camera.forward().scale(camera.planeDistance()));
        var horizontal = camera.sensor().edge1();
        var vertical = camera.sensor().edge2();
        return new float[] {
            width * (.5f + offset.dot(horizontal) / horizontal.lengthSq()),
            height * (.5f - offset.dot(vertical) / vertical.lengthSq())
        };
    }
}
