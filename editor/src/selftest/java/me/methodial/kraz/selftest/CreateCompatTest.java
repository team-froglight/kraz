package me.methodial.kraz.selftest;

import com.mojang.blaze3d.platform.NativeImage;
import me.methodial.kraz.export.SceneExporter;
import me.methodial.kraz.render.RenderSettings;
import me.methodial.kraz.scene.Scene;
import me.methodial.kraz.scene.SceneIO;
import me.methodial.kraz.scene.ScenePart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.lwjgl.opengl.GL11;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs only when Create is installed. Create draws its machines through its own renderer (Flywheel instancing)
 * in the real world; this checks what happens to those blocks inside a Kraz scene. Blocks are looked up by id so
 * the test needs no compile-time dependency on Create.
 */
final class CreateCompatTest {
    private final Checks c;
    private final Path out;
    private Scene machines;
    private Scene kineticOnly;
    private NativeImage firstFrame;

    CreateCompatTest(Checks checks, Path out) {
        this.c = checks;
        this.out = out;
    }

    private static Block block(String name) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", name));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState with(BlockState state, String property, String value) {
        for (Property p : state.getProperties()) {
            if (p.getName().equals(property)) {
                var parsed = p.getValue(value);
                if (parsed.isPresent()) {
                    return state.setValue(p, (Comparable) parsed.get());
                }
            }
        }
        return state;
    }

    private static CompoundTag spinning(float speed) {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("Speed", speed);
        return tag;
    }

    Scene machines() {
        return machines;
    }

    /** First half: build and render. The second half runs some ticks later to observe animation. */
    void start() throws Exception {
        c.section("Compatibility: Create");
        String[] names = {"andesite_casing", "cogwheel", "large_cogwheel", "shaft", "gearbox", "creative_motor", "water_wheel",
                "mechanical_press", "depot", "basin", "mechanical_mixer", "fluid_tank", "encased_fan", "belt", "mechanical_bearing"};
        List<String> missing = new ArrayList<>();
        StringBuilder shapes = new StringBuilder();
        for (String name : names) {
            Block b = block(name);
            if (b == Blocks.AIR) {
                missing.add(name);
            } else {
                shapes.append(name).append('=').append(b.defaultBlockState().getRenderShape()).append(b.defaultBlockState().hasBlockEntity() ? "+BE " : " ");
            }
        }
        c.check("Create's blocks are registered", missing.isEmpty(), "missing: " + missing);
        c.info("render shapes: " + shapes);

        machines = Scene.create();
        ScenePart part = machines.main();
        part.fill(new BlockPos(0, 0, 0), new BlockPos(6, 0, 6), block("andesite_casing").defaultBlockState());
        part.setBlock(new BlockPos(1, 1, 1), with(block("cogwheel").defaultBlockState(), "axis", "y"), spinning(64));
        part.setBlock(new BlockPos(3, 1, 2), with(block("large_cogwheel").defaultBlockState(), "axis", "y"), spinning(-32));
        part.setBlock(new BlockPos(1, 1, 3), with(block("shaft").defaultBlockState(), "axis", "x"), spinning(64));
        part.setBlock(new BlockPos(2, 1, 3), with(block("shaft").defaultBlockState(), "axis", "x"), spinning(64));
        part.setBlock(new BlockPos(0, 1, 3), with(block("creative_motor").defaultBlockState(), "facing", "east"), spinning(64));
        part.setBlock(new BlockPos(3, 1, 3), with(block("gearbox").defaultBlockState(), "axis", "y"), spinning(64));
        part.setBlock(new BlockPos(5, 2, 5), with(block("water_wheel").defaultBlockState(), "facing", "north"), spinning(16));
        part.setBlock(new BlockPos(1, 1, 5), block("depot").defaultBlockState());
        part.setBlock(new BlockPos(1, 3, 5), block("mechanical_press").defaultBlockState(), spinning(64));
        part.setBlock(new BlockPos(3, 1, 5), block("basin").defaultBlockState());
        part.setBlock(new BlockPos(3, 3, 5), block("mechanical_mixer").defaultBlockState(), spinning(64));
        part.setBlock(new BlockPos(5, 1, 0), block("fluid_tank").defaultBlockState());
        part.setBlock(new BlockPos(5, 2, 0), block("fluid_tank").defaultBlockState());
        part.setBlock(new BlockPos(5, 1, 2), with(block("encased_fan").defaultBlockState(), "facing", "up"), spinning(128));
        part.setBlock(new BlockPos(0, 1, 0), with(block("mechanical_bearing").defaultBlockState(), "facing", "up"), spinning(16));
        machines.fitCamera();

        firstFrame = SceneExporter.renderImage(machines, 1024, 1024, RenderSettings.HIGH_QUALITY);
        firstFrame.writeToFile(out.resolve("create_machines.png"));
        c.check("a scene full of Create machines renders", Images.coverage(firstFrame) > 0.2, String.format("coverage %.2f", Images.coverage(firstFrame)));
        int expected = 0;
        int[] count = {0};
        part.store().forEachBlock((x, y, z, state) -> {
            if (state.hasBlockEntity()) {
                count[0]++;
            }
        });
        expected = count[0];
        c.eq("Create block entities were created inside the scene's level", expected, part.store().blockEntities().size());

        // Shafts and cogwheels have no static block model at all: whatever shows up here was drawn by Create's
        // own block entity renderers running inside the scene.
        kineticOnly = Scene.create();
        ScenePart kinetic = kineticOnly.main();
        kinetic.setBlock(new BlockPos(0, 0, 0), with(block("cogwheel").defaultBlockState(), "axis", "y"), spinning(64));
        kinetic.setBlock(new BlockPos(2, 0, 0), with(block("large_cogwheel").defaultBlockState(), "axis", "y"), spinning(64));
        kinetic.setBlock(new BlockPos(0, 0, 2), with(block("shaft").defaultBlockState(), "axis", Direction.Axis.X.getName()), spinning(64));
        kineticOnly.fitCamera();
        try (NativeImage image = SceneExporter.renderImage(kineticOnly, 512, 512, RenderSettings.HIGH_QUALITY)) {
            image.writeToFile(out.resolve("create_kinetic.png"));
            c.check("cogwheels and shafts (drawn only by Create's renderers) are visible", Images.coverage(image) > 0.03,
                    String.format("coverage %.3f", Images.coverage(image)));
        }

        // Which renderer is actually doing the work? Ask Flywheel whether it would instance each level, see which
        // block entity renderer Create registered, and render once more with the block entities taken away.
        var mc = net.minecraft.client.Minecraft.getInstance();
        try {
            Class<?> manager = Class.forName("dev.engine_room.flywheel.api.visualization.VisualizationManager");
            var supports = manager.getMethod("supportsVisualization", net.minecraft.world.level.LevelAccessor.class);
            boolean real = (boolean) supports.invoke(null, mc.level);
            boolean scene = (boolean) supports.invoke(null, kinetic.level());
            c.info("Flywheel instancing: real world=" + real + ", Kraz scene level=" + scene);
            c.check("Flywheel instancing is active in the real world (so this test means something)", real);
            c.check("Flywheel does not try to instance a Kraz scene; Create's fallback renderers are used instead", !scene);
        } catch (ReflectiveOperationException e) {
            c.error("querying Flywheel", e);
        }
        StringBuilder renderers = new StringBuilder();
        for (var blockEntity : kinetic.store().blockEntities().values()) {
            var renderer = mc.getBlockEntityRenderDispatcher().getRenderer(blockEntity);
            renderers.append(blockEntity.getClass().getSimpleName()).append(" -> ")
                    .append(renderer == null ? "none" : renderer.getClass().getName()).append("; ");
        }
        c.info("renderers: " + renderers);
        try (NativeImage full = SceneExporter.renderImage(kineticOnly, 256, 256, RenderSettings.FAST)) {
            kinetic.store().blockEntities().clear();
            try (NativeImage withoutBlockEntities = SceneExporter.renderImage(kineticOnly, 256, 256, RenderSettings.FAST)) {
                withoutBlockEntities.writeToFile(out.resolve("create_kinetic_static_only.png"));
                double all = Images.coverage(full), baked = Images.coverage(withoutBlockEntities);
                c.info(String.format("coverage with Create's renderers %.3f, baked block models alone %.3f", all, baked));
                c.check("the rotating parts come from Create's block entity renderers, not from baked block models", baked < all * 0.5,
                        String.format("%.3f vs %.3f", baked, all));
            }
        }

        // A second batch of blocks whose visible parts are custom-rendered in the real game.
        String[] more = {"mechanical_arm", "deployer", "mechanical_saw", "mechanical_drill", "millstone", "crushing_wheel", "flywheel",
                "steam_engine", "mechanical_pump", "spout", "rope_pulley", "clutch", "speedometer", "stressometer", "mechanical_crafter",
                "weighted_ejector", "hand_crank", "cuckoo_clock", "turntable", "rotation_speed_controller", "sequenced_gearshift",
                "windmill_bearing", "mechanical_piston", "gantry_shaft", "item_drain", "nixie_tube", "display_link", "schematicannon"};
        Scene gallery = Scene.create();
        ScenePart shelf = gallery.main();
        int placed = 0;
        List<String> absent = new ArrayList<>();
        for (int i = 0; i < more.length; i++) {
            Block b = block(more[i]);
            if (b == Blocks.AIR) {
                absent.add(more[i]);
                continue;
            }
            int x = (i % 7) * 2, z = (i / 7) * 2;
            shelf.setBlock(new BlockPos(x, 0, z), Blocks.SMOOTH_STONE.defaultBlockState());
            shelf.setBlock(new BlockPos(x, 1, z), b.defaultBlockState(), spinning(48));
            placed++;
        }
        gallery.fitCamera();
        try (NativeImage image = SceneExporter.renderImage(gallery, 1280, 1024, RenderSettings.HIGH_QUALITY)) {
            image.writeToFile(out.resolve("create_gallery.png"));
            c.check("a gallery of " + placed + " more Create machines renders", Images.coverage(image) > 0.1, "absent ids: " + absent);
        }
        c.eq("every gallery block entity was created", placed, shelf.store().blockEntities().size());

        Scene loaded = SceneIO.load(SceneIO.save(machines));
        c.eq("a Create scene round-trips through save/load", List.of(), SceneDiff.diff(machines, loaded));
        int error = GL11.glGetError();
        c.check("no OpenGL error after rendering Create content", error == 0, "0x" + Integer.toHexString(error));
    }

    void finish() throws Exception {
        try (NativeImage later = SceneExporter.renderImage(machines, 1024, 1024, RenderSettings.HIGH_QUALITY)) {
            later.writeToFile(out.resolve("create_machines_later.png"));
            int diff = Images.differing(firstFrame, later, 16);
            c.check("Create's kinetic animation plays inside the scene (frames differ over time)", diff > 300, diff + " px changed in 12 ticks");
        } finally {
            firstFrame.close();
        }
    }
}
