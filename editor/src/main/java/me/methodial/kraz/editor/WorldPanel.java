package me.methodial.kraz.editor;

import me.methodial.apothecary.node.Node;
import me.methodial.apothecary.node.Region;
import me.methodial.apothecary.widget.SegmentedControl;
import me.methodial.apothecary.widget.Slider;
import me.methodial.apothecary.widget.Switch;
import me.methodial.kraz.world.WorldProjection;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static me.methodial.apothecary.ui.UI.*;

/**
 * Showing the scene in the world: where (at your feet, where you look, or exact coordinates), facing, tilt,
 * scale, opacity, which point of the scene stands on that spot, and how it is lit. The projection shows the scene
 * being edited, live, and stays when the editor closes; {@code /kraz edit} brings the editor back.
 */
final class WorldPanel {
    private static final float[] SCALES = {1 / 16f, 1 / 8f, 1 / 4f, 1 / 2f, 1, 2, 4};
    private static final String[] SCALE_NAMES = {"1/16", "1/8", "1/4", "1/2", "1", "2", "4"};

    private final EditorModel model;
    private final Runnable viewInWorld;
    private final List<Runnable> sync = new ArrayList<>();
    private Region body;
    private boolean projected;

    WorldPanel(EditorModel model, Runnable viewInWorld) {
        this.model = model;
        this.viewInWorld = viewInWorld;
    }

    /** The panel's content; the editor puts it in a window. */
    Node content() {
        body = new Region(this::fill);
        body.cls("kz-list");
        return body;
    }

    /** Runs each frame while the panel is open. */
    void frame() {
        if (body == null) {
            return; // shown this frame; built on the next
        }
        boolean now = model.projection() != null;
        if (now != projected) {
            projected = now;
            body.invalidate();
        }
        List.copyOf(sync).forEach(Runnable::run);
    }

    private void fill(Region r) {
        sync.clear();
        if (Minecraft.getInstance().level == null) {
            r.add(text("Join a world to show the scene in it.").cls("kz-muted"));
            return;
        }
        r.add(row(s(x -> x.gap(2)),
                EditorUi.label("At my feet", "Stand the scene where you are standing", () -> model.projectAt(EditorModel.playerFeet())).style(x -> x.grow().basis(0)),
                EditorUi.label("Where I look", "Stand the scene on the block your crosshair points at", () -> model.projectAt(EditorModel.crosshair())).style(x -> x.grow().basis(0))));
        WorldProjection p = model.projection();
        if (p == null) {
            r.add(text("The scene is not in the world yet. It shows live: what you edit here changes there.").cls("kz-muted"));
            return;
        }

        r.add(EditorUi.heading("Position"));
        r.add(row(s(x -> x.gap(2)),
                axis("x", () -> (float) p.origin().x, v -> p.setOrigin(new Vec3(v, p.origin().y, p.origin().z))),
                axis("y", () -> (float) p.origin().y, v -> p.setOrigin(new Vec3(p.origin().x, v, p.origin().z))),
                axis("z", () -> (float) p.origin().z, v -> p.setOrigin(new Vec3(p.origin().x, p.origin().y, v)))));
        SegmentedControl<WorldProjection.Anchor> anchor = new SegmentedControl<WorldProjection.Anchor>()
                .option(WorldProjection.Anchor.BOTTOM_CENTER, "Stand").option(WorldProjection.Anchor.CENTER, "Centre").option(WorldProjection.Anchor.ORIGIN, "1:1 grid");
        anchor.setQuietly(p.anchor());
        anchor.onChange(p::setAnchor);
        anchor.tooltip("Which point of the scene sits on the spot: its bottom, its middle, or its own 0,0,0 (to build from it block for block)");
        anchor.cls("kz-wide");
        r.add(anchor);

        r.add(EditorUi.heading("Facing"));
        r.add(row(s(x -> x.itemsCenter().gap(2)),
                text("Yaw").cls("kz-prop-label"),
                EditorUi.icon("kz-undo", "Turn 90° left", () -> p.setYaw(Math.round(p.yaw() - 90))),
                EditorUi.number(p::yaw, p::setYaw, 15, () -> { }, sync),
                EditorUi.icon("kz-redo", "Turn 90° right", () -> p.setYaw(Math.round(p.yaw() + 90)))));
        r.add(row(s(x -> x.itemsCenter().gap(2)),
                text("Tilt").cls("kz-prop-label"), EditorUi.number(p::pitch, p::setPitch, 15, () -> { }, sync)));

        r.add(EditorUi.heading("Scale"));
        Node presets = row(s(x -> x.gap(1).wrap()));
        for (int i = 0; i < SCALES.length; i++) {
            float value = SCALES[i];
            var b = EditorUi.label(SCALE_NAMES[i], value + " blocks per block", () -> p.setScale(value));
            sync.add(() -> b.toggleCls("on", Math.abs(p.scale() - value) < 1e-4f));
            presets.add(b);
        }
        r.add(presets);
        r.add(row(s(x -> x.itemsCenter().gap(2)),
                text("Exact").cls("kz-prop-label"), EditorUi.number(p::scale, p::setScale, 0.0625f, () -> { }, sync)));

        r.add(EditorUi.heading("Look"));
        Slider opacity = new Slider(0, 1, p.alpha());
        opacity.step(0.05f);
        opacity.showValue(v -> Math.round(v * 100) + "%");
        opacity.onChange(p::setAlpha);
        opacity.cls("kz-wide");
        r.add(row(s(x -> x.itemsCenter().gap(4)), text("Opacity").cls("kz-prop-label"), opacity.style(x -> x.w(110))));
        r.add(toggle("Animate", "Play the scene's animation", p.animate(), p::setAnimate));
        r.add(toggle("Own lighting", "Keep the scene lit as in the editor, day or night, like a hologram", p.sceneLighting(), p::setSceneLighting));
        r.add(toggle("Show outline", "Draw the scene's bounds around it", p.showBounds(), p::setShowBounds));
        r.add(toggle("Visible", "Hide it without losing its placement", p.visible(), p::setVisible));

        r.add(row(s(x -> x.gap(2).mt(4)),
                EditorUi.label("View in world", "Close the editor to look at it. /kraz edit brings the editor back", viewInWorld).style(x -> x.grow().basis(0)),
                EditorUi.label("Remove", "Take the scene out of the world", model::stopProjecting).cls("danger").style(x -> x.grow().basis(0))));
        r.add(text(String.format(Locale.ROOT, "Also: /kraz project <name> [pos] [scale] [yaw]")).cls("kz-muted"));
    }

    private Node axis(String name, java.util.function.Supplier<Float> get, java.util.function.Consumer<Float> set) {
        var field = EditorUi.number(get, set, 1, () -> { }, sync);
        field.style(x -> x.grow().basis(0).minW(0).w(me.methodial.apothecary.style.Length.AUTO));
        return row(s(x -> x.itemsCenter().gap(1).grow().basis(0).minW(0)), text(name).cls("kz-axis", name), field);
    }

    private static Node toggle(String label, String tooltip, boolean on, java.util.function.Consumer<Boolean> set) {
        Switch sw = new Switch(label, on);
        sw.onChange(set::accept);
        sw.tooltip(tooltip);
        return sw;
    }
}
