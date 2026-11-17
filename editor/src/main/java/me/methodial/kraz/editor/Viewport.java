package me.methodial.kraz.editor;

import me.methodial.kraz.scene.Scene;
import me.methodial.kraz.scene.ScenePart;
import me.methodial.kraz.scene.element.SceneElement;
import me.methodial.kraz.scene.element.TransformedElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The maths between the editor's 2D viewport rectangle and the 3D scene: projecting points for overlays, turning
 * the mouse into a ray, and finding what that ray hits.
 */
public final class Viewport {
    float x, y, width, height;
    private final Scene scene;
    private final Matrix4f viewProjection = new Matrix4f();
    private final Matrix4f inverse = new Matrix4f();

    Viewport(Scene scene) {
        this.scene = scene;
    }

    boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** Recomputes the camera matrices; call once per frame and before any picking. */
    void update() {
        float aspect = Math.max(1f, width) / Math.max(1f, height);
        scene.camera().projectionMatrix(aspect, viewProjection).mul(scene.camera().viewMatrix(new Matrix4f()));
        viewProjection.invert(inverse);
    }

    /**
     * Projects a scene-space point to GUI coordinates.
     *
     * @return false if the point is behind the camera
     */
    public boolean project(Vector3f scenePoint, Vector2f out) {
        Vector4f clip = viewProjection.transform(new Vector4f(scenePoint, 1.0f));
        if (clip.w <= 1e-5f) {
            return false;
        }
        out.set(x + (clip.x / clip.w * 0.5f + 0.5f) * width, y + (0.5f - clip.y / clip.w * 0.5f) * height);
        return true;
    }

    /** A ray through the scene under a GUI position, as its near and far end points in scene space. */
    record Ray(Vector3f from, Vector3f to) {
        Vector3f direction() {
            return new Vector3f(to).sub(from).normalize();
        }
    }

    Ray ray(double mouseX, double mouseY) {
        float ndcX = (float) ((mouseX - x) / width * 2.0 - 1.0);
        float ndcY = (float) (1.0 - (mouseY - y) / height * 2.0);
        return new Ray(inverse.transformProject(new Vector3f(ndcX, ndcY, -1)), inverse.transformProject(new Vector3f(ndcX, ndcY, 1)));
    }

    /**
     * @param fraction how far along the ray the hit is (0 = near plane, 1 = far plane); comparable between hits
     */
    record BlockPick(ScenePart part, BlockPos pos, Direction face, float fraction) {
    }

    /** Nearest block under the ray in one part, using the blocks' real outline shapes. */
    @Nullable BlockPick pickBlock(Ray ray, ScenePart part) {
        if (!part.visible() || part.store().isEmpty()) {
            return null;
        }
        Matrix4f toPart = part.matrix().invert(new Matrix4f());
        Vector3f from = toPart.transformPosition(ray.from, new Vector3f());
        Vector3f to = toPart.transformPosition(ray.to, new Vector3f());
        BlockHitResult hit = part.store().clip(new ClipContext(new Vec3(from.x, from.y, from.z), new Vec3(to.x, to.y, to.z),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        float length = from.distance(to);
        float fraction = length < 1e-6f ? 0 : (float) hit.getLocation().distanceTo(new Vec3(from.x, from.y, from.z)) / length;
        return new BlockPick(part, hit.getBlockPos().immutable(), hit.getDirection(), fraction);
    }

    @Nullable BlockPick pickBlock(Ray ray) {
        BlockPick best = null;
        for (ScenePart part : scene.parts()) {
            BlockPick pick = pickBlock(ray, part);
            if (pick != null && (best == null || pick.fraction < best.fraction)) {
                best = pick;
            }
        }
        return best;
    }

    record ElementPick(ScenePart part, SceneElement element, float fraction) {
    }

    /** Nearest element whose bounding sphere the ray passes through. */
    @Nullable ElementPick pickElement(Ray ray) {
        ElementPick best = null;
        Vector3f direction = ray.direction();
        float length = ray.from.distance(ray.to);
        Vector3f center = new Vector3f();
        for (ScenePart part : scene.parts()) {
            if (!part.visible()) {
                continue;
            }
            for (SceneElement element : part.elements()) {
                if (!(element instanceof TransformedElement<?> transformed)) {
                    continue;
                }
                part.matrix().transformPosition(transformed.pickCenter(center));
                float radius = Math.max(0.2f, transformed.pickRadius() * part.scale());
                Vector3f toCenter = new Vector3f(center).sub(ray.from);
                float along = toCenter.dot(direction);
                float distanceSquared = toCenter.lengthSquared() - along * along;
                if (along < 0 || distanceSquared > radius * radius) {
                    continue;
                }
                float fraction = (along - (float) Math.sqrt(radius * radius - distanceSquared)) / length;
                if (best == null || fraction < best.fraction) {
                    best = new ElementPick(part, element, fraction);
                }
            }
        }
        return best;
    }

    /** Where the ray crosses the horizontal plane {@code y = height} of a part, in part coordinates. */
    @Nullable Vector3f pickPlane(Ray ray, ScenePart part, float planeY) {
        Matrix4f toPart = part.matrix().invert(new Matrix4f());
        Vector3f from = toPart.transformPosition(ray.from, new Vector3f());
        Vector3f to = toPart.transformPosition(ray.to, new Vector3f());
        float dy = to.y - from.y;
        if (Math.abs(dy) < 1e-6f) {
            return null;
        }
        float t = (planeY - from.y) / dy;
        return t < 0 || t > 1 ? null : from.lerp(to, t);
    }

    /** The twelve edges of a box given in the space of {@code matrix}, as pairs of scene-space points. */
    static Vector3f[][] boxEdges(Matrix4f matrix, float x0, float y0, float z0, float x1, float y1, float z1) {
        Vector3f[] corners = new Vector3f[8];
        for (int i = 0; i < 8; i++) {
            corners[i] = matrix.transformPosition(new Vector3f((i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, (i & 4) == 0 ? z0 : z1));
        }
        int[][] pairs = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        Vector3f[][] edges = new Vector3f[12][];
        for (int i = 0; i < 12; i++) {
            edges[i] = new Vector3f[]{corners[pairs[i][0]], corners[pairs[i][1]]};
        }
        return edges;
    }
}
