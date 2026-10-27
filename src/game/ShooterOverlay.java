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
        } else {
            rifle(draw, width, height, status.cooldown());
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

    private static void rifle(Graphics2D draw, int width, int height, double cooldown) {
        var weapon = (Graphics2D) draw.create();
        try {
            weapon.translate(width * .66, height * .83 + (cooldown > 1 ? 10 : 0));
            weapon.rotate(-.45);
            weapon.setColor(new Color(75, 64, 44));
            weapon.fillRoundRect(-45, 0, 250, 56, 14, 14);
            weapon.setColor(new Color(28, 34, 38));
            weapon.fillRect(-125, 3, 190, 18);
            weapon.fillRoundRect(-10, -18, 115, 38, 8, 8);
            weapon.setColor(new Color(64, 75, 81));
            weapon.fillRoundRect(-30, -48, 150, 29, 12, 12);
            weapon.setColor(new Color(64, 130, 145));
            weapon.fillOval(98, -47, 22, 27);
            weapon.setColor(new Color(140, 150, 155));
            int bolt =
                    cooldown > 0
                            ? (int) (Math.sin(Math.min(1, cooldown / 1.25) * Math.PI) * 35)
                            : 0;
            weapon.fillRect(55 + bolt, -2, 8, 40);
            weapon.fillOval(49 + bolt, 29, 20, 18);
        } finally {
            weapon.dispose();
        }
    }
}
