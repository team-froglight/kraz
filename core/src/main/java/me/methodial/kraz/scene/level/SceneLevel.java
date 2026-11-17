package me.methodial.kraz.scene.level;

import me.methodial.kraz.scene.BlockStore;
import me.methodial.kraz.scene.ScenePart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.util.AbortableIterationConsumer;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A minimal, inert {@link Level} that exposes a {@link ScenePart}'s blocks. It exists so block entities and
 * entities placed in a scene have a level to belong to; it never ticks, plays sounds or talks to a server.
 *
 * <p>Registries, recipes and similar lookups are borrowed from the client world it was created from.
 */
public final class SceneLevel extends Level {
    private final ScenePart part;
    private final BlockStore store;
    private final ClientLevel source;
    private final Holder<Biome> biome;
    private final TickRateManager tickRateManager = new TickRateManager();
    private final Source chunkSource = new Source();
    private @Nullable EmptyLevelChunk emptyChunk;

    private SceneLevel(ScenePart part, ClientLevel source) {
        super(new ClientLevel.ClientLevelData(Difficulty.PEACEFUL, false, true), source.dimension(), source.registryAccess(),
                source.dimensionTypeRegistration(), source.getProfilerSupplier(), true, false, 0L, 1_000_000);
        this.part = part;
        this.store = part.store();
        this.source = source;
        var biomes = source.registryAccess().registryOrThrow(Registries.BIOME);
        this.biome = biomes.getHolder(Biomes.PLAINS).map(h -> (Holder<Biome>) h)
                .orElseGet(() -> source.getBiome(BlockPos.ZERO));
    }

    public static SceneLevel create(ScenePart part, ClientLevel source) {
        return new SceneLevel(part, source);
    }

    /** The client world whose registries this level borrows. */
    public ClientLevel source() {
        return source;
    }

    public ScenePart part() {
        return part;
    }

    // ------------------------------------------------------------------ blocks

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return store.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return store.getFluidState(pos);
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return store.getBlockEntity(pos);
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        return store.setBlock(pos, state);
    }

    @Override
    public boolean isLoaded(BlockPos pos) {
        return true;
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        return store.getBrightness(layer, pos);
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        return store.getRawBrightness(pos, amount);
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return store.getShade(direction, shade);
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return store.getBlockTint(pos, resolver);
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return biome;
    }

    @Override
    public ChunkSource getChunkSource() {
        return chunkSource;
    }

    // ------------------------------------------------------------------ inert plumbing

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
    }

    @Override
    public void playSeededSound(@Nullable Player player, double x, double y, double z, Holder<SoundEvent> sound,
                                SoundSource category, float volume, float pitch, long seed) {
    }

    @Override
    public void playSeededSound(@Nullable Player player, Entity entity, Holder<SoundEvent> sound, SoundSource category,
                                float volume, float pitch, long seed) {
    }

    @Override
    public String gatherChunkSourceStats() {
        return "Kraz scene";
    }

    @Override
    public @Nullable Entity getEntity(int id) {
        return null;
    }

    @Override
    public TickRateManager tickRateManager() {
        return tickRateManager;
    }

    @Override
    public @Nullable MapItemSavedData getMapData(MapId mapId) {
        return source.getMapData(mapId);
    }

    @Override
    public void setMapData(MapId mapId, MapItemSavedData mapData) {
    }

    @Override
    public MapId getFreeMapId() {
        return new MapId(0);
    }

    @Override
    public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {
    }

    @Override
    public Scoreboard getScoreboard() {
        return source.getScoreboard();
    }

    @Override
    public RecipeManager getRecipeManager() {
        return source.getRecipeManager();
    }

    @Override
    protected LevelEntityGetter<Entity> getEntities() {
        return NO_ENTITIES;
    }

    @Override
    public PotionBrewing potionBrewing() {
        return source.potionBrewing();
    }

    @Override
    public void setDayTimeFraction(float dayTimeFraction) {
    }

    @Override
    public float getDayTimeFraction() {
        return 0;
    }

    @Override
    public float getDayTimePerTick() {
        return 0;
    }

    @Override
    public void setDayTimePerTick(float dayTimePerTick) {
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {
    }

    @Override
    public void gameEvent(Holder<GameEvent> gameEvent, Vec3 pos, GameEvent.Context context) {
    }

    @Override
    public List<? extends Player> players() {
        return Collections.emptyList();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return source.enabledFeatures();
    }

    private EmptyLevelChunk emptyChunk() {
        if (emptyChunk == null) {
            emptyChunk = new EmptyLevelChunk(this, new ChunkPos(0, 0), biome);
        }
        return emptyChunk;
    }

    private final class Source extends ChunkSource {
        private final LevelLightEngine lightEngine = new LevelLightEngine(this, false, false);

        @Override
        public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean require) {
            return emptyChunk();
        }

        @Override
        public void tick(BooleanSupplier hasTimeLeft, boolean tickChunks) {
        }

        @Override
        public String gatherStats() {
            return "Kraz scene";
        }

        @Override
        public int getLoadedChunksCount() {
            return 0;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return lightEngine;
        }

        @Override
        public Level getLevel() {
            return SceneLevel.this;
        }
    }

    private static final LevelEntityGetter<Entity> NO_ENTITIES = new LevelEntityGetter<>() {
        @Override
        public @Nullable Entity get(int id) {
            return null;
        }

        @Override
        public @Nullable Entity get(UUID uuid) {
            return null;
        }

        @Override
        public Iterable<Entity> getAll() {
            return Collections.emptyList();
        }

        @Override
        public <U extends Entity> void get(EntityTypeTest<Entity, U> test, AbortableIterationConsumer<U> consumer) {
        }

        @Override
        public void get(AABB boundingBox, Consumer<Entity> consumer) {
        }

        @Override
        public <U extends Entity> void get(EntityTypeTest<Entity, U> test, AABB bounds, AbortableIterationConsumer<U> consumer) {
        }
    };
}
