package com.jjktbf.graphics.ui.editor;

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
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.StatKey;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * List editor for {@link AbilityEffectData} rows — used by the Ability editor.
 *
 * Each row is summarised in the list (type + key fields) with Edit / Remove
 * controls. Editing opens a modal whose fields adapt to the chosen effect type:
 *   - STAT_* types show a stat picker (StatKey names) + int/double value.
 *   - move-tag types show a MoveTag select + int/double value.
 *   - GRANT_MOVE shows a move-id field.
 *   - AUTO_STATUS_APPLY shows status-type + target + timing.
 *   - others show the relevant single value field.
 *
 * The reference table for type→field mapping is embedded as a hint string so
 * the user always knows which fields apply.
 */
public class EffectListEditor extends Table {

    private final Skin skin;
    private final List<AbilityEffectData> effects;
    private final Runnable onDirty;
    private final Runnable requestRebuild;
    private final Container<Actor> listContainer;

    public EffectListEditor(List<AbilityEffectData> effects,
                            Runnable onDirty,
                            Runnable requestRebuild,
                            Skin skin) {
        super(skin);
        this.skin = skin;
        this.effects = effects == null ? new ArrayList<>() : effects;
        this.onDirty = onDirty;
        this.requestRebuild = requestRebuild;

        listContainer = new Container<>();
        listContainer.fill(true, false);
        add(listContainer).growX().row();

        TextButton addBtn = new TextButton("+ Add effect", skin);
        addBtn.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                AbilityEffectData e = new AbilityEffectData();
                e.type = AbilityEffectType.STAT_ADD.name();
                e.intValue = 0;
                EffectListEditor.this.effects.add(e);
                if (onDirty != null) onDirty.run();
                openEditor(EffectListEditor.this.effects.size() - 1);
            }
        });
        add(addBtn).left().padTop(4).row();

        rebuildList();
    }

    private void rebuildList() {
        Table list = new Table(skin);
        list.defaults().left().pad(3);
        if (effects.isEmpty()) {
            Label empty = new Label("(none)", skin, "small");
            empty.setColor(skin.get("text-dim", com.badlogic.gdx.graphics.Color.class));
            list.add(empty).row();
        } else {
            // Snapshot indices to avoid CME on rebuild.
            for (int i = 0; i < effects.size(); i++) {
                final int idx = i;
                AbilityEffectData e = effects.get(idx);
                list.add(new Label(describe(e), skin, "small")).left().growX();
                TextButton edit = new TextButton("Edit", skin);
                edit.addListener(new ChangeListener() {
                    @Override public void changed(ChangeEvent ev, Actor a) { openEditor(idx); }
                });
                TextButton rm = new TextButton("X", skin);
                rm.addListener(new ChangeListener() {
                    @Override public void changed(ChangeEvent ev, Actor a) {
                        effects.remove(idx);
                        if (onDirty != null) onDirty.run();
                        rebuildList();
                        if (requestRebuild != null) requestRebuild.run();
                    }
                });
                list.add(edit).padLeft(4);
                list.add(rm).padLeft(4).row();
            }
        }
        listContainer.setActor(list);
    }

    private void openEditor(int idx) {
        final AbilityEffectData e = effects.get(idx);

        // Build all the field widgets up front so the result() override can
        // read their current values synchronously when Done is clicked.
        final SelectBox<String> typeBox = new SelectBox<>(skin);
        String[] typeNames = new String[AbilityEffectType.values().length];
        for (int i = 0; i < AbilityEffectType.values().length; i++) {
            typeNames[i] = AbilityEffectType.values()[i].name();
        }
        typeBox.setItems(typeNames);
        typeBox.setSelected(safeTypeName(e.type));

        final Label hint = new Label(typeHint(typeBox.getSelected()), skin, "small");
        hint.setColor(skin.get("text-dim", com.badlogic.gdx.graphics.Color.class));
        typeBox.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent ev, Actor a) {
                hint.setText(typeHint(typeBox.getSelected()));
            }
        });

        final SelectBox<String> statBox = new SelectBox<>(skin);
        statBox.setItems(statNames());
        if (e.stat != null) statBox.setSelected(e.stat);

        final SelectBox<String> tagBox = new SelectBox<>(skin);
        tagBox.setItems(moveTagNames());
        tagBox.setSelected(e.moveTag != null ? e.moveTag : "ALL");

        final TextField intField = new HoverTextField(e.intValue == null ? "" : String.valueOf(e.intValue), skin);
        intField.setTextFieldFilter((tf, c) -> Character.isDigit(c) || c == '-');

        final TextField dblField = new HoverTextField(e.doubleValue == null ? "" : String.valueOf(e.doubleValue), skin);
        dblField.setTextFieldFilter((tf, c) -> Character.isDigit(c) || c == '-' || c == '.');

        final TextField moveIdField = new HoverTextField(e.moveId == null ? "" : e.moveId, skin);

        final TextField strField = new HoverTextField(e.stringValue == null ? "" : e.stringValue, skin);

        final SelectBox<String> targetBox = new SelectBox<>(skin);
        targetBox.setItems("SELF", "ENEMY");
        targetBox.setSelected(e.target == null ? "SELF" : e.target);

        final SelectBox<String> timingBox = new SelectBox<>(skin);
        timingBox.setItems("FIGHT_START", "ROUND_START", "ON_HIT");
        timingBox.setSelected(e.timing == null ? "FIGHT_START" : e.timing);

        Dialog dlg = new Dialog("Edit Effect", skin) {
            @Override
            protected void result(Object object) {
                if (!Boolean.TRUE.equals(object)) return;
                e.type = typeBox.getSelected();
                e.stat     = statBox.getSelected();
                String tg  = tagBox.getSelected();
                e.moveTag  = "ALL".equals(tg) ? null : tg;
                e.intValue    = parseInteger(intField.getText());
                e.doubleValue = parseDouble(dblField.getText());
                e.moveId      = moveIdField.getText().isBlank() ? null : moveIdField.getText();
                e.stringValue = strField.getText().isBlank() ? null : strField.getText();
                e.target = targetBox.getSelected();
                e.timing = timingBox.getSelected();
                if (onDirty != null) onDirty.run();
                rebuildList();
                if (requestRebuild != null) requestRebuild.run();
            }
        };

        Table content = dlg.getContentTable();
        content.defaults().pad(4).left().growX();

        content.add(new Label("Type", skin)).padRight(8);
        content.add(typeBox).growX().row();
        content.add(hint).colspan(2).row();

        content.add(new Label("Stat", skin)).padRight(8);
        content.add(statBox).growX().row();

        content.add(new Label("Move Tag (or ALL)", skin)).padRight(8);
        content.add(tagBox).growX().row();

        content.add(new Label("Int value", skin)).padRight(8);
        content.add(intField).growX().row();

        content.add(new Label("Double value", skin)).padRight(8);
        content.add(dblField).growX().row();

        content.add(new Label("Move ID", skin)).padRight(8);
        content.add(moveIdField).growX().row();

        content.add(new Label("String value", skin)).padRight(8);
        content.add(strField).growX().row();

        content.add(new Label("Target", skin)).padRight(8);
        content.add(targetBox).growX().row();

        content.add(new Label("Timing", skin)).padRight(8);
        content.add(timingBox).growX().row();

        dlg.button("Done", true);
        dlg.button("Cancel", false);
        dlg.show(getStage());
    }

    private static Integer parseInteger(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static Double parseDouble(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Double.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** One-line summary of an effect for the list. */
    private String describe(AbilityEffectData e) {
        StringBuilder sb = new StringBuilder();
        sb.append(safeTypeName(e.type));
        if (e.stat != null && !e.stat.isEmpty())      sb.append(" · ").append(e.stat);
        if (e.moveTag != null && !e.moveTag.isEmpty()) sb.append(" · ").append(e.moveTag);
        if (e.intValue != null)    sb.append(" · int=").append(e.intValue);
        if (e.doubleValue != null) sb.append(" · dbl=").append(e.doubleValue);
        if (e.moveId != null && !e.moveId.isEmpty()) sb.append(" · mv=").append(e.moveId);
        if (e.stringValue != null && !e.stringValue.isEmpty())
            sb.append(" · '").append(e.stringValue).append("'");
        return sb.toString();
    }

    /** What fields this type uses — shown as a hint under the type picker. */
    private String typeHint(String typeName) {
        AbilityEffectType t;
        try { t = AbilityEffectType.valueOf(typeName); } catch (Exception e) { return ""; }
        switch (t) {
            case STAT_ADD:
            case STAT_MULTIPLY:
            case STAT_DIVIDE:
            case STAT_SET_VALUE:
            case STAT_SET_MIN:        return "Uses: Stat + Int/Double value.";
            case STAT_BONUS_POINTS:   return "Uses: Int value (point-buy budget).";
            case CE_COST_TO_MINIMUM:
            case CE_COST_MULTIPLY:
            case MOVE_ACCURACY_ADD:
            case MOVE_ACCURACY_MULTIPLY:
            case OPPONENT_ACCURACY_ADD:
            case OPPONENT_ACCURACY_MULTIPLY:
            case DAMAGE_MULTIPLY:
            case LOCK_MOVE_TAG:       return "Uses: Move Tag (+ Int/Double value).";
            case GRANT_MOVE:          return "Uses: Move ID.";
            case BF_CHANCE_ADD:       return "Uses: Double value.";
            case UNLOCK_TECHNIQUE:    return "Uses: String value (technique name).";
            case MODIFY_DEFENSE:      return "Uses: Double value (multiplier).";
            case MODIFY_AP_BAR:       return "Uses: Int value.";
            case AUTO_STATUS_APPLY:   return "Uses: String value (status type) + Target + Timing.";
            case COST_CE_PER_ROUND:   return "Uses: Int value.";
            default:                  return "";
        }
    }

    private static String safeTypeName(String t) {
        if (t == null || t.isEmpty()) return AbilityEffectType.STAT_ADD.name();
        try { return AbilityEffectType.valueOf(t).name(); }
        catch (Exception e) { return t; }
    }

    private static String[] statNames() {
        StatKey[] ks = StatKey.values();
        String[] names = new String[ks.length];
        for (int i = 0; i < ks.length; i++) names[i] = ks[i].fieldName;
        return names;
    }

    private static String[] moveTagNames() {
        com.jjktbf.model.move.MoveTag[] ts = com.jjktbf.model.move.MoveTag.values();
        String[] names = new String[ts.length];
        for (int i = 0; i < ts.length; i++) names[i] = ts[i].name();
        return names;
    }
}
