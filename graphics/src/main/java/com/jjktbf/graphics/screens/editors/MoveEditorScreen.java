package com.jjktbf.graphics.screens.editors;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;
import com.badlogic.gdx.utils.Align;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.ui.editor.EditorScreenBase;
import com.jjktbf.graphics.ui.editor.EnumSelectBox;
import com.jjktbf.graphics.ui.editor.HoverTextField;
import com.jjktbf.graphics.ui.editor.TagPicker;
import com.jjktbf.graphics.ui.editor.ValidationResult;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.AbilityRepository;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterRepository;
import com.jjktbf.model.character.StatKey;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.InterruptType;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveRepository;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.move.StatusEffectType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Graphical CRUD editor for {@link MoveData}. Master-detail layout, mouse +
 * keyboard driven, pixel-art themed.
 *
 * Form sections: identity, tags, cost (AP/CE), tag-controlled Attack / Defense /
 * Utility details, technique requirement, stat prerequisites, and free-move flag.
 *
 * Save validates by calling {@link MoveData#toMove()} (the same path the engine
 * uses), so any rule the runtime enforces is enforced here too.
 */
public class MoveEditorScreen extends EditorScreenBase<MoveData> {

    private final MoveRepository repo;

    // Handles to dynamically-shown/hidden widgets, refreshed in rebuildDetail.
    private Container<Actor> categorySectionsContainer;
    private Container<Actor> blockFieldsContainer;
    private Container<Actor> ceMinMaxContainer;
    private Container<Actor> powerFieldsContainer;

    public MoveEditorScreen(JJKGame game, AssetLoader assets) {
        super(game, assets);
        repo = new MoveRepository("data/moves");
    }

    // =========================================================================
    // Abstract hooks
    // =========================================================================

    @Override protected String title() { return "MOVE EDITOR"; }

    @Override protected MoveData newDraft() {
        MoveData m = new MoveData();
        m.name = "New Move";
        m.description = "";
        m.tags = new ArrayList<>();
        m.basePower = 0;
        m.baseAccuracy = 1.0;
        m.neverMiss = false;
        m.apCost = 10;
        m.unleashPoint = 1;
        m.baseCeCost = 0;
        m.hasCeCost = false;
        m.minCeCost = 0;
        m.maxCeCost = 0;
        m.interruptType = InterruptType.NONE.name();
        m.defenseType = DefenseType.NONE.name();
        m.blockDuration = 0;
        m.blockAffectedTags = null;
        m.blockDamageReduction = 100;
        m.blockFlatReduction = 0;
        m.onHitEffects = new ArrayList<>();
        m.selfEffects = new ArrayList<>();
        m.prerequisites = null;
        m.requiredTechniqueId = null;
        m.isFreeMove = false;
        return m;
    }

    @Override protected MoveData draftFromRecord(MoveData stored) {
        // Deep-copy the DTO field-by-field. Do NOT round-trip through
        // fromMove(toMove()) — toMove() collapses the tag set to a single
        // derived category, which would discard any multi-category selection
        // (e.g. an Attack + Innate Technique move would lose its tags).
        return deepCopy(stored);
    }

    /** Field-by-field deep copy of a MoveData (lists/maps are cloned). */
    private static MoveData deepCopy(MoveData s) {
        MoveData d = new MoveData();
        d.id                    = s.id;
        d.name                  = s.name;
        d.description           = s.description;
        d.tags                  = s.tags != null ? new ArrayList<>(s.tags) : null;
        d.basePower             = s.basePower;
        d.baseAccuracy          = s.baseAccuracy;
        d.neverMiss             = s.neverMiss;
        d.stun                  = s.stun;
        d.guardBreak            = s.guardBreak;
        d.heavy                 = s.heavy;
        d.apCost                = s.apCost;
        d.unleashPoint          = s.unleashPoint;
        d.baseCeCost            = s.baseCeCost;
        d.hasCeCost             = s.hasCeCost != null ? s.hasCeCost : s.baseCeCost > 0;
        d.minCeCost             = s.minCeCost;
        d.maxCeCost             = s.maxCeCost;
        d.interruptType         = s.interruptType;
        d.defenseType           = s.defenseType;
        d.blockDuration         = s.blockDuration;
        d.blockAffectedTags     = s.blockAffectedTags != null
                                  ? new ArrayList<>(s.blockAffectedTags) : null;
        d.blockDamageReduction  = s.blockDamageReduction;
        d.blockFlatReduction    = s.blockFlatReduction;
        // Effect lists MUST be mutable ArrayLists — the "+ Add effect" handlers
        // call list.add(...). Stream.toList() returns an immutable list which
        // throws UnsupportedOperationException on add (the editor crash bug).
        d.onHitEffects          = s.onHitEffects != null
                                  ? s.onHitEffects.stream().map(MoveEditorScreen::copyEffect)
                                      .filter(java.util.Objects::nonNull)
                                      .collect(java.util.stream.Collectors.toCollection(ArrayList::new))
                                  : new ArrayList<>();
        d.selfEffects           = s.selfEffects != null
                                  ? s.selfEffects.stream().map(MoveEditorScreen::copyEffect)
                                      .filter(java.util.Objects::nonNull)
                                      .collect(java.util.stream.Collectors.toCollection(ArrayList::new))
                                  : new ArrayList<>();
        d.codedAbilityKey       = s.codedAbilityKey;
        d.codedAction           = s.codedAction;
        d.prerequisites         = s.prerequisites != null
                                  ? new LinkedHashMap<>(s.prerequisites) : null;
        d.requiredTechniqueId   = s.requiredTechniqueId;
        d.isFreeMove            = s.isFreeMove;
        return d;
    }

    private static MoveData.StatusEffectData copyEffect(MoveData.StatusEffectData e) {
        if (e == null) return null;
        StatusEffectType type;
        try {
            type = StatusEffectType.fromName(e.type, e.magnitude);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        MoveData.StatusEffectData c = new MoveData.StatusEffectData();
        c.type            = type.name();
        c.durationRounds  = e.durationRounds;
        c.durationTicks   = e.durationTicks;
        c.magnitude       = StatusEffectType.normalizeStoredMagnitude(e.type, e.magnitude);
        return c;
    }

    @Override protected String idOf(MoveData r) { return r.id; }

    @Override protected String nextId() { return repo.nextId(); }

    @Override protected void stampNewId(MoveData draft) { draft.id = repo.nextId(); }

    @Override protected String listLabel(MoveData r) {
        return r.name == null || r.name.isEmpty() ? "(unnamed)" : r.name;
    }

    @Override protected boolean isNewDraft(MoveData draft) {
        return draft.id == null || draft.id.isEmpty()
            || repo.findById(draft.id).isEmpty();
    }

    @Override
    protected void reloadRecords() throws IOException {
        repo.load();
        records.clear();
        records.addAll(repo.getAll());
    }

    @Override
    protected ValidationResult validateAndSave(MoveData d) {
        if (d.isCoded()) {
            return ValidationResult.error("Coded moves are defined in source and cannot be edited here.");
        }
        if (d.name == null || d.name.trim().isEmpty()) {
            return ValidationResult.error("Name is required.");
        }
        if (d.tags == null || d.tags.isEmpty()) {
            return ValidationResult.error("At least one tag is required.");
        }
        String tagError = categoryTagValidationError(d);
        if (tagError != null) return ValidationResult.error(tagError);
        // Inactive section details stay on the live draft while editing. Work on
        // a copy so a failed save cannot erase details hidden by a temporary
        // tag toggle.
        MoveData toSave = normalizedCopyForSave(d);
        boolean adding = isNewDraft(d);
        // New drafts need a non-blank id for the engine builder to validate.
        if (adding && (toSave.id == null || toSave.id.isBlank())) {
            toSave.id = repo.nextId();
        }
        // Validate via the engine's own builder — catches unleashPoint/AP,
        // bad enums, derived-category errors, etc.
        try {
            toSave.toMove();
        } catch (Exception e) {
            return ValidationResult.error("Invalid move: " + e.getMessage());
        }

        MoveData previous = adding ? null : repo.findById(toSave.id).orElse(null);
        boolean repositoryMutated = false;
        try {
            if (adding) {
                // Clear so the repo assigns the canonical next id (robust to
                // other edits since the draft was created).
                toSave.id = null;
                repo.add(toSave);
            } else {
                repo.update(toSave);
            }
            repositoryMutated = true;
            repo.save();
        } catch (Exception e) {
            // add/update mutates the repository before save writes to disk. Put
            // its in-memory state back so a later save cannot persist a failed
            // normalized copy or a phantom new record.
            if (repositoryMutated) {
                try {
                    if (adding) repo.delete(toSave.id);
                    else if (previous != null) repo.update(previous);
                } catch (RuntimeException ignored) {
                    // Keep the original persistence error as the useful result.
                }
            }
            return ValidationResult.error("Save failed: " + e.getMessage());
        }

        // Persistence succeeded. The hidden details can now be discarded from
        // the editor draft as well.
        d.id = toSave.id;
        discardInactiveCategoryDetails(d);
        try {
            TechniqueTreeRepositorySync.synchronize();
        } catch (Exception e) {
            return ValidationResult.ok("Saved \"" + d.name
                + "\", but technique tree sync failed: " + e.getMessage());
        }
        return ValidationResult.ok("Saved \"" + d.name + "\".");
    }

    static String categoryTagValidationError(MoveData move) {
        long purposeCount = List.of(
            MoveTag.ATTACK, MoveTag.UTILITY, MoveTag.DEFENSIVE).stream()
            .filter(tag -> move.tags.contains(tag.name()))
            .count();
        if (purposeCount != 1) {
            return "Select exactly one of Attack, Utility, or Defensive.";
        }
        if (move.tags.contains(MoveTag.ATTACK.name())) {
            boolean hasDamageNature = List.of(
                MoveTag.PHYSICAL,
                MoveTag.CURSED_ENERGY,
                MoveTag.INNATE_TECHNIQUE,
                MoveTag.NON_INNATE_TECHNIQUE).stream()
                .anyMatch(tag -> move.tags.contains(tag.name()));
            if (!hasDamageNature) {
                return "An Attack needs a Physical, Cursed Energy, or Technique tag.";
            }
        }
        if (move.tags.contains(MoveTag.DEFENSIVE.name())) {
            DefenseType defense;
            try { defense = DefenseType.valueOf(move.defenseType); }
            catch (Exception ignored) { defense = DefenseType.NONE; }
            if (defense == DefenseType.NONE) {
                return "A Defensive move needs a percentage or flat block type.";
            }
        }
        return null;
    }

    @Override
    protected ValidationResult delete(String id) {
        try {
            MoveData deleted = repo.findById(id).orElse(null);
            if (deleted == null) return ValidationResult.error("Move no longer exists.");

            AbilityRepository abilityRepo = new AbilityRepository("data/abilities");
            CharacterRepository characterRepo = new CharacterRepository("data/characters");
            abilityRepo.load();
            characterRepo.load();

            AbilityData dependent = abilityRepo.getAll().stream()
                .filter(ability -> moveReferenceOf(ability, id) != null)
                .findFirst().orElse(null);
            if (dependent != null) {
                return ValidationResult.error(
                    "Cannot delete: ability \"" + dependent.name + "\" references this move.");
            }

            Map<String, String> remappedIds = new LinkedHashMap<>();
            int nextIndex = 0;
            for (MoveData move : repo.getAll()) {
                if (id.equals(move.id)) continue;
                remappedIds.put(move.id,
                    com.jjktbf.model.repo.BaseRepository.formatId(nextIndex++));
            }

            for (CharacterData character : characterRepo.getAll()) {
                if (character.moveIds == null) continue;
                character.moveIds = character.moveIds.stream()
                    .filter(moveId -> !id.equals(moveId))
                    .map(moveId -> remappedIds.getOrDefault(moveId, moveId))
                    .distinct()
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            }
            for (AbilityData ability : abilityRepo.getAll()) {
                if ("MOVE".equalsIgnoreCase(ability.sourceType)) {
                    ability.sourceValue = remappedIds.getOrDefault(
                        ability.sourceValue, ability.sourceValue);
                }
                ability.activeMoveId = remappedIds.getOrDefault(
                    ability.activeMoveId, ability.activeMoveId);
                remapConditionMoves(ability.activationCondition, remappedIds);
                if (ability.effects == null) continue;
                ability.effects.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(effect -> AbilityEffectType.GRANT_MOVE.name().equalsIgnoreCase(effect.type))
                    .forEach(effect -> effect.moveId = remappedIds.getOrDefault(
                        effect.moveId, effect.moveId));
            }

            repo.delete(id);
            repo.save();
            abilityRepo.save();
            characterRepo.save();
            TechniqueTreeRepositorySync.synchronize(
                com.jjktbf.model.technique.SkillTreeNodeData.MOVE, remappedIds, id);
            return ValidationResult.ok("Deleted.");
        } catch (Exception e) {
            return ValidationResult.error("Delete failed: " + e.getMessage());
        }
    }

    private static String moveReferenceOf(AbilityData ability, String moveId) {
        if (moveId.equals(ability.activeMoveId)) return "active move";
        if ("MOVE".equalsIgnoreCase(ability.sourceType) && moveId.equals(ability.sourceValue)) {
            return "move source";
        }
        if (ability.effects != null) {
            boolean grantsMove = ability.effects.stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(effect -> AbilityEffectType.GRANT_MOVE.name().equalsIgnoreCase(effect.type)
                    && moveId.equals(effect.moveId));
            if (grantsMove) return "granted move";
        }
        if (conditionReferencesMove(ability.activationCondition, moveId)) {
            return "activation condition";
        }
        return null;
    }

    private static boolean conditionReferencesMove(AbilityConditionData condition, String moveId) {
        if (condition == null) return false;
        if (moveId.equals(condition.moveId)) return true;
        return condition.children != null && condition.children.stream()
            .anyMatch(child -> conditionReferencesMove(child, moveId));
    }

    private static void remapConditionMoves(
        AbilityConditionData condition,
        Map<String, String> remappedIds
    ) {
        if (condition == null) return;
        condition.moveId = remappedIds.getOrDefault(condition.moveId, condition.moveId);
        if (condition.children != null) {
            condition.children.forEach(child -> remapConditionMoves(child, remappedIds));
        }
    }

    // =========================================================================
    // Detail form
    // =========================================================================

    @Override
    protected Actor buildDetailForm(MoveData d) {
        // A TagPicker can emit a coupling change while this form is being built.
        // Drop references to the previous form so that event cannot refresh
        // detached actors.
        categorySectionsContainer = null;
        blockFieldsContainer = null;
        ceMinMaxContainer = null;
        powerFieldsContainer = null;

        Table form = formRoot();

        // ── Identity ───────────────────────────────────────────────────────────
        Table identity = formSection(form, "NAME");
        identity.add(idBadge(d.id)).left().row();
        identity.add(labelledField("Name", d.name,
                s -> { d.name = s; })).growX().row();
        identity.add(labelledField("Description", d.description,
                s -> { d.description = s; })).growX().row();

        // ── Tags ───────────────────────────────────────────────────────────────
        Table tagsSection = formSection(form, "TAGS");
        Set<MoveTag> initialTags = new LinkedHashSet<>();
        if (d.tags != null) {
            for (String t : d.tags) {
                try { initialTags.add(MoveTag.valueOf(t)); } catch (Exception ignored) {}
            }
        }
        TagPicker tagPicker = new TagPicker(initialTags, tags -> {
            d.tags = tags.stream().map(MoveTag::name).toList();
            // STUN/GUARD_BREAK/HEAVY are modifier tags backed by dedicated flags (not
            // part of any MoveCategory), so keep them in sync with the tag selection.
            d.stun = tags.contains(MoveTag.STUN);
            d.guardBreak = tags.contains(MoveTag.GUARD_BREAK);
            d.heavy = tags.contains(MoveTag.HEAVY);
            ensureTechniqueStatPrerequisites(d, tags);
            refreshCategorySections(d);
        }, skin);
        // Sync the draft's tags with the picker's coupling-enforced initial set
        // (e.g. a technique tag implies CURSED_ENERGY). suppressDirty is on
        // during build, so this won't mark the record dirty on load.
        d.tags = tagPicker.getSelected().stream().map(MoveTag::name).toList();
        d.stun = tagPicker.getSelected().contains(MoveTag.STUN);
        d.guardBreak = tagPicker.getSelected().contains(MoveTag.GUARD_BREAK);
        d.heavy = tagPicker.getSelected().contains(MoveTag.HEAVY);
        tagsSection.add(tagPicker).growX().row();

        // ── Cost ───────────────────────────────────────────────────────────────
        Table cost = formSection(form, "COST");
        cost.add(labelledIntField("AP Cost", d.apCost, 1, 999,
                v -> { d.apCost = v; })).growX().row();
        cost.add(labelledIntField("Unleash Point (1..AP)", d.unleashPoint, 1, 999,
                v -> { d.unleashPoint = v; })).growX().row();
        CheckBox hasCeCostCb = new CheckBox(" Has CE cost", skin);
        hasCeCostCb.setChecked(Boolean.TRUE.equals(d.hasCeCost));
        hasCeCostCb.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                d.hasCeCost = hasCeCostCb.isChecked();
                refreshConditionalFields(d);
            }
        });
        cost.add(hasCeCostCb).left().row();

        // CE amount and min/max (shown only when the move has a CE cost).
        ceMinMaxContainer = new Container<>();
        ceMinMaxContainer.setActor(buildCeMinMax(d));
        cost.add(ceMinMaxContainer).growX().row();

        // Purpose tags control which detail cards are active. Replacing this
        // actor hides a card without touching its draft values.
        categorySectionsContainer = new Container<>();
        categorySectionsContainer.setActor(buildCategorySections(d));
        form.add(categorySectionsContainer).growX().row();

        // ── Technique requirement ──────────────────────────────────────────────
        Table technique = formSection(form, "TECHNIQUE REQUIREMENT");
        technique.add(labelledField("Required Technique (name or blank)",
                d.requiredTechniqueId,
                s -> { d.requiredTechniqueId = (s == null || s.isBlank()) ? null : s; }))
            .growX().row();
        // Read-only hint: does the named technique exist in the TechniqueRepository?
        // Warns (does not block) — a move may legitimately predate its technique.
        if (d.requiredTechniqueId != null && !d.requiredTechniqueId.isBlank()) {
            boolean exists = techniqueExists(d.requiredTechniqueId);
            Label techHint = exists
                ? formHint("✓ technique \"" + d.requiredTechniqueId + "\" found")
                : formHint("⚠ no technique named \"" + d.requiredTechniqueId + "\" — create it in the Technique Editor");
            techHint.setColor(exists
                ? skin.get("text-ok", com.badlogic.gdx.graphics.Color.class)
                : skin.get("text-error", com.badlogic.gdx.graphics.Color.class));
            technique.add(techHint).left().row();
        }

        // ── Prerequisites ──────────────────────────────────────────────────────
        Table prereqs = formSection(form, "STAT PREREQUISITES");
        prereqs.add(buildPrerequisitesEditor(d)).growX().row();

        // ── Free-move ───────────────────────────────────────────────────────────
        Table misc = formSection(form, "MISC");
        CheckBox freeCb = new CheckBox(" Free move (does not consume a slot)", skin);
        freeCb.setChecked(d.isFreeMove);
        freeCb.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                d.isFreeMove = freeCb.isChecked();
                markDirty();
            }
        });
        misc.add(freeCb).left().row();

        return form;
    }

    // =========================================================================
    // Conditional sub-sections
    // =========================================================================

    private Actor buildCategorySections(MoveData d) {
        Table sections = formRoot();
        sections.pad(0f);
        powerFieldsContainer = null;
        blockFieldsContainer = null;

        if (hasTag(d, MoveTag.ATTACK)) {
            Table attack = formSection(sections, "ATTACK");

            attack.add(new Label("POWER / ACCURACY", skin, "small")).left().row();
            powerFieldsContainer = new Container<>();
            powerFieldsContainer.setActor(buildPowerFields(d));
            attack.add(powerFieldsContainer).growX().row();

            CheckBox neverMissCb = new CheckBox(" Never-miss (ignore accuracy roll)", skin);
            neverMissCb.setChecked(d.neverMiss);
            neverMissCb.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    d.neverMiss = neverMissCb.isChecked();
                    refreshConditionalFields(d);
                }
            });
            attack.add(neverMissCb).left().row();

            attack.add(labelledRow("Interrupt Type", new EnumSelectBox<>(
                InterruptType.class, d.interruptType, false,
                s -> { d.interruptType = s; markDirty(); }, skin))).growX().row();
            attack.add(new Label("ON-HIT EFFECTS", skin, "small")).padTop(8f).left().row();
            attack.add(buildEffectsEditor("onHit", d)).growX().row();
        }

        if (hasTag(d, MoveTag.DEFENSIVE)) {
            Table defense = formSection(sections, "DEFENSE");
            defense.add(labelledRow("Type", new EnumSelectBox<>(
                DefenseType.class, d.defenseType, false,
                s -> { d.defenseType = s; refreshConditionalFields(d); }, skin))).growX().row();

            blockFieldsContainer = new Container<>();
            blockFieldsContainer.setActor(buildBlockFields(d));
            defense.add(blockFieldsContainer).growX().row();
        }

        if (hasTag(d, MoveTag.UTILITY)) {
            Table utility = formSection(sections, "UTILITY");
            utility.add(new Label("SELF EFFECTS", skin, "small")).left().row();
            utility.add(buildEffectsEditor("self", d)).growX().row();
        }

        return sections;
    }

    private Actor buildPowerFields(MoveData d) {
        Table t = new Table(skin);
        t.defaults().left().pad(4);
        t.add(labelledIntField("Base Power", d.basePower, 0, 99999,
                v -> { d.basePower = v; })).growX().row();
        if (d.neverMiss) {
            t.add(formHint("Accuracy is N/A for a never-miss move.")).row();
            return t;
        }
        // Accuracy as integer 1..100; stored /100 as double.
        int acc = (int) Math.round(d.baseAccuracy * 100.0);
        t.add(labelledIntField("Base Accuracy %", acc, 1, 100,
                v -> { d.baseAccuracy = v / 100.0; })).growX().row();
        return t;
    }

    private Actor buildCeMinMax(MoveData d) {
        Table t = new Table(skin);
        t.defaults().left().pad(4);
        if (!Boolean.TRUE.equals(d.hasCeCost)) {
            t.add(formHint("(this move has no CE cost)")).row();
            return t;
        }
        t.add(labelledIntField("Base CE Cost", d.baseCeCost, 0, 99999,
                v -> { d.baseCeCost = v; })).growX().row();
        t.add(labelledIntField("Min CE Cost", d.minCeCost, 0, d.baseCeCost,
                v -> { d.minCeCost = v; })).growX().row();
        t.add(labelledIntField("Max CE Cost", d.maxCeCost, d.baseCeCost, 99999,
                v -> { d.maxCeCost = v; })).growX().row();
        return t;
    }

    private Actor buildBlockFields(MoveData d) {
        Table t = new Table(skin);
        t.defaults().left().pad(4);
        DefenseType dt;
        try { dt = DefenseType.valueOf(d.defenseType); }
        catch (Exception e) { dt = DefenseType.NONE; }

        if (dt == DefenseType.NONE) {
            t.add(formHint("(no defense — select PERCENTAGE_BLOCK or FLAT_BLOCK)")).row();
            return t;
        }

        // Block duration (−1 = end of round, 0 = use AP)
        t.add(labelledIntField("Block Duration (−1 = EOR, 0 = use AP)",
                d.blockDuration, -1, 99999,
                v -> { d.blockDuration = v; })).growX().row();

        if (dt == DefenseType.PERCENTAGE_BLOCK) {
            t.add(labelledIntField("Damage Reduction %", d.blockDamageReduction, 0, 100,
                    v -> { d.blockDamageReduction = v; d.blockFlatReduction = 0; })).growX().row();
        } else { // FLAT_BLOCK
            t.add(labelledIntField("Flat Reduction", d.blockFlatReduction, 0, 99999,
                    v -> { d.blockFlatReduction = v; d.blockDamageReduction = 100; })).growX().row();
        }

        // Affected tags — multi-toggle
        t.add(new Label("Affected Tags (blank = all)", skin)).padTop(4).row();
        t.add(buildBlockTagToggles(d)).growX().row();
        return t;
    }

    private Actor buildBlockTagToggles(MoveData d) {
        Table grid = new Table(skin);
        grid.defaults().pad(3);
        MoveTag[] affected = { MoveTag.PHYSICAL, MoveTag.CURSED_ENERGY,
                               MoveTag.INNATE_TECHNIQUE, MoveTag.NON_INNATE_TECHNIQUE };
        Set<String> selected = new LinkedHashSet<>();
        if (d.blockAffectedTags != null) selected.addAll(d.blockAffectedTags);

        int col = 0;
        for (MoveTag tag : affected) {
            CheckBox cb = new CheckBox(pretty(tag.name()), skin);
            cb.setChecked(selected.contains(tag.name()));
            cb.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    Set<String> cur = new LinkedHashSet<>(
                        d.blockAffectedTags == null ? List.of() : d.blockAffectedTags);
                    if (cb.isChecked()) cur.add(tag.name());
                    else                cur.remove(tag.name());
                    d.blockAffectedTags = cur.isEmpty() ? null : new ArrayList<>(cur);
                    markDirty();
                }
            });
            grid.add(cb).left();
            if (++col >= 2) { grid.row(); col = 0; }
        }
        return grid;
    }

    private Actor buildPrerequisitesEditor(MoveData d) {
        Table t = new Table(skin);
        Table statRow = new Table(skin);
        statRow.defaults().center().padLeft(6f).padRight(6f);

        for (StatKey stat : StatKey.values()) {
            Table statColumn = new Table(skin);
            statColumn.defaults().center();

            Label label = new Label(stat.label, skin, "small");
            label.setAlignment(Align.center);
            label.setWrap(true);
            statColumn.add(label).width(82f).height(48f).row();

            TextField valueField = new HoverTextField(
                String.valueOf(prerequisiteValue(d, stat)), skin);
            valueField.setTextFieldFilter((TextField tf, char c) -> Character.isDigit(c));
            wirePrerequisiteField(valueField, d, stat);
            statColumn.add(valueField).width(64f);

            statRow.add(statColumn);
        }

        t.add(statRow).center().expandX().row();
        t.add(formHint("Set each stat's minimum value (0 means no threshold)."))
            .padTop(6f).center().row();
        return t;
    }

    /** Match the character editor's numeric fields: commit on Enter or focus loss. */
    private void wirePrerequisiteField(TextField field, MoveData d, StatKey stat) {
        field.addListener(new InputListener() {
            @Override public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Input.Keys.ENTER) {
                    commitPrerequisite(field, d, stat);
                    return true;
                }
                return false;
            }
        });
        field.addListener(new FocusListener() {
            @Override public void keyboardFocusChanged(
                FocusEvent event, Actor actor, boolean focused
            ) {
                if (!focused) commitPrerequisite(field, d, stat);
            }
        });
    }

    private void commitPrerequisite(TextField field, MoveData d, StatKey stat) {
        String text = field.getText().trim();
        int value = 0;
        if (!text.isEmpty()) {
            try {
                value = Math.max(0, Math.min(300, Integer.parseInt(text)));
            } catch (NumberFormatException ignored) {
                value = prerequisiteValue(d, stat);
            }
        }
        field.setText(String.valueOf(value));

        Map<String, Integer> updated = d.prerequisites == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(d.prerequisites);
        updated.entrySet().removeIf(
            entry -> entry.getKey() != null && stat.matches(entry.getKey()));
        updated.put(stat.fieldName, value);
        d.prerequisites = updated;
        markDirty();
    }

    private static int prerequisiteValue(MoveData d, StatKey stat) {
        if (d.prerequisites == null) return 0;
        for (Map.Entry<String, Integer> entry : d.prerequisites.entrySet()) {
            if (entry.getKey() != null && stat.matches(entry.getKey())) {
                return entry.getValue() == null ? 0
                    : Math.max(0, Math.min(300, entry.getValue()));
            }
        }
        return 0;
    }

    /** Technique moves require their governing stat key, even at the default threshold. */
    private static void ensureTechniqueStatPrerequisites(MoveData d, Set<MoveTag> tags) {
        Map<String, Integer> updated = d.prerequisites == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(d.prerequisites);
        boolean changed = false;
        if (tags.contains(MoveTag.INNATE_TECHNIQUE)
            && !hasPrerequisite(updated, StatKey.CURSED_TECHNIQUE_MASTERY)) {
            updated.put(StatKey.CURSED_TECHNIQUE_MASTERY.fieldName, 0);
            changed = true;
        }
        if (tags.contains(MoveTag.NON_INNATE_TECHNIQUE)
            && !hasPrerequisite(updated, StatKey.JUJUTSU_SKILL)) {
            updated.put(StatKey.JUJUTSU_SKILL.fieldName, 0);
            changed = true;
        }
        if (changed) d.prerequisites = updated;
    }

    private static boolean hasPrerequisite(Map<String, Integer> prerequisites, StatKey stat) {
        return prerequisites.keySet().stream()
            .anyMatch(key -> key != null && stat.matches(key));
    }

    /**
     * Build a simple list editor for status effects (onHit or self).
     * @param which "onHit" or "self"
     */
    private Actor buildEffectsEditor(String which, MoveData d) {
        List<MoveData.StatusEffectData> raw = "onHit".equals(which) ? d.onHitEffects : d.selfEffects;
        if (raw == null) {
            raw = new ArrayList<>();
            if ("onHit".equals(which)) d.onHitEffects = raw; else d.selfEffects = raw;
        }
        final List<MoveData.StatusEffectData> list = raw;

        Table t = new Table(skin);
        t.defaults().left().pad(3);

        if (list.isEmpty()) {
            t.add(formHint("(none)")).colspan(5).row();
        } else {
            // Snapshot for safe iteration during rebuild.
            for (int i = 0; i < list.size(); i++) {
                final int idx = i;
                MoveData.StatusEffectData eff = list.get(idx);
                Label lbl = new Label(
                    StatusEffectType.referenceDisplayName(eff.type)
                        + " | rounds=" + eff.durationRounds
                        + " | ticks=" + eff.durationTicks
                        + " | amount=" + eff.magnitude,
                    skin, "small");
                t.add(lbl).left().growX();
                TextButton editBtn = new TextButton("Edit", skin);
                editBtn.addListener(new ChangeListener() {
                    @Override public void changed(ChangeEvent event, Actor actor) {
                        showEffectEditor(eff, updated -> list.set(idx, updated));
                    }
                });
                TextButton rmBtn = new TextButton("X", skin);
                rmBtn.addListener(new ChangeListener() {
                    @Override public void changed(ChangeEvent event, Actor actor) {
                        list.remove(idx);
                        markDirty();
                        rebuildDetail();
                    }
                });
                t.add(editBtn).padLeft(4);
                t.add(rmBtn).padLeft(4).row();
            }
        }

        // Add button
        TextButton addBtn = new TextButton("+ Add effect", skin);
        addBtn.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                MoveData.StatusEffectData eff = new MoveData.StatusEffectData();
                eff.type = StatusEffectType.STRENGTH_INCREASE.name();
                eff.durationRounds = 1;
                eff.durationTicks = 0;
                eff.magnitude = 10.0;
                showEffectEditor(eff, list::add);
            }
        });
        t.add(addBtn).colspan(5).padTop(4).row();
        return t;
    }

    /** Modal editor for a single StatusEffectData row. */
    private void showEffectEditor(
        MoveData.StatusEffectData source,
        Consumer<MoveData.StatusEffectData> commit
    ) {
        MoveData.StatusEffectData eff = copyEffect(source);
        com.badlogic.gdx.scenes.scene2d.ui.Dialog dlg =
            new com.badlogic.gdx.scenes.scene2d.ui.Dialog("Edit Effect", skin) {
                @Override
                protected void result(Object object) {
                    if (Boolean.TRUE.equals(object)) {
                        commit.accept(eff);
                        markDirty();
                        rebuildDetail();
                    }
                }
            };

        Table content = new Table(skin);
        content.defaults().pad(4).left();

        content.add(new Label("Type", skin)).padRight(8);
        List<StatusEffectType> statusTypes = List.of(StatusEffectType.values());
        SelectBox<String> typeBox = new SelectBox<>(skin);
        List<String> statusLabels = new ArrayList<>(statusTypes.stream()
            .map(StatusEffectType::displayName).toList());
        String storedStatus = eff.type;
        String selectedStatus = StatusEffectType.referenceDisplayName(storedStatus);
        if (!statusLabels.contains(selectedStatus)) statusLabels.add(0, selectedStatus);
        typeBox.setItems(statusLabels.toArray(new String[0]));
        typeBox.setSelected(selectedStatus);
        typeBox.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                if (selectedStatus.equals(typeBox.getSelected())) {
                    eff.type = storedStatus;
                    return;
                }
                eff.type = statusTypes.stream()
                    .filter(status -> status.displayName().equals(typeBox.getSelected()))
                    .findFirst().orElse(StatusEffectType.STRENGTH_INCREASE).name();
            }
        });
        content.add(typeBox).growX().row();

        // Duration
        TextField roundsField = new HoverTextField(String.valueOf(eff.durationRounds), skin);
        roundsField.setTextFieldFilter((tf, c) -> Character.isDigit(c) || c == '-');
        roundsField.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                try { eff.durationRounds = Integer.parseInt(roundsField.getText()); }
                catch (NumberFormatException ignored) {}
            }
        });
        content.add(new Label("Duration rounds (-1 = permanent)", skin)).padRight(8);
        content.add(roundsField).growX().row();

        TextField ticksField = new HoverTextField(String.valueOf(eff.durationTicks), skin);
        ticksField.setTextFieldFilter((tf, c) -> Character.isDigit(c));
        ticksField.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                try { eff.durationTicks = Integer.parseInt(ticksField.getText()); }
                catch (NumberFormatException ignored) {}
            }
        });
        content.add(new Label("Duration ticks", skin)).padRight(8);
        content.add(ticksField).growX().row();

        // Magnitude
        TextField magField = new HoverTextField(String.valueOf(eff.magnitude), skin);
        magField.setTextFieldFilter((tf, c) ->
            Character.isDigit(c) || c == '.');
        magField.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                try { eff.magnitude = Double.parseDouble(magField.getText()); }
                catch (NumberFormatException ignored) {}
            }
        });
        content.add(new Label("Amount (flat points)", skin)).padRight(8);
        content.add(magField).growX().row();

        dlg.getContentTable().add(content).grow().row();
        dlg.button("Done", true);
        dlg.button("Cancel", false);
        dlg.show(stage);
    }

    // =========================================================================
    // Conditional refresh
    // =========================================================================

    /** Show or hide complete purpose cards after a tag change. */
    private void refreshCategorySections(MoveData d) {
        markDirty();
        if (categorySectionsContainer != null) {
            categorySectionsContainer.setActor(buildCategorySections(d));
        }
    }

    /** Re-render fields whose contents depend on another value in their card. */
    private void refreshConditionalFields(MoveData d) {
        markDirty();
        if (blockFieldsContainer != null) blockFieldsContainer.setActor(buildBlockFields(d));
        if (ceMinMaxContainer  != null) ceMinMaxContainer.setActor(buildCeMinMax(d));
        if (powerFieldsContainer != null) powerFieldsContainer.setActor(buildPowerFields(d));
    }

    private static boolean hasTag(MoveData d, MoveTag tag) {
        return d.tags != null && d.tags.contains(tag.name());
    }

    static MoveData normalizedCopyForSave(MoveData draft) {
        MoveData copy = deepCopy(draft);
        discardInactiveCategoryDetails(copy);
        return copy;
    }

    private static void discardInactiveCategoryDetails(MoveData d) {
        if (!hasTag(d, MoveTag.ATTACK)) {
            d.basePower = 0;
            d.baseAccuracy = 1.0;
            d.neverMiss = false;
            d.interruptType = InterruptType.NONE.name();
            d.onHitEffects = new ArrayList<>();
        }
        if (!hasTag(d, MoveTag.DEFENSIVE)) {
            d.defenseType = DefenseType.NONE.name();
            d.blockDuration = 0;
            d.blockAffectedTags = null;
            d.blockDamageReduction = 100;
            d.blockFlatReduction = 0;
        }
        if (!hasTag(d, MoveTag.UTILITY)) {
            d.selfEffects = new ArrayList<>();
        }
    }

    // =========================================================================
    // Small UI helpers
    // =========================================================================

    /** True if a technique with the given name (case-insensitive) exists in the technique repo. */
    private boolean techniqueExists(String name) {
        try {
            com.jjktbf.model.technique.TechniqueRepository techRepo =
                new com.jjktbf.model.technique.TechniqueRepository("data/techniques");
            techRepo.load();
            return techRepo.nameExists(name);
        } catch (java.io.IOException e) {
            return false; // can't confirm — don't block
        }
    }

    private static String pretty(String enumName) {
        String[] parts = enumName.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

}
