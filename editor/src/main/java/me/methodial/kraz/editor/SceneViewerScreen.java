package me.methodial.kraz.editor;

import me.methodial.apothecary.geom.Rect;
import me.methodial.apothecary.mc.node.SceneView;
import me.methodial.apothecary.mc.screen.UIScreen;
import me.methodial.apothecary.node.Node;
import me.methodial.apothecary.node.UIRoot;
import me.methodial.apothecary.render.Canvas;
import me.methodial.apothecary.style.Color;
import me.methodial.apothecary.widget.Button;
import me.methodial.apothecary.widget.Slider;
import me.methodial.apothecary.widget.Text;
import me.methodial.kraz.anim.Timeline;
import me.methodial.kraz.export.SceneExporter;
import me.methodial.kraz.render.RenderSettings;
import me.methodial.kraz.scene.Scene;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static me.methodial.apothecary.ui.UI.*;

/**
 * Full-screen viewer for a scene, built with Apothecary: orbit, pan and zoom, timeline transport, a toggle for
 * the shader pipeline and one-click image and animation export. Open with {@code /kraz view <name>}.
 */
public class SceneViewerScreen extends UIScreen {
    private final Scene scene;
    private final String exportName;
    private final Text status = new Text("");
    private Button play;
    private Slider time;
    private Node stage;
    private boolean shaded;
    private boolean scrubbing;

    public SceneViewerScreen(Scene scene, String exportName, @Nullable Screen parent) {
        super(Component.translatable("kraz.viewer.title"));
        this.scene = scene;
        this.exportName = exportName;
        parent(parent);
        backdrop(Backdrop.NONE);
        pauses(false);
    }

    public Scene scene() {
        return scene;
    }

    @Override
    protected void build(UIRoot root) {
        Timeline timeline = scene.timeline();
        play = button("Pause", () -> {
            if (!timeline.playing() && !timeline.loop() && timeline.time() >= timeline.length()) {
                timeline.seek(0);
            }
            timeline.setPlaying(!timeline.playing());
        });
        time = new Slider(0, 1, 0);
        time.onChange(value -> {
            if (!scrubbing) {
                timeline.setPlaying(false);
                timeline.seek(value * timeline.length());
            }
        });
        time.disabled(timeline.length() <= 0);
        Button gif = button("GIF", () -> report(SceneExporter.exportGif(scene, SceneExporter.exportPath(exportName, "gif"), 512, 512, 20, RenderSettings.HIGH_QUALITY)));
        gif.disabled(timeline.length() <= 0);

        stage = box(s(x -> x.grow().fullW()));
        showStage();
        root.setContent(col(s(x -> x.full().bg(Color.hex("#1B1D23"))),
                stage,
                row(s(x -> x.gap(6).pad(6).itemsCenter().bg(Color.hex("#101114"))),
                        play,
                        button("Projection", () -> scene.camera().setOrthographic(!scene.camera().orthographic())),
                        button("Shaders", () -> {
                            shaded = !shaded;
                            showStage();
                        }).ghost(),
                        time.style(x -> x.grow()),
                        status.cls("muted"),
                        button("PNG", () -> report(SceneExporter.exportPng(scene, SceneExporter.exportPath(exportName, "png"), 2048, 2048, RenderSettings.HIGH_QUALITY))),
                        gif)));
    }

    /** (Re)creates the scene view; switching between the plain and the shaded renderer needs a new node. */
    private void showStage() {
        SceneView view = new SceneView(scene) {
            @Override
            protected void paintContent(Canvas c, Rect r) {
                super.paintContent(c, r);
                sync();
            }
        };
        view.interactive().style(x -> x.full());
        if (shaded) {
            view.shaded();
        }
        stage.setChildren(view);
    }

    /** Runs once a frame, after the scene advanced: keeps the transport controls in step with the timeline. */
    private void sync() {
        Timeline timeline = scene.timeline();
        play.text(timeline.playing() ? "Pause" : "Play");
        float length = timeline.length();
        if (length > 0 && !time.isFocused()) {
            scrubbing = true;
            time.setQuietly(Math.clamp(timeline.time() / length, 0f, 1f));
            scrubbing = false;
        }
        if (status.text().isEmpty() || status.text().endsWith("s")) {
            status.text(String.format(Locale.ROOT, "%.1fs / %.1fs", timeline.time() / 20f, length / 20f));
        }
    }

    private void report(CompletableFuture<Path> export) {
        status.text("Exporting…");
        export.whenCompleteAsync((path, error) ->
                status.text(error == null ? "Saved " + path.getFileName() : "Export failed: " + error.getMessage()), minecraft);
    }
}
