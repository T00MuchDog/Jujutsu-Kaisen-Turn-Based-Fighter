package com.jjktbf.graphics.ui.editor;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectParameter;
import com.jjktbf.model.character.AbilityEffectTarget;
import com.jjktbf.model.character.AbilityEffectTiming;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.StatKey;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.technique.InnateTechniqueData;

import java.util.ArrayList;
import java.util.List;

/** Effect list and effect-specific modal editor used by the ability editor. */
public class EffectListEditor extends Table {

    private static final String ALL_MOVES = "All moves";
    private static final String SELECT_MOVE = "[select a move]";
    private static final String SELECT_TECHNIQUE = "[select a technique]";
    private static final String NO_MOVES = "[no moves available]";
    private static final String NO_TECHNIQUES = "[no techniques available]";

    private final Skin skin;
    private final List<AbilityEffectData> effects;
    private final List<MoveData> moves;
    private final List<InnateTechniqueData> techniques;
    private final Runnable onDirty;
    private final Runnable requestRebuild;
    private final Container<Actor> listContainer;

    public EffectListEditor(
        List<AbilityEffectData> effects,
        List<MoveData> moves,
        List<InnateTechniqueData> techniques,
        Runnable onDirty,
        Runnable requestRebuild,
        Skin skin
    ) {
        super(skin);
        this.skin = skin;
        this.effects = effects == null ? new ArrayList<>() : effects;
        this.moves = moves == null ? List.of() : moves;
        this.techniques = techniques == null ? List.of() : techniques;
        this.onDirty = onDirty;
        this.requestRebuild = requestRebuild;

        listContainer = new Container<>();
        listContainer.fill(true, false);
        add(listContainer).growX().row();

        TextButton addButton = new TextButton("+ Add effect", skin);
        addButton.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                openEditor(-1);
            }
        });
        add(addButton).left().padTop(4).row();

        rebuildList();
    }

    private void rebuildList() {
        Table list = new Table(skin);
        list.defaults().left().pad(3);
        if (effects.isEmpty()) {
            Label empty = new Label("(none)", skin, "small");
            empty.setColor(skin.get("text-dim", Color.class));
            list.add(empty).row();
        } else {
            for (int i = 0; i < effects.size(); i++) {
                final int index = i;
                AbilityEffectData effect = effects.get(index);
                list.add(new Label(describe(effect), skin, "small")).left().growX();

                TextButton edit = new TextButton("Edit", skin);
                edit.addListener(new ChangeListener() {
                    @Override public void changed(ChangeEvent event, Actor actor) {
                        openEditor(index);
                    }
                });
                TextButton remove = new TextButton("X", skin);
                remove.addListener(new ChangeListener() {
                    @Override public void changed(ChangeEvent event, Actor actor) {
                        effects.remove(index);
                        dirtyAndRebuild();
                    }
                });
                list.add(edit).padLeft(4);
                list.add(remove).padLeft(4).row();
            }
        }
        listContainer.setActor(list);
    }

    /** Open a working-copy editor. A cancelled add/edit never mutates the draft. */
    private void openEditor(int index) {
        boolean adding = index < 0;
        AbilityEffectData working = adding
            ? AbilityEffectType.STAT_ADD.createDefault()
            : effects.get(index).copy();
        AbilityEffectType initialType = safeType(working.type);
        initialType.prepare(working);

        SelectBox<String> typeBox = new SelectBox<>(skin);
        typeBox.setItems(effectTypeLabels());
        typeBox.setSelected(initialType.displayName());

        Label hint = new Label(initialType.description(), skin, "small");
        hint.setColor(skin.get("text-dim", Color.class));
        hint.setWrap(true);

        Label error = new Label("", skin, "small");
        error.setColor(skin.get("text-error", Color.class));
        error.setWrap(true);

        Container<Actor> fieldsContainer = new Container<>();
        fieldsContainer.fill(true, false);
        fieldsContainer.setActor(buildFields(working, initialType));

        typeBox.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                AbilityEffectType selected = typeFromLabel(typeBox.getSelected());
                selected.reset(working);
                hint.setText(selected.description());
                error.setText("");
                fieldsContainer.setActor(buildFields(working, selected));
            }
        });

        Dialog dialog = new Dialog(adding ? "Add Effect" : "Edit Effect", skin);
        Table content = dialog.getContentTable();
        content.defaults().pad(4).left().growX();
        content.add(new Label("Effect", skin)).padRight(8);
        content.add(typeBox).growX().row();
        content.add(hint).colspan(2).width(440).growX().row();
        content.add(fieldsContainer).colspan(2).growX().row();
        content.add(error).colspan(2).width(440).growX().row();

        TextButton done = new TextButton("Done", skin);
        done.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                AbilityEffectType selected = typeFromLabel(typeBox.getSelected());
                selected.clearUnusedFields(working);
                String validationError = selected.validationError(working);
                if (validationError != null) {
                    error.setText(validationError);
                    return;
                }
                if (selected == AbilityEffectType.GRANT_MOVE
                    && moves.stream().noneMatch(move -> working.moveId.equals(move.id))) {
                    error.setText("Choose a move that still exists.");
                    return;
                }
                if (selected == AbilityEffectType.UNLOCK_TECHNIQUE
                    && techniques.stream().noneMatch(technique ->
                        working.stringValue.equalsIgnoreCase(technique.name))) {
                    error.setText("Choose a technique that still exists.");
                    return;
                }
                if (adding) effects.add(working.copy());
                else effects.set(index, working.copy());
                dialog.hide();
                dirtyAndRebuild();
            }
        });
        TextButton cancel = new TextButton("Cancel", skin);
        cancel.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                dialog.hide();
            }
        });
        dialog.getButtonTable().add(done).pad(4);
        dialog.getButtonTable().add(cancel).pad(4);
        dialog.show(getStage());
    }

    private Actor buildFields(AbilityEffectData effect, AbilityEffectType type) {
        Table fields = new Table(skin);
        fields.defaults().pad(4).left().growX();
        TextField durationField = type.uses(AbilityEffectParameter.DURATION)
            ? integerField(effect.durationRounds) : null;

        if (type.uses(AbilityEffectParameter.STAT)) {
            SelectBox<String> statBox = new SelectBox<>(skin);
            statBox.setItems(statLabels());
            statBox.setSelected(statLabel(effect.stat));
            effect.stat = statFromLabel(statBox.getSelected()).fieldName;
            statBox.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    effect.stat = statFromLabel(statBox.getSelected()).fieldName;
                }
            });
            addRow(fields, "Stat", statBox);
        }

        if (type.uses(AbilityEffectParameter.MOVE_SCOPE)) {
            SelectBox<String> scopeBox = new SelectBox<>(skin);
            scopeBox.setItems(moveScopeLabels(type != AbilityEffectType.LOCK_MOVE_TAG));
            scopeBox.setSelected(moveScopeLabel(effect.moveTag));
            effect.moveTag = ALL_MOVES.equals(scopeBox.getSelected())
                ? null : tagFromLabel(scopeBox.getSelected()).name();
            scopeBox.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    effect.moveTag = ALL_MOVES.equals(scopeBox.getSelected())
                        ? null : tagFromLabel(scopeBox.getSelected()).name();
                }
            });
            addRow(fields, type == AbilityEffectType.LOCK_MOVE_TAG ? "Move tag" : "Affected moves", scopeBox);
        }

        if (type.uses(AbilityEffectParameter.INTEGER)) {
            TextField integer = integerField(effect.intValue);
            integer.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    effect.intValue = parseInteger(integer.getText());
                }
            });
            addRow(fields, integerLabel(type), integer);
        }

        if (type.uses(AbilityEffectParameter.DECIMAL)) {
            boolean percentage = type == AbilityEffectType.BF_CHANCE_ADD;
            Double displayed = percentage && effect.doubleValue != null
                ? effect.doubleValue * 100.0 : effect.doubleValue;
            TextField decimal = decimalField(displayed);
            decimal.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    Double value = parseDouble(decimal.getText());
                    effect.doubleValue = percentage && value != null ? value / 100.0 : value;
                }
            });
            addRow(fields, decimalLabel(type), decimal);
        }

        if (type.uses(AbilityEffectParameter.MOVE_ID)) {
            SelectBox<String> moveBox = new SelectBox<>(skin);
            moveBox.setItems(moveReferenceLabels(effect.moveId));
            moveBox.setSelected(moveReferenceLabel(effect.moveId));
            moveBox.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    effect.moveId = moveIdFromLabel(moveBox.getSelected());
                }
            });
            addRow(fields, "Move", moveBox);
        }

        if (type.uses(AbilityEffectParameter.TECHNIQUE)) {
            SelectBox<String> techniqueBox = new SelectBox<>(skin);
            techniqueBox.setItems(techniqueLabels(effect.stringValue));
            techniqueBox.setSelected(techniqueLabel(effect.stringValue));
            techniqueBox.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    effect.stringValue = techniqueNameFromLabel(techniqueBox.getSelected());
                }
            });
            addRow(fields, "Technique", techniqueBox);
        }

        if (type.uses(AbilityEffectParameter.STATUS_TYPE)) {
            SelectBox<String> statusBox = new SelectBox<>(skin);
            String[] statuses = AbilityEffectType.supportedAutoStatuses().stream()
                .map(status -> pretty(status.name()))
                .toArray(String[]::new);
            statusBox.setItems(statuses);
            statusBox.setSelected(pretty(effect.stringValue));
            effect.stringValue = enumName(statusBox.getSelected());
            statusBox.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    effect.stringValue = enumName(statusBox.getSelected());
                }
            });
            addRow(fields, "Status", statusBox);
        }

        if (type.uses(AbilityEffectParameter.TARGET)) {
            SelectBox<String> targetBox = new SelectBox<>(skin);
            targetBox.setItems(AbilityEffectTarget.SELF.name(), AbilityEffectTarget.ENEMY.name());
            targetBox.setSelected(effect.target);
            effect.target = targetBox.getSelected();
            targetBox.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    effect.target = targetBox.getSelected();
                }
            });
            addRow(fields, "Target", targetBox);
        }

        if (type.uses(AbilityEffectParameter.TIMING)) {
            SelectBox<String> timingBox = new SelectBox<>(skin);
            timingBox.setItems(
                AbilityEffectTiming.FIGHT_START.name(),
                AbilityEffectTiming.ROUND_START.name(),
                AbilityEffectTiming.ON_HIT.name());
            timingBox.setSelected(effect.timing);
            effect.timing = timingBox.getSelected();
            timingBox.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    effect.timing = timingBox.getSelected();
                    if (AbilityEffectTiming.ROUND_START.name().equals(effect.timing)) {
                        effect.durationRounds = 1;
                        if (durationField != null) durationField.setText("1");
                    }
                }
            });
            addRow(fields, "Apply when", timingBox);
        }

        if (type.uses(AbilityEffectParameter.DURATION)) {
            durationField.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    effect.durationRounds = parseInteger(durationField.getText());
                }
            });
            addRow(fields, "Duration (-1 = permanent)", durationField);
        }

        if (type.uses(AbilityEffectParameter.MAGNITUDE)) {
            TextField magnitude = decimalField(effect.magnitude);
            magnitude.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    effect.magnitude = parseDouble(magnitude.getText());
                }
            });
            addRow(fields, "Magnitude (FOCUS fraction / CE OUTPUT points)", magnitude);
        }

        return fields;
    }

    private void dirtyAndRebuild() {
        if (onDirty != null) onDirty.run();
        rebuildList();
        if (requestRebuild != null) requestRebuild.run();
    }

    private static void addRow(Table table, String label, Actor actor) {
        table.add(new Label(label, table.getSkin())).padRight(8);
        table.add(actor).growX().row();
    }

    private TextField integerField(Integer value) {
        TextField field = new HoverTextField(value == null ? "" : String.valueOf(value), skin);
        field.setTextFieldFilter((textField, character) -> Character.isDigit(character) || character == '-');
        return field;
    }

    private TextField decimalField(Double value) {
        TextField field = new HoverTextField(value == null ? "" : formatNumber(value), skin);
        field.setTextFieldFilter((textField, character) ->
            Character.isDigit(character) || character == '-' || character == '.');
        return field;
    }

    private String describe(AbilityEffectData effect) {
        AbilityEffectType type = safeType(effect == null ? null : effect.type);
        if (effect == null) return type.displayName();
        StringBuilder summary = new StringBuilder(type.displayName());
        if (type.uses(AbilityEffectParameter.STAT) && effect.stat != null) {
            summary.append(" | ").append(statLabel(effect.stat));
        }
        if (type.uses(AbilityEffectParameter.MOVE_SCOPE)) {
            summary.append(" | ").append(moveScopeLabel(effect.moveTag));
        }
        if (type.uses(AbilityEffectParameter.INTEGER) && effect.intValue != null) {
            summary.append(" | ").append(effect.intValue >= 0 ? "+" : "").append(effect.intValue);
        }
        if (type.uses(AbilityEffectParameter.DECIMAL) && effect.doubleValue != null) {
            if (type == AbilityEffectType.BF_CHANCE_ADD) {
                summary.append(" | ").append(formatNumber(effect.doubleValue * 100.0)).append('%');
            } else {
                summary.append(" | x").append(formatNumber(effect.doubleValue));
            }
        }
        if (type.uses(AbilityEffectParameter.MOVE_ID) && effect.moveId != null) {
            summary.append(" | ").append(moveReferenceLabel(effect.moveId));
        }
        if (type.uses(AbilityEffectParameter.TECHNIQUE) && effect.stringValue != null) {
            summary.append(" | ").append(effect.stringValue);
        }
        if (type.uses(AbilityEffectParameter.STATUS_TYPE) && effect.stringValue != null) {
            summary.append(" | ").append(pretty(effect.stringValue));
            summary.append(" -> ").append(effect.target);
            summary.append(" @ ").append(effect.timing);
        }
        return summary.toString();
    }

    private static AbilityEffectType safeType(String typeName) {
        try {
            return AbilityEffectType.fromName(typeName);
        } catch (Exception ex) {
            return AbilityEffectType.STAT_ADD;
        }
    }

    private static String[] effectTypeLabels() {
        AbilityEffectType[] types = AbilityEffectType.values();
        String[] labels = new String[types.length];
        for (int i = 0; i < types.length; i++) labels[i] = types[i].displayName();
        return labels;
    }

    private static AbilityEffectType typeFromLabel(String label) {
        for (AbilityEffectType type : AbilityEffectType.values()) {
            if (type.displayName().equals(label)) return type;
        }
        return AbilityEffectType.STAT_ADD;
    }

    private static String[] statLabels() {
        StatKey[] stats = StatKey.values();
        String[] labels = new String[stats.length];
        for (int i = 0; i < stats.length; i++) labels[i] = stats[i].label;
        return labels;
    }

    private static String statLabel(String statName) {
        try {
            return StatKey.fromString(statName).label;
        } catch (Exception ex) {
            return StatKey.VITALITY.label;
        }
    }

    private static StatKey statFromLabel(String label) {
        for (StatKey stat : StatKey.values()) {
            if (stat.label.equals(label)) return stat;
        }
        return StatKey.VITALITY;
    }

    private static String[] moveScopeLabels(boolean includeAll) {
        MoveTag[] tags = MoveTag.values();
        String[] labels = new String[tags.length + (includeAll ? 1 : 0)];
        int index = 0;
        if (includeAll) labels[index++] = ALL_MOVES;
        for (MoveTag tag : tags) labels[index++] = pretty(tag.name());
        return labels;
    }

    private static String moveScopeLabel(String tagName) {
        if (tagName == null || tagName.isBlank()) return ALL_MOVES;
        try {
            return pretty(MoveTag.valueOf(tagName).name());
        } catch (Exception ex) {
            return pretty(tagName);
        }
    }

    private static MoveTag tagFromLabel(String label) {
        return MoveTag.valueOf(enumName(label));
    }

    private String[] moveReferenceLabels(String currentId) {
        List<String> labels = new ArrayList<>();
        labels.add(SELECT_MOVE);
        for (MoveData move : moves) labels.add(moveLabel(move));
        if (currentId != null && !currentId.isBlank()
            && moves.stream().noneMatch(move -> currentId.equals(move.id))) {
            labels.add(currentId + " - (missing)");
        }
        if (moves.isEmpty()) labels.add(NO_MOVES);
        return labels.toArray(new String[0]);
    }

    private String moveReferenceLabel(String moveId) {
        if (moveId == null || moveId.isBlank()) return SELECT_MOVE;
        return moves.stream()
            .filter(move -> moveId.equals(move.id))
            .findFirst()
            .map(EffectListEditor::moveLabel)
            .orElse(moveId + " - (missing)");
    }

    private static String moveLabel(MoveData move) {
        return move.id + " - " + move.name;
    }

    private static String moveIdFromLabel(String label) {
        if (label == null || label.startsWith("[")) return null;
        int separator = label.indexOf(" - ");
        return separator < 0 ? label.trim() : label.substring(0, separator).trim();
    }

    private String[] techniqueLabels(String current) {
        List<String> labels = new ArrayList<>();
        labels.add(SELECT_TECHNIQUE);
        labels.addAll(techniques.stream().map(technique -> technique.name).toList());
        if (current != null && !current.isBlank()
            && techniques.stream().noneMatch(technique -> current.equalsIgnoreCase(technique.name))) {
            labels.add(current + " (missing)");
        }
        if (techniques.isEmpty()) labels.add(NO_TECHNIQUES);
        return labels.toArray(new String[0]);
    }

    private String techniqueLabel(String current) {
        if (current == null || current.isBlank()) {
            return SELECT_TECHNIQUE;
        }
        return techniques.stream()
            .filter(technique -> current.equalsIgnoreCase(technique.name))
            .findFirst()
            .map(technique -> technique.name)
            .orElse(current + " (missing)");
    }

    private static String techniqueNameFromLabel(String label) {
        if (label == null || label.startsWith("[")) return null;
        return label.endsWith(" (missing)")
            ? label.substring(0, label.length() - " (missing)".length())
            : label;
    }

    private static String integerLabel(AbilityEffectType type) {
        return switch (type) {
            case STAT_ADD -> "Amount (+/-)";
            case STAT_SET_VALUE -> "Exact value";
            case STAT_BONUS_POINTS -> "Point-budget change";
            case MOVE_ACCURACY_ADD, OPPONENT_ACCURACY_ADD -> "Accuracy points (+/-)";
            case MODIFY_AP_BAR -> "AP change (+/-)";
            case COST_CE_PER_ROUND -> "CE cost per round";
            default -> "Value";
        };
    }

    private static String decimalLabel(AbilityEffectType type) {
        return switch (type) {
            case STAT_DIVIDE -> "Divisor";
            case BF_CHANCE_ADD -> "Chance change % (+/-)";
            default -> "Multiplier";
        };
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isBlank() || "-".equals(value)) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Double parseDouble(String value) {
        if (value == null || value.isBlank() || "-".equals(value) || ".".equals(value)) return null;
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String formatNumber(double value) {
        if (value == Math.rint(value)) return String.valueOf((long) value);
        return String.valueOf(value);
    }

    private static String enumName(String label) {
        return label == null ? "" : label.trim().toUpperCase().replace(' ', '_');
    }

    private static String pretty(String enumName) {
        if (enumName == null || enumName.isBlank()) return "";
        String[] words = enumName.toLowerCase().split("_");
        StringBuilder label = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (label.length() > 0) label.append(' ');
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.toString();
    }
}
