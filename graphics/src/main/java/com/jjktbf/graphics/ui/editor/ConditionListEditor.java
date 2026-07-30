package com.jjktbf.graphics.ui.editor;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.jjktbf.graphics.audio.SoundCue;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityConditionRuleData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.coded.CodedAbilityRegistry;
import com.jjktbf.model.move.MoveData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** Card-based editor for condition trees and their linked ability effects. */
public final class ConditionListEditor extends Table {

    private final List<AbilityConditionRuleData> rules;
    private final List<AbilityEffectData> effects;
    private final List<MoveData> moves;
    private final Runnable onDirty;
    private final Consumer<SoundCue> soundPlayer;
    private final Skin skin;
    private final Container<Actor> cards = new Container<>();

    public ConditionListEditor(
        List<AbilityConditionRuleData> rules,
        List<AbilityEffectData> effects,
        List<MoveData> moves,
        Runnable onDirty,
        Consumer<SoundCue> soundPlayer,
        Skin skin
    ) {
        super(skin);
        this.rules = rules;
        this.effects = effects == null ? List.of() : effects;
        this.moves = moves == null ? List.of() : moves;
        this.onDirty = onDirty;
        this.soundPlayer = soundPlayer == null ? cue -> { } : soundPlayer;
        this.skin = skin;
        defaults().left().pad(4f).growX();
        cards.fill(true, false);
        add(cards).growX().row();

        TextButton add = button("+ Add condition", () -> {
            AbilityConditionRuleData rule = AbilityConditionRuleData.allEffects(
                AbilityConditionData.manualActivation());
            if (!this.rules.isEmpty()) rule.targetEffectIds = new ArrayList<>();
            this.rules.add(rule);
            this.soundPlayer.accept(SoundCue.UI_CONFIRM);
            changed();
        });
        add(add).left().padTop(4f).row();
        rebuild();
    }

    private void rebuild() {
        Table list = new Table(skin);
        list.defaults().left().pad(4f).growX();
        for (int index = 0; index < rules.size(); index++) {
            list.add(buildCard(index, rules.get(index))).growX().row();
        }
        cards.setActor(list);
    }

    private Actor buildCard(int index, AbilityConditionRuleData rule) {
        Table card = new Table(skin);
        card.setBackground(skin.getDrawable("battle-card"));
        card.defaults().left().pad(4f).growX();
        card.pad(7f);

        Table header = new Table(skin);
        Label title = new Label("CONDITION " + (index + 1), skin, "small");
        title.setColor(Color.GOLD);
        header.add(title).left().growX();
        TextButton up = button("Up", () -> move(index, -1));
        up.setDisabled(index == 0);
        header.add(up).padLeft(4f);
        TextButton down = button("Down", () -> move(index, 1));
        down.setDisabled(index == rules.size() - 1);
        header.add(down).padLeft(4f);
        TextButton remove = button("X", () -> {
            if (rules.size() <= 1) return;
            rules.remove(index);
            soundPlayer.accept(SoundCue.UI_DELETE);
            changed();
        });
        remove.setDisabled(rules.size() <= 1);
        header.add(remove).padLeft(4f);
        card.add(header).growX().row();

        Label when = new Label("WHEN", skin, "small");
        when.setColor(Color.GOLD);
        card.add(when).padTop(5f).row();
        card.add(new ConditionTreeEditor(
            rule.condition,
            moves,
            onDirty,
            soundPlayer,
            skin)).growX().row();

        CheckBox sameTrigger = new CheckBox(
            " Match event conditions on the same combat event", skin);
        sameTrigger.setChecked(Boolean.TRUE.equals(rule.matchSameTrigger));
        sameTrigger.addListener(change(() -> {
            rule.matchSameTrigger = sameTrigger.isChecked() ? Boolean.TRUE : null;
            dirty();
        }));
        card.add(sameTrigger).growX().row();

        CheckBox chanceEnabled = new CheckBox(
            " Roll activation chance", skin);
        chanceEnabled.setChecked(Boolean.TRUE.equals(rule.activationChanceEnabled));
        TextField chance = new HoverTextField(formatPercent(
            rule.activationChance == null ? 1.0 : rule.activationChance), skin);
        chance.setTextFieldFilter((field, character) ->
            Character.isDigit(character) || character == '.');
        chance.setDisabled(!chanceEnabled.isChecked());
        chanceEnabled.addListener(change(() -> {
            soundPlayer.accept(SoundCue.UI_TOGGLE);
            rule.activationChanceEnabled = chanceEnabled.isChecked();
            if (rule.activationChance == null) rule.activationChance = 1.0;
            chance.setDisabled(!chanceEnabled.isChecked());
            dirty();
        }));
        chance.addListener(change(() -> {
            Double value = parseDouble(chance.getText());
            rule.activationChance = value == null ? null : value / 100.0;
            dirty();
        }));
        card.add(chanceEnabled).growX().row();
        Table chanceRow = new Table(skin);
        chanceRow.add(new Label("Activation chance %", skin)).padRight(8f);
        chanceRow.add(chance).growX();
        card.add(chanceRow).growX().row();

        Label then = new Label("THEN ACTIVATE", skin, "small");
        then.setColor(Color.GOLD);
        card.add(then).padTop(5f).row();
        CheckBox allEffects = new CheckBox(" All effects", skin);
        allEffects.setChecked(rule.targetEffectIds == null);
        allEffects.addListener(change(() -> {
            soundPlayer.accept(SoundCue.UI_TOGGLE);
            rule.targetEffectIds = allEffects.isChecked()
                ? null
                : effects.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(effect -> effect.effectId)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            changed();
        }));
        card.add(allEffects).growX().row();

        if (rule.targetEffectIds == null) {
            Label hint = new Label("Every current and future effect is linked to this condition.", skin, "small");
            hint.setColor(skin.get("text-dim", Color.class));
            hint.setWrap(true);
            card.add(hint).growX().row();
        } else {
            for (int effectIndex = 0; effectIndex < effects.size(); effectIndex++) {
                AbilityEffectData effect = effects.get(effectIndex);
                if (effect == null || effect.effectId == null) continue;
                CheckBox linked = new CheckBox(
                    " " + effectLabel(effectIndex, effect), skin);
                linked.setChecked(rule.targetEffectIds.contains(effect.effectId));
                linked.addListener(change(() -> {
                    if (linked.isChecked()) {
                        if (!rule.targetEffectIds.contains(effect.effectId)) {
                            rule.targetEffectIds.add(effect.effectId);
                        }
                    } else {
                        rule.targetEffectIds.remove(effect.effectId);
                    }
                    dirty();
                }));
                card.add(linked).growX().row();
            }
        }
        return card;
    }

    private void move(int index, int offset) {
        int target = index + offset;
        if (target < 0 || target >= rules.size()) return;
        Collections.swap(rules, index, target);
        soundPlayer.accept(SoundCue.UI_TOGGLE);
        changed();
    }

    private String effectLabel(int index, AbilityEffectData effect) {
        AbilityEffectType type;
        try { type = AbilityEffectType.fromName(effect.type); }
        catch (Exception ex) { return "Effect " + (index + 1) + " (invalid)"; }
        String label = "Effect " + (index + 1) + ": " + type.displayName();
        if (!effect.isCoded()) return label;
        return label + CodedAbilityRegistry.abilityFeatures().stream()
            .filter(feature -> feature.key().equalsIgnoreCase(effect.codedAbilityKey)
                && feature.feature().equalsIgnoreCase(effect.codedFeature))
            .map(feature -> " - " + feature.label())
            .findFirst().orElse("");
    }

    private TextButton button(String text, Runnable action) {
        TextButton button = new TextButton(text, skin);
        button.addListener(change(() -> {
            if (!button.isDisabled()) action.run();
        }));
        return button;
    }

    private static ChangeListener change(Runnable action) {
        return new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) { action.run(); }
        };
    }

    private void changed() {
        dirty();
        rebuild();
    }

    private void dirty() {
        if (onDirty != null) onDirty.run();
    }

    private static Double parseDouble(String value) {
        try {
            return value == null || value.isBlank() || ".".equals(value)
                ? null : Double.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String formatPercent(double fraction) {
        double percentage = fraction * 100.0;
        return percentage == Math.rint(percentage)
            ? String.valueOf((long) percentage) : String.valueOf(percentage);
    }
}
