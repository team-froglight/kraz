package me.methodial.kraz.editor;

import me.methodial.apothecary.event.EventType;
import me.methodial.apothecary.geom.Rect;
import me.methodial.apothecary.node.Node;
import me.methodial.apothecary.render.Canvas;
import me.methodial.apothecary.render.FontSpec;
import me.methodial.apothecary.style.Color;
import me.methodial.apothecary.style.TextDecoration;
import me.methodial.kraz.anim.Timeline;
import me.methodial.kraz.anim.Track;
import me.methodial.kraz.scene.Scene;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The editor's dope sheet: one row per animated property with a marker per keyframe.
 *
 * <ul>
 *   <li>Click or drag on empty space or the ruler: scrub time.</li>
 *   <li>Click a key: select it. Drag it: move it in time (snapped to whole ticks).</li>
 *   <li>Right-click a key: delete it. Right-click a track's name: delete the whole track.</li>
 * </ul>
 *
 * {@link #press}, {@link #dragTo} and {@link #release} take coordinates local to this node; {@link #xOf} and
 * {@link #rowY} say where things are.
 */
public final class TimelineNode extends Node {
    private static final float RULER = 12, ROW = 11, LABELS = 120;
    private static final FontSpec SMALL = new FontSpec("minecraft", 9, me.methodial.apothecary.style.FontWeight.NORMAL, false, 0);
    private static final Color BACK = Color.ofArgb(0x00000000), RULER_BACK = Color.ofArgb(0x26000000), GRID = Color.hex("#FFFFFF").withAlpha(0.12f),
            STRIPE = Color.hex("#FFFFFF").withAlpha(0.05f), TEXT = Color.ofArgb(0xD9FFFFFF), FAINT = Color.ofArgb(0x80FFFFFF),
            ACCENT = Color.hex("#d9af4e"), KEY = Color.ofArgb(0xCCFFFFFF), PLAYHEAD = Color.hex("#52a535"), WHITE = Color.hex("#FFFFFF");

    private final EditorModel model;
    private final Scene scene;
    private int scroll;
    private boolean draggingKey, keyMoved, scrubbing;

    public TimelineNode(EditorModel model) {
        super("kraz-timeline");
        this.model = model;
        this.scene = model.scene();
        on(EventType.MOUSE_DOWN, e -> {
            float[] p = toLocal(e.x(), e.y());
            press(p[0], p[1], e.button());
        });
        on(EventType.DRAG, e -> dragTo(toLocal(e.x(), e.y())[0]));
        on(EventType.DRAG_END, e -> release());
        on(EventType.MOUSE_UP, e -> release());
        on(EventType.SCROLL, e -> {
            scroll -= (int) Math.signum(e.deltaY());
            e.stopPropagation();
        });
    }

    @Override
    public boolean isLeaf() {
        return true;
    }

    @Override
    protected float[] measureContent(float availWidth, float availHeight) {
        return new float[]{200, 70};
    }

    // ------------------------------------------------------------------ geometry

    private float length() {
        return Math.max(1, scene.timeline().length());
    }

    private float tracksLeft() {
        return contentBox().x() + LABELS;
    }

    private float tracksWidth() {
        return Math.max(10, contentBox().width() - LABELS - 8);
    }

    private float rowsTop() {
        return contentBox().y() + RULER;
    }

    private int visibleRows() {
        return Math.max(1, (int) ((contentBox().height() - RULER) / ROW));
    }

    /** Local x of a point in time. */
    public float xOf(float time) {
        return tracksLeft() + time / length() * tracksWidth();
    }

    /** Local y of the middle of the ruler. */
    public float rulerY() {
        return contentBox().y() + RULER * 0.5f;
    }

    /** Local y of the middle of a track's row, or NaN if it is scrolled out of view. */
    public float rowY(Track<?> track) {
        int index = tracks().indexOf(track) - scroll;
        return index < 0 || index >= visibleRows() ? Float.NaN : rowsTop() + index * ROW + ROW * 0.5f;
    }

    /** Local x inside the column of track names. */
    public float labelX() {
        return contentBox().x() + 10;
    }

    private float timeAt(float x) {
        return Math.clamp((x - tracksLeft()) / tracksWidth(), 0f, 1f) * length();
    }

    /** The tracks shown: those bound to scene properties. */
    public List<Track<?>> tracks() {
        List<Track<?>> list = new ArrayList<>();
        for (Track<?> track : scene.timeline().tracks()) {
            if (track.binding() != null) {
                list.add(track);
            }
        }
        return list;
    }

    private @Nullable Track<?> trackAt(float y) {
        if (y < rowsTop()) {
            return null;
        }
        List<Track<?>> tracks = tracks();
        int index = (int) ((y - rowsTop()) / ROW) + scroll;
        return index >= 0 && index < tracks.size() ? tracks.get(index) : null;
    }

    private int keyAt(Track<?> track, float x) {
        int best = -1;
        float bestDistance = 4.5f;
        for (int k = 0; k < track.keys().size(); k++) {
            float distance = Math.abs(xOf(track.keys().get(k).time()) - x);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = k;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ input

    public void press(float x, float y, int button) {
        Timeline timeline = scene.timeline();
        model.endEdit();
        Track<?> track = trackAt(y);
        if (x < tracksLeft()) {
            if (track != null && button == 1) {
                model.checkpoint();
                timeline.removeTrack(track);
                model.clearKeySelection();
                model.setStatus("Removed track " + EditorModel.shortName(track.binding()));
            }
            return;
        }
        int key = track == null ? -1 : keyAt(track, x);
        if (key >= 0) {
            if (button == 1) {
                model.checkpoint();
                track.removeKey(key);
                if (track.keys().isEmpty()) {
                    timeline.removeTrack(track);
                }
                model.clearKeySelection();
                timeline.seek(timeline.time());
            } else {
                model.selectedTrack = track;
                model.selectedKey = key;
                draggingKey = true;
                keyMoved = false;
            }
            return;
        }
        if (button == 0) {
            model.clearKeySelection();
            scrubbing = true;
            timeline.setPlaying(false);
            timeline.seek(Math.round(timeAt(x)));
        }
    }

    public void dragTo(float x) {
        Timeline timeline = scene.timeline();
        if (draggingKey && model.selectedKeyValue() != null) {
            float time = Math.round(timeAt(x));
            if (time != model.selectedTrack.keys().get(model.selectedKey).time()) {
                if (!keyMoved) {
                    keyMoved = true;
                    model.checkpoint();
                }
                model.selectedKey = model.selectedTrack.moveKey(model.selectedKey, time);
                timeline.seek(timeline.time());
            }
        } else if (scrubbing) {
            timeline.seek(Math.round(timeAt(x)));
        }
    }

    public void release() {
        draggingKey = false;
        scrubbing = false;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void paintContent(Canvas c, Rect r) {
        Timeline timeline = scene.timeline();
        c.fillRect(r, BACK);
        c.fillRect(new Rect(r.x(), r.y(), r.width(), RULER), RULER_BACK);
        c.pushClip(r);

        // Ruler: a tick per second, labelled where there is room.
        float seconds = length() / 20.0f;
        int labelEvery = Math.max(1, (int) Math.ceil(seconds * 26.0f / tracksWidth()));
        for (int s = 0; s <= (int) seconds; s++) {
            float x = xOf(s * 20.0f);
            c.fillRect(new Rect(x, r.y(), 0.5f, r.height()), GRID);
            if (s % labelEvery == 0) {
                c.drawText(s + "s", x + 2, r.y() + 2, SMALL, FAINT, false, TextDecoration.NONE);
            }
        }

        List<Track<?>> tracks = tracks();
        scroll = Math.clamp(scroll, 0, Math.max(0, tracks.size() - visibleRows()));
        String prefix = model.selectionBindingPrefix();
        for (int i = 0; i < visibleRows() && i + scroll < tracks.size(); i++) {
            Track<?> track = tracks.get(i + scroll);
            float y = rowsTop() + i * ROW;
            if ((i & 1) == 0) {
                c.fillRect(new Rect(r.x(), y, r.width(), ROW), STRIPE);
            }
            boolean ofSelection = prefix != null && track.binding().startsWith(prefix);
            c.pushClip(new Rect(r.x(), y, LABELS - 4, ROW));
            c.drawText(EditorModel.shortName(track.binding()), r.x() + 4, y + 2, SMALL, ofSelection ? ACCENT : TEXT, false, TextDecoration.NONE);
            c.popClip();
            for (int k = 0; k < track.keys().size(); k++) {
                float x = xOf(track.keys().get(k).time());
                boolean selected = track == model.selectedTrack && k == model.selectedKey;
                Color color = selected ? WHITE : ofSelection ? ACCENT : KEY;
                float size = selected ? 4 : 3;
                c.fillRoundRect(new Rect(x - size, y + ROW * 0.5f - size, size * 2, size * 2), size, me.methodial.apothecary.style.Paint.of(color));
            }
        }
        if (tracks.isEmpty()) {
            c.drawText("No animation yet: select something and press a K button to add a keyframe.", r.x() + 8, rowsTop() + 6, SMALL, FAINT, false, TextDecoration.NONE);
        }

        float playhead = xOf(timeline.time());
        c.fillRect(new Rect(playhead - 0.5f, r.y(), 1, r.height()), PLAYHEAD);
        c.fillRect(new Rect(playhead - 2.5f, r.y(), 5, 3), PLAYHEAD);
        c.fillRect(new Rect(r.x() + LABELS - 1, r.y(), 1, r.height()), RULER_BACK);
        c.popClip();
    }
}
