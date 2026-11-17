package me.methodial.kraz.scene.element;

import com.mojang.blaze3d.vertex.PoseStack;
import me.methodial.kraz.scene.ScenePart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Something drawn every frame inside a {@link ScenePart}, in addition to its baked block geometry: an item, a
 * free-floating block model, an entity, or anything custom. Implement this to draw your own content into a scene.
 */
public interface SceneElement {
    /**
     * Draws the element. The pose stack is already in the owning part's coordinate space.
     */
    void render(Context context);

    /** Called when cached level-bound objects (entities) must be dropped because the client world changed. */
    default void invalidate() {
    }

    /** Serialized form, or {@code null} if this element cannot be saved with the scene. */
    default @Nullable CompoundTag save() {
        return null;
    }

    /**
     * Name used to address this element from timeline tracks and the editor; unique within a scene once the
     * element has been added to a part. Empty for elements that cannot be addressed.
     */
    default String name() {
        return "";
    }

    /**
     * Whether the element can look different from one frame to the next without anything about the scene having
     * changed (an entity breathing, an enchantment glint). Scenes without such elements can be cached as an image.
     */
    default boolean isDynamic() {
        return true;
    }

    /** Changes whenever something about the element that affects how it is drawn changes. */
    default long version() {
        return 0;
    }

    /** Animatable values of this element. */
    default List<ElementProperty<?>> properties() {
        return List.of();
    }

    /** Looks up a property by id; may create it on demand for open-ended families such as bones. */
    default @Nullable ElementProperty<?> property(String id) {
        for (ElementProperty<?> property : properties()) {
            if (property.id().equals(id)) {
                return property;
            }
        }
        return null;
    }

    /**
     * @param part        the part being drawn
     * @param pose        pose stack in part space
     * @param buffers     buffer source, flushed by the scene renderer
     * @param time        scene time in ticks
     * @param partialTick fraction of the current game tick
     */
    record Context(ScenePart part, PoseStack pose, MultiBufferSource buffers, float time, float partialTick) {
    }
}
