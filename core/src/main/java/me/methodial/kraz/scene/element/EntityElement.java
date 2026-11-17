package me.methodial.kraz.scene.element;

import com.mojang.logging.LogUtils;
import me.methodial.kraz.anim.Track;
import me.methodial.kraz.mixin.WalkAnimationStateAccessor;
import me.methodial.kraz.render.pose.BonePoser;
import me.methodial.kraz.scene.level.SceneLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * An entity standing in the scene. The entity is never ticked; it is posed instead:
 *
 * <ul>
 *   <li>{@link #setYaw body yaw}, {@link #setHeadYaw head yaw} and {@link #setHeadPitch head pitch};</li>
 *   <li>the walk cycle, as a {@link #setLimbSwing position} and an {@link #setLimbAmount amount};</li>
 *   <li>a vanilla {@link #setPose pose} such as crouching or sleeping;</li>
 *   <li>per-bone rotation offsets ({@link #setBone}) applied on top of the model's own animation.</li>
 * </ul>
 *
 * All numeric ones are {@link #properties() properties}, so they can be keyframed. The entity's age follows the
 * scene time, which keeps idle animations playing. Entities need a loaded client world for their registries and
 * are skipped without one.
 */
public final class EntityElement extends TransformedElement<EntityElement> {
    public static final String TYPE = "entity";
    private static final Logger LOGGER = LogUtils.getLogger();

    private CompoundTag data;
    private @Nullable Consumer<Entity> configurator;
    private float yaw;
    private float headYaw;
    private float headPitch;
    private float limbSwing;
    private float limbAmount;
    private Pose pose = Pose.STANDING;
    private final Map<String, Vector3f> bones = new LinkedHashMap<>();
    private final Set<String> knownBones = new LinkedHashSet<>();

    private @Nullable Entity entity;
    private boolean failed;

    /** @param data entity NBT including its {@code id}, as written by {@link Entity#save} */
    public EntityElement(CompoundTag data) {
        this.data = data.copy();
    }

    public EntityElement(EntityType<?> type) {
        this.data = new CompoundTag();
        this.data.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
    }

    @Override
    public String kind() {
        ResourceLocation id = ResourceLocation.tryParse(data.getString("id"));
        return id == null ? "entity" : id.getPath();
    }

    /** Runs once on the entity each time it is created, e.g. to equip it or pick a variant. */
    public EntityElement configure(Consumer<Entity> configurator) {
        this.configurator = configurator;
        invalidate();
        return this;
    }

    public CompoundTag data() {
        return data;
    }

    /** Replaces the entity's NBT (variant, equipment, age, …) and recreates it. */
    public EntityElement setData(CompoundTag data) {
        this.data = data.copy();
        invalidate();
        return this;
    }

    public float yaw() {
        return yaw;
    }

    /** Body yaw in degrees. */
    public EntityElement setYaw(float degrees) {
        this.yaw = degrees;
        return this;
    }

    public float headYaw() {
        return headYaw;
    }

    /** Head yaw in degrees, relative to the body. */
    public EntityElement setHeadYaw(float degrees) {
        this.headYaw = degrees;
        return this;
    }

    public float headPitch() {
        return headPitch;
    }

    public EntityElement setHeadPitch(float degrees) {
        this.headPitch = degrees;
        return this;
    }

    public float limbSwing() {
        return limbSwing;
    }

    /** Position in the walk cycle. Animate it upwards to make the entity walk on the spot. */
    public EntityElement setLimbSwing(float value) {
        this.limbSwing = value;
        return this;
    }

    public float limbAmount() {
        return limbAmount;
    }

    /** How strongly the walk cycle shows, 0 (standing still) .. 1 (full stride). */
    public EntityElement setLimbAmount(float value) {
        this.limbAmount = value;
        return this;
    }

    public Pose pose() {
        return pose;
    }

    /** Vanilla pose: standing, crouching, sleeping, swimming, sitting and so on. */
    public EntityElement setPose(Pose pose) {
        this.pose = pose;
        return this;
    }

    /** Rotation offset in degrees added to one bone of the entity's model. See {@link #boneNames()}. */
    public EntityElement setBone(String bone, float xDegrees, float yDegrees, float zDegrees) {
        if (xDegrees == 0 && yDegrees == 0 && zDegrees == 0) {
            bones.remove(bone);
        } else {
            bones.computeIfAbsent(bone, b -> new Vector3f()).set(xDegrees, yDegrees, zDegrees);
        }
        return this;
    }

    public Vector3f bone(String bone) {
        Vector3f value = bones.get(bone);
        return value == null ? new Vector3f() : new Vector3f(value);
    }

    /** Bones with a non-zero offset. */
    public Map<String, Vector3f> bones() {
        return Collections.unmodifiableMap(bones);
    }

    /**
     * Names of the bones the entity's model offers. Known only after the entity has been drawn once, because
     * they are read from the live model.
     */
    public Set<String> boneNames() {
        Set<String> all = new LinkedHashSet<>(knownBones);
        all.addAll(bones.keySet());
        return all;
    }

    /** The live entity, or {@code null} before first render or without a world. */
    public @Nullable Entity entity() {
        return entity;
    }

    @Override
    public float pickRadius() {
        return entity == null ? 0.6f * scale : Math.max(entity.getBbHeight(), entity.getBbWidth()) * 0.5f * scale;
    }

    @Override
    public Vector3f pickCenter(Vector3f dest) {
        float height = entity == null ? 1.0f : entity.getBbHeight();
        return dest.set(position.x, position.y + height * 0.5f * scale, position.z);
    }

    @Override
    public List<ElementProperty<?>> properties() {
        List<ElementProperty<?>> list = super.properties();
        list.add(new ElementProperty<Float>("yaw", Track.Type.FLOAT, () -> yaw, this::setYaw));
        list.add(new ElementProperty<Float>("head_yaw", Track.Type.FLOAT, () -> headYaw, this::setHeadYaw));
        list.add(new ElementProperty<Float>("head_pitch", Track.Type.FLOAT, () -> headPitch, this::setHeadPitch));
        list.add(new ElementProperty<Float>("limb_swing", Track.Type.FLOAT, () -> limbSwing, this::setLimbSwing));
        list.add(new ElementProperty<Float>("limb_amount", Track.Type.FLOAT, () -> limbAmount, this::setLimbAmount));
        for (String bone : boneNames()) {
            list.add(boneProperty(bone));
        }
        return list;
    }

    @Override
    public @Nullable ElementProperty<?> property(String id) {
        // Bones can be addressed before the model has ever been seen, e.g. while loading a saved animation.
        if (id.startsWith("bone/")) {
            return boneProperty(id.substring(5));
        }
        return super.property(id);
    }

    private ElementProperty<Vector3f> boneProperty(String bone) {
        return new ElementProperty<>("bone/" + bone, Track.Type.VEC3, () -> bone(bone), v -> setBone(bone, v.x, v.y, v.z));
    }

    @Override
    public void invalidate() {
        entity = null;
        failed = false;
    }

    private @Nullable Entity resolve(@Nullable SceneLevel level) {
        if (entity != null || failed || level == null) {
            return entity;
        }
        try {
            entity = EntityType.loadEntityRecursive(data, level, e -> e);
            if (entity != null && configurator != null) {
                configurator.accept(entity);
            }
        } catch (Exception e) {
            LOGGER.warn("Kraz: could not create entity {}", data.getString("id"), e);
        }
        failed = entity == null;
        return entity;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void render(Context context) {
        Entity e = resolve(context.part().level());
        if (e == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        if (dispatcher.camera == null) {
            dispatcher.prepare(e.level(), mc.gameRenderer.getMainCamera(), null);
        }
        e.tickCount = (int) context.time();
        e.setPos(position.x, position.y, position.z);
        e.xo = e.xOld = position.x;
        e.yo = e.yOld = position.y;
        e.zo = e.zOld = position.z;
        e.setYRot(yaw + headYaw);
        e.yRotO = yaw + headYaw;
        e.setXRot(headPitch);
        e.xRotO = headPitch;
        if (e.getPose() != pose) {
            e.setPose(pose);
        }
        if (e instanceof LivingEntity living) {
            living.yBodyRot = living.yBodyRotO = yaw;
            living.yHeadRot = living.yHeadRotO = yaw + headYaw;
            living.walkAnimation.setSpeed(limbAmount);
            WalkAnimationStateAccessor walk = (WalkAnimationStateAccessor) living.walkAnimation;
            walk.kraz$setSpeedOld(limbAmount);
            walk.kraz$setPosition(limbSwing);
        }
        float partial = context.time() - (int) context.time();
        var poseStack = context.pose();
        poseStack.pushPose();
        poseStack.translate(position.x, position.y, position.z);
        poseStack.mulPose(rotation);
        poseStack.scale(scale, scale, scale);
        try {
            EntityRenderer renderer = dispatcher.getRenderer(e);
            Vec3 offset = renderer.getRenderOffset(e, partial);
            poseStack.translate(offset.x, offset.y, offset.z);
            BonePoser.begin(bones, knownBones::addAll);
            renderer.render(e, yaw, partial, poseStack, context.buffers(), light(context));
        } catch (Exception ex) {
            LOGGER.warn("Kraz: entity {} failed to render and was disabled", data.getString("id"), ex);
            entity = null;
            failed = true;
        } finally {
            BonePoser.end();
        }
        poseStack.popPose();
    }

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", TYPE);
        tag.put("entity", data.copy());
        tag.putFloat("yaw", yaw);
        tag.putFloat("head_yaw", headYaw);
        tag.putFloat("head_pitch", headPitch);
        tag.putFloat("limb_swing", limbSwing);
        tag.putFloat("limb_amount", limbAmount);
        tag.putString("pose", pose.name());
        ListTag boneList = new ListTag();
        bones.forEach((bone, value) -> {
            CompoundTag b = new CompoundTag();
            b.putString("bone", bone);
            b.putFloat("x", value.x);
            b.putFloat("y", value.y);
            b.putFloat("z", value.z);
            boneList.add(b);
        });
        tag.put("bones", boneList);
        saveTransform(tag);
        return tag;
    }

    public static EntityElement load(CompoundTag tag) {
        EntityElement element = new EntityElement(tag.getCompound("entity"));
        element.yaw = tag.getFloat("yaw");
        element.headYaw = tag.getFloat("head_yaw");
        element.headPitch = tag.getFloat("head_pitch");
        element.limbSwing = tag.getFloat("limb_swing");
        element.limbAmount = tag.getFloat("limb_amount");
        try {
            element.pose = tag.contains("pose") ? Pose.valueOf(tag.getString("pose")) : Pose.STANDING;
        } catch (IllegalArgumentException e) {
            element.pose = Pose.STANDING;
        }
        ListTag boneList = tag.getList("bones", Tag.TAG_COMPOUND);
        for (int i = 0; i < boneList.size(); i++) {
            CompoundTag b = boneList.getCompound(i);
            element.setBone(b.getString("bone"), b.getFloat("x"), b.getFloat("y"), b.getFloat("z"));
        }
        element.loadTransform(tag);
        return element;
    }
}
