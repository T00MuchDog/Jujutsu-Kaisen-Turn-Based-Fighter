package com.jjktbf.graphics.ui.editor;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.DragAndDrop;
import com.badlogic.gdx.utils.Align;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.model.move.MovePool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Four-column move selector grouped by assignment state and move pool. */
public class MoveAssignmentPanel extends Table {

    private static final float LIST_HEIGHT = 480f;
    private static final float COLUMN_GAP = 8f;
    private static final float TAG_SCALE = 0.82f;

    public interface Controller {
        List<AssignmentPanel.Item> availableItems(MovePool pool);
        List<AssignmentPanel.Item> learnedItems(MovePool pool);
        boolean canLearn(String id);
        void onLearn(String id);
        void onForget(String id);
        int learnedCount(MovePool pool);
        int learnedLimit(MovePool pool);
    }

    private enum Side {
        AVAILABLE,
        LEARNED
    }

    private record ColumnKey(Side side, MovePool pool) { }

    private static final class MoveColumn {
        private final Side side;
        private final MovePool pool;
        private final Label heading;
        private final TextField search;
        private final Table rows;
        private final ScrollPane scroll;
        private final List<DragAndDrop.Source> dragSources = new ArrayList<>();

        private MoveColumn(
            Side side,
            MovePool pool,
            Label heading,
            TextField search,
            Table rows,
            ScrollPane scroll
        ) {
            this.side = side;
            this.pool = pool;
            this.heading = heading;
            this.search = search;
            this.rows = rows;
            this.scroll = scroll;
        }
    }

    /** Keeps long move labels from widening the scrollable list beyond its viewport. */
    private static final class MoveList extends Table {
        private MoveList(Skin skin) {
            super(skin);
        }

        @Override public float getPrefWidth() {
            return 0f;
        }
    }

    private final Skin skin;
    private final Controller controller;
    private final DragAndDrop dragAndDrop = new DragAndDrop();
    private final Map<ColumnKey, MoveColumn> columns = new java.util.LinkedHashMap<>();

    public MoveAssignmentPanel(Controller controller, Skin skin) {
        super(skin);
        this.controller = controller;
        this.skin = skin;
        top();
        defaults().top();

        add(groupHeading("AVAILABLE")).colspan(2).growX().center().padBottom(6f);
        add(groupHeading("LEARNED")).colspan(2).growX().center().padBottom(6f).row();

        add(buildColumn(Side.AVAILABLE, MovePool.COMBAT_ARTS))
            .growX().uniformX().padRight(COLUMN_GAP);
        add(buildColumn(Side.AVAILABLE, MovePool.JUJUTSU_ARTS))
            .growX().uniformX().padRight(COLUMN_GAP * 2f);
        add(buildColumn(Side.LEARNED, MovePool.COMBAT_ARTS))
            .growX().uniformX().padRight(COLUMN_GAP);
        add(buildColumn(Side.LEARNED, MovePool.JUJUTSU_ARTS))
            .growX().uniformX();

        columns.values().forEach(this::addDropTarget);
        refresh();
    }

    /** Re-read all move lists and counts without replacing the search fields. */
    public void refresh() {
        columns.values().forEach(this::refreshColumn);
    }

    static boolean matchesSearch(AssignmentPanel.Item item, String query) {
        if (query == null || query.isBlank()) return true;
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return contains(item.label, needle)
            || contains(item.sublabel, needle)
            || contains(item.id, needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private Label groupHeading(String text) {
        Label label = new Label(text, skin, "title");
        label.setColor(skin.get("text-dark", Color.class));
        label.setAlignment(Align.center);
        return label;
    }

    private Actor buildColumn(Side side, MovePool pool) {
        Table column = new Table(skin);
        column.top();

        Label heading = new Label(poolLabel(pool), skin);
        heading.setAlignment(Align.center);
        column.add(heading).growX().center().padBottom(6f).row();

        HoverTextField search = new HoverTextField("", skin);
        search.setMessageText("search moves...");
        column.add(search).growX().height(34f).padBottom(6f).row();

        Table rows = new MoveList(skin);
        rows.top().left();
        rows.defaults().growX().padBottom(4f);
        ScrollPane scroll = new AxisLockedScrollPane(rows, skin);
        scroll.setFadeScrollBars(false);
        scroll.setFlickScroll(false);
        scroll.setScrollingDisabled(true, false);
        column.add(scroll).growX().height(LIST_HEIGHT);

        MoveColumn moveColumn = new MoveColumn(side, pool, heading, search, rows, scroll);
        columns.put(new ColumnKey(side, pool), moveColumn);
        search.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                refreshColumn(moveColumn);
            }
        });
        return column;
    }

    private void refreshColumn(MoveColumn column) {
        if (column.side == Side.LEARNED) {
            column.heading.setText(poolLabel(column.pool) + "  "
                + controller.learnedCount(column.pool) + "/"
                + controller.learnedLimit(column.pool));
        }

        List<AssignmentPanel.Item> items = column.side == Side.AVAILABLE
            ? controller.availableItems(column.pool)
            : controller.learnedItems(column.pool);
        String query = column.search.getText();
        column.dragSources.forEach(dragAndDrop::removeSource);
        column.dragSources.clear();
        column.rows.clearChildren();
        boolean anyVisible = false;
        for (AssignmentPanel.Item item : items) {
            if (!matchesSearch(item, query)) continue;
            column.rows.add(makeMoveCard(item, column)).growX().left().row();
            anyVisible = true;
        }
        if (!anyVisible) {
            Label empty = new Label(query == null || query.isBlank() ? "(empty)" : "(no matches)",
                skin, "small");
            empty.setColor(skin.get("text-dim", Color.class));
            column.rows.add(empty).left().pad(6f).row();
        }
        column.scroll.setScrollX(0f);
    }

    private Actor makeMoveCard(AssignmentPanel.Item item, MoveColumn column) {
        Table card = new Table(skin);
        card.setBackground(skin.getDrawable(
            item.locked ? "battle-card-disabled" : "white-panel"));
        card.setClip(true);
        card.pad(6f);

        Label name = new Label(item.label, skin, "small");
        name.setEllipsis(true);
        name.setAlignment(Align.left);
        card.add(name).left().growX().row();

        String details = item.locked && item.lockReason != null
            ? item.lockReason : item.sublabel;
        if (details != null && !details.isEmpty()) {
            Label tags = new Label(details, skin, "small");
            tags.setColor(skin.get("text-dim", Color.class));
            tags.setFontScale(TAG_SCALE / AssetLoader.FONT_OVERSAMPLE);
            tags.setEllipsis(true);
            tags.setAlignment(Align.left);
            card.add(tags).left().growX();
        }

        if (item.locked) {
            name.setColor(skin.get("text-dim", Color.class));
            return card;
        }

        card.addListener(new InputListener() {
            @Override public void enter(
                InputEvent event, float x, float y, int pointer, Actor fromActor
            ) {
                card.setBackground(skin.getDrawable("battle-card-over"));
            }

            @Override public void exit(
                InputEvent event, float x, float y, int pointer, Actor toActor
            ) {
                card.setBackground(skin.getDrawable("white-panel"));
            }
        });
        card.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) {
                if (column.side == Side.AVAILABLE) {
                    if (!controller.canLearn(item.id)) return;
                    controller.onLearn(item.id);
                } else {
                    controller.onForget(item.id);
                }
                refresh();
            }
        });
        DragAndDrop.Source dragSource = new DragAndDrop.Source(card) {
            @Override public DragAndDrop.Payload dragStart(
                InputEvent event, float x, float y, int pointer
            ) {
                DragAndDrop.Payload payload = new DragAndDrop.Payload();
                payload.setObject(new String[] {
                    column.side.name(), column.pool.name(), item.id
                });
                Label dragLabel = new Label(item.label, skin, "small");
                dragLabel.setColor(skin.get("white", Color.class));
                payload.setDragActor(dragLabel);
                return payload;
            }
        };
        dragAndDrop.addSource(dragSource);
        column.dragSources.add(dragSource);
        return card;
    }

    private void addDropTarget(MoveColumn column) {
        dragAndDrop.addTarget(new DragAndDrop.Target(column.scroll) {
            @Override public boolean drag(
                DragAndDrop.Source source,
                DragAndDrop.Payload payload,
                float x,
                float y,
                int pointer
            ) {
                String id = payloadValue(payload, 2);
                return id != null
                    && column.pool.name().equals(payloadValue(payload, 1))
                    && !column.side.name().equals(payloadValue(payload, 0))
                    && (column.side != Side.LEARNED || controller.canLearn(id));
            }

            @Override public void drop(
                DragAndDrop.Source source,
                DragAndDrop.Payload payload,
                float x,
                float y,
                int pointer
            ) {
                String id = payloadValue(payload, 2);
                if (id == null) return;
                if (column.side == Side.LEARNED) controller.onLearn(id);
                else controller.onForget(id);
                refresh();
            }
        });
    }

    private static String payloadValue(DragAndDrop.Payload payload, int index) {
        Object value = payload.getObject();
        return value instanceof String[] values && values.length > index ? values[index] : null;
    }

    private static String poolLabel(MovePool pool) {
        return pool == MovePool.COMBAT_ARTS ? "COMBAT ARTS" : "JUJUTSU ARTS";
    }
}
