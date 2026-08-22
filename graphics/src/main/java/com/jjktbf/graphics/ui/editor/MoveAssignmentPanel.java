package com.jjktbf.graphics.ui.editor;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector2;
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
import com.jjktbf.graphics.audio.SoundCue;
import com.jjktbf.graphics.ui.profile.UiProfile;
import com.jjktbf.model.move.MovePool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

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
        void onReorder(MovePool pool, List<String> orderedIds);
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
        private List<AssignmentPanel.Item> loadedItems = List.of();

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
    private final Consumer<SoundCue> soundPlayer;
    private final boolean windowsLayout;
    private final DragAndDrop dragAndDrop = new DragAndDrop();
    private final Map<ColumnKey, MoveColumn> columns = new java.util.LinkedHashMap<>();

    public MoveAssignmentPanel(
        Controller controller,
        Consumer<SoundCue> soundPlayer,
        UiProfile uiProfile,
        Skin skin
    ) {
        super(skin);
        this.controller = controller;
        this.soundPlayer = soundPlayer == null ? cue -> { } : soundPlayer;
        this.windowsLayout = uiProfile == UiProfile.WINDOWS;
        this.skin = skin;
        top();
        defaults().top();

        if (columnRowCount(uiProfile) == 2) {
            add(groupHeading("AVAILABLE")).colspan(2).growX().center().padBottom(6f).row();
            add(buildColumn(Side.AVAILABLE, MovePool.COMBAT_ARTS))
                .growX().uniformX().minWidth(0f).padRight(COLUMN_GAP);
            add(buildColumn(Side.AVAILABLE, MovePool.JUJUTSU_ARTS))
                .growX().uniformX().minWidth(0f).row();
            add(groupHeading("LEARNED (DRAG TO REORDER)"))
                .colspan(2).growX().center().padTop(8f).padBottom(6f).row();
            add(buildColumn(Side.LEARNED, MovePool.COMBAT_ARTS))
                .growX().uniformX().minWidth(0f).padRight(COLUMN_GAP);
            add(buildColumn(Side.LEARNED, MovePool.JUJUTSU_ARTS))
                .growX().uniformX().minWidth(0f);
        } else {
            add(groupHeading("AVAILABLE")).colspan(2).growX().center().padBottom(6f);
            add(groupHeading("LEARNED (DRAG TO REORDER)"))
                .colspan(2).growX().center().padBottom(6f).row();

            add(buildColumn(Side.AVAILABLE, MovePool.COMBAT_ARTS))
                .growX().uniformX().padRight(COLUMN_GAP);
            add(buildColumn(Side.AVAILABLE, MovePool.JUJUTSU_ARTS))
                .growX().uniformX().padRight(COLUMN_GAP * 2f);
            add(buildColumn(Side.LEARNED, MovePool.COMBAT_ARTS))
                .growX().uniformX().padRight(COLUMN_GAP);
            add(buildColumn(Side.LEARNED, MovePool.JUJUTSU_ARTS))
                .growX().uniformX();
        }

        columns.values().forEach(this::addDropTarget);
        refresh();
    }

    /** Re-read all move lists and counts without replacing the search fields. */
    public void refresh() {
        columns.values().forEach(this::reloadColumn);
    }

    static boolean matchesSearch(AssignmentPanel.Item item, String query) {
        if (query == null || query.isBlank()) return true;
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return contains(item.label, needle)
            || contains(item.sublabel, needle)
            || contains(item.id, needle);
    }

    static int columnRowCount(UiProfile uiProfile) {
        return uiProfile == UiProfile.WINDOWS ? 2 : 1;
    }

    static List<Integer> matchingItemIndices(
        List<AssignmentPanel.Item> loadedItems, String query
    ) {
        List<Integer> indices = new ArrayList<>();
        for (int index = 0; index < loadedItems.size(); index++) {
            if (matchesSearch(loadedItems.get(index), query)) indices.add(index);
        }
        return indices;
    }

    static boolean moveToInsertionIndex(List<String> ids, int sourceIndex, int insertionIndex) {
        if (ids == null || sourceIndex < 0 || sourceIndex >= ids.size()
            || insertionIndex < 0 || insertionIndex > ids.size()) return false;

        List<String> original = new ArrayList<>(ids);
        String movedId = ids.remove(sourceIndex);
        if (sourceIndex < insertionIndex) insertionIndex--;
        ids.add(insertionIndex, movedId);
        return !ids.equals(original);
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
        column.add(search).growX().height(windowsLayout ? 51f : 34f).padBottom(6f).row();

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
                rebuildRows(moveColumn);
            }
        });
        return column;
    }

    private void reloadColumn(MoveColumn column) {
        if (column.side == Side.LEARNED) {
            column.heading.setText(poolLabel(column.pool) + "  "
                + controller.learnedCount(column.pool) + "/"
                + controller.learnedLimit(column.pool));
        }

        column.loadedItems = List.copyOf(column.side == Side.AVAILABLE
            ? controller.availableItems(column.pool)
            : controller.learnedItems(column.pool));
        rebuildRows(column);
    }

    private void rebuildRows(MoveColumn column) {
        String query = column.search.getText();
        column.dragSources.forEach(dragAndDrop::removeSource);
        column.dragSources.clear();
        column.rows.clearChildren();
        List<Integer> matchingIndices = matchingItemIndices(column.loadedItems, query);
        for (int index : matchingIndices) {
            AssignmentPanel.Item item = column.loadedItems.get(index);
            column.rows.add(makeMoveCard(item, column, index)).growX().left().row();
        }
        if (matchingIndices.isEmpty()) {
            Label empty = new Label(query == null || query.isBlank() ? "(empty)" : "(no matches)",
                skin, "small");
            empty.setColor(skin.get("text-dim", Color.class));
            column.rows.add(empty).left().pad(6f).row();
        }
        column.scroll.setScrollX(0f);
    }

    private Actor makeMoveCard(AssignmentPanel.Item item, MoveColumn column, int itemIndex) {
        Table card = new Table(skin);
        card.setUserObject(itemIndex);
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
                    if (!controller.canLearn(item.id)) {
                        soundPlayer.accept(SoundCue.UI_DENIED);
                        return;
                    }
                    controller.onLearn(item.id);
                    soundPlayer.accept(SoundCue.UI_CONFIRM);
                } else {
                    controller.onForget(item.id);
                    soundPlayer.accept(SoundCue.UI_DELETE);
                }
                refresh();
            }
        });
        DragAndDrop.Source dragSource = new DragAndDrop.Source(card) {
            @Override public DragAndDrop.Payload dragStart(
                InputEvent event, float x, float y, int pointer
            ) {
                soundPlayer.accept(SoundCue.UI_PICKUP);
                DragAndDrop.Payload payload = new DragAndDrop.Payload();
                payload.setObject(new String[] {
                    column.side.name(), column.pool.name(), item.id, String.valueOf(itemIndex)
                });
                Label dragLabel = new Label(item.label, skin, "small");
                dragLabel.setColor(skin.get("white", Color.class));
                payload.setDragActor(dragLabel);
                return payload;
            }

            @Override public void dragStop(
                InputEvent event,
                float x,
                float y,
                int pointer,
                DragAndDrop.Payload payload,
                DragAndDrop.Target target
            ) {
                if (target == null) soundPlayer.accept(SoundCue.UI_DENIED);
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
                if (!column.pool.name().equals(payloadValue(payload, 1))) {
                    return false;
                }
                String sourceSide = payloadValue(payload, 0);
                if (column.side == Side.LEARNED && Side.LEARNED.name().equals(sourceSide)) {
                    return column.search.getText().isBlank() && payloadIndex(payload) >= 0;
                }
                return id != null && !column.side.name().equals(sourceSide)
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
                if (column.side == Side.LEARNED
                    && Side.LEARNED.name().equals(payloadValue(payload, 0))) {
                    List<String> orderedIds = new ArrayList<>();
                    for (AssignmentPanel.Item item : controller.learnedItems(column.pool)) {
                        orderedIds.add(item.id);
                    }
                    if (moveToInsertionIndex(
                        orderedIds, payloadIndex(payload), insertionIndex(column, x, y)
                    )) {
                        controller.onReorder(column.pool, orderedIds);
                    }
                } else if (id != null && column.side == Side.LEARNED) {
                    controller.onLearn(id);
                } else if (id != null) {
                    controller.onForget(id);
                }
                soundPlayer.accept(SoundCue.UI_DROP);
                refresh();
            }
        });
    }

    private static int insertionIndex(MoveColumn column, float x, float y) {
        Vector2 point = column.scroll.localToActorCoordinates(column.rows, new Vector2(x, y));
        for (Actor actor : column.rows.getChildren()) {
            if (!(actor.getUserObject() instanceof Integer index)) continue;
            if (point.y >= actor.getY() + actor.getHeight() / 2f) return index;
        }
        return controllerSize(column);
    }

    private static int controllerSize(MoveColumn column) {
        int size = 0;
        for (Actor actor : column.rows.getChildren()) {
            if (actor.getUserObject() instanceof Integer index) size = Math.max(size, index + 1);
        }
        return size;
    }

    private static String payloadValue(DragAndDrop.Payload payload, int index) {
        Object value = payload.getObject();
        return value instanceof String[] values && values.length > index ? values[index] : null;
    }

    private static int payloadIndex(DragAndDrop.Payload payload) {
        try {
            return Integer.parseInt(payloadValue(payload, 3));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static String poolLabel(MovePool pool) {
        return pool == MovePool.COMBAT_ARTS ? "COMBAT ARTS" : "JUJUTSU ARTS";
    }
}
