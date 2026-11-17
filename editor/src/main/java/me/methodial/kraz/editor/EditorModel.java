package me.methodial.kraz.editor;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.methodial.kraz.anim.Easing;
import me.methodial.kraz.anim.Timeline;
import me.methodial.kraz.anim.Track;
import me.methodial.kraz.camera.SceneCamera;
import me.methodial.kraz.export.SceneExporter;
import me.methodial.kraz.render.RenderSettings;
import me.methodial.kraz.scene.Scene;
import me.methodial.kraz.scene.SceneEnvironment;
import me.methodial.kraz.scene.SceneIO;
import me.methodial.kraz.scene.ScenePart;
import me.methodial.kraz.scene.element.BlockElement;
import me.methodial.kraz.scene.element.ElementProperty;
import me.methodial.kraz.scene.element.EntityElement;
import me.methodial.kraz.scene.element.ItemElement;
import me.methodial.kraz.scene.element.SceneElement;
import me.methodial.kraz.scene.element.TransformedElement;
import me.methodial.kraz.world.WorldProjection;
import me.methodial.kraz.world.WorldProjections;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Everything the scene editor does, with no UI in it: the current tool and selection, undo history, creating and
 * deleting things, keyframing, and the list of editable values for whatever is selected. The screen is a view
 * of this; tests and other front ends can drive it directly.
 */
public final class EditorModel {
    public enum Tool {SELECT, PLACE, ERASE}

    public enum Gizmo {MOVE, ROTATE, SCALE}

    /** What an inspector row edits. */
    public enum Kind {HEADER, FLOAT, VEC3, EULER, ACTION, TEXT}

    /** One editable thing about the selection. */
    public static final class Row {
        public final Kind kind;
        public final String label;
        /** Timeline binding of this value, or null if it cannot be animated. */
        public @Nullable String binding;
        public float step = 1;
        Supplier<float[]> get;
        Consumer<float[]> set;
        /** ACTION: the button's current caption. TEXT: the current text. */
        public Supplier<String> text;
        Runnable action;
        Function<String, Boolean> apply;

        Row(Kind kind, String label) {
            this.kind = kind;
            this.label = label;
        }

        public float[] values() {
            return get.get();
        }

        Object keyValue() {
            float[] v = get.get();
            return switch (kind) {
                case FLOAT -> v[0];
                case VEC3 -> new Vector3f(v[0], v[1], v[2]);
                default -> quaternion(v);
            };
        }
    }

    private final Scene scene;
    public Tool tool = Tool.SELECT;
    public Gizmo gizmo = Gizmo.MOVE;
    public boolean autoKey;
    public Easing easing = Easing.LINEAR;
    public String idText = "minecraft:stone";
    public String sceneName;
    public @Nullable Track<?> selectedTrack;
    public int selectedKey = -1;

    private @Nullable Object selection;
    private String status = "Right-drag: orbit · middle-drag: pan · wheel: zoom";
    private boolean statusIsError;
    private final ArrayDeque<CompoundTag> undoStack = new ArrayDeque<>();
    private final ArrayDeque<CompoundTag> redoStack = new ArrayDeque<>();
    private @Nullable Object editToken;
    private long generation;
    private boolean dirty;
    private @Nullable WorldProjection projection;
    private boolean stroking;

    public EditorModel(Scene scene, String sceneName) {
        this.scene = scene;
        this.sceneName = sceneName;
        scene.timeline().setPlaying(false);
        if (scene.timeline().length() <= 0) {
            scene.timeline().setLength(100);
        }
    }

    public Scene scene() {
        return scene;
    }

    public String status() {
        return status;
    }

    public boolean statusIsError() {
        return statusIsError;
    }

    public void setStatus(String text) {
        status = text;
        statusIsError = false;
    }

    public void setError(String text) {
        status = text;
        statusIsError = true;
    }

    /** Changes whenever the selection or the scene's structure was replaced wholesale (undo, load). */
    public long generation() {
        return generation;
    }

    // ------------------------------------------------------------------ selection

    /** {@code null} (scene settings), a {@link ScenePart} or a {@link SceneElement}. */
    public @Nullable Object selection() {
        return selection;
    }

    public void select(@Nullable Object object) {
        if (selection != object) {
            selection = object;
            generation++;
        }
    }

    /** Binding prefix of the selection's tracks, for highlighting them in the timeline. */
    public @Nullable String selectionBindingPrefix() {
        if (selection instanceof ScenePart part) {
            return "part/" + part.name() + "/";
        }
        if (selection instanceof SceneElement element && !element.name().isEmpty()) {
            return "element/" + element.name() + "/";
        }
        return selection == null ? "camera/" : null;
    }

    /** The part new blocks and elements go into: the selected one, or the selected element's, or main. */
    public ScenePart targetPart() {
        if (selection instanceof ScenePart part) {
            return part;
        }
        if (selection instanceof SceneElement element) {
            ScenePart part = scene.partOf(element);
            if (part != null) {
                return part;
            }
        }
        return scene.main();
    }

    // ------------------------------------------------------------------ history

    /** Records the scene so the next change can be undone. */
    public void checkpoint() {
        dirty = true;
        undoStack.push(SceneIO.save(scene));
        while (undoStack.size() > 64) {
            undoStack.removeLast();
        }
        redoStack.clear();
        editToken = null;
    }

    /** As {@link #checkpoint()}, but only once per run of edits to the same thing (typing in one field). */
    public void editCheckpoint(Object token) {
        if (editToken != token) {
            checkpoint();
            editToken = token;
        }
    }

    /** Ends the current run of edits, so the next one gets its own undo step. */
    public void endEdit() {
        editToken = null;
    }

    private void restore(CompoundTag snapshot) {
        // The view is not part of the edit history: undoing a block should not also swing the camera back.
        SceneCamera camera = scene.camera();
        Vector3f target = new Vector3f(camera.target());
        float yaw = camera.yaw(), pitch = camera.pitch(), distance = camera.distance(), ortho = camera.orthoHeight();
        boolean orthographic = camera.orthographic();
        float time = scene.timeline().time();
        String partName = selection instanceof ScenePart part ? part.name() : null;
        String elementName = selection instanceof SceneElement element ? element.name() : null;

        SceneIO.loadInto(scene, snapshot);
        scene.timeline().setPlaying(false);
        scene.timeline().seek(time);
        if (scene.timeline().find("camera/yaw") == null && scene.timeline().find("camera/target") == null) {
            camera.setTarget(target).setYaw(yaw).setPitch(pitch).setDistance(distance).setOrthoHeight(ortho).setOrthographic(orthographic);
        }
        selection = partName != null ? scene.findPart(partName) : elementName != null ? scene.findElement(elementName) : null;
        clearKeySelection();
        editToken = null;
        generation++;
    }

    public void undo() {
        if (undoStack.isEmpty()) {
            setStatus("Nothing to undo");
            return;
        }
        redoStack.push(SceneIO.save(scene));
        restore(undoStack.pop());
        setStatus("Undo");
    }

    public void redo() {
        if (redoStack.isEmpty()) {
            setStatus("Nothing to redo");
            return;
        }
        undoStack.push(SceneIO.save(scene));
        restore(redoStack.pop());
        setStatus("Redo");
    }

    // ------------------------------------------------------------------ files

    public void save() {
        try {
            Path path = SceneIO.scenePath(sceneName);
            SceneIO.write(scene, path);
            dirty = false;
            setStatus("Saved kraz/scenes/" + path.getFileName());
        } catch (Exception e) {
            setError("Save failed: " + e.getMessage());
        }
    }

    public void load() {
        Path path = SceneIO.scenePath(sceneName);
        if (!Files.isRegularFile(path)) {
            setError("No saved scene named '" + sceneName + "'");
            return;
        }
        try {
            CompoundTag loaded = SceneIO.save(SceneIO.read(path));
            checkpoint();
            SceneIO.loadInto(scene, loaded);
            scene.timeline().setPlaying(false);
            if (scene.timeline().length() <= 0) {
                scene.timeline().setLength(100);
            }
            selection = null;
            clearKeySelection();
            generation++;
            dirty = false;
            setStatus("Loaded " + path.getFileName());
        } catch (Exception e) {
            setError("Load failed: " + e.getMessage());
        }
    }

    public void newScene() {
        checkpoint();
        scene.reset();
        scene.timeline().setLength(100).setPlaying(false);
        selection = null;
        clearKeySelection();
        generation++;
        dirty = false;
        setStatus("New scene");
    }

    public void exportPng() {
        report(SceneExporter.exportPng(scene, SceneExporter.exportPath(sceneName, "png"), 2048, 2048, RenderSettings.HIGH_QUALITY));
    }

    public void exportGif() {
        report(SceneExporter.exportGif(scene, SceneExporter.exportPath(sceneName, "gif"), 512, 512, 20, RenderSettings.HIGH_QUALITY));
    }

    private void report(CompletableFuture<Path> export) {
        setStatus("Exporting…");
        export.whenCompleteAsync((path, error) -> {
            if (error == null) {
                setStatus("Exported kraz/exports/" + path.getFileName());
            } else {
                setError("Export failed: " + error.getMessage());
            }
        }, Minecraft.getInstance());
    }

    // ------------------------------------------------------------------ creating and deleting

    public static @Nullable BlockState parseBlock(String text) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), text.trim(), false).blockState();
        } catch (CommandSyntaxException e) {
            return null;
        }
    }

    private static @Nullable ItemStack parseItem(String text) {
        ResourceLocation id = ResourceLocation.tryParse(text.trim());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return null;
        }
        return new ItemStack(BuiltInRegistries.ITEM.get(id));
    }

    /** Where new elements appear: the point the camera looks at, snapped to half blocks. */
    private Vector3f spawnPoint(ScenePart part) {
        Vector3f local = part.matrix().invert(new Matrix4f()).transformPosition(new Vector3f(scene.camera().target()));
        return local.set(Math.round(local.x * 2) / 2f, Math.round(local.y * 2) / 2f, Math.round(local.z * 2) / 2f);
    }

    public void addBlockElement() {
        BlockState state = parseBlock(idText);
        if (state == null) {
            setError("'" + idText + "' is not a block");
            return;
        }
        checkpoint();
        ScenePart part = targetPart();
        select(part.add(new BlockElement(state)).setPosition(spawnPoint(part)));
        setStatus("Added block element");
    }

    public void addItemElement() {
        ItemStack stack = parseItem(idText);
        if (stack == null) {
            setError("'" + idText + "' is not an item");
            return;
        }
        checkpoint();
        ScenePart part = targetPart();
        select(part.add(new ItemElement(stack)).setPosition(spawnPoint(part)));
        setStatus("Added item element");
    }

    public void addEntityElement() {
        ResourceLocation id = ResourceLocation.tryParse(idText.trim());
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            setError("'" + idText + "' is not an entity");
            return;
        }
        if (Minecraft.getInstance().level == null) {
            setError("Entities need a loaded world");
            return;
        }
        checkpoint();
        ScenePart part = targetPart();
        select(part.add(new EntityElement(BuiltInRegistries.ENTITY_TYPE.get(id))).setPosition(spawnPoint(part)));
        setStatus("Added entity; its bones show up in the inspector");
    }

    public void addPart() {
        checkpoint();
        int n = scene.parts().size();
        while (scene.findPart("part_" + n) != null) {
            n++;
        }
        select(scene.part("part_" + n));
        tool = Tool.PLACE;
        setStatus("New part selected: blocks you place now go into it");
    }

    private void removeTracks(String prefix) {
        for (Track<?> track : List.copyOf(scene.timeline().tracks())) {
            if (track.binding() != null && track.binding().startsWith(prefix)) {
                scene.timeline().removeTrack(track);
            }
        }
        clearKeySelection();
    }

    public void deleteSelection() {
        if (selection instanceof SceneElement element) {
            ScenePart part = scene.partOf(element);
            if (part != null) {
                checkpoint();
                part.remove(element);
                if (!element.name().isEmpty()) {
                    removeTracks("element/" + element.name() + "/");
                }
                select(null);
                setStatus("Deleted element");
            }
        } else if (selection instanceof ScenePart part) {
            if (part == scene.main()) {
                setError("The main part cannot be deleted (use Erase or New)");
                return;
            }
            checkpoint();
            for (SceneElement element : part.elements()) {
                if (!element.name().isEmpty()) {
                    removeTracks("element/" + element.name() + "/");
                }
            }
            scene.removePart(part);
            removeTracks("part/" + part.name() + "/");
            select(null);
            setStatus("Deleted part");
        }
    }

    /** Places the block named in {@link #idText}, or erases, at a cell of the target part. One undo step. */
    public void useBlockTool(BlockPos target) {
        ScenePart part = targetPart();
        if (tool == Tool.ERASE) {
            checkpoint();
            part.removeBlock(target);
            return;
        }
        BlockState state = parseBlock(idText);
        if (state == null) {
            setError("'" + idText + "' is not a block");
            return;
        }
        checkpoint();
        part.setBlock(target, state);
    }

    // ------------------------------------------------------------------ keyframes

    public void clearKeySelection() {
        selectedTrack = null;
        selectedKey = -1;
    }

    /** The selected key, if it still exists. */
    public Track.@Nullable Key<?> selectedKeyValue() {
        if (selectedTrack == null || !scene.timeline().tracks().contains(selectedTrack) || selectedKey < 0 || selectedKey >= selectedTrack.keys().size()) {
            return null;
        }
        return selectedTrack.keys().get(selectedKey);
    }

    /** 0: not animated, 1: has a track, 2: has a key at the current time. */
    public int keyState(Row row) {
        Track<?> track = row.binding == null ? null : scene.timeline().find(row.binding);
        if (track == null) {
            return 0;
        }
        return track.keyAt(Math.round(scene.timeline().time())) >= 0 ? 2 : 1;
    }

    @SuppressWarnings("unchecked")
    public void writeKey(Row row, boolean checkpoint) {
        if (row.binding == null) {
            return;
        }
        if (checkpoint) {
            checkpoint();
        }
        Timeline timeline = scene.timeline();
        Track<Object> track;
        try {
            track = timeline.bound(row.binding);
        } catch (IllegalArgumentException e) {
            setError("Cannot animate " + row.binding);
            return;
        }
        float time = Math.round(timeline.time());
        int existing = track.keyAt(time);
        track.setKey(time, row.keyValue(), existing >= 0 ? track.keys().get(existing).easing() : easing);
        if (checkpoint) {
            setStatus("Key: " + shortName(row.binding) + " @ " + (int) time);
        }
    }

    public void removeKey(Row row) {
        Timeline timeline = scene.timeline();
        Track<?> track = row.binding == null ? null : timeline.find(row.binding);
        int index = track == null ? -1 : track.keyAt(Math.round(timeline.time()));
        if (index < 0) {
            return;
        }
        checkpoint();
        track.removeKey(index);
        if (track.keys().isEmpty()) {
            timeline.removeTrack(track);
        }
        clearKeySelection();
    }

    /** Keys every animated value of the selection, plus its basic transform. */
    public void keySelection() {
        checkpoint();
        int count = 0;
        for (Row row : rows()) {
            if (row.binding == null) {
                continue;
            }
            boolean basic = row.binding.endsWith("/offset") || row.binding.endsWith("/position") || row.binding.endsWith("/rotation")
                    || row.binding.endsWith("/yaw") || row.binding.startsWith("camera/yaw") || row.binding.startsWith("camera/pitch");
            if (basic || scene.timeline().find(row.binding) != null) {
                writeKey(row, false);
                count++;
            }
        }
        setStatus("Keyed " + count + " values @ " + Math.round(scene.timeline().time()));
    }

    /** Writes a key for one binding of the selection if auto-key is on (used after gizmo drags). */
    public void autoKey(String binding) {
        if (!autoKey) {
            return;
        }
        for (Row row : rows()) {
            if (binding.equals(row.binding)) {
                writeKey(row, false);
            }
        }
    }

    /** Steps the easing of the selected key, or of keys created from now on. */
    public void cycleEasing(int direction) {
        Easing[] all = Easing.values();
        Track.Key<?> key = selectedKeyValue();
        Easing current = key != null ? key.easing() : easing;
        Easing next = all[Math.floorMod(current.ordinal() + direction, all.length)];
        if (key != null) {
            checkpoint();
            selectedTrack.setEasing(selectedKey, next);
            scene.timeline().seek(scene.timeline().time());
        } else {
            easing = next;
        }
    }

    /** Sets the easing of the selected key, or of keys created from now on. */
    public void setEasing(Easing value) {
        Track.Key<?> key = selectedKeyValue();
        if (key != null) {
            if (key.easing() != value) {
                checkpoint();
                selectedTrack.setEasing(selectedKey, value);
                scene.timeline().seek(scene.timeline().time());
            }
        } else {
            easing = value;
        }
    }

    public Easing shownEasing() {
        Track.Key<?> key = selectedKeyValue();
        return key != null ? key.easing() : easing;
    }

    public void togglePlay() {
        Timeline timeline = scene.timeline();
        if (!timeline.playing() && !timeline.loop() && timeline.time() >= timeline.length()) {
            timeline.seek(0);
        }
        timeline.setPlaying(!timeline.playing());
    }

    /** {@code part/tower/offset} reads better as {@code tower.offset} in a narrow column. */
    public static String shortName(String binding) {
        if (binding.startsWith("camera/")) {
            return "camera." + binding.substring(7);
        }
        String rest = binding.startsWith("part/") ? binding.substring(5) : binding.startsWith("element/") ? binding.substring(8) : binding;
        return rest.replace("/bone/", ".").replace('/', '.');
    }

    // ------------------------------------------------------------------ inspector rows

    /** Applies an edited value, keyframing it when auto-key is on. */
    public void apply(Row row, float[] values) {
        row.set.accept(values);
        if (autoKey && row.binding != null) {
            writeKey(row, false);
        }
    }

    public void runAction(Row row) {
        checkpoint();
        row.action.run();
    }

    /** @return whether the text was valid and has been applied */
    public boolean applyText(Row row, String text) {
        editCheckpoint(row.label);
        return row.apply.apply(text);
    }

    /** Identifies the set of rows, so a UI knows when to rebuild rather than refresh. */
    public String rowsSignature() {
        if (selection instanceof SceneElement element) {
            StringBuilder s = new StringBuilder(element.name()).append('#').append(System.identityHashCode(element));
            for (ElementProperty<?> property : element.properties()) {
                s.append('|').append(property.id());
            }
            return s.toString();
        }
        return selection instanceof ScenePart part ? "part:" + part.name() : "scene";
    }

    /** The editable values of the current selection (or of the scene, with nothing selected). */
    public List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        if (selection instanceof ScenePart part) {
            partRows(rows, part);
        } else if (selection instanceof SceneElement element) {
            elementRows(rows, element);
        } else {
            sceneRows(rows);
        }
        return rows;
    }

    static float[] euler(Quaternionf q) {
        Vector3f e = q.getEulerAnglesYXZ(new Vector3f());
        return new float[]{(float) Math.toDegrees(e.x), (float) Math.toDegrees(e.y), (float) Math.toDegrees(e.z)};
    }

    static Quaternionf quaternion(float[] degrees) {
        return new Quaternionf().rotationYXZ((float) Math.toRadians(degrees[1]), (float) Math.toRadians(degrees[0]), (float) Math.toRadians(degrees[2]));
    }

    private static void header(List<Row> rows, String text) {
        rows.add(new Row(Kind.HEADER, text));
    }

    private static void floatRow(List<Row> rows, String label, @Nullable String binding, float step, Supplier<Float> get, Consumer<Float> set) {
        Row row = new Row(Kind.FLOAT, label);
        row.binding = binding;
        row.step = step;
        row.get = () -> new float[]{get.get()};
        row.set = v -> set.accept(v[0]);
        rows.add(row);
    }

    private static void vectorRow(List<Row> rows, String label, @Nullable String binding, float step, Supplier<Vector3f> get, Consumer<Vector3f> set) {
        Row row = new Row(Kind.VEC3, label);
        row.binding = binding;
        row.step = step;
        row.get = () -> {
            Vector3f v = get.get();
            return new float[]{v.x, v.y, v.z};
        };
        row.set = v -> set.accept(new Vector3f(v[0], v[1], v[2]));
        rows.add(row);
    }

    private static void rotationRow(List<Row> rows, String label, @Nullable String binding, Supplier<Quaternionf> get, Consumer<Quaternionf> set) {
        Row row = new Row(Kind.EULER, label);
        row.binding = binding;
        row.step = 5;
        row.get = () -> euler(get.get());
        row.set = v -> set.accept(quaternion(v));
        rows.add(row);
    }

    private static void action(List<Row> rows, Supplier<String> text, Runnable action) {
        Row row = new Row(Kind.ACTION, "");
        row.text = text;
        row.action = action;
        rows.add(row);
    }

    private static void text(List<Row> rows, String label, Supplier<String> current, Function<String, Boolean> apply) {
        Row row = new Row(Kind.TEXT, label);
        row.text = current;
        row.apply = apply;
        rows.add(row);
    }

    private void sceneRows(List<Row> rows) {
        SceneCamera camera = scene.camera();
        header(rows, "Scene");
        floatRow(rows, "Cam yaw", "camera/yaw", 5, camera::yaw, camera::setYaw);
        floatRow(rows, "Cam pitch", "camera/pitch", 5, camera::pitch, camera::setPitch);
        floatRow(rows, "Zoom", "camera/ortho_height", 0.5f, camera::orthoHeight, camera::setOrthoHeight);
        floatRow(rows, "Distance", "camera/distance", 0.5f, camera::distance, camera::setDistance);
        floatRow(rows, "FOV", "camera/fov", 5, camera::fov, camera::setFov);
        vectorRow(rows, "Camera target", "camera/target", 0.25f, () -> new Vector3f(camera.target()), camera::setTarget);
        action(rows, () -> camera.orthographic() ? "Projection: orthographic" : "Projection: perspective", () -> camera.setOrthographic(!camera.orthographic()));
        action(rows, () -> "Frame whole scene (F)", scene::fitCamera);
        action(rows, () -> "Light: " + (scene.environment().lightMode() == SceneEnvironment.LightMode.COMPUTED ? "computed" : "full bright"),
                () -> scene.setEnvironment(scene.environment().withLightMode(scene.environment().lightMode() == SceneEnvironment.LightMode.COMPUTED
                        ? SceneEnvironment.LightMode.FULL_BRIGHT : SceneEnvironment.LightMode.COMPUTED)));
        floatRow(rows, "Sky light", null, 0.1f, () -> scene.environment().skyBrightness(), v -> scene.setEnvironment(scene.environment().withSkyBrightness(v)));
        floatRow(rows, "Gamma", null, 0.1f, () -> scene.environment().gamma(), v -> scene.setEnvironment(scene.environment().withGamma(v)));
        action(rows, () -> "Face shading: " + (scene.environment().shade() ? "on" : "off"), () -> scene.setEnvironment(scene.environment().withShade(!scene.environment().shade())));
        text(rows, "Background (AARRGGBB)", () -> String.format("%08X", scene.background()), value -> {
            try {
                scene.setBackground((int) Long.parseLong(value.trim(), 16));
                return value.trim().length() == 8;
            } catch (NumberFormatException e) {
                return false;
            }
        });
    }

    private void partRows(List<Row> rows, ScenePart part) {
        String prefix = "part/" + part.name() + "/";
        header(rows, "Part: " + part.name());
        vectorRow(rows, "Offset", prefix + "offset", 0.0625f, () -> new Vector3f(part.offset()), part::setOffset);
        rotationRow(rows, "Rotation", prefix + "rotation", () -> new Quaternionf(part.rotation()), part::setRotation);
        floatRow(rows, "Scale", prefix + "scale", 0.1f, part::scale, part::setScale);
        floatRow(rows, "Opacity", prefix + "alpha", 0.1f, part::alpha, part::setAlpha);
        vectorRow(rows, "Pivot", prefix + "pivot", 0.5f, () -> new Vector3f(part.pivot()), part::setPivot);
        action(rows, () -> "Pivot to centre of blocks", () -> {
            BoundingBox box = part.bounds();
            if (box != null) {
                part.setPivot((box.minX() + box.maxX() + 1) * 0.5f, box.minY(), (box.minZ() + box.maxZ() + 1) * 0.5f);
            }
        });
        action(rows, () -> part.visible() ? "Visible" : "Hidden", () -> part.setVisible(!part.visible()));
    }

    @SuppressWarnings("unchecked")
    private void elementRows(List<Row> rows, SceneElement element) {
        header(rows, element.name().isEmpty() ? "Element" : element.name());
        for (ElementProperty<?> property : element.properties()) {
            String binding = element.name().isEmpty() ? null : "element/" + element.name() + "/" + property.id();
            String label = property.id().startsWith("bone/") ? "Bone " + property.id().substring(5) : pretty(property.id());
            switch (property.type()) {
                case FLOAT -> {
                    ElementProperty<Float> p = (ElementProperty<Float>) property;
                    float step = switch (property.id()) {
                        case "scale", "limb_amount" -> 0.1f;
                        case "limb_swing" -> 0.5f;
                        default -> 5;
                    };
                    floatRow(rows, label, binding, step, p.getter(), p.setter());
                }
                case VEC3 -> {
                    ElementProperty<Vector3f> p = (ElementProperty<Vector3f>) property;
                    vectorRow(rows, label, binding, property.id().startsWith("bone/") ? 5 : 0.0625f, p.getter(), p.setter());
                }
                case QUAT -> {
                    ElementProperty<Quaternionf> p = (ElementProperty<Quaternionf>) property;
                    rotationRow(rows, label, binding, p.getter(), p.setter());
                }
            }
        }
        if (element instanceof EntityElement entity) {
            action(rows, () -> "Pose: " + entity.pose().name().toLowerCase(), () -> {
                Pose[] poses = Pose.values();
                entity.setPose(poses[(entity.pose().ordinal() + 1) % poses.length]);
            });
            if (entity.boneNames().isEmpty()) {
                header(rows, Minecraft.getInstance().level == null ? "Entities need a world" : "Bones appear once drawn");
            }
            text(rows, "Entity NBT", () -> entity.data().toString(), value -> {
                try {
                    CompoundTag tag = TagParser.parseTag(value);
                    if (!tag.contains("id")) {
                        return false;
                    }
                    entity.setData(tag);
                    return true;
                } catch (CommandSyntaxException e) {
                    return false;
                }
            });
        } else if (element instanceof BlockElement block) {
            text(rows, "Block state", () -> BlockStateParser.serialize(block.state()), value -> {
                BlockState state = parseBlock(value);
                if (state == null) {
                    return false;
                }
                block.setState(state);
                return true;
            });
        } else if (element instanceof ItemElement item) {
            text(rows, "Item id", () -> BuiltInRegistries.ITEM.getKey(item.stack().getItem()).toString(), value -> {
                ItemStack stack = parseItem(value);
                if (stack == null) {
                    return false;
                }
                item.setStack(stack);
                return true;
            });
            action(rows, () -> "Display: " + item.displayContext().getSerializedName(), () -> {
                ItemDisplayContext[] all = ItemDisplayContext.values();
                item.setDisplayContext(all[(item.displayContext().ordinal() + 1) % all.length]);
            });
        }
    }

    private static String pretty(String id) {
        String text = id.replace('_', ' ');
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // ------------------------------------------------------------------ unsaved changes

    /** Whether the scene changed since it was last saved, loaded or started. */
    public boolean dirty() {
        return dirty;
    }

    // ------------------------------------------------------------------ painting blocks

    /** Starts a brush stroke: everything painted until {@link #endStroke()} is one undo step. */
    public void beginStroke() {
        checkpoint();
        stroking = true;
    }

    /** Places or erases at one cell as part of the current stroke. */
    public void paint(BlockPos target) {
        if (!stroking) {
            beginStroke();
        }
        ScenePart part = targetPart();
        if (tool == Tool.ERASE) {
            part.removeBlock(target);
            return;
        }
        BlockState state = parseBlock(idText);
        if (state == null) {
            setError("'" + idText + "' is not a block");
            return;
        }
        if (!part.getBlockState(target).equals(state)) {
            part.setBlock(target, state);
        }
    }

    public void endStroke() {
        stroking = false;
    }

    /** Fills (or, with the Erase tool, clears) the box between two cells. One undo step. */
    public void fillBox(BlockPos a, BlockPos b) {
        BlockState state = tool == Tool.ERASE ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : parseBlock(idText);
        if (state == null) {
            setError("'" + idText + "' is not a block");
            return;
        }
        long volume = (Math.abs(a.getX() - b.getX()) + 1L) * (Math.abs(a.getY() - b.getY()) + 1L) * (Math.abs(a.getZ() - b.getZ()) + 1L);
        if (volume > 64 * 64 * 64) {
            setError("That box is " + volume + " blocks; the limit is " + 64 * 64 * 64);
            return;
        }
        checkpoint();
        targetPart().fill(a, b, state);
        setStatus((tool == Tool.ERASE ? "Cleared " : "Filled ") + volume + " blocks");
    }

    // ------------------------------------------------------------------ rename, duplicate, framing

    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[a-z0-9_\\-]+");

    /**
     * Renames a part or element, carrying its animation tracks along. Names may use a-z, 0-9, _ and -.
     *
     * @return whether it was renamed
     */
    public boolean rename(Object target, String newName) {
        String name = newName.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        if (!NAME.matcher(name).matches()) {
            setError("Names may use a-z, 0-9, _ and -");
            return false;
        }
        String old;
        String prefix;
        if (target instanceof ScenePart part) {
            old = part.name();
            if (part == scene.main()) {
                setError("The main part keeps its name");
                return false;
            }
            if (scene.findPart(name) != null && !name.equals(old)) {
                setError("There is already a part named '" + name + "'");
                return false;
            }
            prefix = "part/";
        } else if (target instanceof SceneElement element && !element.name().isEmpty()) {
            old = element.name();
            if (scene.findElement(name) != null && !name.equals(old)) {
                setError("There is already an element named '" + name + "'");
                return false;
            }
            prefix = "element/";
        } else {
            return false;
        }
        if (name.equals(old)) {
            return true;
        }
        CompoundTag tag = SceneIO.save(scene);
        ListTag parts = tag.getList("parts", Tag.TAG_COMPOUND);
        for (int i = 0; i < parts.size(); i++) {
            CompoundTag p = parts.getCompound(i);
            if (prefix.equals("part/") && p.getString("name").equals(old)) {
                p.putString("name", name);
            }
            ListTag elements = p.getList("elements", Tag.TAG_COMPOUND);
            for (int j = 0; j < elements.size(); j++) {
                CompoundTag e = elements.getCompound(j);
                if (prefix.equals("element/") && e.getString("name").equals(old)) {
                    e.putString("name", name);
                }
            }
        }
        ListTag tracks = tag.getCompound("timeline").getList("tracks", Tag.TAG_COMPOUND);
        for (int i = 0; i < tracks.size(); i++) {
            CompoundTag t = tracks.getCompound(i);
            String binding = t.getString("binding");
            if (binding.startsWith(prefix + old + "/")) {
                t.putString("binding", prefix + name + binding.substring(prefix.length() + old.length()));
            }
        }
        checkpoint();
        restore(tag);
        select(prefix.equals("part/") ? scene.findPart(name) : scene.findElement(name));
        setStatus("Renamed " + old + " to " + name);
        return true;
    }

    /** Copies the selected element or part next to the original and selects the copy. */
    public void duplicateSelection() {
        if (selection instanceof SceneElement element) {
            ScenePart part = scene.partOf(element);
            CompoundTag tag = element.save();
            if (part == null || tag == null) {
                setError("This element cannot be copied");
                return;
            }
            checkpoint();
            tag.remove("name");
            SceneElement copy = SceneIO.loadElement(tag);
            if (copy instanceof TransformedElement<?> moved) {
                moved.setPosition(new Vector3f(moved.position()).add(1, 0, 0));
            }
            if (copy != null) {
                part.add(copy);
                select(copy);
                setStatus("Duplicated as " + copy.name());
            }
        } else if (selection instanceof ScenePart part) {
            checkpoint();
            String name = part.name() + "_copy";
            for (int i = 2; scene.findPart(name) != null; i++) {
                name = part.name() + "_copy" + i;
            }
            ScenePart copy = scene.part(name);
            part.store().forEachBlock((x, y, z, state) -> copy.store().setBlock(x, y, z, state,
                    part.store().getBlockEntityData(new BlockPos(x, y, z))));
            BoundingBox box = part.bounds();
            float shift = box == null ? 1 : box.getXSpan() + 1;
            copy.setOffset(new Vector3f(part.offset()).add(shift, 0, 0)).setPivot(part.pivot()).setRotation(part.rotation())
                    .setScale(part.scale()).setAlpha(part.alpha());
            for (SceneElement element : part.elements()) {
                CompoundTag tag = element.save();
                if (tag != null) {
                    tag.remove("name");
                    SceneElement e = SceneIO.loadElement(tag);
                    if (e != null) {
                        copy.add(e);
                    }
                }
            }
            select(copy);
            setStatus("Duplicated as " + name);
        }
    }

    /** Points the camera at the selection (or the whole scene, with nothing selected) and zooms to fit it. */
    public void frameSelection() {
        if (selection instanceof ScenePart part && part.bounds() != null) {
            BoundingBox box = part.bounds();
            Vector3f center = part.matrix().transformPosition(new Vector3f(
                    (box.minX() + box.maxX() + 1) * 0.5f, (box.minY() + box.maxY() + 1) * 0.5f, (box.minZ() + box.maxZ() + 1) * 0.5f));
            float radius = 0.5f * part.scale() * (float) Math.sqrt((double) box.getXSpan() * box.getXSpan()
                    + (double) box.getYSpan() * box.getYSpan() + (double) box.getZSpan() * box.getZSpan());
            scene.camera().frame(center, Math.max(1, radius));
        } else if (selection instanceof TransformedElement<?> element && scene.partOf(element) != null) {
            Vector3f center = scene.partOf(element).matrix().transformPosition(element.pickCenter(new Vector3f()));
            scene.camera().frame(center, Math.max(1, element.pickRadius() * 2.5f));
        } else {
            scene.fitCamera();
        }
    }

    // ------------------------------------------------------------------ world projection

    /** The projection of this scene into the world, or {@code null} while it is not projected. */
    public @Nullable WorldProjection projection() {
        if (projection != null && !WorldProjections.all().contains(projection)) {
            projection = null; // removed elsewhere (/kraz project clear, leaving the world)
        }
        return projection;
    }

    /** Where the player stands: the middle of the bottom of the block their feet are in. */
    public static @Nullable Vec3 playerFeet() {
        var player = Minecraft.getInstance().player;
        return player == null ? null : Vec3.atBottomCenterOf(player.blockPosition());
    }

    /** The cell in front of the block face the crosshair points at; null when looking at nothing. */
    public static @Nullable Vec3 crosshair() {
        HitResult hit = Minecraft.getInstance().hitResult;
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            return Vec3.atBottomCenterOf(block.getBlockPos().relative(block.getDirection()));
        }
        return null;
    }

    /** Shows the scene in the world at a point (or moves it there if it is already shown). */
    public @Nullable WorldProjection projectAt(@Nullable Vec3 origin) {
        if (origin == null) {
            setError(Minecraft.getInstance().level == null ? "Projecting needs a loaded world" : "Look at a block to project there");
            return null;
        }
        WorldProjection current = projection();
        if (current == null) {
            projection = WorldProjections.add(new WorldProjection(scene, origin));
        } else {
            current.setOrigin(origin);
        }
        setStatus(String.format(java.util.Locale.ROOT, "Projected at %.1f, %.1f, %.1f", origin.x, origin.y, origin.z));
        return projection;
    }

    public void stopProjecting() {
        if (projection() != null) {
            WorldProjections.remove(projection);
            projection = null;
            setStatus("Projection removed");
        }
    }

    // ------------------------------------------------------------------ gizmo target

    /** Uniform access to "the thing with a position, rotation and scale" for parts and elements. */
    public interface Xform {
        Vector3f position();

        void setPosition(Vector3f value);

        Quaternionf rotation();

        void setRotation(Quaternionf value);

        float scale();

        void setScale(float value);

        /** Matrix from the space positions are expressed in to scene space. */
        Matrix4f parent();

        /** Where the gizmo sits, in scene space. */
        Vector3f origin();

        String binding(String property);
    }

    public @Nullable Xform xform() {
        if (selection instanceof ScenePart part) {
            return new Xform() {
                public Vector3f position() {
                    return new Vector3f(part.offset());
                }

                public void setPosition(Vector3f value) {
                    part.setOffset(value);
                }

                public Quaternionf rotation() {
                    return new Quaternionf(part.rotation());
                }

                public void setRotation(Quaternionf value) {
                    part.setRotation(value);
                }

                public float scale() {
                    return part.scale();
                }

                public void setScale(float value) {
                    part.setScale(value);
                }

                public Matrix4f parent() {
                    return new Matrix4f();
                }

                public Vector3f origin() {
                    return part.matrix().transformPosition(new Vector3f(part.pivot()));
                }

                public String binding(String property) {
                    return "part/" + part.name() + "/" + (property.equals("position") ? "offset" : property);
                }
            };
        }
        if (selection instanceof TransformedElement<?> element) {
            ScenePart part = scene.partOf(element);
            if (part == null) {
                return null;
            }
            return new Xform() {
                public Vector3f position() {
                    return new Vector3f(element.position());
                }

                public void setPosition(Vector3f value) {
                    element.setPosition(value);
                }

                public Quaternionf rotation() {
                    return new Quaternionf(element.rotation());
                }

                public void setRotation(Quaternionf value) {
                    element.setRotation(value);
                }

                public float scale() {
                    return element.scale();
                }

                public void setScale(float value) {
                    element.setScale(value);
                }

                public Matrix4f parent() {
                    return new Matrix4f(part.matrix());
                }

                public Vector3f origin() {
                    return part.matrix().transformPosition(element.pickCenter(new Vector3f()));
                }

                public String binding(String property) {
                    return "element/" + element.name() + "/" + property;
                }
            };
        }
        return null;
    }
}
