package me.methodial.kraz.scene.element;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * A single block model drawn at a free (non-grid) position with its own rotation and scale. Use it for blocks that
 * fly, spin or otherwise move on their own; blocks that sit on the grid belong in the part itself, where they are
 * baked into static geometry.
 */
public final class BlockElement extends TransformedElement<BlockElement> {
    public static final String TYPE = "block";

    private BlockState state;

    public BlockElement(BlockState state) {
        this.state = state;
    }

    public BlockState state() {
        return state;
    }

    public BlockElement setState(BlockState state) {
        this.state = state;
        touch();
        return this;
    }

    @Override
    public boolean isDynamic() {
        return false;
    }

    @Override
    public String kind() {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    @Override
    public org.joml.Vector3f pickCenter(org.joml.Vector3f dest) {
        // The model spans 0..1 from its position, so the visual centre is half a block in.
        return rotation.transform(dest.set(0.5f, 0.5f, 0.5f)).mul(scale).add(position);
    }

    @Override
    public float pickRadius() {
        return 0.75f * scale;
    }

    @Override
    public void render(Context context) {
        var pose = context.pose();
        pose.pushPose();
        applyTransform(pose);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, pose, context.buffers(), light(context),
                OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
        pose.popPose();
    }

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", TYPE);
        tag.put("state", NbtUtils.writeBlockState(state));
        saveTransform(tag);
        return tag;
    }

    public static BlockElement load(CompoundTag tag) {
        BlockElement element = new BlockElement(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound("state")));
        element.loadTransform(tag);
        return element;
    }
}
