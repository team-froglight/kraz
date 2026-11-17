package me.methodial.kraz.selftest;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.methodial.kraz.anim.Timeline;
import me.methodial.kraz.anim.Track;
import me.methodial.kraz.camera.SceneCamera;
import me.methodial.kraz.scene.Scene;
import me.methodial.kraz.scene.ScenePart;
import me.methodial.kraz.scene.element.SceneElement;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Semantic comparison of two scenes: everything that is supposed to survive a save/load round trip. */
final class SceneDiff {
    private SceneDiff() {
    }

    static List<String> diff(Scene a, Scene b) {
        List<String> out = new ArrayList<>();
        if (!a.environment().equals(b.environment())) {
            out.add("environment " + a.environment() + " vs " + b.environment());
        }
        if (a.background() != b.background()) {
            out.add("background");
        }
        SceneCamera ca = a.camera(), cb = b.camera();
        if (ca.yaw() != cb.yaw() || ca.pitch() != cb.pitch() || ca.distance() != cb.distance() || ca.fov() != cb.fov()
                || ca.orthoHeight() != cb.orthoHeight() || ca.orthographic() != cb.orthographic() || !ca.target().equals(cb.target())) {
            out.add("camera");
        }
        if (a.parts().size() != b.parts().size()) {
            out.add("part count " + a.parts().size() + " vs " + b.parts().size());
            return out;
        }
        for (int i = 0; i < a.parts().size(); i++) {
            diffPart(a.parts().get(i), b.parts().get(i), out);
        }
        diffTimeline(a.timeline(), b.timeline(), out);
        return out;
    }

    private static void diffPart(ScenePart a, ScenePart b, List<String> out) {
        String p = "part '" + a.name() + "': ";
        if (!a.name().equals(b.name())) {
            out.add(p + "name vs '" + b.name() + "'");
        }
        if (!a.offset().equals(b.offset()) || !a.pivot().equals(b.pivot()) || !a.rotation().equals(b.rotation())
                || a.scale() != b.scale() || a.alpha() != b.alpha() || a.visible() != b.visible()) {
            out.add(p + "transform");
        }
        Long2ObjectOpenHashMap<BlockState> blocksA = blocks(a), blocksB = blocks(b);
        if (!blocksA.equals(blocksB)) {
            out.add(p + "blocks (" + blocksA.size() + " vs " + blocksB.size() + ")");
        }
        if (!new Long2ObjectOpenHashMap<>(a.store().blockEntityData()).equals(new Long2ObjectOpenHashMap<>(b.store().blockEntityData()))) {
            out.add(p + "block entity data");
        }
        List<CompoundTag> elementsA = savedElements(a), elementsB = savedElements(b);
        if (!elementsA.equals(elementsB)) {
            out.add(p + "elements (" + elementsA.size() + " vs " + elementsB.size() + ")");
        }
    }

    private static Long2ObjectOpenHashMap<BlockState> blocks(ScenePart part) {
        Long2ObjectOpenHashMap<BlockState> map = new Long2ObjectOpenHashMap<>();
        part.store().forEachBlock((x, y, z, state) -> map.put(BlockPos.asLong(x, y, z), state));
        return map;
    }

    private static List<CompoundTag> savedElements(ScenePart part) {
        List<CompoundTag> list = new ArrayList<>();
        for (SceneElement element : part.elements()) {
            CompoundTag tag = element.save();
            if (tag != null) {
                list.add(tag);
            }
        }
        return list;
    }

    private static void diffTimeline(Timeline a, Timeline b, List<String> out) {
        if (a.loop() != b.loop() || a.speed() != b.speed() || a.length() != b.length()) {
            out.add("timeline settings");
        }
        for (Track<?> track : a.tracks()) {
            if (track.binding() == null) {
                continue;
            }
            Track<?> other = null;
            for (Track<?> candidate : b.tracks()) {
                if (track.binding().equals(candidate.binding())) {
                    other = candidate;
                }
            }
            if (other == null) {
                out.add("track " + track.binding() + " missing");
                continue;
            }
            if (track.keys().size() != other.keys().size()) {
                out.add("track " + track.binding() + " key count");
                continue;
            }
            for (int i = 0; i < track.keys().size(); i++) {
                Track.Key<?> ka = track.keys().get(i), kb = other.keys().get(i);
                if (ka.time() != kb.time() || ka.easing() != kb.easing() || !Objects.equals(ka.value(), kb.value())) {
                    out.add("track " + track.binding() + " key " + i);
                }
            }
        }
    }
}
