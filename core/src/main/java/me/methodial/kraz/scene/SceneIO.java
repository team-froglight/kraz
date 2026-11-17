package me.methodial.kraz.scene;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import me.methodial.kraz.camera.SceneCamera;
import me.methodial.kraz.scene.element.BlockElement;
import me.methodial.kraz.scene.element.EntityElement;
import me.methodial.kraz.scene.element.ItemElement;
import me.methodial.kraz.scene.element.SceneElement;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Saving, loading and capturing scenes.
 *
 * <p>The native format is gzip-compressed NBT holding every part (blocks, block entity data, serializable
 * elements, transform), the camera, the environment and all timeline tracks bound to scene properties. Vanilla
 * structure files ({@code .nbt} as written by structure blocks) can be loaded as well.
 */
public final class SceneIO {
    private static final int FORMAT_VERSION = 1;

    private SceneIO() {
    }

    // ------------------------------------------------------------------ files

    /** {@code <game dir>/kraz/scenes/<name>.nbt} */
    public static Path scenePath(String name) {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("kraz").resolve("scenes")
                .resolve(name.replaceAll("[^A-Za-z0-9._-]", "_") + ".nbt");
    }

    public static void write(Scene scene, Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        NbtIo.writeCompressed(save(scene), path);
    }

    /** Reads either a Kraz scene or a vanilla structure file. */
    public static Scene read(Path path) throws IOException {
        return fromTag(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()));
    }

    /**
     * Loads a scene shipped in a resource pack or mod jar, e.g. {@code mymod:kraz/intro.nbt} for
     * {@code assets/mymod/kraz/intro.nbt}. Accepts Kraz scenes and vanilla structure files.
     */
    public static Scene read(ResourceManager resources, ResourceLocation location) throws IOException {
        Resource resource = resources.getResourceOrThrow(location);
        try (InputStream in = resource.open()) {
            return fromTag(NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()));
        }
    }

    public static Scene fromTag(CompoundTag tag) {
        if (tag.contains("kraz_version")) {
            return load(tag);
        }
        Scene scene = Scene.create();
        placeStructure(scene.main(), tag, BlockPos.ZERO);
        return scene.fitCamera();
    }

    // ------------------------------------------------------------------ native format

    public static CompoundTag save(Scene scene) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("kraz_version", FORMAT_VERSION);
        tag.putInt("background", scene.background());

        SceneEnvironment env = scene.environment();
        CompoundTag envTag = new CompoundTag();
        envTag.putString("light_mode", env.lightMode().name());
        envTag.putFloat("sky_brightness", env.skyBrightness());
        envTag.putFloat("gamma", env.gamma());
        envTag.putBoolean("shade", env.shade());
        envTag.putInt("grass", env.grassColor());
        envTag.putInt("foliage", env.foliageColor());
        envTag.putInt("water", env.waterColor());
        tag.put("environment", envTag);

        SceneCamera camera = scene.camera();
        CompoundTag cameraTag = new CompoundTag();
        putVector(cameraTag, "target", camera.target());
        cameraTag.putFloat("yaw", camera.yaw());
        cameraTag.putFloat("pitch", camera.pitch());
        cameraTag.putFloat("distance", camera.distance());
        cameraTag.putFloat("fov", camera.fov());
        cameraTag.putFloat("ortho_height", camera.orthoHeight());
        cameraTag.putBoolean("orthographic", camera.orthographic());
        tag.put("camera", cameraTag);

        ListTag parts = new ListTag();
        for (ScenePart part : scene.parts()) {
            parts.add(savePart(part));
        }
        tag.put("parts", parts);
        tag.put("timeline", scene.timeline().save());
        return tag;
    }

    private static CompoundTag savePart(ScenePart part) {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", part.name());
        putVector(tag, "offset", part.offset());
        putVector(tag, "pivot", part.pivot());
        Quaternionf q = part.rotation();
        tag.putIntArray("rotation", new int[]{Float.floatToIntBits(q.x), Float.floatToIntBits(q.y), Float.floatToIntBits(q.z), Float.floatToIntBits(q.w)});
        tag.putFloat("scale", part.scale());
        tag.putFloat("alpha", part.alpha());
        tag.putBoolean("visible", part.visible());

        // Blocks as a palette plus two parallel arrays; gzip takes care of the rest.
        Object2IntOpenHashMap<BlockState> paletteIndex = new Object2IntOpenHashMap<>();
        ListTag palette = new ListTag();
        LongArrayList positions = new LongArrayList();
        IntArrayList states = new IntArrayList();
        part.store().forEachBlock((x, y, z, state) -> {
            int index = paletteIndex.computeIfAbsent(state, (BlockState s) -> {
                palette.add(NbtUtils.writeBlockState(s));
                return palette.size() - 1;
            });
            positions.add(BlockPos.asLong(x, y, z));
            states.add(index);
        });
        tag.put("palette", palette);
        tag.putLongArray("positions", positions.toLongArray());
        tag.putIntArray("states", states.toIntArray());

        ListTag blockEntities = new ListTag();
        part.store().blockEntityData().forEach((pos, data) -> {
            CompoundTag entry = new CompoundTag();
            entry.putLong("pos", pos);
            entry.put("data", data);
            blockEntities.add(entry);
        });
        tag.put("block_entities", blockEntities);

        ListTag elements = new ListTag();
        for (SceneElement element : part.elements()) {
            CompoundTag saved = element.save();
            if (saved != null) {
                elements.add(saved);
            }
        }
        tag.put("elements", elements);
        return tag;
    }

    public static Scene load(CompoundTag tag) {
        return loadInto(Scene.create(), tag);
    }

    /**
     * Replaces the contents of an existing scene with a saved one. Anything displaying the scene keeps working,
     * so this is also how the editor implements undo.
     */
    public static Scene loadInto(Scene scene, CompoundTag tag) {
        scene.reset();
        scene.setBackground(tag.getInt("background"));

        CompoundTag envTag = tag.getCompound("environment");
        if (!envTag.isEmpty()) {
            SceneEnvironment.LightMode mode;
            try {
                mode = SceneEnvironment.LightMode.valueOf(envTag.getString("light_mode"));
            } catch (IllegalArgumentException e) {
                mode = SceneEnvironment.LightMode.FULL_BRIGHT;
            }
            scene.setEnvironment(new SceneEnvironment(mode, envTag.getFloat("sky_brightness"), envTag.getFloat("gamma"),
                    envTag.getBoolean("shade"), envTag.getInt("grass"), envTag.getInt("foliage"), envTag.getInt("water")));
        }

        ListTag parts = tag.getList("parts", Tag.TAG_COMPOUND);
        for (int i = 0; i < parts.size(); i++) {
            loadPart(scene, parts.getCompound(i));
        }

        CompoundTag cameraTag = tag.getCompound("camera");
        if (cameraTag.isEmpty()) {
            scene.fitCamera();
        } else {
            scene.fitCamera(); // establishes clip planes for the content; framing is overridden below
            scene.camera()
                    .setTarget(getVector(cameraTag, "target"))
                    .setYaw(cameraTag.getFloat("yaw"))
                    .setPitch(cameraTag.getFloat("pitch"))
                    .setDistance(cameraTag.getFloat("distance"))
                    .setFov(cameraTag.getFloat("fov"))
                    .setOrthoHeight(cameraTag.getFloat("ortho_height"))
                    .setOrthographic(cameraTag.getBoolean("orthographic"));
        }
        scene.timeline().load(tag.getCompound("timeline"));
        return scene;
    }

    private static void loadPart(Scene scene, CompoundTag tag) {
        ScenePart part = scene.part(tag.getString("name"));
        part.setOffset(getVector(tag, "offset"));
        part.setPivot(getVector(tag, "pivot"));
        int[] q = tag.getIntArray("rotation");
        if (q.length == 4) {
            part.setRotation(new Quaternionf(Float.intBitsToFloat(q[0]), Float.intBitsToFloat(q[1]), Float.intBitsToFloat(q[2]), Float.intBitsToFloat(q[3])));
        }
        part.setScale(tag.contains("scale") ? tag.getFloat("scale") : 1.0f);
        part.setAlpha(tag.contains("alpha") ? tag.getFloat("alpha") : 1.0f);
        part.setVisible(!tag.contains("visible") || tag.getBoolean("visible"));

        List<BlockState> palette = readPalette(tag.getList("palette", Tag.TAG_COMPOUND));
        long[] positions = tag.getLongArray("positions");
        int[] states = tag.getIntArray("states");
        int count = Math.min(positions.length, states.length);
        for (int i = 0; i < count; i++) {
            if (states[i] >= 0 && states[i] < palette.size()) {
                long pos = positions[i];
                part.store().setBlock(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos), palette.get(states[i]), null);
            }
        }
        ListTag blockEntities = tag.getList("block_entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < blockEntities.size(); i++) {
            CompoundTag entry = blockEntities.getCompound(i);
            BlockPos pos = BlockPos.of(entry.getLong("pos"));
            part.setBlock(pos, part.getBlockState(pos), entry.getCompound("data"));
        }
        ListTag elements = tag.getList("elements", Tag.TAG_COMPOUND);
        for (int i = 0; i < elements.size(); i++) {
            CompoundTag element = elements.getCompound(i);
            SceneElement loaded = loadElement(element);
            if (loaded != null) {
                part.add(loaded);
            }
        }
    }

    /** Recreates an element from what its {@code save()} wrote; {@code null} for unknown types. */
    public static @org.jetbrains.annotations.Nullable SceneElement loadElement(CompoundTag element) {
        return switch (element.getString("type")) {
            case BlockElement.TYPE -> BlockElement.load(element);
            case EntityElement.TYPE -> EntityElement.load(element);
            case ItemElement.TYPE -> ItemElement.load(element);
            default -> null;
        };
    }

    private static List<BlockState> readPalette(ListTag list) {
        List<BlockState> palette = new ArrayList<>(list.size());
        var blocks = BuiltInRegistries.BLOCK.asLookup();
        for (int i = 0; i < list.size(); i++) {
            // Unknown blocks (a mod that is no longer installed) read back as air.
            palette.add(NbtUtils.readBlockState(blocks, list.getCompound(i)));
        }
        return palette;
    }

    // ------------------------------------------------------------------ vanilla structures

    /**
     * Places a vanilla structure template (the NBT of a structure-block {@code .nbt} file) into a part, including
     * block entity data and entities.
     */
    public static void placeStructure(ScenePart part, CompoundTag structure, BlockPos origin) {
        List<BlockState> palette = readPalette(structure.getList("palette", Tag.TAG_COMPOUND));
        ListTag blocks = structure.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            ListTag pos = block.getList("pos", Tag.TAG_INT);
            int state = block.getInt("state");
            if (pos.size() != 3 || state < 0 || state >= palette.size()) {
                continue;
            }
            BlockState blockState = palette.get(state);
            if (blockState.is(Blocks.STRUCTURE_VOID)) {
                continue;
            }
            int x = origin.getX() + pos.getInt(0), y = origin.getY() + pos.getInt(1), z = origin.getZ() + pos.getInt(2);
            CompoundTag data = block.contains("nbt", Tag.TAG_COMPOUND) ? block.getCompound("nbt").copy() : null;
            part.store().setBlock(x, y, z, blockState, data);
        }
        ListTag entities = structure.getList("entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag entry = entities.getCompound(i);
            ListTag pos = entry.getList("pos", Tag.TAG_DOUBLE);
            if (pos.size() != 3 || !entry.contains("nbt", Tag.TAG_COMPOUND)) {
                continue;
            }
            part.add(entityElement(entry.getCompound("nbt"),
                    (float) (origin.getX() + pos.getDouble(0)), (float) (origin.getY() + pos.getDouble(1)), (float) (origin.getZ() + pos.getDouble(2))));
        }
    }

    // ------------------------------------------------------------------ capture

    /**
     * Copies a box of a live world into a new scene, with the box's minimum corner at the scene origin.
     *
     * @param entities also capture non-player entities inside the box
     */
    public static Scene capture(Level level, BlockPos a, BlockPos b, boolean entities) {
        Scene scene = Scene.create();
        ScenePart part = scene.main();
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            CompoundTag data = null;
            if (state.hasBlockEntity()) {
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity != null) {
                    data = blockEntity.saveWithFullMetadata(level.registryAccess());
                }
            }
            part.store().setBlock(pos.getX() - min.getX(), pos.getY() - min.getY(), pos.getZ() - min.getZ(), state, data);
        }
        if (entities) {
            AABB box = new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
            for (Entity entity : level.getEntities((Entity) null, box, e -> !(e instanceof Player))) {
                CompoundTag data = new CompoundTag();
                if (entity.save(data)) {
                    part.add(entityElement(data,
                            (float) (entity.getX() - min.getX()), (float) (entity.getY() - min.getY()), (float) (entity.getZ() - min.getZ()))
                            .setYaw(entity.getYRot()).setHeadPitch(entity.getXRot()));
                }
            }
        }
        return scene.fitCamera();
    }

    private static EntityElement entityElement(CompoundTag data, float x, float y, float z) {
        CompoundTag copy = data.copy();
        // The saved absolute position is meaningless inside a scene; the element carries its own.
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z));
        copy.put("Pos", pos);
        copy.remove("UUID");
        float yaw = 0;
        ListTag rotation = copy.getList("Rotation", Tag.TAG_FLOAT);
        if (rotation.size() == 2) {
            yaw = rotation.getFloat(0);
        }
        return new EntityElement(copy).setPosition(x, y, z).setYaw(yaw);
    }

    private static void putVector(CompoundTag tag, String key, Vector3f v) {
        tag.putIntArray(key, new int[]{Float.floatToIntBits(v.x), Float.floatToIntBits(v.y), Float.floatToIntBits(v.z)});
    }

    private static Vector3f getVector(CompoundTag tag, String key) {
        int[] bits = tag.getIntArray(key);
        return bits.length == 3
                ? new Vector3f(Float.intBitsToFloat(bits[0]), Float.intBitsToFloat(bits[1]), Float.intBitsToFloat(bits[2]))
                : new Vector3f();
    }
}
