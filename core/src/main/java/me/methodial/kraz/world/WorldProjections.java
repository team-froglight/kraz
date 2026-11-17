package me.methodial.kraz.world;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import me.methodial.kraz.render.SceneRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The scenes currently shown in the world. Client-side and not saved: projections are dropped when the player
 * leaves the world.
 */
public final class WorldProjections {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<WorldProjection> PROJECTIONS = new ArrayList<>();
    private static boolean installed;

    private WorldProjections() {
    }

    /** Hooks rendering and world-exit handling; called once by Kraz itself. */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, WorldProjections::render);
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> Minecraft.getInstance().execute(WorldProjections::clear));
    }

    public static WorldProjection add(WorldProjection projection) {
        if (!PROJECTIONS.contains(projection)) {
            PROJECTIONS.add(projection);
        }
        return projection;
    }

    public static void remove(WorldProjection projection) {
        if (PROJECTIONS.remove(projection)) {
            projection.close();
        }
    }

    public static void clear() {
        for (WorldProjection projection : PROJECTIONS) {
            projection.close();
        }
        PROJECTIONS.clear();
    }

    public static List<WorldProjection> all() {
        return Collections.unmodifiableList(PROJECTIONS);
    }

    private static void render(RenderLevelStageEvent event) {
        if (PROJECTIONS.isEmpty() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Matrix4f sceneToWorld = new Matrix4f();
        for (WorldProjection projection : List.copyOf(PROJECTIONS)) {
            if (!projection.visible()) {
                continue;
            }
            try {
                projection.tick();
                SceneRenderer renderer = projection.renderer().setAlpha(projection.alpha());
                renderer.drawInWorld(event.getModelViewMatrix(), event.getProjectionMatrix(), camera, projection.sceneToWorld(sceneToWorld),
                        projection.sceneLighting() ? SceneRenderer.Light.SCENE : SceneRenderer.Light.WORLD, partial);
                if (projection.showBounds()) {
                    drawBounds(event, camera, projection);
                }
            } catch (Exception e) {
                LOGGER.error("Kraz: a world projection failed to render and was removed", e);
                remove(projection);
            }
        }
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
    }

    private static void drawBounds(RenderLevelStageEvent event, Vec3 camera, WorldProjection projection) {
        AABB box = projection.worldBounds();
        if (box == null) {
            return;
        }
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        PoseStack pose = new PoseStack();
        // The level renderer has already put the camera's rotation on the model-view stack; only the offset is ours.
        pose.translate(-camera.x, -camera.y, -camera.z);
        LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), box.inflate(0.002), 0.85f, 0.69f, 0.31f, 0.9f);
        buffers.endBatch(RenderType.lines());
    }
}
