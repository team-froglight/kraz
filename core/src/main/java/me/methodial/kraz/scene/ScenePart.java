package me.methodial.kraz.scene;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongIterator;
import me.methodial.kraz.scene.element.SceneElement;
import me.methodial.kraz.scene.element.TransformedElement;
import me.methodial.kraz.scene.level.SceneLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A group of blocks, block entities and elements that moves as one. Every part has its own transform, opacity and
 * visibility, all of which can be animated without rebuilding any geometry.
 */
public final class ScenePart {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Scene scene;
    private final String name;
    private final BlockStore store = new BlockStore();
    private final List<SceneElement> elements = new ArrayList<>();

    private final Vector3f offset = new Vector3f();
    private final Vector3f pivot = new Vector3f();
    private final Quaternionf rotation = new Quaternionf();
    private float scale = 1.0f;
    private float alpha = 1.0f;
    private boolean visible = true;
    private final Matrix4f matrix = new Matrix4f();
    private boolean matrixDirty = true;
    private long version;

    private @Nullable SceneLevel level;

    ScenePart(Scene scene, String name) {
        this.scene = scene;
        this.name = name;
        this.store.setEnvironment(scene.environment());
    }

    public Scene scene() {
        return scene;
    }

    public String name() {
        return name;
    }

    public BlockStore store() {
        return store;
    }

    // ------------------------------------------------------------------ blocks

    public ScenePart setBlock(BlockPos pos, BlockState state) {
        store.setBlock(pos.getX(), pos.getY(), pos.getZ(), state, null);
        return this;
    }

    public ScenePart setBlock(int x, int y, int z, BlockState state) {
        store.setBlock(x, y, z, state, null);
        return this;
    }

    /** Places a block together with saved block entity data (as produced by {@code saveWithFullMetadata}). */
    public ScenePart setBlock(BlockPos pos, BlockState state, @Nullable CompoundTag blockEntityData) {
        store.setBlock(pos.getX(), pos.getY(), pos.getZ(), state, blockEntityData);
        return this;
    }

    public ScenePart removeBlock(BlockPos pos) {
        return setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
    }

    public ScenePart fill(BlockPos from, BlockPos to, BlockState state) {
        int x0 = Math.min(from.getX(), to.getX()), x1 = Math.max(from.getX(), to.getX());
        int y0 = Math.min(from.getY(), to.getY()), y1 = Math.max(from.getY(), to.getY());
        int z0 = Math.min(from.getZ(), to.getZ()), z1 = Math.max(from.getZ(), to.getZ());
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    store.setBlock(x, y, z, state, null);
                }
            }
        }
        return this;
    }

    public BlockState getBlockState(BlockPos pos) {
        return store.getBlockState(pos);
    }

    public ScenePart clear() {
        store.clear();
        return this;
    }

    public @Nullable BoundingBox bounds() {
        return store.bounds();
    }

    // ------------------------------------------------------------------ elements

    public <T extends SceneElement> T add(T element) {
        if (element instanceof TransformedElement<?> named) {
            // Names are how timeline tracks find elements, so they must be unique across the scene.
            String wanted = named.name().isEmpty() ? named.kind() : named.name();
            named.setName(scene.uniqueElementName(wanted, element));
        }
        elements.add(element);
        version++;
        return element;
    }

    public boolean remove(SceneElement element) {
        version++;
        return elements.remove(element);
    }

    public List<SceneElement> elements() {
        return Collections.unmodifiableList(elements);
    }

    // ------------------------------------------------------------------ transform

    public Vector3f offset() {
        return offset;
    }

    public ScenePart setOffset(float x, float y, float z) {
        offset.set(x, y, z);
        return touch();
    }

    public ScenePart setOffset(Vector3f value) {
        return setOffset(value.x, value.y, value.z);
    }

    /** The point rotation and scale are applied around, in part coordinates. */
    public Vector3f pivot() {
        return pivot;
    }

    public ScenePart setPivot(float x, float y, float z) {
        pivot.set(x, y, z);
        return touch();
    }

    public ScenePart setPivot(Vector3f value) {
        return setPivot(value.x, value.y, value.z);
    }

    public Quaternionf rotation() {
        return rotation;
    }

    public ScenePart setRotation(Quaternionf value) {
        rotation.set(value);
        return touch();
    }

    /** Rotation from Euler angles in degrees, applied in Y-X-Z order. */
    public ScenePart setRotation(float xDegrees, float yDegrees, float zDegrees) {
        rotation.rotationYXZ((float) Math.toRadians(yDegrees), (float) Math.toRadians(xDegrees), (float) Math.toRadians(zDegrees));
        return touch();
    }

    public float scale() {
        return scale;
    }

    public ScenePart setScale(float value) {
        scale = value;
        return touch();
    }

    public float alpha() {
        return alpha;
    }

    public ScenePart setAlpha(float value) {
        alpha = Math.clamp(value, 0f, 1f);
        version++;
        return this;
    }

    public boolean visible() {
        return visible;
    }

    public ScenePart setVisible(boolean value) {
        visible = value;
        version++;
        return this;
    }

    private ScenePart touch() {
        matrixDirty = true;
        version++;
        return this;
    }

    /** Part-to-scene transform. The returned matrix is owned by the part; do not modify it. */
    public Matrix4f matrix() {
        if (matrixDirty) {
            matrixDirty = false;
            matrix.translation(offset.x + pivot.x, offset.y + pivot.y, offset.z + pivot.z)
                    .rotate(rotation)
                    .scale(scale)
                    .translate(-pivot.x, -pivot.y, -pivot.z);
        }
        return matrix;
    }

    /** Changes whenever the part's blocks, elements, transform or visibility change. */
    public long version() {
        long v = version + store.revision();
        for (SceneElement element : elements) {
            v += element.version();
        }
        return v;
    }

    // ------------------------------------------------------------------ block entities

    /**
     * The fake level backing this part's block entities and entities, or {@code null} while no client world is
     * loaded (their construction needs the world's registries).
     */
    public @Nullable SceneLevel level() {
        var real = Minecraft.getInstance().level;
        if (level != null && (real == null || level.source() != real)) {
            level = null;
            invalidateLevelObjects();
        }
        if (level == null && real != null) {
            level = SceneLevel.create(this, real);
        }
        return level;
    }

    private void invalidateLevelObjects() {
        store.blockEntities().clear();
        store.forEachBlock((x, y, z, state) -> {
            if (state.hasBlockEntity()) {
                store.dirtyBlockEntities().add(BlockPos.asLong(x, y, z));
            }
        });
        for (SceneElement element : elements) {
            element.invalidate();
        }
    }

    /** Creates or drops block entity instances for positions that changed. Render thread only. */
    public void syncBlockEntities() {
        SceneLevel level = level();
        if (level == null || store.dirtyBlockEntities().isEmpty()) {
            return;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (LongIterator it = store.dirtyBlockEntities().iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            cursor.set(key);
            store.blockEntities().remove(key);
            BlockState state = store.getBlockState(cursor);
            if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
                continue;
            }
            BlockPos pos = cursor.immutable();
            try {
                BlockEntity blockEntity = entityBlock.newBlockEntity(pos, state);
                if (blockEntity == null) {
                    continue;
                }
                blockEntity.setLevel(level);
                CompoundTag data = store.getBlockEntityData(pos);
                if (data != null) {
                    blockEntity.loadWithComponents(data, level.registryAccess());
                }
                store.blockEntities().put(key, blockEntity);
            } catch (Exception e) {
                LOGGER.warn("Kraz: could not create block entity for {} at {}", state, pos, e);
            }
        }
        store.dirtyBlockEntities().clear();
    }
}
