package me.methodial.kraz.scene;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable flood-filled sky and block light for a {@link BlockStore}, packed as {@code sky << 4 | block} per cell.
 * Everything outside the stored box is open air under a clear sky.
 */
public final class LightData {
    /** Scenes larger than this fall back to full brightness rather than allocating hundreds of megabytes. */
    private static final long MAX_VOLUME = 96L * 1024 * 1024;

    private final int minX, minY, minZ;
    private final int sizeX, sizeY, sizeZ;
    private final byte[] packed;

    private LightData(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, byte[] packed) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.packed = packed;
    }

    public int get(int x, int y, int z) {
        int lx = x - minX, ly = y - minY, lz = z - minZ;
        if (lx < 0 || ly < 0 || lz < 0 || lx >= sizeX || ly >= sizeY || lz >= sizeZ) {
            return 0xF0;
        }
        return packed[(ly * sizeZ + lz) * sizeX + lx] & 0xFF;
    }

    static @Nullable LightData compute(BlockStore store) {
        BoundingBox box = store.bounds();
        if (box == null) {
            return null;
        }
        // One cell of open air around the content lets light wrap around the outside of the build.
        int minX = box.minX() - 1, minY = box.minY() - 1, minZ = box.minZ() - 1;
        int sx = box.getXSpan() + 2, sy = box.getYSpan() + 2, sz = box.getZSpan() + 2;
        long volume = (long) sx * sy * sz;
        if (volume > MAX_VOLUME) {
            return null;
        }
        int size = (int) volume;
        byte[] opacity = new byte[size];
        byte[] block = new byte[size];
        byte[] sky = new byte[size];
        IntArrayFIFOQueue queue = new IntArrayFIFOQueue();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (Long2ObjectMap.Entry<BlockStore.Section> entry : store.sections().long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            int ox = SectionPos.x(key) << 4, oy = SectionPos.y(key) << 4, oz = SectionPos.z(key) << 4;
            BlockState[] states = entry.getValue().states;
            for (int i = 0; i < 4096; i++) {
                BlockState state = states[i];
                if (state == null) {
                    continue;
                }
                int x = ox + (i & 15), y = oy + (i >> 8), z = oz + ((i >> 4) & 15);
                pos.set(x, y, z);
                int index = ((y - minY) * sz + (z - minZ)) * sx + (x - minX);
                opacity[index] = (byte) state.getLightBlock(store, pos);
                int emission = state.getLightEmission(store, pos);
                if (emission > 0) {
                    block[index] = (byte) Math.min(emission, 15);
                    queue.enqueue(index);
                }
            }
        }
        flood(block, opacity, queue, sx, sy, sz);

        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) {
                for (int y = sy - 1; y >= 0; y--) {
                    int index = (y * sz + z) * sx + x;
                    if (opacity[index] != 0) {
                        break;
                    }
                    sky[index] = 15;
                    queue.enqueue(index);
                }
            }
        }
        flood(sky, opacity, queue, sx, sy, sz);

        for (int i = 0; i < size; i++) {
            sky[i] = (byte) (sky[i] << 4 | block[i]);
        }
        return new LightData(minX, minY, minZ, sx, sy, sz, sky);
    }

    private static void flood(byte[] light, byte[] opacity, IntArrayFIFOQueue queue, int sx, int sy, int sz) {
        int layer = sx * sz;
        while (!queue.isEmpty()) {
            int index = queue.dequeueInt();
            int level = light[index];
            if (level <= 1) {
                continue;
            }
            int x = index % sx;
            int z = (index / sx) % sz;
            int y = index / layer;
            if (x > 0) spread(light, opacity, queue, index - 1, level);
            if (x < sx - 1) spread(light, opacity, queue, index + 1, level);
            if (z > 0) spread(light, opacity, queue, index - sx, level);
            if (z < sz - 1) spread(light, opacity, queue, index + sx, level);
            if (y > 0) spread(light, opacity, queue, index - layer, level);
            if (y < sy - 1) spread(light, opacity, queue, index + layer, level);
        }
    }

    private static void spread(byte[] light, byte[] opacity, IntArrayFIFOQueue queue, int index, int level) {
        int next = level - Math.max(1, opacity[index]);
        if (next > light[index]) {
            light[index] = (byte) next;
            queue.enqueue(index);
        }
    }
}
