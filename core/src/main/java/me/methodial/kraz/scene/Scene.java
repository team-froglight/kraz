package me.methodial.kraz.scene;

import me.methodial.kraz.anim.Timeline;
import me.methodial.kraz.camera.SceneCamera;
import me.methodial.kraz.scene.element.SceneElement;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A self-contained miniature world: one or more {@link ScenePart}s, a {@link SceneCamera}, lighting settings and a
 * {@link Timeline} that animates all of them.
 *
 * <p>Scenes hold no GPU resources. Rendering state lives in whatever displays the scene (see
 * {@code me.methodial.kraz.render.SceneCanvas}), so a scene can be built on any thread and shown in several places.
 * Once it is being displayed, mutate it from the render thread only.
 */
public final class Scene {
    public static final String MAIN = "main";

    private final List<ScenePart> parts = new ArrayList<>();
    private final SceneCamera camera = new SceneCamera();
    private final Timeline timeline = new Timeline(this);
    private SceneEnvironment environment = SceneEnvironment.DEFAULT;
    private int background = 0x00000000;

    private Scene() {
        parts.add(new ScenePart(this, MAIN));
    }

    public static Scene create() {
        return new Scene();
    }

    /** The default part, which always exists. */
    public ScenePart main() {
        return parts.getFirst();
    }

    /** Returns the part with this name, creating it if needed. */
    public ScenePart part(String name) {
        ScenePart existing = findPart(name);
        if (existing != null) {
            return existing;
        }
        ScenePart part = new ScenePart(this, name);
        parts.add(part);
        return part;
    }

    /** Removes a part and everything in it. The main part cannot be removed. */
    public boolean removePart(ScenePart part) {
        return part != main() && parts.remove(part);
    }

    /** Finds an element by its name in any part. */
    public @Nullable SceneElement findElement(String name) {
        if (name.isEmpty()) {
            return null;
        }
        for (ScenePart part : parts) {
            for (SceneElement element : part.elements()) {
                if (name.equals(element.name())) {
                    return element;
                }
            }
        }
        return null;
    }

    /** The part an element belongs to, or {@code null}. */
    public @Nullable ScenePart partOf(SceneElement element) {
        for (ScenePart part : parts) {
            if (part.elements().contains(element)) {
                return part;
            }
        }
        return null;
    }

    String uniqueElementName(String wanted, SceneElement self) {
        SceneElement existing = findElement(wanted);
        if (existing == null || existing == self) {
            return wanted;
        }
        String base = wanted.replaceAll("_\\d+$", "");
        for (int i = 2; ; i++) {
            String candidate = base + "_" + i;
            if (findElement(candidate) == null) {
                return candidate;
            }
        }
    }

    /**
     * Empties the scene in place: all parts but the main one are removed, the main part and the timeline are
     * cleared. Whatever is displaying the scene keeps working, which is what makes undo by reloading possible.
     */
    public Scene reset() {
        while (parts.size() > 1) {
            parts.removeLast();
        }
        ScenePart main = main();
        main.clear();
        for (SceneElement element : List.copyOf(main.elements())) {
            main.remove(element);
        }
        main.setOffset(0, 0, 0).setPivot(0, 0, 0).setRotation(0, 0, 0).setScale(1).setAlpha(1).setVisible(true);
        setEnvironment(SceneEnvironment.DEFAULT);
        background = 0;
        timeline.clear();
        return this;
    }

    public @Nullable ScenePart findPart(String name) {
        for (ScenePart part : parts) {
            if (part.name().equals(name)) {
                return part;
            }
        }
        return null;
    }

    public List<ScenePart> parts() {
        return Collections.unmodifiableList(parts);
    }

    public SceneCamera camera() {
        return camera;
    }

    public Timeline timeline() {
        return timeline;
    }

    public SceneEnvironment environment() {
        return environment;
    }

    public Scene setEnvironment(SceneEnvironment environment) {
        this.environment = environment;
        for (ScenePart part : parts) {
            part.store().setEnvironment(environment);
        }
        return this;
    }

    /** Background as ARGB. Defaults to fully transparent. */
    public int background() {
        return background;
    }

    public Scene setBackground(int argb) {
        this.background = argb;
        return this;
    }

    /** Union of all parts' block bounds in part-local coordinates, or {@code null} if the scene has no blocks. */
    public @Nullable BoundingBox bounds() {
        BoundingBox result = null;
        for (ScenePart part : parts) {
            BoundingBox box = part.bounds();
            if (box != null) {
                result = result == null ? box : BoundingBox.encapsulatingBoxes(List.of(result, box)).orElse(result);
            }
        }
        return result;
    }

    /** Points the camera at the centre of the scene and zooms so everything fits. */
    public Scene fitCamera() {
        BoundingBox box = bounds();
        if (box != null) {
            Vector3f center = new Vector3f(
                    (box.minX() + box.maxX() + 1) * 0.5f,
                    (box.minY() + box.maxY() + 1) * 0.5f,
                    (box.minZ() + box.maxZ() + 1) * 0.5f);
            float radius = 0.5f * (float) Math.sqrt(
                    (double) box.getXSpan() * box.getXSpan() + (double) box.getYSpan() * box.getYSpan() + (double) box.getZSpan() * box.getZSpan());
            camera.frame(center, radius);
        }
        return this;
    }

    /** Changes whenever anything visible about the scene changes; used to skip redundant redraws. */
    public long version() {
        long v = environment.hashCode() * 31L + background;
        for (ScenePart part : parts) {
            v = v * 31 + part.version();
        }
        return v * 31 + camera.version();
    }
}
