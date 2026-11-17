package me.methodial.kraz.scene.element;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/** An item rendered at a free position, by default the way it looks in an item frame. */
public final class ItemElement extends TransformedElement<ItemElement> {
    public static final String TYPE = "item";
    private static final Logger LOGGER = LogUtils.getLogger();

    private ItemStack stack;
    private ItemDisplayContext displayContext = ItemDisplayContext.FIXED;

    public ItemElement(ItemStack stack) {
        this.stack = stack;
    }

    public ItemStack stack() {
        return stack;
    }

    public ItemElement setStack(ItemStack stack) {
        this.stack = stack;
        touch();
        return this;
    }

    public ItemDisplayContext displayContext() {
        return displayContext;
    }

    public ItemElement setDisplayContext(ItemDisplayContext displayContext) {
        this.displayContext = displayContext;
        touch();
        return this;
    }

    @Override
    public boolean isDynamic() {
        // Only the enchantment glint moves on its own.
        return stack.hasFoil();
    }

    @Override
    public String kind() {
        return stack.isEmpty() ? "item" : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    @Override
    public void render(Context context) {
        if (stack.isEmpty()) {
            return;
        }
        var pose = context.pose();
        pose.pushPose();
        applyTransform(pose);
        var itemRenderer = Minecraft.getInstance().getItemRenderer();
        if (displayContext == ItemDisplayContext.GUI && context.buffers() instanceof MultiBufferSource.BufferSource batch) {
            // An inventory icon: light it the way the inventory does, so it looks exactly like a slot's item.
            var model = itemRenderer.getModel(stack, context.part().level(), null, 0);
            batch.endBatch();
            Matrix4f lights = model.usesBlockLight()
                    ? new Matrix4f().scaling(1.0F, -1.0F, 1.0F).rotateYXZ(1.0821041F, 3.2375858F, 0.0F).rotateYXZ((float) (-Math.PI / 8), (float) (Math.PI * 3.0 / 4.0), 0.0F)
                    : new Matrix4f().rotationY((float) (-Math.PI / 8)).rotateX((float) (Math.PI * 3.0 / 4.0));
            // Vanilla's directions are for the GUI's y-down space; a scene is y-up.
            Vector3f a = lights.transformDirection(new Vector3f(0.2F, 1.0F, -0.7F).normalize()).mul(1, -1, 1);
            Vector3f b = lights.transformDirection(new Vector3f(-0.2F, 1.0F, 0.7F).normalize()).mul(1, -1, 1);
            RenderSystem.setShaderLights(a, b);
            itemRenderer.render(stack, displayContext, false, pose, batch, 0xF000F0, OverlayTexture.NO_OVERLAY, model);
            batch.endBatch();
            Lighting.setupLevel();
        } else {
            itemRenderer.renderStatic(stack, displayContext, light(context), OverlayTexture.NO_OVERLAY, pose, context.buffers(), context.part().level(), 0);
        }
        pose.popPose();
    }

    /**
     * Registries used to encode item components: the world's when one is loaded, otherwise the built-in ones
     * (enough for everything except data-driven components such as enchantments).
     */
    private static HolderLookup.Provider registries() {
        var level = Minecraft.getInstance().level;
        return level != null ? level.registryAccess() : RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Override
    public @Nullable CompoundTag save() {
        if (stack.isEmpty()) {
            return null;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("type", TYPE);
        try {
            tag.put("stack", stack.save(registries()));
        } catch (Exception e) {
            // Components that need registries we do not have: keep at least the item itself.
            CompoundTag plain = new CompoundTag();
            plain.putString("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            plain.putInt("count", stack.getCount());
            tag.put("stack", plain);
        }
        tag.putString("display", displayContext.getSerializedName());
        saveTransform(tag);
        return tag;
    }

    public static ItemElement load(CompoundTag tag) {
        ItemStack stack = ItemStack.EMPTY;
        try {
            stack = ItemStack.parse(registries(), tag.getCompound("stack")).orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            LOGGER.warn("Kraz: could not read item element {}", tag.getCompound("stack"), e);
        }
        ItemElement element = new ItemElement(stack);
        for (ItemDisplayContext context : ItemDisplayContext.values()) {
            if (context.getSerializedName().equals(tag.getString("display"))) {
                element.displayContext = context;
            }
        }
        element.loadTransform(tag);
        return element;
    }
}
