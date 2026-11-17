package me.methodial.kraz.scene;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * Shared lighting, tinting and shading behaviour for everything that exposes scene blocks to Minecraft's renderers:
 * the live {@link BlockStore} and the immutable {@link BlockStore.Snapshot}s handed to the meshing threads.
 */
public abstract class BlockView implements BlockAndTintGetter {
    private static final LevelLightEngine NO_LIGHT_ENGINE = new LevelLightEngine(new LightChunkGetter() {
        @Override
        public @Nullable LightChunk getChunkForLighting(int chunkX, int chunkZ) {
            return null;
        }

        @Override
        public BlockGetter getLevel() {
            return EmptyBlockGetter.INSTANCE;
        }
    }, false, false);

    public abstract SceneEnvironment environment();

    /** Flood-filled light, or {@code null} when everything is fully sky lit. */
    protected abstract @Nullable LightData lightData();

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        if (!shade || !environment().shade()) {
            return 1.0f;
        }
        return switch (direction) {
            case DOWN -> 0.5f;
            case UP -> 1.0f;
            case NORTH, SOUTH -> 0.8f;
            case WEST, EAST -> 0.6f;
        };
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return NO_LIGHT_ENGINE;
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        LightData light = lightData();
        if (light == null) {
            return layer == LightLayer.SKY ? 15 : 0;
        }
        int packed = light.get(pos.getX(), pos.getY(), pos.getZ());
        return layer == LightLayer.SKY ? (packed >> 4) & 15 : packed & 15;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        LightData light = lightData();
        if (light == null) {
            return 15 - amount;
        }
        int packed = light.get(pos.getX(), pos.getY(), pos.getZ());
        return Math.max(((packed >> 4) & 15) - amount, packed & 15);
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        SceneEnvironment env = environment();
        if (resolver == BiomeColors.GRASS_COLOR_RESOLVER) {
            return env.grassColor();
        }
        if (resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER) {
            return env.foliageColor();
        }
        if (resolver == BiomeColors.WATER_COLOR_RESOLVER) {
            return env.waterColor();
        }
        Biome biome = fallbackBiome();
        return biome != null ? resolver.getColor(biome, pos.getX(), pos.getZ()) : 0xFFFFFF;
    }

    private static @Nullable Biome fallbackBiome() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        return level.registryAccess().registry(Registries.BIOME).map(r -> r.get(Biomes.PLAINS)).orElse(null);
    }

    @Override
    public int getHeight() {
        return 4096;
    }

    @Override
    public int getMinBuildHeight() {
        return -2048;
    }
}
