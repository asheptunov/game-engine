package game;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;

/** Presentation only; the scope's magnification and finite pupil are rendered by the camera. */
final class ShooterOverlay {

    private ShooterOverlay() {}

    static void draw(
            Graphics2D draw,
            int width,
            int height,
            GameNavigation navigation,
            ShooterGame.Status status) {
        int cx = width / 2;
        int cy = height / 2;
        if (navigation.scoped()) {
            int radius = (int) (Math.min(width, height) * .43);
            var mask = new Area(new Rectangle2D.Float(0, 0, width, height));
            mask.subtract(
                    new Area(
                            new Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2)));
            draw.setColor(new Color(5, 8, 10));
            draw.fill(mask);
            draw.setStroke(new BasicStroke(8));
            draw.setColor(new Color(63, 76, 80));
            draw.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
            draw.setStroke(new BasicStroke(1));
            draw.setColor(new Color(20, 25, 25));
            draw.drawLine(cx - radius + 6, cy, cx + radius - 6, cy);
            draw.drawLine(cx, cy - radius + 6, cx, cy + radius - 6);
            for (int tick = -4; tick <= 4; tick++) {
                if (tick != 0) {
                    draw.drawLine(cx - 5, cy + tick * 22, cx + 5, cy + tick * 22);
                    draw.drawLine(cx + tick * 22, cy - 5, cx + tick * 22, cy + 5);
                }
            }
            draw.setColor(Color.WHITE);
            draw.drawString(
                    "4×  |  50 mm pupil  |  Focus " + Math.round(navigation.scopeFocus()) + " m",
                    cx - 140,
                    cy + radius - 24);
        }
        if (status.hurtTime() > 0) {
            draw.setColor(new Color(220, 30, 20, 70));
            draw.setStroke(new BasicStroke(20));
            draw.drawRect(10, 10, width - 20, height - 20);
            draw.setStroke(new BasicStroke(1));
        }
        draw.setColor(new Color(8, 14, 20, 220));
        draw.fillRoundRect(16, height - 146, 440, 65, 12, 12);
        draw.setColor(new Color(65, 75, 80));
        draw.fillRect(28, height - 106, 160, 10);
        draw.setColor(status.health() > 30 ? new Color(90, 210, 140) : Color.RED);
        draw.fillRect(28, height - 106, status.health() * 160 / 100, 10);
        draw.setColor(Color.WHITE);
        draw.drawString(
                "HEALTH "
                        + status.health()
                        + "    "
                        + status.rounds()
                        + " / "
                        + status.reserve()
                        + " rounds    Patrols "
                        + status.kills()
                        + "/5",
                28,
                height - 124);
        draw.drawString(status.action(), 204, height - 96);
        if (status.hitTime() > 0) {
            draw.setColor(new Color(255, 210, 100));
            draw.drawLine(cx - 10, cy - 10, cx - 4, cy - 4);
            draw.drawLine(cx + 10, cy - 10, cx + 4, cy - 4);
            draw.drawLine(cx - 10, cy + 10, cx - 4, cy + 4);
            draw.drawLine(cx + 10, cy + 10, cx + 4, cy + 4);
            draw.drawString(status.hit(), cx + 20, cy + 30);
        }
    }
}
