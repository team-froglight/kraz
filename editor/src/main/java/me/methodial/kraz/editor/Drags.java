package me.methodial.kraz.editor;

import me.methodial.apothecary.event.EventType;
import me.methodial.apothecary.event.MouseEvent;
import me.methodial.apothecary.node.Node;

import java.util.function.Consumer;

/**
 * Drag handling that follows the pointer exactly.
 *
 * <p>Apothecary's {@code DRAG} events carry the total distance from where the button went down, not the step since
 * the last event. Things that are placed (a splitter, a window) are therefore set to <em>where they were when
 * the drag started plus that distance</em>, so they stay under the pointer and limits never make them drift. Things
 * that are nudged (orbiting, zooming) get the step since the last event, worked out from the totals.
 */
final class Drags {
    private Drags() {
    }

    /** What a placed thing needs: called once at the start of each drag, then with the total distance. */
    interface Placement {
        void start();

        /** {@code e.dragX()/dragY()} is the total distance; {@code e.x()/y()} where the pointer is. */
        void moved(MouseEvent e);
    }

    /** Calls {@code placement.start()} when a drag begins, then {@code moved} with the total distance. */
    static void place(Node node, Placement placement) {
        boolean[] started = {false};
        node.on(EventType.MOUSE_DOWN, e -> started[0] = false);
        node.on(EventType.DRAG_START, e -> {
            started[0] = true;
            placement.start();
        });
        node.on(EventType.DRAG, e -> {
            if (!started[0]) {
                // A DRAG without a DRAG_START (dispatched by hand): this event starts it.
                started[0] = true;
                placement.start();
            }
            placement.moved(e);
        });
        node.on(EventType.DRAG_END, e -> started[0] = false);
    }

    /** Calls {@code step} with the movement since the previous drag event, for things that are nudged. */
    static void step(Node node, Consumer<float[]> step) {
        float[] last = {0, 0};
        node.on(EventType.MOUSE_DOWN, e -> {
            last[0] = 0;
            last[1] = 0;
        });
        node.on(EventType.DRAG_START, e -> {
            last[0] = 0;
            last[1] = 0;
        });
        node.on(EventType.DRAG, (MouseEvent e) -> {
            float dx = e.dragX() - last[0], dy = e.dragY() - last[1];
            last[0] = e.dragX();
            last[1] = e.dragY();
            step.accept(new float[]{dx, dy});
        });
    }
}
