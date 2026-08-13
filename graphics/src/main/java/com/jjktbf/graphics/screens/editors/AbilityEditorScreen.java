package com.jjktbf.graphics.screens.editors;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.ui.DynamicSelectBox;
import com.jjktbf.graphics.ui.editor.EditorScreenBase;
import com.jjktbf.graphics.ui.editor.EffectListEditor;
import com.jjktbf.graphics.ui.editor.ConditionListEditor;
import com.jjktbf.graphics.ui.editor.EnumSelectBox;
import com.jjktbf.graphics.ui.editor.HoverTextField;
import com.jjktbf.graphics.ui.editor.ValidationResult;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityConditionRuleData;
import com.jjktbf.model.character.AbilityConditionType;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectParameter;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.AbilityRepository;
import com.jjktbf.model.character.AbilityResolver;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterRepository;
import com.jjktbf.model.character.CharacterType;
import com.jjktbf.model.character.StatKey;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveRepository;
import com.jjktbf.model.move.StatusEffectType;
import com.jjktbf.model.technique.InnateTechniqueData;
import com.jjktbf.model.technique.TechniqueRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Graphical CRUD editor for always-on passive and conditionally activated abilities. */
public class AbilityEditorScreen extends EditorScreenBase<AbilityData> {

    private static final String SELECT_MOVE = "[select a move]";
    private static final String SELECT_TECHNIQUE = "[select a technique]";
    private static final String SELECT_ABILITY = "[select an ability]";
    private static final String PASSIVE_SECTION = "PASSIVE";
    private static final String ACTIVE_SECTION = "ACTIVE";
    private static final List<String> ABILITY_RECORD_SECTIONS = List.of(
        PASSIVE_SECTION, ACTIVE_SECTION);

    private final AbilityRepository repo;
    private final MoveRepository moveRepo;
    private final TechniqueRepository techniqueRepo;
    private final CharacterRepository charRepo;

    private Container<Actor> sourceValueContainer;
    private Container<Actor> effectsContainer;
    private Container<Actor> activationContainer;

    public AbilityEditorScreen(JJKGame game, AssetLoader assets) {
        super(game, assets);
        repo = new AbilityRepository("data/abilities");
        moveRepo = new MoveRepository("data/moves");
        techniqueRepo = new TechniqueRepository("data/techniques");
        charRepo = new CharacterRepository("data/characters");
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
        ability.masteryThreshold = 0;
        return ability;
    }

    @Override
    protected AbilityData draftFromRecord(AbilityData stored) {
        AbilityData draft = copyAbility(stored);
        draft.migrateActivationData();
        normalizeLegacyStatusAmounts(draft);
        return draft;
    }

    private static AbilityData copyAbility(AbilityData stored) {
        AbilityData draft = new AbilityData();
        draft.id = stored.id;
        draft.name = stored.name;
        draft.flavourText = stored.flavourText;
        draft.mechanicText = stored.mechanicText;
        draft.category = stored.category;
        draft.sourceType = stored.sourceType;
        draft.sourceValue = stored.sourceValue;
        draft.effects = stored.effects == null
            ? new ArrayList<>()
            : stored.effects.stream()
                .filter(java.util.Objects::nonNull)
                .map(AbilityEffectData::copy)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        draft.activationConditions = stored.activationConditions == null
            ? null
            : stored.activationConditions.stream()
                .filter(java.util.Objects::nonNull)
                .map(AbilityConditionRuleData::copy)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        draft.activationCondition = stored.activationCondition == null
            ? null : stored.activationCondition.copy();
        draft.activationChanceEnabled = stored.activationChanceEnabled;
        draft.activationChance = stored.activationChance;
        draft.masteryThreshold = stored.masteryThreshold;
        return draft;
    }

    @Override protected String idOf(AbilityData record) { return record.id; }
    @Override protected String nextId() { return repo.nextId(); }
    @Override protected void stampNewId(AbilityData draft) { draft.id = repo.nextId(); }

    @Override
    protected String listLabel(AbilityData record) {
        return record.name;
    }

    @Override
    protected List<String> recordSections() {
        return ABILITY_RECORD_SECTIONS;
    }

    @Override
    protected String recordSection(AbilityData record) {
        return abilityRecordSection(record);
    }

    static String abilityRecordSection(AbilityData record) {
        return record != null && record.isActive() ? ACTIVE_SECTION : PASSIVE_SECTION;
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
        charRepo.load();
        records.clear();
        records.addAll(repo.getAll());
    }

    @Override
    protected ValidationResult validateAndSave(AbilityData ability) {
        AbilityData toSave = copyAbility(ability);
        normalizeLegacyStatusAmounts(toSave);
        String validationError = validationError(toSave);
        if (validationError != null) return ValidationResult.error(validationError);

        boolean adding = isNewDraft(ability);
        AbilityData previous = adding ? null : repo.findById(toSave.id).orElse(null);
        String previousName = previous == null ? null : previous.name;
        normalizeForSave(toSave);
        Map<AbilityData, String> previousSources = new java.util.IdentityHashMap<>();
        repo.getAll().forEach(record -> previousSources.put(record, record.sourceValue));
        boolean repositoryMutated = false;
        try {
            if (adding) {
                toSave.id = null;
                repo.add(toSave);
            } else {
                repo.update(toSave);
            }
            repositoryMutated = true;
            rewriteNameBasedDependents(toSave, previousName);
            repo.save();
        } catch (Exception ex) {
            previousSources.forEach((record, source) -> record.sourceValue = source);
            if (repositoryMutated) {
                try {
                    if (adding) repo.delete(toSave.id);
                    else if (previous != null) repo.update(previous);
                } catch (RuntimeException ignored) {
                    // Preserve the persistence error as the useful result.
                }
            }
            return ValidationResult.error("Save failed: " + ex.getMessage());
        }
        ability.id = toSave.id;
        try {
            TechniqueTreeRepositorySync.synchronize();
        } catch (Exception ex) {
            return ValidationResult.ok("Saved \"" + toSave.name
                + "\", but technique tree sync failed: " + ex.getMessage());
        }
        return ValidationResult.ok("Saved \"" + toSave.name + "\".");
    }

    static void normalizeLegacyStatusAmounts(AbilityData ability) {
        if (ability == null || ability.effects == null) return;
        for (AbilityEffectData effect : ability.effects) {
            if (effect == null || effect.type == null || effect.stringValue == null) continue;
            AbilityEffectType type;
            try { type = AbilityEffectType.fromName(effect.type); }
            catch (IllegalArgumentException ignored) { continue; }
            if (!type.uses(AbilityEffectParameter.STATUS_TYPE)
                || !type.uses(AbilityEffectParameter.MAGNITUDE)) {
                continue;
            }
            double storedAmount = effect.magnitude != null ? effect.magnitude : 0.0;
            String storedType = effect.stringValue;
            try {
                effect.stringValue = StatusEffectType.fromName(
                    storedType, storedAmount).name();
                effect.magnitude = StatusEffectType.normalizeStoredMagnitude(
                    storedType, storedAmount);
            } catch (IllegalArgumentException ignored) {
                // Normal validation reports removed or unknown status names.
            }
        }
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

        if (ability.effects == null || ability.effects.isEmpty()) {
            return "An ability needs at least one effect.";
        }
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
            if (type.isMoveOnly()) {
                return "Effect " + (i + 1) + " (" + type.displayName()
                    + ") can only be attached to a move.";
            }
            if (ability.isPassive() && type.requiresActivation()) {
                return "Effect " + (i + 1) + " (" + type.displayName()
                    + ") needs an activation condition and can only be used by an active ability.";
            }
            if (ability.isActive() && type.isPassiveOnly()) {
                return "Effect " + (i + 1) + " (" + type.displayName()
                    + ") only applies while a passive ability is assigned.";
            }
            if (type == AbilityEffectType.GRANT_MOVE
                || type == AbilityEffectType.UNLOCK_MOVE) {
                String moveError = validateMoveReference(
                    effect.moveId, "Effect " + (i + 1) + " references a move that does not exist.");
                if (moveError != null) return moveError;
            }
            if (type == AbilityEffectType.GRANT_ABILITY
                && repo.findById(effect.abilityId).isEmpty()) {
                return "Effect " + (i + 1) + " references an ability that does not exist.";
            }
            if (type == AbilityEffectType.UNLOCK_TECHNIQUE
                && techniqueRepo.findByName(effect.stringValue).isEmpty()) {
                return "Effect " + (i + 1) + " references a technique that does not exist.";
            }
            if (type == AbilityEffectType.SUMMON_CHARACTER) {
                String summonError = summonReferenceValidationError(
                    effect.characterId, charRepo.getAll());
                if (summonError != null) {
                    return "Effect " + (i + 1) + " (" + type.displayName()
                        + "): " + summonError;
                }
            }
        }
        String effectIdError = AbilityConditionRuleData.effectIdValidationError(ability.effects);
        if (effectIdError != null) return effectIdError;
        if (ability.isActive()) {
            String ruleError = AbilityConditionRuleData.validationError(
                ability.activationConditions, ability.effects);
            if (ruleError != null) return ruleError;
            for (AbilityConditionRuleData rule : ability.activationConditions) {
                String conditionMoveError = validateConditionMoves(rule.condition);
                if (conditionMoveError != null) return conditionMoveError;
            }
        }
        return null;
    }

    private String validateSource(AbilityData ability) {
        SourceTypeEnum source = SourceTypeEnum.valueOf(ability.sourceType.toUpperCase());
        return switch (source) {
            case CHARACTER, SHIKIGAMI -> null;
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

    static String summonReferenceValidationError(
        String characterId,
        List<CharacterData> characters
    ) {
        if (characterId == null || characterId.isBlank()) {
            return "Choose an existing Shikigami summon target.";
        }
        CharacterData target = characters == null ? null : characters.stream()
            .filter(java.util.Objects::nonNull)
            .filter(character -> characterId.equals(character.id))
            .findFirst().orElse(null);
        if (target == null) return "The summon target does not exist.";
        try {
            return target.effectiveType() == CharacterType.SHIKIGAMI
                ? null : "The summon target must be a Shikigami.";
        } catch (IllegalArgumentException ignored) {
            return "The summon target must be a Shikigami.";
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
        if (SourceTypeEnum.CHARACTER.name().equals(ability.sourceType)
            || SourceTypeEnum.SHIKIGAMI.name().equals(ability.sourceType)) {
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

        AbilityData.ensureEffectIds(ability.effects);
        if (ability.isActive()) {
            if (ability.activationConditions == null || ability.activationConditions.isEmpty()) {
                ability.activationConditions = new ArrayList<>(List.of(
                    AbilityConditionRuleData.allEffects(
                        AbilityConditionData.manualActivation())));
            }
            pruneConditionTargets(ability);
            for (AbilityConditionRuleData rule : ability.activationConditions) {
                if (rule.condition.containsAlways()) {
                    rule.condition = AbilityConditionData.always();
                } else {
                    normalizeCondition(rule.condition);
                }
                if (!Boolean.TRUE.equals(rule.activationChanceEnabled)) {
                    rule.activationChanceEnabled = null;
                    rule.activationChance = null;
                }
                if (!Boolean.TRUE.equals(rule.matchSameTrigger)) {
                    rule.matchSameTrigger = null;
                }
            }
        } else {
            ability.activationConditions = null;
        }
        ability.activationCondition = null;
        ability.activationChanceEnabled = null;
        ability.activationChance = null;
        for (AbilityEffectData effect : ability.effects) {
            AbilityEffectType.fromName(effect.type).clearUnusedFields(effect);
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
        if (dependent == null) {
            dependent = repo.getAll().stream()
                .filter(ability -> !id.equals(ability.id) && ability.effects != null)
                .filter(ability -> ability.effects.stream()
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(effect -> AbilityEffectType.GRANT_ABILITY.name()
                        .equalsIgnoreCase(effect.type) && id.equals(effect.abilityId)))
                .findFirst().orElse(null);
        }
        if (dependent != null) {
            return ValidationResult.error(
                "Cannot delete: \"" + dependent.name + "\" references this ability.");
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
                if (ability.effects != null) {
                    ability.effects.stream()
                        .filter(java.util.Objects::nonNull)
                        .filter(effect -> AbilityEffectType.GRANT_ABILITY.name()
                            .equalsIgnoreCase(effect.type))
                        .forEach(effect -> effect.abilityId = remappedIds.getOrDefault(
                            effect.abilityId, effect.abilityId));
                }
            }

            CharacterRepository characterRepo = new CharacterRepository("data/characters");
            characterRepo.load();
            for (CharacterData character : characterRepo.getAll()) {
                if (character.abilityIds != null) {
                    character.abilityIds = remapCharacterAbilityIds(
                        character.abilityIds, id, remappedIds);
                }
                if (character.availableAbilityIds != null) {
                    character.availableAbilityIds = remapCharacterAbilityIds(
                        character.availableAbilityIds, id, remappedIds);
                }
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

    private static List<String> remapCharacterAbilityIds(
        List<String> ids,
        String deletedId,
        Map<String, String> remappedIds
    ) {
        return ids.stream()
            .filter(abilityId -> !deletedId.equals(abilityId))
            .map(abilityId -> remappedIds.getOrDefault(abilityId, abilityId))
            .distinct()
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    @Override
    protected Actor buildDetailForm(AbilityData ability) {
        activationContainer = null;
        AbilityData.ensureEffectIds(ability.effects);
        if (ability.isActive()) initialiseActivationDefaults(ability);
        pruneConditionTargets(ability);
        Table form = formRoot();

        Table identity = formSection(form, "NAME");
        identity.add(idBadge(ability.id)).left().row();
        identity.add(labelledField("Name", ability.name, value -> ability.name = value)).growX().row();
        identity.add(labelledField("Flavour Text", ability.flavourText,
            value -> ability.flavourText = value)).growX().row();
        identity.add(labelledKeywordField("Mechanic Text", ability.mechanicText,
            value -> ability.mechanicText = value)).growX().row();

        Table category = formSection(form, "CATEGORY");
        category.add(labelledRow("Category", new EnumSelectBox<>(
            CategoryEnum.class, ability.category, false,
            value -> {
                ability.category = value;
                initialiseCategoryDefaults(ability);
                markDirty();
                rebuildDetail();
            }, skin))).growX().row();
        if (ability.isPassive()) {
            category.add(formHint("Passive abilities are always active while assigned.")).growX().row();
        }

        Table source = formSection(form, "SOURCE");
        source.add(labelledRow("Available from", new EnumSelectBox<>(
            SourceTypeEnum.class, ability.sourceType, false,
            value -> {
                ability.sourceType = value;
                initialiseSourceDefaults(ability);
                if (safeSource(ability.sourceType) != SourceTypeEnum.TECHNIQUE) {
                    clearMasteryProgression(ability);
                }
                refreshConditionalSections(ability);
            }, skin))).growX().row();
        sourceValueContainer = new Container<>();
        sourceValueContainer.setActor(buildSourceValue(ability));
        source.add(sourceValueContainer).growX().row();

        if (ability.isActive()) {
            Table activation = formSection(form, "CONDITIONS");
            activationContainer = new Container<>();
            activationContainer.setActor(buildActivation(ability));
            activation.add(activationContainer).growX().row();
        }

        Table effects = formSection(form, "EFFECTS");
        effectsContainer = new Container<>();
        effectsContainer.setActor(buildEffects(ability));
        effects.add(effectsContainer).growX().row();

        return form;
    }

    private Actor buildSourceValue(AbilityData ability) {
        Table table = new Table(skin);
        table.defaults().left().pad(4).growX();
        SourceTypeEnum source = safeSource(ability.sourceType);

        switch (source) {
            case CHARACTER -> {
                table.add(formHint("Available to every character for normal assignment.")).row();
            }
            case SHIKIGAMI -> {
                table.add(formHint("Available only to Shikigami character definitions.")).row();
            }
            case TECHNIQUE -> {
                SelectBox<String> technique = techniqueSelect(ability.sourceValue, value ->
                    ability.sourceValue = techniqueNameFromLabel(value));
                table.add(labelledRow("Technique", technique)).growX().row();
                table.add(formHint(
                    "This ability is added to its technique tree. Configure its unlock rules there."))
                    .row();
            }
            case MOVE -> {
                SelectBox<String> move = moveSelect(ability.sourceValue,
                    value -> ability.sourceValue = idFromLabel(value));
                table.add(labelledRow("Known move", move)).growX().row();
                table.add(formHint("Available while the character knows this move.")).row();
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
                table.add(formHint("Available while the stat meets this minimum.")).row();
            }
            case ABILITY -> {
                SelectBox<String> parent = abilitySelect(ability, ability.sourceValue,
                    value -> ability.sourceValue = idFromLabel(value));
                table.add(labelledRow("Parent ability", parent)).growX().row();
                table.add(formHint("Available while the parent ability is assigned.")).row();
            }
        }
        return table;
    }

    private Actor buildEffects(AbilityData ability) {
        if (ability.effects == null) ability.effects = new ArrayList<>();
        return new EffectListEditor(
            ability.effects,
            moveRepo.getAll(),
            repo.getAll(),
            techniqueRepo.getAll(),
            charRepo.getAll(),
            this::markDirty,
            this::rebuildDetail,
            game.audio()::play,
            safeSource(ability.sourceType) == SourceTypeEnum.TECHNIQUE,
            ability.isPassive(),
            skin);
    }

    private Actor buildActivation(AbilityData ability) {
        initialiseActivationDefaults(ability);
        Table table = new Table(skin);
        table.defaults().left().pad(4).growX();
        table.add(new ConditionListEditor(
            ability.activationConditions,
            ability.effects,
            moveRepo.getAll(),
            this::markDirty,
            game.audio()::play,
            safeSource(ability.sourceType) == SourceTypeEnum.TECHNIQUE,
            skin)).growX().row();
        table.add(formHint(
            "Nest AND/OR groups inside each condition. Link a condition to all effects or select "
                + "specific effects below. Every effect must be linked exactly once."))
            .row();
        return table;
    }

    private void refreshConditionalSections(AbilityData ability) {
        markDirty();
        if (sourceValueContainer != null) sourceValueContainer.setActor(buildSourceValue(ability));
        if (effectsContainer != null) effectsContainer.setActor(buildEffects(ability));
        if (activationContainer != null) activationContainer.setActor(buildActivation(ability));
    }

    private static void initialiseSourceDefaults(AbilityData ability) {
        SourceTypeEnum source = safeSource(ability.sourceType);
        ability.masteryThreshold = 0;
        ability.sourceValue = source == SourceTypeEnum.STAT_THRESHOLD
            ? new AbilityResolver.StatRequirement(StatKey.VITALITY, 80).expression()
            : null;
    }

    private static void clearMasteryProgression(AbilityData ability) {
        if (ability.effects != null) {
            ability.effects.stream().filter(java.util.Objects::nonNull)
                .forEach(effect -> effect.masteryProgression = null);
        }
        if (ability.activationConditions != null) {
            for (AbilityConditionRuleData rule : ability.activationConditions) {
                if (rule == null) continue;
                rule.masteryProgression = null;
                clearMasteryProgression(rule.condition);
            }
        }
    }

    private static void clearMasteryProgression(AbilityConditionData condition) {
        if (condition == null) return;
        condition.masteryProgression = null;
        if (condition.children != null) {
            condition.children.forEach(AbilityEditorScreen::clearMasteryProgression);
        }
    }

    static void initialiseCategoryDefaults(AbilityData ability) {
        if (ability.isActive()) {
            initialiseActivationDefaults(ability);
        }
    }

    static void initialiseActivationDefaults(AbilityData ability) {
        if (ability.activationConditions == null || ability.activationConditions.isEmpty()) {
            ability.activationConditions = new ArrayList<>(List.of(
                AbilityConditionRuleData.allEffects(
                    AbilityConditionData.manualActivation())));
        }
        ability.activationConditions.removeIf(java.util.Objects::isNull);
        if (ability.activationConditions.isEmpty()) {
            ability.activationConditions.add(AbilityConditionRuleData.allEffects(
                AbilityConditionData.manualActivation()));
        }
        for (AbilityConditionRuleData rule : ability.activationConditions) {
            if (rule.condition == null) {
                rule.condition = AbilityConditionData.manualActivation();
            }
        }
    }

    private static void pruneConditionTargets(AbilityData ability) {
        if (ability == null || ability.activationConditions == null) return;
        java.util.Set<String> effectIds = ability.effects == null
            ? java.util.Set.of()
            : ability.effects.stream()
                .filter(java.util.Objects::nonNull)
                .map(effect -> effect.effectId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        for (AbilityConditionRuleData rule : ability.activationConditions) {
            if (rule != null && rule.targetEffectIds != null) {
                rule.targetEffectIds.removeIf(effectId -> !effectIds.contains(effectId));
            }
        }
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
        SelectBox<String> box = new DynamicSelectBox<>(skin);
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
        SHIKIGAMI,
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
