package game;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;

/** Health, ammunition and hit feedback; the scope and reticle belong to the world. */
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
