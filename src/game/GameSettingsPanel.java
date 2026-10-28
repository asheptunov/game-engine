package game;

import engine.Sky;

import math.Vec3;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SpinnerNumberModel;

/** Event-thread settings editor. Renderer/navigation setters publish immutable inputs. */
public final class GameSettingsPanel extends JPanel {
    private final GameNavigation navigation;
    private final GameRenderer renderer;
    private final List<Runnable> refreshers = new ArrayList<>();
    private final JLabel message =
            new JLabel("Changes apply immediately. Esc releases the pointer.");
    private boolean refreshing;
    private boolean customResolution;

    public GameSettingsPanel(GameNavigation navigation, GameRenderer renderer) {
        this.navigation = navigation;
        this.renderer = renderer;
        setLayout(new BorderLayout(0, 4));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        var tabs = new JTabbedPane();
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.addTab("Render", scroll(renderControls()));
        tabs.addTab("Camera", scroll(cameraControls()));
        tabs.addTab("Lighting", scroll(lightControls()));
        tabs.addTab("Fog", scroll(fogControls()));
        tabs.addTab("Advanced", scroll(advancedControls()));
        tabs.addTab("Capture", scroll(captureControls()));
        add(tabs, BorderLayout.CENTER);
        add(message, BorderLayout.SOUTH);
        theme(this);
        tabs.setUI(
                new javax.swing.plaf.basic.BasicTabbedPaneUI() {
                    @Override
                    protected void paintTabBackground(
                            java.awt.Graphics draw,
                            int placement,
                            int index,
                            int x,
                            int y,
                            int width,
                            int height,
                            boolean selected) {
                        draw.setColor(selected ? new Color(65, 105, 145) : new Color(35, 46, 62));
                        draw.fillRect(x, y, width, height);
                    }

                    @Override
                    protected void paintTabBorder(
                            java.awt.Graphics draw,
                            int placement,
                            int index,
                            int x,
                            int y,
                            int width,
                            int height,
                            boolean selected) {}

                    @Override
                    protected void paintContentBorder(
                            java.awt.Graphics draw, int placement, int selected) {}

                    @Override
                    protected void paintFocusIndicator(
                            java.awt.Graphics draw,
                            int placement,
                            java.awt.Rectangle[] rectangles,
                            int index,
                            java.awt.Rectangle icon,
                            java.awt.Rectangle text,
                            boolean selected) {}
                });
        refresh();
    }

    private JPanel captureControls() {
        var panel = page();
        heading(panel, "Diagnostic captures");
        panel.add(new JLabel("F8: current snapshot. F9: preceding 30 seconds."));
        panel.add(new JLabel("Saved under out/game/captures; share the whole folder."));
        button(panel, "Save snapshot (F8)", () -> renderer.captureBundle(false));
        button(panel, "Save recent history (F9)", () -> renderer.captureBundle(true));
        panel.add(new JLabel("Includes scene, textures, controls, physics and rendered images."));
        return panel;
    }

    private static void theme(java.awt.Component component) {
        component.setBackground(new Color(23, 31, 43));
        component.setForeground(new Color(225, 232, 242));
        component.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 12));
        if (component instanceof javax.swing.text.JTextComponent) {
            component.setBackground(new Color(35, 46, 62));
        }
        if (component instanceof JButton button) {
            button.setBackground(new Color(45, 65, 88));
            button.setFocusPainted(false);
            button.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
        }
        if (component instanceof java.awt.Container container) {
            for (var child : container.getComponents()) {
                theme(child);
            }
        }
    }

    private JScrollPane scroll(JPanel panel) {
        var scroll = new JScrollPane(panel);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        return scroll;
    }

    private JPanel page() {
        var panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private void heading(JPanel panel, String label) {
        var title = new JLabel(label);
        title.setAlignmentX(LEFT_ALIGNMENT);
        title.setBorder(BorderFactory.createEmptyBorder(10, 4, 4, 4));
        panel.add(title);
    }

    private record ResolutionPreset(String label, int width, int height) {
        @Override
        public String toString() {
            return label;
        }
    }

    private void resolutionPresets(JPanel panel) {
        var choices =
                new ResolutionPreset[] {
                    new ResolutionPreset("360p — 640 × 360", 640, 360),
                    new ResolutionPreset("480p — 854 × 480", 854, 480),
                    new ResolutionPreset("540p — 960 × 540", 960, 540),
                    new ResolutionPreset("720p HD — 1280 × 720", 1280, 720),
                    new ResolutionPreset("900p — 1600 × 900", 1600, 900),
                    new ResolutionPreset("1080p — 1920 × 1080", 1920, 1080),
                    new ResolutionPreset("Custom", 0, 0)
                };
        var selector = new JComboBox<>(choices);
        selector.setToolTipText("Internal render size; the image is scaled to the game window.");
        row(panel, "Render resolution", selector);
        panel.add(new JLabel("Scaled to window. Lower resolution renders faster."));
        panel.add(
                new JLabel(
                        "Current render limit: "
                                + renderer.capabilities().maximumDimension()
                                + " pixels per dimension."));
        selector.addActionListener(
                event ->
                        apply(
                                () -> {
                                    var selected = (ResolutionPreset) selector.getSelectedItem();
                                    customResolution = selected != null && selected.width() == 0;
                                    if (selected != null && selected.width() != 0) {
                                        navigation.fixedResolution(
                                                selected.width(), selected.height());
                                    }
                                }));
        refreshers.add(
                () -> {
                    var view = navigation.view();
                    var selected = choices[choices.length - 1];
                    for (var choice : choices) {
                        if (!customResolution
                                && choice.width() == view.width()
                                && choice.height() == view.height()) {
                            selected = choice;
                            break;
                        }
                    }
                    selector.setSelectedItem(selected);
                });
    }

    private void frameBudget(JPanel panel) {
        var selector =
                new JComboBox<>(
                        new String[] {"Fixed resolution", "60 FPS", "120 FPS", "Custom budget"});
        row(panel, "Movement frame budget", selector);
        selector.setName("frameBudget");
        panel.add(
                new JLabel("Lowers detail while moving; restores selected resolution when still."));
        selector.addActionListener(
                event ->
                        apply(
                                () -> {
                                    int choice = selector.getSelectedIndex();
                                    if (choice == 3) {
                                        return;
                                    }
                                    renderer.configure(
                                            s ->
                                                    s.withInteractive(
                                                            choice != 0,
                                                            choice == 0
                                                                    ? s.interactiveMillis()
                                                                    : 1000.
                                                                            / (choice == 1
                                                                                    ? 60
                                                                                    : 120),
                                                            s.minimumWidth(),
                                                            s.minimumHeight()));
                                }));
        refreshers.add(
                () -> {
                    var settings = renderer.settings();
                    int choice = frameBudgetChoice(settings);
                    selector.setSelectedIndex(choice);
                });
    }

    private static int frameBudgetChoice(engine.RenderSettings settings) {
        if (!settings.interactive()) {
            return 0;
        }
        if (Math.abs(settings.interactiveMillis() - 1000. / 60) < .001) {
            return 1;
        }
        return Math.abs(settings.interactiveMillis() - 1000. / 120) < .001 ? 2 : 3;
    }

    private JPanel renderControls() {
        var panel = page();
        heading(
                panel,
                renderer.capabilities().id().toUpperCase(java.util.Locale.ROOT) + " renderer");
        check(panel, "Refinement", renderer::refinement, renderer::refinement, true);
        number(
                panel,
                "Sample limit (0 = unlimited)",
                renderer::refinementTarget,
                value -> renderer.sampleLimit((long) value),
                0,
                1_000_000_000,
                16,
                true);
        check(
                panel,
                "Pause rendering",
                () -> renderer.settings().paused(),
                value -> renderer.configure(s -> s.withPaused(value)),
                true);
        button(panel, "Restart samples", () -> renderer.restart());
        heading(panel, "Image and transport");
        resolutionPresets(panel);
        frameBudget(panel);
        number(
                panel,
                "Width (pixels)",
                () -> navigation.view().width(),
                value -> navigation.fixedResolution((int) value, navigation.view().height()),
                64,
                renderer.capabilities().maximumDimension(),
                16,
                true);
        number(
                panel,
                "Height (pixels)",
                () -> navigation.view().height(),
                value -> navigation.fixedResolution(navigation.view().width(), (int) value),
                64,
                renderer.capabilities().maximumDimension(),
                16,
                true);
        number(
                panel,
                "Exposure multiplier",
                renderer::exposure,
                value -> renderer.exposure((float) value),
                .01,
                100,
                .1,
                true);
        number(
                panel,
                "Path depth",
                () -> renderer.settings().pathDepth(),
                value -> renderer.configure(s -> s.withPathDepth((int) value)),
                renderer.shooter() == null ? 0 : 8,
                renderer.capabilities().maximumDepth(),
                1,
                true);
        number(
                panel,
                "Samples per batch",
                () -> renderer.settings().samplesPerBatch(),
                value -> renderer.configure(s -> s.withSamplesPerBatch((int) value)),
                1,
                8,
                1,
                true);
        return panel;
    }

    private JPanel cameraControls() {
        var panel = page();
        heading(panel, "Perspective camera");
        number(
                panel,
                "Vertical FOV (degrees)",
                navigation::fov,
                value -> navigation.fov((float) value),
                1,
                179,
                1,
                true);
        heading(panel, "Depth of field (aperture 0 = off)");
        check(
                panel,
                "Autofocus (screen center)",
                navigation::autofocus,
                navigation::autofocus,
                true);
        number(
                panel,
                "Aperture radius (world units)",
                navigation::aperture,
                value -> navigation.optics((float) value, navigation.focus()),
                0,
                10,
                .01,
                true);
        number(
                panel,
                "Focus distance (world units)",
                navigation::focus,
                value -> navigation.manualFocus((float) value),
                .01,
                10000,
                .25,
                true);
        panel.add(new JLabel("Editing distance selects manual focus. Sky retains focus."));
        heading(panel, "Navigation");
        check(
                panel,
                "Walk with gravity (off = fly)",
                navigation::walking,
                navigation::walking,
                true);
        number(
                panel,
                "Movement speed (units/s)",
                navigation::speed,
                value -> navigation.speed((float) value),
                .1,
                100,
                .5,
                true);
        number(
                panel,
                "Mouse sensitivity (radians/pixel)",
                navigation::sensitivity,
                navigation::sensitivity,
                .0001,
                .05,
                .0005,
                true);
        button(panel, "Reset camera position", navigation::reset);
        return panel;
    }

    private JPanel lightControls() {
        var panel = page();
        heading(panel, "Day / night");
        check(panel, "Day/night cycle", renderer::dayCycle, renderer::dayCycle, true);
        number(
                panel,
                "Time of day (hours)",
                renderer::timeOfDay,
                renderer::timeOfDay,
                0,
                24,
                .25,
                true);
        number(
                panel,
                "Full day (seconds)",
                renderer::dayDuration,
                renderer::dayDuration,
                30,
                7200,
                30,
                true);
        panel.add(new JLabel("Manual lighting edits pause the cycle."));
        atmosphereControls(panel);
        heading(panel, "Sun presets");
        var presets = new JPanel(new FlowLayout(FlowLayout.LEFT));
        presets.setAlignmentX(LEFT_ALIGNMENT);
        for (var preset : GameLighting.Preset.values()) {
            button(presets, preset.name(), () -> renderer.lightingPreset(preset));
        }
        panel.add(presets);
        panel.add(new JLabel("07:00 / 12:00 / 17:00; pauses the cycle."));
        panel.add(new JLabel("Legacy sun/sky controls below apply when atmosphere is off."));
        check(
                panel,
                "Sky enabled",
                () -> renderer.lighting().skyEnabled(),
                value -> renderer.lighting(renderer.lighting().withSky(value)),
                true);
        number(
                panel,
                "Sun intensity",
                () -> renderer.lighting().intensity(),
                value -> {
                    var light = renderer.lighting();
                    renderer.lighting(
                            new GameLighting(
                                    light.preset(),
                                    light.skyEnabled(),
                                    light.direction(),
                                    light.sunColor(),
                                    (float) value,
                                    light.skyColors()));
                },
                0,
                100,
                .25,
                true);
        heading(panel, "Sun direction (toward source; nonzero XYZ)");
        vector(
                panel,
                "Direction",
                () -> renderer.lighting().direction(),
                value -> {
                    var light = renderer.lighting();
                    renderer.lighting(
                            new GameLighting(
                                    light.preset(),
                                    light.skyEnabled(),
                                    value,
                                    light.sunColor(),
                                    light.intensity(),
                                    light.skyColors()));
                },
                -1,
                1,
                new String[] {"X", "Y", "Z"});
        heading(panel, "Linear RGB colors");
        vector(
                panel,
                "Sun",
                () -> renderer.lighting().sunColor(),
                value -> {
                    var light = renderer.lighting();
                    renderer.lighting(
                            new GameLighting(
                                    light.preset(),
                                    light.skyEnabled(),
                                    light.direction(),
                                    value,
                                    light.intensity(),
                                    light.skyColors()));
                },
                0,
                1,
                new String[] {"R", "G", "B"});
        skyColor(panel, false);
        skyColor(panel, true);
        return panel;
    }

    private void atmosphereControls(JPanel panel) {
        heading(panel, "Scattering atmosphere (solar direction follows clock)");
        check(
                panel,
                "Atmosphere enabled",
                renderer::atmosphereEnabled,
                renderer::atmosphereEnabled,
                true);
        airQuality(panel, "atmosphereResolution");
        button(panel, "Clear air / reset atmosphere", () -> renderer.atmospherePreset(false));
        button(panel, "Hazy morning air (keeps time)", () -> renderer.atmospherePreset(true));
        number(
                panel,
                "Molecular density scale",
                () -> renderer.atmosphere().molecularScattering().x() / .0058f,
                value ->
                        editAtmosphere(
                                (float) value,
                                aerosolScale(),
                                renderer.atmosphere().aerosolHeightKm(),
                                renderer.atmosphere().absorption()),
                0,
                4,
                .1,
                true);
        number(
                panel,
                "Aerosol density scale",
                this::aerosolScale,
                value ->
                        editAtmosphere(
                                molecularScale(),
                                (float) value,
                                renderer.atmosphere().aerosolHeightKm(),
                                renderer.atmosphere().absorption()),
                0,
                10,
                .25,
                true);
        number(
                panel,
                "Aerosol scale height (km)",
                () -> renderer.atmosphere().aerosolHeightKm(),
                value ->
                        editAtmosphere(
                                molecularScale(),
                                aerosolScale(),
                                (float) value,
                                renderer.atmosphere().absorption()),
                .1,
                5,
                .05,
                true);
        vector(
                panel,
                "Absorption (1/km at ground)",
                () -> renderer.atmosphere().absorption(),
                value ->
                        editAtmosphere(
                                molecularScale(),
                                aerosolScale(),
                                renderer.atmosphere().aerosolHeightKm(),
                                value),
                0,
                .2,
                new String[] {"R", "G", "B"},
                .0001);
    }

    private float molecularScale() {
        return renderer.atmosphere().molecularScattering().x() / .0058f;
    }

    private float aerosolScale() {
        return renderer.atmosphere().aerosolScattering().x() / .004f;
    }

    private void editAtmosphere(float molecular, float aerosol, float height, Vec3 absorption) {
        renderer.atmosphere(renderer.atmosphere().edited(molecular, aerosol, height, absorption));
    }

    private void skyColor(JPanel panel, boolean top) {
        vector(
                panel,
                top ? "Sky zenith" : "Sky nadir",
                () ->
                        top
                                ? renderer.lighting().skyColors().zenith()
                                : renderer.lighting().skyColors().nadir(),
                value -> {
                    var light = renderer.lighting();
                    var sky =
                            top
                                    ? new Sky(light.skyColors().nadir(), value)
                                    : new Sky(value, light.skyColors().zenith());
                    renderer.lighting(
                            new GameLighting(
                                    light.preset(),
                                    light.skyEnabled(),
                                    light.direction(),
                                    light.sunColor(),
                                    light.intensity(),
                                    sky));
                },
                0,
                10,
                new String[] {"R", "G", "B"});
    }

    private void vector(
            JPanel panel,
            String label,
            Supplier<Vec3> read,
            Consumer<Vec3> write,
            double low,
            double high,
            String[] axes) {
        vector(panel, label, read, write, low, high, axes, .05);
    }

    private void vector(
            JPanel panel,
            String label,
            Supplier<Vec3> read,
            Consumer<Vec3> write,
            double low,
            double high,
            String[] axes,
            double step) {
        for (int index = 0; index < 3; index++) {
            final int channel = index;
            number(
                    panel,
                    label + " " + axes[index],
                    () -> component(read.get(), channel),
                    value -> {
                        var current = read.get();
                        write.accept(
                                new Vec3(
                                        channel == 0 ? (float) value : current.x(),
                                        channel == 1 ? (float) value : current.y(),
                                        channel == 2 ? (float) value : current.z()));
                    },
                    low,
                    high,
                    step,
                    true);
        }
    }

    private static float component(Vec3 value, int index) {
        return index == 0 ? value.x() : index == 1 ? value.y() : value.z();
    }

    private int selectedFog;

    private void airQuality(JPanel panel, String name) {
        heading(panel, "Atmosphere / fog resolution (experimental)");
        var quality = new javax.swing.JComboBox<>(new String[] {"Full", "Half", "Quarter"});
        quality.setName(name);
        quality.setMaximumSize(
                new java.awt.Dimension(Integer.MAX_VALUE, quality.getPreferredSize().height));
        quality.addActionListener(
                event -> {
                    if (!refreshing) {
                        int divisor = 1 << quality.getSelectedIndex();
                        renderer.configure(settings -> settings.withFogDownsample(divisor));
                    }
                });
        refreshers.add(
                () ->
                        quality.setSelectedIndex(
                                Integer.numberOfTrailingZeros(
                                        renderer.settings().fogDownsample())));
        panel.add(quality);
        panel.add(new JLabel("Shared GPU air setting; geometry stays sharp."));
        panel.add(new JLabel("CPU, lens and unsupported fog use Full."));
        panel.add(new JLabel("Half / Quarter approximate air; compare with Full."));
    }

    private JPanel fogControls() {
        var panel = page();
        airQuality(panel, "fogResolution");
        check(
                panel,
                "Shared 3D fog lighting (experimental)",
                () -> renderer.settings().fogLightingGrid(),
                enabled -> renderer.configure(settings -> settings.withFogLightingGrid(enabled)),
                true);
        panel.add(new JLabel("GPU: shares lighting across primary and bounced rays."));
        panel.add(new JLabel("Keeps exact shadows; unsupported air uses direct lighting."));
        check(
                panel,
                "Jitter fog samples (experimental)",
                () -> renderer.settings().fogJitter(),
                enabled -> renderer.configure(settings -> settings.withFogJitter(enabled)),
                true);
        panel.add(new JLabel("GPU: trades fog bands for noise; refinement averages it."));
        heading(panel, "Local air at the ruins");
        panel.add(
                new JLabel(
                        "Ruins mist sets 07:30 and pauses the clock; keeps camera and exposure."));
        button(panel, "Ruins mist (07:30)", renderer::fogPreset);
        button(
                panel,
                "View ruins (moves camera)",
                () -> navigation.pose(new Vec3(8, 4, 18), .85, .02));
        button(panel, "Reset local fog", renderer::fogReset);
        check(panel, "Local fog enabled", renderer::fogEnabled, renderer::fogEnabled, true);
        check(panel, "Second overlapping region", renderer::fogOverlap, renderer::fogOverlap, true);
        check(panel, "Reverse region order", renderer::fogReverse, renderer::fogReverse, true);
        check(panel, "Close sunlight gap", renderer::fogClosed, renderer::fogClosed, true);
        number(
                panel,
                "Screen shift Z (world units)",
                renderer::fogShift,
                renderer::fogShift,
                -10,
                10,
                .5,
                true);
        number(
                panel,
                "Edit region (1 or 2)",
                () -> selectedFog + 1,
                v -> selectedFog = (int) v - 1,
                1,
                2,
                1,
                true);
        vector(
                panel,
                "Fog center",
                () -> fog().center(),
                v -> editFog("center", v, 0),
                -10000,
                10000,
                new String[] {"X", "Y", "Z"});
        vector(
                panel,
                "Fog half extent",
                () -> fog().halfExtent(),
                v -> editFog("extent", v, 0),
                .25,
                50,
                new String[] {"X", "Y", "Z"});
        number(
                panel,
                "Fog yaw (degrees)",
                () -> fog().yawDegrees(),
                v -> editFog("yaw", null, v),
                -180,
                180,
                5,
                true);
        number(
                panel,
                "Fog density scale",
                () -> fog().density(),
                v -> editFog("density", null, v),
                0,
                4,
                .1,
                true);
        number(
                panel,
                "Fog height falloff (normalized)",
                () -> fog().heightFalloff(),
                v -> editFog("falloff", null, v),
                0,
                8,
                .25,
                true);
        number(
                panel,
                "Fog scattering direction g",
                () -> fog().anisotropy(),
                v -> editFog("g", null, v),
                -.9,
                .9,
                .05,
                true);
        number(
                panel,
                "Fog spatial variation",
                () -> fog().variation(),
                v -> editFog("variation", null, v),
                0,
                1,
                .1,
                true);
        number(
                panel,
                "Fog pattern frequency",
                () -> fog().frequency(),
                v -> editFog("frequency", null, v),
                0,
                8,
                .25,
                true);
        number(
                panel,
                "Fog edge feather fraction",
                () -> fog().feather(),
                v -> editFog("feather", null, v),
                0,
                .49,
                .05,
                true);
        vector(
                panel,
                "Fog scattering / world unit",
                () -> fog().scattering(),
                v -> editFog("scattering", v, 0),
                0,
                .2,
                new String[] {"R", "G", "B"},
                .005);
        vector(
                panel,
                "Fog absorption / world unit",
                () -> fog().absorption(),
                v -> editFog("absorption", v, 0),
                0,
                .2,
                new String[] {"R", "G", "B"},
                .001);
        return panel;
    }

    private engine.FogRegion fog() {
        return renderer.fogRegion(selectedFog);
    }

    private void editFog(String field, Vec3 vector, double value) {
        var r = fog();
        float v = (float) value;
        renderer.fogRegion(
                selectedFog,
                new engine.FogRegion(
                        field.equals("center") ? vector : r.center(),
                        field.equals("extent") ? vector : r.halfExtent(),
                        field.equals("yaw") ? v : r.yawDegrees(),
                        field.equals("scattering") ? vector : r.scattering(),
                        field.equals("absorption") ? vector : r.absorption(),
                        field.equals("density") ? v : r.density(),
                        field.equals("falloff") ? v : r.heightFalloff(),
                        field.equals("g") ? v : r.anisotropy(),
                        field.equals("variation") ? v : r.variation(),
                        field.equals("frequency") ? v : r.frequency(),
                        field.equals("feather") ? v : r.feather()));
    }

    private JPanel advancedControls() {
        var panel = page();
        heading(panel, "Sampling");
        var seed =
                new JSpinner(
                        new SpinnerNumberModel(
                                Long.valueOf(renderer.settings().seed()),
                                null,
                                null,
                                Long.valueOf(1)));
        row(panel, "Sampling seed", seed);
        seed.addChangeListener(
                event ->
                        apply(
                                () ->
                                        renderer.configure(
                                                s ->
                                                        s.withSeed(
                                                                ((Number) seed.getValue())
                                                                        .longValue()))));
        heading(panel, "Adaptive resolution (CPU and GPU)");
        var adaptive = support(engine.RenderCapabilities.Feature.ADAPTIVE_RESOLUTION);
        check(panel, "Adaptive resolution", renderer::adaptive, renderer::adaptive, adaptive);
        number(
                panel,
                "Target frame time (ms)",
                () -> renderer.settings().interactiveMillis(),
                value ->
                        renderer.configure(
                                s ->
                                        s.withInteractive(
                                                s.interactive(),
                                                value,
                                                s.minimumWidth(),
                                                s.minimumHeight())),
                1,
                1000,
                1,
                adaptive);
        number(
                panel,
                "Min width (0 = auto)",
                () -> renderer.settings().minimumWidth(),
                value ->
                        renderer.configure(
                                s ->
                                        s.withInteractive(
                                                s.interactive(),
                                                s.interactiveMillis(),
                                                (int) value,
                                                (value == 0
                                                        ? 0
                                                        : Math.max(64, s.minimumHeight())))),
                0,
                renderer.capabilities().maximumDimension(),
                16,
                adaptive);
        number(
                panel,
                "Min height (0 = auto)",
                () -> renderer.settings().minimumHeight(),
                value ->
                        renderer.configure(
                                s ->
                                        s.withInteractive(
                                                s.interactive(),
                                                s.interactiveMillis(),
                                                (value == 0 ? 0 : Math.max(64, s.minimumWidth())),
                                                (int) value)),
                0,
                renderer.capabilities().maximumDimension(),
                16,
                adaptive);
        var automaticMinimum =
                button(
                        panel,
                        "Automatic minimum resolution",
                        () ->
                                renderer.configure(
                                        s ->
                                                s.withInteractive(
                                                        s.interactive(),
                                                        s.interactiveMillis(),
                                                        0,
                                                        0)));
        automaticMinimum.setEnabled(adaptive.supported());
        if (!adaptive.supported()) {
            automaticMinimum.setToolTipText(adaptive.reason());
        }
        heading(panel, "CPU scheduling (unavailable on GPU)");
        number(
                panel,
                "Workers",
                () -> renderer.settings().workers(),
                value -> renderer.configure(s -> s.withWorkers((int) value)),
                1,
                Math.min(32, Runtime.getRuntime().availableProcessors()),
                1,
                support(engine.RenderCapabilities.Feature.WORKER_CONTROL));
        number(
                panel,
                "Tile size (pixels)",
                () -> renderer.settings().tileSize(),
                value -> renderer.configure(s -> s.withTileSize((int) value)),
                1,
                256,
                1,
                support(engine.RenderCapabilities.Feature.TILE_CONTROL));
        check(
                panel,
                "Acceleration",
                () -> renderer.settings().acceleration(),
                value -> renderer.configure(s -> s.withAcceleration(value)),
                support(engine.RenderCapabilities.Feature.OPTIONAL_ACCELERATION));
        heading(panel, "CPU temporal reconstruction");
        check(
                panel,
                "Temporal reconstruction",
                () -> renderer.settings().temporal(),
                value -> renderer.configure(s -> s.withTemporal(value, s.temporalRevision() + 1)),
                support(engine.RenderCapabilities.Feature.TEMPORAL_RECONSTRUCTION));
        panel.add(new JLabel("Finite aperture disables temporal reuse."));
        check(
                panel,
                "Temporal motion budget",
                () -> renderer.settings().temporalBudget(),
                value ->
                        renderer.configure(
                                s ->
                                        s.withTemporalBudget(
                                                value, s.motionSamples(), s.motionScale())),
                support(engine.RenderCapabilities.Feature.TEMPORAL_BUDGET));
        number(
                panel,
                "Motion samples",
                () -> renderer.settings().motionSamples(),
                value ->
                        renderer.configure(
                                s ->
                                        s.withTemporalBudget(
                                                s.temporalBudget(), (int) value, s.motionScale())),
                1,
                8,
                1,
                support(engine.RenderCapabilities.Feature.TEMPORAL_BUDGET));
        number(
                panel,
                "Motion resolution scale",
                () -> renderer.settings().motionScale(),
                value ->
                        renderer.configure(
                                s ->
                                        s.withTemporalBudget(
                                                s.temporalBudget(), s.motionSamples(), value)),
                .25,
                1,
                .05,
                support(engine.RenderCapabilities.Feature.TEMPORAL_BUDGET));
        return panel;
    }

    private void row(JPanel panel, String label, JComponent control) {
        control.setName(label);
        var row = new JPanel(new GridLayout(1, 2, 8, 0));
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
        row.add(new JLabel(label));
        row.add(control);
        row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 36));
        panel.add(row);
    }

    private engine.RenderCapabilities.Support support(engine.RenderCapabilities.Feature feature) {
        return renderer.capabilities().support(feature);
    }

    private void number(
            JPanel panel,
            String label,
            DoubleSupplier read,
            DoubleConsumer write,
            double low,
            double high,
            double step,
            boolean enabled) {
        number(
                panel,
                label,
                read,
                write,
                low,
                high,
                step,
                new engine.RenderCapabilities.Support(enabled, "Control unavailable"));
    }

    private void check(
            JPanel panel,
            String label,
            BooleanSupplier read,
            Consumer<Boolean> write,
            boolean enabled) {
        check(
                panel,
                label,
                read,
                write,
                new engine.RenderCapabilities.Support(enabled, "Control unavailable"));
    }

    private void number(
            JPanel panel,
            String label,
            DoubleSupplier read,
            DoubleConsumer write,
            double low,
            double high,
            double step,
            engine.RenderCapabilities.Support support) {
        var spinner = new JSpinner(new SpinnerNumberModel(read.getAsDouble(), low, high, step));
        if (step < .001) {
            spinner.setEditor(new JSpinner.NumberEditor(spinner, "0.####"));
        }
        spinner.setEnabled(support.supported());
        if (!support.supported()) {
            spinner.setToolTipText(support.reason());
        }
        row(panel, label, spinner);
        spinner.addChangeListener(
                event -> apply(() -> write.accept(((Number) spinner.getValue()).doubleValue())));
        refreshers.add(
                () -> {
                    var focus =
                            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                                    .getFocusOwner();
                    if (focus == null
                            || (!spinner.isFocusOwner() && !spinner.isAncestorOf(focus))) {
                        double value = read.getAsDouble();
                        if (((Number) spinner.getValue()).doubleValue() != value) {
                            spinner.setValue(value);
                        }
                    }
                });
    }

    private void check(
            JPanel panel,
            String label,
            BooleanSupplier read,
            Consumer<Boolean> write,
            engine.RenderCapabilities.Support support) {
        var box = new JCheckBox(label, read.getAsBoolean());
        box.setAlignmentX(LEFT_ALIGNMENT);
        box.setName(label);
        box.setEnabled(support.supported());
        if (!support.supported()) {
            box.setToolTipText(support.reason());
        }
        box.addActionListener(event -> apply(() -> write.accept(box.isSelected())));
        panel.add(box);
        refreshers.add(() -> box.setSelected(read.getAsBoolean()));
    }

    private JButton button(JPanel panel, String label, Runnable action) {
        var button = new JButton(label);
        button.setAlignmentX(LEFT_ALIGNMENT);
        button.setName(label);
        button.addActionListener(event -> apply(action));
        panel.add(button);
        return button;
    }

    private void apply(Runnable action) {
        if (refreshing) {
            return;
        }
        try {
            action.run();
            message.setForeground(new Color(180, 212, 242));
            message.setText("Applied. Click the view to fly; Esc opens settings.");
        } catch (IllegalArgumentException failure) {
            message.setForeground(new Color(255, 160, 140));
            message.setText(failure.getMessage());
        }
        refresh();
    }

    public void refresh() {
        refreshing = true;
        try {
            for (var refresh : refreshers) {
                refresh.run();
            }
        } finally {
            refreshing = false;
        }
    }
}
