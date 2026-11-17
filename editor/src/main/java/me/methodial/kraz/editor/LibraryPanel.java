package me.methodial.kraz.editor;

import me.methodial.apothecary.mc.node.ItemView;
import me.methodial.apothecary.node.Node;
import me.methodial.apothecary.node.Region;
import me.methodial.apothecary.widget.SegmentedControl;
import me.methodial.apothecary.widget.TextField;
import me.methodial.kraz.editor.EditorModel.Tool;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static me.methodial.apothecary.ui.UI.*;

/**
 * The library: every block, item and entity in the game, searchable, as icons. Clicking a block makes it the
 * brush and switches to the Place tool; clicking an item or entity adds it to the scene where the camera looks.
 */
final class LibraryPanel {
    enum Kind {BLOCKS, ITEMS, ENTITIES}

    /** Results shown at once: each tile is a small offscreen render, so the list is kept short. */
    private static final int LIMIT = 60;

    private final EditorModel model;
    private Kind kind = Kind.BLOCKS;
    private String query = "";
    private Region results;

    LibraryPanel(EditorModel model) {
        this.model = model;
    }

    /** The panel's content; the editor puts it in a window. */
    Node content() {
        TextField search = new TextField();
        search.placeholder("Search blocks, items, entities");
        search.cls("kz-wide");
        search.onChange(text -> {
            query = text.trim().toLowerCase(Locale.ROOT);
            results.invalidate();
        });
        SegmentedControl<Kind> kinds = new SegmentedControl<Kind>().option(Kind.BLOCKS, "Blocks").option(Kind.ITEMS, "Items").option(Kind.ENTITIES, "Entities");
        kinds.setQuietly(kind);
        kinds.cls("kz-wide");
        kinds.onChange(k -> {
            kind = k;
            results.invalidate();
        });
        results = new Region(this::fill);
        results.cls("kz-list");
        return box(cls("kz-panel-col"), search, kinds, results,
                text("Blocks become the brush. Items and entities are added where the camera looks.").cls("kz-muted"));
    }

    private void fill(Region r) {
        Node grid = box(cls("kz-grid"));
        int shown = 0;
        int total = 0;
        for (Entry entry : entries()) {
            total++;
            if (shown >= LIMIT) {
                continue;
            }
            shown++;
            ItemView icon = new ItemView(entry.icon);
            icon.decorations(false);
            icon.style(x -> x.full());
            Node tile = box(cls("kz-tile"), icon);
            tile.tooltip(entry.name + "\n" + entry.id);
            if (entry.id.equals(model.idText)) {
                tile.state("selected", true);
            }
            tile.onClick(() -> choose(entry));
            grid.add(tile);
        }
        r.add(grid);
        if (total == 0) {
            r.add(text("Nothing matches.").cls("kz-muted"));
        } else if (total > shown) {
            r.add(text("Showing " + shown + " of " + total + "; type to narrow it down.").cls("kz-muted"));
        }
    }

    private void choose(Entry entry) {
        model.idText = entry.id;
        switch (kind) {
            case BLOCKS -> {
                model.tool = Tool.PLACE;
                model.setStatus("Brush: " + entry.name);
            }
            case ITEMS -> model.addItemElement();
            case ENTITIES -> model.addEntityElement();
        }
        results.invalidate();
    }

    private record Entry(String id, String name, ItemStack icon) {
    }

    private boolean matches(ResourceLocation id, String name) {
        return query.isEmpty() || id.toString().contains(query) || name.toLowerCase(Locale.ROOT).contains(query);
    }

    private List<Entry> entries() {
        List<Entry> list = new ArrayList<>();
        switch (kind) {
            case BLOCKS -> {
                for (Item item : BuiltInRegistries.ITEM) {
                    if (item instanceof BlockItem block) {
                        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block.getBlock());
                        String name = block.getDescription().getString();
                        if (matches(id, name)) {
                            list.add(new Entry(id.toString(), name, new ItemStack(item)));
                        }
                    }
                }
            }
            case ITEMS -> {
                for (Item item : BuiltInRegistries.ITEM) {
                    if (item == Items.AIR || item instanceof BlockItem) {
                        continue;
                    }
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                    String name = item.getDescription().getString();
                    if (matches(id, name)) {
                        list.add(new Entry(id.toString(), name, new ItemStack(item)));
                    }
                }
            }
            case ENTITIES -> {
                for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                    if (type.getCategory() == MobCategory.MISC && type != EntityType.ARMOR_STAND && type != EntityType.VILLAGER
                            && type != EntityType.IRON_GOLEM && type != EntityType.SNOW_GOLEM) {
                        continue; // projectiles, markers and the like
                    }
                    ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                    String name = type.getDescription().getString();
                    if (matches(id, name)) {
                        SpawnEggItem egg = SpawnEggItem.byId(type);
                        ItemStack icon = egg != null ? new ItemStack(egg) : type == EntityType.ARMOR_STAND ? new ItemStack(Items.ARMOR_STAND) : new ItemStack(Items.NAME_TAG);
                        list.add(new Entry(id.toString(), name, icon));
                    }
                }
            }
        }
        return list;
    }
}
