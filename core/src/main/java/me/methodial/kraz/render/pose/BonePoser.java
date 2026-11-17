package me.methodial.kraz.render.pose;

import me.methodial.kraz.mixin.ModelPartAccessor;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Lets a scene pose the individual bones of any living entity's model.
 *
 * <p>Vanilla models recompute every bone each frame in {@code setupAnim}. A mixin calls {@link #apply} right
 * after that, and the rotations registered for the entity currently being drawn are added on top, so idle
 * animation keeps playing underneath the pose. Bones are discovered from the model itself: the part tree of
 * hierarchical models, or the model class's {@code ModelPart} fields for the older hand-written ones.
 *
 * <p>Render thread only.
 */
public final class BonePoser {
    private static final Map<EntityModel<?>, Map<String, ModelPart>> BONES = new WeakHashMap<>();

    private static @Nullable Map<String, Vector3f> activeOffsets;
    private static @Nullable Consumer<Set<String>> activeListener;

    private BonePoser() {
    }

    /**
     * Arms posing for the next living entity rendered.
     *
     * @param offsets  rotation offsets in degrees (x, y, z) by bone name
     * @param listener receives the model's bone names, so callers can offer them for editing
     */
    public static void begin(Map<String, Vector3f> offsets, Consumer<Set<String>> listener) {
        activeOffsets = offsets;
        activeListener = listener;
    }

    public static void end() {
        activeOffsets = null;
        activeListener = null;
    }

    /** Called by the mixin after the model has been animated and before it is drawn. */
    public static void apply(EntityModel<?> model) {
        Map<String, Vector3f> offsets = activeOffsets;
        Consumer<Set<String>> listener = activeListener;
        if (offsets == null) {
            return;
        }
        // Only the entity's own model is posed, not models drawn later for passengers or layers.
        end();
        Map<String, ModelPart> bones = BONES.computeIfAbsent(model, BonePoser::discover);
        if (listener != null) {
            listener.accept(bones.keySet());
        }
        for (Map.Entry<String, Vector3f> entry : offsets.entrySet()) {
            ModelPart part = bones.get(entry.getKey());
            if (part != null) {
                Vector3f degrees = entry.getValue();
                part.xRot += (float) Math.toRadians(degrees.x);
                part.yRot += (float) Math.toRadians(degrees.y);
                part.zRot += (float) Math.toRadians(degrees.z);
            }
        }
    }

    private static Map<String, ModelPart> discover(EntityModel<?> model) {
        Map<String, ModelPart> bones = new LinkedHashMap<>();
        Set<ModelPart> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (model instanceof HierarchicalModel<?> hierarchical) {
            walk(hierarchical.root(), "", bones, seen);
        }
        // Hand-written models keep their bones in fields; their names are the best labels available.
        for (Class<?> type = model.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != ModelPart.class || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    ModelPart part = (ModelPart) field.get(model);
                    if (part != null && seen.add(part)) {
                        String name = field.getName();
                        bones.putIfAbsent(name, part);
                        walk(part, name + "/", bones, seen);
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Not reachable in this environment: the bone simply is not offered.
                }
            }
        }
        return bones;
    }

    private static void walk(ModelPart part, String prefix, Map<String, ModelPart> bones, Set<ModelPart> seen) {
        for (Map.Entry<String, ModelPart> child : ((ModelPartAccessor) (Object) part).kraz$children().entrySet()) {
            if (seen.add(child.getValue())) {
                String name = prefix + child.getKey();
                bones.putIfAbsent(name, child.getValue());
                walk(child.getValue(), name + "/", bones, seen);
            }
        }
    }
}
