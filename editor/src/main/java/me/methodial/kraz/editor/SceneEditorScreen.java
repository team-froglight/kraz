package me.methodial.kraz.editor;

import me.methodial.apothecary.event.EventType;
import me.methodial.apothecary.mc.node.ItemView;
import me.methodial.apothecary.mc.screen.UIScreen;
import me.methodial.apothecary.node.Node;
import me.methodial.apothecary.node.Region;
import me.methodial.apothecary.node.UIRoot;
import me.methodial.apothecary.theme.OreUI;
import me.methodial.apothecary.widget.Button;
import me.methodial.apothecary.widget.Dialog;
import me.methodial.apothecary.widget.Menu;
import me.methodial.apothecary.widget.PixelIcon;
import me.methodial.apothecary.widget.Select;
import me.methodial.apothecary.widget.Text;
import me.methodial.apothecary.widget.TextField;
import me.methodial.apothecary.widget.Toast;
import me.methodial.kraz.anim.Easing;
import me.methodial.kraz.anim.Timeline;
import me.methodial.kraz.camera.SceneCamera;
import me.methodial.kraz.editor.EditorLayout.PanelId;
import me.methodial.kraz.editor.EditorLayout.PanelState;
import me.methodial.kraz.editor.EditorLayout.Place;
import me.methodial.kraz.editor.EditorModel.Gizmo;
import me.methodial.kraz.editor.EditorModel.Row;
import me.methodial.kraz.editor.EditorModel.Tool;
import me.methodial.kraz.scene.Scene;
import me.methodial.kraz.scene.ScenePart;
import me.methodial.kraz.scene.element.BlockElement;
import me.methodial.kraz.scene.element.EntityElement;
import me.methodial.kraz.scene.element.ItemElement;
import me.methodial.kraz.scene.element.SceneElement;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static me.methodial.apothecary.ui.UI.*;

/**
 * In-game scene editor, arranged the way Blender and Axiom arrange theirs, in Menuet's Ore UI look.
 *
 * <pre>
 * ┌ File Edit View Add Window Help │ undo redo │ brush ▢ id ▦ │ status … │ name save │ world ┐
 * ├ Outliner ┐║┌tools┐                                       ┌nav┐║┌ Properties ──────────┤
 * │          │║│ ▶ ▣ │          the scene, full window        │ ⊕ │║│                      │
 * │          │║│ ✥ ⟳ │                                        │ ✋│║│                      │
 * ├══════════╧╩╧═════╧════════════════════════════════════════╧═══╧╩╧══════════════════════┤
 * │ Timeline                                                                               │
 * ├ LMB select · RMB orbit · MMB pan ……………………………… blocks 120 · parts 2 · t 1.25s ──────┤
 * </pre>
 *
 * Every panel is a window: drag its title bar out of a dock to float it, drag a floating one to an edge to dock it,
 * drag the splitters (║ ═) to resize docks and a floating window's corner to resize it. Window menu shows and hides
 * panels and view aids; View sets the UI scale. The arrangement is saved ({@link EditorLayout}).
 *
 * <p>Everything else goes through the {@link EditorModel}; the screen is a view of it. The model outlives the
 * screen: {@code /kraz edit} without a name, or the editor key, reopens the last session.
 */
public class SceneEditorScreen extends UIScreen {
    /** The last session, reopened by {@code /kraz edit} without a name. */
    private static @Nullable EditorModel lastSession;
    private static @Nullable EditorLayout sharedLayout;

    private final EditorModel model;
    private final Scene scene;
    private final EditorLayout layout;
    /** Refreshes for controls that live as long as the screen (panel contents). */
    private final List<Runnable> syncers = new ArrayList<>();
    /** Refreshes for the window chrome, rebuilt whenever the arrangement changes. */
    private final List<Runnable> chromeSyncers = new ArrayList<>();
    private final List<Runnable> inspectorSyncers = new ArrayList<>();
    private final Map<PanelId, Node> contents = new EnumMap<>(PanelId.class);
    private final Map<PanelId, Node> windows = new EnumMap<>(PanelId.class);
    private EditorViewport viewport;
    private TimelineNode timeline;
    private Region chrome, outliner, inspector;
    private Text status;
    private Node leftDock, rightDock, bottomDock, dockHint;
    private String outlineSignature = "", inspectorSignature = "";
    private String lastStatus = "";
    private long lastFrame = -1;
    private long builtStructure = -1;
    private int zOrder = 10;
    private float appliedLeft = -1, appliedRight = -1, appliedBottom = -1;
    private long appliedFor = -1;
    private @Nullable String renaming;
    private final List<PanelId> opened = new ArrayList<>();
    private final LibraryPanel library;
    private final WorldPanel world;
    private boolean closingForWorld;

    public SceneEditorScreen(Scene scene, String sceneName, @Nullable Screen parent) {
        this(new EditorModel(scene, sceneName), parent);
    }

    public SceneEditorScreen(EditorModel model, @Nullable Screen parent) {
        super(Component.translatable("kraz.editor.title"));
        EditorIcons.register();
        this.model = model;
        this.scene = model.scene();
        if (sharedLayout == null) {
            sharedLayout = EditorLayout.load();
        }
        this.layout = sharedLayout;
        this.library = new LibraryPanel(model);
        this.world = new WorldPanel(model, this::viewInWorld);
        lastSession = model;
        parent(parent);
        backdrop(Backdrop.NONE);
        pauses(false);
        theme(OreUI.theme()).sheet(OreUI.sheet()).sheet(EditorStyles.sheet());
    }

    /** The last editing session, if any. */
    public static @Nullable EditorModel lastSession() {
        return lastSession;
    }

    public EditorModel model() {
        return model;
    }

    public Scene scene() {
        return scene;
    }

    public EditorLayout layout() {
        return layout;
    }

    public EditorViewport viewport() {
        return viewport;
    }

    public TimelineNode timelineNode() {
        return timeline;
    }

    /** The window frame of a panel, while it is shown. */
    public @Nullable Node window(PanelId id) {
        return windows.get(id);
    }

    /**
     * Menuet's rule (the player's GUI scale, unless the window is too small for the editor at it), times the
     * editor's own UI scale from the View menu.
     */
    @Override
    public float currentScale() {
        var window = Minecraft.getInstance().getWindow();
        float fit = (float) Math.floor(Math.min(window.getWidth() / 620f, window.getHeight() / 340f));
        float base = Math.max(1f, Math.min((float) window.getGuiScale(), fit));
        return Math.max(0.5f, base * layout.uiScale);
    }

    /** Sets the UI scale multiplier and lays the editor out again at it. */
    public void setUiScale(float scale) {
        layout.uiScale = Math.clamp(Math.round(scale * 4) / 4f, 0.5f, 2f);
        resize(minecraft, width, height);
        layout.save();
        model.setStatus("UI scale " + Math.round(layout.uiScale * 100) + "%");
    }

    // ------------------------------------------------------------------ building

    @Override
    protected void build(UIRoot root) {
        viewport = new EditorViewport(model, this::frame);
        viewport.cls("kz-stage");
        viewport.setShowGrid(layout.grid);
        timeline = new TimelineNode(model);
        timeline.style(x -> x.grow().fullW().minH(0));
        outliner = new Region(this::buildOutliner);
        outliner.cls("kz-list");
        inspector = new Region(this::buildInspector);
        inspector.cls("kz-list");
        chrome = new Region(this::buildChrome);
        chrome.cls("kz-layer");
        root.setContent(box(cls("kz-page"), viewport, chrome));
    }

    /** The panel contents, made once and moved between windows as the arrangement changes. */
    private Node content(PanelId id) {
        return contents.computeIfAbsent(id, i -> switch (i) {
            case OUTLINER -> box(cls("kz-panel-col"), outliner, addBar());
            case PROPERTIES -> inspector;
            case TIMELINE -> box(cls("kz-panel-col"), transport(), timeline);
            case LIBRARY -> library.content();
            case WORLD -> world.content();
            case SHORTCUTS -> shortcuts();
        });
    }

    /** The whole arrangement: rebuilt when a panel moves; sizes change in place while dragging. */
    private void buildChrome(Region r) {
        chromeSyncers.clear();
        windows.clear();
        builtStructure = layout.structure;

        appliedFor = -1; // new dock nodes: size them
        leftDock = dock(Place.LEFT);
        rightDock = dock(Place.RIGHT);
        bottomDock = dock(Place.BOTTOM);
        applyDockSizes();

        Node middle = box(cls("kz-middle"));
        if (leftDock != null) {
            middle.add(leftDock, splitter(true, () -> appliedLeft > 0 ? appliedLeft : layout.leftWidth, 1, d -> {
                layout.leftWidth = clampWidth(d);
                applyDockSizes();
            }));
        }
        Node center = box(cls("kz-center"));
        if (layout.toolColumn) {
            center.add(toolColumn());
        }
        if (layout.navGizmo) {
            center.add(navColumn());
        }
        middle.add(center);
        if (rightDock != null) {
            middle.add(splitter(true, () -> appliedRight > 0 ? appliedRight : layout.rightWidth, -1, d -> {
                layout.rightWidth = clampWidth(d);
                applyDockSizes();
            }), rightDock);
        }
        r.add(menuBar(), middle);
        if (bottomDock != null) {
            r.add(splitter(false, () -> appliedBottom > 0 ? appliedBottom : layout.bottomHeight, -1, d -> {
                layout.bottomHeight = Math.clamp(d, 50, Math.max(60, ui.height() * 0.6f));
                applyDockSizes();
            }), bottomDock);
        }
        if (layout.statusBar) {
            r.add(statusBar());
        }

        Node floats = box(cls("kz-floats"));
        for (PanelId id : PanelId.values()) {
            if (layout.place(id) == Place.FLOAT) {
                floats.add(window(id, true));
            }
        }
        dockHint = box(cls("kz-dock-hint"));
        dockHint.style(x -> x.hidden());
        floats.add(dockHint);
        r.add(floats);
    }

    private float clampWidth(float w) {
        return Math.clamp(w, 90, Math.max(100, ui.width() * 0.42f));
    }

    /**
     * Sizes the docks: the sizes the user dragged them to, given way where the window is too small, so the view
     * always keeps room for itself (and the tool bar and navigation gizmo beside it). The saved sizes are untouched.
     */
    private void applyDockSizes() {
        float minView = 200;
        float left = leftDock != null ? layout.leftWidth : 0, right = rightDock != null ? layout.rightWidth : 0;
        float available = Math.max(0, ui.width() - minView - 16);
        if (left + right > available && left + right > 0) {
            float k = available / (left + right);
            left = Math.max(Math.min(90, left), left * k);
            right = Math.max(Math.min(90, right), right * k);
        }
        float bottom = Math.min(layout.bottomHeight, Math.max(50, ui.height() - 140));
        float l = left, rr = right, b = bottom;
        if (l == appliedLeft && rr == appliedRight && b == appliedBottom && builtStructure == appliedFor) {
            return; // unchanged: no need to lay the window out again
        }
        appliedLeft = l;
        appliedRight = rr;
        appliedBottom = b;
        appliedFor = builtStructure;
        if (leftDock != null) {
            leftDock.style(x -> x.w(l));
        }
        if (rightDock != null) {
            rightDock.style(x -> x.w(rr));
        }
        if (bottomDock != null) {
            bottomDock.style(x -> x.h(b));
        }
    }

    private @Nullable Node dock(Place place) {
        List<PanelId> ids = new ArrayList<>();
        for (PanelId id : PanelId.values()) {
            if (layout.place(id) == place) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return null;
        }
        Node dock = box(cls("kz-dock", place.name().toLowerCase(Locale.ROOT)));
        for (PanelId id : ids) {
            dock.add(window(id, false));
        }
        return dock;
    }

    /**
     * A draggable splitter. While dragged, {@code set} receives the size the dock had when the drag started plus
     * the pointer's travel along the splitter's axis ({@code sign} -1 for docks on the right or bottom), so the
     * splitter stays under the pointer.
     */
    private Node splitter(boolean vertical, java.util.function.Supplier<Float> current, float sign, java.util.function.Consumer<Float> set) {
        Node split = box(cls("kz-split", vertical ? "v" : "h"));
        float[] start = {0};
        Drags.place(split, new Drags.Placement() {
            @Override
            public void start() {
                start[0] = current.get();
            }

            @Override
            public void moved(me.methodial.apothecary.event.MouseEvent e) {
                set.accept(start[0] + sign * (vertical ? e.dragX() : e.dragY()));
            }
        });
        split.on(EventType.DRAG_END, e -> layout.save());
        split.tooltip("Drag to resize");
        return split;
    }

    // ------------------------------------------------------------------ windows

    /** A panel in its window frame: title bar (drag to move, or to undock), dock/float and close buttons. */
    private Node window(PanelId id, boolean floating) {
        PanelState state = layout.panel(id);
        Node body = content(id);
        body.removeFromParent();

        Button dockButton = Button.pixel(floating ? "arrow-left" : "kz-frame", floating ? "Dock it" : "Float it as a window");
        dockButton.cls("kz-win-btn");
        dockButton.action(() -> {
            layout.place(id, floating ? id.home : Place.FLOAT);
            layout.save();
        });
        Button close = Button.pixel("close", "Hide (Window menu brings it back)");
        close.cls("kz-win-btn");
        close.action(() -> {
            layout.place(id, Place.HIDDEN);
            layout.save();
        });
        Node head = box(cls("kz-win-head"), new PixelIcon(id.icon), text(id.title).cls("kz-win-title"), dockButton, close);
        Node win = box(cls("kz-card", "kz-win", floating ? "kz-float" : "kz-docked"), head, box(cls("kz-win-body"), body));
        win.id("panel-" + id.name().toLowerCase(Locale.ROOT));
        windows.put(id, win);

        // The title bar: a floating window follows the pointer exactly (where it was when grabbed, plus the
        // pointer's travel); a docked one comes out of its dock once pulled a few units, held where it was grabbed.
        float[] grab = {0, 0};
        Drags.place(head, new Drags.Placement() {
            @Override
            public void start() {
                grab[0] = state.x;
                grab[1] = state.y;
            }

            @Override
            public void moved(me.methodial.apothecary.event.MouseEvent e) {
                if (layout.place(id) == Place.FLOAT) {
                    state.x = grab[0] + e.dragX();
                    state.y = grab[1] + e.dragY();
                    placeFloating(id);
                    showDockHint(dockZone(e.x(), e.y()));
                } else if (Math.abs(e.dragX()) + Math.abs(e.dragY()) > 10) {
                    // Float it with the pointer over its title bar, then keep following from there.
                    float offset = Math.min(state.w, 120) / 2;
                    state.x = e.x() - offset;
                    state.y = e.y() - 8;
                    grab[0] = state.x - e.dragX();
                    grab[1] = state.y - e.dragY();
                    layout.place(id, Place.FLOAT);
                }
            }
        });
        head.on(EventType.DRAG_END, e -> {
            Place zone = dockZone(e.x(), e.y());
            showDockHint(null);
            if (layout.place(id) == Place.FLOAT && zone != null) {
                layout.place(id, zone);
            }
            layout.save();
        });

        if (floating) {
            if (state.x < 0 || state.y < 0) {
                // First time floating: beside the right dock.
                state.x = Math.max(0, ui.width() - layout.rightWidth - state.w - 16);
                state.y = 34;
            }
            placeFloating(id);
            win.on(EventType.MOUSE_DOWN, e -> win.style(x -> x.z(++zOrder)));
            Node grip = box(cls("kz-grip"));
            grip.tooltip("Drag to resize");
            float[] size = {0, 0};
            Drags.place(grip, new Drags.Placement() {
                @Override
                public void start() {
                    size[0] = state.w;
                    size[1] = state.h;
                }

                @Override
                public void moved(me.methodial.apothecary.event.MouseEvent e) {
                    state.w = Math.clamp(size[0] + e.dragX(), 140, ui.width());
                    state.h = Math.clamp(size[1] + e.dragY(), 90, ui.height());
                    placeFloating(id);
                }
            });
            grip.on(EventType.DRAG_END, e -> layout.save());
            win.add(grip);
        }
        return win;
    }

    private void moveFloating(PanelId id, float dx, float dy) {
        PanelState s = layout.panel(id);
        s.x += dx;
        s.y += dy;
        placeFloating(id);
    }

    /** Puts a floating window where its state says, kept on screen. */
    private void placeFloating(PanelId id) {
        Node win = windows.get(id);
        PanelState s = layout.panel(id);
        s.w = Math.min(s.w, ui.width());
        s.h = Math.min(s.h, ui.height());
        s.x = Math.clamp(s.x, 0, Math.max(0, ui.width() - s.w));
        s.y = Math.clamp(s.y, 0, Math.max(0, ui.height() - s.h));
        if (win != null) {
            win.style(x -> x.left(s.x).top(s.y).w(s.w).h(s.h));
        }
    }

    /** The dock a window dropped at this point would join, if it is near an edge. */
    private @Nullable Place dockZone(float x, float y) {
        float edge = 26;
        if (x < edge) {
            return Place.LEFT;
        }
        if (x > ui.width() - edge) {
            return Place.RIGHT;
        }
        if (y > ui.height() - edge) {
            return Place.BOTTOM;
        }
        return null;
    }

    private void showDockHint(@Nullable Place zone) {
        if (dockHint == null) {
            return;
        }
        if (zone == null) {
            dockHint.style(x -> x.hidden());
            return;
        }
        float w = ui.width(), h = ui.height();
        switch (zone) {
            case LEFT -> dockHint.style(x -> x.block().left(0).top(30).w(layout.leftWidth).h(h - 30 - layout.bottomHeight));
            case RIGHT -> dockHint.style(x -> x.block().left(w - layout.rightWidth).top(30).w(layout.rightWidth).h(h - 30 - layout.bottomHeight));
            default -> dockHint.style(x -> x.block().left(0).top(h - layout.bottomHeight).w(w).h(layout.bottomHeight));
        }
    }

    /** Shows or hides a panel, remembering the order transient panels were opened in for Escape. */
    public void togglePanel(PanelId id) {
        layout.toggle(id);
        opened.remove(id);
        // Only the pop-up style windows close with Escape; the main panels stay until hidden on purpose.
        boolean transientPanel = id == PanelId.LIBRARY || id == PanelId.WORLD || id == PanelId.SHORTCUTS;
        if (transientPanel && layout.visible(id)) {
            opened.add(id);
        }
        layout.save();
    }

    // ------------------------------------------------------------------ menu bar

    private Node menuBar() {
        ItemView swatch = new ItemView(this::brushIcon);
        swatch.decorations(false);
        swatch.cls("kz-swatch");
        swatch.tooltip("The brush");
        TextField id = new TextField(model.idText);
        id.id("brush");
        id.placeholder("block, item or entity id");
        id.tooltip("The brush: a block (states allowed: oak_stairs[facing=east]), item or entity id. Q picks the block under the cursor");
        id.cls("kz-field");
        id.style(x -> x.w(104));
        id.onChange(text -> model.idText = text);
        chromeSyncers.add(() -> {
            if (!id.isFocused() && !model.idText.equals(id.text())) {
                id.setQuietly(model.idText);
            }
        });

        status = new Text("");
        status.cls("kz-status");
        lastStatus = "";

        TextField name = new TextField(model.sceneName);
        name.id("scene-name");
        name.placeholder("scene name");
        name.tooltip("The name Save, Open and exports use");
        name.style(x -> x.w(84));
        name.onChange(text -> model.sceneName = text);

        Button worldButton = icon("world", "kz-world", "Show in the world", () -> togglePanel(PanelId.WORLD));
        chromeSyncers.add(() -> worldButton.toggleCls("on", model.projection() != null));
        Button libraryButton = icon("library", "grid", "Library: every block, item and entity (Tab)", () -> togglePanel(PanelId.LIBRARY));
        chromeSyncers.add(() -> libraryButton.toggleCls("on", layout.visible(PanelId.LIBRARY)));

        Node bar = box(cls("kz-card", "kz-top"));
        for (String menu : new String[]{"File", "Edit", "View", "Add", "Window", "Help"}) {
            bar.add(menuButton(menu));
        }
        bar.add(EditorUi.separator(),
                group(icon("undo", "kz-undo", "Undo (Ctrl+Z)", model::undo), icon("redo", "kz-redo", "Redo (Ctrl+Y)", model::redo)),
                EditorUi.separator(),
                swatch, id, libraryButton,
                status,
                name, icon("save", "kz-save", "Save (Ctrl+S)", model::save),
                EditorUi.separator(), worldButton);
        return bar;
    }

    private Node menuButton(String title) {
        Node button = box(cls("kz-menu-btn"), text(title));
        button.id("menu-" + title.toLowerCase(Locale.ROOT));
        button.focusable(true);
        button.onClick(() -> {
            Menu menu = buildMenu(title);
            button.state("open", true);
            menu.show(ui, button, UIRoot.Placement.BELOW, () -> button.state("open", false));
        });
        return button;
    }

    private static String check(boolean on) {
        return on ? "check" : null;
    }

    /** The menus' contents, made fresh each time so ticks and states are current. */
    Menu buildMenu(String title) {
        Menu m = new Menu();
        SceneCamera camera = scene.camera();
        switch (title) {
            case "File" -> m.item(null, "New", null, this::confirmNew)
                    .item(null, "Open '" + model.sceneName + "'", null, model::load)
                    .item(null, "Save", "Ctrl+S", model::save)
                    .separator()
                    .item(null, "Export image (PNG)", null, model::exportPng)
                    .item(null, "Export animation (GIF)", null, model::exportGif)
                    .separator()
                    .item(null, "Show in world…", null, () -> togglePanel(PanelId.WORLD))
                    .item(null, "Close editor", "Esc", this::onClose);
            case "Edit" -> m.item(null, "Undo", "Ctrl+Z", model::undo)
                    .item(null, "Redo", "Ctrl+Y", model::redo)
                    .separator()
                    .item(null, "Duplicate", "Ctrl+D", model::duplicateSelection)
                    .item(null, "Rename", "F2", this::startRename)
                    .item(null, "Delete", "Del", model::deleteSelection)
                    .item(null, "Select none", "Esc", () -> model.select(null))
                    .separator()
                    .item(null, "Key selection", "K", model::keySelection);
            case "View" -> m.item(null, "Frame selection", "F", model::frameSelection)
                    .item(null, "Isometric", "0", () -> view(-35, 30))
                    .item(null, "Front", "1", () -> view(0, 0))
                    .item(null, "Side", "3", () -> view(90, 0))
                    .item(null, "Top", "7", () -> view(0, 89.9f))
                    .item(check(camera.orthographic()), "Orthographic", "5", () -> camera.setOrthographic(!camera.orthographic()))
                    .item(check(layout.grid), "Ground grid", null, this::toggleGrid)
                    .separator()
                    .header("UI scale " + Math.round(layout.uiScale * 100) + "%")
                    .item(null, "Larger", "Ctrl+=", () -> setUiScale(layout.uiScale + 0.25f))
                    .item(null, "Smaller", "Ctrl+-", () -> setUiScale(layout.uiScale - 0.25f))
                    .item(null, "Reset", "Ctrl+0", () -> setUiScale(1));
            case "Add" -> m.item("box", "Part", null, model::addPart)
                    .item(null, "Block (brush)", null, model::addBlockElement)
                    .item(null, "Item (brush)", null, model::addItemElement)
                    .item(null, "Entity (brush)", null, model::addEntityElement)
                    .separator()
                    .item("grid", "From the library…", "Tab", () -> togglePanel(PanelId.LIBRARY));
            case "Window" -> {
                for (PanelId id : PanelId.values()) {
                    m.item(check(layout.visible(id)), id.title, null, () -> togglePanel(id));
                }
                m.separator()
                        .item(check(layout.toolColumn), "Tool bar", null, () -> {
                            layout.toolColumn = !layout.toolColumn;
                            layout.touch();
                        })
                        .item(check(layout.navGizmo), "Navigation gizmo", null, () -> {
                            layout.navGizmo = !layout.navGizmo;
                            layout.touch();
                        })
                        .item(check(layout.statusBar), "Status bar", null, () -> {
                            layout.statusBar = !layout.statusBar;
                            layout.touch();
                        })
                        .separator()
                        .item(null, "Reset layout", null, () -> {
                            float scale = layout.uiScale;
                            layout.reset();
                            layout.uiScale = scale;
                            layout.save();
                        });
            }
            default -> m.item("info", "Shortcuts", "F1", () -> togglePanel(PanelId.SHORTCUTS));
        }
        return m;
    }

    private void toggleGrid() {
        layout.grid = !layout.grid;
        viewport.setShowGrid(layout.grid);
        layout.save();
    }

    private void startRename() {
        Object selection = model.selection();
        if (selection instanceof ScenePart part && part != scene.main()) {
            renaming = part.name();
        } else if (selection instanceof SceneElement element) {
            renaming = element.name();
        }
    }

    private ItemStack brushIcon() {
        ResourceLocation id = ResourceLocation.tryParse(model.idText.trim().replaceAll("\\[.*$", "").replaceAll("\\{.*$", ""));
        if (id == null) {
            return ItemStack.EMPTY;
        }
        if (BuiltInRegistries.ITEM.containsKey(id)) {
            return new ItemStack(BuiltInRegistries.ITEM.get(id));
        }
        if (BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            var egg = net.minecraft.world.item.SpawnEggItem.byId(BuiltInRegistries.ENTITY_TYPE.get(id));
            return new ItemStack(egg != null ? egg : Items.NAME_TAG);
        }
        return ItemStack.EMPTY;
    }

    private static Node group(Node... nodes) {
        return box(cls("kz-group"), (Object[]) nodes);
    }

    private static Button icon(String id, String icon, String tooltip, Runnable action) {
        Button b = EditorUi.icon(icon, tooltip, action);
        b.id(id);
        return b;
    }

    // ------------------------------------------------------------------ tool bar, navigation, status

    /** Blender's toolbar: the tools down the left edge of the view. */
    private Node toolColumn() {
        Button[] tools = {
                icon("tool-select", "kz-select", "Select and move (V)", () -> model.tool = Tool.SELECT),
                icon("tool-place", "kz-place", "Place: click, drag to paint, Shift-drag for a box (B)", () -> model.tool = Tool.PLACE),
                icon("tool-erase", "kz-erase", "Erase: click, drag, Shift-drag for a box (E)", () -> model.tool = Tool.ERASE)};
        Button[] gizmos = {
                icon("gizmo-move", "kz-move", "Move gizmo (G). Ctrl snaps to blocks, Shift to 1/16", () -> model.gizmo = Gizmo.MOVE),
                icon("gizmo-rotate", "kz-rotate", "Rotate gizmo (R). Ctrl snaps to 15°", () -> model.gizmo = Gizmo.ROTATE),
                icon("gizmo-scale", "kz-scale", "Scale gizmo (S)", () -> model.gizmo = Gizmo.SCALE)};
        chromeSyncers.add(() -> {
            for (Tool t : Tool.values()) {
                tools[t.ordinal()].toggleCls("on", model.tool == t);
            }
            for (Gizmo g : Gizmo.values()) {
                gizmos[g.ordinal()].toggleCls("on", model.gizmo == g);
            }
        });
        return box(cls("kz-card", "kz-tools"), tools[0], tools[1], tools[2], box(cls("kz-hsep")), gizmos[0], gizmos[1], gizmos[2]);
    }

    /** Blender's navigation column: the axis gizmo, then zoom and pan handles (drag them), lens and framing. */
    private Node navColumn() {
        SceneCamera camera = scene.camera();
        NavGizmo gizmo = new NavGizmo(camera);
        Button zoom = icon("nav-zoom", "search", "Drag up or down to zoom", () -> { });
        Drags.step(zoom, d -> camera.zoom((float) Math.pow(1.01, d[1])));
        Button pan = icon("nav-pan", "kz-move", "Drag to pan", () -> { });
        Drags.step(pan, d -> {
            float perUnit = (camera.orthographic() ? camera.orthoHeight()
                    : 2f * camera.distance() * (float) Math.tan(Math.toRadians(camera.fov()) * 0.5)) / Math.max(1f, ui.height());
            camera.pan(-d[0] * perUnit, d[1] * perUnit);
        });
        Button lens = icon("nav-lens", "kz-camera", "Perspective or orthographic (5)", () -> camera.setOrthographic(!camera.orthographic()));
        chromeSyncers.add(() -> lens.toggleCls("on", !camera.orthographic()));
        Button frame = icon("frame", "kz-frame", "Frame the selection (F)", model::frameSelection);
        Button grid = icon("grid", "grid", "Ground grid", this::toggleGrid);
        chromeSyncers.add(() -> grid.toggleCls("on", layout.grid));
        return box(cls("kz-nav-col"), gizmo, box(cls("kz-card", "kz-nav-buttons"), zoom, pan, lens, frame, grid));
    }

    private Node statusBar() {
        Text hints = new Text("");
        hints.cls("kz-hints");
        Text stats = new Text("");
        stats.cls("kz-stats");
        chromeSyncers.add(() -> {
            String h = switch (model.tool) {
                case SELECT -> "LMB select · drag a gizmo handle · RMB orbit · MMB pan · wheel zoom";
                case PLACE -> "LMB place · drag paint · Shift-drag box · Q pick block · RMB orbit · MMB pan";
                case ERASE -> "LMB erase · drag erase · Shift-drag clear box · RMB orbit · MMB pan";
            };
            if (!h.equals(hints.text())) {
                hints.text(h);
            }
            int[] blocks = {0};
            int elements = 0;
            for (ScenePart part : scene.parts()) {
                part.store().forEachBlock((x, y, z, s) -> blocks[0]++);
                elements += part.elements().size();
            }
            String s = String.format(Locale.ROOT, "%d blocks · %d parts · %d elements · %.2fs%s", blocks[0], scene.parts().size(), elements,
                    scene.timeline().time() / 20f, model.dirty() ? " · unsaved" : "");
            if (!s.equals(stats.text())) {
                stats.text(s);
            }
        });
        return box(cls("kz-status-bar"), hints, spacer(), stats);
    }

    private void view(float yaw, float pitch) {
        scene.camera().setYaw(yaw).setPitch(pitch);
    }

    private Node addBar() {
        return row(s(x -> x.gap(0).wrap().noShrink()),
                icon("add-part", "kz-part", "New part: a group of blocks that moves as one", model::addPart),
                icon("add-block", "kz-block", "Add the brush block as a free-moving model", model::addBlockElement),
                icon("add-item", "kz-item", "Add the brush item", model::addItemElement),
                icon("add-entity", "kz-entity", "Add the brush entity (needs a world)", model::addEntityElement),
                icon("duplicate", "kz-copy", "Duplicate the selection (Ctrl+D)", model::duplicateSelection),
                icon("delete", "trash", "Delete the selection (Del)", model::deleteSelection));
    }

    private Node transport() {
        Timeline t = scene.timeline();
        Button play = icon("play", "kz-play", "Play / pause (Space)", model::togglePlay);
        // Swap the glyph in place: pixelIcon() would add another one each time.
        me.methodial.apothecary.widget.PixelIcon playGlyph = null;
        for (Node child : play.children()) {
            if (child instanceof me.methodial.apothecary.widget.PixelIcon p) {
                playGlyph = p;
            }
        }
        final me.methodial.apothecary.widget.PixelIcon glyph = playGlyph;
        syncers.add(() -> {
            String want = t.playing() ? "kz-pause" : "kz-play";
            if (glyph != null && !want.equals(glyph.name())) {
                glyph.name(want);
            }
        });
        Button loop = icon("loop", "kz-loop", "Loop", () -> t.setLoop(!t.loop()));
        syncers.add(() -> loop.toggleCls("on", t.loop()));
        Button auto = EditorUi.label("Auto key", "When on, every edit to an animated value writes a key at the current time", () -> model.autoKey = !model.autoKey);
        auto.id("auto-key");
        syncers.add(() -> auto.toggleCls("on", model.autoKey));

        Select<Easing> easing = new Select<>();
        for (Easing e : Easing.values()) {
            easing.option(e, pretty(e.name()));
        }
        easing.setQuietly(model.shownEasing());
        easing.onChange(model::setEasing);
        easing.tooltip("Easing of the selected key, or of the keys you add next");
        easing.id("easing");
        easing.style(x -> x.w(96));
        syncers.add(() -> easing.setQuietly(model.shownEasing()));

        TextField time = EditorUi.number(t::time, v -> t.seek(Math.max(0, v)), 1, () -> { }, syncers);
        time.tooltip("Current time in ticks (20 per second). ← → step");
        TextField length = EditorUi.number(t::length, v -> t.setLength(Math.max(1, v)), 10, () -> model.editCheckpoint("length"), syncers);
        length.tooltip("Length in ticks");
        Text seconds = new Text("");
        seconds.cls("kz-muted", "kz-nowrap");
        syncers.add(() -> seconds.text(String.format(Locale.ROOT, "%.2fs / %.2fs", t.time() / 20f, t.length() / 20f)));

        return row(s(x -> x.itemsCenter().gap(4).noShrink()),
                group(icon("rewind", "kz-start", "Back to the start (Home)", () -> t.seek(0)), play, loop),
                text("Time").cls("kz-muted", "kz-nowrap"), time, text("of").cls("kz-muted", "kz-nowrap"), length, seconds,
                EditorUi.separator(),
                icon("key", "kz-key", "Key the selection at this time (K)", model::keySelection), auto, easing,
                spacer(),
                text("Drag keys to retime · right-click deletes").cls("kz-muted", "kz-hint"));
    }

    private static String pretty(String constant) {
        String s = constant.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }


    // ------------------------------------------------------------------ outliner

    private String outlineSignature() {
        StringBuilder s = new StringBuilder().append(System.identityHashCode(model.selection())).append(renaming);
        for (ScenePart part : scene.parts()) {
            s.append('/').append(part.name()).append(part.visible() ? "" : "!");
            for (SceneElement element : part.elements()) {
                s.append(',').append(element.name());
            }
        }
        return s.toString();
    }

    private void buildOutliner(Region r) {
        r.add(entry(null, "Scene settings", "kz-camera", false, null));
        for (ScenePart part : scene.parts()) {
            r.add(entry(part, part.name(), "kz-part", false, part));
            for (SceneElement element : part.elements()) {
                String icon = element instanceof EntityElement ? "kz-entity" : element instanceof ItemElement ? "kz-item"
                        : element instanceof BlockElement ? "kz-block" : "kz-part";
                r.add(entry(element, element.name().isEmpty() ? element.getClass().getSimpleName() : element.name(), icon, true, null));
            }
        }
    }

    private Node entry(@Nullable Object object, String name, String icon, boolean child, @Nullable ScenePart visibility) {
        boolean selected = model.selection() == object;
        Node row = box(cls("kz-row", "clickable"));
        if (child) {
            row.cls("child");
        }
        row.state("selected", selected);
        row.add(new me.methodial.apothecary.widget.PixelIcon(icon));
        String key = object == null ? null : name;
        if (key != null && key.equals(renaming) && object != scene.main()) {
            TextField field = new TextField(name);
            field.style(x -> x.grow().minW(0));
            field.onSubmit(text -> {
                renaming = null;
                model.rename(object, text);
            });
            row.add(field);
            ui.setFocus(field);
            field.selectAll();
        } else {
            row.add(text(name).cls("kz-row-label"));
        }
        if (visibility != null) {
            Button eye = Button.pixel(visibility.visible() ? "kz-eye" : "kz-eye-off", visibility.visible() ? "Hide (H)" : "Show (H)");
            eye.cls("kz-row-eye");
            eye.style(x -> x.size(12).pad(0).noShadow().borderW(0).bg(me.methodial.apothecary.style.Color.TRANSPARENT));
            eye.action(() -> {
                model.checkpoint();
                visibility.setVisible(!visibility.visible());
            });
            row.add(eye);
        }
        row.onClick(e -> {
            model.select(object);
            if (e.clickCount() >= 2 && object != null && object != scene.main()) {
                renaming = name; // double-click renames
            }
        });
        row.tooltip(object == null ? "Camera, light and background" : "Double-click to rename (F2)");
        return row;
    }

    // ------------------------------------------------------------------ inspector

    private void buildInspector(Region r) {
        inspectorSyncers.clear();
        for (Row row : model.rows()) {
            switch (row.kind) {
                case HEADER -> r.add(text(row.label).cls(r.childCount() == 0 ? "kz-title" : "kz-heading"));
                case FLOAT -> r.add(box(cls("kz-prop"), text(row.label).cls("kz-prop-label"), numberField(row, 0), keyDiamond(row)));
                case VEC3, EULER -> {
                    Node fields = box(cls("kz-prop-fields"));
                    String[] axes = {"x", "y", "z"};
                    for (int i = 0; i < 3; i++) {
                        fields.add(text(axes[i]).cls("kz-axis", axes[i]), numberField(row, i));
                    }
                    r.add(box(cls("kz-prop", "vector"),
                            box(cls("kz-prop-head"), text(row.label + (row.kind == EditorModel.Kind.EULER ? " (°)" : "")).cls("kz-prop-label"), keyDiamond(row)),
                            fields));
                }
                case ACTION -> {
                    Button b = EditorUi.label(row.text.get(), null, () -> model.runAction(row));
                    b.cls("kz-wide");
                    inspectorSyncers.add(() -> EditorUi.setLabel(b, row.text.get()));
                    r.add(b);
                }
                case TEXT -> {
                    TextField f = new TextField(row.text.get());
                    f.cls("kz-wide");
                    f.maxLength(32767);
                    f.onChange(value -> f.style(x -> x.fg(model.applyText(row, value) ? me.methodial.apothecary.style.Color.hex("#ffffff") : EditorStyles.DANGER)));
                    r.add(EditorUi.heading(row.label), f);
                }
            }
        }
        if (model.selection() == null) {
            r.add(EditorUi.heading("Tips"));
            r.add(text("Click something in the view or the list to edit it. F1 lists every shortcut.").cls("kz-muted"));
        }
    }

    private Node keyDiamond(Row row) {
        if (row.binding == null) {
            return box(s(x -> x.size(12).noShrink()));
        }
        KeyDiamond diamond = new KeyDiamond(() -> model.keyState(row));
        diamond.tooltip("Key this value at the current time. Shift-click removes the key");
        diamond.onClick(() -> {
            if (Screen.hasShiftDown()) {
                model.removeKey(row);
            } else {
                model.writeKey(row, true);
            }
        });
        return diamond;
    }

    private TextField numberField(Row row, int index) {
        return EditorUi.number(() -> row.values()[index], value -> {
            float[] all = row.values();
            all[index] = value;
            model.apply(row, all);
        }, row.step, () -> model.editCheckpoint(row), inspectorSyncers);
    }

    // ------------------------------------------------------------------ shortcuts

    private Node shortcuts() {
        String[][] keys = {
                {"V B E", "Select, Place, Erase"},
                {"G R S", "Move, rotate, scale gizmo"},
                {"Drag", "Paint blocks (Place/Erase)"},
                {"Shift-drag", "Fill or clear a box"},
                {"Q", "Pick the block under the cursor"},
                {"Tab", "Library"},
                {"Right-drag", "Orbit"},
                {"Middle-drag", "Pan"},
                {"Wheel", "Zoom (or nudge a number)"},
                {"F", "Frame the selection"},
                {"0 1 3 7 5", "Iso, front, side, top; lens"},
                {"K", "Key the selection"},
                {"Space", "Play / pause"},
                {"← →  Home", "Step time; to the start"},
                {"Ctrl+D", "Duplicate"},
                {"F2", "Rename"},
                {"H", "Hide / show the part"},
                {"Del", "Delete"},
                {"Ctrl+Z / Y", "Undo / redo"},
                {"Ctrl+S", "Save"},
                {"Ctrl+= - 0", "UI scale"},
                {"Title bars", "Drag to float; drop at an edge to dock"},
                {"Splitters", "Drag to resize the docks"},
                {"Esc", "Leave a field, a window, the tool, the selection, then the editor"}};
        Node list = box(cls("kz-list"));
        for (String[] k : keys) {
            list.add(box(cls("kz-shortcut"), text(k[0]).cls("kz-chip"), text(k[1]).cls("kz-muted")));
        }
        return list;
    }

    // ------------------------------------------------------------------ per frame

    /** Runs once per rendered frame: plays the timeline and brings every control in line with the model. */
    private void frame() {
        long now = Util.getMillis();
        if (lastFrame >= 0) {
            scene.timeline().advance(Math.min(now - lastFrame, 250) / 50.0f);
        }
        lastFrame = now;

        if (layout.structure != builtStructure) {
            builtStructure = layout.structure;
            chrome.invalidate();
        }
        String outline = outlineSignature();
        if (!outline.equals(outlineSignature)) {
            outlineSignature = outline;
            outliner.invalidate();
        }
        String rows = model.rowsSignature() + "@" + model.generation();
        if (!rows.equals(inspectorSignature)) {
            inspectorSignature = rows;
            inspectorSyncers.clear();
            inspector.invalidate();
        }
        String text = model.status();
        if (status != null && !text.equals(lastStatus)) {
            lastStatus = text;
            status.text(text);
            status.toggleCls("error", model.statusIsError());
            if (model.statusIsError()) {
                Toast.show(ui, "error", "Kraz", model.status(), 3);
            }
        }
        syncers.forEach(Runnable::run);
        List.copyOf(chromeSyncers).forEach(Runnable::run);
        List.copyOf(inspectorSyncers).forEach(Runnable::run);
        if (layout.visible(PanelId.WORLD)) {
            world.frame();
        }
        applyDockSizes();
        // Floating windows stay on screen when the window shrinks.
        for (PanelId id : PanelId.values()) {
            if (layout.place(id) == Place.FLOAT && windows.containsKey(id)) {
                placeFloating(id);
            }
        }
    }

    // ------------------------------------------------------------------ closing

    /** Leaves the editor to look at the projection; the session stays and {@code /kraz edit} returns to it. */
    private void viewInWorld() {
        closingForWorld = true;
        minecraft.setScreen(null);
        Minecraft.getInstance().gui.setOverlayMessage(Component.literal("/kraz edit returns to the editor"), false);
    }

    @Override
    public void onClose() {
        if (model.dirty() && !closingForWorld) {
            new Dialog("Unsaved changes")
                    .text("'" + model.sceneName + "' has changes that are not saved.")
                    .button("Save and close", "primary", () -> {
                        model.save();
                        if (!model.dirty()) {
                            super.onClose();
                        }
                    })
                    .button("Discard", "danger", super::onClose)
                    .button("Keep editing", null, () -> { })
                    .open(ui);
            return;
        }
        super.onClose();
    }

    @Override
    public void removed() {
        layout.save();
        super.removed();
    }

    private void confirmNew() {
        if (!model.dirty()) {
            model.newScene();
            return;
        }
        new Dialog("Start a new scene?")
                .text("The changes to '" + model.sceneName + "' are not saved. New can be undone.")
                .button("New scene", "danger", model::newScene)
                .button("Cancel", null, () -> { })
                .open(ui);
    }

    // ------------------------------------------------------------------ keyboard

    @Override
    public boolean keyPressed(int key, int scancode, int mods) {
        if (ui.hasModal()) {
            return super.keyPressed(key, scancode, mods);
        }
        if (ui.focused() instanceof TextField) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                ui.setFocus(null);
                renaming = null;
                return true;
            }
            return super.keyPressed(key, scancode, mods);
        }
        Timeline t = scene.timeline();
        if (hasControlDown()) {
            switch (key) {
                case GLFW.GLFW_KEY_Z -> model.undo();
                case GLFW.GLFW_KEY_Y -> model.redo();
                case GLFW.GLFW_KEY_S -> model.save();
                case GLFW.GLFW_KEY_D -> model.duplicateSelection();
                case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> setUiScale(layout.uiScale + 0.25f);
                case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> setUiScale(layout.uiScale - 0.25f);
                case GLFW.GLFW_KEY_0, GLFW.GLFW_KEY_KP_0 -> setUiScale(1);
                default -> {
                    return super.keyPressed(key, scancode, mods);
                }
            }
            return true;
        }
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                PanelId last = opened.isEmpty() ? null : opened.getLast();
                if (last != null && layout.visible(last)) {
                    togglePanel(last);
                } else if (model.tool != Tool.SELECT) {
                    model.tool = Tool.SELECT;
                } else if (model.selection() != null) {
                    model.select(null);
                } else {
                    onClose();
                }
            }
            case GLFW.GLFW_KEY_V -> model.tool = Tool.SELECT;
            case GLFW.GLFW_KEY_B -> model.tool = Tool.PLACE;
            case GLFW.GLFW_KEY_E -> model.tool = Tool.ERASE;
            case GLFW.GLFW_KEY_G -> model.gizmo = Gizmo.MOVE;
            case GLFW.GLFW_KEY_R -> model.gizmo = Gizmo.ROTATE;
            case GLFW.GLFW_KEY_S -> model.gizmo = Gizmo.SCALE;
            case GLFW.GLFW_KEY_K -> model.keySelection();
            case GLFW.GLFW_KEY_F -> model.frameSelection();
            case GLFW.GLFW_KEY_Q -> viewport.pickBlockUnderMouse();
            case GLFW.GLFW_KEY_TAB -> togglePanel(PanelId.LIBRARY);
            case GLFW.GLFW_KEY_F1 -> togglePanel(PanelId.SHORTCUTS);
            case GLFW.GLFW_KEY_F2 -> startRename();
            case GLFW.GLFW_KEY_H -> {
                if (model.selection() instanceof ScenePart part) {
                    model.checkpoint();
                    part.setVisible(!part.visible());
                }
            }
            case GLFW.GLFW_KEY_0, GLFW.GLFW_KEY_KP_0 -> view(-35, 30);
            case GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_KP_1 -> view(0, 0);
            case GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_KP_3 -> view(90, 0);
            case GLFW.GLFW_KEY_7, GLFW.GLFW_KEY_KP_7 -> view(0, 89.9f);
            case GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_KP_5 -> scene.camera().setOrthographic(!scene.camera().orthographic());
            case GLFW.GLFW_KEY_SPACE -> model.togglePlay();
            case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> model.deleteSelection();
            case GLFW.GLFW_KEY_LEFT -> t.seek(Math.max(0, Math.round(t.time()) - 1));
            case GLFW.GLFW_KEY_RIGHT -> t.seek(Math.round(t.time()) + 1);
            case GLFW.GLFW_KEY_HOME -> t.seek(0);
            default -> {
                return super.keyPressed(key, scancode, mods);
            }
        }
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // Escape is handled above, one step at a time
    }
}
