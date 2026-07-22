package com.jjktbf.graphics.ui.editor;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.StatKey;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.technique.InnateTechniqueData;
import com.jjktbf.model.technique.SkillTreeNodeData;
import com.jjktbf.model.technique.SkillTreePrerequisiteData;
import com.jjktbf.model.technique.TechniqueSkillTree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Interactive, horizontally scrollable skill-tree board used by both editors. */
public class SkillTreeCanvas extends WidgetGroup {

    public static final float VIEW_HEIGHT = 500f;
    private static final float NODE_WIDTH = 270f;
    private static final float NODE_HEIGHT = 116f;
    private static final float MIN_WIDTH = 1800f;
    private static final float BOARD_PADDING = 60f;
    private static final float LINE_WIDTH = 4f;
    private static final float CONNECTION_HIT_RADIUS = 10f;
    private static final float DRAG_THRESHOLD = 5f;

    private final InnateTechniqueData technique;
    private final CharacterData character;
    private final boolean editable;
    private final Runnable onChanged;
    private final Consumer<String> onStatus;
    private final Function<SkillTreeNodeData, String> activationError;
    private final Skin skin;
    private final Map<String, MoveData> movesById = new LinkedHashMap<>();
    private final Map<String, AbilityData> abilitiesById = new LinkedHashMap<>();
    private final Map<String, NodeView> viewsByNodeId = new LinkedHashMap<>();
    private final Drawable background;
    private final Drawable connector;

    private SkillTreeNodeData attachingSource;
    private Table contextMenu;
    private InputListener dismissContextMenuListener;
    private float preferredWidth = MIN_WIDTH;

    public SkillTreeCanvas(
        InnateTechniqueData technique,
        List<MoveData> moves,
        List<AbilityData> abilities,
        CharacterData character,
        boolean editable,
        Runnable onChanged,
        Consumer<String> onStatus,
        Function<SkillTreeNodeData, String> activationError,
        Skin skin
    ) {
        this.technique = Objects.requireNonNull(technique);
        this.character = character;
        this.editable = editable;
        this.onChanged = onChanged == null ? () -> { } : onChanged;
        this.onStatus = onStatus == null ? ignored -> { } : onStatus;
        this.activationError = activationError == null ? ignored -> null : activationError;
        this.skin = Objects.requireNonNull(skin);
        this.background = skin.getDrawable("battle-palette");
        this.connector = skin.getDrawable("white-pixel");
        setTouchable(Touchable.enabled);

        if (moves != null) moves.stream().filter(Objects::nonNull)
            .filter(move -> move.id != null).forEach(move -> movesById.put(move.id, move));
        if (abilities != null) abilities.stream().filter(Objects::nonNull)
            .filter(ability -> ability.id != null)
            .forEach(ability -> abilitiesById.put(ability.id, ability));

        rebuildNodes();
        addListener(new InputListener() {
            @Override public boolean touchDown(
                InputEvent event, float x, float y, int pointer, int button
            ) {
                if (!editable || button != Input.Buttons.RIGHT) return false;
                AttachedConnection connection = attachedConnectionAt(x, y);
                if (connection == null) return false;
                removeConnection(connection);
                event.stop();
                return true;
            }
        });
    }

    @Override
    public float getPrefWidth() {
        return preferredWidth;
    }

    @Override
    public float getPrefHeight() {
        return VIEW_HEIGHT;
    }

    @Override
    public void layout() {
        for (NodeView view : viewsByNodeId.values()) {
            view.setBounds(view.node.x, view.node.y, NODE_WIDTH, NODE_HEIGHT);
        }
    }

    @Override
    protected void drawChildren(Batch batch, float parentAlpha) {
        background.draw(batch, 0f, 0f, getWidth(), getHeight());
        drawConnectors(batch);
        super.drawChildren(batch, parentAlpha);
    }

    @Override
    public boolean remove() {
        hideContextMenu();
        return super.remove();
    }

    private void rebuildNodes() {
        clearChildren();
        viewsByNodeId.clear();
        if (technique.skillTree == null) return;
        for (SkillTreeNodeData node : technique.skillTree) {
            if (node == null || node.id == null) continue;
            NodeView view = new NodeView(node);
            viewsByNodeId.put(node.id, view);
            addActor(view);
        }
        recalculateWidth();
        setSize(preferredWidth, VIEW_HEIGHT);
        invalidateHierarchy();
    }

    private void recalculateWidth() {
        float furthestRight = 0f;
        if (technique.skillTree != null) {
            for (SkillTreeNodeData node : technique.skillTree) {
                if (node != null) furthestRight = Math.max(furthestRight, node.x + NODE_WIDTH);
            }
        }
        preferredWidth = Math.max(MIN_WIDTH, furthestRight + BOARD_PADDING);
    }

    private void drawConnectors(Batch batch) {
        if (technique.skillTree == null) return;
        for (SkillTreeNodeData target : technique.skillTree) {
            if (target == null || target.prerequisites == null) continue;
            for (SkillTreePrerequisiteData requirement : target.prerequisites) {
                if (requirement == null || !requirement.hasAttachment()) continue;
                SkillTreeNodeData source = TechniqueSkillTree.nodeById(technique, requirement.nodeId);
                if (source == null) continue;
                float startX = source.x + NODE_WIDTH;
                float startY = source.y + NODE_HEIGHT / 2f;
                float endX = target.x;
                float endY = target.y + NODE_HEIGHT / 2f;
                float middleX = (startX + endX) / 2f;
                drawHorizontal(batch, startX, middleX, startY);
                drawVertical(batch, middleX, startY, endY);
                drawHorizontal(batch, middleX, endX, endY);
            }
        }
    }

    private void drawHorizontal(Batch batch, float fromX, float toX, float y) {
        connector.draw(batch, Math.min(fromX, toX), y - LINE_WIDTH / 2f,
            Math.max(LINE_WIDTH, Math.abs(toX - fromX)), LINE_WIDTH);
    }

    private void drawVertical(Batch batch, float x, float fromY, float toY) {
        connector.draw(batch, x - LINE_WIDTH / 2f, Math.min(fromY, toY),
            LINE_WIDTH, Math.max(LINE_WIDTH, Math.abs(toY - fromY)));
    }

    private AttachedConnection attachedConnectionAt(float x, float y) {
        if (technique.skillTree == null) return null;
        for (SkillTreeNodeData target : technique.skillTree) {
            if (target == null || target.prerequisites == null) continue;
            for (SkillTreePrerequisiteData requirement : target.prerequisites) {
                if (requirement == null || !requirement.hasAttachment()) continue;
                SkillTreeNodeData source = TechniqueSkillTree.nodeById(technique, requirement.nodeId);
                if (source == null) continue;
                float startX = source.x + NODE_WIDTH;
                float startY = source.y + NODE_HEIGHT / 2f;
                float endX = target.x;
                float endY = target.y + NODE_HEIGHT / 2f;
                float middleX = (startX + endX) / 2f;
                if (hitsHorizontalConnection(x, y, startX, middleX, startY)
                    || hitsVerticalConnection(x, y, middleX, startY, endY)
                    || hitsHorizontalConnection(x, y, middleX, endX, endY)) {
                    return new AttachedConnection(source, target, requirement);
                }
            }
        }
        return null;
    }

    private static boolean hitsHorizontalConnection(
        float x, float y, float fromX, float toX, float lineY
    ) {
        return x >= Math.min(fromX, toX) - CONNECTION_HIT_RADIUS
            && x <= Math.max(fromX, toX) + CONNECTION_HIT_RADIUS
            && Math.abs(y - lineY) <= CONNECTION_HIT_RADIUS;
    }

    private static boolean hitsVerticalConnection(
        float x, float y, float lineX, float fromY, float toY
    ) {
        return y >= Math.min(fromY, toY) - CONNECTION_HIT_RADIUS
            && y <= Math.max(fromY, toY) + CONNECTION_HIT_RADIUS
            && Math.abs(x - lineX) <= CONNECTION_HIT_RADIUS;
    }

    private void removeConnection(AttachedConnection connection) {
        connection.target.prerequisites.removeIf(requirement -> requirement == connection.requirement);
        onStatus.accept("Removed connection from " + contentName(connection.source)
            + " to " + contentName(connection.target) + ".");
        onChanged.run();
    }

    private void toggleNode(SkillTreeNodeData node) {
        if (character == null) return;
        if (TechniqueSkillTree.isActive(node, character)) {
            TechniqueSkillTree.setActive(node, character, false);
            TechniqueSkillTree.pruneLockedSelections(technique, character);
            onStatus.accept(contentName(node) + " deactivated.");
        } else {
            List<String> unmet = TechniqueSkillTree.unmetPrerequisites(technique, node, character);
            if (!unmet.isEmpty()) {
                onStatus.accept(String.join("; ", unmet));
                return;
            }
            String conflict = activationError.apply(node);
            if (conflict != null) {
                onStatus.accept(conflict);
                return;
            }
            TechniqueSkillTree.setActive(node, character, true);
            onStatus.accept(contentName(node) + " activated.");
        }
        refreshVisualStates();
        onChanged.run();
    }

    private void attachTo(SkillTreeNodeData target) {
        if (attachingSource == null) return;
        if (target.prerequisites == null) target.prerequisites = new ArrayList<>();
        SkillTreePrerequisiteData existing = target.prerequisites.stream()
            .filter(Objects::nonNull)
            .filter(requirement -> SkillTreePrerequisiteData.NODE.equalsIgnoreCase(requirement.type))
            .filter(requirement -> Objects.equals(attachingSource.id, requirement.nodeId))
            .findFirst().orElse(null);
        if (existing == null) {
            existing = new SkillTreePrerequisiteData();
            existing.type = SkillTreePrerequisiteData.NODE;
            existing.nodeId = attachingSource.id;
            target.prerequisites.add(existing);
        }
        existing.attached = true;
        onStatus.accept("Attached " + contentName(attachingSource) + " to " + contentName(target) + ".");
        attachingSource = null;
        onChanged.run();
    }

    private void refreshVisualStates() {
        viewsByNodeId.values().forEach(NodeView::refreshState);
    }

    private void showContextMenu(SkillTreeNodeData node, float stageX, float stageY) {
        hideContextMenu();
        attachingSource = null;
        Stage stage = getStage();
        if (stage == null) return;

        Table menu = new Table(skin);
        menu.setBackground(skin.getDrawable("battle-card"));
        menu.pad(6f);
        menu.defaults().growX().pad(2f);
        Label heading = new Label(contentName(node), skin, "small");
        heading.setEllipsis(true);
        menu.add(heading).width(245f).row();
        TextButton attach = new TextButton("Attach it to another node", skin);
        attach.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                attachingSource = node;
                hideContextMenu();
                onStatus.accept("Click the node that should require " + contentName(node) + ".");
            }
        });
        menu.add(attach).row();
        TextButton prerequisites = new TextButton("Add a prerequisite", skin);
        prerequisites.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                hideContextMenu();
                openPrerequisiteManager(node);
            }
        });
        menu.add(prerequisites).row();
        menu.pack();
        menu.setPosition(
            MathUtils.clamp(stageX, 0f, Math.max(0f, stage.getWidth() - menu.getWidth())),
            MathUtils.clamp(stageY - menu.getHeight(), 0f,
                Math.max(0f, stage.getHeight() - menu.getHeight())));
        stage.addActor(menu);
        contextMenu = menu;

        dismissContextMenuListener = new InputListener() {
            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                if (contextMenu != null && !contextMenu.isAscendantOf(event.getTarget())) {
                    hideContextMenu();
                }
                return false;
            }
        };
        stage.addCaptureListener(dismissContextMenuListener);
    }

    private void hideContextMenu() {
        if (contextMenu != null) {
            contextMenu.remove();
            contextMenu = null;
        }
        Stage stage = getStage();
        if (stage != null && dismissContextMenuListener != null) {
            stage.removeCaptureListener(dismissContextMenuListener);
        }
        dismissContextMenuListener = null;
    }

    private void openPrerequisiteManager(SkillTreeNodeData node) {
        if (node.prerequisites == null) node.prerequisites = new ArrayList<>();
        Dialog dialog = new Dialog("Prerequisites: " + contentName(node), skin);
        Container<Actor> listContainer = new Container<>();
        listContainer.fill(true, false);
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> listContainer.setActor(prerequisiteList(node, refresh[0]));
        refresh[0].run();

        dialog.getContentTable().add(listContainer).width(600f).growX().pad(6f).row();
        TextButton add = new TextButton("+ Add prerequisite", skin);
        add.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                openPrerequisiteEditor(node, -1, refresh[0]);
            }
        });
        dialog.getContentTable().add(add).left().pad(6f).row();
        dialog.button("Close");
        dialog.show(getStage());
    }

    private Actor prerequisiteList(SkillTreeNodeData node, Runnable refresh) {
        Table list = new Table(skin);
        list.defaults().left().pad(3f);
        if (node.prerequisites.isEmpty()) {
            Label empty = new Label("(none)", skin, "small");
            empty.setColor(skin.get("text-dim", Color.class));
            list.add(empty).row();
            return list;
        }
        for (int index = 0; index < node.prerequisites.size(); index++) {
            int requirementIndex = index;
            SkillTreePrerequisiteData requirement = node.prerequisites.get(index);
            list.add(new Label(describe(requirement), skin, "small")).growX();
            TextButton edit = new TextButton("Edit", skin);
            edit.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    openPrerequisiteEditor(node, requirementIndex, refresh);
                }
            });
            list.add(edit);
            TextButton remove = new TextButton("X", skin);
            remove.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    node.prerequisites.remove(requirementIndex);
                    refresh.run();
                    onChanged.run();
                }
            });
            list.add(remove).row();
        }
        return list;
    }

    private void openPrerequisiteEditor(SkillTreeNodeData node, int index, Runnable refreshManager) {
        boolean adding = index < 0;
        SkillTreePrerequisiteData working = adding
            ? newPrerequisite() : node.prerequisites.get(index).copy();
        Dialog dialog = new Dialog(adding ? "Add Prerequisite" : "Edit Prerequisite", skin);
        SelectBox<String> type = new SelectBox<>(skin);
        type.setItems("Cursed Technique Mastery", "Stat", "Node");
        type.setSelected(typeLabel(working.type));
        Container<Actor> fields = new Container<>();
        fields.fill(true, false);
        fields.setActor(prerequisiteFields(working));
        type.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                String selectedType = typeValue(type.getSelected());
                if (!selectedType.equalsIgnoreCase(working.type)) {
                    working.type = selectedType;
                    working.stat = null;
                    working.nodeId = null;
                    working.minimum = 0;
                    working.attached = null;
                }
                fields.setActor(prerequisiteFields(working));
            }
        });

        Table content = dialog.getContentTable();
        content.defaults().pad(4f).left().growX();
        content.add(new Label("Type", skin));
        content.add(type).width(340f).row();
        content.add(fields).colspan(2).growX().row();
        Label error = new Label("", skin, "small");
        error.setColor(skin.get("text-error", Color.class));
        content.add(error).colspan(2).row();

        TextButton done = new TextButton("Done", skin);
        done.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                String validation = validatePrerequisite(working);
                if (validation != null) {
                    error.setText(validation);
                    return;
                }
                if (adding) node.prerequisites.add(working.copy());
                else node.prerequisites.set(index, working.copy());
                dialog.hide();
                refreshManager.run();
                onChanged.run();
            }
        });
        TextButton cancel = new TextButton("Cancel", skin);
        cancel.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) { dialog.hide(); }
        });
        dialog.getButtonTable().add(done).pad(4f);
        dialog.getButtonTable().add(cancel).pad(4f);
        dialog.show(getStage());
    }

    private Actor prerequisiteFields(SkillTreePrerequisiteData requirement) {
        Table fields = new Table(skin);
        fields.defaults().pad(4f).left().growX();
        if (SkillTreePrerequisiteData.NODE.equalsIgnoreCase(requirement.type)) {
            SelectBox<String> nodes = new SelectBox<>(skin);
            List<String> labels = technique.skillTree == null ? List.of() : technique.skillTree.stream()
                .filter(Objects::nonNull).map(this::nodeOptionLabel).toList();
            nodes.setItems(labels.toArray(new String[0]));
            String selected = technique.skillTree == null ? null : technique.skillTree.stream()
                .filter(candidate -> Objects.equals(candidate.id, requirement.nodeId))
                .map(this::nodeOptionLabel).findFirst().orElse(null);
            if (selected != null) nodes.setSelected(selected);
            if (requirement.nodeId == null && !labels.isEmpty()) {
                requirement.nodeId = nodeIdFromOption(labels.get(0));
            }
            nodes.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    requirement.nodeId = nodeIdFromOption(nodes.getSelected());
                }
            });
            fields.add(new Label("Required node", skin)).width(210f);
            fields.add(nodes).width(340f).row();
            return fields;
        }

        if (SkillTreePrerequisiteData.STAT.equalsIgnoreCase(requirement.type)) {
            SelectBox<String> stats = new SelectBox<>(skin);
            List<String> labels = java.util.Arrays.stream(StatKey.values())
                .filter(stat -> stat != StatKey.CURSED_TECHNIQUE_MASTERY)
                .map(stat -> stat.label).toList();
            stats.setItems(labels.toArray(new String[0]));
            StatKey selected = statOf(requirement.stat);
            if (selected == StatKey.CURSED_TECHNIQUE_MASTERY) selected = StatKey.VITALITY;
            stats.setSelected(selected.label);
            requirement.stat = selected.fieldName;
            stats.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    requirement.stat = java.util.Arrays.stream(StatKey.values())
                        .filter(stat -> stat.label.equals(stats.getSelected()))
                        .findFirst().orElse(StatKey.VITALITY).fieldName;
                }
            });
            fields.add(new Label("Stat", skin)).width(210f);
            fields.add(stats).width(340f).row();
        }

        TextField minimum = new HoverTextField(
            String.valueOf(requirement.minimum == null ? 0 : requirement.minimum), skin);
        minimum.setTextFieldFilter((field, character) -> Character.isDigit(character));
        minimum.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                try {
                    requirement.minimum = Integer.parseInt(minimum.getText());
                } catch (NumberFormatException ex) {
                    requirement.minimum = null;
                }
            }
        });
        fields.add(new Label("Minimum (0-300)", skin)).width(210f);
        fields.add(minimum).width(340f).row();
        return fields;
    }

    private String validatePrerequisite(SkillTreePrerequisiteData requirement) {
        if (SkillTreePrerequisiteData.NODE.equalsIgnoreCase(requirement.type)) {
            return TechniqueSkillTree.nodeById(technique, requirement.nodeId) == null
                ? "Choose a node that still exists." : null;
        }
        if (requirement.minimum == null || requirement.minimum < 0 || requirement.minimum > 300) {
            return "Minimum must be between 0 and 300.";
        }
        if (SkillTreePrerequisiteData.STAT.equalsIgnoreCase(requirement.type)) {
            try {
                StatKey.fromString(requirement.stat);
            } catch (IllegalArgumentException ex) {
                return "Choose a valid stat.";
            }
        }
        return null;
    }

    private SkillTreePrerequisiteData newPrerequisite() {
        SkillTreePrerequisiteData prerequisite = new SkillTreePrerequisiteData();
        prerequisite.type = SkillTreePrerequisiteData.MASTERY;
        prerequisite.minimum = 0;
        return prerequisite;
    }

    private String describe(SkillTreePrerequisiteData prerequisite) {
        if (prerequisite == null || prerequisite.type == null) return "Invalid prerequisite";
        if (SkillTreePrerequisiteData.MASTERY.equalsIgnoreCase(prerequisite.type)) {
            return "Cursed Technique Mastery >= " + prerequisite.minimum;
        }
        if (SkillTreePrerequisiteData.STAT.equalsIgnoreCase(prerequisite.type)) {
            return statOf(prerequisite.stat).label + " >= " + prerequisite.minimum;
        }
        SkillTreeNodeData node = TechniqueSkillTree.nodeById(technique, prerequisite.nodeId);
        return "Node: " + (node == null ? prerequisite.nodeId : contentName(node))
            + (prerequisite.hasAttachment() ? " (attached)" : "");
    }

    private String contentName(SkillTreeNodeData node) {
        if (node == null) return "Missing node";
        if (SkillTreeNodeData.MOVE.equalsIgnoreCase(node.contentType)) {
            MoveData move = movesById.get(node.contentId);
            return move == null ? "Missing move " + node.contentId : move.name;
        }
        AbilityData ability = abilitiesById.get(node.contentId);
        return ability == null ? "Missing ability " + node.contentId : ability.name;
    }

    private String contentDescription(SkillTreeNodeData node) {
        if (SkillTreeNodeData.MOVE.equalsIgnoreCase(node.contentType)) {
            MoveData move = movesById.get(node.contentId);
            return move == null || move.description == null ? "" : move.description;
        }
        AbilityData ability = abilitiesById.get(node.contentId);
        return ability == null || ability.mechanicText == null ? "" : ability.mechanicText;
    }

    private String nodeOptionLabel(SkillTreeNodeData node) {
        return node.id + " - " + contentName(node);
    }

    private static String nodeIdFromOption(String option) {
        if (option == null) return null;
        int separator = option.indexOf(" - ");
        return separator < 0 ? option : option.substring(0, separator);
    }

    private static String typeLabel(String type) {
        if (SkillTreePrerequisiteData.STAT.equalsIgnoreCase(type)) return "Stat";
        if (SkillTreePrerequisiteData.NODE.equalsIgnoreCase(type)) return "Node";
        return "Cursed Technique Mastery";
    }

    private static String typeValue(String label) {
        if ("Stat".equals(label)) return SkillTreePrerequisiteData.STAT;
        if ("Node".equals(label)) return SkillTreePrerequisiteData.NODE;
        return SkillTreePrerequisiteData.MASTERY;
    }

    private static StatKey statOf(String name) {
        try {
            return StatKey.fromString(name);
        } catch (IllegalArgumentException ex) {
            return StatKey.VITALITY;
        }
    }

    private record AttachedConnection(
        SkillTreeNodeData source,
        SkillTreeNodeData target,
        SkillTreePrerequisiteData requirement
    ) { }

    private final class NodeView extends Table {
        private final SkillTreeNodeData node;
        private final Label name;
        private final Label description;
        private float downStageX;
        private float downStageY;
        private float startX;
        private float startY;
        private boolean dragged;
        private boolean hovered;

        private NodeView(SkillTreeNodeData node) {
            super(skin);
            this.node = node;
            setBackground(skin.getDrawable("white-panel"));
            setTouchable(Touchable.enabled);
            pad(9f);
            defaults().left().growX();

            name = new Label(contentName(node), skin);
            name.setEllipsis(true);
            add(name).width(NODE_WIDTH - 18f).row();
            description = new Label(contentDescription(node), skin, "small");
            description.setWrap(true);
            description.setAlignment(Align.topLeft);
            add(description).width(NODE_WIDTH - 18f).height(72f).top().row();
            refreshState();

            addListener(new InputListener() {
                @Override public boolean touchDown(
                    InputEvent event, float x, float y, int pointer, int button
                ) {
                    if (button == Input.Buttons.RIGHT && editable) {
                        showContextMenu(node, event.getStageX(), event.getStageY());
                        event.stop();
                        return true;
                    }
                    if (button != Input.Buttons.LEFT) return false;
                    downStageX = event.getStageX();
                    downStageY = event.getStageY();
                    startX = node.x;
                    startY = node.y;
                    dragged = false;
                    event.stop();
                    return true;
                }

                @Override public void touchDragged(InputEvent event, float x, float y, int pointer) {
                    if (!editable) return;
                    float deltaX = event.getStageX() - downStageX;
                    float deltaY = event.getStageY() - downStageY;
                    if (!dragged && Math.abs(deltaX) + Math.abs(deltaY) < DRAG_THRESHOLD) return;
                    dragged = true;
                    node.x = MathUtils.clamp(startX + deltaX, 0f,
                        Math.max(0f, SkillTreeCanvas.this.getPrefWidth() - NODE_WIDTH));
                    node.y = MathUtils.clamp(startY + deltaY, 0f, VIEW_HEIGHT - NODE_HEIGHT);
                    setPosition(node.x, node.y);
                }

                @Override public void touchUp(
                    InputEvent event, float x, float y, int pointer, int button
                ) {
                    if (button != Input.Buttons.LEFT) return;
                    if (editable && dragged) {
                        recalculateWidth();
                        setWidth(preferredWidth);
                        invalidateHierarchy();
                        onChanged.run();
                    } else if (editable && attachingSource != null) {
                        attachTo(node);
                    } else if (!editable) {
                        toggleNode(node);
                    }
                }

                @Override public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                    if (pointer == -1) {
                        hovered = true;
                        refreshState();
                    }
                }

                @Override public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                    if (pointer == -1) {
                        hovered = false;
                        refreshState();
                    }
                }
            });
        }

        private void refreshState() {
            boolean active = character != null && TechniqueSkillTree.isActive(node, character);
            boolean locked = character != null && !active
                && !TechniqueSkillTree.isUnlocked(technique, node, character);
            // Active nodes keep the yellow hover-outline treatment; locked nodes
            // (prerequisites unmet) render with the same dark grey panel used for
            // unavailable moves in the assignment panels, instead of the white of
            // available nodes, and don't pick up the hover outline.
            String backgroundName = locked ? "battle-card-disabled"
                : (active || hovered ? "textfield-over" : "white-panel");
            setBackground(skin.getDrawable(backgroundName));
            if (character == null) {
                name.setColor(skin.get("text-dark", Color.class));
                description.setColor(skin.get("text-dark", Color.class));
                return;
            }
            if (active) {
                name.setColor(skin.get("text-ok", Color.class));
                description.setColor(skin.get("text-dark", Color.class));
            } else if (locked) {
                name.setColor(skin.get("text-dim", Color.class));
                description.setColor(skin.get("text-dim", Color.class));
            } else {
                name.setColor(skin.get("text-dark", Color.class));
                description.setColor(skin.get("text-dark", Color.class));
            }
        }
    }
}
