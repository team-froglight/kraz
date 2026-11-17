package me.methodial.kraz.editor;

import me.methodial.apothecary.event.EventType;
import me.methodial.apothecary.geom.Rect;
import me.methodial.apothecary.mc.render.McCanvas;
import me.methodial.apothecary.node.Node;
import me.methodial.apothecary.render.Canvas;
import me.methodial.apothecary.render.FontSpec;
import me.methodial.apothecary.style.Color;
import me.methodial.apothecary.style.TextDecoration;
import me.methodial.kraz.camera.SceneCamera;
import me.methodial.kraz.editor.EditorModel.Gizmo;
import me.methodial.kraz.editor.EditorModel.Tool;
import me.methodial.kraz.editor.EditorModel.Xform;
import me.methodial.kraz.render.RenderSettings;
import me.methodial.kraz.render.SceneCanvas;
import me.methodial.kraz.scene.Scene;
import me.methodial.kraz.scene.ScenePart;
import me.methodial.kraz.scene.element.TransformedElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * The editor's 3D view: draws the scene, the selection outline, the tool's target cell and the transform gizmo,
 * and turns mouse input into camera moves, selections, block edits and gizmo drags on the {@link EditorModel}.
 *
 * <p>Right-drag orbits, middle-drag pans, the wheel zooms; the left button does what the current tool does.
 * {@link #press}, {@link #dragTo} and {@link #release} take coordinates local to this node and are what the
 * mouse handlers call, so the view can be driven programmatically exactly as a user drives it.
 */
public final class EditorViewport extends Node {
    private static final Color[] AXIS_COLORS = {Color.hex("#FF5C5C"), Color.hex("#6BE06B"), Color.hex("#5C9DFF")};
    private static final Vector3f[] AXES = {new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1)};
    private static final Color SELECTION = Color.hex("#d9af4e"), WHITE = Color.hex("#FFFFFF"),
            HOVER = Color.ofArgb(0x80FFFFFF), GRID = Color.ofArgb(0x26FFFFFF);
    private static final float HANDLE = 46;

    private enum Drag {NONE, ORBIT, PAN, GIZMO, PAINT, BOX}

    private final EditorModel model;
    private final Scene scene;
    private final Viewport viewport;
    private final Runnable onFrame;
    private SceneCanvas canvas;

    private Drag drag = Drag.NONE;
    private boolean showGrid = true;
    private int gizmoAxis = -1;
    private float startX, startY, lastX, lastY;
    private float hoverX = Float.NaN, hoverY;
    private final Vector3f startPosition = new Vector3f();
    private final Quaternionf startRotation = new Quaternionf();
    private float startScale;
    private @Nullable BlockPos strokeStart, strokeLast;
    private net.minecraft.core.Direction.@Nullable Axis strokeAxis;
    /** The face the last tool target was built against, or null when it came from the ground plane. */
    private net.minecraft.core.Direction strokeFace;

    /** @param onFrame runs once per rendered frame, before the scene is drawn */
    public EditorViewport(EditorModel model, Runnable onFrame) {
        super("kraz-viewport");
        this.model = model;
        this.scene = model.scene();
        this.viewport = new Viewport(scene);
        this.onFrame = onFrame;
        on(EventType.MOUSE_DOWN, e -> {
            float[] p = toLocal(e.x(), e.y());
            press(p[0], p[1], e.button());
        });
        on(EventType.DRAG, e -> {
            float[] p = toLocal(e.x(), e.y());
            dragTo(p[0], p[1]);
        });
        on(EventType.DRAG_END, e -> release());
        on(EventType.MOUSE_UP, e -> release());
        on(EventType.MOUSE_MOVE, e -> {
            float[] p = toLocal(e.x(), e.y());
            hoverX = p[0];
            hoverY = p[1];
        });
        on(EventType.MOUSE_LEAVE, e -> hoverX = Float.NaN);
        on(EventType.SCROLL, e -> {
            scroll(e.deltaY());
            e.stopPropagation();
        });
    }

    /** The projection between this node's local coordinates and the scene, as of the last frame or input. */
    public Viewport viewport() {
        syncRect();
        return viewport;
    }

    private void syncRect() {
        Rect r = contentBox();
        viewport.x = r.x();
        viewport.y = r.y();
        viewport.width = Math.max(1, r.width());
        viewport.height = Math.max(1, r.height());
        viewport.update();
    }

    @Override
    public boolean isLeaf() {
        return true;
    }

    @Override
    protected float[] measureContent(float availWidth, float availHeight) {
        return new float[]{160, 120};
    }

    // ------------------------------------------------------------------ input

    public void press(float x, float y, int button) {
        syncRect();
        model.endEdit();
        startX = lastX = x;
        startY = lastY = y;
        if (button == 1) {
            drag = Drag.ORBIT;
        } else if (button == 2) {
            drag = Drag.PAN;
        } else if (button == 0) {
            if (model.tool != Tool.SELECT) {
                BlockPos target = toolTarget(x, y);
                if (target == null) {
                    return;
                }
                strokeStart = target;
                strokeLast = target;
                strokeAxis = strokeFace == null ? null : strokeFace.getAxis();
                if (Screen.hasShiftDown()) {
                    drag = Drag.BOX;
                } else {
                    drag = Drag.PAINT;
                    model.beginStroke();
                    model.paint(target);
                }
                return;
            }
            int axis = gizmoAxisAt(x, y);
            Xform xform = model.xform();
            if (axis >= 0 && xform != null) {
                model.checkpoint();
                drag = Drag.GIZMO;
                gizmoAxis = axis;
                startPosition.set(xform.position());
                startRotation.set(xform.rotation());
                startScale = xform.scale();
                return;
            }
            Viewport.Ray ray = viewport.ray(x, y);
            Viewport.ElementPick element = viewport.pickElement(ray);
            Viewport.BlockPick block = viewport.pickBlock(ray);
            if (element != null && (block == null || element.fraction() <= block.fraction())) {
                model.select(element.element());
            } else if (block != null) {
                model.select(block.part());
            } else {
                model.select(null);
            }
        }
    }

    /**
     * The cell under a point during a stroke: kept on the layer the stroke started on (the layer of cells in front
     * of the clicked face), so dragging paints a flat area instead of piling blocks up towards the camera.
     */
    private @Nullable BlockPos strokeCell(float x, float y) {
        if (model.tool == Tool.ERASE || strokeAxis == null || strokeStart == null) {
            return toolTarget(x, y);
        }
        ScenePart part = model.targetPart();
        Viewport.Ray ray = viewport.ray(x, y);
        org.joml.Matrix4f toPart = part.matrix().invert(new org.joml.Matrix4f());
        Vector3f from = toPart.transformPosition(new Vector3f(ray.from()));
        Vector3f to = toPart.transformPosition(new Vector3f(ray.to()));
        int axis = strokeAxis.ordinal();
        float plane = strokeStart.get(strokeAxis) + 0.5f;
        float a = from.get(axis), b = to.get(axis);
        if (Math.abs(b - a) < 1e-6f) {
            return null;
        }
        float t = (plane - a) / (b - a);
        if (t < 0 || t > 1) {
            return null;
        }
        Vector3f hit = from.lerp(to, t);
        return BlockPos.containing(hit.x, hit.y, hit.z);
    }

    public void dragTo(float x, float y) {
        float dx = x - lastX, dy = y - lastY;
        lastX = x;
        lastY = y;
        SceneCamera camera = scene.camera();
        switch (drag) {
            case ORBIT -> camera.orbit(-dx * 0.6f, dy * 0.6f);
            case PAN -> {
                float blocksPerUnit = (camera.orthographic() ? camera.orthoHeight()
                        : 2.0f * camera.distance() * (float) Math.tan(Math.toRadians(camera.fov()) * 0.5)) / Math.max(1f, viewport.height);
                camera.pan(-dx * blocksPerUnit, dy * blocksPerUnit);
            }
            case GIZMO -> dragGizmo(x - startX, y - startY);
            case PAINT -> {
                BlockPos cell = strokeCell(x, y);
                if (cell != null && !cell.equals(strokeLast)) {
                    strokeLast = cell;
                    model.paint(cell);
                }
            }
            case BOX -> {
                BlockPos cell = strokeCell(x, y);
                if (cell != null) {
                    strokeLast = cell;
                }
            }
            case NONE -> {
            }
        }
    }

    public void release() {
        if (drag == Drag.GIZMO) {
            Xform xform = model.xform();
            if (xform != null) {
                model.autoKey(xform.binding(model.gizmo == Gizmo.MOVE ? "position" : model.gizmo == Gizmo.ROTATE ? "rotation" : "scale"));
            }
        } else if (drag == Drag.PAINT) {
            model.endStroke();
        } else if (drag == Drag.BOX && strokeStart != null && strokeLast != null) {
            model.fillBox(strokeStart, strokeLast);
        }
        drag = Drag.NONE;
        gizmoAxis = -1;
        strokeStart = strokeLast = null;
    }

    public void scroll(float amount) {
        scene.camera().zoom((float) Math.pow(0.9, amount));
    }

    /** The cell a click with the current tool would affect, or null. */
    public @Nullable BlockPos toolTarget(float x, float y) {
        ScenePart part = model.targetPart();
        Viewport.Ray ray = viewport.ray(x, y);
        Viewport.BlockPick pick = viewport.pickBlock(ray, part);
        if (model.tool == Tool.ERASE) {
            strokeFace = null;
            return pick == null ? null : pick.pos();
        }
        if (pick != null) {
            strokeFace = pick.face();
            return pick.pos().relative(pick.face());
        }
        // Nothing to build against yet: use the part's ground plane.
        strokeFace = net.minecraft.core.Direction.UP;
        Vector3f ground = viewport.pickPlane(ray, part, 0);
        return ground == null ? null : BlockPos.containing(ground.x, 0, ground.z);
    }

    /** Puts the block under a point into the id box (the eyedropper). */
    public void pickBlockAt(float x, float y) {
        syncRect();
        Viewport.BlockPick pick = viewport.pickBlock(viewport.ray(x, y));
        if (pick != null) {
            model.idText = net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(pick.part().getBlockState(pick.pos()));
            model.setStatus("Picked " + model.idText);
        }
    }

    public boolean hovered() {
        return !Float.isNaN(hoverX);
    }

    public void pickBlockUnderMouse() {
        if (hovered()) {
            pickBlockAt(hoverX, hoverY);
        }
    }

    // ------------------------------------------------------------------ gizmo

    /** End point of a gizmo handle in scene space, sized to stay the same length on screen. */
    public @Nullable Vector3f handleTip(Vector3f origin, int axis) {
        Vector2f a = new Vector2f(), b = new Vector2f();
        if (!viewport.project(origin, a) || !viewport.project(new Vector3f(origin).add(AXES[axis]), b)) {
            return null;
        }
        float unitsPerBlock = a.distance(b);
        if (unitsPerBlock < 0.5f) {
            return null; // the axis points straight at the camera
        }
        return new Vector3f(AXES[axis]).mul(HANDLE / unitsPerBlock).add(origin);
    }

    private int gizmoAxisAt(float x, float y) {
        Xform xform = model.xform();
        if (xform == null) {
            return -1;
        }
        Vector3f origin = xform.origin();
        Vector2f a = new Vector2f(), b = new Vector2f();
        int best = -1;
        float bestDistance = 6.0f;
        for (int axis = 0; axis < 3; axis++) {
            Vector3f tip = handleTip(origin, axis);
            if (tip == null || !viewport.project(origin, a) || !viewport.project(tip, b)) {
                continue;
            }
            float dx = b.x - a.x, dy = b.y - a.y;
            float t = Math.clamp(((x - a.x) * dx + (y - a.y) * dy) / (dx * dx + dy * dy), 0.15f, 1.0f);
            float distance = (float) Math.hypot(x - (a.x + dx * t), y - (a.y + dy * t));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = axis;
            }
        }
        return best;
    }

    private void dragGizmo(float dx, float dy) {
        Xform xform = model.xform();
        if (xform == null || gizmoAxis < 0) {
            return;
        }
        Vector3f axis = AXES[gizmoAxis];
        switch (model.gizmo) {
            case MOVE -> {
                // How many blocks along the axis does this much movement along its on-screen direction mean?
                Vector3f origin = xform.origin();
                Vector2f a = new Vector2f(), b = new Vector2f();
                if (!viewport.project(origin, a) || !viewport.project(new Vector3f(origin).add(axis), b)) {
                    return;
                }
                float sx = b.x - a.x, sy = b.y - a.y;
                float lengthSquared = sx * sx + sy * sy;
                if (lengthSquared < 0.25f) {
                    return;
                }
                float blocks = (dx * sx + dy * sy) / lengthSquared;
                if (Screen.hasControlDown()) {
                    blocks = Math.round(blocks);
                } else if (Screen.hasShiftDown()) {
                    blocks = Math.round(blocks * 16) / 16f;
                }
                Vector3f delta = xform.parent().invert().transformDirection(new Vector3f(axis).mul(blocks));
                xform.setPosition(new Vector3f(startPosition).add(delta));
            }
            case ROTATE -> {
                float degrees = dx - dy;
                if (Screen.hasControlDown()) {
                    degrees = Math.round(degrees / 15f) * 15f;
                }
                Vector3f local = xform.parent().invert().transformDirection(new Vector3f(axis)).normalize();
                xform.setRotation(new Quaternionf().rotationAxis((float) Math.toRadians(degrees), local).mul(startRotation));
            }
            case SCALE -> xform.setScale(Math.max(0.01f, startScale * (1.0f + (dx - dy) * 0.01f)));
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void paintContent(Canvas c, Rect r) {
        onFrame.run();
        McCanvas mc = c.backend(McCanvas.class);
        if (mc == null || r.width() < 1 || r.height() < 1) {
            return;
        }
        syncRect();
        if (canvas == null) {
            canvas = new SceneCanvas(scene).setSettings(RenderSettings.DEFAULT);
        }
        Rect gui = mc.toGui(r);
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        mc.withPose(() -> canvas.draw(mc.graphics(), r.x(), r.y(), r.width(), r.height(),
                Math.max(1, (int) Math.round(gui.width() * guiScale)), Math.max(1, (int) Math.round(gui.height() * guiScale)), partial));

        c.pushClip(r);
        if (showGrid) {
            drawGrid(c);
        }
        Object selection = model.selection();
        // What a click would select, faintly, before the selection itself.
        if (hovered() && drag == Drag.NONE && model.tool == Tool.SELECT && gizmoAxisAt(hoverX, hoverY) < 0) {
            Viewport.Ray ray = viewport.ray(hoverX, hoverY);
            Viewport.ElementPick element = viewport.pickElement(ray);
            Viewport.BlockPick block = viewport.pickBlock(ray);
            if (element != null && (block == null || element.fraction() <= block.fraction())) {
                if (element.element() != selection && element.element() instanceof TransformedElement<?> e) {
                    Vector3f center = e.pickCenter(new Vector3f());
                    float radius = Math.max(0.2f, e.pickRadius());
                    box(c, Viewport.boxEdges(element.part().matrix(), center.x - radius, center.y - radius, center.z - radius,
                            center.x + radius, center.y + radius, center.z + radius), 1, HOVER);
                }
            } else if (block != null) {
                BlockPos p = block.pos();
                box(c, Viewport.boxEdges(block.part().matrix(), p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1), 1, HOVER);
            }
        }
        if (selection instanceof ScenePart part) {
            BoundingBox box = part.bounds();
            if (box != null) {
                box(c, Viewport.boxEdges(part.matrix(), box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1), 1, SELECTION);
            }
        } else if (selection instanceof TransformedElement<?> element) {
            ScenePart part = scene.partOf(element);
            if (part != null) {
                Vector3f center = element.pickCenter(new Vector3f());
                float radius = Math.max(0.2f, element.pickRadius());
                box(c, Viewport.boxEdges(part.matrix(), center.x - radius, center.y - radius, center.z - radius,
                        center.x + radius, center.y + radius, center.z + radius), 1, SELECTION);
            }
        }
        Color toolColor = model.tool == Tool.ERASE ? AXIS_COLORS[0] : AXIS_COLORS[1];
        if (drag == Drag.BOX && strokeStart != null && strokeLast != null) {
            int x0 = Math.min(strokeStart.getX(), strokeLast.getX()), y0 = Math.min(strokeStart.getY(), strokeLast.getY()), z0 = Math.min(strokeStart.getZ(), strokeLast.getZ());
            int x1 = Math.max(strokeStart.getX(), strokeLast.getX()) + 1, y1 = Math.max(strokeStart.getY(), strokeLast.getY()) + 1, z1 = Math.max(strokeStart.getZ(), strokeLast.getZ()) + 1;
            box(c, Viewport.boxEdges(model.targetPart().matrix(), x0, y0, z0, x1, y1, z1), 2, toolColor);
        } else if (hovered() && drag == Drag.NONE && model.tool != Tool.SELECT) {
            BlockPos target = toolTarget(hoverX, hoverY);
            if (target != null) {
                box(c, Viewport.boxEdges(model.targetPart().matrix(), target.getX(), target.getY(), target.getZ(),
                        target.getX() + 1, target.getY() + 1, target.getZ() + 1), 1.5f, toolColor);
            }
        }
        drawGizmo(c);
        c.popClip();
    }

    /** Whether the ground grid is drawn. */
    public EditorViewport setShowGrid(boolean on) {
        showGrid = on;
        return this;
    }

    public boolean showGrid() {
        return showGrid;
    }

    /** A faint block grid on the target part's ground (y = 0), around its blocks, with the X and Z axes in colour. */
    private void drawGrid(Canvas c) {
        ScenePart part = model.targetPart();
        BoundingBox box = part.bounds();
        int x0 = box == null ? -8 : box.minX() - 4, x1 = box == null ? 8 : box.maxX() + 5;
        int z0 = box == null ? -8 : box.minZ() - 4, z1 = box == null ? 8 : box.maxZ() + 5;
        // Keep it bounded on huge scenes.
        if ((long) (x1 - x0) * (z1 - z0) > 160 * 160) {
            return;
        }
        org.joml.Matrix4f m = part.matrix();
        Vector2f a = new Vector2f(), b = new Vector2f();
        for (int x = x0; x <= x1; x++) {
            if (viewport.project(m.transformPosition(new Vector3f(x, 0, z0)), a) && viewport.project(m.transformPosition(new Vector3f(x, 0, z1)), b)) {
                c.drawLine(a.x, a.y, b.x, b.y, x == 0 ? 1.5f : 0.5f, x == 0 ? AXIS_COLORS[2].withAlpha(0.7f) : GRID);
            }
        }
        for (int z = z0; z <= z1; z++) {
            if (viewport.project(m.transformPosition(new Vector3f(x0, 0, z)), a) && viewport.project(m.transformPosition(new Vector3f(x1, 0, z)), b)) {
                c.drawLine(a.x, a.y, b.x, b.y, z == 0 ? 1.5f : 0.5f, z == 0 ? AXIS_COLORS[0].withAlpha(0.7f) : GRID);
            }
        }
    }

    private void box(Canvas c, Vector3f[][] edges, float width, Color color) {
        Vector2f a = new Vector2f(), b = new Vector2f();
        for (Vector3f[] edge : edges) {
            if (viewport.project(edge[0], a) && viewport.project(edge[1], b)) {
                c.drawLine(a.x, a.y, b.x, b.y, width, color);
            }
        }
    }

    private void drawGizmo(Canvas c) {
        Xform xform = model.xform();
        if (xform == null || model.tool != Tool.SELECT) {
            return;
        }
        Vector3f origin = xform.origin();
        int hot = drag == Drag.GIZMO ? gizmoAxis : hovered() ? gizmoAxisAt(hoverX, hoverY) : -1;
        Vector2f a = new Vector2f(), b = new Vector2f();
        for (int axis = 0; axis < 3; axis++) {
            Vector3f tip = handleTip(origin, axis);
            if (tip == null || !viewport.project(origin, a) || !viewport.project(tip, b)) {
                continue;
            }
            Color color = axis == hot ? WHITE : AXIS_COLORS[axis];
            c.drawLine(a.x, a.y, b.x, b.y, axis == hot ? 3 : 2, color);
            if (model.gizmo == Gizmo.ROTATE) {
                c.drawArc(b.x, b.y, 4, 1.5f, 0, 360, color);
            } else {
                float half = model.gizmo == Gizmo.MOVE ? 3 : 2;
                c.fillRect(new Rect(b.x - half, b.y - half, half * 2, half * 2), color);
            }
        }
    }

    @Override
    protected void onDetach() {
        if (canvas != null) {
            canvas.close();
            canvas = null;
        }
    }
}
