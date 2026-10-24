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
        tabs.addTab("Advanced", scroll(advancedControls()));
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

    private JPanel renderControls() {
        var panel = page();
        heading(
                panel,
                renderer.adaptiveSupported() ? "CPU renderer" : "GPU renderer (fixed resolution)");
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
        number(
                panel,
                "Width (pixels)",
                () -> navigation.view().width(),
                value -> navigation.fixedResolution((int) value, navigation.view().height()),
                64,
                1600,
                16,
                true);
        number(
                panel,
                "Height (pixels)",
                () -> navigation.view().height(),
                value -> navigation.fixedResolution(navigation.view().width(), (int) value),
                64,
                1600,
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
                0,
                renderer.adaptiveSupported() ? 32 : 2,
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
                value -> navigation.optics(navigation.aperture(), (float) value),
                .01,
                10000,
                .25,
                true);
        panel.add(new JLabel("Manual focus; larger aperture increases blur."));
        heading(panel, "Navigation");
        number(
                panel,
                "Flight speed (units/s)",
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
        heading(panel, "Sun presets");
        var presets = new JPanel(new FlowLayout(FlowLayout.LEFT));
        presets.setAlignmentX(LEFT_ALIGNMENT);
        for (var preset : GameLighting.Preset.values()) {
            button(
                    presets,
                    preset.name(),
                    () -> renderer.lighting(renderer.lighting().withPreset(preset)));
        }
        panel.add(presets);
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
                    .05,
                    true);
        }
    }

    private static float component(Vec3 value, int index) {
        return index == 0 ? value.x() : index == 1 ? value.y() : value.z();
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
        heading(panel, "CPU scheduling (unavailable on GPU)");
        boolean cpu = renderer.adaptiveSupported();
        check(panel, "Adaptive resolution", renderer::adaptive, renderer::adaptive, cpu);
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
                cpu);
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
                1600,
                16,
                cpu);
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
                1600,
                16,
                cpu);
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
                                                        0)))
                .setEnabled(cpu);
        number(
                panel,
                "Workers",
                () -> renderer.settings().workers(),
                value -> renderer.configure(s -> s.withWorkers((int) value)),
                1,
                Math.min(32, Runtime.getRuntime().availableProcessors()),
                1,
                cpu);
        number(
                panel,
                "Tile size (pixels)",
                () -> renderer.settings().tileSize(),
                value -> renderer.configure(s -> s.withTileSize((int) value)),
                1,
                256,
                1,
                cpu);
        check(
                panel,
                "Acceleration",
                () -> renderer.settings().acceleration(),
                value -> renderer.configure(s -> s.withAcceleration(value)),
                cpu);
        heading(panel, "CPU temporal reconstruction");
        check(
                panel,
                "Temporal reconstruction",
                () -> renderer.settings().temporal(),
                value -> renderer.configure(s -> s.withTemporal(value, s.temporalRevision() + 1)),
                cpu);
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
                cpu);
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
                cpu);
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
                cpu);
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

    private void number(
            JPanel panel,
            String label,
            DoubleSupplier read,
            DoubleConsumer write,
            double low,
            double high,
            double step,
            boolean enabled) {
        var spinner = new JSpinner(new SpinnerNumberModel(read.getAsDouble(), low, high, step));
        if (step < .001) {
            spinner.setEditor(new JSpinner.NumberEditor(spinner, "0.####"));
        }
        spinner.setEnabled(enabled);
        if (!enabled) {
            spinner.setToolTipText("Not supported by the current GPU backend");
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
            boolean enabled) {
        var box = new JCheckBox(label, read.getAsBoolean());
        box.setAlignmentX(LEFT_ALIGNMENT);
        box.setName(label);
        box.setEnabled(enabled);
        if (!enabled) {
            box.setToolTipText("Not supported by the current GPU backend");
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
