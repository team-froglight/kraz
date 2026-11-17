package me.methodial.kraz.anim;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * A keyframed value. Sampling interpolates between the two surrounding keys using the easing of the later key and
 * hands the result to the track's target.
 *
 * @param <T> value type; see {@link Type} for what can be interpolated
 */
public final class Track<T> {
    /** Value kinds a track can interpolate, each with a flat float encoding used when saving. */
    public enum Type {
        FLOAT(1), VEC3(3), QUAT(4);

        final int components;

        Type(int components) {
            this.components = components;
        }
    }

    public record Key<T>(float time, T value, Easing easing) {
    }

    private final Type type;
    private final Consumer<T> target;
    private final @Nullable String binding;
    private final List<Key<T>> keys = new ArrayList<>();

    Track(Type type, Consumer<T> target, @Nullable String binding) {
        this.type = type;
        this.target = target;
        this.binding = binding;
    }

    public Type type() {
        return type;
    }

    /** Name of the scene property this track drives (e.g. {@code camera/yaw}), or {@code null} for custom targets. */
    public @Nullable String binding() {
        return binding;
    }

    public List<Key<T>> keys() {
        return Collections.unmodifiableList(keys);
    }

    /** Adds a key reached with linear interpolation. */
    public Track<T> key(float time, T value) {
        return key(time, value, Easing.LINEAR);
    }

    /**
     * @param time   in ticks
     * @param easing curve used while approaching this key from the previous one
     */
    public Track<T> key(float time, T value, Easing easing) {
        Key<T> key = new Key<>(time, copy(value), easing);
        int i = keys.size();
        while (i > 0 && keys.get(i - 1).time > time) {
            i--;
        }
        keys.add(i, key);
        return this;
    }

    /** Adds a key, replacing any existing key at exactly this time. */
    public Track<T> setKey(float time, T value, Easing easing) {
        keys.removeIf(k -> k.time == time);
        return key(time, value, easing);
    }

    /** Index of the key at exactly this time, or -1. */
    public int keyAt(float time) {
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).time == time) {
                return i;
            }
        }
        return -1;
    }

    public void removeKey(int index) {
        keys.remove(index);
    }

    /** Moves a key in time, keeping its value and easing. Returns its new index. */
    public int moveKey(int index, float time) {
        Key<T> key = keys.remove(index);
        keys.removeIf(k -> k.time == time);
        key(time, key.value, key.easing);
        return keyAt(time);
    }

    public void setEasing(int index, Easing easing) {
        Key<T> key = keys.get(index);
        keys.set(index, new Key<>(key.time, key.value, easing));
    }

    /** Time of the last key, in ticks. */
    public float duration() {
        return keys.isEmpty() ? 0 : keys.getLast().time;
    }

    public @Nullable T sample(float time) {
        int n = keys.size();
        if (n == 0) {
            return null;
        }
        Key<T> first = keys.getFirst();
        if (time <= first.time || n == 1) {
            return first.value;
        }
        Key<T> last = keys.get(n - 1);
        if (time >= last.time) {
            return last.value;
        }
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (keys.get(mid).time <= time) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        Key<T> a = keys.get(lo), b = keys.get(hi);
        float span = b.time - a.time;
        float t = span <= 0 ? 1 : b.easing.apply((time - a.time) / span);
        return lerp(a.value, b.value, t);
    }

    void apply(float time) {
        T value = sample(time);
        if (value != null) {
            target.accept(value);
        }
    }

    @SuppressWarnings("unchecked")
    private T lerp(T a, T b, float t) {
        return switch (type) {
            case FLOAT -> (T) (Float) ((Float) a + ((Float) b - (Float) a) * t);
            case VEC3 -> (T) ((Vector3f) a).lerp((Vector3f) b, t, new Vector3f());
            case QUAT -> (T) ((Quaternionf) a).slerp((Quaternionf) b, t, new Quaternionf());
        };
    }

    @SuppressWarnings("unchecked")
    private T copy(T value) {
        return switch (type) {
            case FLOAT -> value;
            case VEC3 -> (T) new Vector3f((Vector3f) value);
            case QUAT -> (T) new Quaternionf((Quaternionf) value);
        };
    }

    float[] encode(T value) {
        return switch (type) {
            case FLOAT -> new float[]{(Float) value};
            case VEC3 -> {
                Vector3f v = (Vector3f) value;
                yield new float[]{v.x, v.y, v.z};
            }
            case QUAT -> {
                Quaternionf q = (Quaternionf) value;
                yield new float[]{q.x, q.y, q.z, q.w};
            }
        };
    }

    @SuppressWarnings("unchecked")
    T decode(float[] data, int offset) {
        return switch (type) {
            case FLOAT -> (T) (Float) data[offset];
            case VEC3 -> (T) new Vector3f(data[offset], data[offset + 1], data[offset + 2]);
            case QUAT -> (T) new Quaternionf(data[offset], data[offset + 1], data[offset + 2], data[offset + 3]);
        };
    }
}
