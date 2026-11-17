package me.methodial.kraz.scene.element;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * Shows an entity that already exists in the real world (the player, a mob being looked at) inside a scene,
 * without copying it: skin, equipment and animation are the live ones. While it is drawn, the entity's rotation
 * is replaced by this element's and put back afterwards, the way the inventory's player preview works.
 *
 * <p>Cannot be saved with a scene: it refers to a live object. Use {@link EntityElement} for entities that
 * belong to the scene.
 */
public final class LiveEntityElement extends TransformedElement<LiveEntityElement> {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Supplier<? extends Entity> entity;
    private float yaw;
    private float headYaw;
    private float headPitch;
    private boolean failed;

    public LiveEntityElement(Supplier<? extends Entity> entity) {
        this.entity = entity;
    }

    /** Body yaw in degrees; 0 faces +Z. */
    public LiveEntityElement setYaw(float degrees) {
        this.yaw = degrees;
        return this;
    }

    /** Head yaw in degrees, relative to the body. */
    public LiveEntityElement setHeadYaw(float degrees) {
        this.headYaw = degrees;
        return this;
    }

    public LiveEntityElement setHeadPitch(float degrees) {
        this.headPitch = degrees;
        return this;
    }

    @Override
    public String kind() {
        return "live_entity";
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void render(Context context) {
        Entity e = entity.get();
        if (e == null || failed) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        if (dispatcher.camera == null) {
            dispatcher.prepare(e.level(), mc.gameRenderer.getMainCamera(), null);
        }
        float yRot = e.getYRot(), xRot = e.getXRot(), yRotO = e.yRotO, xRotO = e.xRotO;
        LivingEntity living = e instanceof LivingEntity l ? l : null;
        float body = 0, bodyO = 0, head = 0, headO = 0;
        if (living != null) {
            body = living.yBodyRot;
            bodyO = living.yBodyRotO;
            head = living.yHeadRot;
            headO = living.yHeadRotO;
        }
        var pose = context.pose();
        pose.pushPose();
        applyTransform(pose);
        try {
            e.setYRot(yaw + headYaw);
            e.yRotO = yaw + headYaw;
            e.setXRot(headPitch);
            e.xRotO = headPitch;
            if (living != null) {
                living.yBodyRot = living.yBodyRotO = yaw;
                living.yHeadRot = living.yHeadRotO = yaw + headYaw;
            }
            EntityRenderer renderer = dispatcher.getRenderer(e);
            Vec3 offset = renderer.getRenderOffset(e, 1.0f);
            pose.translate(offset.x, offset.y, offset.z);
            renderer.render(e, yaw, 1.0f, pose, context.buffers(), 0xF000F0);
        } catch (Exception ex) {
            failed = true;
            LOGGER.warn("Kraz: live entity {} failed to render and was disabled", e.getType(), ex);
        } finally {
            e.setYRot(yRot);
            e.setXRot(xRot);
            e.yRotO = yRotO;
            e.xRotO = xRotO;
            if (living != null) {
                living.yBodyRot = body;
                living.yBodyRotO = bodyO;
                living.yHeadRot = head;
                living.yHeadRotO = headO;
            }
        }
        pose.popPose();
    }
}
