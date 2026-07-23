package com.jjktbf.graphics.ui.editor;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.VerticalGroup;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.DragAndDrop;
import com.badlogic.gdx.utils.Align;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Two-column assignment widget: AVAILABLE (left) and ASSIGNED (right), with
 * drag-and-drop and click-to-toggle.
 *
 * Used by the Character editor for both moves (slot-gated, eligibility-filtered)
 * and abilities (source/conflict-gated). The screen supplies a {@link Controller} that owns
 * the data and the gating rules; this widget is purely presentational.
 *
 * Interaction:
 *   - Click an available row  → asks controller to assign (may be rejected by slot gate).
 *   - Click an assigned row   → unassigns.
 *   - Drag from AVAILABLE → drop on ASSIGNED column → assign.
 *   - Drag from ASSIGNED  → drop on AVAILABLE column → unassign.
 *
 * After any change the controller's lists are re-read and both columns refresh.
 */
public class AssignmentPanel extends Table {

    /** A selectable item shown in either column. */
    public static final class Item {
        public final String id;
        public final String label;
        public final String sublabel; // optional second line (e.g. tags, type)
        /** When true, the row renders greyed-out, is unclickable, un-draggable, and un-hoverable.
         *  Used to show technique moves whose CTM prerequisite the character hasn't met. */
        public final boolean locked;
        /** Optional explanation shown as the sublabel when {@link #locked} is true. */
        public final String lockReason;
        public Item(String id, String label, String sublabel) {
            this(id, label, sublabel, false, null);
        }
        public Item(String id, String label, String sublabel, boolean locked, String lockReason) {
            this.id = id; this.label = label; this.sublabel = sublabel;
            this.locked = locked; this.lockReason = lockReason;
        }
    }

    /** Data + rules source. All methods are re-queried on every refresh. */
    public interface Controller {
        List<Item> availableItems();
        List<Item> assignedItems();
        boolean canAssign(String id);     // slot/source/conflict gate
        void onAssign(String id);
        void onUnassign(String id);
        String budgetSummary();           // text shown above the columns, or ""
    }

    private final Skin skin;
    private final Controller controller;
    private final DragAndDrop dnd = new DragAndDrop();

    private final VerticalGroup availableCol;
    private final VerticalGroup assignedCol;
    private final Label budgetLabel;
    private final Label hintLabel;

    public AssignmentPanel(Controller controller, Skin skin) {
        super(skin);
        this.skin = skin;
        this.controller = controller;
        defaults().pad(4);

        // Title + budget summary
        budgetLabel = new Label("", skin, "small");
        budgetLabel.setColor(skin.get("text-dim", com.badlogic.gdx.graphics.Color.class));
        add(budgetLabel).colspan(2).left().row();

        hintLabel = new Label("Click to toggle  •  Drag between columns", skin, "small");
        hintLabel.setColor(skin.get("text-dim", com.badlogic.gdx.graphics.Color.class));
        add(hintLabel).colspan(2).left().padBottom(4).row();

        // Column headers
        Label availableHeader = new Label("AVAILABLE", skin);
        availableHeader.setColor(skin.get("text-dark", com.badlogic.gdx.graphics.Color.class));
        Label assignedHeader = new Label("ASSIGNED", skin);
        assignedHeader.setColor(skin.get("text-dark", com.badlogic.gdx.graphics.Color.class));
        add(availableHeader).left().padRight(8);
        add(assignedHeader).left().row();

        // Two scrollable columns; rows fill the column width so the cards align.
        availableCol = new VerticalGroup();
        availableCol.columnLeft();
        availableCol.grow();
        availableCol.space(4f);
        assignedCol  = new VerticalGroup();
        assignedCol.columnLeft();
        assignedCol.grow();
        assignedCol.space(4f);

        ScrollPane availableScroll = new AxisLockedScrollPane(availableCol, skin);
        ScrollPane assignedScroll  = new AxisLockedScrollPane(assignedCol,  skin);
        availableScroll.setFadeScrollBars(false);
        assignedScroll.setFadeScrollBars(false);
        availableScroll.setScrollingDisabled(true, false);
        assignedScroll.setScrollingDisabled(true, false);

        add(availableScroll).grow().width(220).padRight(8).top();
        add(assignedScroll).grow().width(220).top();

        // Wire both columns as DnD targets. Payload.getObject() is a String[]
        // of { side, id } packed in dragStart().
        dnd.addTarget(new DragAndDrop.Target(availableScroll) {
            @Override
            public boolean drag(DragAndDrop.Source source, DragAndDrop.Payload payload,
                                float x, float y, int pointer) {
                return "assigned".equals(sideOf(payload));
            }
            @Override
            public void drop(DragAndDrop.Source source, DragAndDrop.Payload payload,
                             float x, float y, int pointer) {
                String id = idOf(payload);
                if (id != null) { controller.onUnassign(id); refresh(); }
            }
        });
        dnd.addTarget(new DragAndDrop.Target(assignedScroll) {
            @Override
            public boolean drag(DragAndDrop.Source source, DragAndDrop.Payload payload,
                                float x, float y, int pointer) {
                return "available".equals(sideOf(payload))
                    && controller.canAssign(idOf(payload));
            }
            @Override
            public void drop(DragAndDrop.Source source, DragAndDrop.Payload payload,
                             float x, float y, int pointer) {
                String id = idOf(payload);
                if (id != null) { controller.onAssign(id); refresh(); }
            }
        });

        refresh();
    }

    /** Re-read the controller and rebuild both columns. */
    public void refresh() {
        budgetLabel.setText(controller.budgetSummary());
        rebuildColumn(availableCol, controller.availableItems(), "available");
        rebuildColumn(assignedCol,  controller.assignedItems(),  "assigned");
    }

    private void rebuildColumn(VerticalGroup col, List<Item> items, String side) {
        col.clear();
        if (items.isEmpty()) {
            Label empty = new Label("(empty)", skin, "small");
            empty.setColor(skin.get("text-dim", com.badlogic.gdx.graphics.Color.class));
            col.addActor(empty);
            return;
        }
        for (Item it : items) {
            col.addActor(makeRow(it, side));
        }
    }

    private Actor makeRow(Item item, String side) {
        Table row = new Table(skin);
        row.setBackground(skin.getDrawable(item.locked ? "battle-card-disabled" : "white-panel"));
        row.pad(5);

        final Label name = new Label(item.label, skin, "small");
        name.setAlignment(Align.left);
        row.add(name).left().growX().row();
        final Label sub;
        String subText = item.locked && item.lockReason != null
            ? (item.lockReason) : item.sublabel;
        if (subText != null && !subText.isEmpty()) {
            sub = new Label(subText, skin, "small");
            sub.setColor(skin.get("text-dim", com.badlogic.gdx.graphics.Color.class));
            sub.setAlignment(Align.left);
            row.add(sub).left().growX();
        } else {
            sub = null;
        }

        // LOCKED rows: greyed-out, no hover, no click, no drag. They exist only
        // to show progression-gated items (e.g. a technique move whose CTM the
        // character hasn't reached yet).
        if (item.locked) {
            com.badlogic.gdx.graphics.Color dim = skin.get("text-dim", com.badlogic.gdx.graphics.Color.class);
            name.setColor(dim);
            return row;
        }

        // Hover highlight: the row's border glows yellow (same treatment as
        // hovered text boxes), matching the battle-card hover state.
        row.addListener(new InputListener() {
            @Override public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                row.setBackground(skin.getDrawable("battle-card-over"));
            }
            @Override public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                row.setBackground(skin.getDrawable("white-panel"));
            }
        });

        // Click to toggle
        row.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                if ("available".equals(side)) {
                    if (controller.canAssign(item.id)) {
                        controller.onAssign(item.id);
                        refresh();
                    }
                } else {
                    controller.onUnassign(item.id);
                    refresh();
                }
            }
        });

        // Drag source — payload packs {side, id} so targets can read both.
        dnd.addSource(new DragAndDrop.Source(row) {
            @Override
            public DragAndDrop.Payload dragStart(InputEvent event, float x, float y, int pointer) {
                DragAndDrop.Payload p = new DragAndDrop.Payload();
                p.setObject(new String[]{ side, item.id });
                // Drag avatar: a small label
                Label dragLbl = new Label(item.label, skin, "small");
                dragLbl.setColor(skin.get("white", com.badlogic.gdx.graphics.Color.class));
                p.setDragActor(dragLbl);
                return p;
            }
        });

        return row;
    }

    /** Pull the source-side string out of a {side, id} payload. */
    private static String sideOf(DragAndDrop.Payload p) {
        Object o = p.getObject();
        return (o instanceof String[] arr && arr.length >= 1) ? arr[0] : null;
    }

    /** Pull the item-id string out of a {side, id} payload. */
    private static String idOf(DragAndDrop.Payload p) {
        Object o = p.getObject();
        return (o instanceof String[] arr && arr.length >= 2) ? arr[1] : null;
    }
}
