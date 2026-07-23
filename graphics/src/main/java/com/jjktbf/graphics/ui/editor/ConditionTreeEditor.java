package com.jjktbf.graphics.ui.editor;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.jjktbf.model.character.*;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.move.StatusEffectType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Recursive AND/OR predicate editor used by the ability editor. */
public class ConditionTreeEditor extends Table {

    private static final String SELECT_MOVE = "[select a move]";

    private final AbilityConditionData root;
    private final List<MoveData> moves;
    private final Runnable onDirty;
    private final Skin skin;
    private final Container<Actor> treeContainer = new Container<>();

    public ConditionTreeEditor(
        AbilityConditionData root,
        List<MoveData> moves,
        Runnable onDirty,
        Skin skin
    ) {
        super(skin);
        this.root = root;
        this.moves = moves == null ? List.of() : moves;
        this.onDirty = onDirty;
        this.skin = skin;
        treeContainer.fill(true, false);
        add(treeContainer).growX().row();
        rebuild();
    }

    private void rebuild() {
        Table tree = new Table(skin);
        tree.defaults().left().pad(3);
        renderNode(tree, root, null, -1, 0);
        treeContainer.setActor(tree);
    }

    private void renderNode(
        Table table,
        AbilityConditionData node,
        AbilityConditionData parent,
        int childIndex,
        int depth
    ) {
        AbilityConditionType type = safeType(node.type);
        Label summary = new Label(indent(depth) + describe(node), skin, "small");
        if (type.isGroup()) summary.setColor(Color.GOLD);
        table.add(summary).growX();

        if (type.isGroup()) {
            TextButton addCondition = button("+ Condition", () -> {
                node.children.add(AbilityConditionType.HP_PERCENT_AT_OR_BELOW.createDefault());
                changed();
            });
            TextButton addAnd = button("+ AND", () -> addGroup(node, AbilityConditionType.ALL));
            TextButton addOr = button("+ OR", () -> addGroup(node, AbilityConditionType.ANY));
            TextButton toggle = button(type == AbilityConditionType.ALL ? "Use OR" : "Use AND", () -> {
                node.type = type == AbilityConditionType.ALL
                    ? AbilityConditionType.ANY.name() : AbilityConditionType.ALL.name();
                changed();
            });
            table.add(addCondition);
            table.add(addAnd);
            table.add(addOr);
            table.add(toggle);
        } else {
            table.add(button("Edit", () -> openLeafEditor(node))).padLeft(3);
            if (type != AbilityConditionType.ALWAYS) {
                table.add(button("AND", () -> wrapNode(node, parent, childIndex, AbilityConditionType.ALL)));
                table.add(button("OR", () -> wrapNode(node, parent, childIndex, AbilityConditionType.ANY)));
            } else {
                table.add().colspan(2);
            }
        }

        if (parent != null) {
            table.add(button("X", () -> {
                parent.children.remove(childIndex);
                if (parent.children.isEmpty()) {
                    parent.copyFrom(parent == root
                        ? AbilityConditionData.always()
                        : AbilityConditionType.HP_PERCENT_AT_OR_BELOW.createDefault());
                }
                changed();
            }));
        } else {
            table.add(button("Reset", () -> {
                root.copyFrom(AbilityConditionData.always());
                changed();
            }));
        }
        table.row();

        if (type.isGroup() && node.children != null) {
            for (int i = 0; i < node.children.size(); i++) {
                renderNode(table, node.children.get(i), node, i, depth + 1);
            }
        }
    }

    private void addGroup(AbilityConditionData parent, AbilityConditionType type) {
        AbilityConditionData group = type.createDefault();
        group.children.add(AbilityConditionType.HP_PERCENT_AT_OR_BELOW.createDefault());
        parent.children.add(group);
        changed();
    }

    private void wrapNode(
        AbilityConditionData node,
        AbilityConditionData parent,
        int childIndex,
        AbilityConditionType groupType
    ) {
        AbilityConditionData group = groupType.createDefault();
        group.children.add(node.copy());
        group.children.add(AbilityConditionType.HP_PERCENT_AT_OR_BELOW.createDefault());
        if (parent == null) root.copyFrom(group);
        else parent.children.set(childIndex, group);
        changed();
    }

    private void openLeafEditor(AbilityConditionData original) {
        AbilityConditionData working = original.copy();
        AbilityConditionType initial = safeType(working.type);
        if (initial.isGroup()) return;

        SelectBox<String> typeBox = new SelectBox<>(skin);
        typeBox.setItems(Arrays.stream(AbilityConditionType.values())
            .filter(type -> !type.isGroup())
            .map(AbilityConditionType::displayName)
            .toArray(String[]::new));
        typeBox.setSelected(initial.displayName());

        Label hint = new Label(initial.description(), skin, "small");
        hint.setColor(skin.get("text-dim", Color.class));
        hint.setWrap(true);
        Label error = new Label("", skin, "small");
        error.setColor(skin.get("text-error", Color.class));
        error.setWrap(true);

        Container<Actor> fields = new Container<>();
        fields.fill(true, false);
        fields.setActor(buildFields(working, initial));

        typeBox.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                AbilityConditionType selected = typeFromLabel(typeBox.getSelected());
                selected.reset(working);
                hint.setText(selected.description());
                error.setText("");
                fields.setActor(buildFields(working, selected));
            }
        });

        Dialog dialog = new Dialog("Edit Activation Condition", skin);
        Table content = dialog.getContentTable();
        content.defaults().pad(4).left().growX();
        content.add(new Label("Condition", skin));
        content.add(typeBox).growX().row();
        content.add(hint).colspan(2).width(480).growX().row();
        content.add(fields).colspan(2).growX().row();
        content.add(error).colspan(2).width(480).growX().row();

        TextButton done = button("Done", () -> {
            AbilityConditionType selected = typeFromLabel(typeBox.getSelected());
            selected.clearUnusedFields(working);
            String validation = AbilityConditionType.validationError(working);
            if (validation != null) {
                error.setText(validation);
                return;
            }
            if (selected == AbilityConditionType.MOVE_USED
                && moves.stream().noneMatch(move -> java.util.Objects.equals(move.id, working.moveId))) {
                error.setText("Choose a move that still exists.");
                return;
            }
            if (selected == AbilityConditionType.ALWAYS) root.copyFrom(working);
            else original.copyFrom(working);
            dialog.hide();
            changed();
        });
        TextButton cancel = button("Cancel", dialog::hide);
        dialog.getButtonTable().add(done).pad(4);
        dialog.getButtonTable().add(cancel).pad(4);
        dialog.show(getStage());
    }

    private Actor buildFields(AbilityConditionData condition, AbilityConditionType type) {
        Table fields = new Table(skin);
        fields.defaults().pad(4).left().growX();

        if (type.uses(AbilityConditionParameter.ACTOR)) {
            SelectBox<String> box = enumBox(AbilityConditionActor.values(), condition.actor, value -> condition.actor = value);
            addRow(fields, "Combatant", box);
        }
        if (type.uses(AbilityConditionParameter.PERCENTAGE)) {
            TextField field = decimalField(condition.percentage == null ? null : condition.percentage * 100.0);
            field.addListener(change(() -> {
                Double value = parseDouble(field.getText());
                condition.percentage = value == null ? null : value / 100.0;
            }));
            addRow(fields, "Percentage", field);
        }
        if (type.uses(AbilityConditionParameter.AMOUNT)) {
            TextField field = integerField(condition.amount);
            field.addListener(change(() -> condition.amount = parseInteger(field.getText())));
            addRow(fields, type == AbilityConditionType.HEALED ? "Minimum (0 = any)" : "Value", field);
        }
        if (type.uses(AbilityConditionParameter.MOVE_ID)) {
            SelectBox<String> box = new SelectBox<>(skin);
            List<String> labels = new ArrayList<>();
            labels.add(SELECT_MOVE);
            labels.addAll(moves.stream().map(ConditionTreeEditor::moveLabel).toList());
            String selected = moveLabel(condition.moveId);
            if (selected != null && !labels.contains(selected)) labels.add(selected);
            box.setItems(labels.toArray(new String[0]));
            box.setSelected(selected == null ? SELECT_MOVE : selected);
            box.addListener(change(() -> condition.moveId = moveId(box.getSelected())));
            addRow(fields, "Move", box);
        }
        if (type.uses(AbilityConditionParameter.MOVE_TAG)) {
            SelectBox<String> box = prettyEnumBox(MoveTag.values(), condition.moveTag,
                value -> condition.moveTag = enumName(value));
            addRow(fields, "Move tag", box);
        }
        if (type.uses(AbilityConditionParameter.STAT)) {
            SelectBox<String> box = new SelectBox<>(skin);
            box.setItems(Arrays.stream(StatKey.values()).map(stat -> stat.label).toArray(String[]::new));
            box.setSelected(statLabel(condition.stat));
            box.addListener(change(() -> condition.stat = statFromLabel(box.getSelected()).fieldName));
            addRow(fields, "Character stat", box);
        }
        if (type.uses(AbilityConditionParameter.STATUS_TYPE)) {
            List<StatusEffectType> statuses = List.of(StatusEffectType.values());
            SelectBox<String> box = new SelectBox<>(skin);
            List<String> labels = new ArrayList<>(statuses.stream()
                .map(StatusEffectType::displayName).toList());
            String storedStatus = condition.statusType;
            String selectedStatus = StatusEffectType.referenceDisplayName(storedStatus);
            if (!labels.contains(selectedStatus)) labels.add(0, selectedStatus);
            box.setItems(labels.toArray(new String[0]));
            box.setSelected(selectedStatus);
            box.addListener(change(() -> {
                if (selectedStatus.equals(box.getSelected())) {
                    condition.statusType = storedStatus;
                    return;
                }
                condition.statusType = statuses.stream()
                    .filter(status -> status.displayName().equals(box.getSelected()))
                    .findFirst().orElse(StatusEffectType.STRENGTH_INCREASE).name();
            }));
            addRow(fields, "Status", box);
        }
        if (type.uses(AbilityConditionParameter.TICK)) {
            TextField field = integerField(condition.tick);
            field.addListener(change(() -> condition.tick = parseInteger(field.getText())));
            addRow(fields, "Timeline point", field);
        }
        if (type.uses(AbilityConditionParameter.ROUND)) {
            TextField field = integerField(condition.round);
            field.addListener(change(() -> condition.round = parseInteger(field.getText())));
            addRow(fields, type == AbilityConditionType.EVERY_N_ROUNDS ? "Every N rounds" : "Round", field);
        }
        if (type.uses(AbilityConditionParameter.PHASE)) {
            BattleState.Phase[] phases = {
                BattleState.Phase.PLANNING,
                BattleState.Phase.RESOLUTION,
                BattleState.Phase.ROUND_END
            };
            SelectBox<String> box = enumBox(phases, condition.phase, value -> condition.phase = value);
            addRow(fields, "Phase", box);
        }
        return fields;
    }

    private <E extends Enum<E>> SelectBox<String> enumBox(E[] values, String selected, java.util.function.Consumer<String> onChange) {
        SelectBox<String> box = new SelectBox<>(skin);
        box.setItems(Arrays.stream(values).map(Enum::name).toArray(String[]::new));
        box.setSelected(selected);
        box.addListener(change(() -> onChange.accept(box.getSelected())));
        return box;
    }

    private <E extends Enum<E>> SelectBox<String> prettyEnumBox(E[] values, String selected, java.util.function.Consumer<String> onChange) {
        SelectBox<String> box = new SelectBox<>(skin);
        box.setItems(Arrays.stream(values).map(value -> pretty(value.name())).toArray(String[]::new));
        box.setSelected(pretty(selected));
        box.addListener(change(() -> onChange.accept(box.getSelected())));
        return box;
    }

    private TextButton button(String text, Runnable action) {
        TextButton button = new TextButton(text, skin);
        button.addListener(change(action));
        return button;
    }

    private static ChangeListener change(Runnable action) {
        return new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) { action.run(); }
        };
    }

    private void changed() {
        if (onDirty != null) onDirty.run();
        rebuild();
    }

    private static void addRow(Table table, String label, Actor actor) {
        table.add(new Label(label, table.getSkin())).padRight(8);
        table.add(actor).growX().row();
    }

    private TextField integerField(Integer value) {
        TextField field = new HoverTextField(value == null ? "" : String.valueOf(value), skin);
        field.setTextFieldFilter((textField, character) -> java.lang.Character.isDigit(character) || character == '-');
        return field;
    }

    private TextField decimalField(Double value) {
        TextField field = new HoverTextField(value == null ? "" : format(value), skin);
        field.setTextFieldFilter((textField, character) ->
            java.lang.Character.isDigit(character) || character == '-' || character == '.');
        return field;
    }

    private String describe(AbilityConditionData condition) {
        AbilityConditionType type = safeType(condition.type);
        StringBuilder result = new StringBuilder(type.displayName());
        if (type.uses(AbilityConditionParameter.ACTOR)) result.append(" | ").append(condition.actor);
        if (type.uses(AbilityConditionParameter.PERCENTAGE) && condition.percentage != null) {
            result.append(" | ").append(format(condition.percentage * 100)).append('%');
        }
        if (type.uses(AbilityConditionParameter.AMOUNT)) result.append(" | ").append(condition.amount);
        if (type.uses(AbilityConditionParameter.MOVE_ID)) result.append(" | ").append(moveLabel(condition.moveId));
        if (type.uses(AbilityConditionParameter.MOVE_TAG)) result.append(" | ").append(pretty(condition.moveTag));
        if (type.uses(AbilityConditionParameter.STAT)) result.append(" | ").append(statLabel(condition.stat));
        if (type.uses(AbilityConditionParameter.STATUS_TYPE)) result.append(" | ").append(pretty(condition.statusType));
        if (type.uses(AbilityConditionParameter.TICK)) result.append(" | tick ").append(condition.tick);
        if (type.uses(AbilityConditionParameter.ROUND)) result.append(" | round ").append(condition.round);
        if (type.uses(AbilityConditionParameter.PHASE)) result.append(" | ").append(condition.phase);
        return result.toString();
    }

    private String moveLabel(String id) {
        if (id == null || id.isBlank()) return null;
        return moves.stream().filter(move -> id.equals(move.id)).findFirst()
            .map(ConditionTreeEditor::moveLabel).orElse(id + " - (missing)");
    }

    private static String moveLabel(MoveData move) { return move.id + " - " + move.name; }
    private static String moveId(String label) {
        if (label == null || label.startsWith("[")) return null;
        int separator = label.indexOf(" - ");
        return separator < 0 ? label : label.substring(0, separator);
    }

    private static AbilityConditionType safeType(String value) {
        try { return AbilityConditionType.fromName(value); }
        catch (Exception ex) { return AbilityConditionType.ALWAYS; }
    }

    private static AbilityConditionType typeFromLabel(String label) {
        return Arrays.stream(AbilityConditionType.values())
            .filter(type -> type.displayName().equals(label))
            .findFirst().orElse(AbilityConditionType.ALWAYS);
    }

    private static String statLabel(String value) {
        try { return StatKey.fromString(value).label; }
        catch (Exception ex) { return StatKey.VITALITY.label; }
    }

    private static StatKey statFromLabel(String label) {
        return Arrays.stream(StatKey.values()).filter(stat -> stat.label.equals(label))
            .findFirst().orElse(StatKey.VITALITY);
    }

    private static Integer parseInteger(String value) {
        try { return value == null || value.isBlank() || "-".equals(value) ? null : Integer.valueOf(value); }
        catch (NumberFormatException ex) { return null; }
    }

    private static Double parseDouble(String value) {
        try { return value == null || value.isBlank() || "-".equals(value) ? null : Double.valueOf(value); }
        catch (NumberFormatException ex) { return null; }
    }

    private static String enumName(String value) {
        return value == null ? "" : value.trim().toUpperCase().replace(' ', '_');
    }

    private static String pretty(String value) {
        if (value == null) return "";
        String[] words = value.toLowerCase().split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(java.lang.Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private static String indent(int depth) { return "  ".repeat(Math.max(0, depth)); }
    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
