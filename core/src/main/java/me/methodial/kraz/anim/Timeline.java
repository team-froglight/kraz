package me.methodial.kraz.anim;

import me.methodial.kraz.camera.SceneCamera;
import me.methodial.kraz.scene.Scene;
import me.methodial.kraz.scene.ScenePart;
import me.methodial.kraz.scene.element.ElementProperty;
import me.methodial.kraz.scene.element.SceneElement;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Drives a scene over time. Time is measured in ticks (20 per second).
 *
 * <p>Two kinds of things live on a timeline:
 * <ul>
 *   <li>{@link Track}s: keyframed values such as the camera yaw or a part's offset. Tracks bound to scene
 *       properties through the helper methods here are saved with the scene.</li>
 *   <li>Events: arbitrary code run when playback crosses a point in time, optionally with an undo action so
 *       scrubbing backwards restores the previous state. Events are code and are not saved.</li>
 * </ul>
 */
public final class Timeline {
    private record Event(float time, Runnable action, @Nullable Runnable undo) {
    }

    private final Scene scene;
    private final List<Track<?>> tracks = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();
    private int eventCursor;
    private float time;
    private float length = -1;
    private float speed = 1.0f;
    private boolean loop = true;
    private boolean playing = true;

    public Timeline(Scene scene) {
        this.scene = scene;
    }

    // ------------------------------------------------------------------ tracks

    public Track<Float> floatTrack(Consumer<Float> target) {
        return add(new Track<>(Track.Type.FLOAT, target, null));
    }

    public Track<Vector3f> vec3Track(Consumer<Vector3f> target) {
        return add(new Track<>(Track.Type.VEC3, target, null));
    }

    public Track<Quaternionf> quatTrack(Consumer<Quaternionf> target) {
        return add(new Track<>(Track.Type.QUAT, target, null));
    }

    public Track<Float> cameraYaw() {
        return bound("camera/yaw");
    }

    public Track<Float> cameraPitch() {
        return bound("camera/pitch");
    }

    public Track<Float> cameraDistance() {
        return bound("camera/distance");
    }

    public Track<Float> cameraFov() {
        return bound("camera/fov");
    }

    public Track<Float> cameraOrthoHeight() {
        return bound("camera/ortho_height");
    }

    public Track<Vector3f> cameraTarget() {
        return bound("camera/target");
    }

    public Track<Vector3f> offset(ScenePart part) {
        return bound("part/" + part.name() + "/offset");
    }

    public Track<Vector3f> pivot(ScenePart part) {
        return bound("part/" + part.name() + "/pivot");
    }

    public Track<Quaternionf> rotation(ScenePart part) {
        return bound("part/" + part.name() + "/rotation");
    }

    public Track<Float> scale(ScenePart part) {
        return bound("part/" + part.name() + "/scale");
    }

    public Track<Float> alpha(ScenePart part) {
        return bound("part/" + part.name() + "/alpha");
    }

    /**
     * Returns the track for a scene property, creating it on first use. Known bindings are {@code camera/yaw},
     * {@code camera/pitch}, {@code camera/distance}, {@code camera/fov}, {@code camera/ortho_height},
     * {@code camera/target}, {@code part/<name>/offset|pivot|rotation|scale|alpha} and
     * {@code element/<element name>/<property id>}.
     */
    @SuppressWarnings("unchecked")
    public <T> Track<T> bound(String binding) {
        for (Track<?> track : tracks) {
            if (binding.equals(track.binding())) {
                return (Track<T>) track;
            }
        }
        Track<?> track = createBound(binding);
        if (track == null) {
            throw new IllegalArgumentException("Unknown track binding: " + binding);
        }
        return (Track<T>) add(track);
    }

    private @Nullable Track<?> createBound(String binding) {
        SceneCamera camera = scene.camera();
        if (binding.startsWith("camera/")) {
            return switch (binding.substring(7)) {
                case "yaw" -> new Track<Float>(Track.Type.FLOAT, camera::setYaw, binding);
                case "pitch" -> new Track<Float>(Track.Type.FLOAT, camera::setPitch, binding);
                case "distance" -> new Track<Float>(Track.Type.FLOAT, camera::setDistance, binding);
                case "fov" -> new Track<Float>(Track.Type.FLOAT, camera::setFov, binding);
                case "ortho_height" -> new Track<Float>(Track.Type.FLOAT, camera::setOrthoHeight, binding);
                case "target" -> new Track<Vector3f>(Track.Type.VEC3, camera::setTarget, binding);
                default -> null;
            };
        }
        if (binding.startsWith("part/")) {
            int slash = binding.lastIndexOf('/');
            if (slash <= 5) {
                return null;
            }
            ScenePart part = scene.part(binding.substring(5, slash));
            return switch (binding.substring(slash + 1)) {
                case "offset" -> new Track<Vector3f>(Track.Type.VEC3, part::setOffset, binding);
                case "pivot" -> new Track<Vector3f>(Track.Type.VEC3, part::setPivot, binding);
                case "rotation" -> new Track<Quaternionf>(Track.Type.QUAT, part::setRotation, binding);
                case "scale" -> new Track<Float>(Track.Type.FLOAT, part::setScale, binding);
                case "alpha" -> new Track<Float>(Track.Type.FLOAT, part::setAlpha, binding);
                default -> null;
            };
        }
        if (binding.startsWith("element/")) {
            int slash = binding.indexOf('/', 8);
            if (slash < 0) {
                return null;
            }
            SceneElement element = scene.findElement(binding.substring(8, slash));
            ElementProperty<?> property = element == null ? null : element.property(binding.substring(slash + 1));
            return property == null ? null : elementTrack(property, binding);
        }
        return null;
    }

    private static <T> Track<T> elementTrack(ElementProperty<T> property, String binding) {
        return new Track<>(property.type(), property.setter(), binding);
    }

    /** Track for a property of a named element, e.g. {@code property(cow, "bone/head")}. */
    public <T> Track<T> property(SceneElement element, String propertyId) {
        if (element.name().isEmpty()) {
            throw new IllegalArgumentException("Only named elements that were added to a part can be animated");
        }
        return bound("element/" + element.name() + "/" + propertyId);
    }

    /** The existing track for a binding, or {@code null}; never creates one. */
    public @Nullable Track<?> find(String binding) {
        for (Track<?> track : tracks) {
            if (binding.equals(track.binding())) {
                return track;
            }
        }
        return null;
    }

    public void removeTrack(Track<?> track) {
        tracks.remove(track);
    }

    /** Removes every track and event and rewinds to time 0. */
    public void clear() {
        tracks.clear();
        events.clear();
        eventCursor = 0;
        time = 0;
        length = -1;
    }

    private <T> Track<T> add(Track<T> track) {
        tracks.add(track);
        return track;
    }

    public List<Track<?>> tracks() {
        return Collections.unmodifiableList(tracks);
    }

    // ------------------------------------------------------------------ events

    /** Runs {@code action} when playback reaches {@code time}. */
    public Timeline event(float time, Runnable action) {
        return event(time, action, null);
    }

    /** As {@link #event(float, Runnable)}, with {@code undo} run when playback loops or is scrubbed back past it. */
    public Timeline event(float time, Runnable action, @Nullable Runnable undo) {
        int i = events.size();
        while (i > 0 && events.get(i - 1).time > time) {
            i--;
        }
        events.add(i, new Event(time, action, undo));
        if (i < eventCursor) {
            eventCursor++;
        }
        return this;
    }

    // ------------------------------------------------------------------ playback

    public float time() {
        return time;
    }

    /** Total length in ticks: the explicit length if set, otherwise the time of the last key or event. */
    public float length() {
        if (length >= 0) {
            return length;
        }
        float end = 0;
        for (Track<?> track : tracks) {
            end = Math.max(end, track.duration());
        }
        if (!events.isEmpty()) {
            end = Math.max(end, events.getLast().time);
        }
        return end;
    }

    public Timeline setLength(float ticks) {
        length = ticks;
        return this;
    }

    public boolean loop() {
        return loop;
    }

    public Timeline setLoop(boolean value) {
        loop = value;
        return this;
    }

    public float speed() {
        return speed;
    }

    public Timeline setSpeed(float value) {
        speed = value;
        return this;
    }

    public boolean playing() {
        return playing;
    }

    public Timeline setPlaying(boolean value) {
        playing = value;
        return this;
    }

    public boolean isEmpty() {
        return tracks.isEmpty() && events.isEmpty();
    }

    /** Advances playback by {@code deltaTicks} (scaled by {@link #speed()}) if playing. */
    public void advance(float deltaTicks) {
        if (!playing || isEmpty()) {
            return;
        }
        float end = length();
        float next = time + deltaTicks * speed;
        if (end <= 0) {
            seek(0);
        } else if (next < end) {
            seek(next);
        } else if (loop) {
            seek(end);
            seek(next % end);
        } else {
            seek(end);
            playing = false;
        }
    }

    /** Jumps to a point in time, applying every track and running or undoing the events in between. */
    public void seek(float ticks) {
        time = Math.max(0, ticks);
        while (eventCursor > 0 && events.get(eventCursor - 1).time > time) {
            Event event = events.get(--eventCursor);
            if (event.undo != null) {
                event.undo.run();
            }
        }
        while (eventCursor < events.size() && events.get(eventCursor).time <= time) {
            events.get(eventCursor++).action.run();
        }
        for (Track<?> track : tracks) {
            track.apply(time);
        }
    }

    // ------------------------------------------------------------------ persistence

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("length", length);
        tag.putFloat("speed", speed);
        tag.putBoolean("loop", loop);
        ListTag list = new ListTag();
        for (Track<?> track : tracks) {
            if (track.binding() != null) {
                list.add(saveTrack(track));
            }
        }
        tag.put("tracks", list);
        return tag;
    }

    private static <T> CompoundTag saveTrack(Track<T> track) {
        CompoundTag tag = new CompoundTag();
        tag.putString("binding", track.binding());
        ListTag keys = new ListTag();
        for (Track.Key<T> key : track.keys()) {
            CompoundTag k = new CompoundTag();
            k.putFloat("time", key.time());
            k.putString("easing", key.easing().name());
            float[] values = track.encode(key.value());
            int[] bits = new int[values.length];
            for (int i = 0; i < values.length; i++) {
                bits[i] = Float.floatToIntBits(values[i]);
            }
            k.putIntArray("value", bits);
            keys.add(k);
        }
        tag.put("keys", keys);
        return tag;
    }

    public void load(CompoundTag tag) {
        length = tag.contains("length") ? tag.getFloat("length") : -1;
        speed = tag.contains("speed") ? tag.getFloat("speed") : 1;
        loop = !tag.contains("loop") || tag.getBoolean("loop");
        ListTag list = tag.getList("tracks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            Track<Object> track;
            try {
                track = bound(t.getString("binding"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            ListTag keys = t.getList("keys", Tag.TAG_COMPOUND);
            for (int j = 0; j < keys.size(); j++) {
                CompoundTag k = keys.getCompound(j);
                int[] bits = k.getIntArray("value");
                if (bits.length < track.type().components) {
                    continue;
                }
                float[] values = new float[bits.length];
                for (int n = 0; n < bits.length; n++) {
                    values[n] = Float.intBitsToFloat(bits[n]);
                }
                Easing easing;
                try {
                    easing = Easing.valueOf(k.getString("easing"));
                } catch (IllegalArgumentException e) {
                    easing = Easing.LINEAR;
                }
                track.key(k.getFloat("time"), track.decode(values, 0), easing);
            }
        }
    }
}
