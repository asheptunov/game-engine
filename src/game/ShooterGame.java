package game;

import engine.Camera;
import engine.Material;
import engine.RayQuery;
import engine.SceneDocument;
import engine.SceneInstance;
import engine.SpatialQuery;
import engine.Transform;

import math.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Game-owned combat. Distances are metres, time is seconds; one monitor owns simulation state. */
public final class ShooterGame {
    static final double STEP = 1.0 / 120;
    static final float MUZZLE_SPEED = 180;
    private static final Material UNIFORM =
            new Material("Patrol uniform", new Vec3(.45f, .16f, .09f));
    private static final Material SKIN = new Material("Patrol head", new Vec3(.65f, .45f, .3f));
    private static final Material STEEL = new Material("Weapon steel", new Vec3(.06f, .07f, .08f));

    public enum Region {
        HEAD(100),
        TORSO(70),
        ARM(30),
        LEG(40);
        final int damage;

        Region(int damage) {
            this.damage = damage;
        }
    }

    public record Target(int id, Vec3 feet, int health) {}

    public record Status(
            int health,
            int rounds,
            int reserve,
            String action,
            String hit,
            int kills,
            double hitTime,
            double hurtTime,
            double cooldown,
            List<Target> targets) {}

    private static final class Enemy {
        final int id;
        final Vec3 home;
        Vec3 feet;
        int health = 100;
        double attack;

        Enemy(int id, Vec3 feet) {
            this.id = id;
            this.home = feet;
            this.feet = feet;
            attack = 3 + id;
        }
    }

    private static final class Bullet {
        Vec3 position;
        Vec3 velocity;
        final boolean player;
        double age;
        float distance;

        Bullet(Vec3 position, Vec3 velocity, boolean player) {
            this.position = position;
            this.velocity = velocity;
            this.player = player;
        }
    }

    record Part(Region region, Vec3 center, Vec3 half) {}

    private final SpatialQuery scenery;
    private final List<Enemy> enemies = new ArrayList<>();
    private final List<Bullet> bullets = new ArrayList<>();
    private int health = 100;
    private int rounds = 5;
    private int reserve = 30;
    private int kills;
    private double time;
    private double remainder;
    private double cooldown;
    private double hitTime;
    private double hurtTime;
    private boolean reloading;
    private String hit = "";

    public ShooterGame(List<SceneInstance> instances) {
        var document = new SceneDocument();
        scenery =
                SpatialQuery.prepare(
                        document.transact(
                                edit -> {
                                    for (var instance : instances) {
                                        var node =
                                                edit.createNode(
                                                        instance.name(),
                                                        null,
                                                        instance.transform());
                                        var geometry =
                                                edit.createGeometry(
                                                        instance.name(), instance.geometry());
                                        var material =
                                                edit.createMaterial(
                                                        instance.name(), instance.material());
                                        edit.assignGeometry(node, geometry, material);
                                    }
                                }));
        reset();
    }

    public synchronized void reset() {
        health = 100;
        rounds = 5;
        reserve = 30;
        kills = 0;
        cooldown = 0;
        time = 0;
        remainder = 0;
        hitTime = 0;
        hurtTime = 0;
        reloading = false;
        hit = "";
        bullets.clear();
        enemies.clear();
        for (int id = 0; id < 5; id++) {
            float x = (id % 2 == 0 ? -1 : 1) * (2 + id);
            float z = 10 + id * 8;
            enemies.add(new Enemy(id, new Vec3(x, ground(x, z), z)));
        }
    }

    private float ground(float x, float z) {
        return scenery.nearest(new RayQuery(new Vec3(x, 80, z), new Vec3(0, -1, 0)))
                .map(value -> value.worldPosition().y())
                .orElse(-1.5f);
    }

    public synchronized boolean fire(Camera camera) {
        if (health == 0 || cooldown > 0 || rounds == 0) {
            return false;
        }
        rounds--;
        cooldown = 1.25;
        bullets.add(new Bullet(camera.eye(), camera.forward().scale(MUZZLE_SPEED), true));
        return true;
    }

    public synchronized void reload() {
        if (health > 0 && !reloading && cooldown == 0 && rounds < 5 && reserve > 0) {
            reloading = true;
            cooldown = 2.8;
        }
    }

    public synchronized Status status() {
        return new Status(
                health,
                rounds,
                reserve,
                action(),
                hit,
                kills,
                hitTime,
                hurtTime,
                cooldown,
                enemies.stream().map(e -> new Target(e.id, e.feet, e.health)).toList());
    }

    public synchronized boolean alive() {
        return health > 0;
    }

    private String action() {
        if (health == 0) {
            return "DOWN — Enter to restart";
        }
        if (kills == enemies.size()) {
            return "AREA CLEAR — Enter to restart";
        }
        if (reloading) {
            return "RELOADING";
        }
        if (cooldown > 0) {
            return "CYCLING BOLT";
        }
        return rounds == 0 ? "EMPTY — R to reload" : "READY";
    }

    /** Fixed substeps and swept segments prevent fast projectiles skipping thin cover. */
    public synchronized void advance(double seconds, Camera camera, boolean active) {
        if (!active || health == 0 || !Double.isFinite(seconds) || seconds < 0) {
            return;
        }
        remainder += Math.min(.25, seconds);
        while (remainder + 1e-12 >= STEP) {
            step(camera);
            remainder -= STEP;
        }
    }

    private void step(Camera camera) {
        time += STEP;
        cooldown = Math.max(0, cooldown - STEP);
        hitTime = Math.max(0, hitTime - STEP);
        hurtTime = Math.max(0, hurtTime - STEP);
        if (reloading && cooldown == 0) {
            int count = Math.min(5 - rounds, reserve);
            rounds += count;
            reserve -= count;
            reloading = false;
        }
        for (var enemy : enemies) {
            advanceEnemy(enemy, camera);
        }
        for (var iterator = bullets.iterator(); iterator.hasNext(); ) {
            var bullet = iterator.next();
            if (advanceBullet(bullet, camera)) {
                iterator.remove();
            }
        }
    }

    private void advanceEnemy(Enemy enemy, Camera camera) {
        if (enemy.health == 0) {
            return;
        }
        float x =
                enemy.home.x()
                        + (float) (Math.sin(time * .45 + enemy.id) - Math.sin(enemy.id)) * 1.8f;
        float y = ((int) (time / STEP) % 12 == 0) ? ground(x, enemy.home.z()) : enemy.feet.y();
        enemy.feet = new Vec3(x, y, enemy.home.z());
        enemy.attack -= STEP;
        if (enemy.attack > 0) {
            return;
        }
        enemy.attack = 2.5 + enemy.id * .25;
        var muzzle = enemy.feet.add(new Vec3(0, 1.35f, -.5f));
        var aim =
                camera.eye().add(new Vec3((float) Math.sin(time * 2 + enemy.id) * .65f, -.35f, 0));
        var delta = aim.sub(muzzle);
        float distance = delta.length();
        if (distance < 2 || distance > 65 || blocked(muzzle, delta, distance) < distance) {
            return;
        }
        // Compensate gravity for the estimated flight time; deterministic aim error allows dodging.
        float flight = distance / 65;
        var velocity = delta.scale(1 / flight).add(new Vec3(0, 4.905f * flight, 0));
        bullets.add(new Bullet(muzzle, velocity, false));
    }

    private float blocked(Vec3 origin, Vec3 delta, float distance) {
        return scenery.nearest(new RayQuery(origin, delta, .001f, distance, Set.of()))
                .map(engine.RayHit::distance)
                .orElse(distance);
    }

    private boolean advanceBullet(Bullet bullet, Camera camera) {
        float dt = (float) STEP;
        var delta = bullet.velocity.scale(dt).add(new Vec3(0, -4.905f * dt * dt, 0));
        float distance = delta.length();
        float nearest = blocked(bullet.position, delta, distance);
        if (bullet.player) {
            var impact = enemyImpact(bullet.position, delta, nearest);
            if (impact != null) {
                damageEnemy(impact, bullet.distance);
                return true;
            }
        } else {
            float along =
                    segmentBox(
                                    bullet.position,
                                    delta,
                                    camera.eye().add(new Vec3(0, -.65f, 0)),
                                    new Vec3(.28f, .85f, .28f))
                            * distance;
            if (along < nearest) {
                health = Math.max(0, health - 12);
                hurtTime = .5;
                return true;
            }
        }
        bullet.position = bullet.position.add(delta);
        bullet.velocity =
                bullet.velocity
                        .add(new Vec3(0, -9.81f * dt, 0))
                        .scale((float) Math.exp(-.035 * STEP));
        bullet.age += STEP;
        bullet.distance += distance;
        return nearest < distance || bullet.age > 5;
    }

    static int damage(Region region, float distance) {
        return region == Region.HEAD
                ? 100
                : Math.round(region.damage * Math.clamp(1 - distance / 600, .5f, 1));
    }

    private record Impact(Enemy enemy, Region region, float distance) {}

    private Impact enemyImpact(Vec3 origin, Vec3 delta, float nearest) {
        Impact impact = null;
        float length = delta.length();
        for (var enemy : enemies) {
            if (enemy.health == 0) {
                continue;
            }
            for (var part : parts(enemy.feet)) {
                float along = segmentBox(origin, delta, part.center, part.half) * length;
                if (along < nearest) {
                    nearest = along;
                    impact = new Impact(enemy, part.region, along);
                }
            }
        }
        return impact;
    }

    private void damageEnemy(Impact impact, float traveled) {
        int damage = damage(impact.region, traveled + impact.distance);
        var victim = impact.enemy;
        victim.health = Math.max(0, victim.health - damage);
        if (victim.health == 0) {
            kills++;
        }
        hit =
                impact.region
                        + "  "
                        + damage
                        + (victim.health == 0 ? " — eliminated" : " damage | HP " + victim.health);
        hitTime = 1.8;
    }

    static List<Part> parts(Vec3 feet) {
        return List.of(
                new Part(Region.HEAD, feet.add(new Vec3(0, 1.75f, 0)), new Vec3(.19f, .23f, .19f)),
                new Part(Region.TORSO, feet.add(new Vec3(0, 1.15f, 0)), new Vec3(.28f, .36f, .19f)),
                new Part(
                        Region.ARM, feet.add(new Vec3(-.39f, 1.15f, 0)), new Vec3(.1f, .36f, .12f)),
                new Part(Region.ARM, feet.add(new Vec3(.39f, 1.15f, 0)), new Vec3(.1f, .36f, .12f)),
                new Part(
                        Region.LEG, feet.add(new Vec3(-.16f, .39f, 0)), new Vec3(.12f, .39f, .15f)),
                new Part(
                        Region.LEG, feet.add(new Vec3(.16f, .39f, 0)), new Vec3(.12f, .39f, .15f)));
    }

    /** Fraction along a swept segment, or infinity for a miss; boxes match rendered body parts. */
    static float segmentBox(Vec3 origin, Vec3 delta, Vec3 center, Vec3 half) {
        float low = 0;
        float high = 1;
        float[] start = {origin.x() - center.x(), origin.y() - center.y(), origin.z() - center.z()};
        float[] direction = {delta.x(), delta.y(), delta.z()};
        float[] extent = {half.x(), half.y(), half.z()};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(direction[axis]) < 1e-9) {
                if (Math.abs(start[axis]) > extent[axis]) {
                    return Float.POSITIVE_INFINITY;
                }
            } else {
                float a = (-extent[axis] - start[axis]) / direction[axis];
                float b = (extent[axis] - start[axis]) / direction[axis];
                low = Math.max(low, Math.min(a, b));
                high = Math.min(high, Math.max(a, b));
                if (low > high) {
                    return Float.POSITIVE_INFINITY;
                }
            }
        }
        return low;
    }

    public synchronized List<SceneInstance> instances() {
        var result = new ArrayList<SceneInstance>();
        for (var enemy : enemies) {
            if (enemy.health == 0) {
                continue;
            }
            int index = 0;
            for (var part : parts(enemy.feet)) {
                result.add(
                        box(
                                "patrol/" + enemy.id + "/" + index++,
                                part.center,
                                part.half,
                                part.region == Region.HEAD ? SKIN : UNIFORM));
            }
            result.add(
                    box(
                            "patrol/" + enemy.id + "/rifle",
                            enemy.feet.add(new Vec3(.22f, 1.23f, -.35f)),
                            new Vec3(.06f, .07f, .48f),
                            STEEL));
        }
        return List.copyOf(result);
    }

    private static SceneInstance box(String name, Vec3 center, Vec3 half, Material material) {
        return new SceneInstance(
                name, SceneInstance.box(), new Transform(center, Vec3.ZERO, half), material);
    }
}
