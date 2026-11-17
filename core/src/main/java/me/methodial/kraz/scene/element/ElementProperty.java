package me.methodial.kraz.scene.element;

import me.methodial.kraz.anim.Track;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A named, animatable value of a scene element, such as its position or one bone's rotation. Properties are how
 * the timeline (binding {@code element/<element name>/<property id>}) and the editor address elements generically.
 *
 * @param id     unique within the element, e.g. {@code position} or {@code bone/head}
 * @param type   value kind: Float, {@code Vector3f} or {@code Quaternionf}
 * @param getter current value (a copy, for vectors and quaternions)
 */
public record ElementProperty<T>(String id, Track.Type type, Supplier<T> getter, Consumer<T> setter) {
}
