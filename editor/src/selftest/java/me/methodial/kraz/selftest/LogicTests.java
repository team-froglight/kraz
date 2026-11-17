package me.methodial.kraz.selftest;

import it.unimi.dsi.fastutil.longs.LongSet;
import me.methodial.kraz.anim.Easing;
import me.methodial.kraz.anim.Timeline;
import me.methodial.kraz.anim.Track;
import me.methodial.kraz.camera.SceneCamera;
import me.methodial.kraz.demo.DemoScenes;
import me.methodial.kraz.scene.BlockStore;
import me.methodial.kraz.scene.Scene;
import me.methodial.kraz.scene.SceneEnvironment;
import me.methodial.kraz.scene.SceneIO;
import me.methodial.kraz.scene.ScenePart;
import me.methodial.kraz.scene.element.BlockElement;
import me.methodial.kraz.scene.element.EntityElement;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Tests that need no GL context: animation, saving, block storage, lighting, camera maths. */
final class LogicTests {
    private final Checks c;
    private final Path out;

    LogicTests(Checks checks, Path out) {
        this.c = checks;
        this.out = out;
    }

    void run() throws Exception {
        easing();
        tracks();
        timelinePlayback();
        timelineEvents();
        boundTracks();
        storage();
        lighting();
        camera();
        partTransform();
        saving();
        structureFile();
    }

    // ------------------------------------------------------------------ animation

    private void easing() {
        c.section("Animation: easing curves");
        boolean endpoints = true, finite = true;
        for (Easing easing : Easing.values()) {
            float start = easing.apply(0), end = easing.apply(1);
            if (Math.abs(start) > 1e-4 || Math.abs(end - 1) > 1e-4) {
                endpoints = false;
                c.info(easing + ": f(0)=" + start + " f(1)=" + end);
            }
            for (int i = 0; i <= 100; i++) {
                if (!Float.isFinite(easing.apply(i / 100f))) {
                    finite = false;
                }
            }
        }
        c.check("every easing starts at 0 and ends at 1", endpoints);
        c.check("every easing is finite on 0..1", finite);
        c.near("LINEAR midpoint", 0.5, Easing.LINEAR.apply(0.5f), 1e-6);
        c.near("IN_QUAD(0.5)", 0.25, Easing.IN_QUAD.apply(0.5f), 1e-6);
        c.near("OUT_QUAD(0.5)", 0.75, Easing.OUT_QUAD.apply(0.5f), 1e-6);
        c.near("IN_OUT_CUBIC(0.5)", 0.5, Easing.IN_OUT_CUBIC.apply(0.5f), 1e-6);
        c.near("IN_OUT_SINE(0.5)", 0.5, Easing.IN_OUT_SINE.apply(0.5f), 1e-6);
        c.check("OUT_BACK overshoots", Easing.OUT_BACK.apply(0.6f) > 1.0f);
        c.check("IN_BACK undershoots", Easing.IN_BACK.apply(0.2f) < 0.0f);
        c.near("STEP holds before the key", 0, Easing.STEP.apply(0.99f), 0);
        boolean monotonic = true;
        for (Easing easing : new Easing[]{Easing.IN_QUAD, Easing.OUT_QUAD, Easing.IN_OUT_QUAD, Easing.IN_CUBIC, Easing.OUT_CUBIC,
                Easing.IN_OUT_CUBIC, Easing.IN_SINE, Easing.OUT_SINE, Easing.IN_OUT_SINE, Easing.IN_EXPO, Easing.OUT_EXPO}) {
            for (int i = 1; i <= 100; i++) {
                if (easing.apply(i / 100f) < easing.apply((i - 1) / 100f) - 1e-6) {
                    monotonic = false;
                    c.info(easing + " decreases at " + i);
                }
            }
        }
        c.check("non-overshooting easings are monotonic", monotonic);
    }

    private void tracks() {
        c.section("Animation: tracks");
        Scene scene = Scene.create();
        Timeline t = scene.timeline();
        float[] sink = new float[1];
        Track<Float> track = t.floatTrack(v -> sink[0] = v);
        c.check("empty track samples to null", track.sample(5) == null);
        track.key(20, 10f).key(0, 0f).key(10, 100f);
        c.eq("keys are kept sorted when added out of order", List.of(0f, 10f, 20f), track.keys().stream().map(Track.Key::time).toList());
        c.near("value before first key", 0, track.sample(-5), 0);
        c.near("value at first key", 0, track.sample(0), 0);
        c.near("linear interpolation", 50, track.sample(5), 1e-4);
        c.near("value at middle key", 100, track.sample(10), 1e-4);
        c.near("second segment", 55, track.sample(15), 1e-4);
        c.near("value after last key", 10, track.sample(99), 0);
        c.near("duration", 20, track.duration(), 0);

        Track<Float> eased = t.floatTrack(v -> {
        }).key(0, 0f).key(10, 8f, Easing.IN_QUAD);
        c.near("easing of the later key shapes the segment", 2, eased.sample(5), 1e-4);
        Track<Float> stepped = t.floatTrack(v -> {
        }).key(0, 1f).key(10, 2f, Easing.STEP);
        c.near("STEP holds the previous value", 1, stepped.sample(9.99f), 0);
        c.near("STEP jumps at the key", 2, stepped.sample(10), 0);

        Vector3f source = new Vector3f(0, 0, 0);
        Track<Vector3f> vec = t.vec3Track(v -> {
        }).key(0, source).key(10, new Vector3f(10, 20, -30));
        source.set(99, 99, 99);
        c.eq("vector keys are copied, not aliased", new Vector3f(0, 0, 0), vec.sample(0));
        c.eq("vector interpolation", new Vector3f(5, 10, -15), vec.sample(5));

        Track<Quaternionf> quat = t.quatTrack(q -> {
        }).key(0, new Quaternionf()).key(10, new Quaternionf().rotateY((float) Math.PI / 2));
        Vector3f rotated = quat.sample(5).transform(new Vector3f(1, 0, 0));
        c.near("quaternion slerp midpoint is a 45° turn (x)", Math.cos(Math.PI / 4), rotated.x, 1e-4);
        c.near("quaternion slerp midpoint is a 45° turn (z)", -Math.sin(Math.PI / 4), rotated.z, 1e-4);

        t.seek(5);
        c.near("seek applies tracks to their targets", 50, sink[0], 1e-4);
    }

    private void timelinePlayback() {
        c.section("Animation: playback");
        Scene scene = Scene.create();
        Timeline t = scene.timeline();
        c.check("new timeline is empty", t.isEmpty());
        t.advance(10);
        c.near("advancing an empty timeline does nothing", 0, t.time(), 0);
        t.cameraYaw().key(0, 0f).key(100, 100f);
        c.near("length follows the last key", 100, t.length(), 0);
        t.setLength(40);
        c.near("explicit length wins", 40, t.length(), 0);

        t.setLoop(true).setPlaying(true);
        t.seek(0);
        t.advance(10);
        c.near("advance moves time", 10, t.time(), 1e-5);
        c.near("advance drives the camera", 10, scene.camera().yaw(), 1e-4);
        t.advance(35);
        c.near("looping wraps around", 5, t.time(), 1e-4);
        c.check("still playing after a loop", t.playing());

        t.setSpeed(2);
        t.seek(0);
        t.advance(5);
        c.near("speed scales time", 10, t.time(), 1e-5);
        t.setSpeed(1);

        t.setPlaying(false);
        t.advance(5);
        c.near("paused timeline does not advance", 10, t.time(), 0);

        t.setLoop(false).setPlaying(true);
        t.advance(500);
        c.near("non-looping playback clamps at the end", 40, t.time(), 0);
        c.check("non-looping playback stops at the end", !t.playing());
        t.seek(-5);
        c.near("seek clamps below zero", 0, t.time(), 0);
    }

    private void timelineEvents() {
        c.section("Animation: events");
        Scene scene = Scene.create();
        Timeline t = scene.timeline();
        List<String> log = new ArrayList<>();
        t.event(20, () -> log.add("b"), () -> log.add("-b"));
        t.event(10, () -> log.add("a"), () -> log.add("-a"));
        t.event(30, () -> log.add("c"));
        t.setLength(40).setLoop(true);

        t.seek(5);
        c.eq("nothing fires before the first event", List.of(), log);
        t.seek(10);
        c.eq("event fires exactly at its time", List.of("a"), log);
        t.seek(15);
        c.eq("event does not fire twice", List.of("a"), log);
        t.seek(35);
        c.eq("skipped events fire in order", List.of("a", "b", "c"), log);
        log.clear();
        t.seek(12);
        c.eq("scrubbing back undoes in reverse order, skipping events without undo", List.of("-b"), log);
        log.clear();
        t.seek(0);
        c.eq("scrubbing to the start undoes the rest", List.of("-a"), log);
        log.clear();
        t.setPlaying(true);
        t.advance(39);
        t.advance(3);
        c.eq("a loop replays: all fire, then all undo on wrap", List.of("a", "b", "c", "-b", "-a"), log);

        // The documented use: an event that edits blocks, reversible by scrubbing.
        ScenePart part = scene.main();
        BlockPos pos = new BlockPos(1, 2, 3);
        Timeline t2 = Scene.create().timeline();
        t2.event(10, () -> part.setBlock(pos, Blocks.GOLD_BLOCK.defaultBlockState()), () -> part.removeBlock(pos));
        t2.seek(10);
        c.eq("event placed a block", Blocks.GOLD_BLOCK.defaultBlockState(), part.getBlockState(pos));
        t2.seek(0);
        c.check("undo removed it again", part.getBlockState(pos).isAir());
    }

    private void boundTracks() {
        c.section("Animation: tracks bound to the scene");
        Scene scene = Scene.create();
        Timeline t = scene.timeline();
        ScenePart part = scene.part("arm");
        t.cameraYaw().key(0, 10f).key(10, 30f);
        t.cameraPitch().key(0, 0f).key(10, 40f);
        t.cameraDistance().key(0, 5f).key(10, 15f);
        t.cameraFov().key(0, 30f).key(10, 60f);
        t.cameraOrthoHeight().key(0, 4f).key(10, 8f);
        t.cameraTarget().key(0, new Vector3f()).key(10, new Vector3f(2, 4, 6));
        t.offset(part).key(0, new Vector3f()).key(10, new Vector3f(0, 10, 0));
        t.pivot(part).key(0, new Vector3f()).key(10, new Vector3f(1, 1, 1));
        t.rotation(part).key(0, new Quaternionf()).key(10, new Quaternionf().rotateY((float) Math.PI));
        t.scale(part).key(0, 1f).key(10, 3f);
        t.alpha(part).key(0, 0f).key(10, 1f);
        c.check("asking for the same binding twice returns the same track", t.cameraYaw() == t.cameraYaw());
        c.eq("track count", 11, t.tracks().size());

        long before = scene.version();
        t.seek(5);
        SceneCamera cam = scene.camera();
        c.near("camera yaw", 20, cam.yaw(), 1e-4);
        c.near("camera pitch", 20, cam.pitch(), 1e-4);
        c.near("camera distance", 10, cam.distance(), 1e-4);
        c.near("camera fov", 45, cam.fov(), 1e-4);
        c.near("camera ortho height", 6, cam.orthoHeight(), 1e-4);
        c.eq("camera target", new Vector3f(1, 2, 3), cam.target());
        c.eq("part offset", new Vector3f(0, 5, 0), part.offset());
        c.eq("part pivot", new Vector3f(0.5f, 0.5f, 0.5f), part.pivot());
        c.near("part scale", 2, part.scale(), 1e-4);
        c.near("part alpha", 0.5, part.alpha(), 1e-4);
        c.near("part rotation is a quarter turn", Math.PI / 2, part.rotation().angle(), 1e-3);
        c.check("animating bumps the scene version (drives redraw)", scene.version() != before);

        boolean threw = false;
        try {
            t.bound("camera/nonsense");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        c.check("unknown binding is rejected", threw);
    }

    // ------------------------------------------------------------------ storage & light

    private void storage() {
        c.section("Scene: block storage");
        Scene scene = Scene.create();
        ScenePart part = scene.main();
        BlockStore store = part.store();
        BlockState stone = Blocks.STONE.defaultBlockState();
        c.check("new store is empty", store.isEmpty() && store.bounds() == null);
        c.check("unset block reads as air", store.getBlockState(new BlockPos(5, 5, 5)).isAir());

        LongSet dirty = store.trackDirtySections();
        part.setBlock(new BlockPos(-1, -17, 33), stone);
        c.eq("negative coordinates round-trip", stone, store.getBlockState(new BlockPos(-1, -17, 33)));
        c.eq("bounds of one block", "-1,-17,33..-1,-17,33", boundsString(store));
        c.check("change marks its section dirty", dirty.contains(SectionPos.asLong(-1, -2, 2)));
        long revision = store.revision();
        part.setBlock(new BlockPos(-1, -17, 33), stone);
        c.check("setting the same state again is a no-op", store.revision() == revision);
        part.removeBlock(new BlockPos(-1, -17, 33));
        c.check("removing the last block empties the store", store.isEmpty() && store.bounds() == null);

        dirty.clear();
        part.setBlock(new BlockPos(16, 0, 0), stone);
        c.check("a block on a section border also dirties the neighbour (culling/AO)", dirty.contains(SectionPos.asLong(0, 0, 0)));
        c.check("...but not unrelated sections", !dirty.contains(SectionPos.asLong(2, 0, 0)));
        dirty.clear();
        part.setBlock(new BlockPos(24, 8, 8), stone);
        c.eq("an interior block dirties only its own section", 1, dirty.size());

        LongSet second = store.trackDirtySections();
        c.check("a second renderer starts with every section dirty", second.contains(SectionPos.asLong(1, 0, 0)));
        dirty.clear();
        second.clear();
        part.setBlock(new BlockPos(20, 5, 5), stone);
        c.check("both renderers hear about a change", dirty.size() == 1 && second.size() == 1);
        store.untrackDirtySections(second);
        second.clear();
        part.setBlock(new BlockPos(21, 5, 5), stone);
        c.check("an untracked renderer no longer does", second.isEmpty());
        store.untrackDirtySections(dirty);

        part.clear();
        part.fill(new BlockPos(0, 0, 0), new BlockPos(3, 4, 5), stone);
        int[] count = {0};
        store.forEachBlock((x, y, z, state) -> count[0]++);
        c.eq("fill places every block", 4 * 5 * 6, count[0]);
        c.eq("fill bounds", "0,0,0..3,4,5", boundsString(store));

        BlockStore.Snapshot snapshot = store.snapshot(SectionPos.asLong(0, 0, 0));
        part.setBlock(new BlockPos(1, 1, 1), Blocks.GOLD_BLOCK.defaultBlockState());
        c.eq("a snapshot is unaffected by later edits (copy on write)", stone, snapshot.getBlockState(new BlockPos(1, 1, 1)));
        c.eq("the live store sees the edit", Blocks.GOLD_BLOCK.defaultBlockState(), store.getBlockState(new BlockPos(1, 1, 1)));
        c.check("a snapshot reads air outside its neighbourhood", snapshot.getBlockState(new BlockPos(100, 0, 0)).isAir());

        CompoundTag data = new CompoundTag();
        data.putString("marker", "x");
        part.setBlock(new BlockPos(0, 0, 0), Blocks.CHEST.defaultBlockState(), data);
        c.eq("block entity data is stored", data, store.getBlockEntityData(new BlockPos(0, 0, 0)));
        part.setBlock(new BlockPos(0, 0, 0), stone);
        c.check("replacing the block drops its data", store.getBlockEntityData(new BlockPos(0, 0, 0)) == null);

        Scene two = Scene.create();
        two.part("a");
        two.part("b");
        c.check("part() returns the existing part", two.part("a") == two.part("a"));
        c.eq("parts in creation order", List.of("main", "a", "b"), two.parts().stream().map(ScenePart::name).toList());
        c.check("findPart of an unknown name is null", two.findPart("zzz") == null);
    }

    private static String boundsString(BlockStore store) {
        var b = store.bounds();
        return b == null ? "null" : b.minX() + "," + b.minY() + "," + b.minZ() + ".." + b.maxX() + "," + b.maxY() + "," + b.maxZ();
    }

    private void lighting() {
        c.section("Scene: lighting");
        Scene scene = Scene.create();
        ScenePart part = scene.main();
        BlockStore store = part.store();
        BlockState stone = Blocks.STONE.defaultBlockState();
        // A sealed 7³ stone shell with a 5³ hollow inside.
        part.fill(new BlockPos(0, 0, 0), new BlockPos(6, 6, 6), stone);
        part.fill(new BlockPos(1, 1, 1), new BlockPos(5, 5, 5), Blocks.AIR.defaultBlockState());
        BlockPos middle = new BlockPos(3, 3, 3);

        c.eq("FULL_BRIGHT: sky light is 15 everywhere", 15, store.getBrightness(LightLayer.SKY, middle));
        c.eq("FULL_BRIGHT: no block light", 0, store.getBrightness(LightLayer.BLOCK, middle));

        scene.setEnvironment(SceneEnvironment.DEFAULT.withLightMode(SceneEnvironment.LightMode.COMPUTED));
        c.eq("COMPUTED: sealed room has no sky light", 0, store.getBrightness(LightLayer.SKY, middle));
        c.eq("COMPUTED: sealed room has no block light", 0, store.getBrightness(LightLayer.BLOCK, middle));
        c.eq("COMPUTED: open air above is fully lit", 15, store.getBrightness(LightLayer.SKY, new BlockPos(3, 7, 3)));
        c.eq("COMPUTED: far outside the scene is fully lit", 15, store.getBrightness(LightLayer.SKY, new BlockPos(500, 0, 500)));

        part.setBlock(new BlockPos(1, 1, 1), Blocks.GLOWSTONE.defaultBlockState());
        c.eq("a light source lights its neighbour to 14", 14, store.getBrightness(LightLayer.BLOCK, new BlockPos(2, 1, 1)));
        c.eq("block light falls off by one per block", 15 - 6, store.getBrightness(LightLayer.BLOCK, middle));
        c.eq("raw brightness combines both", 9, store.getRawBrightness(middle, 0));
        part.removeBlock(new BlockPos(1, 1, 1));
        c.eq("removing the source removes the light", 0, store.getBrightness(LightLayer.BLOCK, middle));

        part.removeBlock(new BlockPos(3, 6, 3));
        c.eq("a hole in the roof lets sky light straight down", 15, store.getBrightness(LightLayer.SKY, new BlockPos(3, 1, 3)));
        c.eq("...and it spreads sideways, dimming", 13, store.getBrightness(LightLayer.SKY, new BlockPos(5, 1, 3)));

        c.near("shade: top faces are full bright", 1.0, store.getShade(Direction.UP, true), 0);
        c.near("shade: bottom faces are darkest", 0.5, store.getShade(Direction.DOWN, true), 0);
        scene.setEnvironment(scene.environment().withShade(false));
        c.near("shade can be turned off", 1.0, store.getShade(Direction.DOWN, true), 0);
        c.eq("tints come from the environment", 0x91BD59, store.getBlockTint(middle, net.minecraft.client.renderer.BiomeColors.GRASS_COLOR_RESOLVER));
    }

    private void camera() {
        c.section("Scene: camera");
        SceneCamera cam = new SceneCamera().setTarget(1, 2, 3).setYaw(0).setPitch(0).setOrthographic(false).setDistance(10);
        c.eq("yaw 0 / pitch 0 looks from +Z", new Vector3f(1, 2, 13), cam.position(new Vector3f()));
        cam.setYaw(90);
        Vector3f p = cam.position(new Vector3f());
        c.check("yaw 90 looks from +X", Math.abs(p.x - 11) < 1e-4 && Math.abs(p.z - 3) < 1e-4);
        cam.setPitch(200);
        c.near("pitch is clamped short of straight down", 89.9, cam.pitch(), 1e-4);
        cam.setYaw(0).setPitch(0);
        Vector3f projected = cam.viewMatrix(new Matrix4f()).transformPosition(new Vector3f(1, 2, 3));
        c.check("the target sits on the view axis", Math.abs(projected.x) < 1e-4 && Math.abs(projected.y) < 1e-4 && projected.z < 0);
        cam.pan(2, 1);
        c.check("pan moves the target along the view plane", cam.target().distance(3, 3, 3) < 1e-4, cam.target().toString());
        cam.setOrthographic(true).setOrthoHeight(8).setDistance(10);
        cam.zoom(0.5f);
        c.check("zoom scales both projections", cam.orthoHeight() == 4 && cam.distance() == 5);

        Scene scene = Scene.create();
        scene.main().fill(new BlockPos(0, 0, 0), new BlockPos(9, 9, 9), Blocks.STONE.defaultBlockState());
        scene.fitCamera();
        c.eq("fitCamera centres on the content", new Vector3f(5, 5, 5), scene.camera().target());
        Matrix4f clip = scene.camera().projectionMatrix(1, new Matrix4f()).mul(scene.camera().viewMatrix(new Matrix4f()));
        boolean inside = true;
        for (int i = 0; i < 8; i++) {
            Vector3f corner = clip.transformProject(new Vector3f((i & 1) * 10, ((i >> 1) & 1) * 10, ((i >> 2) & 1) * 10));
            inside &= Math.abs(corner.x) <= 1 && Math.abs(corner.y) <= 1 && Math.abs(corner.z) <= 1;
        }
        c.check("fitCamera keeps the whole scene in view (orthographic)", inside);
        scene.camera().setOrthographic(false);
        clip = scene.camera().projectionMatrix(1, new Matrix4f()).mul(scene.camera().viewMatrix(new Matrix4f()));
        inside = true;
        for (int i = 0; i < 8; i++) {
            Vector3f corner = clip.transformProject(new Vector3f((i & 1) * 10, ((i >> 1) & 1) * 10, ((i >> 2) & 1) * 10));
            inside &= Math.abs(corner.x) <= 1 && Math.abs(corner.y) <= 1 && Math.abs(corner.z) <= 1;
        }
        c.check("fitCamera keeps the whole scene in view (perspective)", inside);
    }

    private void partTransform() {
        c.section("Scene: part transforms");
        ScenePart part = Scene.create().part("p");
        c.eq("identity by default", new Vector3f(1, 2, 3), part.matrix().transformPosition(new Vector3f(1, 2, 3)));
        part.setOffset(10, 0, 0);
        c.eq("offset translates", new Vector3f(11, 2, 3), part.matrix().transformPosition(new Vector3f(1, 2, 3)));
        part.setOffset(0, 0, 0).setPivot(5, 0, 5).setRotation(0, 180, 0);
        Vector3f turned = part.matrix().transformPosition(new Vector3f(6, 0, 5));
        c.check("rotation happens around the pivot", turned.distance(4, 0, 5) < 1e-4, turned.toString());
        c.check("the pivot itself stays put", part.matrix().transformPosition(new Vector3f(5, 0, 5)).distance(5, 0, 5) < 1e-4);
        part.setRotation(0, 0, 0).setScale(2);
        c.check("scale happens around the pivot", part.matrix().transformPosition(new Vector3f(6, 1, 5)).distance(7, 2, 5) < 1e-4);
        part.setAlpha(5);
        c.near("alpha is clamped", 1, part.alpha(), 0);
    }

    // ------------------------------------------------------------------ saving

    private Scene richScene() {
        Scene scene = Scene.create();
        scene.setBackground(0x80123456);
        scene.setEnvironment(new SceneEnvironment(SceneEnvironment.LightMode.COMPUTED, 0.3f, 0.8f, false, 0x112233, 0x445566, 0x778899));
        ScenePart main = scene.main();
        main.fill(new BlockPos(-3, 0, -3), new BlockPos(20, 1, 20), Blocks.STONE.defaultBlockState());
        main.setBlock(new BlockPos(0, 2, 0), Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.WEST).setValue(StairBlock.HALF, Half.TOP).setValue(StairBlock.WATERLOGGED, true));
        main.setBlock(new BlockPos(1, 2, 0), Blocks.WATER.defaultBlockState());
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putString("CustomName", "{\"text\":\"Loot\"}");
        chest.putIntArray("some_ints", new int[]{1, 2, 3});
        main.setBlock(new BlockPos(2, 2, 0), Blocks.CHEST.defaultBlockState(), chest);
        main.add(new BlockElement(Blocks.DIAMOND_BLOCK.defaultBlockState())).setPosition(1.5f, 4, 2.25f).setRotation(10, 20, 30).setScale(0.5f);
        CompoundTag cow = new CompoundTag();
        cow.putString("id", "minecraft:cow");
        cow.putInt("Age", -100);
        main.add(new EntityElement(cow)).setPosition(4, 2, 4).setYaw(77).setHeadPitch(-12);

        ScenePart arm = scene.part("arm");
        arm.setBlock(new BlockPos(5, 5, 5), Blocks.PISTON.defaultBlockState());
        arm.setOffset(1, 2, 3).setPivot(5.5f, 5, 5.5f).setRotation(15, 30, 45).setScale(1.5f).setAlpha(0.25f).setVisible(false);

        scene.camera().setTarget(3, 2, 1).setYaw(123).setPitch(-20).setDistance(33).setFov(55).setOrthoHeight(12).setOrthographic(false);

        Timeline t = scene.timeline();
        t.cameraYaw().key(0, 0f).key(50, 180f, Easing.IN_OUT_CUBIC).key(100, 360f, Easing.OUT_BOUNCE);
        t.cameraTarget().key(0, new Vector3f(1, 2, 3)).key(100, new Vector3f(4, 5, 6), Easing.OUT_BACK);
        t.offset(arm).key(10, new Vector3f(0, 5, 0)).key(30, new Vector3f(), Easing.OUT_ELASTIC);
        t.rotation(arm).key(0, new Quaternionf()).key(40, new Quaternionf().rotateXYZ(0.1f, 0.2f, 0.3f), Easing.STEP);
        t.scale(arm).key(0, 0.1f).key(20, 1f, Easing.IN_EXPO);
        t.alpha(arm).key(0, 0f).key(20, 1f);
        t.floatTrack(v -> {
        }).key(0, 0f).key(10, 1f); // custom: intentionally not saved
        t.setLength(120).setLoop(false).setSpeed(0.5f);
        return scene;
    }

    private void saving() throws Exception {
        c.section("Saving: native format");
        Scene original = richScene();

        CompoundTag tag = SceneIO.save(original);
        Scene fromTag = SceneIO.load(tag);
        c.eq("save -> load in memory preserves everything", List.of(), SceneDiff.diff(original, fromTag));
        c.eq("fromTag recognises the native format", List.of(), SceneDiff.diff(original, SceneIO.fromTag(tag)));

        Path file = out.resolve("logic_roundtrip.nbt");
        SceneIO.write(original, file);
        Scene fromFile = SceneIO.read(file);
        c.eq("write -> read on disk preserves everything", List.of(), SceneDiff.diff(original, fromFile));
        c.check("file is gzip-compressed NBT", Files.size(file) > 0 && Files.size(file) < 4096, Files.size(file) + " bytes for 1154 blocks");
        Scene twice = SceneIO.load(SceneIO.save(fromFile));
        c.eq("a second generation is identical too", List.of(), SceneDiff.diff(original, twice));

        c.eq("blockstate properties survive", original.main().getBlockState(new BlockPos(0, 2, 0)), fromFile.main().getBlockState(new BlockPos(0, 2, 0)));
        c.eq("block entity data survives", original.main().store().getBlockEntityData(new BlockPos(2, 2, 0)),
                fromFile.main().store().getBlockEntityData(new BlockPos(2, 2, 0)));
        c.eq("bound tracks are saved, custom ones are not", 6, fromFile.timeline().tracks().size());

        // Animation must *behave* identically after a round trip, not just hold equal keys.
        boolean same = true;
        for (float time = 0; time <= 120; time += 3.5f) {
            original.timeline().seek(time);
            fromFile.timeline().seek(time);
            ScenePart a = original.findPart("arm"), b = fromFile.findPart("arm");
            if (original.camera().yaw() != fromFile.camera().yaw() || !original.camera().target().equals(fromFile.camera().target())
                    || !a.offset().equals(b.offset()) || !a.rotation().equals(b.rotation()) || a.scale() != b.scale() || a.alpha() != b.alpha()) {
                same = false;
                c.info("animation diverges at t=" + time);
            }
        }
        c.check("loaded animation plays back identically at every sampled time", same);

        Scene saveMidway = richScene();
        saveMidway.timeline().seek(25);
        Scene midway = SceneIO.load(SceneIO.save(saveMidway));
        c.eq("saving mid-animation keeps the animated pose", saveMidway.findPart("arm").offset(), midway.findPart("arm").offset());

        c.section("Saving: robustness");
        CompoundTag empty = new CompoundTag();
        empty.putInt("kraz_version", 1);
        Scene blank = SceneIO.fromTag(empty);
        c.check("an empty scene file loads as an empty scene", blank.parts().size() == 1 && blank.bounds() == null);
        CompoundTag damaged = SceneIO.save(original);
        damaged.getList("parts", 10).getCompound(0).getList("palette", 10).getCompound(0).putString("Name", "nomod:missing_block");
        damaged.getCompound("timeline").getList("tracks", 10).getCompound(0).putString("binding", "camera/unknown");
        damaged.getCompound("timeline").getList("tracks", 10).getCompound(1).getList("keys", 10).getCompound(0).putString("easing", "NOT_AN_EASING");
        Scene tolerant = SceneIO.load(damaged);
        c.check("unknown blocks, bindings and easings are skipped instead of failing", tolerant.parts().size() == 2,
                "tracks kept: " + tolerant.timeline().tracks().size());
        c.eq("scenePath sanitises names", "a_b_.._c.nbt", SceneIO.scenePath("a/b\\..:c").getFileName().toString());
        c.check("scenePath stays inside kraz/scenes", SceneIO.scenePath("../../evil").getParent().endsWith(Path.of("kraz", "scenes")));

        c.section("Saving: demo scene");
        Scene demo = DemoScenes.island();
        Scene demoLoaded = SceneIO.load(SceneIO.save(demo));
        c.eq("the demo scene round-trips (minus its item element, which is not serialisable)",
                List.of(), SceneDiff.diff(demo, demoLoaded));
    }

    private void structureFile() throws Exception {
        c.section("Saving: vanilla structure files");
        CompoundTag structure;
        try (InputStream in = Minecraft.class.getResourceAsStream("/data/minecraft/structure/igloo/top.nbt")) {
            if (in == null) {
                c.check("vanilla igloo structure found on the classpath", false);
                return;
            }
            structure = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
        int expected = 0;
        var palette = structure.getList("palette", 10);
        var blocks = structure.getList("blocks", 10);
        for (int i = 0; i < blocks.size(); i++) {
            String name = palette.getCompound(blocks.getCompound(i).getInt("state")).getString("Name");
            if (!name.equals("minecraft:air") && !name.equals("minecraft:structure_void")) {
                expected++;
            }
        }
        Scene scene = SceneIO.fromTag(structure);
        int[] count = {0};
        scene.main().store().forEachBlock((x, y, z, state) -> count[0]++);
        c.eq("every non-air block of the igloo is placed", expected, count[0]);
        var size = structure.getList("size", 3);
        var bounds = scene.bounds();
        c.check("bounds fit inside the structure's declared size", bounds != null && bounds.minX() >= 0 && bounds.maxX() < size.getInt(0)
                && bounds.maxY() < size.getInt(1) && bounds.maxZ() < size.getInt(2));
        Path file = out.resolve("igloo.nbt");
        NbtIo.writeCompressed(structure, file);
        Scene fromFile = SceneIO.read(file);
        c.eq("SceneIO.read accepts a structure file directly", List.of(), SceneDiff.diff(scene, fromFile));
        c.eq("a structure converts losslessly to the native format", List.of(), SceneDiff.diff(scene, SceneIO.load(SceneIO.save(scene))));
    }
}
