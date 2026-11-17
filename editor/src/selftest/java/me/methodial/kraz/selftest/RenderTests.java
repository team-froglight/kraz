package me.methodial.kraz.selftest;

import com.mojang.blaze3d.platform.NativeImage;
import me.methodial.kraz.demo.DemoScenes;
import me.methodial.kraz.export.SceneExporter;
import me.methodial.kraz.gui.SceneWidget;
import me.methodial.kraz.render.RenderSettings;
import me.methodial.kraz.render.SceneCanvas;
import me.methodial.kraz.render.post.PostEffects;
import me.methodial.kraz.scene.Scene;
import me.methodial.kraz.scene.SceneEnvironment;
import me.methodial.kraz.scene.SceneIO;
import me.methodial.kraz.scene.ScenePart;
import me.methodial.kraz.scene.element.BlockElement;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/** Tests that render: antialiasing, effects, animation frames, caching, exports, widget input. No world needed. */
final class RenderTests {
    private final Checks c;
    private final Path out;
    final List<CompletableFuture<?>> pending = new ArrayList<>();
    private int sequenceFrames;

    RenderTests(Checks checks, Path out) {
        this.c = checks;
        this.out = out;
    }

    private NativeImage render(Scene scene, int w, int h, RenderSettings settings, String saveAs) throws Exception {
        NativeImage image = SceneExporter.renderImage(scene, w, h, settings);
        if (saveAs != null) {
            image.writeToFile(out.resolve(saveAs));
        }
        return image;
    }

    private void glClean(String where) {
        int error = GL11.glGetError();
        c.check("no OpenGL error after " + where, error == 0, "0x" + Integer.toHexString(error));
    }

    void run() throws Exception {
        basics();
        leaves();
        antialiasing();
        effects();
        lightingLooks();
        animationFrames();
        partFading();
        dynamicEdits();
        caching();
        structureRender();
        exports();
        widgetInput();
        benchmark();
    }

    /** A plain stone cube: no translucency, so partial alpha can only come from antialiased edges. */
    private Scene cube() {
        Scene scene = Scene.create();
        scene.main().fill(new BlockPos(0, 0, 0), new BlockPos(2, 2, 2), Blocks.STONE.defaultBlockState());
        scene.fitCamera();
        scene.camera().setYaw(30).setPitch(25);
        return scene;
    }

    private void basics() throws Exception {
        c.section("Render: basics");
        Scene scene = cube();
        try (NativeImage image = render(scene, 256, 256, RenderSettings.FAST, "cube_fast.png")) {
            c.eq("image has the requested size", "256x256", image.getWidth() + "x" + image.getHeight());
            double coverage = Images.coverage(image);
            c.check("the cube covers a sensible part of the frame", coverage > 0.25 && coverage < 0.85, String.format("%.2f", coverage));
            c.eq("background is transparent", 0, Images.alpha(image.getPixelRGBA(2, 2)));
            c.eq("geometry is opaque", 255, Images.alpha(image.getPixelRGBA(128, 128)));
            c.check("the cube is textured, not a flat fill", Images.distinctColors(image, 64) >= 6, Images.distinctColors(image, 64) + " colours");
            // Orientation: with the camera looking down at 25°, the bright top face must be in the upper half.
            int top = image.getPixelRGBA(128, 70), side = image.getPixelRGBA(100, 190);
            c.check("image is the right way up (lit top face above shaded side)", Images.green(top) > Images.green(side) + 10,
                    Images.describe(top) + " vs " + Images.describe(side));
        }
        try (NativeImage wide = render(scene, 400, 100, RenderSettings.FAST, null);
             NativeImage tall = render(scene, 100, 400, RenderSettings.FAST, null)) {
            c.check("non-square targets work", Images.coverage(wide) > 0.02 && Images.coverage(tall) > 0.02);
        }
        try (NativeImage tiny = render(scene, 1, 1, RenderSettings.HIGH_QUALITY, null)) {
            c.eq("a 1x1 target does not crash", 1, tiny.getWidth());
        }
        scene.setBackground(0xFF2B3A55);
        try (NativeImage image = render(scene, 128, 128, RenderSettings.DEFAULT, null)) {
            c.eq("opaque background colour is exact", "rgba(43,58,85,255)", Images.describe(image.getPixelRGBA(1, 1)));
        }
        scene.setBackground(0x80FF0000);
        try (NativeImage image = render(scene, 128, 128, RenderSettings.DEFAULT, null)) {
            int p = image.getPixelRGBA(1, 1);
            c.check("translucent background keeps straight colour and alpha", Images.red(p) >= 253 && Images.green(p) <= 2 && Math.abs(Images.alpha(p) - 128) <= 1,
                    Images.describe(p));
        }
        scene.setBackground(0);
        try (NativeImage ortho = render(scene, 256, 256, RenderSettings.FAST, null)) {
            scene.camera().setOrthographic(false);
            try (NativeImage perspective = render(scene, 256, 256, RenderSettings.FAST, "cube_perspective.png")) {
                c.check("perspective projection differs from orthographic", Images.differing(ortho, perspective, 8) > 500);
                c.check("perspective render is not empty", Images.coverage(perspective) > 0.1);
            }
        }
        try (NativeImage empty = render(Scene.create(), 64, 64, RenderSettings.DEFAULT, null)) {
            c.eq("an empty scene renders as fully transparent", 0.0, Images.coverage(empty));
        }
        glClean("basic renders");
    }

    /** Transparent pixels inside the middle of the frame, where a block seen head-on covers everything. */
    private static int holes(NativeImage image) {
        int n = 0;
        for (int y = image.getHeight() * 3 / 10; y < image.getHeight() * 7 / 10; y++) {
            for (int x = image.getWidth() * 3 / 10; x < image.getWidth() * 7 / 10; x++) {
                if (Images.alpha(image.getPixelRGBA(x, y)) == 0) {
                    n++;
                }
            }
        }
        return n;
    }

    private void leaves() throws Exception {
        c.section("Render: leaves follow the Fancy/Fast setting");
        var options = net.minecraft.client.Minecraft.getInstance().options;
        var original = options.graphicsMode().get();
        Scene scene = Scene.create();
        scene.main().setBlock(new BlockPos(0, 0, 0), Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT, true));
        scene.fitCamera();
        scene.camera().setYaw(0).setPitch(0).setOrthoHeight(1.0f);
        try (SceneCanvas canvas = new SceneCanvas(scene).setSettings(RenderSettings.FAST)) {
            options.graphicsMode().set(net.minecraft.client.GraphicsStatus.FANCY);
            try (NativeImage fancy = canvas.capture(128, 128)) {
                SceneCanvas.unpremultiply(fancy);
                fancy.writeToFile(out.resolve("leaves_fancy.png"));
                c.check("Fancy: leaves are see-through, even outside a world", holes(fancy) > 300, holes(fancy) + " transparent px");
                int g = 0;
                for (int y = 40; y < 88; y++) {
                    for (int x = 40; x < 88; x++) {
                        int p = fancy.getPixelRGBA(x, y);
                        if (Images.alpha(p) == 255 && Images.green(p) > Images.red(p) + 20 && Images.green(p) > Images.blue(p) + 20) {
                            g++;
                        }
                    }
                }
                c.check("Fancy: leaves are tinted foliage green", g > 300, g + " green px");
            }
            options.graphicsMode().set(net.minecraft.client.GraphicsStatus.FAST);
            try (NativeImage fast = canvas.capture(128, 128)) {
                fast.writeToFile(out.resolve("leaves_fast.png"));
                c.eq("Fast: leaves are opaque, as in the game (and the scene re-meshed for it)", 0, holes(fast));
            }
        } finally {
            options.graphicsMode().set(original);
        }
        glClean("leaves");
    }

    private void antialiasing() throws Exception {
        c.section("Render: antialiasing");
        Scene scene = cube();
        try (NativeImage none = render(scene, 256, 256, RenderSettings.FAST, null);
             NativeImage msaa4 = render(scene, 256, 256, RenderSettings.DEFAULT, "cube_msaa4.png");
             NativeImage msaa8 = render(scene, 256, 256, new RenderSettings(8, 1, List.of()), null);
             NativeImage ss2 = render(scene, 256, 256, new RenderSettings(0, 2, List.of()), null);
             NativeImage ss4 = render(scene, 256, 256, new RenderSettings(0, 4, List.of()), null);
             NativeImage hq = render(scene, 256, 256, RenderSettings.HIGH_QUALITY, "cube_hq.png");
             NativeImage absurd = render(scene, 256, 256, new RenderSettings(4096, 1, List.of()), null)) {
            int n0 = Images.partialAlpha(none), n4 = Images.partialAlpha(msaa4), n8 = Images.partialAlpha(msaa8);
            int s2 = Images.partialAlpha(ss2), s4 = Images.partialAlpha(ss4), nh = Images.partialAlpha(hq);
            c.info("edge pixels with partial alpha: none=" + n0 + " msaa4=" + n4 + " msaa8=" + n8 + " ss2=" + s2 + " ss4=" + s4 + " hq=" + nh);
            c.eq("without antialiasing every edge pixel is hard", 0, n0);
            c.check("4x MSAA produces smoothed edge pixels", n4 > 100);
            c.check("8x MSAA produces smoothed edge pixels", n8 > 100);
            c.check("2x supersampling produces smoothed edge pixels", s2 > 100);
            c.check("4x supersampling produces smoothed edge pixels", s4 > 100);
            c.check("MSAA + supersampling combined works", nh > 100);
            c.check("an unsupported sample count is clamped instead of failing", Images.partialAlpha(absurd) > 100);
            c.check("antialiasing only touches edges", Images.differing(none, msaa4, 40) < 256 * 256 / 10, Images.differing(none, msaa4, 40) + " px differ");
            c.near("coverage is the same with and without AA", Images.coverage(none), Images.coverage(hq), 0.03);
        }
        glClean("antialiased renders");
    }

    private void effects() throws Exception {
        c.section("Render: post-processing shaders");
        Scene scene = cube();
        try (NativeImage plain = render(scene, 256, 256, RenderSettings.FAST, null);
             NativeImage fxaa = render(scene, 256, 256, RenderSettings.FAST.withEffects(PostEffects.FXAA), "cube_fxaa.png")) {
            int changed = Images.differing(plain, fxaa, 4);
            c.check("FXAA changes edge pixels", changed > 50, changed + " px");
            c.check("FXAA leaves most of the image alone", changed < 256 * 256 / 3, changed + " px");
            c.check("FXAA smooths the silhouette against a transparent background", Images.partialAlpha(fxaa) > 50, Images.partialAlpha(fxaa) + " px");
            c.eq("FXAA keeps the background transparent", 0, Images.alpha(fxaa.getPixelRGBA(2, 2)));
        }
        scene.setBackground(0xFF808080);
        try (NativeImage plain = render(scene, 256, 256, RenderSettings.FAST, null);
             NativeImage vignette = render(scene, 256, 256, RenderSettings.FAST.withEffects(PostEffects.vignette(0.8f)), "cube_vignette.png");
             NativeImage chained = render(scene, 256, 256, RenderSettings.HIGH_QUALITY.withEffects(PostEffects.FXAA, PostEffects.vignette(0.8f), PostEffects.FXAA), null)) {
            int cornerPlain = Images.red(plain.getPixelRGBA(1, 1)), cornerDark = Images.red(vignette.getPixelRGBA(1, 1));
            c.check("vignette darkens the corners", cornerDark < cornerPlain - 40, cornerPlain + " -> " + cornerDark);
            c.check("vignette leaves the centre alone", Images.differing(plain, vignette, 2) < 256 * 256 && plain.getPixelRGBA(128, 128) == vignette.getPixelRGBA(128, 128));
            c.eq("vignette keeps alpha", 255, Images.alpha(vignette.getPixelRGBA(1, 1)));
            c.check("three chained effects on top of MSAA + supersampling", Images.red(chained.getPixelRGBA(1, 1)) < cornerPlain - 40
                    && Images.alpha(chained.getPixelRGBA(128, 128)) == 255);
        }
        glClean("post effects");
    }

    private void lightingLooks() throws Exception {
        c.section("Render: lighting modes");
        // A room open only towards the camera: bright when full-bright, dark inside when light is computed.
        Scene scene = Scene.create();
        ScenePart part = scene.main();
        part.fill(new BlockPos(0, 0, 0), new BlockPos(6, 6, 6), Blocks.SMOOTH_STONE.defaultBlockState());
        part.fill(new BlockPos(1, 1, 1), new BlockPos(5, 5, 6), Blocks.AIR.defaultBlockState());
        scene.fitCamera();
        scene.camera().setYaw(0).setPitch(0);
        try (NativeImage bright = render(scene, 128, 128, RenderSettings.FAST, null)) {
            scene.setEnvironment(scene.environment().withLightMode(SceneEnvironment.LightMode.COMPUTED));
            try (NativeImage computed = render(scene, 128, 128, RenderSettings.FAST, "room_computed.png")) {
                int a = Images.green(bright.getPixelRGBA(64, 64)), b = Images.green(computed.getPixelRGBA(64, 64));
                c.check("computed light makes the back of a room darker", b < a - 30, a + " -> " + b);
                part.setBlock(new BlockPos(3, 3, 1), Blocks.GLOWSTONE.defaultBlockState());
                try (NativeImage lit = render(scene, 128, 128, RenderSettings.FAST, "room_lit.png")) {
                    int wall = Images.green(computed.getPixelRGBA(40, 64)), litWall = Images.green(lit.getPixelRGBA(40, 64));
                    c.check("a light source brightens the room", litWall > wall + 30, wall + " -> " + litWall);
                }
            }
            scene.setEnvironment(scene.environment().withLightMode(SceneEnvironment.LightMode.FULL_BRIGHT).withSkyBrightness(0.0f));
            part.removeBlock(new BlockPos(3, 3, 1));
            try (NativeImage night = render(scene, 128, 128, RenderSettings.FAST, null)) {
                int a = Images.green(bright.getPixelRGBA(64, 64)), b = Images.green(night.getPixelRGBA(64, 64));
                c.check("sky brightness 0 renders a night scene", b < a - 60, a + " -> " + b);
            }
        }
        Scene grass = Scene.create();
        grass.main().fill(new BlockPos(0, 0, 0), new BlockPos(2, 0, 2), Blocks.GRASS_BLOCK.defaultBlockState());
        grass.fitCamera();
        grass.camera().setPitch(89);
        try (NativeImage green = render(grass, 64, 64, RenderSettings.FAST, null)) {
            grass.setEnvironment(grass.environment().withTints(0xFF0000, 0xFF0000, 0xFF0000));
            try (NativeImage red = render(grass, 64, 64, RenderSettings.FAST, null)) {
                int g = green.getPixelRGBA(32, 32), r = red.getPixelRGBA(32, 32);
                c.check("biome tint is applied and configurable", Images.green(g) > Images.red(g) && Images.red(r) > Images.green(r) + 40,
                        Images.describe(g) + " -> " + Images.describe(r));
            }
        }
        glClean("lighting renders");
    }

    private void animationFrames() throws Exception {
        c.section("Render: animation");
        Scene scene = DemoScenes.island();
        scene.timeline().setPlaying(false);
        float[] times = {0, 27, 60, 135, 200};
        NativeImage[] frames = new NativeImage[times.length];
        try (SceneCanvas canvas = new SceneCanvas(scene).setSettings(RenderSettings.DEFAULT)) {
            for (int i = 0; i < times.length; i++) {
                scene.timeline().seek(times[i]);
                frames[i] = canvas.capture(256, 256);
                SceneCanvas.unpremultiply(frames[i]);
                frames[i].writeToFile(out.resolve("anim_t" + (int) times[i] + ".png"));
            }
            for (int i = 1; i < times.length; i++) {
                int diff = Images.differing(frames[i - 1], frames[i], 8);
                c.check("frame at t=" + (int) times[i] + " differs from t=" + (int) times[i - 1], diff > 400, diff + " px");
            }
            scene.timeline().seek(27);
            try (NativeImage again = canvas.capture(256, 256)) {
                SceneCanvas.unpremultiply(again);
                c.eq("seeking back to t=27 reproduces that frame exactly", 0, Images.differing(frames[1], again, 0));
            }
        } finally {
            for (NativeImage frame : frames) {
                if (frame != null) {
                    frame.close();
                }
            }
        }
        // Real-time playback through the widget's clock.
        Scene live = DemoScenes.island();
        live.timeline().seek(0);
        float start = live.timeline().time();
        live.timeline().advance(12.5f);
        c.near("advance() moves the demo timeline in real time", start + 12.5, live.timeline().time(), 1e-4);
        glClean("animation renders");
    }

    private void partFading() throws Exception {
        c.section("Render: part transforms and fading");
        Scene scene = Scene.create();
        scene.main().fill(new BlockPos(0, 0, 0), new BlockPos(4, 0, 4), Blocks.STONE.defaultBlockState());
        ScenePart box = scene.part("box");
        box.fill(new BlockPos(1, 1, 1), new BlockPos(3, 3, 3), Blocks.GOLD_BLOCK.defaultBlockState());
        scene.fitCamera();
        scene.camera().setYaw(20).setPitch(20);
        try (SceneCanvas canvas = new SceneCanvas(scene).setSettings(RenderSettings.FAST);
             NativeImage base = canvas.capture(200, 200)) {
            box.setOffset(0, 3, 0);
            try (NativeImage moved = canvas.capture(200, 200)) {
                c.check("moving a part changes the image", Images.differing(base, moved, 8) > 1000);
            }
            box.setOffset(0, 0, 0).setPivot(2.5f, 1, 2.5f).setRotation(0, 45, 0);
            try (NativeImage rotated = canvas.capture(200, 200)) {
                c.check("rotating a part changes the image", Images.differing(base, rotated, 8) > 500);
            }
            box.setRotation(0, 0, 0).setScale(0.5f);
            try (NativeImage scaled = canvas.capture(200, 200)) {
                c.check("scaling a part changes the image", Images.differing(base, scaled, 8) > 1000);
            }
            box.setScale(1);
            try (NativeImage restored = canvas.capture(200, 200)) {
                c.eq("restoring the transform restores the image exactly", 0, Images.differing(base, restored, 0));
            }
            box.setVisible(false);
            try (NativeImage hidden = canvas.capture(200, 200)) {
                c.check("hiding a part removes it", Images.differing(base, hidden, 8) > 2000);
                box.setVisible(true).setAlpha(0);
                try (NativeImage zero = canvas.capture(200, 200)) {
                    c.eq("alpha 0 looks the same as hidden", 0, Images.differing(hidden, zero, 0));
                }
                box.setAlpha(0.5f);
                try (NativeImage half = canvas.capture(200, 200)) {
                    c.check("alpha 0.5 is between hidden and solid", Images.differing(half, hidden, 12) > 2000 && Images.differing(half, base, 12) > 2000);
                    // Above the floor the box is over empty background: it must come out half transparent, not dim.
                    int best = 0;
                    for (int y = 0; y < 200; y++) {
                        for (int x = 0; x < 200; x++) {
                            int a = Images.alpha(half.getPixelRGBA(x, y));
                            if (a > 100 && a < 160) {
                                best++;
                            }
                        }
                    }
                    c.check("a fading part over empty background is translucent in the output", best > 300, best + " px near alpha 128");
                }
            }
            box.setAlpha(1);
            c.check("animating transforms never rebuilt geometry", !canvas.busy());
        }
        glClean("part transforms");
    }

    private void dynamicEdits() throws Exception {
        c.section("Render: editing a live scene");
        Scene scene = Scene.create();
        ScenePart part = scene.main();
        part.fill(new BlockPos(0, 0, 0), new BlockPos(20, 0, 20), Blocks.STONE.defaultBlockState());
        scene.fitCamera();
        try (SceneCanvas canvas = new SceneCanvas(scene).setSettings(RenderSettings.FAST);
             NativeImage before = canvas.capture(200, 200)) {
            part.fill(new BlockPos(8, 1, 8), new BlockPos(12, 6, 12), Blocks.REDSTONE_BLOCK.defaultBlockState());
            try (NativeImage after = canvas.capture(200, 200)) {
                c.check("blocks added after first render show up", Images.differing(before, after, 8) > 500);
            }
            part.fill(new BlockPos(8, 1, 8), new BlockPos(12, 6, 12), Blocks.AIR.defaultBlockState());
            try (NativeImage removed = canvas.capture(200, 200)) {
                c.eq("removing them restores the original image exactly", 0, Images.differing(before, removed, 0));
            }
            // Asynchronous path, as used by on-screen views: geometry arrives within a few frames.
            part.fill(new BlockPos(2, 1, 2), new BlockPos(18, 3, 18), Blocks.EMERALD_BLOCK.defaultBlockState());
            int frames = 0;
            do {
                canvas.render(200, 200, 0, false);
                Thread.sleep(5);
            } while (canvas.busy() && ++frames < 400);
            c.check("background meshing finishes", !canvas.busy(), frames + " frames");
            try (NativeImage async = canvas.capture(200, 200)) {
                c.check("asynchronously built geometry is displayed", Images.differing(before, async, 8) > 2000);
            }
            part.clear();
            try (NativeImage cleared = canvas.capture(200, 200)) {
                c.eq("clearing the part empties the image", 0.0, Images.coverage(cleared));
            }
            BlockElement element = part.add(new BlockElement(Blocks.DIAMOND_BLOCK.defaultBlockState())).setPosition(10, 0, 10).setScale(4);
            try (NativeImage withElement = canvas.capture(200, 200)) {
                c.check("a free-floating block element renders", Images.coverage(withElement) > 0.005, String.format("%.4f", Images.coverage(withElement)));
            }
            part.remove(element);
        }
        // Two canvases showing one scene must both see edits.
        Scene shared = cube();
        try (SceneCanvas a = new SceneCanvas(shared).setSettings(RenderSettings.FAST);
             SceneCanvas b = new SceneCanvas(shared).setSettings(RenderSettings.FAST);
             NativeImage a1 = a.capture(100, 100); NativeImage b1 = b.capture(100, 100)) {
            c.eq("two canvases on one scene render the same image", 0, Images.differing(a1, b1, 0));
            shared.main().fill(new BlockPos(0, 3, 0), new BlockPos(2, 4, 2), Blocks.STONE.defaultBlockState());
            try (NativeImage a2 = a.capture(100, 100); NativeImage b2 = b.capture(100, 100)) {
                c.check("an edit reaches both canvases", Images.differing(a1, a2, 8) > 100 && Images.differing(a2, b2, 0) == 0);
            }
        }
        glClean("live edits");
    }

    private void caching() {
        c.section("Render: cached redraw");
        Scene scene = cube();
        try (SceneCanvas canvas = new SceneCanvas(scene).setSettings(RenderSettings.DEFAULT).setRedraw(SceneCanvas.Redraw.WHEN_CHANGED)) {
            canvas.render(200, 200, 0, true);
            long first = canvas.framesRendered();
            for (int i = 0; i < 20; i++) {
                canvas.render(200, 200, 0, false);
            }
            c.eq("an unchanged static scene is drawn once and then reused", first, canvas.framesRendered());
            scene.camera().orbit(5, 0);
            canvas.render(200, 200, 0, false);
            c.eq("moving the camera triggers one redraw", first + 1, canvas.framesRendered());
            canvas.render(200, 200, 0, false);
            c.eq("...and only one", first + 1, canvas.framesRendered());
            canvas.render(210, 200, 0, false);
            c.eq("resizing triggers a redraw", first + 2, canvas.framesRendered());
            canvas.setSettings(RenderSettings.FAST);
            canvas.render(210, 200, 0, false);
            c.eq("changing settings triggers a redraw", first + 3, canvas.framesRendered());
            scene.main().setBlock(new BlockPos(1, 3, 1), Blocks.STONE.defaultBlockState());
            canvas.render(210, 200, 0, true);
            c.eq("editing a block triggers a redraw", first + 4, canvas.framesRendered());
            scene.main().setAlpha(0.5f);
            canvas.render(210, 200, 0, false);
            c.eq("changing a part's alpha triggers a redraw", first + 5, canvas.framesRendered());
            scene.setBackground(0xFF000000);
            canvas.render(210, 200, 0, false);
            c.eq("changing the background triggers a redraw", first + 6, canvas.framesRendered());
            scene.timeline().cameraYaw().key(0, 0f).key(10, 90f);
            scene.timeline().seek(5);
            canvas.render(210, 200, 0, false);
            c.eq("advancing the animation triggers a redraw", first + 7, canvas.framesRendered());
            canvas.setRedraw(SceneCanvas.Redraw.ALWAYS);
            canvas.render(210, 200, 0, false);
            canvas.render(210, 200, 0, false);
            c.eq("Redraw.ALWAYS draws every frame", first + 9, canvas.framesRendered());
            canvas.setRedraw(SceneCanvas.Redraw.WHEN_CHANGED);
            scene.main().add(new BlockElement(Blocks.STONE.defaultBlockState()));
            canvas.render(210, 200, 0, false);
            canvas.render(210, 200, 0, false);
            c.eq("adding a static block element redraws once and is then cached too", first + 10, canvas.framesRendered());
        }
        glClean("cached redraw");
    }

    private void structureRender() throws Exception {
        c.section("Render: vanilla structure");
        try (InputStream in = Minecraft.class.getResourceAsStream("/data/minecraft/structure/igloo/top.nbt")) {
            if (in == null) {
                c.check("igloo structure available", false);
                return;
            }
            Scene scene = SceneIO.fromTag(NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()));
            try (NativeImage image = render(scene, 512, 512, RenderSettings.HIGH_QUALITY, "igloo.png")) {
                c.check("the igloo renders and is framed by fitCamera", Images.coverage(image) > 0.2 && Images.alpha(image.getPixelRGBA(2, 2)) == 0,
                        String.format("coverage %.2f", Images.coverage(image)));
            }
        }
        glClean("structure render");
    }

    private void exports() throws Exception {
        c.section("Export");
        Scene scene = DemoScenes.island();
        scene.timeline().setPlaying(false);
        scene.timeline().seek(100);
        pending.add(SceneExporter.exportPng(scene, out.resolve("demo_hq.png"), 1024, 1024, RenderSettings.HIGH_QUALITY));
        pending.add(SceneExporter.exportPng(scene, out.resolve("nested/dir/demo.png"), 256, 256, RenderSettings.DEFAULT));
        c.near("exporting a still leaves the timeline where it was", 100, scene.timeline().time(), 0);

        pending.add(SceneExporter.exportGif(scene, out.resolve("demo.gif"), 256, 256, 10, RenderSettings.DEFAULT));
        c.near("exporting an animation restores the timeline", 100, scene.timeline().time(), 0);
        sequenceFrames = (int) Math.ceil(scene.timeline().length() / 20.0f * 5);
        pending.add(SceneExporter.exportPngSequence(scene, out.resolve("sequence"), 128, 128, 5, RenderSettings.FAST));

        Scene still = cube();
        pending.add(SceneExporter.exportGif(still, out.resolve("still.gif"), 64, 64, 20, RenderSettings.FAST));

        Path first = SceneExporter.exportPath("selftest_name", "png");
        c.check("exportPath points into kraz/exports", first.getParent().endsWith(Path.of("kraz", "exports")));
        c.eq("exportPath sanitises names", "a_b.png", SceneExporter.exportPath("a/b", "png").getFileName().toString().replaceAll("_\\d+\\.png$", ".png"));
        glClean("starting exports");
    }

    /** Runs once every export future has completed. */
    void verifyExports() throws Exception {
        c.section("Export: results on disk");
        for (CompletableFuture<?> future : pending) {
            c.check("export completed without error", !future.isCompletedExceptionally(),
                    future.isCompletedExceptionally() ? String.valueOf(future.exceptionNow()) : String.valueOf(future.resultNow()));
        }
        try (InputStream in = Files.newInputStream(out.resolve("demo_hq.png")); NativeImage image = NativeImage.read(in)) {
            c.eq("PNG has the requested size", "1024x1024", image.getWidth() + "x" + image.getHeight());
            c.eq("PNG background is transparent", 0, Images.alpha(image.getPixelRGBA(3, 3)));
            c.check("PNG has antialiased or translucent pixels (straight alpha kept)", Images.partialAlpha(image) > 500);
            c.check("PNG shows the scene", Images.coverage(image) > 0.2 && Images.distinctColors(image, 500) >= 300);
        }
        c.check("export creates missing directories", Files.isRegularFile(out.resolve("nested/dir/demo.png")));

        try (Stream<Path> files = Files.list(out.resolve("sequence"))) {
            List<Path> frames = files.sorted().toList();
            c.eq("PNG sequence has one file per frame", sequenceFrames, frames.size());
            c.eq("PNG sequence naming", "frame_0000.png", frames.getFirst().getFileName().toString());
            try (InputStream a = Files.newInputStream(frames.getFirst()); NativeImage first = NativeImage.read(a);
                 InputStream b = Files.newInputStream(frames.get(frames.size() / 2)); NativeImage middle = NativeImage.read(b)) {
                c.check("sequence frames show the animation progressing", Images.differing(first, middle, 8) > 200);
            }
        }
        checkGif("demo.gif", 120, 256);
        checkGif("still.gif", 1, 64);
    }

    private void checkGif(String name, int expectedFrames, int size) throws Exception {
        Path path = out.resolve(name);
        byte[] head = new byte[6];
        try (InputStream in = Files.newInputStream(path)) {
            in.read(head);
        }
        c.eq(name + " has a GIF89a header", "GIF89a", new String(head));
        ImageReader reader = ImageIO.getImageReadersBySuffix("gif").next();
        try (ImageInputStream stream = ImageIO.createImageInputStream(path.toFile())) {
            reader.setInput(stream);
            c.eq(name + " frame count", expectedFrames, reader.getNumImages(true));
            c.eq(name + " size", size + "x" + size, reader.getWidth(0) + "x" + reader.getHeight(0));
            var first = reader.read(0);
            c.eq(name + " background is transparent", 0, first.getRGB(1, 1) >>> 24);
            c.eq(name + " content is opaque", 255, first.getRGB(size / 2, size / 2) >>> 24);
            String meta = metadataText(reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0"));
            c.check(name + " frame delay is set", meta.contains("delayTime=" + (name.equals("demo.gif") ? "10" : "5")), meta);
            c.check(name + " loops forever", meta.contains("NETSCAPE"), meta);
        } finally {
            reader.dispose();
        }
    }

    private static String metadataText(org.w3c.dom.Node node) {
        StringBuilder text = new StringBuilder(node.getNodeName());
        var attributes = node.getAttributes();
        for (int i = 0; attributes != null && i < attributes.getLength(); i++) {
            text.append(' ').append(attributes.item(i).getNodeName()).append('=').append(attributes.item(i).getNodeValue());
        }
        for (var child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            text.append(" | ").append(metadataText(child));
        }
        return text.toString();
    }

    private void widgetInput() {
        c.section("GUI: widget input");
        Scene scene = cube();
        scene.camera().setYaw(0).setPitch(0);
        SceneWidget widget = new SceneWidget(50, 40, 200, 100, scene);
        c.check("a click outside the widget is ignored", !widget.mouseClicked(10, 10, 0));
        c.check("dragging without a press does nothing", !widget.mouseDragged(100, 80, 0, 10, 10) && scene.camera().yaw() == 0);
        c.check("left click inside is captured", widget.mouseClicked(100, 80, 0));
        widget.mouseDragged(110, 85, 0, 10, 5);
        c.near("left drag orbits: yaw", -6, scene.camera().yaw(), 1e-4);
        c.near("left drag orbits: pitch", 3, scene.camera().pitch(), 1e-4);
        c.check("release ends the drag", widget.mouseReleased(110, 85, 0) && !widget.mouseDragged(120, 85, 0, 10, 0));

        scene.camera().setYaw(0).setPitch(0);
        Vector3f target = new Vector3f(scene.camera().target());
        float height = scene.camera().orthoHeight();
        widget.mouseClicked(100, 80, 1);
        widget.mouseDragged(100, 90, 1, 0, 10);
        c.near("right drag pans by exactly the dragged distance", target.y + 10 * height / 100, scene.camera().target().y, 1e-3);
        c.near("right drag does not rotate", 0, scene.camera().yaw(), 0);
        widget.mouseReleased(100, 90, 1);

        c.check("scroll inside zooms", widget.mouseScrolled(100, 80, 0, 1));
        c.near("one notch zooms in by 10%", height * 0.9, scene.camera().orthoHeight(), 1e-4);
        c.check("scroll outside is ignored", !widget.mouseScrolled(10, 10, 0, 1));

        widget.setInteractive(false);
        float yaw = scene.camera().yaw();
        c.check("a non-interactive widget ignores input", !widget.mouseClicked(100, 80, 0) && !widget.mouseScrolled(100, 80, 0, 1) && scene.camera().yaw() == yaw);
        widget.close();
        widget.close();
        c.check("closing a widget twice is harmless", true);
    }

    private void benchmark() {
        c.section("Performance (informational)");
        Scene scene = DemoScenes.island();
        scene.timeline().setPlaying(false);
        scene.timeline().seek(100);
        try (SceneCanvas canvas = new SceneCanvas(scene)) {
            for (RenderSettings settings : new RenderSettings[]{RenderSettings.FAST, RenderSettings.DEFAULT, RenderSettings.HIGH_QUALITY}) {
                canvas.setSettings(settings);
                canvas.render(1280, 720, 0, true);
                GL11.glFinish();
                long start = System.nanoTime();
                for (int i = 0; i < 300; i++) {
                    canvas.render(1280, 720, 0, false);
                }
                GL11.glFinish();
                c.info(String.format("demo scene 1280x720, msaa=%d ss=%d: %.3f ms/frame", settings.msaaSamples(), settings.supersample(),
                        (System.nanoTime() - start) / 1e6 / 300));
            }
        }
        // A world-sized scene: 96x96 terrain with hills, about 60k blocks in 36 sections.
        Scene big = Scene.create();
        ScenePart part = big.main();
        for (int x = 0; x < 96; x++) {
            for (int z = 0; z < 96; z++) {
                int h = 6 + (int) (4 * Math.sin(x * 0.15) * Math.cos(z * 0.11) + 3 * Math.sin((x + z) * 0.07));
                for (int y = 0; y <= h; y++) {
                    part.setBlock(x, y, z, y == h ? Blocks.GRASS_BLOCK.defaultBlockState() : y > h - 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState());
                }
                if (h < 5) {
                    for (int y = h + 1; y <= 5; y++) {
                        part.setBlock(x, y, z, Blocks.WATER.defaultBlockState());
                    }
                }
            }
        }
        big.fitCamera();
        try (SceneCanvas canvas = new SceneCanvas(big)) {
            long start = System.nanoTime();
            canvas.render(1280, 720, 0, true);
            GL11.glFinish();
            c.info(String.format("96x96 terrain: %d sections meshed and uploaded in %.0f ms", part.store().sectionCount(), (System.nanoTime() - start) / 1e6));
            start = System.nanoTime();
            for (int i = 0; i < 300; i++) {
                big.camera().orbit(1, 0);
                canvas.render(1280, 720, 0, false);
            }
            GL11.glFinish();
            c.info(String.format("96x96 terrain 1280x720 msaa=4, orbiting: %.3f ms/frame", (System.nanoTime() - start) / 1e6 / 300));
            try (NativeImage image = canvas.capture(1024, 640)) {
                SceneCanvas.unpremultiply(image);
                image.writeToFile(out.resolve("terrain.png"));
                c.check("world-sized scene renders", Images.coverage(image) > 0.1, String.format("coverage %.2f", Images.coverage(image)));
            } catch (Exception e) {
                c.error("terrain capture", e);
            }
        }
        glClean("benchmarks");
    }
}
