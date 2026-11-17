package me.methodial.kraz.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * How the editor's window is arranged, the way Blender and Axiom let you arrange theirs: which panels are docked
 * where or floating, how big the docks are, where floating windows sit, the UI scale and which view aids show.
 * Saved to {@code config/kraz-editor.json} so it is the same next time.
 */
public final class EditorLayout {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Where a panel lives. */
    public enum Place {LEFT, RIGHT, BOTTOM, FLOAT, HIDDEN}

    /** The editor's panels. */
    public enum PanelId {
        OUTLINER("Outliner", "menu", Place.LEFT),
        PROPERTIES("Properties", "info", Place.RIGHT),
        TIMELINE("Timeline", "kz-film", Place.BOTTOM),
        LIBRARY("Library", "grid", Place.RIGHT),
        WORLD("Show in world", "kz-world", Place.RIGHT),
        SHORTCUTS("Shortcuts", "kz-help", Place.RIGHT);

        public final String title;
        public final String icon;
        /** Where it docks when docked without a choice. */
        public final Place home;

        PanelId(String title, String icon, Place home) {
            this.title = title;
            this.icon = icon;
            this.home = home;
        }
    }

    /** One panel's state. */
    public static final class PanelState {
        public Place place;
        /** Floating geometry in UI units; also where it returns to when floated again. */
        public float x = -1, y = -1, w = 260, h = 300;

        PanelState(Place place) {
            this.place = place;
        }
    }

    public float leftWidth = 160;
    public float rightWidth = 220;
    public float bottomHeight = 130;
    /** Multiplies the automatic UI scale: 1 is the game's GUI scale (as the window allows). */
    public float uiScale = 1;
    public boolean toolColumn = true;
    public boolean navGizmo = true;
    public boolean statusBar = true;
    public boolean grid = true;
    public Map<PanelId, PanelState> panels = new EnumMap<>(PanelId.class);

    /** Bumped on every change that needs the window rebuilt (a panel moved). Not saved. */
    transient long structure;

    public EditorLayout() {
        reset();
    }

    /** The default arrangement. */
    public void reset() {
        leftWidth = 160;
        rightWidth = 220;
        bottomHeight = 130;
        toolColumn = navGizmo = statusBar = grid = true;
        uiScale = 1;
        panels = new EnumMap<>(PanelId.class);
        panels.put(PanelId.OUTLINER, new PanelState(Place.LEFT));
        panels.put(PanelId.PROPERTIES, new PanelState(Place.RIGHT));
        panels.put(PanelId.TIMELINE, new PanelState(Place.BOTTOM));
        panels.put(PanelId.LIBRARY, new PanelState(Place.HIDDEN));
        panels.put(PanelId.WORLD, new PanelState(Place.HIDDEN));
        panels.put(PanelId.SHORTCUTS, new PanelState(Place.HIDDEN));
        PanelState library = panels.get(PanelId.LIBRARY);
        library.w = 250;
        library.h = 300;
        PanelState world = panels.get(PanelId.WORLD);
        world.w = 250;
        world.h = 330;
        PanelState shortcuts = panels.get(PanelId.SHORTCUTS);
        shortcuts.w = 240;
        shortcuts.h = 300;
        structure++;
    }

    public PanelState panel(PanelId id) {
        return panels.computeIfAbsent(id, i -> new PanelState(Place.HIDDEN));
    }

    public Place place(PanelId id) {
        return panel(id).place;
    }

    public boolean visible(PanelId id) {
        return place(id) != Place.HIDDEN;
    }

    public void place(PanelId id, Place place) {
        if (panel(id).place != place) {
            panel(id).place = place;
            structure++;
        }
    }

    /** Shows a hidden panel (floating, for the transient ones; docked at home for the rest) or hides it. */
    public void toggle(PanelId id) {
        if (visible(id)) {
            place(id, Place.HIDDEN);
        } else {
            place(id, switch (id) {
                case LIBRARY, WORLD, SHORTCUTS -> Place.FLOAT;
                default -> id.home;
            });
        }
    }

    public void touch() {
        structure++;
    }

    // ------------------------------------------------------------------ persistence

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("kraz-editor.json");
    }

    /** The saved layout, or the default one if there is none or it cannot be read. */
    public static EditorLayout load() {
        Path file = file();
        if (Files.isRegularFile(file)) {
            try {
                EditorLayout layout = GSON.fromJson(Files.readString(file), EditorLayout.class);
                if (layout != null && layout.panels != null) {
                    for (PanelId id : PanelId.values()) {
                        layout.panel(id);
                    }
                    layout.uiScale = Math.clamp(layout.uiScale, 0.5f, 2f);
                    return layout;
                }
            } catch (Exception e) {
                LOGGER.warn("Kraz: could not read the editor layout from {}; using the default", file, e);
            }
        }
        return new EditorLayout();
    }

    public void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(this));
        } catch (Exception e) {
            LOGGER.warn("Kraz: could not save the editor layout", e);
        }
    }
}
