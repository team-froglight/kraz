package me.methodial.kraz.scene;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

/**
 * Sparse block storage for one {@link ScenePart}. Blocks live in 16³ sections that are copied on write, so the
 * background meshing threads can keep reading a consistent {@link Snapshot} while the scene keeps changing.
 *
 * <p>Must only be mutated from the render thread.
 */
public final class BlockStore extends BlockView {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    static final class Section {
        BlockState[] states = new BlockState[4096];
        /** Set once a snapshot references {@link #states}; the next write clones the array first. */
        boolean shared;
        int count;
    }

    private final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<CompoundTag> blockEntityData = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<BlockEntity> blockEntities = new Long2ObjectOpenHashMap<>();
    private final java.util.List<LongSet> dirtyTrackers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final LongOpenHashSet dirtyBlockEntities = new LongOpenHashSet();

    private SceneEnvironment environment = SceneEnvironment.DEFAULT;
    private @Nullable LightData light;
    private boolean lightDirty = true;
    private @Nullable BoundingBox bounds;
    private boolean boundsDirty = true;
    private long revision;

    // ------------------------------------------------------------------ access

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return getBlockState(pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockState getBlockState(int x, int y, int z) {
        Section section = sections.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
        if (section == null) {
            return AIR;
        }
        BlockState state = section.states[index(x, y, z)];
        return state == null ? AIR : state;
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return blockEntities.get(pos.asLong());
    }

    @Override
    public SceneEnvironment environment() {
        return environment;
    }

    @Override
    protected @Nullable LightData lightData() {
        if (environment.lightMode() != SceneEnvironment.LightMode.COMPUTED) {
            return null;
        }
        if (lightDirty) {
            lightDirty = false;
            light = LightData.compute(this);
        }
        return light;
    }

    /** Increases whenever blocks, block entity data or the environment change. */
    public long revision() {
        return revision;
    }

    public boolean isEmpty() {
        return sections.isEmpty();
    }

    public int sectionCount() {
        return sections.size();
    }

    /** Tight bounds of all non-air blocks, or {@code null} when empty. */
    public @Nullable BoundingBox bounds() {
        if (boundsDirty) {
            boundsDirty = false;
            bounds = computeBounds();
        }
        return bounds;
    }

    public @Nullable CompoundTag getBlockEntityData(BlockPos pos) {
        return blockEntityData.get(pos.asLong());
    }

    // ------------------------------------------------------------------ mutation

    public boolean setBlock(BlockPos pos, BlockState state) {
        return setBlock(pos.getX(), pos.getY(), pos.getZ(), state, null);
    }

    /**
     * @param blockEntityData saved block entity data for this position, or {@code null}
     * @return whether anything changed
     */
    public boolean setBlock(int x, int y, int z, BlockState state, @Nullable CompoundTag blockEntityData) {
        long key = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
        long posKey = BlockPos.asLong(x, y, z);
        Section section = sections.get(key);
        boolean air = state.isAir();
        BlockState old = section == null ? null : section.states[index(x, y, z)];
        BlockState stored = air ? null : state;
        boolean dataChanged = blockEntityData != null || this.blockEntityData.containsKey(posKey);
        if (old == stored && !dataChanged) {
            return false;
        }
        if (old != stored) {
            if (section == null) {
                section = new Section();
                sections.put(key, section);
            }
            if (section.shared) {
                section.states = section.states.clone();
                section.shared = false;
            }
            section.states[index(x, y, z)] = stored;
            if (old == null) {
                section.count++;
            } else if (stored == null && --section.count == 0) {
                sections.remove(key);
            }
            boundsDirty = true;
            lightDirty = true;
            markDirty(x, y, z);
        }
        if (blockEntityData != null) {
            this.blockEntityData.put(posKey, blockEntityData);
        } else {
            this.blockEntityData.remove(posKey);
        }
        if (dataChanged || state.hasBlockEntity() || (old != null && old.hasBlockEntity())) {
            dirtyBlockEntities.add(posKey);
        }
        revision++;
        return true;
    }

    public void clear() {
        for (LongSet tracker : dirtyTrackers) {
            tracker.addAll(sections.keySet());
        }
        dirtyBlockEntities.addAll(blockEntities.keySet());
        sections.clear();
        blockEntityData.clear();
        boundsDirty = true;
        lightDirty = true;
        revision++;
    }

    void setEnvironment(SceneEnvironment environment) {
        if (!this.environment.equals(environment)) {
            this.environment = environment;
            lightDirty = true;
            markAllDirty();
            revision++;
        }
    }

    /** Queues every section for remeshing, e.g. after a resource reload. */
    public void markAllDirty() {
        for (LongSet tracker : dirtyTrackers) {
            tracker.addAll(sections.keySet());
        }
    }

    private void markDirty(int x, int y, int z) {
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        if (environment.lightMode() == SceneEnvironment.LightMode.COMPUTED) {
            // Light travels up to 15 blocks sideways and any distance down a column.
            for (long key : sections.keySet()) {
                if (Math.abs(SectionPos.x(key) - sx) <= 1 && Math.abs(SectionPos.z(key) - sz) <= 1) {
                    dirty(key);
                }
            }
            dirty(SectionPos.asLong(sx, sy, sz));
            return;
        }
        // Face culling and ambient occlusion look one block across section borders.
        int lx = x & 15, ly = y & 15, lz = z & 15;
        int x0 = lx == 0 ? -1 : 0, x1 = lx == 15 ? 1 : 0;
        int y0 = ly == 0 ? -1 : 0, y1 = ly == 15 ? 1 : 0;
        int z0 = lz == 0 ? -1 : 0, z1 = lz == 15 ? 1 : 0;
        for (int dx = x0; dx <= x1; dx++) {
            for (int dy = y0; dy <= y1; dy++) {
                for (int dz = z0; dz <= z1; dz++) {
                    dirty(SectionPos.asLong(sx + dx, sy + dy, sz + dz));
                }
            }
        }
    }

    // ------------------------------------------------------------------ renderer hooks

    private void dirty(long sectionKey) {
        for (LongSet tracker : dirtyTrackers) {
            tracker.add(sectionKey);
        }
    }

    /**
     * Starts tracking stale sections on behalf of one renderer. The returned set initially contains every section
     * and receives the key of each section whose mesh becomes stale; the renderer clears it as it rebuilds.
     * Several renderers can track the same store independently.
     */
    public LongSet trackDirtySections() {
        LongSet tracker = new LongOpenHashSet(sections.keySet());
        dirtyTrackers.add(tracker);
        return tracker;
    }

    public void untrackDirtySections(LongSet tracker) {
        dirtyTrackers.removeIf(t -> t == tracker);
    }

    /** Positions whose block entity instance needs to be (re)created or dropped. */
    public LongSet dirtyBlockEntities() {
        return dirtyBlockEntities;
    }

    public Long2ObjectMap<BlockEntity> blockEntities() {
        return blockEntities;
    }

    Long2ObjectOpenHashMap<Section> sections() {
        return sections;
    }

    public Long2ObjectMap<CompoundTag> blockEntityData() {
        return Long2ObjectMaps.unmodifiable(blockEntityData);
    }

    public boolean hasSection(long sectionKey) {
        return sections.containsKey(sectionKey);
    }

    /** Visits every non-air block. */
    public void forEachBlock(BlockConsumer consumer) {
        for (Long2ObjectMap.Entry<Section> entry : sections.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            int ox = SectionPos.x(key) << 4, oy = SectionPos.y(key) << 4, oz = SectionPos.z(key) << 4;
            BlockState[] states = entry.getValue().states;
            for (int i = 0; i < 4096; i++) {
                BlockState state = states[i];
                if (state != null) {
                    consumer.accept(ox + (i & 15), oy + (i >> 8), oz + ((i >> 4) & 15), state);
                }
            }
        }
    }

    @FunctionalInterface
    public interface BlockConsumer {
        void accept(int x, int y, int z, BlockState state);
    }

    /**
     * Captures an immutable view of one section and its 26 neighbours. Cheap: section arrays are shared and only
     * cloned if the scene is modified afterwards.
     */
    public Snapshot snapshot(long sectionKey) {
        int sx = SectionPos.x(sectionKey), sy = SectionPos.y(sectionKey), sz = SectionPos.z(sectionKey);
        BlockState[][] arrays = new BlockState[27][];
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    Section section = sections.get(SectionPos.asLong(sx + dx, sy + dy, sz + dz));
                    if (section != null) {
                        section.shared = true;
                        arrays[((dy + 1) * 3 + (dz + 1)) * 3 + (dx + 1)] = section.states;
                    }
                }
            }
        }
        Long2ObjectOpenHashMap<ModelData> modelData = null;
        if (!blockEntities.isEmpty()) {
            for (Long2ObjectMap.Entry<BlockEntity> entry : blockEntities.long2ObjectEntrySet()) {
                long pos = entry.getLongKey();
                if (BlockPos.getX(pos) >> 4 != sx || BlockPos.getY(pos) >> 4 != sy || BlockPos.getZ(pos) >> 4 != sz) {
                    continue;
                }
                ModelData data = entry.getValue().getModelData();
                if (data != ModelData.EMPTY) {
                    if (modelData == null) {
                        modelData = new Long2ObjectOpenHashMap<>();
                    }
                    modelData.put(pos, data);
                }
            }
        }
        return new Snapshot(sx, sy, sz, arrays, environment, lightData(), modelData);
    }

    private @Nullable BoundingBox computeBounds() {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        forEachBlock((x, y, z, state) -> {
            if (x < b[0]) b[0] = x;
            if (y < b[1]) b[1] = y;
            if (z < b[2]) b[2] = z;
            if (x > b[3]) b[3] = x;
            if (y > b[4]) b[4] = y;
            if (z > b[5]) b[5] = z;
        });
        return b[0] == Integer.MAX_VALUE ? null : new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    private static int index(int x, int y, int z) {
        return (y & 15) << 8 | (z & 15) << 4 | (x & 15);
    }

    /** Immutable 3×3×3 section neighbourhood; safe to read from any thread. */
    public static final class Snapshot extends BlockView {
        private final int sx, sy, sz;
        private final BlockState[][] arrays;
        private final SceneEnvironment environment;
        private final @Nullable LightData light;
        private final @Nullable Long2ObjectOpenHashMap<ModelData> modelData;

        private Snapshot(int sx, int sy, int sz, BlockState[][] arrays, SceneEnvironment environment,
                         @Nullable LightData light, @Nullable Long2ObjectOpenHashMap<ModelData> modelData) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.arrays = arrays;
            this.environment = environment;
            this.light = light;
            this.modelData = modelData;
        }

        public int sectionX() {
            return sx;
        }

        public int sectionY() {
            return sy;
        }

        public int sectionZ() {
            return sz;
        }

        /** The centre section's states in y-z-x order ({@code null} entries are air), or {@code null} if empty. */
        public BlockState @Nullable [] centerStates() {
            return arrays[13];
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            int dx = (x >> 4) - sx + 1, dy = (y >> 4) - sy + 1, dz = (z >> 4) - sz + 1;
            if ((dx | dy | dz) < 0 || dx > 2 || dy > 2 || dz > 2) {
                return AIR;
            }
            BlockState[] states = arrays[(dy * 3 + dz) * 3 + dx];
            if (states == null) {
                return AIR;
            }
            BlockState state = states[index(x, y, z)];
            return state == null ? AIR : state;
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public ModelData getModelData(BlockPos pos) {
            if (modelData == null) {
                return ModelData.EMPTY;
            }
            ModelData data = modelData.get(pos.asLong());
            return data == null ? ModelData.EMPTY : data;
        }

        @Override
        public SceneEnvironment environment() {
            return environment;
        }

        @Override
        protected @Nullable LightData lightData() {
            return light;
        }
    }
}
