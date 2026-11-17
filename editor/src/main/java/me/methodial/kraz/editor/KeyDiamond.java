package me.methodial.kraz.editor;

import me.methodial.apothecary.geom.Affine;
import me.methodial.apothecary.geom.Rect;
import me.methodial.apothecary.node.Node;
import me.methodial.apothecary.render.Canvas;
import me.methodial.apothecary.style.Color;

import java.util.function.IntSupplier;

/**
 * The keyframe marker beside an animatable value, as animation tools draw it: a hollow diamond when the value is
 * not animated, a small filled one when it is animated, a large gold one when there is a key at the current time.
 */
final class KeyDiamond extends Node {
    private static final Color OFF = Color.ofArgb(0x73FFFFFF), ANIMATED = Color.ofArgb(0xCCFFFFFF), KEYED = Color.hex("#d9af4e");

    private final IntSupplier state;

    /** @param state 0 not animated, 1 animated, 2 keyed at the current time */
    KeyDiamond(IntSupplier state) {
        super("kz-key");
        this.state = state;
        cls("kz-key");
        focusable(true);
    }

    @Override
    public boolean isLeaf() {
        return true;
    }

    @Override
    protected float[] measureContent(float availWidth, float availHeight) {
        return new float[]{12, 12};
    }

    @Override
    protected void paintContent(Canvas c, Rect r) {
        int s = state.getAsInt();
        float cx = r.x() + r.width() / 2, cy = r.y() + r.height() / 2;
        float half = s == 2 ? 3.6f : 3f;
        boolean hover = hasState("hover");
        c.save();
        c.transform(Affine.translation(cx, cy).multiply(Affine.rotation((float) Math.toRadians(45))));
        Color color = s == 2 ? KEYED : s == 1 ? ANIMATED : OFF;
        if (hover) {
            color = s == 2 ? Color.hex("#f0c868") : Color.ofArgb(0xFFFFFFFF);
        }
        if (s == 0) {
            c.strokeRoundRect(new Rect(-half, -half, half * 2, half * 2), 0, 1f, color);
        } else {
            c.fillRect(new Rect(-half, -half, half * 2, half * 2), color);
        }
        c.restore();
    }
}
