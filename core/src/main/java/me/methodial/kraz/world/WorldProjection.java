package me.methodial.kraz.world;

import me.methodial.kraz.render.SceneRenderer;
import me.methodial.kraz.scene.Scene;
import net.minecraft.Util;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * A scene shown inside the world: a hologram of a build, a guide standing next to the player, a schematic to
 * build from at 1:1. Add it to {@link WorldProjections} to show it.
 *
 * <p>The scene's {@link #anchor()} point (by default the middle of the bottom of its blocks) is placed at
 * {@link #origin()}; it is turned {@link #yaw()} degrees about the vertical, then scaled by {@link #scale()}.
 * The scene object is shared, not copied: edits to it show up in the world as they are made.
 */
public final class WorldProjection implements AutoCloseable {
    /** Which point of the scene sits on the origin. */
    public enum Anchor {
        /** The middle of the bottom face of the scene's blocks: stands it on the ground. */
        BOTTOM_CENTER,
        /** The middle of the scene's blocks. */
        CENTER,
        /** The scene's own (0, 0, 0): keeps block coordinates, for building from it at 1:1. */
        ORIGIN
    }

    private final Scene scene;
    private Vec3 origin;
    private float yaw;
    private float pitch;
    private float scale = 1;
    private float alpha = 1;
    private Anchor anchor = Anchor.BOTTOM_CENTER;
    private boolean animate = true;
    private boolean sceneLighting;
    private boolean showBounds = true;
    private boolean visible = true;

    private SceneRenderer renderer;
    private long lastFrame = -1;

    public WorldProjection(Scene scene, Vec3 origin) {
        this.scene = scene;
        this.origin = origin;
    }

    public Scene scene() {
        return scene;
    }

    public Vec3 origin() {
        return origin;
    }

    public WorldProjection setOrigin(Vec3 origin) {
        this.origin = origin;
        return this;
    }

    /** Degrees about the vertical axis. */
    public float yaw() {
        return yaw;
    }

    public WorldProjection setYaw(float degrees) {
        this.yaw = degrees;
        return this;
    }

    /** Degrees of tilt about the scene's left-right axis, applied after the yaw. */
    public float pitch() {
        return pitch;
    }

    public WorldProjection setPitch(float degrees) {
        this.pitch = degrees;
        return this;
    }

    /** Blocks of world per block of scene. */
    public float scale() {
        return scale;
    }

    public WorldProjection setScale(float scale) {
        this.scale = Math.max(1e-3f, scale);
        return this;
    }

    /** 1 is solid; below 1 the scene's blocks show as a see-through ghost. */
    public float alpha() {
        return alpha;
    }

    public WorldProjection setAlpha(float alpha) {
        this.alpha = Math.clamp(alpha, 0f, 1f);
        return this;
    }

    public Anchor anchor() {
        return anchor;
    }

    public WorldProjection setAnchor(Anchor anchor) {
        this.anchor = anchor;
        return this;
    }

    /** Whether the scene's timeline plays in real time while shown. */
    public boolean animate() {
        return animate;
    }

    public WorldProjection setAnimate(boolean animate) {
        this.animate = animate;
        return this;
    }

    /**
     * {@code false} (default): lit by the world, darker at night and in caves. {@code true}: keeps the scene's
     * own lighting, like a glowing hologram.
     */
    public boolean sceneLighting() {
        return sceneLighting;
    }

    public WorldProjection setSceneLighting(boolean sceneLighting) {
        this.sceneLighting = sceneLighting;
        return this;
    }

    /** Whether an outline of the scene's bounds is drawn around it. */
    public boolean showBounds() {
        return showBounds;
    }

    public WorldProjection setShowBounds(boolean showBounds) {
        this.showBounds = showBounds;
        return this;
    }

    public boolean visible() {
        return visible;
    }

    public WorldProjection setVisible(boolean visible) {
        this.visible = visible;
        return this;
    }

    /** The scene point placed on the origin, in scene coordinates. */
    public Vector3f anchorPoint() {
        BoundingBox box = scene.bounds();
        if (box == null || anchor == Anchor.ORIGIN) {
            return new Vector3f();
        }
        float cx = (box.minX() + box.maxX() + 1) * 0.5f, cz = (box.minZ() + box.maxZ() + 1) * 0.5f;
        float y = anchor == Anchor.CENTER ? (box.minY() + box.maxY() + 1) * 0.5f : box.minY();
        return new Vector3f(cx, y, cz);
    }

    /** Takes scene coordinates to world coordinates. */
    public Matrix4f sceneToWorld(Matrix4f dest) {
        Vector3f a = anchorPoint();
        return dest.translation((float) origin.x, (float) origin.y, (float) origin.z)
                .rotateY((float) Math.toRadians(-yaw))
                .rotateX((float) Math.toRadians(pitch))
                .scale(scale)
                .translate(-a.x, -a.y, -a.z);
    }

    /** The world-space box around the projected scene, or {@code null} if the scene has no blocks. */
    public AABB worldBounds() {
        BoundingBox box = scene.bounds();
        if (box == null) {
            return null;
        }
        Matrix4f m = sceneToWorld(new Matrix4f());
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            Vector3f c = m.transformPosition(new Vector3f(
                    (i & 1) == 0 ? box.minX() : box.maxX() + 1, (i & 2) == 0 ? box.minY() : box.maxY() + 1, (i & 4) == 0 ? box.minZ() : box.maxZ() + 1));
            x0 = Math.min(x0, c.x);
            y0 = Math.min(y0, c.y);
            z0 = Math.min(z0, c.z);
            x1 = Math.max(x1, c.x);
            y1 = Math.max(y1, c.y);
            z1 = Math.max(z1, c.z);
        }
        return new AABB(x0, y0, z0, x1, y1, z1);
    }

    /** The renderer drawing this projection; created on first use. Render thread only. */
    SceneRenderer renderer() {
        if (renderer == null) {
            renderer = new SceneRenderer(scene);
        }
        return renderer;
    }

    /** Advances the timeline by the real time since the last frame, if animating. */
    void tick() {
        long now = Util.getMillis();
        if (animate && lastFrame >= 0) {
            scene.timeline().advance(Math.min(now - lastFrame, 250) / 50.0f);
        }
        lastFrame = now;
    }

    @Override
    public void close() {
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
    }
}
