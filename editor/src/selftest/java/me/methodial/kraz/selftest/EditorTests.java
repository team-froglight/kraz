package me.methodial.kraz.selftest;

import com.mojang.blaze3d.platform.NativeImage;
import me.methodial.kraz.anim.Easing;
import me.methodial.kraz.anim.Timeline;
import me.methodial.kraz.anim.Track;
import me.methodial.kraz.export.SceneExporter;
import me.methodial.kraz.editor.SceneEditorScreen;
import me.methodial.kraz.render.RenderSettings;
import me.methodial.kraz.scene.Scene;
import me.methodial.kraz.scene.SceneIO;
import me.methodial.kraz.scene.ScenePart;
import me.methodial.kraz.scene.element.BlockElement;
import me.methodial.kraz.scene.element.EntityElement;
import me.methodial.kraz.scene.element.ItemElement;
import me.methodial.kraz.scene.element.SceneElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives the scene editor the way a user would: real mouse and key events sent to the screen, with the resulting
 * scene inspected afterwards. Runs inside a world because entities and posing need one.
 */
final class EditorTests {

    private final Checks c;
    private final Path out;
    private final Minecraft mc = Minecraft.getInstance();
    private Scene scene;
    private SceneEditorScreen editor;
    private EntityElement zombie;
    private int tracksBeforePosing;

    EditorTests(Checks checks, Path out) {
        this.c = checks;
        this.out = out;
    }

    interface Stage {
        void run() throws Exception;
    }

    /** Stages are separated by a few rendered frames, so the editor reacts as it would between user actions. */
    List<Stage> stages() {
        List<Stage> list = new ArrayList<>();
        list.add(this::posingWithoutEditor);
        list.add(this::open);
        int[][] sizes = {{1600, 900}, {854, 480}, {640, 400}, {500, 320}};
        for (int[] size : sizes) {
            list.add(() -> resize(size[0], size[1]));
            list.add(() -> checkLayout(size[0] + "x" + size[1]));
        }
        list.add(() -> resize(originalWidth, originalHeight));
        list.add(this::windows);
        list.add(this::windowsFloated);
        list.add(this::windowsFollowPointer);
        list.add(this::windowsClamped);
        list.add(this::windowsDocked);
        list.add(this::windowsScaled);
        list.add(this::blocks);
        list.add(this::partsAndElements);
        list.add(this::gizmo);
        list.add(this::keyframes);
        list.add(this::timelinePanel);
        list.add(this::inspectorAndBones);
        list.add(this::inspectorType);
        list.add(this::bonesType);
        list.add(this::filesAndDelete);
        list.add(this::ux);
        list.add(this::help);
        list.add(this::library);
        list.add(this::project);
        list.add(this::viewWorld);
        list.add(this::projectedInWorld);
        list.add(this::projectionHidden);
        list.add(this::reopen);
        list.add(this::close);
        return list;
    }

    // ------------------------------------------------------------------ helpers

    /** Adapters so the tests read the same whatever toolkit the editor is drawn with. */
    private record Btn(me.methodial.apothecary.widget.Button node) {
        void onPress() {
            KrazSelfTest.uiClick(node);
        }
    }

    private record Box(me.methodial.apothecary.widget.TextField node) {
        void setValue(String value) {
            node.text(value);
        }
    }

    private me.methodial.apothecary.geom.Rect view() {
        return editor.viewport().contentBox();
    }

    /** Where a scene point is in the viewport's local coordinates. */
    private Vector2f project(Vector3f scenePoint) {
        Vector2f out = new Vector2f();
        editor.viewport().viewport().project(scenePoint, out);
        return out;
    }

    /** The editor's buttons by what the tests call them: icon buttons have ids, text buttons labels. */
    private static final java.util.Map<String, String> IDS = java.util.Map.ofEntries(
            java.util.Map.entry("Undo", "undo"), java.util.Map.entry("Redo", "redo"), java.util.Map.entry("+ Part", "add-part"),
            java.util.Map.entry("+ Block", "add-block"), java.util.Map.entry("+ Item", "add-item"), java.util.Map.entry("+ Entity", "add-entity"),
            java.util.Map.entry("Save", "save"), java.util.Map.entry("Load", "load"), java.util.Map.entry("New", "new"),
            java.util.Map.entry("Auto key", "auto-key"), java.util.Map.entry("Duplicate", "duplicate"), java.util.Map.entry("Library", "library"),
            java.util.Map.entry("World", "world"), java.util.Map.entry("Help", "help"), java.util.Map.entry("Play", "play"));

    private Btn button(String label) {
        String id = IDS.get(label);
        var node = id != null ? editor.ui().rootNode().findById(id) : null;
        var found = node instanceof me.methodial.apothecary.widget.Button b ? b : KrazSelfTest.uiButton(editor.ui().rootNode(), label);
        if (found == null) {
            throw new IllegalStateException("No button " + label);
        }
        return new Btn(found);
    }

    private me.methodial.apothecary.widget.Button buttonStartingWith(me.methodial.apothecary.node.Node root, String prefix) {
        if (root instanceof me.methodial.apothecary.widget.Button b && b.label() != null && b.label().text().startsWith(prefix)) {
            return b;
        }
        for (var child : root.children()) {
            var found = buttonStartingWith(child, prefix);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private me.methodial.apothecary.widget.TextField findField(me.methodial.apothecary.node.Node root, String value) {
        if (root instanceof me.methodial.apothecary.widget.TextField f && value.equals(f.text())) {
            return f;
        }
        for (var child : root.children()) {
            var found = findField(child, value);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private Box box(String value) {
        var found = findField(editor.ui().rootNode(), value);
        if (found == null) {
            throw new IllegalStateException("No text box showing '" + value + "'");
        }
        return new Box(found);
    }

    private void key(int keyCode) {
        editor.keyPressed(keyCode, 0, 0);
    }

    private void click(float x, float y, int button) {
        editor.viewport().press(x, y, button);
        editor.viewport().release();
    }

    private void drag(float x0, float y0, float x1, float y1, int button) {
        editor.viewport().press(x0, y0, button);
        int steps = 8;
        for (int i = 1; i <= steps; i++) {
            editor.viewport().dragTo(x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps);
        }
        editor.viewport().release();
    }

    private int blockCount(ScenePart part) {
        int[] n = {0};
        part.store().forEachBlock((x, y, z, state) -> n[0]++);
        return n[0];
    }

    private void glClean(String where) {
        int error = GL11.glGetError();
        c.check("no OpenGL error after " + where, error == 0, "0x" + Integer.toHexString(error));
    }

    private void screenshot(String name) throws Exception {
        try (NativeImage shot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            shot.writeToFile(out.resolve(name));
        }
    }

    // ------------------------------------------------------------------ posing (library level)

    private void posingWithoutEditor() throws Exception {
        c.section("Posing: entities");
        Scene s = Scene.create();
        s.main().fill(new BlockPos(0, 0, 0), new BlockPos(2, 0, 2), Blocks.SMOOTH_STONE.defaultBlockState());
        EntityElement e = s.main().add(new EntityElement(EntityType.ZOMBIE)).setPosition(1.5f, 1, 1.5f).setYaw(200);
        s.fitCamera();
        s.camera().setYaw(20).setPitch(10);
        c.eq("an added entity gets a name from its type", "zombie", e.name());
        EntityElement second = s.main().add(new EntityElement(EntityType.ZOMBIE)).setPosition(9, 9, 9);
        c.eq("a second one gets a unique name", "zombie_2", second.name());
        s.main().remove(second);

        try (NativeImage base = SceneExporter.renderImage(s, 256, 256, RenderSettings.FAST)) {
            base.writeToFile(out.resolve("pose_base.png"));
            c.check("the model's bones were discovered while drawing", e.boneNames().size() >= 6, e.boneNames().toString());
            c.check("a humanoid offers head and arm bones", e.boneNames().contains("head") && e.boneNames().contains("rightArm"), e.boneNames().toString());

            e.setBone("rightArm", -120, 0, 0).setBone("head", 0, 40, 0);
            try (NativeImage posed = SceneExporter.renderImage(s, 256, 256, RenderSettings.FAST)) {
                posed.writeToFile(out.resolve("pose_bones.png"));
                c.check("rotating bones changes the rendered pose", Images.differing(base, posed, 12) > 150, Images.differing(base, posed, 12) + " px");
            }
            e.setBone("rightArm", 0, 0, 0).setBone("head", 0, 0, 0);
            c.check("zeroing a bone removes its offset", e.bones().isEmpty());
            try (NativeImage reset = SceneExporter.renderImage(s, 256, 256, RenderSettings.FAST)) {
                c.eq("clearing the bones restores the original image exactly", 0, Images.differing(base, reset, 0));
            }
            e.setLimbSwing(1.5f).setLimbAmount(1.0f);
            try (NativeImage walking = SceneExporter.renderImage(s, 256, 256, RenderSettings.FAST)) {
                walking.writeToFile(out.resolve("pose_walk.png"));
                c.check("the walk cycle moves the limbs", Images.differing(base, walking, 12) > 100, Images.differing(base, walking, 12) + " px");
            }
            e.setLimbSwing(0).setLimbAmount(0).setHeadYaw(60).setHeadPitch(-30);
            try (NativeImage looking = SceneExporter.renderImage(s, 256, 256, RenderSettings.FAST)) {
                c.check("head yaw and pitch turn the head", Images.differing(base, looking, 12) > 40, Images.differing(base, looking, 12) + " px");
            }
            e.setHeadYaw(0).setHeadPitch(0).setYaw(20);
            try (NativeImage turned = SceneExporter.renderImage(s, 256, 256, RenderSettings.FAST)) {
                c.check("body yaw turns the entity", Images.differing(base, turned, 12) > 150);
            }
            e.setYaw(200).setScale(0.5f);
            try (NativeImage small = SceneExporter.renderImage(s, 256, 256, RenderSettings.FAST)) {
                c.check("element scale shrinks the entity", Images.differing(base, small, 12) > 150);
            }
            e.setScale(1);
        }

        c.section("Posing: animation and saving");
        Timeline t = s.timeline();
        t.property(e, "bone/rightArm").key(0, new Vector3f()).key(20, new Vector3f(-90, 0, 0), Easing.OUT_QUAD);
        t.property(e, "limb_swing").key(0, 0f).key(20, 10f);
        t.property(e, "yaw").key(0, 200f).key(20, 260f);
        t.property(e, "position").key(0, new Vector3f(1.5f, 1, 1.5f)).key(20, new Vector3f(1.5f, 2, 1.5f));
        t.seek(10);
        c.near("a bone track drives the bone", -67.5, e.bone("rightArm").x, 1e-3);
        c.near("a float property track drives the walk cycle", 5, e.limbSwing(), 1e-4);
        c.near("yaw is animated", 230, e.yaw(), 1e-3);
        c.near("position is animated", 1.5, e.position().y, 1e-4);
        e.setPose(Pose.CROUCHING);

        Scene loaded = SceneIO.load(SceneIO.save(s));
        c.eq("a posed, animated entity round-trips through save/load", List.of(), SceneDiff.diff(s, loaded));
        EntityElement loadedEntity = (EntityElement) loaded.findElement("zombie");
        c.check("the loaded entity kept its name, pose and bone", loadedEntity != null && loadedEntity.pose() == Pose.CROUCHING
                && Math.abs(loadedEntity.bone("rightArm").x + 67.5f) < 1e-3);
        loaded.timeline().seek(20);
        c.near("bone tracks still work after loading, before the entity was ever drawn", -90, loadedEntity.bone("rightArm").x, 1e-3);
        c.eq("element tracks were saved", 4, loaded.timeline().tracks().size());

        ItemElement item = s.main().add(new ItemElement(new ItemStack(Items.DIAMOND_SWORD, 1))).setPosition(0.5f, 1.5f, 0.5f);
        BlockElement block = s.main().add(new BlockElement(Blocks.TNT.defaultBlockState())).setPosition(2, 1, 2).setName("boom");
        c.eq("explicit element names are kept", "boom", block.name());
        Scene withItem = SceneIO.load(SceneIO.save(s));
        c.eq("item elements are saved too", List.of(), SceneDiff.diff(s, withItem));
        c.check("the loaded item is the same item", withItem.findElement(item.name()) instanceof ItemElement i && i.stack().is(Items.DIAMOND_SWORD));

        Scene target = Scene.create();
        target.main().setBlock(new BlockPos(5, 5, 5), Blocks.DIRT.defaultBlockState());
        target.part("old").setBlock(new BlockPos(0, 0, 0), Blocks.DIRT.defaultBlockState());
        target.timeline().cameraFov().key(0, 10f);
        SceneIO.loadInto(target, SceneIO.save(s));
        c.eq("loadInto replaces an existing scene's contents completely", List.of(), SceneDiff.diff(s, target));
        glClean("posing renders");
    }

    // ------------------------------------------------------------------ editor

    private void open() {
        c.section("Editor: basics");
        scene = Scene.create();
        editor = new SceneEditorScreen(scene, "selftest_edit", null);
        editor.layout().reset(); // a known arrangement, whatever an earlier run saved
        mc.setScreen(editor);
        c.check("the editor opens", mc.screen == editor);
        c.check("an empty scene gets a default animation length", scene.timeline().length() == 100 && !scene.timeline().playing());
        c.check("the editor is drawn with Apothecary UI", editor instanceof me.methodial.apothecary.mc.screen.UIScreen);
    }

    private void blocks() throws Exception {
        c.section("Editor: placing and erasing blocks");
        // Slightly off-centre: the exact centre ray runs along a block edge, which is nobody's face.
        float cx = view().x() + view().width() / 2f + 7, cy = view().y() + view().height() / 2f + 9;
        key(GLFW.GLFW_KEY_B);
        click(cx, cy, 0);
        c.eq("Place tool: clicking empty space puts a block on the ground plane", 1, blockCount(scene.main()));
        click(cx, cy, 0);
        c.eq("clicking a block builds against the face under the cursor", 2, blockCount(scene.main()));
        button("Undo").onPress();
        c.eq("Undo removes the last block", 1, blockCount(scene.main()));
        button("Undo").onPress();
        c.eq("Undo again empties the scene", 0, blockCount(scene.main()));
        button("Redo").onPress();
        button("Redo").onPress();
        c.eq("Redo brings both back", 2, blockCount(scene.main()));

        box("minecraft:stone").setValue("minecraft:oak_stairs[facing=east,half=top]");
        click(cx, cy, 0);
        boolean[] stairs = {false};
        scene.main().store().forEachBlock((x, y, z, state) -> stairs[0] |= state.toString().contains("oak_stairs") && state.toString().contains("half=top"));
        c.check("the id box accepts block states with properties", stairs[0] && blockCount(scene.main()) == 3);

        box("minecraft:oak_stairs[facing=east,half=top]").setValue("not a block!");
        click(cx, cy, 0);
        c.eq("an invalid id places nothing", 3, blockCount(scene.main()));
        box("not a block!").setValue("minecraft:gold_block");

        key(GLFW.GLFW_KEY_E);
        click(cx, cy, 0);
        c.eq("Erase tool removes the block under the cursor", 2, blockCount(scene.main()));
        click(view().x() + 3, view().y() + 3, 0);
        c.eq("erasing empty space does nothing", 2, blockCount(scene.main()));

        float yaw = scene.camera().yaw(), zoom = scene.camera().orthoHeight();
        drag(cx, cy, cx + 30, cy, 1);
        c.near("right-drag orbits the camera", yaw - 18, scene.camera().yaw(), 1e-3);
        Vector3f target = new Vector3f(scene.camera().target());
        drag(cx, cy, cx, cy + 20, 2);
        c.check("middle-drag pans the camera", scene.camera().target().distance(target) > 0.1f);
        editor.viewport().scroll(1);
        c.near("the wheel zooms", zoom * 0.9, scene.camera().orthoHeight(), 1e-4);
        c.eq("navigating never edits the scene", 2, blockCount(scene.main()));
        scene.camera().frame(new Vector3f(2, 1, 2), 7);
        glClean("block tools");
    }

    private void partsAndElements() {
        c.section("Editor: parts and elements");
        button("+ Part").onPress();
        c.eq("+Part adds a part", 2, scene.parts().size());
        ScenePart part = scene.parts().get(1);
        float cx = view().x() + view().width() / 2f, cy = view().y() + view().height() / 2f;
        click(cx, cy - 20, 0);
        c.check("blocks placed with a part selected go into that part", blockCount(part) == 1 && blockCount(scene.main()) == 2,
                blockCount(part) + " in part, " + blockCount(scene.main()) + " in main");

        box("minecraft:gold_block").setValue("minecraft:zombie");
        button("+ Entity").onPress();
        zombie = (EntityElement) part.elements().stream().filter(e -> e instanceof EntityElement).findFirst().orElse(null);
        c.check("+Entity adds an entity element to the selected part", zombie != null && zombie.name().equals("zombie"));
        box("minecraft:zombie").setValue("minecraft:diamond_pickaxe");
        button("+ Item").onPress();
        c.check("+Item adds an item element", scene.findElement("diamond_pickaxe") instanceof ItemElement);
        box("minecraft:diamond_pickaxe").setValue("minecraft:tnt");
        button("+ Block").onPress();
        c.check("+Block adds a block element", scene.findElement("tnt") instanceof BlockElement);
        button("+ Item").onPress();
        c.check("a block id is not accepted as an item… unless it is one (tnt is)", scene.findElement("tnt_2") instanceof ItemElement);
        box("minecraft:tnt").setValue("minecraft:nonsense");
        int before = part.elements().size();
        button("+ Entity").onPress();
        button("+ Item").onPress();
        button("+ Block").onPress();
        c.eq("unknown ids add nothing", before, part.elements().size());
        button("Undo").onPress();
        c.check("Undo removes the last added element", scene.findElement("tnt_2") == null && scene.findElement("tnt") != null);
        zombie = (EntityElement) scene.findElement("zombie");
    }

    private void gizmo() throws Exception {
        c.section("Editor: selection and gizmo");
        key(GLFW.GLFW_KEY_V);
        ScenePart part = scene.partOf(zombie);
        scene.camera().frame(new Vector3f(2, 1, 2), 7);
        zombie.setPosition(4, 1, 4);
        // Keep the other elements out of the way of the clicks below.
        ((ItemElement) scene.findElement("diamond_pickaxe")).setPosition(9, 3, -5);
        ((BlockElement) scene.findElement("tnt")).setPosition(-7, 3, 7);
        // Give the entity a frame to exist so its picking bounds are real; this stage runs after a pause.
        Vector3f center = part.matrix().transformPosition(zombie.pickCenter(new Vector3f()));
        Vector2f at = project(center);
        click(view().x() + 2, view().y() + 2, 0);
        click(at.x, at.y, 0);
        screenshot("editor_selected.png");

        // The X handle starts at the element's centre and runs along +X for about 42 pixels.
        Vector2f tip = project(new Vector3f(center).add(1, 0, 0));
        Vector2f direction = new Vector2f(tip).sub(at).normalize();
        Vector2f grab = new Vector2f(direction).mul(25).add(at);
        Vector3f startPosition = new Vector3f(zombie.position());
        drag(grab.x, grab.y, grab.x + direction.x * 30, grab.y + direction.y * 30, 0);
        Vector3f moved = new Vector3f(zombie.position()).sub(startPosition);
        c.check("clicking an element selects it and dragging its X handle moves it along X only",
                moved.x > 0.2f && Math.abs(moved.y) < 1e-3 && Math.abs(moved.z) < 1e-3, moved.toString());
        float pixelsPerBlock = tip.distance(at);
        c.near("the element follows the mouse exactly (30 px)", 30.0 / pixelsPerBlock, moved.x, 0.02);
        button("Undo").onPress();
        zombie = (EntityElement) scene.findElement("zombie");
        c.check("a gizmo drag is one undo step", zombie != null && zombie.position().distance(startPosition) < 1e-4);

        // Undo keeps the selection by name, so the gizmo is still on the zombie.
        // Undo reloaded the scene: recompute where the gizmo is from the fresh element.
        center = part.matrix().transformPosition(zombie.pickCenter(new Vector3f()));
        at = project(center);
        key(GLFW.GLFW_KEY_R);
        var startRotation = new org.joml.Quaternionf(zombie.rotation());
        Vector2f yTip = project(new Vector3f(center).add(0, 1, 0));
        Vector2f yDirection = new Vector2f(yTip).sub(at).normalize();
        Vector2f yGrab = new Vector2f(yDirection).mul(25).add(at);
        drag(yGrab.x, yGrab.y, yGrab.x + 40, yGrab.y, 0);
        float angle = (float) Math.toDegrees(new org.joml.Quaternionf(zombie.rotation()).difference(startRotation).angle());
        c.near("Rotate gizmo: dragging the Y handle 40 px turns the element 40° about Y", 40, angle, 0.5);
        Vector3f up = zombie.rotation().transform(new Vector3f(0, 1, 0));
        c.check("...and only about Y", up.distance(0, 1, 0) < 1e-3, up.toString());

        key(GLFW.GLFW_KEY_S);
        drag(yGrab.x, yGrab.y, yGrab.x + 50, yGrab.y, 0);
        c.near("Scale gizmo: 50 px right scales by 1.5", 1.5, zombie.scale(), 1e-3);
        key(GLFW.GLFW_KEY_G);

        // Clicking a block selects its part; the part gizmo moves all its blocks at once without rebuilding.
        ScenePart main = scene.main();
        Vector3f blockCenter = new Vector3f();
        main.store().forEachBlock((x, y, z, state) -> blockCenter.set(x + 0.5f, y + 0.5f, z + 0.5f));
        Vector2f onBlock = project(blockCenter);
        click(onBlock.x, onBlock.y, 0);
        Vector2f origin = project(main.matrix().transformPosition(new Vector3f(main.pivot())));
        Vector2f partTip = project(main.matrix().transformPosition(new Vector3f(main.pivot())).add(0, 1, 0));
        Vector2f partDirection = new Vector2f(partTip).sub(origin).normalize();
        Vector2f partGrab = new Vector2f(partDirection).mul(25).add(origin);
        drag(partGrab.x, partGrab.y, partGrab.x + partDirection.x * 20, partGrab.y + partDirection.y * 20, 0);
        c.check("clicking a block selects its part and the Y handle lifts the whole part", main.offset().y > 0.1f && main.offset().x == 0,
                main.offset().toString());
        button("Undo").onPress();
        zombie = (EntityElement) scene.findElement("zombie");
        glClean("gizmo");
    }

    private void keyframes() {
        c.section("Editor: keyframing");
        Timeline t = scene.timeline();
        ScenePart part = scene.partOf(zombie);
        Vector2f at = project(part.matrix().transformPosition(zombie.pickCenter(new Vector3f())));
        click(view().x() + 2, view().y() + 2, 0);
        click(at.x, at.y, 0);
        int tracksBefore = t.tracks().size();
        key(GLFW.GLFW_KEY_K);
        Track<?> position = t.find("element/zombie/position");
        c.check("K keys the selection's transform at the current time", position != null && position.keys().size() == 1 && position.keys().getFirst().time() == 0,
                (t.tracks().size() - tracksBefore) + " tracks created");
        c.check("...including rotation and yaw", t.find("element/zombie/rotation") != null && t.find("element/zombie/yaw") != null);

        for (int i = 0; i < 20; i++) {
            key(GLFW.GLFW_KEY_RIGHT);
        }
        c.near("arrow keys step time", 20, t.time(), 0);
        Vector3f start = new Vector3f(zombie.position());
        zombie.setPosition(start.x, start.y + 4, start.z);
        key(GLFW.GLFW_KEY_K);
        c.eq("a second K at another time adds a second key", 2, position.keys().size());
        key(GLFW.GLFW_KEY_HOME);
        c.near("Home rewinds, and the animation puts the element back", start.y, zombie.position().y, 1e-4);
        for (int i = 0; i < 10; i++) {
            key(GLFW.GLFW_KEY_RIGHT);
        }
        c.near("halfway through, the element is halfway", start.y + 2, zombie.position().y, 1e-3);

        button("Auto key").onPress();
        zombie.setPosition(start.x + 1, zombie.position().y, start.z);
        // Auto key acts on edits made through the editor; simulate one with a gizmo drag along X.
        Vector3f center = part.matrix().transformPosition(zombie.pickCenter(new Vector3f()));
        Vector2f origin = project(center);
        Vector2f direction = project(new Vector3f(center).add(1, 0, 0)).sub(origin).normalize();
        Vector2f grab = new Vector2f(direction).mul(25).add(origin);
        drag(grab.x, grab.y, grab.x + direction.x * 10, grab.y + direction.y * 10, 0);
        c.eq("with Auto key on, a gizmo drag writes a key at the current time", 3, position.keys().size());
        c.check("the auto key landed at t=10", position.keyAt(10) >= 0);
        button("Auto key").onPress();

        key(GLFW.GLFW_KEY_SPACE);
        c.check("Space starts playback", t.playing());
    }

    private void tlClick(float x, float y, int button) {
        editor.timelineNode().press(x, y, button);
        editor.timelineNode().release();
    }

    private void tlDrag(float x0, float y0, float x1, int button) {
        var tl = editor.timelineNode();
        tl.press(x0, y0, button);
        for (int i = 1; i <= 8; i++) {
            tl.dragTo(x0 + (x1 - x0) * i / 8);
        }
        tl.release();
    }

    private void timelinePanel() throws Exception {
        c.section("Editor: timeline panel");
        Timeline t = scene.timeline();
        c.check("playback advanced time on its own", t.time() != 10, "t=" + t.time());
        key(GLFW.GLFW_KEY_SPACE);
        c.check("Space pauses", !t.playing());

        var tl = editor.timelineNode();
        float rulerY = tl.rulerY();
        tlClick(tl.xOf(50), rulerY, 0);
        c.near("clicking the ruler scrubs to that time", 50, t.time(), 1);
        tlDrag(tl.xOf(50), rulerY, tl.xOf(25), 0);
        c.near("dragging scrubs", 25, t.time(), 1);

        // Drag the position track's key at t=20 to t=60.
        Track<?> position = t.find("element/zombie/position");
        float rowY = tl.rowY(position);
        float timeBefore = t.time();
        tlDrag(tl.xOf(20), rowY, tl.xOf(60), 0);
        c.check("dragging a key moves it in time", position.keyAt(60) >= 0 && position.keyAt(20) < 0, position.keys().stream().map(Track.Key::time).toList().toString());
        c.near("dragging a key does not move the playhead", timeBefore, t.time(), 0);

        tlClick(tl.xOf(60), rowY, 0);
        Easing before = position.keys().get(position.keyAt(60)).easing();
        editor.model().cycleEasing(1);
        c.check("the easing control changes the selected key's easing", position.keys().get(position.keyAt(60)).easing() != before);
        c.eq("...and shows it", position.keys().get(position.keyAt(60)).easing(), editor.model().shownEasing());
        screenshot("editor_timeline.png");

        int keys = position.keys().size();
        tlClick(tl.xOf(60), rowY, 1);
        c.eq("right-clicking a key deletes it", keys - 1, position.keys().size());
        button("Undo").onPress();
        zombie = (EntityElement) scene.findElement("zombie");
        position = t.find("element/zombie/position");
        c.check("...and Undo restores it", position != null && position.keyAt(60) >= 0);

        Track<?> yaw = t.find("element/zombie/yaw");
        tlClick(tl.labelX(), tl.rowY(yaw), 1);
        c.check("right-clicking a track's name deletes the track", t.find("element/zombie/yaw") == null);
        glClean("timeline panel");
    }

    private void inspectorAndBones() throws Exception {
        c.section("Editor: inspector and posing");
        Timeline t = scene.timeline();
        t.seek(0);
        ScenePart part = scene.partOf(zombie);
        Vector2f at = project(part.matrix().transformPosition(zombie.pickCenter(new Vector3f())));
        click(view().x() + 2, view().y() + 2, 0);
        click(at.x, at.y, 0);
        c.check("bones are listed once the entity has been drawn", zombie.boneNames().contains("head"), zombie.boneNames().toString());

        // A distinctive value makes the inspector's field for it findable by what it displays.
        zombie.setHeadPitch(33.25f);
    }

    /** Runs a few frames later, once the inspector has refreshed its fields from the scene. */
    private void inspectorType() {
        Timeline t = scene.timeline();
        Box field = box("33.25");
        field.setValue("-45");
        c.near("typing in an inspector field changes the value", -45, zombie.headPitch(), 1e-4);
        field.setValue("abc");
        c.near("unparseable input is ignored", -45, zombie.headPitch(), 1e-4);
        button("Undo").onPress();
        zombie = (EntityElement) scene.findElement("zombie");
        c.near("typing is undoable", 33.25, zombie.headPitch(), 1e-4);
        zombie.setHeadPitch(0);

        zombie.setBone("head", 12.5f, 0, 0);
        tracksBeforePosing = t.tracks().size();
    }

    private void bonesType() throws Exception {
        Timeline t = scene.timeline();
        int tracks = tracksBeforePosing;
        box("12.5").setValue("50");
        c.near("a bone can be posed from the inspector", 50, zombie.bone("head").x, 1e-4);
        screenshot("editor_posing.png");
        c.eq("posing alone does not create animation", tracks, t.tracks().size());

        var poseButton = buttonStartingWith(editor.ui().rootNode(), "Pose: ");
        c.check("the inspector offers a Pose button for entities", poseButton != null);
        if (poseButton != null) {
            Pose before = zombie.pose();
            KrazSelfTest.uiClick(poseButton);
            c.check("the Pose button cycles vanilla poses", zombie.pose() != before, before + " -> " + zombie.pose());
        }
        glClean("inspector");
    }

    private void filesAndDelete() throws Exception {
        // Screenshots show the last rendered frame, i.e. the state the previous stage left behind.
        screenshot("editor_posed.png");
        c.section("Editor: save, load, delete");
        Path file = SceneIO.scenePath("selftest_edit");
        Files.deleteIfExists(file);
        button("Save").onPress();
        c.check("Save writes kraz/scenes/<name>.nbt", Files.isRegularFile(file));
        Scene saved = SceneIO.read(file);
        c.eq("the saved file matches the edited scene", List.of(), SceneDiff.diff(scene, saved));

        Vector2f onZombie = project(scene.partOf(zombie).matrix().transformPosition(zombie.pickCenter(new Vector3f())));
        click(view().x() + 2, view().y() + 2, 0);
        click(onZombie.x, onZombie.y, 0);
        key(GLFW.GLFW_KEY_DELETE);
        c.check("Delete removes the selected element", scene.findElement("zombie") == null);
        c.check("...and its animation tracks", scene.timeline().tracks().stream().noneMatch(track -> track.binding() != null && track.binding().startsWith("element/zombie/")));
        menuItem("File", "New");
        c.check("New with unsaved changes asks first", editor.ui().hasModal());
        var confirm = KrazSelfTest.uiButton(editor.ui().rootNode(), "New scene");
        c.check("...offering to start over", confirm != null);
        if (confirm != null) {
            KrazSelfTest.uiClick(confirm);
        }
        c.check("New empties the scene", scene.bounds() == null && scene.parts().size() == 1 && scene.timeline().tracks().isEmpty());
        menuItem("File", "Open");
        c.eq("Load restores the saved scene into the open editor", List.of(), SceneDiff.diff(saved, scene));
        button("Undo").onPress();
        c.check("Load is undoable", scene.bounds() == null);
        button("Redo").onPress();
        c.eq("...and redoable", List.of(), SceneDiff.diff(saved, scene));

        // Deleting a part takes its elements and their tracks with it.
        ScenePart part = scene.parts().get(1);
        Vector3f blockCenter = new Vector3f();
        part.store().forEachBlock((x, y, z, state) -> blockCenter.set(x + 0.5f, y + 0.5f, z + 0.5f));
        key(GLFW.GLFW_KEY_V);
        Vector2f onBlock = project(part.matrix().transformPosition(blockCenter));
        click(onBlock.x, onBlock.y, 0);
        key(GLFW.GLFW_KEY_DELETE);
        c.check("deleting a part removes it with its elements and tracks", scene.parts().size() == 1 && scene.findElement("zombie") == null
                && scene.timeline().tracks().stream().noneMatch(track -> track.binding() != null && track.binding().startsWith("element/")),
                scene.parts().size() + " parts, " + scene.timeline().tracks().size() + " tracks");
        key(GLFW.GLFW_KEY_DELETE);
        c.eq("the main part cannot be deleted", 1, scene.parts().size());
        screenshot("editor_final.png");
        glClean("editor file operations");
    }

    // ------------------------------------------------------------------ menus and windows

    /** Opens a menu from the menu bar and clicks the item whose label starts with {@code item}, as a user would. */
    private void menuItem(String menu, String item) {
        var root = editor.ui().rootNode();
        var button = root.findById("menu-" + menu.toLowerCase(java.util.Locale.ROOT));
        if (button == null) {
            throw new IllegalStateException("No " + menu + " menu");
        }
        KrazSelfTest.uiClick(button);
        editor.ui().layoutNow();
        // Only the menu just opened: a closed one fades out for a moment and is still in the tree.
        var open = findAll(root, me.methodial.apothecary.widget.Menu.class, new java.util.ArrayList<>()).stream()
                .filter(m -> !m.hasState("closing")).toList();
        if (open.isEmpty()) {
            throw new IllegalStateException("The " + menu + " menu did not open");
        }
        for (var node : withCls(open.getLast(), "menu-label", new java.util.ArrayList<>())) {
            if (node instanceof me.methodial.apothecary.widget.Text t && t.text().startsWith(item)) {
                KrazSelfTest.uiClick(t.parent());
                return;
            }
        }
        throw new IllegalStateException("No '" + item + "' in the " + menu + " menu");
    }

    private static me.methodial.apothecary.event.MouseEvent dragBy(float x, float y, float dx, float dy) {
        return new me.methodial.apothecary.event.MouseEvent(x, y, 0, 0, dx, dy);
    }

    private me.methodial.apothecary.node.Node head(me.methodial.apothecary.node.Node window) {
        return withCls(window, "kz-win-head", new java.util.ArrayList<>()).getFirst();
    }

    private void windows() throws Exception {
        c.section("Editor: windows, docks and splitters");
        var layout = editor.layout();
        var root = editor.ui().rootNode();
        editor.ui().layoutNow();
        c.check("the menu bar has File, Edit, View, Add, Window and Help",
                java.util.stream.Stream.of("file", "edit", "view", "add", "window", "help").allMatch(m -> root.findById("menu-" + m) != null));
        c.check("the panels start docked: outliner left, properties right, timeline bottom",
                editor.window(me.methodial.kraz.editor.EditorLayout.PanelId.OUTLINER) != null
                        && editor.window(me.methodial.kraz.editor.EditorLayout.PanelId.OUTLINER).hasCls("kz-docked")
                        && editor.window(me.methodial.kraz.editor.EditorLayout.PanelId.PROPERTIES).hasCls("kz-docked")
                        && editor.window(me.methodial.kraz.editor.EditorLayout.PanelId.TIMELINE).hasCls("kz-docked"));
        c.check("the navigation gizmo and tool bar are shown", !withCls(root, "kz-nav", new java.util.ArrayList<>()).isEmpty()
                && root.findById("tool-place") != null);

        // Splitters resize the docks.
        var splitters = withCls(root, "kz-split", new java.util.ArrayList<>());
        var left = splitters.stream().filter(n -> n.hasCls("v")).findFirst().orElseThrow();
        float before = layout.leftWidth;
        editor.ui().layoutNow();
        float shownBefore = editor.window(me.methodial.kraz.editor.EditorLayout.PanelId.OUTLINER).parent().width();
        left.dispatchLocal(me.methodial.apothecary.event.EventType.DRAG, dragBy(0, 0, 40, 0));
        editor.ui().layoutNow();
        var outliner = editor.window(me.methodial.kraz.editor.EditorLayout.PanelId.OUTLINER);
        c.near("dragging the left splitter widens the outliner", before + 40, layout.leftWidth, 0.01);
        c.check("...and the dock grows (giving way only where the window is too narrow)", outliner.parent().width() > shownBefore + 5,
                shownBefore + " -> " + outliner.parent().width());
        var bottom = splitters.stream().filter(n -> n.hasCls("h")).findFirst().orElseThrow();
        float bottomBefore = layout.bottomHeight;
        bottom.dispatchLocal(me.methodial.apothecary.event.EventType.DRAG, dragBy(0, 0, 0, -30));
        c.near("dragging the timeline's splitter up makes it taller", bottomBefore + 30, layout.bottomHeight, 0.01);
        left.dispatchLocal(me.methodial.apothecary.event.EventType.DRAG, dragBy(0, 0, 4000, 0));
        c.check("a dock cannot be dragged wider than the window allows", layout.leftWidth <= editor.ui().width() * 0.42f + 0.01f);
        left.dispatchLocal(me.methodial.apothecary.event.EventType.DRAG, dragBy(0, 0, -4000, 0));
        c.near("...nor narrower than its minimum", 90, layout.leftWidth, 0.01);

        // Pull the properties out of their dock by the title bar.
        var properties = editor.window(me.methodial.kraz.editor.EditorLayout.PanelId.PROPERTIES);
        var title = head(properties);
        title.dispatchLocal(me.methodial.apothecary.event.EventType.MOUSE_DOWN, dragBy(500, 60, 0, 0));
        title.dispatchLocal(me.methodial.apothecary.event.EventType.DRAG, dragBy(300, 80, -20, 0));
        c.eq("dragging a docked title bar out floats the panel", me.methodial.kraz.editor.EditorLayout.Place.FLOAT,
                layout.place(me.methodial.kraz.editor.EditorLayout.PanelId.PROPERTIES));
    }

    private void windowsFloated() throws Exception {
        var layout = editor.layout();
        var id = me.methodial.kraz.editor.EditorLayout.PanelId.PROPERTIES;
        var win = editor.window(id);
        c.check("the floated panel is now a floating window", win != null && win.hasCls("kz-float"));
        screenshot("editor_floating.png");
        var state = layout.panel(id);
        float x = state.x, y = state.y, w = state.w;
        head(win).dispatchLocal(me.methodial.apothecary.event.EventType.DRAG, dragBy(200, 150, -30, 20));
        c.check("dragging a floating window's title bar moves it", Math.abs(state.x - (x - 30)) < 0.5f && Math.abs(state.y - Math.min(y + 20, editor.ui().height() - state.h)) < 0.5f,
                state.x + "," + state.y);
        var grip = withCls(win, "kz-grip", new java.util.ArrayList<>()).getFirst();
        grip.dispatchLocal(me.methodial.apothecary.event.EventType.DRAG, dragBy(0, 0, 40, 25));
        c.near("dragging its corner resizes it", w + 40, state.w, 0.5);
        head(win).dispatchLocal(me.methodial.apothecary.event.EventType.DRAG, dragBy(5000, 5000, 9000, 9000));
    }

    /**
     * A drag through UIRoot's real input path, as the mouse produces it: press, many small moves, release. The
     * window must end exactly where the pointer took it, and the splitter must sit under the pointer.
     */
    private void windowsFollowPointer() throws Exception {
        var ui = editor.ui();
        var id = me.methodial.kraz.editor.EditorLayout.PanelId.PROPERTIES;
        var state = editor.layout().panel(id);
        var win = editor.window(id);
        if (win == null || !win.hasCls("kz-float")) {
            c.check("the properties window is floating for the pointer test", false);
            return;
        }
        // Bring it somewhere with room around it.
        state.x = 100;
        state.y = 60;
        state.w = 220;
        state.h = 150;
        ui.layoutNow();
        var head = head(win).globalBounds();
        float px = head.x() + 30, py = head.y() + 5;
        float startX = state.x, startY = state.y;
        ui.mouseMove(px, py);
        ui.mouseDown(px, py, 0);
        for (int i = 1; i <= 20; i++) {
            ui.mouseMove(px + i * 3, py + i * 2);
        }
        ui.mouseUp(px + 60, py + 40, 0);
        c.near("a window dragged 60 units right ends 60 units right (no runaway)", startX + 60, state.x, 0.5);
        c.near("...and 40 units down", startY + 40, state.y, 0.5);

        // The left splitter, dragged through the real input path, ends under the pointer.
        var split = withCls(ui.rootNode(), "kz-split", new java.util.ArrayList<>()).stream().filter(n -> n.hasCls("v")).findFirst().orElseThrow();
        ui.layoutNow();
        var sb = split.globalBounds();
        float sx = sb.x() + sb.width() / 2, sy = sb.y() + sb.height() / 2;
        float width = editor.layout().leftWidth;
        ui.mouseMove(sx, sy);
        ui.mouseDown(sx, sy, 0);
        for (int i = 1; i <= 15; i++) {
            ui.mouseMove(sx + i * 2, sy);
        }
        ui.mouseUp(sx + 30, sy, 0);
        c.near("the left splitter dragged 30 units makes the dock 30 wider", width + 30, editor.layout().leftWidth, 0.5);
    }

    private void windowsClamped() throws Exception {
        var layout = editor.layout();
        var id = me.methodial.kraz.editor.EditorLayout.PanelId.PROPERTIES;
        var win = editor.window(id);
        var b = win.globalBounds();
        float w = editor.ui().width(), h = editor.ui().height();
        c.check("a floating window cannot be dragged off the screen", b.x() >= -0.5f && b.y() >= -0.5f && b.x() + b.width() <= w + 0.5f && b.y() + b.height() <= h + 0.5f,
                b + " in " + w + "x" + h);
        // Drop it at the left edge: it docks there.
        float sx = 10, sy = h / 2;
        head(win).dispatchLocal(me.methodial.apothecary.event.EventType.DRAG, dragBy(sx, sy, 0, 0));
        head(win).dispatchLocal(me.methodial.apothecary.event.EventType.DRAG_END, dragBy(sx, sy, 0, 0));
        c.eq("dropping a window at the left edge docks it there", me.methodial.kraz.editor.EditorLayout.Place.LEFT, layout.place(id));
    }

    private void windowsDocked() throws Exception {
        var layout = editor.layout();
        var id = me.methodial.kraz.editor.EditorLayout.PanelId.PROPERTIES;
        var win = editor.window(id);
        c.check("...beside the outliner in the same dock", win != null && win.hasCls("kz-docked")
                && win.parent() == editor.window(me.methodial.kraz.editor.EditorLayout.PanelId.OUTLINER).parent());
        screenshot("editor_docked_left.png");
        // The Window menu hides and shows panels.
        menuItem("Window", "Timeline");
        c.check("Window > Timeline hides the timeline", !layout.visible(me.methodial.kraz.editor.EditorLayout.PanelId.TIMELINE));
        menuItem("Window", "Timeline");
        c.check("...and brings it back", layout.visible(me.methodial.kraz.editor.EditorLayout.PanelId.TIMELINE));
        menuItem("Window", "Navigation gizmo");
        c.check("the navigation gizmo can be hidden", !layout.navGizmo);
        menuItem("Window", "Navigation gizmo");
        menuItem("Window", "Reset layout");
        c.eq("Reset layout puts the properties back on the right", me.methodial.kraz.editor.EditorLayout.Place.RIGHT, layout.place(id));
        uiWidthBefore = editor.ui().width();
        menuItem("View", "Larger");
        c.near("View > Larger sets the scale right away", 1.25, layout.uiScale, 1e-4);
    }

    private float uiWidthBefore;

    private void windowsScaled() throws Exception {
        var layout = editor.layout();
        c.near("View > Larger raises the UI scale", 1.25, layout.uiScale, 1e-4);
        c.check("...so the UI is laid out with fewer, larger units", editor.ui().width() < uiWidthBefore - 1,
                uiWidthBefore + " -> " + editor.ui().width());
        screenshot("editor_scaled.png");
        key(GLFW.GLFW_KEY_0); // without Ctrl: the isometric view, not the scale
        c.near("a bare 0 does not touch the UI scale", 1.25, layout.uiScale, 1e-4);
        editor.setUiScale(1);
        c.near("the scale can be put back", 1, layout.uiScale, 1e-4);
        // The navigation gizmo: clicking its +X ball looks along X.
        var gizmo = withCls(editor.ui().rootNode(), "kz-nav", new java.util.ArrayList<>());
        c.check("the navigation gizmo is on screen", !gizmo.isEmpty());
        scene.camera().setYaw(10).setPitch(10);
    }

    // ------------------------------------------------------------------ window sizes

    private int originalWidth = -1, originalHeight = -1;

    private void resize(int width, int height) {
        var window = mc.getWindow();
        if (originalWidth < 0) {
            originalWidth = window.getWidth();
            originalHeight = window.getHeight();
        }
        if (width > 0 && height > 0) {
            window.setWindowed(width, height);
        }
    }

    private void checkLayout(String size) throws Exception {
        if (size.equals("1600x900")) {
            c.section("Editor: layout at different window sizes");
        }
        var window = mc.getWindow();
        String actual = window.getWidth() + "x" + window.getHeight();
        if (!actual.equals(size)) {
            c.info("window is " + actual + " instead of " + size + " (the OS or screen limited it); checking that size");
        }
        screenshot("editor_size_" + size + ".png");
        var ui = editor.ui();
        ui.layoutNow();
        float w = ui.width(), h = ui.height();
        var cards = withCls(ui.rootNode(), "kz-card", new java.util.ArrayList<>()).stream().filter(n -> !n.hasCls("kz-float"))
                .filter(n -> !n.hasCls("kz-collapsed") && n.width() > 0 && n.height() > 0).toList();
        boolean inside = true;
        StringBuilder where = new StringBuilder();
        for (var card : cards) {
            var b = card.globalBounds();
            if (b.x() < -0.5f || b.y() < -0.5f || b.x() + b.width() > w + 0.5f || b.y() + b.height() > h + 0.5f) {
                inside = false;
                where.append(String.join(".", card.classes())).append(' ').append(b).append("; ");
            }
        }
        c.check(size + ": every panel is fully on screen", inside, where.length() == 0 ? cards.size() + " panels, UI " + w + "x" + h : where.toString());
        boolean overlap = false;
        StringBuilder pairs = new StringBuilder();
        for (int i = 0; i < cards.size(); i++) {
            for (int j = i + 1; j < cards.size(); j++) {
                var a = cards.get(i).globalBounds();
                var b = cards.get(j).globalBounds();
                if (cards.get(i).isDescendantOf(cards.get(j)) || cards.get(j).isDescendantOf(cards.get(i))) {
                    continue;
                }
                float ox = Math.min(a.x() + a.width(), b.x() + b.width()) - Math.max(a.x(), b.x());
                float oy = Math.min(a.y() + a.height(), b.y() + b.height()) - Math.max(a.y(), b.y());
                if (ox > 0.5f && oy > 0.5f) {
                    overlap = true;
                    pairs.append(String.join(".", cards.get(i).classes())).append(" / ").append(String.join(".", cards.get(j).classes())).append("; ");
                }
            }
        }
        c.check(size + ": no two panels overlap", !overlap, pairs.toString());
        var center = withCls(ui.rootNode(), "kz-center", new java.util.ArrayList<>());
        if (!center.isEmpty()) {
            var v = center.getFirst().globalBounds();
            c.check(size + ": the 3D view keeps a usable area", v.width() >= w * 0.3f && v.height() >= h * 0.25f,
                    String.format(java.util.Locale.ROOT, "%.0fx%.0f of %.0fx%.0f", v.width(), v.height(), w, h));
        }
        var top = withCls(ui.rootNode(), "kz-top", new java.util.ArrayList<>());
        if (!top.isEmpty()) {
            boolean clipped = false;
            for (var child : top.getFirst().children()) {
                var b = child.globalBounds();
                if (b.x() + b.width() > w + 0.5f) {
                    clipped = true;
                }
            }
            c.check(size + ": nothing in the top bar runs off the edge", !clipped);
        }
    }

    // ------------------------------------------------------------------ UX additions

    private static <T extends me.methodial.apothecary.node.Node> List<T> findAll(me.methodial.apothecary.node.Node root, Class<T> type, List<T> out) {
        if (type.isInstance(root)) {
            out.add(type.cast(root));
        }
        for (var child : root.children()) {
            findAll(child, type, out);
        }
        return out;
    }

    private static List<me.methodial.apothecary.node.Node> withCls(me.methodial.apothecary.node.Node root, String cls, List<me.methodial.apothecary.node.Node> out) {
        if (root.hasCls(cls)) {
            out.add(root);
        }
        for (var child : root.children()) {
            withCls(child, cls, out);
        }
        return out;
    }

    private boolean hasText(me.methodial.apothecary.node.Node root, String text) {
        editor.ui().layoutNow(); // regions rebuild before layout
        return findAll(root, me.methodial.apothecary.widget.Text.class, new java.util.ArrayList<>()).stream().anyMatch(t -> text.equals(t.text()));
    }

    private void ux() throws Exception {
        c.section("Editor: painting, box fill, rename, duplicate, shortcuts");
        var model = editor.model();
        c.check("the editor wears the Ore UI theme and Menuet-style cards", !withCls(editor.ui().rootNode(), "kz-card", new java.util.ArrayList<>()).isEmpty());

        // A brush stroke: one press, a drag across the ground, one release.
        model.select(null);
        model.idText = "minecraft:oak_planks";
        model.tool = me.methodial.kraz.editor.EditorModel.Tool.PLACE;
        int before = blockCount(scene.main());
        float cx = view().x() + view().width() / 2f, cy = view().y() + view().height() / 2f + 20;
        var vp = editor.viewport();
        vp.press(cx - 40, cy, 0);
        for (int i = 1; i <= 16; i++) {
            vp.dragTo(cx - 40 + i * 5, cy);
        }
        vp.release();
        int painted = blockCount(scene.main()) - before;
        c.check("dragging with Place paints a row of blocks", painted >= 3, painted + " blocks");
        button("Undo").onPress();
        c.eq("the whole stroke is one undo step", before, blockCount(scene.main()));

        model.fillBox(new BlockPos(20, 0, 20), new BlockPos(22, 1, 23));
        c.eq("box fill places every block in the box", before + 3 * 2 * 4, blockCount(scene.main()));
        model.tool = me.methodial.kraz.editor.EditorModel.Tool.ERASE;
        model.fillBox(new BlockPos(20, 0, 20), new BlockPos(22, 0, 23));
        c.eq("box erase clears the box", before + 3 * 4, blockCount(scene.main()));
        model.fillBox(new BlockPos(20, 1, 20), new BlockPos(22, 1, 23));

        // Escape steps back one thing at a time.
        key(GLFW.GLFW_KEY_ESCAPE);
        c.eq("Escape first drops the tool", me.methodial.kraz.editor.EditorModel.Tool.SELECT, model.tool);
        model.select(scene.main());
        key(GLFW.GLFW_KEY_ESCAPE);
        c.check("then the selection", model.selection() == null);
        c.eq("...and does not close the editor", editor, mc.screen);

        // Rename carries animation along.
        model.addPart();
        ScenePart tower = (ScenePart) model.selection();
        tower.setBlock(new BlockPos(0, 0, 0), Blocks.STONE_BRICKS.defaultBlockState());
        tower.setBlock(new BlockPos(0, 1, 0), Blocks.STONE_BRICKS.defaultBlockState());
        model.keySelection();
        c.check("rename accepts a valid name", model.rename(tower, "tower"));
        c.check("rename rejects an invalid one", !model.rename(scene.findPart("tower"), "Bad Name!"));
        tower = scene.findPart("tower");
        c.check("the renamed part keeps its blocks and selection", tower != null && blockCount(tower) == 2 && model.selection() == tower);
        c.check("its tracks were renamed with it", scene.timeline().find("part/tower/offset") != null
                && scene.timeline().tracks().stream().noneMatch(t -> t.binding() != null && t.binding().startsWith("part/part_")));

        key(GLFW.GLFW_KEY_D); // without Ctrl this is not duplicate
        c.check("a bare D does nothing", scene.findPart("tower_copy") == null);
        model.duplicateSelection();
        ScenePart copy = scene.findPart("tower_copy");
        c.check("duplicating a part copies its blocks beside it", copy != null && blockCount(copy) == 2 && copy.offset().x > tower.offset().x);
        c.check("...and selects the copy", model.selection() == copy);
        button("Undo").onPress();
        c.check("duplicate is undoable", scene.findPart("tower_copy") == null);

        key(GLFW.GLFW_KEY_F1);
    }

    private void help() {
        c.check("F1 opens the shortcuts", hasText(editor.ui().rootNode(), "Shortcuts"));
        c.info("before Escape: focused=" + editor.ui().focused() + ", shortcuts visible=" + editor.layout().visible(me.methodial.kraz.editor.EditorLayout.PanelId.SHORTCUTS));
        key(GLFW.GLFW_KEY_ESCAPE);
        key(GLFW.GLFW_KEY_TAB);
    }

    private void library() throws Exception {
        c.section("Editor: library");
        var menus = new java.util.ArrayList<me.methodial.apothecary.widget.Menu>();
        findAll(editor.ui().rootNode(), me.methodial.apothecary.widget.Menu.class, menus);
        menus.removeIf(m -> m.hasState("closing"));
        c.check("menus close after an item is chosen", menus.isEmpty(), menus.size() + " still open");
        for (var text : findAll(editor.ui().rootNode(), me.methodial.apothecary.widget.Text.class, new java.util.ArrayList<>())) {
            if ("Shortcuts".equals(text.text())) {
                StringBuilder chain = new StringBuilder();
                for (var n = (me.methodial.apothecary.node.Node) text; n != null; n = n.parent()) {
                    chain.append(n.getClass().getSimpleName()).append(n.classes()).append(" < ");
                }
                c.info("\"Shortcuts\" found in: " + chain);
            }
        }
        c.check("Escape closed the shortcuts", !editor.layout().visible(me.methodial.kraz.editor.EditorLayout.PanelId.SHORTCUTS) && !hasText(editor.ui().rootNode(), "Shortcuts"),
                "visible=" + editor.layout().visible(me.methodial.kraz.editor.EditorLayout.PanelId.SHORTCUTS));
        var model = editor.model();
        var root = editor.ui().rootNode();
        var tiles = withCls(root, "kz-tile", new java.util.ArrayList<>());
        c.check("Tab opens the library with block tiles", !tiles.isEmpty(), tiles.size() + " tiles");
        var panel = root.findById("panel-library");
        var popover = panel == null ? java.util.List.<me.methodial.apothecary.node.Node>of() : java.util.List.of(panel);
        var search = popover.isEmpty() ? null : findAll(popover.getFirst(), me.methodial.apothecary.widget.TextField.class, new java.util.ArrayList<>());
        c.check("it has a search field", search != null && !search.isEmpty());
        if (search == null || search.isEmpty()) {
            return;
        }
        search.getFirst().text("diamond_block");
        searchField = search.getFirst();
    }

    private me.methodial.apothecary.widget.TextField searchField;

    private void project() throws Exception {
        screenshot("editor_library.png");
        var model = editor.model();
        var root = editor.ui().rootNode();
        var tiles = withCls(root, "kz-tile", new java.util.ArrayList<>());
        c.check("searching narrows the library", tiles.size() >= 1 && tiles.size() <= 3, tiles.size() + " tiles");
        if (!tiles.isEmpty()) {
            KrazSelfTest.uiClick(tiles.getFirst());
            c.eq("clicking a block tile makes it the brush", "minecraft:diamond_block", model.idText);
            c.eq("...and picks the Place tool", me.methodial.kraz.editor.EditorModel.Tool.PLACE, model.tool);
        }
        key(GLFW.GLFW_KEY_TAB);
        model.tool = me.methodial.kraz.editor.EditorModel.Tool.SELECT;

        c.section("Show in world");
        button("World").onPress();
        var feet = me.methodial.kraz.editor.EditorModel.playerFeet();
        c.check("the player's position is known", feet != null);
        var projection = model.projectAt(feet.add(0, 0, 6));
        c.check("projecting adds the scene to the world", projection != null && me.methodial.kraz.world.WorldProjections.all().contains(projection));
        projection.setScale(0.5f).setYaw(45).setShowBounds(true);
        var bounds = projection.worldBounds();
        var sceneBounds = scene.bounds();
        c.check("scale and rotation shape where it stands", bounds != null && sceneBounds != null
                && bounds.getYsize() > sceneBounds.getYSpan() * 0.45 && bounds.getYsize() < sceneBounds.getYSpan() * 0.55,
                bounds + " for " + sceneBounds);
        c.near("it stands on the chosen spot", feet.y, bounds.minY, 1e-3);
    }

    private void viewWorld() throws Exception {
        screenshot("editor_world_panel.png");
        var view = KrazSelfTest.uiButton(editor.ui().rootNode(), "View in world");
        c.check("the world panel offers View in world", view != null);
        c.check("...and the projection settings", hasText(editor.ui().rootNode(), "Scale") && hasText(editor.ui().rootNode(), "Facing"));
        c.check("the World button shows the scene is projected", button("World").node().hasCls("on"));
        if (view != null) {
            KrazSelfTest.uiClick(view);
        }
        c.check("View in world leaves the editor without asking (the session is kept)", mc.screen == null);
        worldBefore = null;
    }

    private NativeImage worldBefore;

    private void projectedInWorld() throws Exception {
        worldBefore = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        worldBefore.writeToFile(out.resolve("projection_in_world.png"));
        editor.model().projection().setVisible(false);
    }

    private void projectionHidden() throws Exception {
        try (NativeImage hidden = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            hidden.writeToFile(out.resolve("projection_hidden.png"));
            int diff = Images.differing(worldBefore, hidden, 40);
            c.check("the projected scene is visible in the world", diff > 600, diff + " px");
        } finally {
            worldBefore.close();
        }
        editor.model().projection().setVisible(true);
    }

    private void reopen() {
        c.section("Editor: sessions and closing");
        var screen = me.methodial.kraz.editor.EditorCommands.reopen();
        c.check("/kraz edit without a name returns to the same session",
                screen instanceof me.methodial.kraz.editor.SceneEditorScreen s && s.model() == editor.model());
        mc.setScreen(screen);
        editor = (me.methodial.kraz.editor.SceneEditorScreen) screen;
        c.check("...with the projection still in the world", editor.model().projection() != null);
        c.check("...and its unsaved changes still marked", editor.model().dirty());
    }

    private void close() {
        editor.onClose();
        c.check("closing with unsaved changes asks first", mc.screen == editor && editor.ui().hasModal());
        var discard = KrazSelfTest.uiButton(editor.ui().rootNode(), "Discard");
        c.check("...offering Discard", discard != null);
        if (discard != null) {
            KrazSelfTest.uiClick(discard);
        }
        c.check("closing the editor returns to the game", mc.screen == null);
        editor.model().stopProjecting();
        c.check("removing the projection takes it out of the world", me.methodial.kraz.world.WorldProjections.all().isEmpty());
        glClean("closing the editor");
    }
}
