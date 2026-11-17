package me.methodial.kraz.render;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import me.methodial.kraz.KrazClient;
import me.methodial.kraz.scene.Scene;
import me.methodial.kraz.scene.ScenePart;
import me.methodial.kraz.scene.element.SceneElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Draws a {@link Scene} into whatever framebuffer is currently bound, given explicit view and projection matrices.
 * This is the low-level entry point: use it to draw a scene inside the world or into your own render target.
 * For "just show it in a GUI" or "give me an image", use {@link SceneCanvas}.
 *
 * <p>Owns the GPU meshes for the scene, so it must be {@link #close() closed} when no longer needed.
 */
public final class SceneRenderer implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Scene scene;
    private final Map<ScenePart, PartMesh> meshes = new IdentityHashMap<>();
    private final Set<Object> brokenRenderers = new HashSet<>();
    private final Matrix4f partModelView = new Matrix4f();
    private final Matrix4f clip = new Matrix4f();
    private final Matrix4f inverse = new Matrix4f();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private static final Matrix4f IDENTITY = new Matrix4f();
    private boolean meshesChanged;
    private boolean materialMasks;
    private float alpha = 1;
    private static float drawingAlpha = 1;

    public SceneRenderer(Scene scene) {
        this.scene = scene;
    }

    public Scene scene() {
        return scene;
    }

    /**
     * Brings GPU state in line with the scene: creates block entities, schedules section rebuilds and uploads
     * finished ones. Call once per frame before {@link #draw}.
     *
     * @param cameraPosition camera position in scene coordinates
     * @param wait           block until all geometry is ready, for deterministic output
     */
    public void prepare(Vector3f cameraPosition, boolean wait) {
        RenderSystem.assertOnRenderThread();
        meshesChanged = false;
        meshes.keySet().removeIf(part -> {
            if (scene.parts().contains(part)) {
                return false;
            }
            meshes.get(part).close();
            return true;
        });
        // Vanilla only applies the Fancy/Fast leaves setting to block layers when a world loads; until then (title
        // screen, menus) leaves would mesh as opaque blocks with black holes. Apply the player's setting ourselves,
        // and rebuild when it changes, since it decides which layer leaves are baked into.
        boolean fancy = Minecraft.useFancyGraphics();
        if (Minecraft.getInstance().level == null) {
            net.minecraft.client.renderer.ItemBlockRenderTypes.setFancy(fancy);
        }
        int generation = KrazClient.resourceGeneration() * 2 + (fancy ? 1 : 0);
        Vector3f local = new Vector3f();
        for (ScenePart part : scene.parts()) {
            part.syncBlockEntities();
            PartMesh mesh = meshes.computeIfAbsent(part, PartMesh::new);
            part.matrix().invert(inverse).transformPosition(cameraPosition, local);
            mesh.setMaterials(materialMasks);
            mesh.update(local, wait, generation);
            meshesChanged |= mesh.changed();
        }
    }

    /** Whether geometry is still being built in the background. */
    public boolean busy() {
        for (PartMesh mesh : meshes.values()) {
            if (mesh.busy()) {
                return true;
            }
        }
        return false;
    }

    /** Whether the last {@link #prepare} changed any uploaded geometry. */
    public boolean meshesChanged() {
        return meshesChanged;
    }

    /**
     * Draws the scene.
     *
     * @param view          scene-to-eye matrix
     * @param projection    eye-to-clip matrix
     * @param time          scene time in ticks, passed to elements
     * @param partialTick   game partial tick, passed to block entity renderers
     * @param standalone    the scene is drawn on its own into a private target (as opposed to inside the
     *                      world): use the scene's own lightmap and light directions, and keep the alpha
     *                      channel meaningful so the image can be composited or saved with transparency
     */
    public void draw(Matrix4f view, Matrix4f projection, float time, float partialTick, boolean standalone) {
        draw(view, projection, time, partialTick, standalone ? Light.SCENE : Light.WORLD, true);
    }

    /** Where a draw takes its lighting from. */
    public enum Light {
        /** The scene's own lightmap and light directions; alpha is kept meaningful for compositing. */
        SCENE,
        /** Whatever the surrounding world render has set up. */
        WORLD,
        /** No lighting at all: surfaces in their own colours, for pipelines that light the picture themselves. */
        UNLIT
    }

    /** Bakes (or stops baking) the water, lava and light-source masks that {@link #drawMaterials} draws. */
    public void setMaterialMasks(boolean on) {
        materialMasks = on;
    }

    /**
     * Draws the scene.
     *
     * @param translucent also draw translucent block layers; off for depth-only passes such as shadow maps
     */
    public void draw(Matrix4f view, Matrix4f projection, float time, float partialTick, Light light, boolean translucent) {
        RenderSystem.assertOnRenderThread();
        Minecraft mc = Minecraft.getInstance();
        float previousAlpha = drawingAlpha;
        drawingAlpha = alpha;

        float fogStart = RenderSystem.getShaderFogStart();
        int previousLightmap = RenderHooks.lightmapOverride;
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(view);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
        try {
            FogRenderer.setupNoFog();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            if (light == Light.SCENE) {
                RenderHooks.lightmapOverride = SceneLightmap.id(scene.environment());
                Lighting.setupLevel();
            } else if (light == Light.UNLIT) {
                RenderHooks.lightmapOverride = SceneLightmap.fullBright();
            }

            List<RenderType> layers = PartMesh.LAYERS;
            int translucentStart = layers.indexOf(RenderType.translucent());
            if (translucentStart < 0) {
                translucentStart = layers.size();
            }
            for (int i = 0; i < translucentStart; i++) {
                drawLayer(i, PartMesh.LAYERS.get(i), view, projection, Pass.OPAQUE, null);
            }
            if (light == Light.SCENE && RenderHooks.targetOverride != null) {
                forceOpaqueAlpha(projection);
            }
            for (int i = 0; i < translucentStart; i++) {
                drawLayer(i, PartMesh.LAYERS.get(i), view, projection, Pass.FADING, null);
            }
            drawDynamic(mc, time, partialTick);
            for (int i = translucentStart; translucent && i < layers.size(); i++) {
                drawLayer(i, PartMesh.LAYERS.get(i), view, projection, Pass.ALL, null);
            }
        } finally {
            drawingAlpha = previousAlpha;
            RenderHooks.lightmapOverride = previousLightmap;
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.restoreProjectionMatrix();
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /**
     * Draws the material masks over an already drawn scene: water in red, lava in green, light-emitting blocks in
     * blue, each still modulated by its texture. Needs {@link #setMaterialMasks}; the caller sets blending and
     * depth state.
     */
    public void drawMaterials(Matrix4f view, Matrix4f projection) {
        RenderSystem.assertOnRenderThread();
        int previousLightmap = RenderHooks.lightmapOverride;
        float fogStart = RenderSystem.getShaderFogStart();
        try {
            FogRenderer.setupNoFog();
            RenderHooks.lightmapOverride = SceneLightmap.fullBright();
            drawLayer(PartMesh.WATER, RenderType.cutout(), view, projection, Pass.ALL, MASK_WATER);
            drawLayer(PartMesh.LAVA, RenderType.cutout(), view, projection, Pass.ALL, MASK_LAVA);
            drawLayer(PartMesh.EMISSIVE, RenderType.cutout(), view, projection, Pass.ALL, MASK_LIGHT);
        } finally {
            RenderHooks.lightmapOverride = previousLightmap;
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderColor(1, 1, 1, 1);
        }
    }

    private static final float[] MASK_WATER = {1, 0, 0}, MASK_LAVA = {0, 1, 0}, MASK_LIGHT = {0, 0, 1};

    /** Which parts a layer pass covers. */
    private enum Pass {
        /** Fully opaque parts only. */
        OPAQUE,
        /** Parts that are fading (alpha below 1) and so need blending even on opaque layers. */
        FADING,
        ALL;

        boolean includes(ScenePart part) {
            if (!part.visible() || alphaOf(part) <= 0) {
                return false;
            }
            return this == ALL || (this == FADING) == (alphaOf(part) < 1.0f);
        }
    }

    /**
     * Sets alpha to 1 on every pixel covered by opaque geometry so far.
     *
     * <p>Vanilla's opaque layers write whatever alpha the texture has, which nobody notices on the game's
     * framebuffer. Here it would punch holes into exported images wherever an opaque texture has transparent
     * texels, most visibly leaves under Fast graphics. A full-screen quad at the far plane with a reversed depth
     * test touches exactly the pixels something was drawn to.
     */
    private void forceOpaqueAlpha(Matrix4f projection) {
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(IDENTITY, VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.setShader(GameRenderer::getPositionShader);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_GREATER);
        RenderSystem.depthMask(false);
        RenderSystem.colorMask(false, false, false, true);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        builder.addVertex(-1, -1, 1);
        builder.addVertex(1, -1, 1);
        builder.addVertex(1, 1, 1);
        builder.addVertex(-1, 1, 1);
        BufferUploader.drawWithShader(builder.buildOrThrow());
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.enableCull();
        modelView.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
    }

    /** @param tint replaces the colour modulator (used by the material masks), or null for white */
    private void drawLayer(int layer, RenderType type, Matrix4f view, Matrix4f projection, Pass pass, float[] tint) {
        boolean any = false;
        for (ScenePart part : scene.parts()) {
            PartMesh mesh = meshes.get(part);
            if (mesh != null && pass.includes(part) && mesh.hasLayer(layer)) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        type.setupRenderState();
        if (pass == Pass.FADING) {
            // Opaque layers do not blend on their own.
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }
        ShaderInstance shader = RenderSystem.getShader();
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, view, projection, Minecraft.getInstance().getWindow());
        shader.apply();
        Uniform chunkOffset = shader.CHUNK_OFFSET;
        for (ScenePart part : scene.parts()) {
            PartMesh mesh = meshes.get(part);
            if (mesh == null || !pass.includes(part) || !mesh.hasLayer(layer)) {
                continue;
            }
            partModelView.set(view).mul(part.matrix());
            if (shader.MODEL_VIEW_MATRIX != null) {
                shader.MODEL_VIEW_MATRIX.set(partModelView);
                shader.MODEL_VIEW_MATRIX.upload();
            }
            if (shader.COLOR_MODULATOR != null) {
                if (tint != null) {
                    shader.COLOR_MODULATOR.set(tint[0], tint[1], tint[2], 1.0f);
                } else {
                    shader.COLOR_MODULATOR.set(1.0f, 1.0f, 1.0f, alphaOf(part));
                }
                shader.COLOR_MODULATOR.upload();
            }
            frustum.set(clip.set(projection).mul(partModelView));
            mesh.draw(layer, chunkOffset, frustum);
        }
        if (chunkOffset != null) {
            chunkOffset.set(0.0f, 0.0f, 0.0f);
        }
        shader.clear();
        VertexBuffer.unbind();
        type.clearRenderState();
        if (pass == Pass.FADING) {
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void drawDynamic(Minecraft mc, float time, float partialTick) {
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        PoseStack pose = new PoseStack();
        for (ScenePart part : scene.parts()) {
            if (!part.visible() || alphaOf(part) <= 0) {
                continue;
            }
            var blockEntities = part.store().blockEntities();
            if (blockEntities.isEmpty() && part.elements().isEmpty()) {
                continue;
            }
            pose.pushPose();
            pose.mulPose(part.matrix());
            for (BlockEntity blockEntity : new ArrayList<>(blockEntities.values())) {
                BlockEntityRenderer renderer = mc.getBlockEntityRenderDispatcher().getRenderer(blockEntity);
                if (renderer == null || brokenRenderers.contains(renderer)) {
                    continue;
                }
                BlockPos pos = blockEntity.getBlockPos();
                pose.pushPose();
                pose.translate(pos.getX(), pos.getY(), pos.getZ());
                try {
                    renderer.render(blockEntity, partialTick, pose, buffers,
                            LevelRenderer.getLightColor(part.store(), pos), OverlayTexture.NO_OVERLAY);
                } catch (Exception e) {
                    brokenRenderers.add(renderer);
                    LOGGER.warn("Kraz: block entity renderer for {} failed and was disabled for this scene", blockEntity.getType(), e);
                }
                pose.popPose();
            }
            if (!part.elements().isEmpty()) {
                SceneElement.Context context = new SceneElement.Context(part, pose, buffers, time, partialTick);
                for (SceneElement element : part.elements()) {
                    if (brokenRenderers.contains(element)) {
                        continue;
                    }
                    try {
                        element.render(context);
                    } catch (Exception e) {
                        brokenRenderers.add(element);
                        LOGGER.warn("Kraz: scene element {} failed and was disabled for this scene", element, e);
                    }
                }
            }
            pose.popPose();
        }
        buffers.endBatch();
    }

    /**
     * Draws the scene inside the world, lit by the world's own lightmap. Call from a
     * {@code RenderLevelStageEvent} handler (for example at {@code AFTER_TRANSLUCENT_BLOCKS}); pass the event's
     * model-view and projection matrices and camera position.
     *
     * @param origin world position of the scene's (0, 0, 0)
     */
    /**
     * Multiplies every part's opacity, e.g. to show a whole scene as a see-through ghost. Static elements and
     * block entities ignore it.
     */
    public SceneRenderer setAlpha(float alpha) {
        this.alpha = Math.clamp(alpha, 0f, 1f);
        return this;
    }

    public float alpha() {
        return alpha;
    }

    /** The opacity a part is drawn with in the draw in progress. */
    private static float alphaOf(ScenePart part) {
        return part.alpha() * drawingAlpha;
    }

    /**
     * Draws the scene inside the world, lit by the world's own lightmap, placed by an arbitrary transform. Call
     * from a {@code RenderLevelStageEvent} handler with the event's matrices and camera position.
     *
     * @param sceneToWorld takes scene coordinates to world coordinates (position, rotation, scale)
     * @param light        {@link Light#WORLD} to be lit like the surroundings, {@link Light#SCENE} to keep the
     *                     scene's own day-time lighting whatever the world's time
     */
    public void drawInWorld(Matrix4f levelModelView, Matrix4f levelProjection, net.minecraft.world.phys.Vec3 cameraPosition,
                            Matrix4f sceneToWorld, Light light, float partialTick) {
        Vector3f camera = new Vector3f((float) cameraPosition.x, (float) cameraPosition.y, (float) cameraPosition.z);
        Vector3f cameraInScene = sceneToWorld.invert(new Matrix4f()).transformPosition(new Vector3f(camera));
        prepare(cameraInScene, false);
        // Relative to the camera, in floats near zero: keeps precision far from the world origin.
        Matrix4f view = new Matrix4f(levelModelView).translate(-camera.x, -camera.y, -camera.z).mul(sceneToWorld);
        draw(view, levelProjection, scene.timeline().time(), partialTick, light, true);
    }

    public void drawInWorld(Matrix4f levelModelView, Matrix4f levelProjection, net.minecraft.world.phys.Vec3 cameraPosition,
                            net.minecraft.world.phys.Vec3 origin, float partialTick) {
        Vector3f cameraInScene = new Vector3f((float) (cameraPosition.x - origin.x), (float) (cameraPosition.y - origin.y), (float) (cameraPosition.z - origin.z));
        prepare(cameraInScene, false);
        Matrix4f view = new Matrix4f(levelModelView).translate(-cameraInScene.x, -cameraInScene.y, -cameraInScene.z);
        draw(view, levelProjection, scene.timeline().time(), partialTick, false);
    }

    /** Whether the scene contains anything that animates on its own and so needs redrawing every frame. */
    public boolean hasDynamicContent() {
        for (ScenePart part : scene.parts()) {
            if (!part.store().blockEntities().isEmpty()) {
                return true;
            }
            for (SceneElement element : part.elements()) {
                if (element.isDynamic()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        for (PartMesh mesh : meshes.values()) {
            mesh.close();
        }
        meshes.clear();
    }
}
