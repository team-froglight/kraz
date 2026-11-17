package me.methodial.kraz.render.shaded;

import com.mojang.blaze3d.systems.RenderSystem;
import me.methodial.kraz.camera.SceneCamera;
import me.methodial.kraz.render.SceneRenderer;
import me.methodial.kraz.scene.Scene;
import me.methodial.kraz.scene.ScenePart;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws a Kraz {@link Scene} through the {@link ShadedScene} pipeline: sun shadows, ambient occlusion, sky and
 * clouds, bloom, tonemapping, shaded water and lava, and glowing light sources. The counterpart of
 * {@code SceneCanvas} for when a scene should look like the game under a shader pack rather than like vanilla.
 *
 * <p>The scene's own camera is used. Everything about the look (sun direction, time, clouds, bloom, fog) is set
 * on {@link #shading()}. For best results give the scene an environment with face shading off
 * ({@code scene.environment().withShade(false)}), since the pipeline does its own lighting.
 *
 * <p>Owns GPU resources: {@link #close()} it when done. Render thread only.
 */
public final class ShadedSceneCanvas implements AutoCloseable {
    /** Orthographic scene cameras are shown through a long lens this narrow, which is visually the same thing. */
    private static final float ORTHO_FOV = 8.0f;
    private static final int MAX_LIGHTS = 128;

    private final Scene scene;
    private final SceneRenderer renderer;
    private final ShadedScene shaded = new ShadedScene();
    private boolean autoLights = true;
    private boolean autoShadows = true;
    private long lightsKey = Long.MIN_VALUE;
    private final float[] lights = new float[MAX_LIGHTS * 7];
    private int lightCount;
    private float partialTick;
    private final Vector3f cameraPosition = new Vector3f();
    private final ShadedScene.Content content = this::drawPass;

    public ShadedSceneCanvas(Scene scene) {
        this.scene = scene;
        this.renderer = new SceneRenderer(scene);
        this.renderer.setMaterialMasks(true);
        this.shaded.materials(true);
    }

    public Scene scene() {
        return scene;
    }

    /** The pipeline, for tuning the look: {@code sun}, {@code time}, {@code clouds}, {@code bloom}, {@code fog}… */
    public ShadedScene shading() {
        return shaded;
    }

    /** Whether light-emitting blocks in the scene become point lights automatically (default on, up to 128). */
    public ShadedSceneCanvas setAutoLights(boolean on) {
        autoLights = on;
        lightsKey = Long.MIN_VALUE;
        return this;
    }

    /** Whether the shadowed region follows the scene's bounds automatically (default on). */
    public ShadedSceneCanvas setAutoShadows(boolean on) {
        autoShadows = on;
        return this;
    }

    public boolean busy() {
        return renderer.busy();
    }

    /** Draws the scene into a rectangle of the GUI, in GUI units, on {@code graphics}' current pose. */
    public void draw(GuiGraphics graphics, float x, float y, float width, float height, float partialTick) {
        RenderSystem.assertOnRenderThread();
        if (width < 1 || height < 1) {
            return;
        }
        this.partialTick = partialTick;
        SceneCamera camera = scene.camera();
        float fov = camera.orthographic() ? ORTHO_FOV : camera.fov();
        float distance = camera.orthographic()
                ? camera.orthoHeight() * 0.5f / (float) Math.tan(Math.toRadians(ORTHO_FOV) * 0.5)
                : camera.distance();
        // ShadedScene turns the other way around the vertical axis than SceneCamera does.
        shaded.camera(camera.target(), -camera.yaw(), camera.pitch(), distance, fov);

        BoundingBox bounds = scene.bounds();
        float radius = 8;
        Vector3f center = new Vector3f(camera.target());
        if (bounds != null) {
            center.set((bounds.minX() + bounds.maxX() + 1) * 0.5f, (bounds.minY() + bounds.maxY() + 1) * 0.5f, (bounds.minZ() + bounds.maxZ() + 1) * 0.5f);
            radius = 0.5f * (float) Math.sqrt((double) bounds.getXSpan() * bounds.getXSpan() + (double) bounds.getYSpan() * bounds.getYSpan()
                    + (double) bounds.getZSpan() * bounds.getZSpan()) + 1.5f;
        }
        if (autoShadows) {
            shaded.shadows(center, radius, radius > 48 ? 2048 : 1024);
            float reach = distance + radius * 2;
            shaded.fog(reach * 4, reach * 12, Math.max(256, reach * 16));
        }
        if (autoLights) {
            collectLights();
        }

        double yaw = Math.toRadians(camera.yaw()), pitch = Math.toRadians(camera.pitch());
        cameraPosition.set(camera.target()).add((float) (Math.sin(yaw) * Math.cos(pitch)) * distance, (float) Math.sin(pitch) * distance,
                (float) (Math.cos(yaw) * Math.cos(pitch)) * distance);
        graphics.flush();
        renderer.prepare(cameraPosition, false);
        shaded.render(graphics, x, y, width, height, content);
    }

    private void drawPass(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers, ShadedScene.Pass pass) {
        Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrix());
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        float time = scene.timeline().time();
        switch (pass) {
            case SHADOW -> renderer.draw(view, projection, time, partialTick, SceneRenderer.Light.UNLIT, false);
            case SCENE -> renderer.draw(view, projection, time, partialTick, SceneRenderer.Light.UNLIT, true);
            case MATERIAL -> renderer.drawMaterials(view, projection);
            case GLOW -> {
            }
        }
    }

    /** Turns the scene's light-emitting blocks into point lights; redone only when the scene's content changes. */
    private void collectLights() {
        long key = 17;
        for (ScenePart part : scene.parts()) {
            key = key * 31 + part.version();
        }
        if (key != lightsKey) {
            lightsKey = key;
            lightCount = 0;
            Vector3f position = new Vector3f();
            net.minecraft.core.BlockPos.MutableBlockPos cursor = new net.minecraft.core.BlockPos.MutableBlockPos();
            for (ScenePart part : scene.parts()) {
                if (!part.visible()) {
                    continue;
                }
                Matrix4f matrix = part.matrix();
                part.store().forEachBlock((bx, by, bz, state) -> {
                    int emission = emission(part, state, cursor.set(bx, by, bz));
                    if (emission <= 0 || lightCount >= MAX_LIGHTS) {
                        return;
                    }
                    matrix.transformPosition(position.set(bx + 0.5f, by + 0.5f, bz + 0.5f));
                    int i = lightCount++ * 7;
                    lights[i] = position.x;
                    lights[i + 1] = position.y;
                    lights[i + 2] = position.z;
                    lights[i + 3] = emission * 0.6f;
                    float strength = emission / 15.0f;
                    // Warm, like torchlight; lava and fire sit in the same family.
                    lights[i + 4] = 1.0f * strength;
                    lights[i + 5] = 0.72f * strength;
                    lights[i + 6] = 0.42f * strength;
                });
            }
        }
        shaded.clearLights();
        for (int n = 0; n < lightCount; n++) {
            int i = n * 7;
            shaded.addLight(lights[i], lights[i + 1], lights[i + 2], lights[i + 3], lights[i + 4], lights[i + 5], lights[i + 6]);
        }
    }

    private static int emission(ScenePart part, BlockState state, net.minecraft.core.BlockPos pos) {
        return state.getLightEmission(part.store(), pos);
    }

    @Override
    public void close() {
        renderer.close();
        shaded.close();
    }
}
