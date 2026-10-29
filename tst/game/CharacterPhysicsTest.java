package game;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertTrue;

import engine.CapsuleCollision;
import engine.CharacterBody;
import engine.Material;
import engine.SceneInstance;
import engine.Transform;
import engine.input.KeyCode;
import engine.input.KeyInput;
import engine.input.Modifiers;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

import java.util.List;

public final class CharacterPhysicsTest {
    public static void main(String[] args) {
        SuiteRunner.runThis();
    }

    private static SceneInstance box(String name, Vec3 position, Vec3 scale) {
        return new SceneInstance(
                name,
                SceneInstance.box(),
                new Transform(position, Vec3.ZERO, scale),
                new Material(name, new Vec3(.5f, .5f, .5f)));
    }

    private static SceneInstance floor() {
        return box("Floor", new Vec3(0, -1, 0), new Vec3(100, 1, 100));
    }

    @Test
    void acceleratedForestContactsMatchLinearTraversalExactly() {
        var solids =
                AdventureWorld.create().instances().stream()
                        .filter(instance -> !instance.name().startsWith("Foliage "))
                        .toList();
        var accelerated = new CapsuleCollision(solids);
        var linear = new CapsuleCollision(solids, false);
        var random = new java.util.Random(421);
        for (int i = 0; i < 3000; i++) {
            var feet =
                    new Vec3(
                            random.nextFloat(-40, 40),
                            random.nextFloat(-3, 5),
                            random.nextFloat(-15, 115));
            float height = new float[] {.65f, 1.2f, 1.8f}[i % 3];
            assertEquals(
                    linear.contact(feet, .28f, height), accelerated.contact(feet, .28f, height));
        }
        var a = new CharacterBody(new Vec3(0, 3, -8));
        var b = new CharacterBody(new Vec3(0, 3, -8));
        for (int i = 0; i < 1200; i++) {
            float height = i < 400 ? 1.8f : i < 800 ? .65f : 1.2f;
            a.stance(height);
            b.stance(height);
            a.step(accelerated, 0, i < 600 ? 5 : -5, i == 300);
            b.step(linear, 0, i < 600 ? 5 : -5, i == 300);
            assertEquals(b.snapshot(), a.snapshot());
        }
    }

    private static CharacterBody settled(CapsuleCollision world) {
        var body = new CharacterBody(new Vec3(0, 3, 0));
        for (int i = 0; i < 240; i++) {
            body.step(world, 0, 0, false);
        }
        assertTrue(body.grounded());
        return body;
    }

    @Test
    void stanceTransitionsMoveContinuouslyAndWaitForHeadroom() {
        var open = new CapsuleCollision(List.of(floor()));
        var body = settled(open);
        body.stance(.65f);
        body.step(open, 3, 0, false);
        assertTrue(body.height() < 1.8f && body.height() > 1.7f);
        for (int i = 0; i < 120; i++) {
            body.step(open, 3, 0, false);
        }
        assertEquals(.65f, body.height());
        assertTrue(body.feet().x() > .5f);
        assertTrue(body.velocity().x() < 1);
        var low =
                new CapsuleCollision(
                        List.of(
                                floor(),
                                box("roof", new Vec3(0, 1.25f, 0), new Vec3(20, .1f, 20))));
        body.stance(1.8f);
        for (int i = 0; i < 120; i++) {
            body.step(low, 0, 0, false);
        }
        assertTrue(body.height() < 1.15f);
        assertTrue(body.feet().y() < .01f);
        for (int i = 0; i < 120; i++) {
            body.step(open, 0, 0, false);
        }
        assertEquals(1.8f, body.height());
        assertTrue(body.feet().y() < .01f);
    }

    @Test
    void stanceKeysToggleWithoutRepeatAndRestoreMidTransition() {
        var navigation = new GameNavigation();
        navigation.collision(new CapsuleCollision(List.of(floor())));
        navigation.walking(true);
        navigation.capture();
        var crouch = KeyInput.press(KeyCode.C, Modifiers.NONE);
        navigation.key(crouch);
        navigation.key(crouch);
        assertEquals("CROUCHED", navigation.stance());
        navigation.advance(.1);
        var state = navigation.snapshot();
        navigation.advance(.1);
        var after = navigation.snapshot();
        navigation.restore(state);
        navigation.advance(.1);
        assertEquals(after, navigation.snapshot());
        navigation.key(KeyInput.press(KeyCode.Z, Modifiers.NONE));
        assertEquals("PRONE", navigation.stance());
        navigation.reset();
        assertEquals("STANDING", navigation.stance());
    }

    @Test
    void gravityJumpAndLanding() {
        var world = new CapsuleCollision(List.of(floor()));
        var body = settled(world);
        assertTrue(Math.abs(body.feet().y()) < .001);
        body.step(world, 0, 0, true);
        float initialVelocity = body.velocity().y();
        body.step(world, 0, 0, true);
        assertTrue(
                Math.abs(
                                body.velocity().y()
                                        - initialVelocity
                                        + CharacterBody.GRAVITY * CharacterBody.STEP)
                        < .00001);
        float apex = 0;
        for (int i = 0; i < 180; i++) {
            body.step(world, 0, 0, false);
            apex = Math.max(apex, body.feet().y());
        }
        assertTrue(Math.abs(apex - CharacterBody.JUMP_HEIGHT) < .025);
        assertTrue(body.grounded());
        assertTrue(Math.abs(body.feet().y()) < .001);
    }

    @Test
    void thinWallSlidingAndCeiling() {
        var world =
                new CapsuleCollision(
                        List.of(
                                floor(),
                                box("Wall", new Vec3(2, 3, 0), new Vec3(.01f, 3, 20)),
                                box("Ceiling", new Vec3(0, 2.2f, 0), new Vec3(1, .1f, 1))));
        var body = settled(world);
        body.step(world, 0, 0, true);
        for (int i = 0; i < 60; i++) {
            body.step(world, 0, 0, false);
            assertTrue(body.feet().y() + CharacterBody.HEIGHT <= 2.101f);
        }
        for (int i = 0; i < 240; i++) {
            body.step(world, 100, 5, false);
        }
        assertTrue(body.feet().x() < 1.691f);
        assertTrue(body.feet().z() > 1);
    }

    private static GameNavigation navigation() {
        var navigation = new GameNavigation();
        navigation.collision(new CapsuleCollision(List.of(floor())));
        navigation.walking(true);
        navigation.capture();
        for (int i = 0; i < 120; i++) {
            navigation.advance(1.0 / 120);
        }
        return navigation;
    }

    @Test
    void timestepInputPauseAndModeTransitions() {
        var fast = navigation();
        var slow = navigation();
        var jump = KeyInput.press(KeyCode.SPACE, Modifiers.NONE);
        fast.key(jump);
        slow.key(jump);
        fast.key(KeyInput.press(KeyCode.W, Modifiers.NONE));
        slow.key(KeyInput.press(KeyCode.W, Modifiers.NONE));
        for (int i = 0; i < 120; i++) {
            fast.advance(1.0 / 120);
            fast.key(jump);
        }
        for (int i = 0; i < 30; i++) {
            slow.advance(1.0 / 30);
            slow.key(jump);
        }
        assertTrue(fast.view().camera().eye().sub(slow.view().camera().eye()).length() < .0001);
        assertTrue(fast.grounded());
        var eye = fast.view().camera().eye();
        fast.active(false);
        fast.advance(10);
        assertEquals(eye, fast.view().camera().eye());
        fast.active(true);
        fast.capture();
        fast.advance(.1);
        fast.walking(false);
        fast.key(KeyInput.press(KeyCode.SPACE, Modifiers.NONE));
        fast.advance(.1);
        assertTrue(fast.view().camera().eye().y() > eye.y());
        fast.walking(true);
        fast.reset();
        assertEquals(new Vec3(0, 2.4f, -6), fast.view().camera().eye());
    }

    @Test
    void slopesLedgesAndTransformedGeometry() {
        var ramp =
                new SceneInstance(
                        "Ramp",
                        SceneInstance.box(),
                        new Transform(new Vec3(0, -1, 0), new Vec3(0, 0, 20), new Vec3(20, 1, 20)),
                        new Material("Rock", new Vec3(.5f, .5f, .5f)));
        var world = new CapsuleCollision(List.of(ramp));
        var body = settled(world);
        float initial = body.feet().y();
        for (int i = 0; i < 240; i++) {
            body.step(world, 3, 0, false);
        }
        assertTrue(body.feet().x() > 3);
        assertTrue(body.feet().y() > initial + 1);
        assertTrue(body.grounded());
        var ledge =
                new CapsuleCollision(List.of(box("Ledge", new Vec3(0, -1, 0), new Vec3(2, 1, 2))));
        body = settled(ledge);
        for (int i = 0; i < 240; i++) {
            body.step(ledge, 3, 0, false);
        }
        assertTrue(body.feet().x() > 3);
        assertTrue(body.feet().y() < -1);
        assertTrue(!body.grounded());
    }

    @Test
    void steepSlopeDoesNotBecomeWalkable() {
        var ramp =
                new SceneInstance(
                        "Steep ramp",
                        SceneInstance.box(),
                        new Transform(new Vec3(2, 0, 0), new Vec3(0, 0, 70), new Vec3(5, .1f, 5)),
                        new Material("Rock", new Vec3(.5f, .5f, .5f)));
        var world = new CapsuleCollision(List.of(floor(), ramp));
        var body = settled(world);
        for (int i = 0; i < 360; i++) {
            body.step(world, 3, 0, false);
        }
        assertTrue(body.feet().x() < 2.2f);
        assertTrue(body.feet().y() < .4f);
    }

    @Test
    void valleyGroundAndRuinsWall() {
        // Match the game: leaves conceal without becoming solid movement obstacles.
        var world =
                new CapsuleCollision(
                        AdventureWorld.create().instances().stream()
                                .filter(instance -> !instance.name().startsWith("Foliage "))
                                .toList());
        var body = new CharacterBody(new Vec3(0, 2.4f, -6));
        for (int i = 0; i < 240; i++) {
            body.step(world, 0, 0, false);
        }
        assertTrue(body.grounded());
        for (int i = 0; i < 600; i++) {
            body.step(world, 0, 5, false);
        }
        assertTrue(body.feet().z() > 10);
        assertTrue(body.feet().y() > -5);
        body.teleport(new Vec3(23, 6, 28));
        for (int i = 0; i < 240; i++) {
            body.step(world, 0, 0, false);
        }
        float closestColumn = Float.POSITIVE_INFINITY;
        for (int i = 0; i < 360; i++) {
            body.step(world, 3, 0, false);
            float x = body.feet().x() - 27;
            float z = body.feet().z() - 28;
            closestColumn = Math.min(closestColumn, (float) Math.sqrt(x * x + z * z));
        }
        // The capsule may slide around the faceted column but must never enter it.
        assertTrue(closestColumn > .9f);
        assertTrue(closestColumn < 1.2f);
    }
}
