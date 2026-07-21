package com.jjktbf.graphics.screens.editors;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.ui.editor.EditorScreenBase;
import com.jjktbf.graphics.ui.editor.EffectListEditor;
import com.jjktbf.graphics.ui.editor.ConditionTreeEditor;
import com.jjktbf.graphics.ui.editor.EnumSelectBox;
import com.jjktbf.graphics.ui.editor.HoverTextField;
import com.jjktbf.graphics.ui.editor.ValidationResult;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityConditionType;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.AbilityRepository;
import com.jjktbf.model.character.AbilityResolver;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterRepository;
import com.jjktbf.model.character.StatKey;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveRepository;
import com.jjktbf.model.technique.InnateTechniqueData;
import com.jjktbf.model.technique.TechniqueRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Graphical CRUD editor for passive abilities and move-backed active abilities. */
public class AbilityEditorScreen extends EditorScreenBase<AbilityData> {

    private static final String SELECT_MOVE = "[select a move]";
    private static final String SELECT_TECHNIQUE = "[select a technique]";
    private static final String SELECT_ABILITY = "[select an ability]";

    private final AbilityRepository repo;
    private final MoveRepository moveRepo;
    private final TechniqueRepository techniqueRepo;

    private Container<Actor> sourceValueContainer;
    private Container<Actor> activeSubContainer;
    private Container<Actor> effectsContainer;
    private Container<Actor> activationContainer;
    private Label modeHint;

    public AbilityEditorScreen(JJKGame game, AssetLoader assets) {
        super(game, assets);
        repo = new AbilityRepository("data/abilities");
        moveRepo = new MoveRepository("data/moves");
        techniqueRepo = new TechniqueRepository("data/techniques");
    }

    @Override protected String title() { return "ABILITY EDITOR"; }

    @Override
    protected AbilityData newDraft() {
        AbilityData ability = new AbilityData();
        ability.name = "New Ability";
        ability.flavourText = "";
        ability.mechanicText = "";
        ability.category = CategoryEnum.PASSIVE.name();
        ability.sourceType = SourceTypeEnum.CHARACTER.name();
        ability.effects = new ArrayList<>();
        ability.activationCondition = AbilityConditionData.always();
        ability.activationChanceEnabled = false;
        ability.activationChance = 1.0;
        ability.triggerThreshold = 0;
        ability.masteryThreshold = 0;
        return ability;
    }

    @Override
    protected AbilityData draftFromRecord(AbilityData stored) {
        AbilityData draft = new AbilityData();
        draft.id = stored.id;
        draft.name = stored.name;
        draft.flavourText = stored.flavourText;
        draft.mechanicText = stored.mechanicText;
        draft.category = stored.category;
        draft.sourceType = stored.sourceType;
        draft.sourceValue = stored.sourceValue;
        draft.codedAbilityKey = stored.codedAbilityKey;
        draft.codedFeature = stored.codedFeature;
        draft.effects = stored.effects == null
            ? new ArrayList<>()
            : stored.effects.stream()
                .filter(java.util.Objects::nonNull)
                .map(AbilityEffectData::copy)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        draft.activationCondition = stored.activationCondition == null
            ? AbilityConditionData.always() : stored.activationCondition.copy();
        draft.activationChanceEnabled = Boolean.TRUE.equals(stored.activationChanceEnabled);
        draft.activationChance = stored.activationChance == null ? 1.0 : stored.activationChance;
        draft.activeSubType = stored.activeSubType;
        draft.activeMoveId = stored.activeMoveId;
        draft.triggerCondition = stored.triggerCondition;
        draft.triggerThreshold = stored.triggerThreshold;
        draft.masteryThreshold = stored.masteryThreshold;
        return draft;
    }

    @Override protected String idOf(AbilityData record) { return record.id; }
    @Override protected String nextId() { return repo.nextId(); }
    @Override protected void stampNewId(AbilityData draft) { draft.id = repo.nextId(); }

    @Override
    protected String listLabel(AbilityData record) {
        String category = record.category != null ? " (" + record.category + ")" : "";
        return record.name + category;
    }

    @Override
    protected boolean isNewDraft(AbilityData draft) {
        return draft.id == null || draft.id.isEmpty() || repo.findById(draft.id).isEmpty();
    }

    @Override
    protected void reloadRecords() throws IOException {
        repo.load();
        moveRepo.load();
        techniqueRepo.load();
        records.clear();
        records.addAll(repo.getAll());
    }

    @Override
    protected ValidationResult validateAndSave(AbilityData ability) {
        String validationError = validationError(ability);
        if (validationError != null) return ValidationResult.error(validationError);

        String previousName = repo.findById(ability.id).map(record -> record.name).orElse(null);
        normalizeForSave(ability);
        rewriteNameBasedDependents(ability, previousName);
        try {
            if (isNewDraft(ability)) {
                ability.id = null;
                repo.add(ability);
            } else {
                repo.update(ability);
            }
            repo.save();
            TechniqueTreeRepositorySync.synchronize();
        } catch (Exception ex) {
            return ValidationResult.error("Save failed: " + ex.getMessage());
        }
        return ValidationResult.ok("Saved \"" + ability.name + "\".");
    }

    private String validationError(AbilityData ability) {
        if (ability.name == null || ability.name.trim().isEmpty()) return "Name is required.";
        boolean duplicateName = repo.getAll().stream().anyMatch(existing ->
            existing.name != null && existing.name.equalsIgnoreCase(ability.name.trim())
                && !java.util.Objects.equals(existing.id, ability.id));
        if (duplicateName) return "Another ability already uses that name.";

        if (!CategoryEnum.isValid(ability.category)) return "Choose PASSIVE or ACTIVE.";
        if (!SourceTypeEnum.isValid(ability.sourceType)) return "Choose a valid source type.";

        String sourceError = validateSource(ability);
        if (sourceError != null) return sourceError;

        if (ability.isCoded()) {
            return "Coded abilities are defined in source and cannot be edited here.";
        }

        if (ability.isActive()) {
            String moveError = validateMoveReference(
                ability.activeMoveId, "Choose the move that represents this active ability.");
            if (moveError != null) return moveError;
            return null;
        }

        if (ability.effects == null || ability.effects.isEmpty()) {
            return "A passive ability needs at least one effect.";
        }
        String conditionError = AbilityConditionType.validationError(ability.activationCondition);
        if (conditionError != null) return conditionError;
        if (Boolean.TRUE.equals(ability.activationChanceEnabled)
            && (ability.activationChance == null || !Double.isFinite(ability.activationChance)
                || ability.activationChance < 0 || ability.activationChance > 1)) {
            return "Activation chance must be between 0% and 100%.";
        }
        String conditionMoveError = validateConditionMoves(ability.activationCondition);
        if (conditionMoveError != null) return conditionMoveError;
        for (int i = 0; i < ability.effects.size(); i++) {
            AbilityEffectData effect = ability.effects.get(i);
            AbilityEffectType type;
            try {
                type = AbilityEffectType.fromName(effect == null ? null : effect.type);
            } catch (Exception ex) {
                return "Effect " + (i + 1) + " has an invalid type.";
            }
            String effectError = type.validationError(effect);
            if (effectError != null) {
                return "Effect " + (i + 1) + " (" + type.displayName() + "): " + effectError;
            }
            if (!ability.isAlwaysActive()
                && (type == AbilityEffectType.GRANT_MOVE
                    || type == AbilityEffectType.UNLOCK_TECHNIQUE
                    || type == AbilityEffectType.STAT_BONUS_POINTS
                    || type == AbilityEffectType.LOCK_MOVE_TAG)) {
                return "Effect " + (i + 1)
                    + " changes character acquisition and must use Always active.";
            }
            if (!ability.isAlwaysActive() && type == AbilityEffectType.AUTO_STATUS_APPLY) {
                return "Use Apply status for a conditional ability; automatic status timing is only for Always active.";
            }
            if (type == AbilityEffectType.GRANT_MOVE) {
                String moveError = validateMoveReference(
                    effect.moveId, "Effect " + (i + 1) + " references a move that does not exist.");
                if (moveError != null) return moveError;
            }
            if (type == AbilityEffectType.UNLOCK_TECHNIQUE
                && techniqueRepo.findByName(effect.stringValue).isEmpty()) {
                return "Effect " + (i + 1) + " references a technique that does not exist.";
            }
        }
        return null;
    }

    private String validateSource(AbilityData ability) {
        SourceTypeEnum source = SourceTypeEnum.valueOf(ability.sourceType.toUpperCase());
        return switch (source) {
            case CHARACTER -> null;
            case TECHNIQUE -> {
                if (techniqueRepo.findByName(ability.sourceValue).isEmpty()) {
                    yield "Choose an existing technique source.";
                }
                if (ability.masteryThreshold < 0 || ability.masteryThreshold > 300) {
                    yield "Technique mastery threshold must be between 0 and 300.";
                }
                yield null;
            }
            case MOVE -> validateMoveReference(
                ability.sourceValue, "Choose an existing move source.");
            case STAT_THRESHOLD -> AbilityResolver.parseStatRequirement(ability.sourceValue).isEmpty()
                ? "Choose a stat and a valid minimum value." : null;
            case ABILITY -> {
                if (ability.sourceValue == null
                    || ability.sourceValue.equalsIgnoreCase(ability.id)
                    || ability.sourceValue.equalsIgnoreCase(ability.name)) {
                    yield "An ability cannot use itself as its source.";
                }
                boolean exists = repo.getAll().stream().anyMatch(candidate ->
                    ability.sourceValue.equalsIgnoreCase(candidate.id)
                        || ability.sourceValue.equalsIgnoreCase(candidate.name));
                if (!exists) yield "Choose an existing parent ability.";
                yield hasAbilitySourceCycle(ability)
                    ? "Ability sources cannot form a dependency cycle." : null;
            }
        };
    }

    private String validateMoveReference(String moveId, String missingMessage) {
        if (moveId == null || moveId.isBlank()) return missingMessage;
        MoveData move = moveRepo.findById(moveId).orElse(null);
        if (move == null) return missingMessage;
        try {
            move.toMove();
            return null;
        } catch (Exception ex) {
            return "Referenced move \"" + move.name + "\" is invalid: " + ex.getMessage();
        }
    }

    private boolean hasAbilitySourceCycle(AbilityData draftAbility) {
        java.util.Set<String> visited = new java.util.HashSet<>();
        AbilityData current = draftAbility;
        while (current != null && "ABILITY".equalsIgnoreCase(current.sourceType)) {
            String key = current.id != null && !current.id.isBlank()
                ? "id:" + current.id
                : "name:" + String.valueOf(current.name).toLowerCase();
            if (!visited.add(key)) return true;
            String reference = current.sourceValue;
            if (reference == null || reference.isBlank()) return false;
            if (reference.equalsIgnoreCase(draftAbility.id)
                || reference.equalsIgnoreCase(draftAbility.name)) {
                current = draftAbility;
            } else {
                current = repo.getAll().stream()
                    .filter(candidate -> reference.equalsIgnoreCase(candidate.id)
                        || reference.equalsIgnoreCase(candidate.name))
                    .findFirst()
                    .orElse(null);
            }
        }
        return false;
    }

    private void normalizeForSave(AbilityData ability) {
        ability.name = ability.name.trim();
        ability.category = ability.category.toUpperCase();
        ability.sourceType = ability.sourceType.toUpperCase();
        if (SourceTypeEnum.CHARACTER.name().equals(ability.sourceType)) {
            ability.sourceValue = null;
        }
        if (!SourceTypeEnum.TECHNIQUE.name().equals(ability.sourceType)) {
            ability.masteryThreshold = 0;
        }
        if (SourceTypeEnum.ABILITY.name().equals(ability.sourceType)) {
            repo.getAll().stream()
                .filter(parent -> ability.sourceValue.equalsIgnoreCase(parent.id)
                    || ability.sourceValue.equalsIgnoreCase(parent.name))
                .findFirst()
                .ifPresent(parent -> ability.sourceValue = parent.id);
        }

        if (ability.isActive()) {
            ability.activeSubType = "QUEUED";
            ability.triggerCondition = null;
            ability.triggerThreshold = 0;
            ability.effects = new ArrayList<>();
            ability.activationCondition = null;
            ability.activationChanceEnabled = null;
            ability.activationChance = null;
        } else {
            ability.activeSubType = null;
            ability.activeMoveId = null;
            ability.triggerCondition = null;
            ability.triggerThreshold = 0;
            if (ability.activationCondition == null) {
                ability.activationCondition = AbilityConditionData.always();
            }
            if (ability.activationCondition.containsAlways()) {
                ability.activationCondition = AbilityConditionData.always();
            } else {
                normalizeCondition(ability.activationCondition);
            }
            if (!Boolean.TRUE.equals(ability.activationChanceEnabled)) {
                ability.activationChanceEnabled = null;
                ability.activationChance = null;
            }
            for (AbilityEffectData effect : ability.effects) {
                AbilityEffectType.fromName(effect.type).clearUnusedFields(effect);
            }
        }
    }

    private void rewriteNameBasedDependents(AbilityData ability, String previousName) {
        if (previousName == null || previousName.equals(ability.name) || ability.id == null) return;
        for (AbilityData candidate : repo.getAll()) {
            if (candidate == ability || !"ABILITY".equalsIgnoreCase(candidate.sourceType)) continue;
            if (candidate.sourceValue != null && candidate.sourceValue.equalsIgnoreCase(previousName)) {
                candidate.sourceValue = ability.id;
            }
        }
    }

    @Override
    protected ValidationResult delete(String id) {
        AbilityData deleted = repo.findById(id).orElse(null);
        if (deleted == null) return ValidationResult.error("Ability no longer exists.");

        AbilityData dependent = repo.getAll().stream()
            .filter(ability -> !id.equals(ability.id))
            .filter(ability -> "ABILITY".equalsIgnoreCase(ability.sourceType))
            .filter(ability -> ability.sourceValue != null)
            .filter(ability -> ability.sourceValue.equalsIgnoreCase(deleted.id)
                || ability.sourceValue.equalsIgnoreCase(deleted.name))
            .findFirst()
            .orElse(null);
        if (dependent != null) {
            return ValidationResult.error(
                "Cannot delete: \"" + dependent.name + "\" uses this ability as its source.");
        }

        try {
            Map<String, String> remappedIds = new LinkedHashMap<>();
            int nextIndex = 0;
            for (AbilityData ability : repo.getAll()) {
                if (id.equals(ability.id)) continue;
                remappedIds.put(ability.id,
                    com.jjktbf.model.repo.BaseRepository.formatId(nextIndex++));
            }

            for (AbilityData ability : repo.getAll()) {
                if ("ABILITY".equalsIgnoreCase(ability.sourceType)
                    && remappedIds.containsKey(ability.sourceValue)) {
                    ability.sourceValue = remappedIds.get(ability.sourceValue);
                }
            }

            CharacterRepository characterRepo = new CharacterRepository("data/characters");
            characterRepo.load();
            for (CharacterData character : characterRepo.getAll()) {
                if (character.abilityIds == null) continue;
                character.abilityIds = character.abilityIds.stream()
                    .filter(abilityId -> !id.equals(abilityId))
                    .map(abilityId -> remappedIds.getOrDefault(abilityId, abilityId))
                    .distinct()
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            }

            repo.delete(id);
            repo.save();
            characterRepo.save();
            TechniqueTreeRepositorySync.synchronize(
                com.jjktbf.model.technique.SkillTreeNodeData.ABILITY, remappedIds, id);
            return ValidationResult.ok("Deleted.");
        } catch (Exception ex) {
            return ValidationResult.error("Delete failed: " + ex.getMessage());
        }
    }

    @Override
    protected Actor buildDetailForm(AbilityData ability) {
        if (ability.isCoded()) {
            Table form = formRoot();
            Table coded = formSection(form, "CODED ABILITY");
            coded.add(idBadge(ability.id)).left().row();
            coded.add(new Label(ability.name, skin)).left().row();
            coded.add(formHint(ability.mechanicText)).growX().row();
            coded.add(formHint("This ability is implemented by compiled game code and is read-only in the editor."))
                .growX().row();
            return form;
        }
        Table form = formRoot();

        Table identity = formSection(form, "NAME");
        identity.add(idBadge(ability.id)).left().row();
        identity.add(labelledField("Name", ability.name, value -> ability.name = value)).growX().row();
        identity.add(labelledField("Flavour Text", ability.flavourText,
            value -> ability.flavourText = value)).growX().row();
        identity.add(labelledField("Mechanic Text", ability.mechanicText,
            value -> ability.mechanicText = value)).growX().row();

        Table category = formSection(form, "CATEGORY");
        category.add(labelledRow("Category", new EnumSelectBox<>(
            CategoryEnum.class, ability.category, false,
            value -> {
                ability.category = value;
                if (ability.isActive()) {
                    ability.activeSubType = "QUEUED";
                    ability.triggerCondition = null;
                    ability.triggerThreshold = 0;
                }
                refreshConditionalSections(ability);
            }, skin))).growX().row();

        Table source = formSection(form, "SOURCE");
        source.add(labelledRow("Granted by", new EnumSelectBox<>(
            SourceTypeEnum.class, ability.sourceType, false,
            value -> {
                ability.sourceType = value;
                initialiseSourceDefaults(ability);
                refreshConditionalSections(ability);
            }, skin))).growX().row();
        sourceValueContainer = new Container<>();
        sourceValueContainer.setActor(buildSourceValue(ability));
        source.add(sourceValueContainer).growX().row();

        Table active = formSection(form, "ACTIVE SETTINGS");
        activeSubContainer = new Container<>();
        activeSubContainer.setActor(buildActiveSettings(ability));
        active.add(activeSubContainer).growX().row();
        modeHint = new Label(modeHintText(ability), skin, "small");
        modeHint.setColor(skin.get("text-dim", Color.class));
        modeHint.setWrap(true);
        active.add(modeHint).growX().row();

        Table effects = formSection(form, "EFFECTS");
        effectsContainer = new Container<>();
        effectsContainer.setActor(buildEffects(ability));
        effects.add(effectsContainer).growX().row();

        Table activation = formSection(form, "PASSIVE ACTIVATION");
        activationContainer = new Container<>();
        activationContainer.setActor(buildActivation(ability));
        activation.add(activationContainer).growX().row();

        return form;
    }

    private Actor buildSourceValue(AbilityData ability) {
        Table table = new Table(skin);
        table.defaults().left().pad(4).growX();
        SourceTypeEnum source = safeSource(ability.sourceType);

        switch (source) {
            case CHARACTER -> {
                table.add(formHint("Assign this ability directly in the Character Editor.")).row();
            }
            case TECHNIQUE -> {
                SelectBox<String> technique = techniqueSelect(ability.sourceValue, value ->
                    ability.sourceValue = techniqueNameFromLabel(value));
                table.add(labelledRow("Technique", technique)).growX().row();
                table.add(formHint(
                    "This ability is added to the technique's skill tree. Configure its unlock rules there."))
                    .row();
            }
            case MOVE -> {
                SelectBox<String> move = moveSelect(ability.sourceValue,
                    value -> ability.sourceValue = idFromLabel(value));
                table.add(labelledRow("Known move", move)).growX().row();
                table.add(formHint("Granted automatically while the character knows this move.")).row();
            }
            case STAT_THRESHOLD -> {
                AbilityResolver.StatRequirement requirement =
                    AbilityResolver.parseStatRequirement(ability.sourceValue)
                        .orElse(new AbilityResolver.StatRequirement(StatKey.VITALITY, 80));

                SelectBox<String> stat = statSelect(requirement.stat());
                TextField minimum = new HoverTextField(String.valueOf(requirement.minimum()), skin);
                minimum.setTextFieldFilter((field, character) ->
                    Character.isDigit(character) || character == '-');
                Runnable update = () -> {
                    Integer value = parseInteger(minimum.getText());
                    if (value != null) {
                        ability.sourceValue = statFromLabel(stat.getSelected()).fieldName + ">=" + value;
                    } else {
                        ability.sourceValue = null;
                    }
                };
                stat.addListener(new ChangeListener() {
                    @Override public void changed(ChangeEvent event, Actor actor) { update.run(); }
                });
                minimum.addListener(new ChangeListener() {
                    @Override public void changed(ChangeEvent event, Actor actor) { update.run(); }
                });
                table.add(labelledRow("Stat", stat)).growX().row();
                table.add(labelledRow("Minimum value", minimum)).growX().row();
                table.add(formHint("Granted automatically while the stat meets this minimum.")).row();
            }
            case ABILITY -> {
                SelectBox<String> parent = abilitySelect(ability, ability.sourceValue,
                    value -> ability.sourceValue = idFromLabel(value));
                table.add(labelledRow("Parent ability", parent)).growX().row();
                table.add(formHint("Granted automatically after the parent ability is acquired.")).row();
            }
        }
        return table;
    }

    private Actor buildActiveSettings(AbilityData ability) {
        Table table = new Table(skin);
        table.defaults().left().pad(4).growX();
        if (!ability.isActive()) {
            table.add(formHint("Passive abilities are always on and use the Effects section.")).row();
            return table;
        }

        SelectBox<String> move = moveSelect(ability.activeMoveId,
            value -> ability.activeMoveId = idFromLabel(value));
        table.add(labelledRow("Ability move", move)).growX().row();
        table.add(formHint("The linked move contains the active ability's power, cost, and statuses.")).row();
        return table;
    }

    private Actor buildEffects(AbilityData ability) {
        if (!ability.isPassive()) {
            return formHint("Active mechanics are configured on the linked move, so no passive effects apply.");
        }
        if (ability.effects == null) ability.effects = new ArrayList<>();
        return new EffectListEditor(
            ability.effects,
            moveRepo.getAll(),
            techniqueRepo.getAll(),
            this::markDirty,
            this::rebuildDetail,
            skin);
    }

    private Actor buildActivation(AbilityData ability) {
        if (!ability.isPassive()) {
            return formHint("Active abilities are activated by choosing their linked move.");
        }
        if (ability.activationCondition == null) {
            ability.activationCondition = AbilityConditionData.always();
        }
        Table table = new Table(skin);
        table.defaults().left().pad(4).growX();

        CheckBox enabled = new CheckBox(" Roll an activation chance when conditions are met", skin);
        enabled.setChecked(Boolean.TRUE.equals(ability.activationChanceEnabled));
        TextField chance = new HoverTextField(formatPercent(
            ability.activationChance == null ? 1.0 : ability.activationChance), skin);
        chance.setTextFieldFilter((field, character) -> Character.isDigit(character) || character == '.');
        chance.setDisabled(!enabled.isChecked());
        enabled.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                ability.activationChanceEnabled = enabled.isChecked();
                if (ability.activationChance == null) ability.activationChance = 1.0;
                chance.setDisabled(!enabled.isChecked());
                markDirty();
            }
        });
        chance.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                Double value = parseDouble(chance.getText());
                ability.activationChance = value == null ? null : value / 100.0;
                markDirty();
            }
        });
        table.add(enabled).colspan(2).growX().row();
        table.add(labelledRow("Activation chance %", chance)).colspan(2).growX().row();
        table.add(new ConditionTreeEditor(
            ability.activationCondition,
            moveRepo.getAll(),
            this::markDirty,
            skin)).colspan(2).growX().row();
        table.add(formHint("AND/OR groups may be nested. Always active replaces every other condition.")).colspan(2).row();
        return table;
    }

    private String modeHintText(AbilityData ability) {
        return ability.isActive()
            ? "When acquired, the linked move is added outside the normal move-slot and prerequisite rules."
            : "Only passive effects are applied continuously in combat.";
    }

    private void refreshConditionalSections(AbilityData ability) {
        markDirty();
        if (sourceValueContainer != null) sourceValueContainer.setActor(buildSourceValue(ability));
        if (activeSubContainer != null) activeSubContainer.setActor(buildActiveSettings(ability));
        if (effectsContainer != null) effectsContainer.setActor(buildEffects(ability));
        if (activationContainer != null) activationContainer.setActor(buildActivation(ability));
        if (modeHint != null) modeHint.setText(modeHintText(ability));
    }

    private static void initialiseSourceDefaults(AbilityData ability) {
        SourceTypeEnum source = safeSource(ability.sourceType);
        ability.masteryThreshold = 0;
        ability.sourceValue = source == SourceTypeEnum.STAT_THRESHOLD
            ? new AbilityResolver.StatRequirement(StatKey.VITALITY, 80).expression()
            : null;
    }

    private SelectBox<String> moveSelect(String currentId, Consumer<String> onChange) {
        List<String> labels = new ArrayList<>();
        labels.add(SELECT_MOVE);
        labels.addAll(moveRepo.getAll().stream()
            .map(AbilityEditorScreen::moveLabel)
            .toList());
        String selected = moveLabelForId(currentId);
        if (selected != null && labels.stream().noneMatch(selected::equals)) labels.add(selected);
        return select(labels, selected == null ? SELECT_MOVE : selected, onChange);
    }

    private SelectBox<String> techniqueSelect(String current, Consumer<String> onChange) {
        List<String> labels = new ArrayList<>();
        labels.add(SELECT_TECHNIQUE);
        labels.addAll(techniqueRepo.getAll().stream()
            .map(technique -> technique.name)
            .toList());
        String selected = techniqueLabel(current);
        if (selected != null && labels.stream().noneMatch(selected::equals)) labels.add(selected);
        return select(labels, selected == null ? SELECT_TECHNIQUE : selected, onChange);
    }

    private SelectBox<String> abilitySelect(
        AbilityData draft,
        String currentId,
        Consumer<String> onChange
    ) {
        List<String> labels = new ArrayList<>();
        labels.add(SELECT_ABILITY);
        labels.addAll(repo.getAll().stream()
            .filter(ability -> !java.util.Objects.equals(ability.id, draft.id))
            .map(AbilityEditorScreen::abilityLabel)
            .toList());
        String selected = abilityLabelForReference(currentId);
        if (selected != null && labels.stream().noneMatch(selected::equals)) labels.add(selected);
        return select(labels, selected == null ? SELECT_ABILITY : selected, onChange);
    }

    private SelectBox<String> statSelect(StatKey current) {
        List<String> labels = java.util.Arrays.stream(StatKey.values()).map(stat -> stat.label).toList();
        return select(labels, current.label, ignored -> { });
    }

    private SelectBox<String> select(
        List<String> labels,
        String selected,
        Consumer<String> onChange
    ) {
        SelectBox<String> box = new SelectBox<>(skin);
        box.setItems(labels.toArray(new String[0]));
        box.setSelected(selected);
        box.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                onChange.accept(box.getSelected());
                markDirty();
            }
        });
        return box;
    }

    private static String moveLabel(MoveData move) {
        return move.id + " - " + move.name;
    }

    private String moveLabelForId(String id) {
        if (id == null || id.isBlank()) return null;
        return moveRepo.findById(id).map(AbilityEditorScreen::moveLabel)
            .orElse(id + " - (missing)");
    }

    private static String abilityLabel(AbilityData ability) {
        return ability.id + " - " + ability.name;
    }

    private String abilityLabelForReference(String reference) {
        if (reference == null || reference.isBlank()) return null;
        return repo.getAll().stream()
            .filter(ability -> reference.equalsIgnoreCase(ability.id)
                || reference.equalsIgnoreCase(ability.name))
            .findFirst()
            .map(AbilityEditorScreen::abilityLabel)
            .orElse(reference + " - (missing)");
    }

    private String techniqueLabel(String name) {
        if (name == null || name.isBlank()) return null;
        return techniqueRepo.findByName(name).map(technique -> technique.name)
            .orElse(name + " (missing)");
    }

    private static String techniqueNameFromLabel(String label) {
        if (label == null || label.startsWith("[")) return null;
        return label.endsWith(" (missing)")
            ? label.substring(0, label.length() - " (missing)".length())
            : label;
    }

    private static String idFromLabel(String label) {
        if (label == null || label.startsWith("[")) return null;
        int separator = label.indexOf(" - ");
        return separator < 0 ? label.trim() : label.substring(0, separator).trim();
    }

    private static StatKey statFromLabel(String label) {
        for (StatKey stat : StatKey.values()) {
            if (stat.label.equals(label)) return stat;
        }
        return StatKey.VITALITY;
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isBlank() || "-".equals(value)) return null;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Double parseDouble(String value) {
        if (value == null || value.isBlank() || ".".equals(value)) return null;
        try { return Double.valueOf(value); }
        catch (NumberFormatException ex) { return null; }
    }

    private String validateConditionMoves(AbilityConditionData condition) {
        if (condition == null) return null;
        if (AbilityConditionType.MOVE_USED.name().equalsIgnoreCase(condition.type)) {
            String error = validateMoveReference(
                condition.moveId, "An activation condition references a move that does not exist.");
            if (error != null) return error;
        }
        if (condition.children != null) {
            for (AbilityConditionData child : condition.children) {
                String error = validateConditionMoves(child);
                if (error != null) return error;
            }
        }
        return null;
    }

    private static void normalizeCondition(AbilityConditionData condition) {
        AbilityConditionType type = AbilityConditionType.fromName(condition.type);
        type.clearUnusedFields(condition);
        if (condition.children != null) {
            condition.children.forEach(AbilityEditorScreen::normalizeCondition);
        }
    }

    private static String formatPercent(double fraction) {
        double percentage = fraction * 100.0;
        return percentage == Math.rint(percentage)
            ? String.valueOf((long) percentage) : String.valueOf(percentage);
    }

    private static SourceTypeEnum safeSource(String source) {
        try {
            return SourceTypeEnum.valueOf(source == null ? "CHARACTER" : source.toUpperCase());
        } catch (Exception ex) {
            return SourceTypeEnum.CHARACTER;
        }
    }

    private enum CategoryEnum {
        PASSIVE,
        ACTIVE;

        private static boolean isValid(String value) {
            if (value == null) return false;
            for (CategoryEnum category : values()) {
                if (category.name().equalsIgnoreCase(value)) return true;
            }
            return false;
        }
    }

    private enum SourceTypeEnum {
        CHARACTER,
        TECHNIQUE,
        MOVE,
        STAT_THRESHOLD,
        ABILITY;

        private static boolean isValid(String value) {
            if (value == null) return false;
            for (SourceTypeEnum source : values()) {
                if (source.name().equalsIgnoreCase(value)) return true;
            }
            return false;
        }
    }
}
