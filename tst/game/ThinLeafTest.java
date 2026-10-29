package game;

import static harness.Assertions.assertTrue;

import engine.Material;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

/** Energy and PDF checks for the two-sided, zero-thickness diffuse leaf model. */
public final class ThinLeafTest {
    public static void main(String[] args) {
        SuiteRunner.runThis();
    }

    @Test
    void bothHemispheresConserveEnergyAndMatchEvaluation() {
        var leaf = new Material("leaf", new Vec3(.3f, .6f, .1f), Material.Kind.THIN_LEAF);
        var sampled = new Material.Sample();
        var evaluated = new Material.Sample();
        int transmitted = 0;
        for (int i = 0; i < 2000; i++) {
            leaf.sample(0, 0, -1, 0, 0, 1, (i + .5f) / 2000, .37f, sampled);
            leaf.evaluate(0, 0, -1, sampled.dx, sampled.dy, sampled.dz, 0, 0, 1, 1, 1, evaluated);
            near(sampled.probability, evaluated.probability);
            near(leaf.color().x(), evaluated.red / sampled.probability);
            near(leaf.color().y(), evaluated.green / sampled.probability);
            near(leaf.color().z(), evaluated.blue / sampled.probability);
            near(leaf.color().y(), sampled.green);
            assertTrue(!sampled.delta);
            assertTrue(sampled.transmitted == (sampled.dz < 0));
            if (sampled.transmitted) {
                transmitted++;
            }
        }
        assertTrue(transmitted == 1000);
        near(.5, leaf.diffuseCosine(-1));
        near(.5, leaf.diffuseCosine(1));
        near(0, leaf.withKind(Material.Kind.DIFFUSE).diffuseCosine(-1));
    }

    @Test
    void foliageCapturesRetainThinMaterialsAndSolidObjectsStayOpaque() {
        var world = AdventureWorld.create();
        var restored = CaptureScene.capture(world.instances()).restore();
        assertTrue(restored.size() == world.instances().size());
        for (int i = 0; i < restored.size(); i++) {
            var original = world.instances().get(i);
            assertTrue(restored.get(i).material().equals(original.material()));
            if (original.name().startsWith("Foliage")
                    && !original.material().name().equals("Pine needles")) {
                assertTrue(original.material().kind() == Material.Kind.THIN_LEAF);
            }
            if (original.material().name().equals("Pine bark")) {
                assertTrue(original.material().kind() == Material.Kind.DIFFUSE);
            }
        }
    }

    @Test
    void directionalLeavesConserveEnergyAndMatchSampling() {
        var leaf =
                new Material("optical leaf", new Vec3(.3f, .6f, .1f))
                        .withLeaf(new engine.LeafOptics(.6f, new Vec3(1, 1, 1), 0, .65f, 24));
        var sample = new Material.Sample();
        var evaluation = new Material.Sample();
        var random = new java.util.Random(17);
        for (float ci : new float[] {1, .7f, .1f}) {
            float dx = (float) Math.sqrt(1 - ci * ci);
            double energy = 0;
            int count = 100000;
            for (int i = 0; i < count; i++) {
                leaf.sample(dx, 0, -ci, 0, 0, 1, random.nextFloat(), random.nextFloat(), sample);
                energy += sample.green;
                if (sample.green > 0) {
                    leaf.evaluate(
                            dx, 0, -ci, sample.dx, sample.dy, sample.dz, 0, 0, 1, 1, 1, evaluation);
                    near(sample.probability, evaluation.probability);
                    near(sample.green, evaluation.green / sample.probability);
                }
            }
            assertTrue(energy / count <= .845);
            if (ci == 1) {
                assertTrue(Math.abs(energy / count - .84) < .005);
            }
        }
        leaf.evaluate(0, 0, -1, 0, 0, -1, 0, 0, 1, 1, 1, evaluation);
        float peak = evaluation.green;
        leaf.evaluate(0, 0, -1, .8f, 0, -.6f, 0, 0, 1, 1, 1, evaluation);
        assertTrue(peak > evaluation.green * 10);
        assertTrue(leaf.withKind(Material.Kind.DIFFUSE).leaf() == null);
    }

    @Test
    void thicknessFiltersColorAndTransportIsReciprocal() {
        var optics = new engine.LeafOptics(.6f, new Vec3(.1f, .6f, .03f), .2f, .65f, 24);
        var a = new Material.Sample();
        var b = new Material.Sample();
        optics.evaluate(.8f, -.6f, .96f, new Vec3(1, 1, 1), a);
        optics.evaluate(.6f, -.8f, .96f, new Vec3(1, 1, 1), b);
        near(a.green / .6f, b.green / .8f);
        assertTrue(a.green > a.red && a.red > a.blue);
        new engine.LeafOptics(.6f, optics.tint(), .8f, .65f, 24)
                .evaluate(.8f, -.6f, .96f, new Vec3(1, 1, 1), b);
        assertTrue(b.green < a.green && b.red < a.red);
    }

    @Test
    void captureRoundTripAndOlderMaterialCompatibility() throws Exception {
        var file = java.nio.file.Files.createTempFile("leaf-optics", ".properties");
        try {
            var leaf = new Material("leaf", new Vec3(.3f, .6f, .1f)).withLeaf(Foliage.optics(.2f));
            CaptureCodec.save(file, leaf);
            assertTrue(CaptureCodec.load(file, Material.class).equals(leaf));
            var legacy = leaf.withKind(Material.Kind.DIFFUSE);
            CaptureCodec.save(file, legacy);
            var text =
                    java.nio.file.Files.readString(file).replaceAll("(?m)^data\\.leaf=.*\\R?", "");
            java.nio.file.Files.writeString(file, text);
            assertTrue(CaptureCodec.load(file, Material.class).equals(legacy));
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    void sceneAssetAndXmlRetainLeafOptics() throws Exception {
        var document = new engine.SceneDocument();
        var optics = Foliage.optics(.24f);
        document.transact(
                edit ->
                        edit.createMaterial(
                                "leaf",
                                new Material("leaf", new Vec3(.3f, .6f, .1f)).withLeaf(optics)));
        var directory = java.nio.file.Path.of("out/game-check");
        java.nio.file.Files.createDirectories(directory);
        var file = directory.resolve("leaf-scene-" + java.util.UUID.randomUUID() + ".xml");
        try {
            engine.SceneFiles.save(file, document.snapshot());
            var restored = engine.SceneFiles.load(file);
            assertTrue(restored.materialAssets().getFirst().material().leaf().equals(optics));
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    private static void near(double expected, double actual) {
        assertTrue(Math.abs(expected - actual) < 1e-5);
    }
}
