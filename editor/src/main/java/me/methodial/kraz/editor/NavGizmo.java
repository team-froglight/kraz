package me.methodial.kraz.editor;

import me.methodial.apothecary.event.EventType;
import me.methodial.apothecary.geom.Rect;
import me.methodial.apothecary.node.Node;
import me.methodial.apothecary.render.Canvas;
import me.methodial.apothecary.render.FontSpec;
import me.methodial.apothecary.style.Color;
import me.methodial.apothecary.style.FontWeight;
import me.methodial.apothecary.style.Paint;
import me.methodial.apothecary.style.TextDecoration;
import me.methodial.kraz.camera.SceneCamera;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Blender's navigation gizmo: the six axis directions as seen from the camera. Click an axis to look along it,
 * drag anywhere on it to orbit. Positive axes are solid and lettered, negative ones are rings.
 */
final class NavGizmo extends Node {
    private static final Color[] COLORS = {Color.hex("#ff3352"), Color.hex("#8bdc00"), Color.hex("#2890ff")};
    private static final String[] LETTERS = {"X", "Y", "Z"};
    private static final FontSpec FONT = new FontSpec("minecraft", 9, FontWeight.NORMAL, false, 0);
    private static final float BALL = 6;

    private final SceneCamera camera;
    private int hovered = -1;
    private boolean dragged;

    NavGizmo(SceneCamera camera) {
        super("kz-nav");
        this.camera = camera;
        cls("kz-nav");
        on(EventType.MOUSE_MOVE, e -> {
            float[] p = toLocal(e.x(), e.y());
            hovered = ballAt(p[0], p[1]);
        });
        on(EventType.MOUSE_LEAVE, e -> hovered = -1);
        on(EventType.MOUSE_DOWN, e -> dragged = false);
        Drags.step(this, d -> {
            dragged = true;
            camera.orbit(-d[0] * 0.8f, d[1] * 0.8f);
        });
        onClick(e -> {
            if (dragged) {
                return;
            }
            float[] p = toLocal(e.x(), e.y());
            int ball = ballAt(p[0], p[1]);
            if (ball >= 0) {
                lookAlong(ball);
            }
        });
        tooltip("Click an axis to look along it · drag to orbit");
    }

    /** Looks from the given axis end towards the target: 0 +X, 1 +Y, 2 +Z, 3 −X, 4 −Y, 5 −Z. */
    void lookAlong(int ball) {
        switch (ball) {
            case 0 -> camera.setYaw(90).setPitch(0);
            case 3 -> camera.setYaw(-90).setPitch(0);
            case 1 -> camera.setPitch(89.9f);
            case 4 -> camera.setPitch(-89.9f);
            case 2 -> camera.setYaw(0).setPitch(0);
            case 5 -> camera.setYaw(180).setPitch(0);
            default -> {
            }
        }
    }

    @Override
    public boolean isLeaf() {
        return true;
    }

    @Override
    protected float[] measureContent(float availWidth, float availHeight) {
        return new float[]{64, 64};
    }

    private record Ball(int index, float x, float y, float depth) {
    }

    private List<Ball> balls() {
        Rect r = contentBox();
        float cx = r.x() + r.width() / 2, cy = r.y() + r.height() / 2;
        float radius = Math.min(r.width(), r.height()) / 2 - BALL - 1;
        Matrix4f view = camera.viewMatrix(new Matrix4f());
        List<Ball> list = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Vector3f dir = new Vector3f(i % 3 == 0 ? 1 : 0, i % 3 == 1 ? 1 : 0, i % 3 == 2 ? 1 : 0).mul(i < 3 ? 1 : -1);
            Vector3f v = view.transformDirection(dir);
            list.add(new Ball(i, cx + v.x * radius, cy - v.y * radius, v.z));
        }
        // Far ones first, so near ones are drawn over them.
        list.sort(Comparator.comparingDouble(Ball::depth));
        return list;
    }

    private int ballAt(float x, float y) {
        List<Ball> balls = balls();
        for (int i = balls.size() - 1; i >= 0; i--) {
            Ball b = balls.get(i);
            if (Math.hypot(x - b.x, y - b.y) <= BALL + 1.5f) {
                return b.index;
            }
        }
        return -1;
    }

    @Override
    protected void paintContent(Canvas c, Rect r) {
        float cx = r.x() + r.width() / 2, cy = r.y() + r.height() / 2;
        if (hasState("hover")) {
            c.fillCircle(cx, cy, Math.min(r.width(), r.height()) / 2, Paint.of(Color.ofArgb(0x33FFFFFF)));
        }
        for (Ball b : balls()) {
            int axis = b.index % 3;
            boolean positive = b.index < 3;
            Color color = COLORS[axis];
            if (positive) {
                c.drawLine(cx, cy, b.x, b.y, 2, color);
            }
            boolean hot = b.index == hovered;
            if (positive) {
                c.fillCircle(b.x, b.y, BALL, Paint.of(hot ? Color.hex("#ffffff") : color));
                float tw = c.text().width(LETTERS[axis], FONT);
                c.drawText(LETTERS[axis], b.x - tw / 2, b.y - 4, FONT, Color.hex("#101010"), false, TextDecoration.NONE);
            } else {
                c.fillCircle(b.x, b.y, BALL - 1, Paint.of(hot ? Color.ofArgb(0xCCFFFFFF) : color.withAlpha(0.35f)));
                c.drawArc(b.x, b.y, BALL - 1, 1.2f, 0, 360, color);
            }
        }
    }
}
