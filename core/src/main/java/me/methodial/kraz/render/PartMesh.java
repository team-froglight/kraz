package me.methodial.kraz.render;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import me.methodial.kraz.scene.BlockStore;
import me.methodial.kraz.scene.ScenePart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.joml.FrustumIntersection;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GPU geometry for the blocks of one {@link ScenePart}, split into 16³ sections with one vertex buffer per chunk
 * render layer. Sections are rebuilt only when their blocks change; building runs on background threads against
 * immutable snapshots and only the final upload happens on the render thread.
 */
final class PartMesh implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final List<RenderType> LAYERS = RenderType.chunkBufferLayers();
    private static final int TRANSLUCENT = LAYERS.indexOf(RenderType.translucent());
    /** Extra mesh slots after the chunk layers: masks of water, lava and light-emitting blocks for shaded scenes. */
    static final int WATER = LAYERS.size(), LAVA = WATER + 1, EMISSIVE = WATER + 2, SLOTS = WATER + 3;

    private static final ExecutorService WORKERS = createWorkers();
    private static boolean loggedBlockError;

    private static ExecutorService createWorkers() {
        int threads = Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "Kraz-Mesher-" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    /** One uploaded section. */
    private static final class Section {
        final int x, y, z;
        final VertexBuffer[] buffers = new VertexBuffer[SLOTS];
        MeshData.@Nullable SortState sortState;
        final Vector3f sortedFrom = new Vector3f(Float.NaN);

        Section(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        void close() {
            for (VertexBuffer buffer : buffers) {
                if (buffer != null) {
                    buffer.close();
                }
            }
        }
    }

    /** CPU-side result of meshing one section, waiting to be uploaded. */
    private static final class Built {
        final long key;
        final int version;
        final MeshData[] meshes = new MeshData[SLOTS];
        final List<ByteBufferBuilder> allocations = new ArrayList<>();
        MeshData.@Nullable SortState sortState;
        final Vector3f sortedFrom = new Vector3f();

        Built(long key, int version) {
            this.key = key;
            this.version = version;
        }

        void free() {
            for (MeshData mesh : meshes) {
                if (mesh != null) {
                    mesh.close();
                }
            }
            for (ByteBufferBuilder allocation : allocations) {
                allocation.close();
            }
        }
    }

    private final ScenePart part;
    private final it.unimi.dsi.fastutil.longs.LongSet dirty;
    private final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap versions = new Long2IntOpenHashMap();
    private final List<CompletableFuture<Built>> pending = new ArrayList<>();
    private final boolean[] layerUsed = new boolean[SLOTS];
    private int resourceGeneration = -1;
    private boolean materials;
    private boolean changedLastUpdate;

    PartMesh(ScenePart part) {
        this.part = part;
        this.dirty = part.store().trackDirtySections();
    }

    /**
     * Schedules rebuilds for dirty sections and uploads finished ones.
     *
     * @param cameraLocal camera position in part coordinates, used to depth-sort translucent quads
     * @param wait        block until every section is built and uploaded (used for exports)
     */
    /** Whether to also bake the material masks; changing it rebuilds everything. */
    void setMaterials(boolean on) {
        if (materials != on) {
            materials = on;
            part.store().markAllDirty();
        }
    }

    void update(Vector3f cameraLocal, boolean wait, int currentResourceGeneration) {
        boolean withMaterials = materials;
        BlockStore store = part.store();
        changedLastUpdate = false;
        if (resourceGeneration != currentResourceGeneration) {
            // Models and atlas coordinates changed under us: everything on the GPU is stale.
            resourceGeneration = currentResourceGeneration;
            store.markAllDirty();
        }
        if (!dirty.isEmpty()) {
            for (LongIterator it = dirty.iterator(); it.hasNext(); ) {
                long key = it.nextLong();
                int version = versions.addTo(key, 1) + 1;
                if (!store.hasSection(key)) {
                    Section old = sections.remove(key);
                    if (old != null) {
                        old.close();
                        changedLastUpdate = true;
                    }
                    continue;
                }
                BlockStore.Snapshot snapshot = store.snapshot(key);
                Vector3f sortFrom = new Vector3f(cameraLocal);
                pending.add(wait
                        ? CompletableFuture.completedFuture(build(snapshot, key, version, sortFrom, withMaterials))
                        : CompletableFuture.supplyAsync(() -> build(snapshot, key, version, sortFrom, withMaterials), WORKERS));
            }
            dirty.clear();
        }
        for (Iterator<CompletableFuture<Built>> it = pending.iterator(); it.hasNext(); ) {
            CompletableFuture<Built> future = it.next();
            if (!wait && !future.isDone()) {
                continue;
            }
            it.remove();
            Built built;
            try {
                built = future.join();
            } catch (Exception e) {
                LOGGER.error("Kraz: section meshing failed", e);
                continue;
            }
            upload(built);
        }
        resortTranslucent(cameraLocal);
    }

    boolean busy() {
        return !pending.isEmpty();
    }

    /** Whether the last {@link #update} changed what is on the GPU. */
    boolean changed() {
        return changedLastUpdate;
    }

    boolean hasLayer(int layer) {
        return layerUsed[layer];
    }

    private void upload(Built built) {
        if (versions.get(built.key) != built.version) {
            built.free();
            return;
        }
        Section old = sections.remove(built.key);
        if (old != null) {
            old.close();
        }
        Section section = null;
        for (int i = 0; i < built.meshes.length; i++) {
            MeshData mesh = built.meshes[i];
            if (mesh == null) {
                continue;
            }
            if (section == null) {
                section = new Section(SectionPos.x(built.key), SectionPos.y(built.key), SectionPos.z(built.key));
            }
            VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            buffer.upload(mesh);
            built.meshes[i] = null;
            section.buffers[i] = buffer;
            layerUsed[i] = true;
        }
        VertexBuffer.unbind();
        if (section != null) {
            section.sortState = built.sortState;
            section.sortedFrom.set(built.sortedFrom);
            sections.put(built.key, section);
        }
        built.free();
        changedLastUpdate = true;
    }

    private void resortTranslucent(Vector3f cameraLocal) {
        if (TRANSLUCENT < 0 || !layerUsed[TRANSLUCENT]) {
            return;
        }
        for (Section section : sections.values()) {
            if (section.sortState == null || section.buffers[TRANSLUCENT] == null) {
                continue;
            }
            float x = cameraLocal.x - (section.x << 4), y = cameraLocal.y - (section.y << 4), z = cameraLocal.z - (section.z << 4);
            if (section.sortedFrom.distanceSquared(x, y, z) < 1.0f) {
                continue;
            }
            section.sortedFrom.set(x, y, z);
            try (ByteBufferBuilder scratch = new ByteBufferBuilder(section.sortState.centroids().length * 6 * 4)) {
                ByteBufferBuilder.Result indices = section.sortState.buildSortedIndexBuffer(scratch, VertexSorting.byDistance(x, y, z));
                if (indices != null) {
                    VertexBuffer buffer = section.buffers[TRANSLUCENT];
                    buffer.bind();
                    buffer.uploadIndexBuffer(indices);
                    VertexBuffer.unbind();
                    changedLastUpdate = true;
                }
            }
        }
    }

    /**
     * Draws one layer of every visible section. The shader must already be applied; this only moves
     * {@code ChunkOffset} between sections.
     */
    void draw(int layer, @Nullable Uniform chunkOffset, FrustumIntersection frustum) {
        if (!layerUsed[layer]) {
            return;
        }
        for (Section section : sections.values()) {
            VertexBuffer buffer = section.buffers[layer];
            if (buffer == null) {
                continue;
            }
            float x = section.x << 4, y = section.y << 4, z = section.z << 4;
            if (!frustum.testAab(x, y, z, x + 16, y + 16, z + 16)) {
                continue;
            }
            if (chunkOffset != null) {
                chunkOffset.set(x, y, z);
                chunkOffset.upload();
            }
            buffer.bind();
            buffer.draw();
        }
    }

    private static Built build(BlockStore.Snapshot region, long key, int version, Vector3f cameraLocal, boolean materials) {
        Built built = new Built(key, version);
        BlockState[] states = region.centerStates();
        if (states == null) {
            return built;
        }
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        BufferBuilder[] builders = new BufferBuilder[SLOTS];
        PoseStack pose = new PoseStack();
        RandomSource random = RandomSource.create();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int ox = region.sectionX() << 4, oy = region.sectionY() << 4, oz = region.sectionZ() << 4;

        ModelBlockRenderer.enableCaching();
        try {
            for (int i = 0; i < 4096; i++) {
                BlockState state = states[i];
                if (state == null) {
                    continue;
                }
                int lx = i & 15, ly = i >> 8, lz = (i >> 4) & 15;
                pos.set(ox + lx, oy + ly, oz + lz);
                try {
                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) {
                        // The liquid renderer emits section-relative coordinates on its own.
                        dispatcher.renderLiquid(pos, region, builder(built, builders, ItemBlockRenderTypes.getRenderLayer(fluid)), state, fluid);
                        if (materials) {
                            int mask = fluid.is(net.minecraft.tags.FluidTags.LAVA) ? LAVA : fluid.is(net.minecraft.tags.FluidTags.WATER) ? WATER : -1;
                            if (mask >= 0) {
                                dispatcher.renderLiquid(pos, region, builder(built, builders, mask), state, fluid);
                            }
                        }
                    }
                    if (state.getRenderShape() == RenderShape.MODEL) {
                        boolean emissive = materials && fluid.isEmpty() && state.getLightEmission(region, pos) > 0;
                        var model = dispatcher.getBlockModel(state);
                        var modelData = model.getModelData(region, pos, state, region.getModelData(pos));
                        random.setSeed(state.getSeed(pos));
                        for (RenderType type : model.getRenderTypes(state, random, modelData)) {
                            pose.pushPose();
                            pose.translate(lx, ly, lz);
                            dispatcher.renderBatched(state, pos, region, pose, builder(built, builders, type), true, random, modelData, type);
                            if (materials && emissive) {
                                // Same quads again, as the mask shaded scenes use to make light sources glow.
                                dispatcher.renderBatched(state, pos, region, pose, builder(built, builders, EMISSIVE), true, random, modelData, type);
                            }
                            pose.popPose();
                        }
                    }
                } catch (Exception e) {
                    if (!loggedBlockError) {
                        loggedBlockError = true;
                        LOGGER.error("Kraz: failed to mesh {} (further errors are not logged)", state, e);
                    }
                }
            }
        } finally {
            ModelBlockRenderer.clearCache();
        }

        for (int i = 0; i < builders.length; i++) {
            if (builders[i] == null) {
                continue;
            }
            MeshData mesh = builders[i].build();
            if (mesh == null) {
                continue;
            }
            if (i == TRANSLUCENT) {
                ByteBufferBuilder indexStorage = new ByteBufferBuilder(mesh.drawState().indexCount() * 4);
                built.allocations.add(indexStorage);
                built.sortedFrom.set(cameraLocal.x - ox, cameraLocal.y - oy, cameraLocal.z - oz);
                built.sortState = mesh.sortQuads(indexStorage, VertexSorting.byDistance(built.sortedFrom));
            }
            built.meshes[i] = mesh;
        }
        return built;
    }

    private static BufferBuilder builder(Built built, BufferBuilder[] builders, RenderType type) {
        int layer = LAYERS.indexOf(type);
        if (layer < 0) {
            throw new IllegalStateException(type + " is not a chunk render layer");
        }
        return builder(built, builders, layer);
    }

    private static BufferBuilder builder(Built built, BufferBuilder[] builders, int layer) {
        BufferBuilder builder = builders[layer];
        if (builder == null) {
            // Starts small and grows on demand; freed right after the upload.
            ByteBufferBuilder storage = new ByteBufferBuilder(layer == 0 ? 1 << 17 : 1 << 14);
            built.allocations.add(storage);
            builder = new BufferBuilder(storage, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            builders[layer] = builder;
        }
        return builder;
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        for (CompletableFuture<Built> future : pending) {
            future.thenAccept(Built::free);
        }
        pending.clear();
        for (Long2ObjectMap.Entry<Section> entry : sections.long2ObjectEntrySet()) {
            entry.getValue().close();
        }
        sections.clear();

        part.store().untrackDirtySections(dirty);
    }
}
