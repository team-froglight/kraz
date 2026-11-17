package me.methodial.kraz.gui;

import me.methodial.kraz.camera.SceneCamera;
import me.methodial.kraz.render.RenderSettings;
import me.methodial.kraz.render.SceneCanvas;
import me.methodial.kraz.scene.Scene;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

/**
 * Drop-in widget that shows a {@link Scene} in any screen: add it with {@code addRenderableWidget} and call
 * {@link #close()} from the screen's {@code removed()}.
 *
 * <p>Plays the scene's timeline in real time, and (when {@link #setInteractive interactive}) lets the user orbit
 * with the left mouse button, pan with the right or middle button and zoom with the scroll wheel.
 */
public class SceneWidget extends AbstractWidget implements AutoCloseable {
    private final Scene scene;
    private final SceneCanvas canvas;
    private boolean interactive = true;
    private boolean autoPlay = true;
    private int dragButton = -1;
    private long lastFrameMillis = -1;

    public SceneWidget(int x, int y, int width, int height, Scene scene) {
        super(x, y, width, height, Component.empty());
        this.scene = scene;
        this.canvas = new SceneCanvas(scene);
    }

    public Scene scene() {
        return scene;
    }

    public SceneCanvas canvas() {
        return canvas;
    }

    public SceneWidget setSettings(RenderSettings settings) {
        canvas.setSettings(settings);
        return this;
    }

    /** Whether mouse input moves the camera. */
    public SceneWidget setInteractive(boolean interactive) {
        this.interactive = interactive;
        return this;
    }

    /** Whether the widget advances the scene's timeline itself. Turn off to drive the timeline yourself. */
    public SceneWidget setAutoPlay(boolean autoPlay) {
        this.autoPlay = autoPlay;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        if (autoPlay && lastFrameMillis >= 0) {
            // Clamp so a hitch or a paused game does not make the animation jump.
            scene.timeline().advance(Math.min(now - lastFrameMillis, 250) / 50.0f);
        }
        lastFrameMillis = now;
        canvas.draw(graphics, getX(), getY(), getWidth(), getHeight(), partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (interactive && active && visible && isMouseOver(mouseX, mouseY) && button >= 0 && button <= 2) {
            dragButton = button;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == dragButton) {
            dragButton = -1;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!interactive || button != dragButton) {
            return false;
        }
        SceneCamera camera = scene.camera();
        if (button == 0) {
            camera.orbit((float) -dragX * 0.6f, (float) dragY * 0.6f);
        } else {
            float blocksPerPixel = camera.orthographic()
                    ? camera.orthoHeight() / getHeight()
                    : 2.0f * camera.distance() * (float) Math.tan(Math.toRadians(camera.fov()) * 0.5) / getHeight();
            camera.pan((float) -dragX * blocksPerPixel, (float) dragY * blocksPerPixel);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!interactive || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        scene.camera().zoom((float) Math.pow(0.9, scrollY));
        return true;
    }

    @Override
    public void playDownSound(SoundManager handler) {
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.translatable("kraz.narration.scene"));
    }

    /** Frees the widget's GPU resources. Call from the owning screen's {@code removed()}. */
    @Override
    public void close() {
        Minecraft.getInstance().execute(canvas::close);
    }
}
