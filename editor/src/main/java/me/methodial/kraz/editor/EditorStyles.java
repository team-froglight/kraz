package me.methodial.kraz.editor;

import me.methodial.apothecary.sheet.Sheet;
import me.methodial.apothecary.style.Color;
import me.methodial.apothecary.style.Length;
import me.methodial.apothecary.style.PointerEvents;
import me.methodial.apothecary.style.Tr;
import me.methodial.apothecary.theme.Motion;
import me.methodial.apothecary.theme.OreUI;
import me.methodial.apothecary.widget.PixelIcon;

import static me.methodial.apothecary.sheet.Selector.*;

/**
 * The editor's look, over the Ore UI kit and in Menuet's manner: the scene fills the screen and the tools float
 * over it on dark glass cards (Menuet's card colour), dark beveled buttons with pixel glyphs, the active tool on
 * the kit's green face, gold for what is selected or keyed, hairlines between rows.
 */
final class EditorStyles {
    /** Menuet's card: its page colour over the stage. */
    static final Color CARD = Color.ofArgb(0xD923232A);
    static final Color HAIRLINE = Color.ofArgb(0x1FFFFFFF);
    static final Color ROW_HOVER = Color.ofArgb(0x14FFFFFF);
    static final Color ROW_SELECTED = Color.ofArgb(0x26FFFFFF);
    static final Color FIELD = Color.ofArgb(0x26000000);
    static final Color GOLD = Color.hex("#d9af4e");
    static final Color MUTED = Color.ofArgb(0x99FFFFFF);
    static final Color DANGER = Color.hex("#e05555");

    private EditorStyles() {
    }

    static Sheet sheet() {
        Sheet s = new Sheet("kraz-editor");

        // Menuet's void behind the scene: deep indigo at the top falling to near black.
        s.rule(cls("kz-page"), x -> x.full().bg(me.methodial.apothecary.style.Paint.linear(180, Color.hex("#1d1b2e"), Color.hex("#0b0a12"))));
        // The 3D view underneath everything.
        s.rule(cls("kz-stage"), x -> x.abs().inset(0));
        // Panels position cards and let clicks between cards fall through to the view.
        // The window, as Blender and Axiom arrange theirs: a menu bar, then docks around the view (each resizable by
        // the splitters between them), the status bar at the bottom, and floating windows over everything.
        // Containers let clicks fall through to the view; only cards, splitters and handles take them.
        s.rule(cls("kz-layer"), x -> x.abs().inset(0).col().itemsStretch().gap(0).pad(3).pointerEvents(PointerEvents.NONE));
        s.rule(cls("kz-card"), x -> x.col().pad(4, 5).gap(3).bg(CARD).minW(0).minH(0).pointerEvents(PointerEvents.AUTO));

        s.rule(cls("kz-top"), x -> x.row().itemsCenter().gap(3).pad(2, 4).wrap().noShrink().mb(3));
        s.on(cls("kz-menu-btn"), r -> r.style(x -> x.pad(3, 6).pointer().noShrink().transition(Tr.colors(0.08f)))
                .hover(x -> x.bg(ROW_HOVER)));
        s.rule(cls("kz-menu-btn").state("open"), x -> x.bg(ROW_SELECTED).fg(GOLD));

        s.rule(cls("kz-middle"), x -> x.row().itemsStretch().gap(0).grow().basis(0).minH(0).pointerEvents(PointerEvents.NONE));
        s.rule(cls("kz-dock"), x -> x.col().itemsStretch().gap(3).noShrink().minH(0).minW(0).pointerEvents(PointerEvents.NONE));
        s.rule(cls("kz-dock").withCls("bottom"), x -> x.row().itemsStretch());
        s.rule(cls("kz-center"), x -> x.row().itemsStart().gap(3).grow().basis(0).minW(0).minH(0).pad(0, 3).clip().pointerEvents(PointerEvents.NONE));

        // Splitters: a few pixels between docks, lit while hovered or dragged.
        s.on(cls("kz-split"), r -> r.style(x -> x.noShrink().pointerEvents(PointerEvents.AUTO).transition(Tr.colors(0.1f)))
                .hover(x -> x.bg(Color.ofArgb(0x40d9af4e))));
        s.rule(cls("kz-split").withCls("v"), x -> x.w(4).cursor(me.methodial.apothecary.style.Cursor.EW_RESIZE));
        s.rule(cls("kz-split").withCls("h"), x -> x.h(4).cursor(me.methodial.apothecary.style.Cursor.NS_RESIZE));

        // Windows: a title bar (drag it to move or undock) over the panel's content.
        s.rule(cls("kz-win"), x -> x.pad(0).gap(0).minH(0).minW(0));
        s.rule(cls("kz-win").in(cls("kz-dock")), x -> x.grow().basis(0));
        s.rule(cls("kz-win-head"), x -> x.row().itemsCenter().gap(4).pad(2, 3, 2, 5).noShrink().bg(Color.ofArgb(0x1AFFFFFF))
                .cursor(me.methodial.apothecary.style.Cursor.MOVE));
        s.rule(cls("kz-win-title"), x -> x.grow().minW(0).ellipsis().fg(GOLD));
        s.rule(cls("kz-win-body"), x -> x.col().grow().basis(0).minH(0).minW(0).pad(4, 5).gap(3));
        s.rule(cls("kz-panel-col"), x -> x.col().grow().minH(0).gap(3));
        s.rule(cls("kz-float"), x -> x.abs().shadow(0, 4, 12, t -> Color.ofArgb(0x80000000)));
        s.rule(cls("kz-floats"), x -> x.abs().inset(0).pointerEvents(PointerEvents.NONE));
        s.rule(cls("kz-grip"), x -> x.abs().right(0).bottom(0).size(9).pointerEvents(PointerEvents.AUTO)
                .cursor(me.methodial.apothecary.style.Cursor.MOVE));
        s.rule(cls("kz-win-btn"), x -> x.size(14).pad(0).noShrink());
        // Where a dragged window would dock.
        s.rule(cls("kz-dock-hint"), x -> x.abs().bg(Color.ofArgb(0x33d9af4e)).border(1, GOLD).pointerEvents(PointerEvents.NONE));

        // Blender's toolbar down the left of the view, and the navigation column on its right.
        // Both wrap into further columns when the view is too short for them, instead of running past it.
        s.rule(cls("kz-tools"), x -> x.col().wrap().itemsCenter().gap(0).pad(2).noShrink().minH(0).set(me.methodial.apothecary.style.Props.MAX_HEIGHT, Length.percent(100)));
        s.rule(cls("kz-nav-col"), x -> x.col().itemsEnd().gap(2).noShrink().minH(0).set(me.methodial.apothecary.style.Props.MAX_HEIGHT, Length.percent(100)).pointerEvents(PointerEvents.NONE).pushRight());
        s.rule(cls("kz-nav"), x -> x.size(70).pointerEvents(PointerEvents.AUTO).pointer());
        s.rule(cls("kz-nav-buttons"), x -> x.col().wrap().gap(0).pad(2).minH(0).shrink(1).pointerEvents(PointerEvents.AUTO));

        // The status bar: mouse hints, the latest message, scene numbers.
        s.rule(cls("kz-status-bar"), x -> x.row().itemsCenter().gap(8).pad(2, 6).noShrink().mt(3).bg(CARD).pointerEvents(PointerEvents.AUTO));
        s.rule(cls("kz-hints"), x -> x.minW(0).ellipsis().opacity(0.75f).shrink(1));
        s.rule(cls("kz-stats"), x -> x.nowrap().noShrink().opacity(0.6f));

        s.rule(cls("kz-group"), x -> x.row().itemsCenter().gap(1));
        s.rule(cls("kz-sep"), x -> x.w(1).h(18).bg(HAIRLINE).mx(2));
        s.rule(cls("kz-hsep"), x -> x.h(1).w(18).bg(HAIRLINE).my(2));
        s.rule(cls("kz-heading"), x -> x.opacity(0.55f).pad(4, 2, 2, 2).noShrink());
        s.rule(cls("kz-title"), x -> x.fg(GOLD).pad(0, 2).noShrink());
        s.rule(cls("kz-status"), x -> x.opacity(0.75f).minW(0).ellipsis().grow().basis(0));
        s.rule(cls("kz-status").withCls("error"), x -> x.fg(DANGER).opacity(1f));
        s.rule(cls("kz-muted"), x -> x.opacity(0.6f));
        s.rule(cls("kz-nowrap"), x -> x.nowrap().noShrink());
        // A hint that gives way first: it takes the leftover width and ends in "…" rather than wrapping.
        s.rule(cls("kz-hint"), x -> x.grow().basis(0).minW(0).ellipsis().textRight());

        // Icon buttons: a field's size, on the kit's dark face; the active tool on the kit's green.
        s.rule(is("pixel-icon"), x -> x.fontSize(PixelIcon.KIT_FRAME));
        s.on(cls("kz-icon"), r -> {
            r.style(OreUI.besideField());
            OreUI.interactive(r, OreUI.GENERAL_DARK);
        });
        s.on(cls("kz-icon").withCls("on"), r -> OreUI.interactive(r, OreUI.COLORED));
        s.on(cls("kz-text"), r -> {
            r.style(x -> x.h(OreUI.fieldButton()).pad(0, 6).m(1, 1, 1 + OreUI.DEPTH, 1).noShrink());
            OreUI.interactive(r, OreUI.GENERAL_DARK);
        });
        s.on(cls("kz-text").withCls("on"), r -> OreUI.interactive(r, OreUI.COLORED));
        s.on(cls("kz-text").withCls("danger"), r -> OreUI.interactive(r, OreUI.DESTRUCTIVE));

        // The current block/item swatch and its id beside it.
        s.rule(cls("kz-swatch"), x -> x.size(OreUI.FIELD_HEIGHT).noShrink().bg(FIELD).pad(2));
        s.rule(cls("kz-field"), x -> x.minW(0));

        // Lists (outliner, inspector, library results).
        s.rule(cls("kz-list"), x -> x.col().grow().minH(0).scrollY().pad(0, 4, 0, 0));
        s.rule(cls("kz-row"), x -> x.row().itemsCenter().gap(5).pad(2, 4).minH(16).noShrink().borderBottom(1, t -> HAIRLINE)
                .transition(Tr.colors(0.1f)));
        s.on(cls("kz-row").withCls("clickable"), r -> r.style(x -> x.pointer()).hover(x -> x.bg(ROW_HOVER)));
        s.rule(cls("kz-row").state("selected"), x -> x.bg(ROW_SELECTED).fg(GOLD));
        s.rule(cls("kz-row").withCls("child"), x -> x.pl(14));
        s.rule(cls("kz-row-label"), x -> x.grow().minW(0).ellipsis());
        s.rule(cls("kz-row-eye"), x -> x.opacity(0.7f).noShrink().pointer());

        // Inspector rows: label left, field right; vectors put their three fields under the label.
        s.rule(cls("kz-prop"), x -> x.row().itemsCenter().gap(4).pad(2, 0).noShrink());
        s.rule(cls("kz-prop").withCls("vector"), x -> x.col().itemsStretch().gap(2));
        s.rule(cls("kz-prop-head"), x -> x.row().itemsCenter().gap(4));
        s.rule(cls("kz-prop-label"), x -> x.grow().minW(0).ellipsis().opacity(0.85f));
        s.rule(cls("kz-prop-fields"), x -> x.row().gap(2));
        s.rule(cls("kz-num"), x -> x.w(56).noShrink());
        s.rule(cls("kz-num").in(cls("kz-prop-fields")), x -> x.grow().basis(0).minW(0).w(Length.AUTO));
        s.rule(cls("kz-axis"), x -> x.fontSize(7).w(6).noShrink());
        s.rule(cls("kz-axis").withCls("x"), x -> x.fg(Color.hex("#ff6b6b")));
        s.rule(cls("kz-axis").withCls("y"), x -> x.fg(Color.hex("#7be07b")));
        s.rule(cls("kz-axis").withCls("z"), x -> x.fg(Color.hex("#6ba6ff")));
        s.rule(cls("kz-wide"), x -> x.w(Length.percent(100)));

        // Keyframe diamonds beside animatable values.
        s.rule(cls("kz-key"), x -> x.size(12).noShrink().pointer());

        // Library tiles.
        s.rule(cls("kz-grid"), x -> x.grid().cols(6).gap(2).noShrink());
        s.on(cls("kz-tile"), r -> r.style(x -> x.aspect(1).pad(2).bg(FIELD).border(1, Color.ofArgb(0)).pointer()
                        .transition(Tr.colors(0.08f), Tr.transform(0.08f, Motion.OUT)))
                .hover(x -> x.bg(ROW_HOVER).borderColor(Color.ofArgb(0x66FFFFFF))));
        s.rule(cls("kz-tile").state("selected"), x -> x.border(1, GOLD));

        // Help: shortcut chips.
        s.rule(cls("kz-shortcut"), x -> x.row().gap(6).itemsCenter().pad(1, 0));
        s.rule(cls("kz-chip"), x -> x.pad(1, 4).bg(FIELD).border(1, OreUI.OUTLINE).noShrink().minW(28).textCenter());
        return s;
    }
}
