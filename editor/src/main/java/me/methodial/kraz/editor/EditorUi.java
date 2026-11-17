package me.methodial.kraz.editor;

import me.methodial.apothecary.event.EventType;
import me.methodial.apothecary.node.Node;
import me.methodial.apothecary.widget.Button;
import me.methodial.apothecary.widget.TextField;
import me.methodial.apothecary.widget.Text;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static me.methodial.apothecary.ui.UI.*;

/** The editor's building blocks, all in the Ore UI look. */
final class EditorUi {
    private EditorUi() {
    }

    /** A square icon button on the kit's dark face. */
    static Button icon(String icon, String tooltip, Runnable action) {
        Button b = Button.pixel(icon, tooltip);
        b.cls("kz-icon");
        b.action(action);
        b.accessibleName(tooltip);
        return b;
    }

    /** A text button on the kit's dark face. */
    static Button label(String text, @Nullable String tooltip, Runnable action) {
        Button b = new Button(text);
        b.cls("kz-text");
        b.action(action);
        if (tooltip != null) {
            b.tooltip(tooltip);
        }
        return b;
    }

    static Node separator() {
        return box(cls("kz-sep"));
    }

    static Text heading(String text) {
        return (Text) text(text).cls("kz-heading");
    }

    static void setLabel(Button button, String text) {
        if (button.label() == null || !text.equals(button.label().text())) {
            button.text(text);
        }
    }

    static String format(float value) {
        if (value == 0) {
            return "0";
        }
        String text = String.format(Locale.ROOT, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
        return text.equals("-0") ? "0" : text;
    }

    /**
     * A text field bound to a number: it shows the live value while not being typed in, writes through as the
     * user types, and nudges by {@code step} on the mouse wheel (ten steps with Shift).
     *
     * @param beforeEdit runs before each change, e.g. to record an undo step
     * @param sync       receives the refresh to run each frame
     */
    static TextField number(Supplier<Float> get, Consumer<Float> set, float step, Runnable beforeEdit, List<Runnable> sync) {
        TextField field = new TextField(format(get.get()));
        field.cls("kz-num");
        field.onChange(text -> {
            try {
                float value = Float.parseFloat(text.trim().replace(',', '.'));
                if (Float.isFinite(value)) {
                    beforeEdit.run();
                    set.accept(value);
                }
            } catch (NumberFormatException ignored) {
                // Half-typed input such as "-" or "1."; wait for something parseable.
            }
        });
        field.on(EventType.SCROLL, e -> {
            beforeEdit.run();
            set.accept(get.get() + step * Math.signum(e.deltaY()) * (Screen.hasShiftDown() ? 10 : 1));
            field.setQuietly(format(get.get()));
            e.stopPropagation();
        });
        sync.add(() -> {
            if (!field.isFocused()) {
                String text = format(get.get());
                if (!text.equals(field.text())) {
                    field.setQuietly(text);
                }
            }
        });
        return field;
    }
}
