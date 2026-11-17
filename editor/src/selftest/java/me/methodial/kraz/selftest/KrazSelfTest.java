package me.methodial.kraz.selftest;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import me.methodial.kraz.demo.DemoScenes;
import me.methodial.kraz.export.SceneExporter;
import me.methodial.kraz.editor.SceneViewerScreen;
import me.methodial.kraz.render.RenderSettings;
import me.methodial.kraz.render.SceneCanvas;
import me.methodial.kraz.render.SceneRenderer;
import me.methodial.kraz.scene.Scene;
import me.methodial.kraz.scene.SceneIO;
import me.methodial.kraz.scene.ScenePart;
import me.methodial.kraz.scene.element.EntityElement;
import me.methodial.kraz.scene.element.SceneElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Development-only mod that drives Kraz through every feature without a human: it runs on the title screen, then
 * creates a throwaway flat world for the parts that need one, writes {@code report.txt} plus images to the
 * directory given by {@code -Dkraz.selftest=…} and quits. Run it with {@code gradlew runClientSelfTest}.
 */
@Mod(value = "kraz_selftest", dist = Dist.CLIENT)
public final class KrazSelfTest {
    private static final Logger LOGGER = LogUtils.getLogger();

    private interface Step {
        /** @return true when finished; false to be called again next tick */
        boolean run() throws Exception;
    }

    private record Named(String name, Step step) {
    }

    private final Checks c = new Checks();
    private final Path out;
    private final ArrayDeque<Named> steps = new ArrayDeque<>();
    private int wait;
    private int stepTicks;
    private boolean done;

    private Scene titleScene;
    private SceneCanvas titleCanvas;
    private me.methodial.kraz.render.shaded.ShadedSceneCanvas shadedCanvas;
    private RenderTests renderTests;

    private SceneRenderer worldRenderer;
    private Vec3 worldOrigin;
    private boolean drawInWorld;
    private boolean lockView;
    private NativeImage worldBefore;
    private final List<CompletableFuture<?>> worldExports = new ArrayList<>();
    private Scene viewerScene;
    private long exportsBefore;

    public KrazSelfTest() {
        String dir = System.getProperty("kraz.selftest");
        if (dir == null || dir.isBlank()) {
            out = null;
            LOGGER.warn("kraz_selftest is loaded but -Dkraz.selftest is not set; doing nothing");
            return;
        }
        out = Path.of(dir);
        buildPlan();
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> tick());
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Render.Post.class, this::onScreenRender);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, this::onLevelRender);
    }

    private void step(String name, Step step) {
        steps.add(new Named(name, step));
    }

    private void once(String name, ThrowingRunnable action) {
        step(name, () -> {
            action.run();
            return true;
        });
    }

    private void pause(int ticks) {
        once("pause", () -> wait = ticks);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void tick() {
        if (done) {
            return;
        }
        if (lockView && Minecraft.getInstance().player != null) {
            // The real mouse can nudge the view when the window regains focus; screenshots need a fixed camera.
            var player = Minecraft.getInstance().player;
            player.setYRot(0);
            player.setXRot(0);
            player.yRotO = 0;
            player.xRotO = 0;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        Named current = steps.peek();
        if (current == null) {
            finish();
            return;
        }
        try {
            if (current.step.run()) {
                steps.poll();
                stepTicks = 0;
            } else if (++stepTicks > 1200) {
                c.check("step '" + current.name + "' finished in time", false, "gave up after 60 s");
                steps.poll();
                stepTicks = 0;
            }
        } catch (Throwable t) {
            c.error("step '" + current.name + "'", t);
            LOGGER.error("Kraz self test step '{}' failed", current.name, t);
            steps.poll();
            stepTicks = 0;
        }
    }

    private void glClean(String where) {
        int error = GL11.glGetError();
        c.check("no OpenGL error after " + where, error == 0, "0x" + Integer.toHexString(error));
    }

    // ------------------------------------------------------------------ plan

    private void buildPlan() {
        Minecraft mc = Minecraft.getInstance();
        step("wait for title screen", () -> mc.screen instanceof TitleScreen && mc.getOverlay() == null);
        // Screenshot checks need a real framebuffer; a minimized window has a 1x1 one.
        step("wait for a visible window", () -> mc.getMainRenderTarget().width > 64 && mc.getMainRenderTarget().height > 64);
        once("environment", () -> {
            Files.createDirectories(out);
            // The test must keep running when the developer clicks into another window.
            mc.options.pauseOnLostFocus = false;
            c.section("Environment");
            c.info("mods: " + ModList.get().getMods().stream().map(m -> m.getModId() + " " + m.getVersion()).toList());
            c.info("sodium present: " + ModList.get().isLoaded("sodium") + ", create present: " + ModList.get().isLoaded("create"));
            c.info("GL: " + GL11.glGetString(GL11.GL_RENDERER) + " / " + GL11.glGetString(GL11.GL_VERSION));
        });
        once("logic tests", () -> new LogicTests(c, out).run());

        once("start gui embed", () -> {
            titleScene = DemoScenes.island();
            titleCanvas = new SceneCanvas(titleScene);
        });
        pause(40);
        step("gui embed: window visible", () -> mc.getMainRenderTarget().width > 64);
        once("gui embed", this::guiEmbed);
        step("shaded: window visible", () -> mc.getMainRenderTarget().width > 64);
        once("shaded: start", () -> {
            Scene shadedScene = DemoScenes.island();
            shadedScene.setEnvironment(shadedScene.environment().withShade(false));
            shadedScene.camera().setOrthographic(false).setPitch(18).setYaw(-140);
            shadedScene.timeline().setPlaying(false);
            shadedScene.timeline().seek(100);
            shadedCanvas = new me.methodial.kraz.render.shaded.ShadedSceneCanvas(shadedScene);
            shadedCanvas.shading().sun(new org.joml.Vector3f(0.45f, 0.5f, 0.6f)).time(12f);
        });
        pause(40);
        once("shaded: first look", () -> {
            try (NativeImage shot = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
                shot.writeToFile(out.resolve("shaded_backlit.png"));
            }
            // Now with the sun behind the camera, so shadows fall away from it across the grass.
            shadedCanvas.shading().sun(new org.joml.Vector3f(-0.75f, 0.55f, -0.2f));
        });
        pause(10);
        once("shaded: check", this::shadedCheck);
        once("render tests", () -> {
            renderTests = new RenderTests(c, out);
            renderTests.run();
        });
        step("wait for exports", () -> renderTests.pending.stream().allMatch(CompletableFuture::isDone));
        once("verify exports", () -> renderTests.verifyExports());

        once("enter world", this::enterWorld);
        step("wait for world", () -> mc.level != null && mc.player != null && mc.screen == null && mc.getOverlay() == null);
        pause(60);
        once("world setup", () -> {
            c.section("World: commands");
            mc.options.hideGui = true;
            mc.player.connection.sendCommand("time set midnight");
            mc.player.connection.sendCommand("tp @s 0.5 -60 0.5 0 0");
            mc.player.connection.sendCommand("fill -4 -60 3 4 -56 9 air");
            mc.player.connection.sendCommand("setblock 2 -60 5 chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:3}]}");
            mc.player.connection.sendCommand("setblock 0 -60 5 oak_sign{front_text:{messages:['\"Kraz\"','\"\"','\"\"','\"\"']}}");
            mc.player.connection.sendCommand("setblock -1 -60 5 torch");
            mc.player.connection.sendCommand("summon armor_stand -2.5 -60 5.5 {NoGravity:1b,ShowArms:1b,Tags:[\"kraztest\"]}");
        });
        pause(30);
        once("capture command", () -> mc.player.connection.sendCommand("kraz capture -4 -61 3 4 -56 9 selftest true"));
        pause(10);
        once("verify capture", this::verifyCapture);
        once("list command", () -> mc.player.connection.sendCommand("kraz list"));
        once("view command", () -> mc.player.connection.sendCommand("kraz view selftest"));
        pause(30);
        once("viewer", this::viewer);
        step("wait for viewer exports", () -> exportCount() >= exportsBefore + 2);
        once("viewer close", () -> {
            c.check("PNG and GIF buttons wrote files to kraz/exports", true, (exportCount() - exportsBefore) + " new files");
            mc.screen.onClose();
            c.check("closing the viewer returns to the game", mc.screen == null);
        });
        pause(5);
        once("world scene", this::worldScene);
        step("wait for world exports", () -> worldExports.stream().allMatch(CompletableFuture::isDone));
        once("verify world exports", () -> {
            for (CompletableFuture<?> future : worldExports) {
                c.check("world export completed", !future.isCompletedExceptionally(),
                        future.isCompletedExceptionally() ? String.valueOf(future.exceptionNow()) : String.valueOf(future.resultNow()));
            }
        });

        EditorTests editorTests = new EditorTests(c, out);
        int stageNumber = 0;
        for (EditorTests.Stage stage : editorTests.stages()) {
            once("editor stage " + (++stageNumber), stage::run);
            pause(8);
        }
        once("project command", () -> {
            c.section("World: /kraz project");
            mc.player.connection.sendCommand("kraz project selftest ~ ~ ~5 0.5 90");
        });
        pause(5);
        once("project command check", () -> {
            var all = me.methodial.kraz.world.WorldProjections.all();
            c.eq("/kraz project shows a saved scene in the world", 1, all.size());
            if (!all.isEmpty()) {
                c.near("...at the given scale", 0.5, all.getFirst().scale(), 1e-6);
                c.near("...and facing", 90, all.getFirst().yaw(), 1e-6);
            }
            mc.player.connection.sendCommand("kraz project clear");
        });
        pause(5);
        once("project clear check", () -> c.eq("/kraz project clear removes it", 0, me.methodial.kraz.world.WorldProjections.all().size()));

        if (ModList.get().isLoaded("create")) {
            CreateCompatTest create = new CreateCompatTest(c, out);
            once("create: start", create::start);
            pause(12);
            once("create: finish", create::finish);
            // Put it on screen too, so a person watching the run can see Create's machines turning.
            once("create: show", () -> {
                create.machines().timeline().cameraYaw().key(0, -35f).key(120, 325f);
                mc.setScreen(new SceneViewerScreen(create.machines(), "create", null));
            });
            pause(120);
            once("create: screenshot", () -> {
                try (NativeImage shot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                    shot.writeToFile(out.resolve("create_viewer.png"));
                }
                c.check("the Create scene was shown in the on-screen viewer", mc.screen instanceof SceneViewerScreen);
                mc.setScreen(null);
            });
            pause(5);
        }

        once("in-world: aim", () -> lockView = true);
        pause(20);
        once("in-world: before", () -> {
            c.section("World: scene drawn inside the level");
            worldBefore = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            Scene scene = DemoScenes.island();
            scene.timeline().seek(100);
            worldRenderer = new SceneRenderer(scene);
            worldOrigin = new Vec3(-4.5, -60, 9);
            drawInWorld = true;
        });
        pause(30);
        once("in-world: after", this::inWorldAfter);
        pause(10);
        once("in-world: restored", () -> {
            try (NativeImage restored = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                restored.writeToFile(out.resolve("inworld_restored.png"));
                int diff = Images.differing(worldBefore, restored, 60); // tolerant of torch flicker and drifting clouds
                c.check("the world renders normally again afterwards", diff < restored.getWidth() * restored.getHeight() / 20, diff + " px differ from before");
            }
            worldBefore.close();
            glClean("in-world drawing");
        });
    }

    // ------------------------------------------------------------------ title screen

    private void onScreenRender(ScreenEvent.Render.Post event) {
        if (shadedCanvas != null && event.getScreen() instanceof TitleScreen) {
            // The whole screen, as a shader-pack style backdrop would use it.
            shadedCanvas.draw(event.getGuiGraphics(), 0, 0, event.getScreen().width, event.getScreen().height, event.getPartialTick());
            return;
        }
        if (titleCanvas == null || !(event.getScreen() instanceof TitleScreen)) {
            return;
        }
        int w = event.getScreen().width, h = event.getScreen().height;
        event.getGuiGraphics().fill(10, 10, 10 + w / 2, h - 10, 0xFF20242C);
        // A scissor box is what a scrolling list would have active around an embedded scene.
        event.getGuiGraphics().enableScissor(10, 10, 10 + w / 2, h - 10);
        titleCanvas.draw(event.getGuiGraphics(), 10, 10, w / 2, h - 20, event.getPartialTick());
        event.getGuiGraphics().disableScissor();
        event.getGuiGraphics().drawString(Minecraft.getInstance().font, "drawn after the scene", 14, 14, 0xFFFFFFFF);
    }


    private void shadedCheck() throws Exception {
        c.section("Shaded scenes (shadow maps, sky, bloom)");
        Minecraft mc = Minecraft.getInstance();
        try (NativeImage shot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            shot.writeToFile(out.resolve("shaded.png"));
            c.check("geometry finished streaming in", !shadedCanvas.busy());
            c.check("the shaded pipeline produced a picture", Images.distinctColors(shot, 4000) > 2000, Images.distinctColors(shot, 4000) + " colours");
            int sky = shot.getPixelRGBA(shot.getWidth() / 2, 6);
            c.check("there is a sky above the scene", Images.blue(sky) > Images.red(sky) && Images.blue(sky) > 60, Images.describe(sky));
        }
        glClean("shaded rendering");
        shadedCanvas.close();
        shadedCanvas = null;
    }

    private void guiEmbed() throws Exception {
        c.section("GUI: scene embedded in a vanilla screen");
        Minecraft mc = Minecraft.getInstance();
        c.check("geometry finished streaming in", !titleCanvas.busy());
        c.check("SceneCanvas.draw leaves the timeline alone (only SceneWidget drives the clock)", titleScene.timeline().time() == 0);
        try (NativeImage screen = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            screen.writeToFile(out.resolve("gui_embed.png"));
            int w = screen.getWidth(), h = screen.getHeight();
            double scale = mc.getWindow().getGuiScale();
            int x0 = (int) (10 * scale), y0 = (int) (10 * scale), x1 = (int) ((10 + mc.screen.width / 2) * scale), y1 = (int) ((mc.screen.height - 10) * scale);
            int panel = 0, scenePixels = 0;
            for (int y = y0 + 2; y < y1 - 2; y++) {
                for (int x = x0 + 2; x < x1 - 2; x++) {
                    int p = screen.getPixelRGBA(x, y);
                    if (Images.red(p) == 0x20 && Images.green(p) == 0x24 && Images.blue(p) == 0x2C) {
                        panel++;
                    } else {
                        scenePixels++;
                    }
                }
            }
            c.check("the scene is composited over the panel", scenePixels > 2000 && panel > 2000, scenePixels + " scene px, " + panel + " panel px");
            // Right half of the screen is untouched title screen: it must not be the panel colour or black.
            int outside = screen.getPixelRGBA(w - 20, h / 2);
            c.check("nothing leaks outside the widget rectangle", !(Images.red(outside) == 0x20 && Images.green(outside) == 0x24),
                    Images.describe(outside));
        }
        c.check("vanilla keeps rendering after the scene (title screen still up)", mc.screen instanceof TitleScreen);
        glClean("GUI embedding");
        titleCanvas.close();
        titleCanvas = null;
    }

    // ------------------------------------------------------------------ world

    private void enterWorld() {
        Minecraft mc = Minecraft.getInstance();
        String name = "kraz_selftest_" + System.currentTimeMillis();
        LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(0L, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen());
    }

    private void verifyCapture() throws Exception {
        c.section("World: capture, block entities, entities");
        Path file = SceneIO.scenePath("selftest");
        c.check("/kraz capture wrote the scene file", Files.isRegularFile(file));
        Scene scene = SceneIO.read(file);
        ScenePart main = scene.main();
        // Capture origin is the box minimum (-4, -61, 3).
        c.eq("captured chest", Blocks.CHEST, main.getBlockState(new BlockPos(6, 1, 2)).getBlock());
        c.eq("captured sign", Blocks.OAK_SIGN, main.getBlockState(new BlockPos(4, 1, 2)).getBlock());
        c.eq("captured torch", Blocks.TORCH, main.getBlockState(new BlockPos(3, 1, 2)).getBlock());
        c.eq("captured ground", Blocks.GRASS_BLOCK, main.getBlockState(new BlockPos(0, 0, 0)).getBlock());
        var sign = main.store().getBlockEntityData(new BlockPos(4, 1, 2));
        c.check("sign text was captured as block entity data", sign != null && sign.toString().contains("Kraz"), String.valueOf(sign));
        c.check("chest block entity data was captured", main.store().getBlockEntityData(new BlockPos(6, 1, 2)) != null);
        long entities = main.elements().stream().filter(e -> e instanceof EntityElement).count();
        c.eq("the armor stand was captured as an entity element", 1L, entities);
        EntityElement stand = (EntityElement) main.elements().stream().filter(e -> e instanceof EntityElement).findFirst().orElseThrow();
        c.check("entity position is relative to the capture box", stand.position().distance(1.5f, 1, 2.5f) < 0.01f, stand.position().toString());
        c.eq("entity data kept its type", "minecraft:armor_stand", stand.data().getString("id"));
        c.check("the player was not captured", main.elements().stream().noneMatch(e -> e instanceof EntityElement ee && ee.data().getString("id").contains("player")));

        try (NativeImage image = SceneExporter.renderImage(scene, 768, 768, RenderSettings.HIGH_QUALITY)) {
            image.writeToFile(out.resolve("captured.png"));
            c.check("the captured scene renders", Images.coverage(image) > 0.2);
        }
        c.check("a fake level exists for block entities while in a world", main.level() != null);
        c.eq("block entities were instantiated from the captured data", 2, main.store().blockEntities().size());
        var signEntity = main.store().blockEntities().get(new BlockPos(4, 1, 2).asLong());
        c.check("the sign block entity loaded its text", signEntity instanceof net.minecraft.world.level.block.entity.SignBlockEntity s
                && s.getFrontText().getMessage(0, false).getString().equals("Kraz"));
        c.check("block entities belong to the scene's level, not the real one", signEntity != null && signEntity.getLevel() == main.level()
                && signEntity.getLevel() != Minecraft.getInstance().level);
        c.eq("the fake level serves scene blocks", Blocks.CHEST, main.level().getBlockState(new BlockPos(6, 1, 2)).getBlock());

        Scene again = SceneIO.load(SceneIO.save(scene));
        c.eq("a captured scene round-trips through save/load", List.of(), SceneDiff.diff(scene, again));

        // With and without the block entity/entity layer: the difference proves they are actually drawn.
        try (NativeImage full = SceneExporter.renderImage(scene, 256, 256, RenderSettings.FAST)) {
            main.removeBlock(new BlockPos(6, 1, 2));
            main.removeBlock(new BlockPos(4, 1, 2));
            for (SceneElement element : List.copyOf(main.elements())) {
                main.remove(element);
            }
            try (NativeImage bare = SceneExporter.renderImage(scene, 256, 256, RenderSettings.FAST)) {
                c.check("chest, sign and armor stand are visible in the render", Images.differing(full, bare, 16) > 150, Images.differing(full, bare, 16) + " px");
            }
        }
        glClean("capture tests");
    }

    private long exportCount() throws Exception {
        Path dir = SceneExporter.exportPath("x", "png").getParent();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("selftest")).count();
        }
    }

    /** Finds an Apothecary button by its label anywhere under a node. */
    static me.methodial.apothecary.widget.Button uiButton(me.methodial.apothecary.node.Node root, String label) {
        if (root instanceof me.methodial.apothecary.widget.Button b && b.label() != null && label.equals(b.label().text())) {
            return b;
        }
        for (me.methodial.apothecary.node.Node child : root.children()) {
            me.methodial.apothecary.widget.Button found = uiButton(child, label);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    static <T extends me.methodial.apothecary.node.Node> T uiFind(me.methodial.apothecary.node.Node root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        for (me.methodial.apothecary.node.Node child : root.children()) {
            T found = uiFind(child, type);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    static void uiClick(me.methodial.apothecary.node.Node node) {
        node.dispatchLocal(me.methodial.apothecary.event.EventType.CLICK, new me.methodial.apothecary.event.MouseEvent(0, 0, 0, 1, 0, 0));
    }

    private void viewer() throws Exception {
        c.section("GUI: viewer screen (Apothecary UI)");
        Minecraft mc = Minecraft.getInstance();
        c.check("/kraz view opened the viewer", mc.screen instanceof SceneViewerScreen, String.valueOf(mc.screen));
        if (!(mc.screen instanceof SceneViewerScreen)) {
            return;
        }
        try (NativeImage shot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            shot.writeToFile(out.resolve("viewer_capture.png"));
            c.check("the viewer draws the captured scene", Images.distinctColors(shot, 400) > 100, Images.distinctColors(shot, 400) + " colours");
        }
        // Swap in the animated demo so the transport controls have something to control.
        viewerScene = DemoScenes.island();
        SceneViewerScreen demo = new SceneViewerScreen(viewerScene, "selftest", null);
        mc.setScreen(demo);
        var root = demo.ui().rootNode();
        var play = uiButton(root, "Pause");
        var projection = uiButton(root, "Projection");
        var shaders = uiButton(root, "Shaders");
        var png = uiButton(root, "PNG");
        var gif = uiButton(root, "GIF");
        var slider = uiFind(root, me.methodial.apothecary.widget.Slider.class);
        var view = uiFind(root, me.methodial.apothecary.mc.node.SceneView.class);
        c.check("viewer has play, projection, shaders, PNG, GIF, a time slider and a scene view",
                play != null && projection != null && shaders != null && png != null && gif != null && slider != null && view != null);
        if (play == null || projection == null || shaders == null || png == null || gif == null || slider == null || view == null) {
            return;
        }
        var timeline = viewerScene.timeline();
        c.check("the demo starts playing", timeline.playing());
        uiClick(play);
        c.check("Pause pauses", !timeline.playing());
        uiClick(play);
        c.check("Play resumes", timeline.playing());
        boolean ortho = viewerScene.camera().orthographic();
        uiClick(projection);
        c.check("Projection toggles the camera", viewerScene.camera().orthographic() != ortho);
        uiClick(projection);

        slider.value(0.5f);
        c.near("moving the slider scrubs to that time", timeline.length() / 2, timeline.time(), timeline.length() * 0.02);
        c.check("scrubbing pauses playback", !timeline.playing());

        float yaw = viewerScene.camera().yaw();
        view.dispatchLocal(me.methodial.apothecary.event.EventType.DRAG, new me.methodial.apothecary.event.MouseEvent(0, 0, 0, 0, 20, 0));
        c.near("dragging the scene view orbits the camera", yaw - 12, viewerScene.camera().yaw(), 1e-3);
        float zoom = viewerScene.camera().orthoHeight();
        view.dispatchLocal(me.methodial.apothecary.event.EventType.SCROLL, new me.methodial.apothecary.event.ScrollEvent(0, 0, 0, 2));
        c.check("scrolling over it zooms", viewerScene.camera().orthoHeight() < zoom);

        c.check("the GIF button is enabled for an animated scene", !gif.isDisabled());
        exportsBefore = exportCount();
        uiClick(png);
        uiClick(gif);
        glClean("viewer interaction");
    }

    private void worldScene() throws Exception {
        c.section("World: block entities and entities in a built scene");
        Scene scene = DemoScenes.island();
        scene.timeline().seek(100);
        ScenePart main = scene.main();
        main.setBlock(new BlockPos(1, 3, 7), Blocks.ENCHANTING_TABLE.defaultBlockState());
        main.setBlock(new BlockPos(3, 3, 7), Blocks.RED_BED.defaultBlockState());
        main.setBlock(new BlockPos(5, 3, 5), Blocks.WHITE_BANNER.defaultBlockState());
        main.setBlock(new BlockPos(0, 3, 4), Blocks.ENDER_CHEST.defaultBlockState());
        main.add(new EntityElement(EntityType.COW)).setPosition(4.5f, 3.0f, 4.5f).setYaw(30);
        main.add(new EntityElement(EntityType.ARMOR_STAND)).setPosition(1.5f, 3.0f, 4.5f).setYaw(200);
        main.add(new EntityElement(EntityType.ALLAY)).setPosition(4.5f, 5.5f, 2.5f).setYaw(140);
        boolean[] configured = {false};
        main.add(new EntityElement(EntityType.SHEEP)).setPosition(7.5f, 3.0f, 6.5f).configure(e -> configured[0] = true);

        try (NativeImage image = SceneExporter.renderImage(scene, 1024, 1024, RenderSettings.HIGH_QUALITY)) {
            image.writeToFile(out.resolve("world_scene.png"));
            c.check("the scene renders in a world", Images.coverage(image) > 0.2);
        }
        c.eq("block entities created for chest, enchanting table, bed, banner, ender chest", 5, main.store().blockEntities().size());
        c.check("entity configure callback ran", configured[0]);
        main.removeBlock(new BlockPos(0, 3, 4));
        main.syncBlockEntities();
        c.eq("removing a block drops its block entity", 4, main.store().blockEntities().size());

        Scene loaded = SceneIO.load(SceneIO.save(scene));
        c.eq("scene with entities round-trips", List.of(), SceneDiff.diff(scene, loaded));
        loaded.timeline().seek(100);
        worldExports.add(SceneExporter.exportPng(loaded, out.resolve("world_scene_loaded.png"), 512, 512, RenderSettings.DEFAULT));

        // Night must not leak into scenes: the private lightmap keeps them identical at any world time.
        Minecraft mc = Minecraft.getInstance();
        Scene plain = Scene.create();
        plain.main().fill(new BlockPos(0, 0, 0), new BlockPos(2, 2, 2), Blocks.STONE.defaultBlockState());
        plain.fitCamera();
        try (NativeImage day = SceneExporter.renderImage(plain, 128, 128, RenderSettings.FAST)) {
            day.writeToFile(out.resolve("cube_in_world.png"));
            try (java.io.InputStream in = Files.newInputStream(out.resolve("cube_fast.png")); NativeImage title = NativeImage.read(in)) {
                plain.camera().setYaw(30).setPitch(25);
                try (NativeImage same = SceneExporter.renderImage(plain, 256, 256, RenderSettings.FAST)) {
                    c.eq("a scene renders pixel-identically in a world and on the title screen", 0, Images.differing(title, same, 0));
                }
            }
        }
        glClean("world scene");
    }

    private void onLevelRender(RenderLevelStageEvent event) {
        if (!drawInWorld || worldRenderer == null || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        worldRenderer.drawInWorld(event.getModelViewMatrix(), event.getProjectionMatrix(), event.getCamera().getPosition(),
                worldOrigin, event.getPartialTick().getGameTimeDeltaPartialTick(false));
    }

    private void inWorldAfter() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        try (NativeImage after = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            after.writeToFile(out.resolve("inworld.png"));
            int diff = Images.differing(worldBefore, after, 60);
            c.check("the scene appears inside the world", diff > after.getWidth() * after.getHeight() / 100, diff + " px changed");
        }
        c.check("in-world geometry finished building", !worldRenderer.busy());
        drawInWorld = false;
        worldRenderer.close();
        worldRenderer = null;
    }

    // ------------------------------------------------------------------ end

    private void finish() {
        done = true;
        try {
            List<String> lines = new ArrayList<>();
            lines.add("Kraz self test: " + c.passed + " passed, " + c.failed + " failed");
            lines.addAll(c.lines);
            Files.createDirectories(out);
            Files.write(out.resolve("report.txt"), lines);
        } catch (Exception e) {
            LOGGER.error("Kraz self test could not write its report", e);
        }
        Minecraft.getInstance().stop();
    }
}
