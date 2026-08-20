package com.jjktbf.graphics.screens.editors;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;
import com.badlogic.gdx.utils.Align;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.audio.SoundCue;
import com.jjktbf.graphics.ui.ContentSizedDialog;
import com.jjktbf.graphics.ui.DynamicSelectBox;
import com.jjktbf.graphics.ui.profile.UiProfile;
import com.jjktbf.graphics.ui.editor.EditorScreenBase;
import com.jjktbf.graphics.ui.editor.AxisLockedScrollPane;
import com.jjktbf.graphics.ui.editor.EnumSelectBox;
import com.jjktbf.graphics.ui.editor.EffectListEditor;
import com.jjktbf.graphics.ui.editor.ConditionTreeEditor;
import com.jjktbf.graphics.ui.editor.HoverTextField;
import com.jjktbf.graphics.ui.editor.TagPicker;
import com.jjktbf.graphics.ui.editor.ValidationResult;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.AbilityEffectTarget;
import com.jjktbf.model.character.AbilityRepository;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterType;
import com.jjktbf.model.character.CharacterRepository;
import com.jjktbf.model.character.StatKey;
import com.jjktbf.model.character.coded.CodedAbilityRegistry;
import com.jjktbf.model.character.coded.NewShadowStyleAbility;
import com.jjktbf.model.character.coded.RatioAbility;
import com.jjktbf.model.move.AoeType;
import com.jjktbf.model.move.AttackLaunchMode;
import com.jjktbf.model.move.BlockStyle;
import com.jjktbf.model.move.DefenseTargeting;
import com.jjktbf.model.move.DefenseTiming;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.DodgeScope;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveRepository;
import com.jjktbf.model.move.MoveEffectData;
import com.jjktbf.model.move.MoveEffectTrigger;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.move.MoveType;
import com.jjktbf.model.move.StatusEffectType;
import com.jjktbf.model.progression.TechniqueMasteryProgressions;
import com.jjktbf.model.technique.InnateTechniqueData;
import com.jjktbf.model.technique.TechniqueRepository;
import com.jjktbf.graphics.ui.editor.MasteryProgressionEditor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

    private static final String SORCERER_SECTION = "SORCERER";
    private static final String CURSED_SPIRIT_SECTION = "CURSED SPIRIT";
    private static final String CURSED_TECHNIQUES_SECTION = "CURSED TECHNIQUES";
    private static final String SHIKIGAMI_SECTION = "SHIKIGAMI";
    private static final List<String> MOVE_PURPOSE_SECTIONS = List.of(
        "DEFENSE", "ATTACK", "UTILITY");

    private static final List<MoveTag> COMPONENT_DAMAGE_TAGS = List.of(
        MoveTag.PHYSICAL,
        MoveTag.CURSED_ENERGY,
        MoveTag.INNATE_TECHNIQUE,
        MoveTag.NON_INNATE_TECHNIQUE);

    private final MoveRepository repo;
    /** Character repo for the shikigami-summon selector and summon-reference remap on delete. */
    private final CharacterRepository charRepo;
    private final TechniqueRepository techniqueRepo;

    // Handles to dynamically-shown/hidden widgets, refreshed in rebuildDetail.
    private Container<Actor> categorySectionsContainer;
    private Container<Actor> defenseFieldsContainer;
    private Container<Actor> defenseTargetingContainer;
    private Container<Actor> defenseEffectsContainer;
    private Container<Actor> ceMinMaxContainer;
    private Container<Actor> powerFieldsContainer;
    private Container<Actor> aoeFieldsContainer;
    private Container<Actor> attackLaunchContainer;
    private CheckBox weaponRequiredCheckbox;

    public MoveEditorScreen(JJKGame game, AssetLoader assets) {
        super(game, assets);
        repo = new MoveRepository("data/moves");
        charRepo = new CharacterRepository("data/characters");
        techniqueRepo = new TechniqueRepository("data/techniques");
    }

    // =========================================================================
    // Abstract hooks
    // =========================================================================

    @Override protected String title() { return "MOVE EDITOR"; }

    @Override protected MoveData newDraft() {
        MoveData m = new MoveData();
        m.name = "New Move";
        m.description = "";
        m.moveType = MoveType.SORCERER.name();
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
        m.defenseType = DefenseType.NONE.name();
        m.blockStyle = BlockStyle.PERCENTAGE.name();
        m.blockDuration = 0;
        m.blockAffectedTags = null;
        m.blockDamageReduction = 100;
        m.blockFlatReduction = 0;
        m.dodgeChance = 0;
        m.dodgeScope = "BOTH";
        m.parryStaggerTicks = 0;
        m.potency = 1;
        m.weaponRequired = false;
        m.onHitEffects = new ArrayList<>();
        m.selfEffects = new ArrayList<>();
        m.onBlockEffects = new ArrayList<>();
        m.onParryEffects = new ArrayList<>();
        m.onDodgeEffects = new ArrayList<>();
        m.effects = new ArrayList<>();
        m.prerequisites = null;
        m.requiredTechniqueId = null;
        m.isFreeMove = false;
        m.mustBeGranted = false;
        m.moveCap = 0;
        return m;
    }

    @Override protected MoveData draftFromRecord(MoveData stored) {
        // Deep-copy the DTO field-by-field. Do NOT round-trip through
        // fromMove(toMove()) — toMove() collapses the tag set to a single
        // derived category, which would discard any multi-category selection
        // (e.g. an Attack + Innate Technique move would lose its tags).
        MoveData draft = deepCopy(stored);
        draft.migrateLegacyEffects();
        draft.migrateLegacyNeverMissTier();
        return draft;
    }

    /** Field-by-field deep copy of a MoveData (lists/maps are cloned). */
    static MoveData deepCopy(MoveData s) {
        MoveData d = new MoveData();
        d.id                    = s.id;
        d.name                  = s.name;
        d.description           = s.description;
        d.moveType              = s.moveType;
        d.tags                  = s.tags != null ? new ArrayList<>(s.tags) : null;
        d.basePower             = s.basePower;
        d.hitComponents         = copyHitComponents(s.hitComponents);
        d.baseAccuracy          = s.baseAccuracy;
        d.neverMiss             = s.neverMiss;
        d.stun                  = s.stun;
        d.guardBreak            = s.guardBreak;
        d.heavy                 = s.heavy;
        d.potency               = s.potency;
        d.weaponRequired        = s.weaponRequired;
        d.apCost                = s.apCost;
        d.unleashPoint          = s.unleashPoint;
        d.baseCeCost            = s.baseCeCost;
        d.hasCeCost             = s.hasCeCost != null ? s.hasCeCost : s.baseCeCost > 0;
        d.minCeCost             = s.minCeCost;
        d.maxCeCost             = s.maxCeCost;
        d.defenseType           = s.defenseType;
        d.blockStyle            = s.blockStyle;
        d.blockDuration         = s.blockDuration;
        d.blockAffectedTags     = s.blockAffectedTags != null
                                  ? new ArrayList<>(s.blockAffectedTags) : null;
        d.blockDamageReduction  = s.blockDamageReduction;
        d.blockFlatReduction    = s.blockFlatReduction;
        d.dodgeChance           = s.dodgeChance;
        d.dodgeScope            = s.dodgeScope;
        d.parryStaggerTicks     = s.parryStaggerTicks;
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
        d.onBlockEffects        = copyEffectList(s.onBlockEffects);
        d.onParryEffects        = copyEffectList(s.onParryEffects);
        d.onDodgeEffects        = copyEffectList(s.onDodgeEffects);
        d.effects               = s.effects == null ? null : s.effects.stream()
                                  .filter(java.util.Objects::nonNull)
                                  .map(MoveEffectData::copy)
                                  .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        d.prerequisites         = s.prerequisites != null
                                  ? new LinkedHashMap<>(s.prerequisites) : null;
        d.requiredTechniqueId   = s.requiredTechniqueId;
        d.isFreeMove            = s.isFreeMove;
        d.mustBeGranted         = s.mustBeGranted;
        d.shikigamiMove         = s.shikigamiMove;
        d.moveCap               = s.moveCap;
        d.summonCharacterId     = s.summonCharacterId;
        d.aoeType               = s.aoeType;
        d.aoeTargetCount        = s.aoeTargetCount;
        d.defenseTargeting      = s.defenseTargeting;
        d.defenseTargetCount    = s.defenseTargetCount;
        d.attackLaunchMode      = s.attackLaunchMode;
        d.attackLaunchCondition = s.attackLaunchCondition != null
                                  ? s.attackLaunchCondition.copy() : null;
        d.attackLaunchChanceEnabled = s.attackLaunchChanceEnabled;
        d.attackLaunchChance    = s.attackLaunchChance;
        d.attackLaunchMoveId    = s.attackLaunchMoveId;
        return d;
    }

    private static List<MoveData.HitComponentData> copyHitComponents(
        List<MoveData.HitComponentData> source
    ) {
        if (source == null) return null;
        return source.stream()
            .filter(java.util.Objects::nonNull)
            .map(MoveEditorScreen::copyHitComponent)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static MoveData.HitComponentData copyHitComponent(
        MoveData.HitComponentData source
    ) {
        MoveData.HitComponentData copy = new MoveData.HitComponentData();
        copy.basePower = source.basePower;
        copy.tags = source.tags == null ? null : new ArrayList<>(source.tags);
        copy.delayTicks = source.delayTicks;
        copy.requiresPreviousConnection = source.requiresPreviousConnection;
        copy.avoidable = source.avoidable;
        copy.baseAccuracy = source.baseAccuracy;
        copy.onHitEffects = copyEffectListOrNull(source.onHitEffects);
        return copy;
    }

    /** Deep-copy an effect list into a mutable ArrayList (null → empty ArrayList). */
    private static ArrayList<MoveData.StatusEffectData> copyEffectList(java.util.List<MoveData.StatusEffectData> src) {
        if (src == null) return new ArrayList<>();
        return src.stream().map(MoveEditorScreen::copyEffect)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /** Deep-copy an effect list but preserve null (so empty lists aren't serialized). */
    private static ArrayList<MoveData.StatusEffectData> copyEffectListOrNull(java.util.List<MoveData.StatusEffectData> src) {
        if (src == null) return null;
        return src.stream().map(MoveEditorScreen::copyEffect)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static MoveData.StatusEffectData copyEffect(MoveData.StatusEffectData e) {
        if (e == null) return null;
        MoveData.StatusEffectData c = new MoveData.StatusEffectData();
        // Summon rows carry a shikigami id instead of a status type — copy whole.
        if (e.isSummon()) {
            c.summonCharacterId = e.summonCharacterId;
            return c;
        }
        // Coded rows carry an ability key/action instead of a status type — copy them whole.
        if (e.isCoded()) {
            c.codedAbilityKey = e.codedAbilityKey;
            c.codedAction     = e.codedAction;
            c.codedTarget     = e.codedTarget;
            c.codedStackCount = e.codedStackCount;
            c.codedParameters = TechniqueMasteryProgressions.copyIntegers(e.codedParameters);
            c.masteryProgression = TechniqueMasteryProgressions.copy(e.masteryProgression);
            return c;
        }
        StatusEffectType type;
        try {
            type = StatusEffectType.fromName(e.type, e.magnitude);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        // Store one canonical type per stat; non-stat statuses keep their own type.
        c.type            = canonicalStatusType(type).name();
        c.durationRounds  = e.durationRounds;
        c.durationTicks   = e.durationTicks;
        c.magnitude       = type.usesMagnitude()
            ? type.signedMagnitude(StatusEffectType.normalizeStoredMagnitude(e.type, e.magnitude))
            : 0.0;
        c.perTickRemovalChance = e.perTickRemovalChance;
        c.masteryProgression = TechniqueMasteryProgressions.copy(e.masteryProgression);
        return c;
    }

    private static StatusEffectType canonicalStatusType(StatusEffectType type) {
        return type.isStatModifier() && type.signedMagnitude(1.0) < 0
            ? type.opposite() : type;
    }

    private static List<StatusEffectType> editableStatusTypes() {
        return List.of(StatusEffectType.values()).stream()
            .filter(type -> !type.isStatModifier() || type.signedMagnitude(1.0) > 0)
            .toList();
    }

    private static String statusLabel(StatusEffectType type) {
        StatusEffectType canonical = canonicalStatusType(type);
        if (!canonical.isStatModifier()) return canonical.displayName();
        return canonical.baseStat() != null
            ? canonical.baseStat().label
            : canonical.battleStat().label;
    }

    private static String statusLabel(String typeName, double magnitude) {
        try {
            return statusLabel(StatusEffectType.fromName(typeName, magnitude));
        } catch (IllegalArgumentException ignored) {
            return StatusEffectType.referenceDisplayName(typeName);
        }
    }

    private static String statusEffectSummary(MoveData.StatusEffectData effect) {
        try {
            StatusEffectType type = StatusEffectType.fromName(effect.type, effect.magnitude);
            String summary = type.requiresTickDuration()
                ? statusLabel(effect.type, effect.magnitude) + " | AP ticks=" + effect.durationTicks
                : type.requiresRoundDuration()
                    ? statusLabel(effect.type, effect.magnitude)
                        + " | rounds=" + effect.durationRounds
                : statusLabel(effect.type, effect.magnitude)
                    + " | rounds=" + effect.durationRounds
                    + " | ticks=" + effect.durationTicks;
            return type.usesMagnitude()
                ? summary + (type.requiresRoundDuration() ? " | damage/round=" : " | amount=")
                    + effect.magnitude
                : summary;
        } catch (IllegalArgumentException ignored) {
            return statusLabel(effect.type, effect.magnitude)
                + " | rounds=" + effect.durationRounds
                + " | ticks=" + effect.durationTicks
                + " | amount=" + effect.magnitude;
        }
    }

    @Override protected String idOf(MoveData r) { return r.id; }

    @Override protected String nextId() { return repo.nextId(); }

    @Override protected void stampNewId(MoveData draft) { draft.id = repo.nextId(); }

    @Override protected String listLabel(MoveData r) {
        return r.name == null || r.name.isEmpty() ? "(unnamed)" : r.name;
    }

    @Override protected List<String> recordSections() {
        return moveRecordSections(cursedTechniqueNames());
    }

    @Override protected String recordSection(MoveData record) {
        String group = moveRecordGroup(record);
        String techniquePrefix = CURSED_TECHNIQUES_SECTION + "/";
        if (group.startsWith(techniquePrefix)) {
            String requestedName = group.substring(techniquePrefix.length());
            group = techniquePrefix + canonicalTechniqueName(requestedName);
        }
        return group + "/" + moveRecordSection(record);
    }

    @Override protected String recordSectionParent(String section) {
        int separator = section.lastIndexOf('/');
        return separator < 0 ? null : section.substring(0, separator);
    }

    @Override protected String recordSectionLabel(String section) {
        int separator = section.lastIndexOf('/');
        return separator < 0 ? section : section.substring(separator + 1);
    }

    /**
     * Assign multi-purpose data by Defense, then Attack, then Utility priority.
     * Defence wins over attack, so a Defensive+Attack hybrid lists under DEFENSE.
     */
    static String moveRecordSection(MoveData record) {
        List<String> tags = record.tags == null ? List.of() : record.tags;
        if (tags.contains(MoveTag.DEFENSIVE.name())) return "DEFENSE";
        if (tags.contains(MoveTag.ATTACK.name())) return "ATTACK";
        return "UTILITY";
    }

    static String moveRecordGroup(MoveData record) {
        MoveType moveType = record.effectiveMoveType();
        if (moveType == MoveType.CURSED_SPIRIT) return CURSED_SPIRIT_SECTION;
        if (moveType == MoveType.SHIKIGAMI) return SHIKIGAMI_SECTION;
        if (record.requiredTechniqueId != null && !record.requiredTechniqueId.isBlank()) {
            return CURSED_TECHNIQUES_SECTION + "/" + record.requiredTechniqueId.trim();
        }
        return SORCERER_SECTION;
    }

    private static String moveTypeLabel(MoveType type) {
        return switch (type) {
            case SORCERER -> "Sorcerer";
            case CURSED_SPIRIT -> "Cursed Spirit";
            case SHIKIGAMI -> "Shikigami";
        };
    }

    private static MoveType moveTypeFromLabel(String label) {
        if (label != null) {
            for (MoveType type : MoveType.values()) {
                if (moveTypeLabel(type).equalsIgnoreCase(label.trim())) return type;
            }
        }
        return MoveType.SORCERER;
    }

    static List<String> moveRecordSections(List<String> techniqueNames) {
        TreeMap<String, String> sortedNames = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (techniqueNames != null) {
            for (String name : techniqueNames) {
                if (name != null && !name.isBlank()) {
                    sortedNames.putIfAbsent(name.trim(), name.trim());
                }
            }
        }

        List<String> sections = new ArrayList<>();
        addMoveRecordGroup(sections, SORCERER_SECTION);
        addMoveRecordGroup(sections, CURSED_SPIRIT_SECTION);
        sections.add(CURSED_TECHNIQUES_SECTION);
        for (String techniqueName : sortedNames.values()) {
            addMoveRecordGroup(
                sections, CURSED_TECHNIQUES_SECTION + "/" + techniqueName);
        }
        addMoveRecordGroup(sections, SHIKIGAMI_SECTION);
        return List.copyOf(sections);
    }

    private static void addMoveRecordGroup(List<String> sections, String group) {
        sections.add(group);
        for (String purpose : MOVE_PURPOSE_SECTIONS) {
            sections.add(group + "/" + purpose);
        }
    }

    private List<String> cursedTechniqueNames() {
        TreeMap<String, String> names = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (InnateTechniqueData technique : techniqueRepo.getAll()) {
            if (technique.name != null && !technique.name.isBlank()) {
                names.putIfAbsent(technique.name.trim(), technique.name.trim());
            }
        }
        for (MoveData move : records) {
            if (move.effectiveMoveType() != MoveType.SORCERER
                || move.requiredTechniqueId == null
                || move.requiredTechniqueId.isBlank()) {
                continue;
            }
            String name = move.requiredTechniqueId.trim();
            names.putIfAbsent(name, name);
        }
        return List.copyOf(names.values());
    }

    private String canonicalTechniqueName(String requestedName) {
        return cursedTechniqueNames().stream()
            .filter(name -> name.equalsIgnoreCase(requestedName))
            .findFirst()
            .orElse(requestedName);
    }

    @Override protected boolean isNewDraft(MoveData draft) {
        return draft.id == null || draft.id.isEmpty()
            || repo.findById(draft.id).isEmpty();
    }

    @Override
    protected void reloadRecords() throws IOException {
        repo.load();
        charRepo.load();
        techniqueRepo.load();
        records.clear();
        records.addAll(repo.getAll());
    }

    @Override
    protected ValidationResult validateAndSave(MoveData d) {
        if (d.name == null || d.name.trim().isEmpty()) {
            return ValidationResult.error("Name is required.");
        }
        if (d.tags == null || d.tags.isEmpty()) {
            return ValidationResult.error("At least one tag is required.");
        }
        String tagError = categoryTagValidationError(d);
        if (tagError != null) return ValidationResult.error(tagError);
        String launchError = attackLaunchReferenceValidationError(d, repo.getAll());
        if (launchError != null) return ValidationResult.error(launchError);
        String summonError = summonReferenceValidationError(
            d.summonCharacterId, charRepo.getAll());
        if (summonError != null) return ValidationResult.error(summonError);
        if (d.effects != null) {
            for (int index = 0; index < d.effects.size(); index++) {
                MoveEffectData effect = d.effects.get(index);
                if (effect == null || !AbilityEffectType.SUMMON_CHARACTER.name()
                    .equalsIgnoreCase(effect.type)) {
                    if (effect != null && AbilityEffectType.TRANSFORM_CHARACTER.name()
                        .equalsIgnoreCase(effect.type)) {
                        String formError = AbilityEditorScreen.transformationReferenceValidationError(
                            effect.characterId, charRepo.getAll());
                        if (formError != null) {
                            return ValidationResult.error(
                                "Effect " + (index + 1) + ": " + formError);
                        }
                    }
                    continue;
                }
                summonError = summonReferenceValidationError(effect.characterId, charRepo.getAll());
                if (summonError != null) {
                    return ValidationResult.error(
                        "Effect " + (index + 1) + ": " + summonError);
                }
            }
        }
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
        boolean attack    = move.tags.contains(MoveTag.ATTACK.name());
        boolean defensive = move.tags.contains(MoveTag.DEFENSIVE.name());
        boolean utility   = move.tags.contains(MoveTag.UTILITY.name());
        if (!attack && !defensive && !utility) {
            return "Select at least one of Attack, Utility, or Defensive.";
        }
        // ATTACK combines with DEFENSIVE: the hybrid plays on the defensive
        // timeline (defence wins) and its attack launches per the attack
        // section's launch mode. UTILITY combines with either as before: the
        // hybrid keeps its base category and authors its on-fire effect rows
        // in the UTILITY section.
        boolean hasAttackTargetingTag = List.of(
            MoveTag.MELEE, MoveTag.RANGED, MoveTag.AOE, MoveTag.FRIENDLY_FIRE).stream()
            .anyMatch(tag -> move.tags.contains(tag.name()));
        if (!attack && hasAttackTargetingTag) {
            return "Melee, Ranged, AOE, and Friendly Fire tags require Attack.";
        }
        if (move.tags.contains(MoveTag.FRIENDLY_FIRE.name())
            && !move.tags.contains(MoveTag.AOE.name())) {
            return "Friendly Fire requires AOE.";
        }
        if (attack) {
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
            boolean hasCodedSelfEffect = move.effects != null
                ? move.effects.stream()
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(effect -> MoveEffectTrigger.ON_FIRE.name()
                        .equalsIgnoreCase(effect.trigger)
                        && AbilityEffectType.CODED_MOVE_ACTION.name()
                            .equalsIgnoreCase(effect.type))
                : move.selfEffects != null
                    && move.selfEffects.stream().anyMatch(MoveData.StatusEffectData::isCoded);
            if (defense == DefenseType.NONE && !hasCodedSelfEffect) {
                return "A Defensive move needs a defense type (Block, Parry, or Dodge) or a coded self effect.";
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
            MoveData codedDependent = repo.getAll().stream()
                .filter(move -> !id.equals(move.id))
                .filter(move -> referencesCodedMoveTarget(move, id))
                .findFirst().orElse(null);
            if (codedDependent != null) {
                return ValidationResult.error(
                    "Cannot delete: move \"" + codedDependent.name + "\" references this move.");
            }
            MoveData conditionDependent = repo.getAll().stream()
                .filter(move -> !id.equals(move.id))
                .filter(move -> move.effects != null && move.effects.stream()
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(effect -> conditionReferencesMove(effect.condition, id)))
                .findFirst().orElse(null);
            if (conditionDependent != null) {
                return ValidationResult.error(
                    "Cannot delete: move \"" + conditionDependent.name
                        + "\" has an effect condition referencing this move.");
            }

            Map<String, String> remappedIds = new LinkedHashMap<>();
            int nextIndex = 0;
            for (MoveData move : repo.getAll()) {
                if (id.equals(move.id)) continue;
                remappedIds.put(move.id,
                    com.jjktbf.model.repo.BaseRepository.formatId(nextIndex++));
            }

            for (CharacterData character : characterRepo.getAll()) {
                if (character.moveIds != null) {
                    character.moveIds = remapCharacterMoveIds(
                        character.moveIds, id, remappedIds);
                }
                if (character.availableMoveIds != null) {
                    character.availableMoveIds = remapCharacterMoveIds(
                        character.availableMoveIds, id, remappedIds);
                }
            }
            for (AbilityData ability : abilityRepo.getAll()) {
                if ("MOVE".equalsIgnoreCase(ability.sourceType)) {
                    ability.sourceValue = remappedIds.getOrDefault(
                        ability.sourceValue, ability.sourceValue);
                }
                remapConditionMoves(ability.activationCondition, remappedIds);
                if (ability.activationConditions != null) {
                    ability.activationConditions.stream()
                        .filter(java.util.Objects::nonNull)
                        .forEach(rule -> remapConditionMoves(rule.condition, remappedIds));
                }
                if (ability.effects == null) continue;
                ability.effects.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(MoveEditorScreen::referencesMoveAcquisition)
                    .forEach(effect -> effect.moveId = remappedIds.getOrDefault(
                        effect.moveId, effect.moveId));
            }
            remapCodedMoveTargets(repo.getAll(), remappedIds);

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
        if ("MOVE".equalsIgnoreCase(ability.sourceType) && moveId.equals(ability.sourceValue)) {
            return "move source";
        }
        if (ability.effects != null) {
            boolean acquiresMove = ability.effects.stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(effect -> referencesMoveAcquisition(effect)
                    && moveId.equals(effect.moveId));
            if (acquiresMove) return "granted or forced move";
        }
        if (conditionReferencesMove(ability.activationCondition, moveId)) {
            return "activation condition";
        }
        if (ability.activationConditions != null && ability.activationConditions.stream()
            .filter(java.util.Objects::nonNull)
            .anyMatch(rule -> conditionReferencesMove(rule.condition, moveId))) {
            return "activation condition";
        }
        return null;
    }

    private static boolean referencesMoveAcquisition(AbilityEffectData effect) {
        return AbilityEffectType.GRANT_MOVE.name().equalsIgnoreCase(effect.type)
            || AbilityEffectType.UNLOCK_MOVE.name().equalsIgnoreCase(effect.type);
    }

    private static List<String> remapCharacterMoveIds(
        List<String> ids,
        String deletedId,
        Map<String, String> remappedIds
    ) {
        return ids.stream()
            .filter(moveId -> !deletedId.equals(moveId))
            .map(moveId -> remappedIds.getOrDefault(moveId, moveId))
            .distinct()
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    static void remapCodedMoveTargets(List<MoveData> moves, Map<String, String> remappedIds) {
        for (MoveData move : moves) {
            for (MoveData.StatusEffectData effect : codedEffects(move)) {
                if (effect.codedTarget != null) {
                    effect.codedTarget = remappedIds.getOrDefault(
                        effect.codedTarget, effect.codedTarget);
                }
            }
            if (move.effects != null) {
                move.effects.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(effect -> AbilityEffectType.CODED_MOVE_ACTION.name()
                        .equalsIgnoreCase(effect.type))
                    .filter(effect -> effect.codedTarget != null)
                    .forEach(effect -> effect.codedTarget = remappedIds.getOrDefault(
                        effect.codedTarget, effect.codedTarget));
                move.effects.stream()
                    .filter(java.util.Objects::nonNull)
                    .forEach(effect -> remapConditionMoves(effect.condition, remappedIds));
            }
        }
    }

    private static boolean referencesCodedMoveTarget(MoveData move, String id) {
        boolean legacy = codedEffects(move).stream()
            .anyMatch(effect -> id.equals(effect.codedTarget));
        if (legacy || move.effects == null) return legacy;
        return move.effects.stream()
            .filter(java.util.Objects::nonNull)
            .filter(effect -> AbilityEffectType.CODED_MOVE_ACTION.name()
                .equalsIgnoreCase(effect.type))
            .anyMatch(effect -> id.equals(effect.codedTarget));
    }

    private static List<MoveData.StatusEffectData> codedEffects(MoveData move) {
        List<MoveData.StatusEffectData> effects = new ArrayList<>();
        // On-hit coded effects live per hit component; scan each one.
        if (move.hitComponents != null) {
            for (MoveData.HitComponentData component : move.hitComponents) {
                if (component == null || component.onHitEffects == null) continue;
                component.onHitEffects.stream().filter(MoveData.StatusEffectData::isCoded)
                    .forEach(effects::add);
            }
        }
        if (move.selfEffects != null) {
            move.selfEffects.stream().filter(MoveData.StatusEffectData::isCoded)
                .forEach(effects::add);
        }
        return effects;
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
        if (condition.moveId != null) {
            condition.moveId = remappedIds.getOrDefault(condition.moveId, condition.moveId);
        }
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
        defenseFieldsContainer = null;
        defenseTargetingContainer = null;
        defenseEffectsContainer = null;
        ceMinMaxContainer = null;
        powerFieldsContainer = null;
        attackLaunchContainer = null;
        weaponRequiredCheckbox = null;

        if (d.hitComponents != null && hasTag(d, MoveTag.ATTACK)) {
            synchronizeParentDamageTags(d);
        }

        Table form = formRoot();

        // ── Identity ───────────────────────────────────────────────────────────
        Table identity = formSection(form, "NAME");
        identity.add(idBadge(d.id)).left().row();
        identity.add(labelledField("Name", d.name,
                s -> { d.name = s; })).growX().row();
        identity.add(labelledKeywordField("Description", d.description,
                s -> { d.description = s; })).growX().row();

        // ── Tags ───────────────────────────────────────────────────────────────
        Table tagsSection = formSection(form, "TAGS");
        Set<MoveTag> initialTags = new LinkedHashSet<>();
        if (d.tags != null) {
            for (String t : d.tags) {
                try { initialTags.add(MoveTag.valueOf(t)); } catch (Exception ignored) {}
            }
        }
        Set<MoveTag> previousTypeTags = typeTags(initialTags);
        TagPicker tagPicker = new TagPicker(initialTags, tags -> {
            Set<MoveTag> selectedTypeTags = typeTags(tags);
            boolean typeTagsChanged = !selectedTypeTags.equals(previousTypeTags);
            d.tags = tags.stream().map(MoveTag::name)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            if (!tags.contains(MoveTag.INNATE_TECHNIQUE)) {
                clearMasteryProgression(d);
            }
            // A newly-formed Defensive+Attack hybrid defaults to launching its
            // attack on defence (the signature hybrid behaviour), and authors
            // its custom attack as hit components — seed the component list
            // from any legacy base power so the attack card opens in component
            // mode instead of the legacy single-hit field.
            if (tags.contains(MoveTag.DEFENSIVE) && tags.contains(MoveTag.ATTACK)) {
                if (AttackLaunchMode.fromName(d.attackLaunchMode) == null) {
                    d.attackLaunchMode = AttackLaunchMode.ON_DEFENCE.name();
                }
                enableHitComponentEditing(d);
            }
            if (d.hitComponents != null && tags.contains(MoveTag.ATTACK)) {
                if (typeTagsChanged && !selectedTypeTags.isEmpty()) {
                    applyMoveDamageTagsToComponents(d, selectedTypeTags);
                } else {
                    synchronizeParentDamageTags(d);
                }
            }
            // GUARD_BREAK/HEAVY are modifier tags backed by dedicated flags (not
            // part of any MoveCategory), so keep them in sync with the tag selection.
            d.guardBreak = tags.contains(MoveTag.GUARD_BREAK);
            d.heavy = tags.contains(MoveTag.HEAVY);
            ensureTechniqueStatPrerequisites(d, tags);
            synchronizeWeaponRequirement(d);
            previousTypeTags.clear();
            previousTypeTags.addAll(typeTagsFromNames(d.tags));
            if (d.hitComponents != null) rebuildDetail();
            else refreshCategorySections(d);
        }, game.audio()::play, skin);
        // Sync the draft's tags with the picker's coupling-enforced initial set
        // (e.g. a technique tag implies CURSED_ENERGY). suppressDirty is on
        // during build, so this won't mark the record dirty on load.
        d.tags = tagPicker.getSelected().stream().map(MoveTag::name)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (d.hitComponents != null && hasTag(d, MoveTag.ATTACK)) {
            synchronizeParentDamageTags(d);
        }
        previousTypeTags.clear();
        previousTypeTags.addAll(typeTagsFromNames(d.tags));
        d.guardBreak = tagPicker.getSelected().contains(MoveTag.GUARD_BREAK);
        d.heavy = tagPicker.getSelected().contains(MoveTag.HEAVY);
        synchronizeWeaponRequirement(d);
        tagsSection.add(tagPicker).growX().row();
        // Derived TIMELINE marker: which battle board this move plans on.
        // Defence wins over attack, so a Defensive+Attack hybrid shows
        // DEFENSIVE. Read-only, like the MULTI-HIT chip below.
        {
            Table timelineRow = new Table(skin);
            timelineRow.left().padTop(2f);
            Label chip = new Label("  TIMELINE  ", skin, "small");
            chip.setColor(skin.has("text-dim", com.badlogic.gdx.graphics.Color.class)
                ? skin.getColor("text-dim")
                : com.badlogic.gdx.graphics.Color.GRAY);
            timelineRow.add(chip).left();
            timelineRow.add(new Label("derived — " + derivedTimelineName(d) + " board",
                skin, "small")).padLeft(6f);
            tagsSection.add(timelineRow).left().row();
        }
        if (d.hitComponents != null && hasTag(d, MoveTag.ATTACK)) {
            tagsSection.add(formHint(
                "Move damage types apply to every hit; refine individual hits below.")).row();
        }
        // Derived MULTI-HIT marker: shown (read-only) whenever the move authors
        // more than one hit component. It is not a MoveTag, not persisted, and
        // cannot be toggled — it reflects the component count.
        if (d.hitComponents != null && d.hitComponents.size() > 1) {
            Table multiHitRow = new Table(skin);
            multiHitRow.left().padTop(2f);
            Label chip = new Label("  MULTI-HIT  ", skin, "small");
            // PixelSkin has no "disabled" Color resource (Skin.getColor would
            // throw), so reuse the muted text-dim tone used elsewhere for
            // derived/non-authorable hints.
            chip.setColor(skin.has("text-dim", com.badlogic.gdx.graphics.Color.class)
                ? skin.getColor("text-dim")
                : com.badlogic.gdx.graphics.Color.GRAY);
            multiHitRow.add(chip).left();
            multiHitRow.add(new Label("derived — " + d.hitComponents.size() + " hits",
                skin, "small")).padLeft(6f);
            tagsSection.add(multiHitRow).left().row();
        }

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
                game.audio().play(SoundCue.UI_TOGGLE);
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
        misc.add(labelledIntField("Move Cap (uses per round, 0 = unlimited)",
            d.moveCap, 0, 99999, v -> d.moveCap = v)).growX().row();

        CheckBox freeCb = new CheckBox(" Free move (does not consume a slot)", skin);
        freeCb.setChecked(d.isFreeMove);
        freeCb.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                game.audio().play(SoundCue.UI_TOGGLE);
                d.isFreeMove = freeCb.isChecked();
                markDirty();
            }
        });
        misc.add(freeCb).left().row();

        SelectBox<String> moveTypeSelect = new DynamicSelectBox<>(skin, uiProfile);
        moveTypeSelect.setItems(java.util.Arrays.stream(MoveType.values())
            .map(MoveEditorScreen::moveTypeLabel)
            .toList()
            .toArray(new String[0]));
        moveTypeSelect.setSelected(moveTypeLabel(d.effectiveMoveType()));
        moveTypeSelect.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                game.audio().play(SoundCue.UI_TOGGLE);
                d.moveType = moveTypeFromLabel(moveTypeSelect.getSelected()).name();
                d.shikigamiMove = null;
                markDirty();
            }
        });
        misc.add(labelledRow("Move Type", moveTypeSelect)).growX().row();
        misc.add(formHint("Controls which character classes may learn this move."))
            .left().row();

        CheckBox grantedCb = new CheckBox(" Must be granted", skin);
        grantedCb.setChecked(d.mustBeGranted);
        grantedCb.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                game.audio().play(SoundCue.UI_TOGGLE);
                d.mustBeGranted = grantedCb.isChecked();
                markDirty();
            }
        });
        misc.add(grantedCb).left().row();
        misc.add(formHint(
            "Hidden until an ability grants or forces it. A granted move is learned normally."))
            .left().row();

        // Sword-tagged moves and parries always require a weapon, so their
        // requirement is shown as a fixed-on, disabled checkbox.
        weaponRequiredCheckbox = new CheckBox(" Requires a weapon", skin);
        synchronizeWeaponRequirement(d);
        weaponRequiredCheckbox.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                if (weaponRequiredCheckbox.isDisabled()) return;
                game.audio().play(SoundCue.UI_TOGGLE);
                d.weaponRequired = weaponRequiredCheckbox.isChecked();
                markDirty();
            }
        });
        misc.add(weaponRequiredCheckbox).left().row();
        misc.add(formHint(
            "Only usable by characters with a weapon. Always on for parries and sword-tagged moves."))
            .left().row();

        return form;
    }

    // =========================================================================
    // Conditional sub-sections
    // =========================================================================

    private Actor buildCategorySections(MoveData d) {
        Table sections = formRoot();
        sections.pad(0f);
        powerFieldsContainer = null;
        defenseFieldsContainer = null;
        defenseTargetingContainer = null;
        defenseEffectsContainer = null;
        aoeFieldsContainer = null;
        attackLaunchContainer = null;

        // The DEFENSE card sits above the ATTACK card: defence wins over
        // attack, so a Defensive+Attack hybrid reads top-down as a defence
        // whose attack section hangs underneath.
        if (hasTag(d, MoveTag.DEFENSIVE)) {
            Table defense = formSection(sections, "DEFENSE");
            defense.add(labelledRow("Type", new EnumSelectBox<>(
                DefenseType.class, d.defenseType, false,
                s -> {
                    d.defenseType = s;
                    synchronizeWeaponRequirement(d);
                    refreshConditionalFields(d);
                }, skin, uiProfile))).growX().row();

            defenseFieldsContainer = new Container<>();
            defenseFieldsContainer.setActor(buildDefenseFields(d));
            defense.add(defenseFieldsContainer).growX().row();

            defenseTargetingContainer = new Container<>();
            defenseTargetingContainer.setActor(buildDefenseTargetingFields(d));
            defense.add(defenseTargetingContainer).growX().row();

            // Resolution effects fire when this defense actually resolves — when
            // a block blocks, a dodge dodges, or a parry parries an incoming
            // hit. On-fire effects are authored in the UTILITY section: tick
            // UTILITY alongside DEFENSIVE to reveal it for a hybrid move.
            defenseEffectsContainer = new Container<>();
            defenseEffectsContainer.setActor(buildDefenseResolutionEffects(d));
            defense.add(defenseEffectsContainer).growX().row();
        }

        if (hasTag(d, MoveTag.ATTACK)) {
            Table attack = formSection(sections, "ATTACK");

            // A Defensive+Attack hybrid gets an altered attack section on top:
            // launch mode, launch conditions, and the existing-vs-custom
            // choice. Referencing an existing move ends the section there.
            if (d.isDefenceAttackHybrid()) {
                attackLaunchContainer = new Container<>();
                attackLaunchContainer.setActor(buildAttackLaunchFields(d));
                attack.add(attackLaunchContainer).growX().row();
                if (attackLaunchReferencesMove(d)) {
                    return finishCategorySections(sections, d);
                }
            }

            attack.add(new Label("POWER / ACCURACY", skin, "small")).left().row();
            powerFieldsContainer = new Container<>();
            powerFieldsContainer.setActor(buildPowerFields(d));
            attack.add(powerFieldsContainer).growX().row();

            attack.add(buildAccuracyPrioritySelector(
                d, AbilityEffectType.NEVER_MISS, "Never Miss Tier")).growX().row();
            attack.add(formHint(
                "None uses normal accuracy. Never Miss wins against an equal or lower Never Hit tier."))
                .left().row();
            // On-hit effects are authored per hit component below — no move-level section.

            // AOE type sub-section: shown only when the move is both an ATTACK
            // and AOE-tagged. Lets the author pick the targeting shape.
            aoeFieldsContainer = new Container<>();
            aoeFieldsContainer.setActor(hasTag(d, MoveTag.AOE) ? buildAoeFields(d) : new Table());
            attack.add(aoeFieldsContainer).growX().row();

            // On-fire effects are authored only in the UTILITY section: tick
            // UTILITY alongside ATTACK to reveal it for a hybrid move.
            if (d.hitComponents == null) {
                attack.add(new Label("ON-HIT EFFECTS", skin, "small")).padTop(8f).left().row();
                attack.add(buildMoveEffectsEditor(
                    d, MoveEffectTrigger.ON_HIT, null)).growX().row();
            } else {
                attack.add(new Label("ON-HIT EFFECTS (ALL HITS)", skin, "small"))
                    .padTop(8f).left().row();
                attack.add(buildMoveEffectsEditor(
                    d, MoveEffectTrigger.ON_HIT, null)).growX().row();
            }
        }

        return finishCategorySections(sections, d);
    }

    /** Append the tag-independent trailing cards (UTILITY + AVAILABILITY). */
    private Actor finishCategorySections(Table sections, MoveData d) {
        if (hasTag(d, MoveTag.UTILITY)) {
            Table utility = formSection(sections, "UTILITY");
            utility.add(new Label("ON-FIRE EFFECTS", skin, "small")).left().row();
            utility.add(buildMoveEffectsEditor(
                d, MoveEffectTrigger.ON_FIRE, null)).growX().row();
        }

        Table availability = formSection(sections, "AVAILABILITY");
        availability.add(formHint(
            "Constraints here disable and grey out the move while they apply."))
            .left().row();
        availability.add(buildMoveEffectsEditor(
            d, MoveEffectTrigger.AVAILABILITY, null)).growX().row();

        return sections;
    }

    /**
     * The altered attack section of a Defensive+Attack hybrid: when the attack
     * launches, the conditions (and optional chance roll) gating the launch,
     * and the attack source — a referenced existing move or a custom attack
     * defined by the rest of this move's attack fields.
     */
    private Actor buildAttackLaunchFields(MoveData d) {
        Table t = new Table(skin);
        t.defaults().left().pad(4);

        AttackLaunchMode current = AttackLaunchMode.fromName(d.attackLaunchMode);
        if (current == null) {
            current = AttackLaunchMode.ON_DEFENCE;
            d.attackLaunchMode = current.name();
        }
        EnumSelectBox<AttackLaunchMode> modeSelect = new EnumSelectBox<>(
            AttackLaunchMode.class, current.name(), false,
            name -> {
                d.attackLaunchMode = name;
                game.audio().play(SoundCue.UI_NAVIGATE);
                markDirty();
                rebuildDetail();
            }, skin, uiProfile);
        t.add(labelledRow("Launch (" + current.displayName() + ")", modeSelect)).growX().row();
        t.add(formHint(current == AttackLaunchMode.ON_FIRE
            ? "The attack launches on this move's firing tick, right after the defence is granted."
            : "The attack launches when this move's defence resolves an incoming attack "
                + "(block, dodge, or parry), targeting the attacker."))
            .left().row();

        // Secondary launch conditions — the same condition vocabulary as an
        // effect row's activation conditions, but gating the launch itself.
        t.add(new Label("LAUNCH CONDITIONS", skin, "small")).padTop(8f).left().row();
        if (d.attackLaunchCondition == null) d.attackLaunchCondition = AbilityConditionData.always();
        t.add(new ConditionTreeEditor(
            d.attackLaunchCondition,
            repo.getAll(),
            this::markDirty,
            game.audio()::play,
            masteryEligible(d),
            uiProfile,
            skin)).growX().row();

        CheckBox chanceEnabled = new CheckBox(" Roll launch chance", skin);
        chanceEnabled.setChecked(Boolean.TRUE.equals(d.attackLaunchChanceEnabled));
        TextField chance = new HoverTextField(
            d.attackLaunchChance == null ? "100" : String.valueOf(d.attackLaunchChance), skin);
        chance.setTextFieldFilter((field, character) -> Character.isDigit(character));
        chance.setDisabled(!chanceEnabled.isChecked());
        chanceEnabled.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                d.attackLaunchChanceEnabled = chanceEnabled.isChecked() ? Boolean.TRUE : null;
                if (d.attackLaunchChance == null) d.attackLaunchChance = 100;
                chance.setDisabled(!chanceEnabled.isChecked());
                game.audio().play(SoundCue.UI_TOGGLE);
                markDirty();
            }
        });
        chance.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                Integer parsed = parseWholeNumber(chance.getText());
                if (parsed != null) {
                    d.attackLaunchChance = Math.max(0, Math.min(100, parsed));
                    markDirty();
                }
            }
        });
        t.add(chanceEnabled).growX().row();
        t.add(labelledRow("Launch chance %", chance)).growX().row();

        // Attack source: an existing move ends the attack section there; a
        // custom move reveals the normal attack editors below.
        t.add(new Label("ATTACK SOURCE", skin, "small")).padTop(8f).left().row();
        boolean references = attackLaunchReferencesMove(d);
        SelectBox<String> source = new DynamicSelectBox<>(skin, uiProfile);
        source.setItems("Custom move", "Existing move");
        source.setSelected(references ? "Existing move" : "Custom move");
        source.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                boolean wantReference = "Existing move".equals(source.getSelected());
                if (wantReference && !attackLaunchReferencesMove(d)) {
                    d.attackLaunchMoveId = firstLaunchReferenceId(d);
                } else if (!wantReference) {
                    d.attackLaunchMoveId = null;
                }
                game.audio().play(SoundCue.UI_NAVIGATE);
                markDirty();
                rebuildDetail();
            }
        });
        t.add(labelledRow("Source", source)).growX().row();

        if (references) {
            List<String> options = launchReferenceOptions(d);
            if (options.isEmpty()) {
                t.add(formHint("No other moves exist to reference yet.")).left().row();
            } else {
                String selectedLabel = launchReferenceLabel(d, options);
                DynamicSelectBox<String> moveSelect = new DynamicSelectBox<>(skin, uiProfile);
                moveSelect.setItems(options.toArray(new String[0]));
                moveSelect.setSelected(selectedLabel);
                moveSelect.addListener(new ChangeListener() {
                    @Override public void changed(ChangeEvent event, Actor actor) {
                        d.attackLaunchMoveId = moveIdFromLabel(moveSelect.getSelected());
                        game.audio().play(SoundCue.UI_NAVIGATE);
                        markDirty();
                    }
                });
                t.add(labelledRow("Move", moveSelect)).growX().row();
                t.add(formHint(
                    "The referenced move launches as this move's attack — its own CE cost "
                        + "is paid when it launches."))
                    .left().row();
            }
        } else {
            t.add(formHint(
                "The custom attack is defined below: damage types, hits, accuracy, on-hit effects."))
                .left().row();
        }
        return t;
    }

    static boolean attackLaunchReferencesMove(MoveData d) {
        return d.attackLaunchMoveId != null && !d.attackLaunchMoveId.isBlank();
    }

    /** Dropdown labels ("id - name") of every other move that can be launched. */
    private List<String> launchReferenceOptions(MoveData d) {
        List<String> options = new ArrayList<>();
        for (MoveData move : repo.getAll()) {
            if (move == null || move.id == null || move.id.equals(d.id)) continue;
            options.add(moveLabel(move));
        }
        return options;
    }

    private String firstLaunchReferenceId(MoveData d) {
        return launchReferenceOptions(d).stream()
            .findFirst()
            .map(MoveEditorScreen::moveIdFromLabel)
            .orElse(null);
    }

    /** The label representing the referenced id, or the first option when unset. */
    private static String launchReferenceLabel(MoveData d, List<String> options) {
        String id = d.attackLaunchMoveId == null ? null : d.attackLaunchMoveId.trim();
        if (id != null) {
            for (String option : options) {
                if (id.equals(moveIdFromLabel(option))) return option;
            }
        }
        return options.isEmpty() ? null : options.get(0);
    }

    private static Integer parseWholeNumber(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Actor buildPowerFields(MoveData d) {
        Table t = new Table(skin);
        t.defaults().left().pad(4);
        Label combinedPower = null;
        if (d.hitComponents == null) {
            t.add(labelledIntField("Combined Base Power", d.basePower, 0, 99999,
                    v -> { d.basePower = v; })).growX().row();
        } else {
            combinedPower = new Label(String.valueOf(combinedBasePower(d)), skin);
            t.add(labelledRow("Combined Base Power", combinedPower)).growX().row();
            t.add(formHint(hitCountLabel(d.hitComponents.size())
                + "; combined power is derived from the components below.")).row();
        }

        t.add(new Label("HIT COMPONENTS", skin, "small")).padTop(8f).left().row();
        if (d.hitComponents == null) {
            t.add(formHint(
                "Legacy single hit: Base Power and the move's damage type remain authoritative."))
                .row();
            TextButton enableComponents = new TextButton("Use hit components", skin);
            enableComponents.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    game.audio().play(SoundCue.UI_CONFIRM);
                    enableHitComponentEditing(d);
                    markDirty();
                    rebuildDetail();
                }
            });
            t.add(enableComponents).padTop(4f).left().row();
        } else {
            t.add(buildHitComponentsEditor(d, combinedPower)).growX().row();
        }

        // Potency gates which defensive moves can stop this attack (1–5).
        t.add(labelledIntField("Potency (1–5)", d.potency, 1, 5,
                v -> { d.potency = v; })).growX().row();
        if (d.getNeverMissTier() > 0) {
            t.add(formHint(
                "Accuracy is N/A unless a higher Never Hit tier stops the attack.")).row();
            return t;
        }
        // With hit components, accuracy is authored per hit (each component may
        // override). The move-level value is only the fallback default for
        // components that inherit it — show it as such, not as the source of truth.
        if (d.hitComponents != null) {
            t.add(formHint(
                "Accuracy is authored per hit component; the move-level value below "
                + "is the fallback default for hits that inherit it.")).row();
        }
        // Accuracy as integer 1..100; stored /100 as double.
        int acc = (int) Math.round(Math.max(0.0, Math.min(1.0, d.baseAccuracy)) * 100.0);
        t.add(labelledIntField(d.hitComponents != null
                ? "Fallback Base Accuracy %" : "Base Accuracy %", acc, 1, 100,
                v -> { d.baseAccuracy = v / 100.0; })).growX().row();
        return t;
    }

    private Actor buildHitComponentsEditor(MoveData d, Label combinedPower) {
        Table editor = new Table(skin);
        editor.defaults().left().pad(3f);

        for (int i = 0; i < d.hitComponents.size(); i++) {
            int index = i;
            MoveData.HitComponentData component = d.hitComponents.get(index);
            Table card = new Table(skin);
            card.setBackground(skin.getDrawable("battle-card"));
            card.defaults().left().pad(3f);
            card.pad(6f);

            Table header = new Table(skin);
            header.add(new Label("Hit " + (index + 1), skin, "small")).left().growX();

            TextButton up = new TextButton("Up", skin);
            up.setDisabled(index == 0);
            up.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    if (up.isDisabled()) return;
                    game.audio().play(SoundCue.UI_TOGGLE);
                    swapHitComponents(d, index, index - 1);
                    normalizeHitComponentDependencies(d);
                    markDirty();
                    rebuildDetail();
                }
            });
            header.add(up).padLeft(4f);

            TextButton down = new TextButton("Down", skin);
            down.setDisabled(index == d.hitComponents.size() - 1);
            down.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    if (down.isDisabled()) return;
                    game.audio().play(SoundCue.UI_TOGGLE);
                    swapHitComponents(d, index, index + 1);
                    normalizeHitComponentDependencies(d);
                    markDirty();
                    rebuildDetail();
                }
            });
            header.add(down).padLeft(4f);

            TextButton remove = new TextButton("X", skin);
            remove.setDisabled(d.hitComponents.size() == 1);
            remove.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    if (remove.isDisabled()) return;
                    game.audio().play(SoundCue.UI_DELETE);
                    removeHitComponent(d, index);
                    normalizeHitComponentDependencies(d);
                    synchronizeParentDamageTags(d);
                    synchronizeCombinedBasePower(d);
                    markDirty();
                    rebuildDetail();
                }
            });
            header.add(remove).padLeft(4f);
            card.add(header).growX().row();

            card.add(labelledIntField("Base Power", component.basePower, 1, 99999,
                value -> {
                    component.basePower = value;
                    synchronizeCombinedBasePower(d);
                    combinedPower.setText(String.valueOf(d.basePower));
                })).growX().row();
            card.add(new Label("Damage Types", skin)).padTop(3f).row();
            card.add(buildHitComponentTagToggles(d, component)).growX().row();

            // Per-hit accuracy. A component with no authored accuracy (the legacy
            // -1 "inherit" marker) shows the move's base accuracy as its starting
            // value so authors see what they're overriding. Never Miss hides it
            // without discarding the authored value.
            if (d.getNeverMissTier() == 0) {
                int accDisplay = component.baseAccuracy >= 0.0
                    ? (int) Math.round(component.baseAccuracy * 100.0)
                    : (int) Math.round(Math.max(0.0, Math.min(1.0, d.baseAccuracy)) * 100.0);
                card.add(labelledIntField("Base Accuracy %", accDisplay, 1, 100,
                    value -> {
                        component.baseAccuracy = value / 100.0;
                        markDirty();
                    })).growX().row();
            } else {
                card.add(formHint(
                    "Accuracy is N/A unless a higher Never Hit tier stops the attack.")).row();
            }

            int minimumDelay = component.requiresPreviousConnection && index > 0
                ? d.hitComponents.get(index - 1).delayTicks : 0;
            card.add(labelledIntField("Delay Offset (AP ticks)", component.delayTicks,
                minimumDelay, 99999,
                value -> { component.delayTicks = value; })).growX().row();

            CheckBox requiresPrevious = new CheckBox(" Requires previous hit to connect", skin);
            if (index == 0) component.requiresPreviousConnection = false;
            requiresPrevious.setChecked(component.requiresPreviousConnection);
            requiresPrevious.setDisabled(index == 0);
            requiresPrevious.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    if (requiresPrevious.isDisabled()) return;
                    game.audio().play(SoundCue.UI_TOGGLE);
                    component.requiresPreviousConnection = requiresPrevious.isChecked();
                    if (component.requiresPreviousConnection && index > 0) {
                        component.delayTicks = Math.max(component.delayTicks,
                            d.hitComponents.get(index - 1).delayTicks);
                    }
                    markDirty();
                    rebuildDetail();
                }
            });
            card.add(requiresPrevious).left().row();

            CheckBox avoidable = new CheckBox(" Avoidable by dodge or parry", skin);
            avoidable.setChecked(component.avoidable);
            avoidable.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    game.audio().play(SoundCue.UI_TOGGLE);
                    component.avoidable = avoidable.isChecked();
                    markDirty();
                }
            });
            card.add(avoidable).left().row();

            // Per-hit on-hit effects. These fire only when this specific component
            // connects, replacing the old move-level on-hit list.
            card.add(new Label("ON-HIT EFFECTS", skin, "small")).padTop(4f).left().row();
            card.add(buildMoveEffectsEditor(
                d, MoveEffectTrigger.ON_HIT, index)).growX().row();
            editor.add(card).growX().padBottom(5f).row();
        }

        TextButton add = new TextButton("+ Add hit component", skin);
        add.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                game.audio().play(SoundCue.UI_CONFIRM);
                addHitComponent(d);
                markDirty();
                rebuildDetail();
            }
        });
        editor.add(add).padTop(2f).left().row();
        return editor;
    }

    private Actor buildHitComponentTagToggles(
        MoveData move,
        MoveData.HitComponentData component
    ) {
        Table toggles = new Table(skin);
        toggles.defaults().left().pad(3f);
        Set<String> selected = COMPONENT_DAMAGE_TAGS.stream()
            .map(MoveTag::name)
            .filter(tag -> component.tags != null && component.tags.contains(tag))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        removeImpliedCursedEnergy(selected);
        component.tags = orderedComponentTags(selected);
        Map<MoveTag, CheckBox> checkBoxes = new LinkedHashMap<>();

        int column = 0;
        for (MoveTag tag : COMPONENT_DAMAGE_TAGS) {
            CheckBox checkBox = new CheckBox(pretty(tag.name()), skin);
            checkBox.setProgrammaticChangeEvents(false);
            checkBox.setChecked(selected.contains(tag.name()));
            checkBoxes.put(tag, checkBox);
            checkBox.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    if (checkBox.isChecked()) {
                        selected.add(tag.name());
                        removeImpliedCursedEnergy(selected);
                    } else if (selected.size() > 1) {
                        selected.remove(tag.name());
                    } else {
                        checkBox.setChecked(true);
                        game.audio().play(SoundCue.UI_DENIED);
                        return;
                    }
                    checkBoxes.forEach((candidate, box) ->
                        box.setChecked(selected.contains(candidate.name())));
                    component.tags = orderedComponentTags(selected);
                    synchronizeParentDamageTags(move);
                    game.audio().play(SoundCue.UI_TOGGLE);
                    markDirty();
                    rebuildDetail();
                }
            });
            toggles.add(checkBox).left();
            if (++column == 2) {
                toggles.row();
                column = 0;
            }
        }
        return toggles;
    }

    static void enableHitComponentEditing(MoveData move) {
        if (move.hitComponents != null) return;
        MoveData.HitComponentData component = new MoveData.HitComponentData();
        component.basePower = move.basePower;
        component.tags = defaultComponentTags(move);
        component.delayTicks = 0;
        component.requiresPreviousConnection = false;
        component.avoidable = true;
        // Carry the move's accuracy onto the first component, and migrate any
        // legacy move-level on-hit effects onto it so nothing is lost.
        if (move.baseAccuracy >= 0.0) component.baseAccuracy = move.baseAccuracy;
        if (move.onHitEffects != null && !move.onHitEffects.isEmpty()) {
            component.onHitEffects = move.onHitEffects.stream()
                .map(MoveEditorScreen::copyEffect)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            move.onHitEffects = null;
        }
        if (move.effects != null) {
            move.effects.stream()
                .filter(java.util.Objects::nonNull)
                .filter(effect -> MoveEffectTrigger.ON_HIT.name()
                    .equalsIgnoreCase(effect.trigger))
                .filter(effect -> effect.hitComponentIndex == null)
                .forEach(effect -> effect.hitComponentIndex = 0);
        }
        move.hitComponents = new ArrayList<>(List.of(component));
        synchronizeParentDamageTags(move);
        synchronizeCombinedBasePower(move);
    }

    static void addHitComponent(MoveData move) {
        if (move.hitComponents == null) {
            enableHitComponentEditing(move);
            return;
        }
        MoveData.HitComponentData component = new MoveData.HitComponentData();
        component.basePower = 1;
        if (move.hitComponents.isEmpty()) {
            component.tags = defaultComponentTags(move);
        } else {
            MoveData.HitComponentData previous = move.hitComponents.get(
                move.hitComponents.size() - 1);
            component.tags = editableComponentTags(previous.tags);
            if (component.tags.isEmpty()) component.tags = defaultComponentTags(move);
            component.delayTicks = previous.delayTicks;
        }
        component.requiresPreviousConnection = false;
        component.avoidable = true;
        move.hitComponents.add(component);
        normalizeHitComponentDependencies(move);
        synchronizeParentDamageTags(move);
        synchronizeCombinedBasePower(move);
    }

    static void swapHitComponents(MoveData move, int first, int second) {
        if (move == null || move.hitComponents == null
            || first < 0 || second < 0
            || first >= move.hitComponents.size() || second >= move.hitComponents.size()
            || first == second) return;
        java.util.Collections.swap(move.hitComponents, first, second);
        if (move.effects == null) return;
        for (MoveEffectData effect : move.effects) {
            if (effect == null || effect.hitComponentIndex == null
                || !MoveEffectTrigger.ON_HIT.name().equalsIgnoreCase(effect.trigger)) continue;
            if (effect.hitComponentIndex == first) effect.hitComponentIndex = second;
            else if (effect.hitComponentIndex == second) effect.hitComponentIndex = first;
        }
    }

    static void removeHitComponent(MoveData move, int removedIndex) {
        if (move == null || move.hitComponents == null
            || removedIndex < 0 || removedIndex >= move.hitComponents.size()) return;
        move.hitComponents.remove(removedIndex);
        if (move.effects == null) return;
        move.effects.removeIf(effect -> effect != null
            && MoveEffectTrigger.ON_HIT.name().equalsIgnoreCase(effect.trigger)
            && effect.hitComponentIndex != null
            && effect.hitComponentIndex == removedIndex);
        move.effects.stream()
            .filter(java.util.Objects::nonNull)
            .filter(effect -> MoveEffectTrigger.ON_HIT.name().equalsIgnoreCase(effect.trigger))
            .filter(effect -> effect.hitComponentIndex != null
                && effect.hitComponentIndex > removedIndex)
            .forEach(effect -> effect.hitComponentIndex--);
    }

    static int combinedBasePower(MoveData move) {
        if (move.hitComponents == null) return move.basePower;
        long total = 0L;
        for (MoveData.HitComponentData component : move.hitComponents) {
            if (component != null) total += component.basePower;
        }
        return Math.toIntExact(total);
    }

    private static void synchronizeCombinedBasePower(MoveData move) {
        if (move.hitComponents != null) move.basePower = combinedBasePower(move);
    }

    private static void normalizeHitComponentDependencies(MoveData move) {
        if (move.hitComponents == null || move.hitComponents.isEmpty()) return;
        move.hitComponents.get(0).requiresPreviousConnection = false;
        for (int index = 1; index < move.hitComponents.size(); index++) {
            MoveData.HitComponentData component = move.hitComponents.get(index);
            if (component.requiresPreviousConnection) {
                component.delayTicks = Math.max(component.delayTicks,
                    move.hitComponents.get(index - 1).delayTicks);
            }
        }
    }

    private static void synchronizeParentDamageTags(MoveData move) {
        if (move.tags == null || move.hitComponents == null) return;
        move.tags = new ArrayList<>(move.tags);
        move.tags.removeIf(tag -> {
            try { return MoveTag.TYPE_TAGS.contains(MoveTag.valueOf(tag)); }
            catch (IllegalArgumentException ignored) { return false; }
        });
        LinkedHashSet<String> damageTags = new LinkedHashSet<>();
        for (MoveData.HitComponentData component : move.hitComponents) {
            if (component == null || component.tags == null) continue;
            component.tags = editableComponentTags(component.tags);
            damageTags.addAll(component.tags);
        }
        move.tags.addAll(damageTags);
        if (damageTags.contains(MoveTag.INNATE_TECHNIQUE.name())
            || damageTags.contains(MoveTag.NON_INNATE_TECHNIQUE.name())) {
            if (!move.tags.contains(MoveTag.CURSED_ENERGY.name())) {
                move.tags.add(MoveTag.CURSED_ENERGY.name());
            }
        }
    }

    /** Apply a move-level type selection to every authored hit component. */
    static void applyMoveDamageTagsToComponents(MoveData move, Set<MoveTag> selectedTags) {
        if (move == null || move.hitComponents == null) return;
        Set<MoveTag> damageTypes = typeTags(selectedTags);
        ArrayList<String> tags = COMPONENT_DAMAGE_TAGS.stream()
            .filter(damageTypes::contains)
            .map(MoveTag::name)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        tags = editableComponentTags(tags);
        if (tags.isEmpty()) return;
        for (MoveData.HitComponentData component : move.hitComponents) {
            if (component != null) component.tags = new ArrayList<>(tags);
        }
        synchronizeParentDamageTags(move);
    }

    private static Set<MoveTag> typeTags(Set<MoveTag> tags) {
        Set<MoveTag> result = EnumSet.noneOf(MoveTag.class);
        if (tags == null) return result;
        for (MoveTag tag : tags) {
            if (MoveTag.TYPE_TAGS.contains(tag)) result.add(tag);
        }
        return result;
    }

    private static Set<MoveTag> typeTagsFromNames(List<String> tags) {
        Set<MoveTag> result = EnumSet.noneOf(MoveTag.class);
        if (tags == null) return result;
        for (String tag : tags) {
            if (tag == null) continue;
            try {
                MoveTag parsed = MoveTag.valueOf(tag);
                if (MoveTag.TYPE_TAGS.contains(parsed)) result.add(parsed);
            } catch (IllegalArgumentException ignored) {
                // Invalid tags are reported by MoveData validation when the draft is saved.
            }
        }
        return result;
    }

    private static ArrayList<String> defaultComponentTags(MoveData move) {
        MoveCategory category = move.derivedCategory();
        ArrayList<String> tags = new ArrayList<>();
        for (MoveTag tag : COMPONENT_DAMAGE_TAGS) {
            if (category.getTags().contains(tag)) tags.add(tag.name());
        }
        if (tags.isEmpty()) tags.add(MoveTag.PHYSICAL.name());
        return tags;
    }

    private static ArrayList<String> editableComponentTags(List<String> tags) {
        LinkedHashSet<String> selected = COMPONENT_DAMAGE_TAGS.stream()
            .map(MoveTag::name)
            .filter(tag -> tags != null && tags.contains(tag))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        removeImpliedCursedEnergy(selected);
        return orderedComponentTags(selected);
    }

    private static ArrayList<String> orderedComponentTags(Set<String> selected) {
        return COMPONENT_DAMAGE_TAGS.stream()
            .map(MoveTag::name)
            .filter(selected::contains)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static void removeImpliedCursedEnergy(Set<String> tags) {
        if (tags.contains(MoveTag.INNATE_TECHNIQUE.name())
            || tags.contains(MoveTag.NON_INNATE_TECHNIQUE.name())) {
            tags.remove(MoveTag.CURSED_ENERGY.name());
        }
    }

    private static String hitCountLabel(int count) {
        return count + (count == 1 ? " hit" : " hits");
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

    /**
     * Dispatcher that renders the per-defense-type sub-options. Rebuilt whenever
     * the DEFENSE Type dropdown changes (via {@link #refreshConditionalFields}).
     */
    private Actor buildDefenseFields(MoveData d) {
        DefenseType dt;
        try { dt = DefenseType.valueOf(d.defenseType); }
        catch (Exception e) { dt = DefenseType.NONE; }

        switch (dt) {
            case BLOCK:  return buildBlockFields(d);
            case PARRY:  return buildParryFields(d);
            case DODGE:  return buildDodgeFields(d);
            case SHIELD:
                Table stub = new Table(skin);
                stub.defaults().left().pad(4);
                stub.add(formHint("(SHIELD is reserved — not yet implemented)")).row();
                return stub;
            default:
                Table none = new Table(skin);
                none.defaults().left().pad(4);
                none.add(formHint("(no defense — select BLOCK, PARRY, or DODGE)")).row();
                return none;
        }
    }

    /**
     * Shared timing + activation-cap rows, appended by every defence-type
     * builder: when the window opens (FIXED vs REACTION) and how many incoming
     * attacks the defence may contest inside that window (0 = unlimited).
     */
    private void addDefenseTimingAndUsesFields(Table t, MoveData d) {
        t.add(labelledRow("Timing", new EnumSelectBox<>(
            DefenseTiming.class, d.defenseTiming, false,
            s -> { d.defenseTiming = s; }, skin, uiProfile))).growX().row();
        t.add(formHint("REACTION arms at the fire tick and triggers on the next matching "
            + "incoming attack, opening its window then (once per placement).")).row();

        t.add(labelledIntField("Defense Uses (0 = unlimited)",
                d.defenseUses, 0, 99999,
                v -> { d.defenseUses = v; })).growX().row();
        t.add(formHint("Activations allowed while the window is active — it caps "
            + "contests, never the duration.")).row();
    }

    private Actor buildBlockFields(MoveData d) {
        Table t = new Table(skin);
        t.defaults().left().pad(4);

        // Block style: percentage vs flat reduction.
        t.add(labelledRow("Style", new EnumSelectBox<>(
            BlockStyle.class, d.blockStyle, false,
            s -> { d.blockStyle = s; refreshConditionalFields(d); }, skin, uiProfile)))
            .growX().row();

        if (d.isPercentageBlock()) {
            t.add(labelledIntField("Damage Reduction %", d.blockDamageReduction, 0, 100,
                    v -> { d.blockDamageReduction = v; d.blockFlatReduction = 0; })).growX().row();
        } else if (d.isFlatBlock()) {
            t.add(labelledIntField("Flat Reduction", d.blockFlatReduction, 0, 99999,
                    v -> { d.blockFlatReduction = v; d.blockDamageReduction = 100; })).growX().row();
        }

        // Duration (shared by all defence types): -1 = end of round, 0 = use AP.
        t.add(labelledIntField("Duration (−1 = EOR, 0 = use AP)",
                d.blockDuration, -1, 99999,
                v -> { d.blockDuration = v; })).growX().row();

        // Potency gates which attacks this block can stop.
        t.add(labelledIntField("Potency (1–5)", d.potency, 1, 5,
                v -> { d.potency = v; })).growX().row();

        // Affected tags — multi-toggle
        t.add(new Label("Affected Tags (blank = all)", skin)).padTop(4).row();
        t.add(buildBlockTagToggles(d)).growX().row();

        addDefenseTimingAndUsesFields(t, d);
        return t;
    }

    private Actor buildParryFields(MoveData d) {
        Table t = new Table(skin);
        t.defaults().left().pad(4);

        // A parry forces weaponRequired on; show it as a fixed-on indicator.
        t.add(labelledRow("Weapon Required", new Label("Yes (parries always require a weapon)",
            skin, "small"))).growX().row();

        // Duration (parry windows are typically short).
        t.add(labelledIntField("Duration (−1 = EOR, 0 = use AP)",
                d.blockDuration, -1, 99999,
                v -> { d.blockDuration = v; })).growX().row();

        // Potency gates which attacks this parry can stop.
        t.add(labelledIntField("Potency (1–5)", d.potency, 1, 5,
                v -> { d.potency = v; })).growX().row();

        t.add(new Label("Affected Tags (blank = all)", skin)).padTop(4).row();
        t.add(buildBlockTagToggles(d)).growX().row();

        // Stagger ticks applied to the attacker on a successful non-GUARD_BREAK parry.
        t.add(labelledIntField("Stagger Ticks on Attacker (0 = none)",
                d.parryStaggerTicks, 0, 99999,
                v -> { d.parryStaggerTicks = v; })).growX().row();
        t.add(formHint("Applied only when the parried move lacks the GUARD_BREAK tag.")).row();

        addDefenseTimingAndUsesFields(t, d);
        return t;
    }

    private Actor buildDodgeFields(MoveData d) {
        Table t = new Table(skin);
        t.defaults().left().pad(4);

        // Dodge chance (0–100%). Chance-based, not potency-gated.
        t.add(labelledIntField("Dodge Chance %", d.dodgeChance, 0, 100,
                v -> { d.dodgeChance = v; })).growX().row();

        t.add(buildAccuracyPrioritySelector(
            d, AbilityEffectType.NEVER_HIT, "Never Hit Tier")).growX().row();
        t.add(formHint(
            "On a successful dodge roll, Never Hit stops only lower-tier Never Miss attacks."))
            .left().row();

        // Scope: which attack ranges this dodge reacts to.
        t.add(labelledRow("Scope", new EnumSelectBox<>(
            DodgeScope.class, d.dodgeScope, false,
            s -> { d.dodgeScope = s; }, skin, uiProfile))).growX().row();

        // Duration.
        t.add(labelledIntField("Duration (−1 = EOR, 0 = use AP)",
                d.blockDuration, -1, 99999,
                v -> { d.blockDuration = v; })).growX().row();
        t.add(formHint("Dodge is chance-based and ignores potency.")).row();

        addDefenseTimingAndUsesFields(t, d);
        return t;
    }

    /**
     * The DEFENSE card's resolution-effects section: effect rows that fire when
     * this defense resolves a hit. The trigger follows the selected defense
     * type — On block, On parry, or On dodge — so the section is rebuilt
     * whenever the DEFENSE Type dropdown changes (via
     * {@link #refreshConditionalFields}).
     */
    private Actor buildDefenseResolutionEffects(MoveData d) {
        MoveEffectTrigger trigger = defenseResolutionTrigger(d);
        if (trigger == null) {
            Table hint = new Table(skin);
            hint.defaults().left().pad(4);
            hint.add(formHint(
                "(no defense — select BLOCK, PARRY, or DODGE to author resolution effects)"))
                .row();
            return hint;
        }
        Table t = new Table(skin);
        t.defaults().left().pad(4);
        t.add(new Label(trigger.displayName()
            .toUpperCase(java.util.Locale.ROOT).replace(' ', '-') + " EFFECTS",
            skin, "small")).padTop(8f).left().row();
        t.add(buildMoveEffectsEditor(d, trigger, null)).growX().row();
        return t;
    }

    /** Resolution trigger for the authored defense type, or null when none. */
    private static MoveEffectTrigger defenseResolutionTrigger(MoveData d) {
        DefenseType dt;
        try { dt = DefenseType.valueOf(d.defenseType); }
        catch (Exception e) { dt = DefenseType.NONE; }
        return switch (dt) {
            case BLOCK -> MoveEffectTrigger.ON_BLOCK;
            case PARRY -> MoveEffectTrigger.ON_PARRY;
            case DODGE -> MoveEffectTrigger.ON_DODGE;
            default -> null;
        };
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
                    game.audio().play(SoundCue.UI_TOGGLE);
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

        StatKey[] stats = StatKey.values();
        int columns = prerequisiteColumnsPerRow(uiProfile);
        for (int index = 0; index < stats.length; index++) {
            StatKey stat = stats[index];
            Table statColumn = new Table(skin);
            statColumn.defaults().center();

            Label label = new Label(stat.label, skin, "small");
            label.setAlignment(Align.center);
            label.setWrap(true);
            statColumn.add(label).width(windowsLayout ? 123f : 82f)
                .height(windowsLayout ? 72f : 48f).row();

            TextField valueField = new HoverTextField(
                String.valueOf(prerequisiteValue(d, stat)), skin);
            valueField.setTextFieldFilter((TextField tf, char c) -> Character.isDigit(c));
            wirePrerequisiteField(valueField, d, stat);
            statColumn.add(valueField).width(windowsLayout ? 96f : 64f);

            statRow.add(statColumn);
            if ((index + 1) % columns == 0 && index + 1 < stats.length) statRow.row();
        }

        t.add(statRow).center().expandX().row();
        t.add(formHint("Set each stat's minimum value (0 means no threshold)."))
            .padTop(6f).center().row();
        return t;
    }

    static int prerequisiteColumnsPerRow(UiProfile uiProfile) {
        return uiProfile == UiProfile.WINDOWS ? 2 : Integer.MAX_VALUE;
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

    /** Ability-style editor over one trigger slice of the canonical move effect list. */
    private Actor buildMoveEffectsEditor(
        MoveData move,
        MoveEffectTrigger trigger,
        Integer hitComponentIndex
    ) {
        if (move.effects == null) move.effects = new ArrayList<>();
        AbilityData.ensureEffectIds(move.effects);
        List<AbilityEffectData> context = new MoveEffectContextList(
            move.effects, trigger, hitComponentIndex);

        Table editor = new Table(skin);
        editor.defaults().left().pad(3f).growX();
        editor.add(new EffectListEditor(
            context,
            repo.getAll(),
            List.of(),
            techniqueRepo.getAll(),
            charRepo.getAll(),
            this::markDirty,
            this::rebuildDetail,
            game.audio()::play,
            masteryEligible(move),
            false,
            moveEffectTypes(trigger),
            true,
            uiProfile,
            skin)).growX().row();
        if (!context.isEmpty() && trigger != MoveEffectTrigger.AVAILABILITY) {
            editor.add(formHint(
                trigger.displayName() + " is the mandatory first condition. "
                    + "Each row may add another condition and its own chance roll."))
                .padTop(5f).growX().row();
            editor.add(buildMoveEffectConditions(move, trigger, hitComponentIndex))
                .growX().row();
        }
        return editor;
    }

    private Actor buildMoveEffectConditions(
        MoveData move,
        MoveEffectTrigger trigger,
        Integer hitComponentIndex
    ) {
        Table list = new Table(skin);
        list.defaults().left().pad(3f).growX();
        List<MoveEffectData> effects = move.effects.stream()
            .filter(effect -> matchesContext(effect, trigger, hitComponentIndex))
            .toList();
        for (int index = 0; index < effects.size(); index++) {
            MoveEffectData effect = effects.get(index);
            if (effect.condition == null) effect.condition = AbilityConditionData.always();
            Table card = new Table(skin);
            card.setBackground(skin.getDrawable("battle-card"));
            card.defaults().left().pad(4f).growX();
            card.pad(7f);
            AbilityEffectType type;
            try { type = AbilityEffectType.fromName(effect.type); }
            catch (Exception exception) { type = AbilityEffectType.APPLY_STATUS; }
            card.add(new Label(
                "EFFECT " + (index + 1) + ": " + type.displayName(), skin, "small"))
                .growX().row();
            card.add(new Label("ADDITIONAL CONDITION", skin, "small"))
                .padTop(5f).row();
            card.add(new ConditionTreeEditor(
                effect.condition,
                repo.getAll(),
                this::markDirty,
                game.audio()::play,
                masteryEligible(move),
                uiProfile,
                skin)).growX().row();

            CheckBox chanceEnabled = new CheckBox(" Roll effect chance", skin);
            chanceEnabled.setChecked(Boolean.TRUE.equals(effect.activationChanceEnabled));
            TextField chance = new HoverTextField(formatPercent(
                effect.activationChance == null ? 1.0 : effect.activationChance), skin);
            chance.setTextFieldFilter((field, character) ->
                Character.isDigit(character) || character == '.');
            chance.setDisabled(!chanceEnabled.isChecked());
            chanceEnabled.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    effect.activationChanceEnabled = chanceEnabled.isChecked()
                        ? Boolean.TRUE : null;
                    if (effect.activationChance == null) effect.activationChance = 1.0;
                    if (!chanceEnabled.isChecked()) effect.activationMasteryProgression = null;
                    chance.setDisabled(!chanceEnabled.isChecked());
                    game.audio().play(SoundCue.UI_TOGGLE);
                    markDirty();
                }
            });
            chance.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    Double parsed = parseDecimal(chance.getText());
                    effect.activationChance = parsed == null ? null : parsed / 100.0;
                    markDirty();
                }
            });
            card.add(chanceEnabled).growX().row();
            card.add(labelledRow("Effect chance %", chance)).growX().row();
            if (masteryEligible(move) && chanceEnabled.isChecked()) {
                card.add(new MasteryProgressionEditor(
                    TechniqueMasteryProgressions.ACTIVATION_CHANCE,
                    () -> (int) Math.floor((effect.activationChance == null
                        ? 1.0 : effect.activationChance) * 100.0),
                    () -> effect.activationMasteryProgression,
                    value -> effect.activationMasteryProgression = value,
                    this::markDirty,
                    uiProfile,
                    skin)).growX().row();
            }
            list.add(card).growX().padTop(4f).row();
        }
        return list;
    }

    private static List<AbilityEffectType> moveEffectTypes(MoveEffectTrigger trigger) {
        if (trigger == MoveEffectTrigger.AVAILABILITY) {
            return java.util.Arrays.stream(AbilityEffectType.values())
                .filter(AbilityEffectType::isMoveAvailabilityConstraint)
                .toList();
        }
        List<AbilityEffectType> preferred = List.of(
            AbilityEffectType.TEMP_STAT_PERCENT,
            AbilityEffectType.BATTLE_STAT_PERCENT,
            AbilityEffectType.APPLY_STATUS,
            AbilityEffectType.INSTANT_KILL,
            AbilityEffectType.SUMMON_CHARACTER,
            AbilityEffectType.DESUMMON_TARGET_SHIKIGAMI,
            AbilityEffectType.CODED_MOVE_ACTION);
        List<AbilityEffectType> types = new ArrayList<>(preferred);
        java.util.Arrays.stream(AbilityEffectType.values())
            .filter(AbilityEffectType::isMoveEffect)
            .filter(type -> !type.isAccuracyPriority())
            .filter(type -> !type.isMoveAvailabilityConstraint())
            .filter(type -> !types.contains(type))
            .forEach(types::add);
        return List.copyOf(types);
    }

    private static boolean matchesContext(
        MoveEffectData effect,
        MoveEffectTrigger trigger,
        Integer hitComponentIndex
    ) {
        if (effect == null || trigger == null
            || !trigger.name().equalsIgnoreCase(effect.trigger)) return false;
        return trigger != MoveEffectTrigger.ON_HIT
            || java.util.Objects.equals(hitComponentIndex, effect.hitComponentIndex);
    }

    private static Double parseDecimal(String value) {
        if (value == null || value.isBlank() || ".".equals(value)) return null;
        try { return Double.valueOf(value); }
        catch (NumberFormatException exception) { return null; }
    }

    private static String formatPercent(double fraction) {
        double percentage = fraction * 100.0;
        return percentage == Math.rint(percentage)
            ? String.valueOf((long) percentage) : String.valueOf(percentage);
    }

    /** Mutable trigger-filtered view consumed by the shared ability effect editor. */
    private static final class MoveEffectContextList
        extends java.util.AbstractList<AbilityEffectData> {

        private final List<MoveEffectData> all;
        private final MoveEffectTrigger trigger;
        private final Integer hitComponentIndex;

        private MoveEffectContextList(
            List<MoveEffectData> all,
            MoveEffectTrigger trigger,
            Integer hitComponentIndex
        ) {
            this.all = all;
            this.trigger = trigger;
            this.hitComponentIndex = hitComponentIndex;
        }

        @Override public AbilityEffectData get(int index) {
            return all.get(actualIndex(index));
        }

        @Override public int size() {
            int count = 0;
            for (MoveEffectData effect : all) {
                if (matchesContext(effect, trigger, hitComponentIndex)) count++;
            }
            return count;
        }

        @Override public AbilityEffectData set(int index, AbilityEffectData element) {
            int actual = actualIndex(index);
            MoveEffectData previous = all.get(actual);
            MoveEffectData updated = previous.copy();
            updated.copyFrom(element);
            updated.trigger = trigger.name();
            updated.hitComponentIndex = trigger == MoveEffectTrigger.ON_HIT
                ? hitComponentIndex : null;
            all.set(actual, updated);
            return previous;
        }

        @Override public void add(int index, AbilityEffectData element) {
            MoveEffectData added = new MoveEffectData();
            added.copyFrom(element);
            added.trigger = trigger.name();
            added.hitComponentIndex = trigger == MoveEffectTrigger.ON_HIT
                ? hitComponentIndex : null;
            added.condition = AbilityConditionData.always();
            int actual = index == size() ? all.size() : actualIndex(index);
            all.add(actual, added);
            AbilityData.ensureEffectIds(all);
        }

        @Override public AbilityEffectData remove(int index) {
            return all.remove(actualIndex(index));
        }

        private int actualIndex(int contextIndex) {
            int current = 0;
            for (int index = 0; index < all.size(); index++) {
                if (!matchesContext(all.get(index), trigger, hitComponentIndex)) continue;
                if (current++ == contextIndex) return index;
            }
            throw new IndexOutOfBoundsException(contextIndex);
        }
    }

    /**
     * Build a simple list editor for status effects.
     * @param which one of "onHit", "self", "onBlock", "onParry", "onDodge"
     */
    private Actor buildEffectsEditor(String which, MoveData d) {
        List<MoveData.StatusEffectData> raw;
        switch (which) {
            case "self":    raw = d.selfEffects;    break;
            case "onBlock": raw = d.onBlockEffects;  break;
            case "onParry": raw = d.onParryEffects;  break;
            case "onDodge": raw = d.onDodgeEffects;  break;
            default:        raw = d.selfEffects;     break;
        }
        if (raw == null) {
            raw = new ArrayList<>();
            switch (which) {
                case "self":    d.selfEffects = raw;    break;
                case "onBlock": d.onBlockEffects = raw; break;
                case "onParry": d.onParryEffects = raw; break;
                case "onDodge": d.onDodgeEffects = raw; break;
                default:        d.selfEffects = raw;    break;
            }
        }
        return buildEffectsEditor(raw, masteryEligible(d));
    }

    /**
     * Render an editable list of status effects. Used by the move-level
     * self/block/parry/dodge editors and the per-hit-component on-hit editor.
     */
    private Actor buildEffectsEditor(
        List<MoveData.StatusEffectData> raw,
        boolean masteryEligible
    ) {
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
                // A coded row carries a hardcoded ability action (e.g. MIRACLES/CREATE)
                // instead of a status; render it with a distinct label so it is not
                // confused with a normal status effect. A summon row carries a
                // shikigami id instead.
                String rowLabel;
                if (eff.isSummon()) {
                    rowLabel = "SUMMON: " + (eff.summonCharacterId == null ? "?" : eff.summonCharacterId);
                } else if (eff.isCoded()) {
                    rowLabel = "CODED: " + eff.codedAbilityKey + "/" + eff.codedAction
                        + (eff.codedTarget == null ? "" : " -> " + eff.codedTarget)
                        + (eff.codedStackCount == null ? "" : " x" + eff.codedStackCount);
                } else {
                    rowLabel = statusEffectSummary(eff);
                }
                Label lbl = new Label(rowLabel, skin, "small");
                t.add(lbl).left().growX();
                TextButton editBtn = new TextButton("Edit", skin);
                editBtn.addListener(new ChangeListener() {
                    @Override public void changed(ChangeEvent event, Actor actor) {
                        game.audio().play(SoundCue.UI_CONFIRM);
                        showEffectEditor(eff, masteryEligible, updated -> list.set(idx, updated));
                    }
                });
                TextButton rmBtn = new TextButton("X", skin);
                rmBtn.addListener(new ChangeListener() {
                    @Override public void changed(ChangeEvent event, Actor actor) {
                        game.audio().play(SoundCue.UI_DELETE);
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
                game.audio().play(SoundCue.UI_CONFIRM);
                MoveData.StatusEffectData eff = new MoveData.StatusEffectData();
                eff.type = StatusEffectType.STRENGTH_INCREASE.name();
                eff.durationRounds = 1;
                eff.durationTicks = 0;
                eff.magnitude = 10.0;
                showEffectEditor(eff, masteryEligible, list::add);
            }
        });
        t.add(addBtn).colspan(5).padTop(4).row();
        return t;
    }

    /** Modal editor for a single StatusEffectData row. */
    private void showEffectEditor(
        MoveData.StatusEffectData source,
        boolean masteryEligible,
        Consumer<MoveData.StatusEffectData> commit
    ) {
        MoveData.StatusEffectData eff = copyEffect(source);
        ContentSizedDialog dlg = new ContentSizedDialog("Edit Effect", skin, uiProfile) {
                @Override
                protected void result(Object object) {
                    if (Boolean.TRUE.equals(object)) {
                        game.audio().play(SoundCue.UI_CONFIRM);
                        commit.accept(eff);
                        markDirty();
                        rebuildDetail();
                    } else {
                        game.audio().play(SoundCue.UI_BACK);
                    }
                }
            };

        Table content = new Table(skin);
        content.defaults().pad(4).left();

        // An effect row is EITHER a status effect (has a Type) OR a coded action
        // (key/action dispatched to a compiled runtime). The choice is a toggle
        // sitting directly to the right of the "Type" label, with the dropdown to
        // the right of the toggle. Flipping the toggle swaps the SAME dropdown's
        // contents (status types ↔ coded actions); the status customisation fields
        // (rounds/ticks/amount) disappear in coded mode, and any coded-action-
        // specific options appear below in their place.
        final List<StatusEffectType> statusTypes = editableStatusTypes();
        final List<String> statusLabels = new ArrayList<>(statusTypes.stream()
            .map(MoveEditorScreen::statusLabel).toList());

        // Allow-listed coded bindings come from the registry; future technique
        // moves extend it and appear here automatically. Each entry is {key, action, label}.
        final List<CodedAbilityRegistry.EffectAction> codedOptions =
            CodedAbilityRegistry.effectActions();
        final List<String> codedLabels = new ArrayList<>(codedOptions.stream()
            .map(CodedAbilityRegistry.EffectAction::label).toList());

        // --- The Type row: "Type" label  [Status/Coded toggle]  [Summon]  [dropdown] ---
        final SelectBox<String> typeBox = new DynamicSelectBox<>(skin, uiProfile);
        final TextButton toggleBtn = new TextButton("Coded", skin);
        final TextButton summonBtn = new TextButton("Summon", skin);
        // Holders so the listeners below can reference the mode swap before it's assigned.
        final Runnable[] applyMode = new Runnable[1];
        // Guard against the listener firing while we programmatically swap items.
        final boolean[] syncing = {false};

        toggleBtn.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                game.audio().play(SoundCue.UI_TOGGLE);
                boolean nowCoded = !eff.isCoded();
                clearSummon(eff);
                eff.masteryProgression = null;
                if (nowCoded) {
                    if (eff.codedAbilityKey == null || eff.codedAbilityKey.isBlank()) {
                        eff.codedAbilityKey = codedOptions.get(0).key();
                        eff.codedAction     = codedOptions.get(0).action();
                    }
                    normalizeCodedSettings(eff);
                    eff.type = null; // a coded row carries no status type
                } else {
                    clearCoded(eff);
                    eff.masteryProgression = null;
                    if (eff.type == null) eff.type = StatusEffectType.STRENGTH_INCREASE.name();
                }
                applyMode[0].run();
            }
        });

        summonBtn.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                game.audio().play(SoundCue.UI_TOGGLE);
                // Switching to summon mode: clear status + coded state, seed a
                // shikigami id if none is set.
                clearCoded(eff);
                eff.type = null;
                eff.masteryProgression = null;
                if (eff.summonCharacterId == null || eff.summonCharacterId.isBlank()) {
                    java.util.List<String> opts = shikigamiOptions();
                    eff.summonCharacterId = opts.isEmpty() ? null : shikigamiIdFromLabel(opts.get(0), opts);
                }
                applyMode[0].run();
            }
        });

        typeBox.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                if (syncing[0]) return;
                String sel = typeBox.getSelected();
                if (eff.isSummon()) {
                    java.util.List<String> opts = shikigamiOptions();
                    eff.summonCharacterId = shikigamiIdFromLabel(sel, opts);
                    applyMode[0].run();
                } else if (eff.isCoded()) {
                    int idx = codedLabels.indexOf(sel);
                    if (idx >= 0) {
                        eff.codedAbilityKey = codedOptions.get(idx).key();
                        eff.codedAction     = codedOptions.get(idx).action();
                        normalizeCodedSettings(eff);
                        eff.masteryProgression = null;
                    }
                    applyMode[0].run(); // refresh coded-action options below
                } else {
                    StatusEffectType matched = statusTypes.stream()
                        .filter(status -> statusLabel(status).equals(sel))
                        .findFirst().orElse(StatusEffectType.STRENGTH_INCREASE);
                    eff.type = matched.name();
                    if (matched.requiresTickDuration()) {
                        eff.durationRounds = 0;
                        if (eff.durationTicks <= 0) eff.durationTicks = 1;
                        eff.magnitude = 0.0;
                    } else if (matched.requiresRoundDuration()) {
                        if (eff.durationRounds == 0 || eff.durationRounds < -1) {
                            eff.durationRounds = 1;
                        }
                        eff.durationTicks = 0;
                    }
                    applyMode[0].run();
                }
            }
        });

        Table typeRow = new Table(skin);
        typeRow.defaults().pad(4).left();
        typeRow.add(new Label("Type", skin)).padRight(8);
        typeRow.add(toggleBtn).padRight(8);
        typeRow.add(summonBtn).padRight(8);
        typeRow.add(typeBox).growX();
        content.add(typeRow).growX().row();

        // Swappable containers: only one block shows at a time, depending on mode.
        final Container<Actor> customRow = new Container<>();
        content.add(customRow).growX().row();

        // --- applyMode: rebuild the dropdown items + toggle label for the current
        // mode, and show the matching customisation block. ---
        applyMode[0] = () -> {
            syncing[0] = true;
            try {
                if (eff.isSummon()) {
                    // Summon mode: the dropdown lists shikigami definitions; the
                    // toggle button offers to switch back to Status, and a hint
                    // explains the row.
                    toggleBtn.setText("Status");
                    java.util.List<String> opts = shikigamiOptions();
                    typeBox.setItems(opts.toArray(new String[0]));
                    String current = summonLabelFor(eff.summonCharacterId, opts);
                    if (!opts.contains(current) && !opts.isEmpty()) current = opts.get(0);
                    typeBox.setSelected(current);
                    if (!opts.isEmpty()) {
                        eff.summonCharacterId = shikigamiIdFromLabel(current, opts);
                    }
                    customRow.setActor(buildSummonEffectFields(eff));
                } else if (eff.isCoded()) {
                    toggleBtn.setText("Status");
                    typeBox.setItems(codedLabels.toArray(new String[0]));
                    int idx = -1;
                    for (int i = 0; i < codedOptions.size(); i++) {
                        if (codedOptions.get(i).key().equalsIgnoreCase(eff.codedAbilityKey)
                            && codedOptions.get(i).action().equalsIgnoreCase(eff.codedAction)) {
                            idx = i; break;
                        }
                    }
                    typeBox.setSelected(idx >= 0 ? codedLabels.get(idx) : codedLabels.get(0));
                    normalizeCodedSettings(eff);
                    customRow.setActor(buildCodedEffectFields(
                        eff, applyMode[0], masteryEligible));
                } else {
                    toggleBtn.setText("Coded");
                    typeBox.setItems(statusLabels.toArray(new String[0]));
                    typeBox.setSelected(statusLabel(eff.type, eff.magnitude));
                    customRow.setActor(buildStatusEffectFields(eff, masteryEligible));
                }
            } finally {
                syncing[0] = false;
            }
        };
        applyMode[0].run();

        ScrollPane scroll = new AxisLockedScrollPane(content, skin);
        scroll.setScrollingDisabled(true, false);
        scroll.setFadeScrollBars(false);
        scroll.setOverscroll(false, false);
        scroll.setForceScroll(false, false);
        float viewportHeight = stage == null ? 720f : stage.getHeight();
        float maxHeight = windowsLayout
            ? Math.max(270f, Math.min(840f, viewportHeight - 270f))
            : Math.max(180f, Math.min(560f, viewportHeight - 180f));
        dlg.getContentTable().add(scroll).minHeight(windowsLayout ? 210f : 140f)
            .maxHeight(maxHeight).growX().row();
        dlg.button("Done", true);
        dlg.button("Cancel", false);
        dlg.show(stage);
    }

    private Actor buildStatusEffectFields(
        MoveData.StatusEffectData effect,
        boolean masteryEligible
    ) {
        Table fields = new Table(skin);
        fields.defaults().pad(4).left();
        StatusEffectType type;
        try {
            type = StatusEffectType.fromName(effect.type, effect.magnitude);
        } catch (IllegalArgumentException ignored) {
            type = StatusEffectType.STRENGTH_INCREASE;
        }

        if (type.requiresTickDuration()) {
            effect.durationRounds = 0;
            if (effect.durationTicks <= 0) effect.durationTicks = 1;
            effect.magnitude = 0.0;
            TextField ticksField = new HoverTextField(String.valueOf(effect.durationTicks), skin);
            ticksField.setTextFieldFilter((tf, c) -> Character.isDigit(c));
            ticksField.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    try { effect.durationTicks = Integer.parseInt(ticksField.getText()); }
                    catch (NumberFormatException ignored) { }
                }
            });
            fields.add(new Label("Stagger duration (AP ticks)", skin)).padRight(8);
            fields.add(ticksField).growX().row();
            addMoveProgression(fields, effect, TechniqueMasteryProgressions.DURATION_TICKS,
                () -> effect.durationTicks, masteryEligible);
            return fields;
        }

        if (type.requiresRoundDuration()) {
            effect.durationTicks = 0;
            if (effect.durationRounds == 0 || effect.durationRounds < -1) {
                effect.durationRounds = 1;
            }
        }

        TextField roundsField = new HoverTextField(String.valueOf(effect.durationRounds), skin);
        roundsField.setTextFieldFilter((tf, c) -> Character.isDigit(c) || c == '-');
        roundsField.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                try { effect.durationRounds = Integer.parseInt(roundsField.getText()); }
                catch (NumberFormatException ignored) { }
            }
        });
        fields.add(new Label("Duration rounds (-1 = permanent)", skin)).padRight(8);
        fields.add(roundsField).growX().row();
        addMoveProgression(fields, effect, TechniqueMasteryProgressions.DURATION_ROUNDS,
            () -> effect.durationRounds, masteryEligible);

        if (!type.requiresRoundDuration()) {
            TextField ticksField = new HoverTextField(String.valueOf(effect.durationTicks), skin);
            ticksField.setTextFieldFilter((tf, c) -> Character.isDigit(c));
            ticksField.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    try { effect.durationTicks = Integer.parseInt(ticksField.getText()); }
                    catch (NumberFormatException ignored) { }
                }
            });
            fields.add(new Label("Duration ticks", skin)).padRight(8);
            fields.add(ticksField).growX().row();
            addMoveProgression(fields, effect, TechniqueMasteryProgressions.DURATION_TICKS,
                () -> effect.durationTicks, masteryEligible);
        }

        TextField magnitudeField = new HoverTextField(String.valueOf(effect.magnitude), skin);
        magnitudeField.setTextFieldFilter((tf, c) -> Character.isDigit(c) || c == '-' || c == '.');
        magnitudeField.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                try { effect.magnitude = Double.parseDouble(magnitudeField.getText()); }
                catch (NumberFormatException ignored) { }
            }
        });
        fields.add(new Label(type.requiresRoundDuration()
            ? "Damage per round" : "Amount (+/- flat points)", skin)).padRight(8);
        fields.add(magnitudeField).growX().row();
        if (type.usesMagnitude()) {
            addMoveProgression(fields, effect, TechniqueMasteryProgressions.MAGNITUDE,
                () -> (int) Math.floor(Math.abs(effect.magnitude)), masteryEligible);
        }
        return fields;
    }

    /**
     * Build the customisation block for a summon-flavour effect row: an
     * explanatory hint (the shikigami itself is chosen in the Type dropdown,
     * which is repopulated with shikigami options in summon mode).
     */
    private Actor buildSummonEffectFields(MoveData.StatusEffectData effect) {
        Table fields = new Table(skin);
        fields.defaults().pad(4).left();
        fields.add(formHint(
            "Summons the selected shikigami onto the wielder's team when this row fires. "
            + "Pick the shikigami in the Type dropdown above. Only SHIKIGAMI definitions "
            + "may be summoned."))
            .colspan(2).row();
        if (shikigamiOptions().isEmpty()) {
            fields.add(formHint("⚠ no shikigami definitions found — create one in the Character Editor."))
                .colspan(2).row();
        }
        return fields;
    }

    /** Clear the coded-action fields on an effect row (used when leaving coded mode). */
    private static void clearCoded(MoveData.StatusEffectData eff) {
        eff.codedAbilityKey = null;
        eff.codedAction     = null;
        eff.codedTarget     = null;
        eff.codedStackCount = null;
        eff.codedParameters = null;
    }

    /** Clear the summon field on an effect row (used when leaving summon mode). */
    private static void clearSummon(MoveData.StatusEffectData eff) {
        eff.summonCharacterId = null;
    }

    private Actor buildCodedEffectFields(
        MoveData.StatusEffectData effect,
        Runnable refresh,
        boolean masteryEligible
    ) {
        Table fields = new Table(skin);
        fields.defaults().pad(4).left();
        fields.add(formHint(
            "Coded action - dispatched to the matching compiled ability at runtime."))
            .colspan(2).row();

        if (NewShadowStyleAbility.KEY.equalsIgnoreCase(effect.codedAbilityKey)) {
            List<MoveData> candidates = repo.getAll().stream()
                .filter(MoveEditorScreen::isSimpleDomainReactionMove)
                .toList();
            if (candidates.isEmpty()) {
                fields.add(formHint("Create an attacking move before linking this reaction."))
                    .colspan(2).row();
                return fields;
            }
            SelectBox<String> moveBox = new DynamicSelectBox<>(skin, uiProfile);
            List<String> labels = candidates.stream().map(MoveEditorScreen::moveLabel).toList();
            moveBox.setItems(labels.toArray(new String[0]));
            String selected = candidates.stream()
                .filter(move -> move.id.equals(effect.codedTarget))
                .map(MoveEditorScreen::moveLabel)
                .findFirst().orElse(labels.get(0));
            moveBox.setSelected(selected);
            moveBox.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    effect.codedTarget = moveIdFromLabel(moveBox.getSelected());
                }
            });
            fields.add(new Label("Reaction move", skin)).padRight(8);
            fields.add(moveBox).growX().row();
            return fields;
        }

        if (!RatioAbility.KEY.equalsIgnoreCase(effect.codedAbilityKey)) {
            addCodedParameterFields(fields, effect, masteryEligible);
            return fields;
        }

        SelectBox<String> targetBox = new DynamicSelectBox<>(skin, uiProfile);
        String applyLabel = "Apply to this move";
        String createLabel = "Create Ratio stacks";
        targetBox.setItems(applyLabel, createLabel);
        targetBox.setSelected(RatioAbility.CREATE_STACKS.equalsIgnoreCase(effect.codedTarget)
            ? createLabel : applyLabel);
        targetBox.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                effect.codedTarget = createLabel.equals(targetBox.getSelected())
                    ? RatioAbility.CREATE_STACKS : RatioAbility.APPLY_TO_MOVE;
                effect.codedStackCount = RatioAbility.CREATE_STACKS.equals(effect.codedTarget)
                    ? effect.codedStackCount == null ? 1 : effect.codedStackCount
                    : null;
                effect.masteryProgression = null;
                refresh.run();
            }
        });
        fields.add(new Label("Targeting", skin)).padRight(8);
        fields.add(targetBox).growX().row();

        if (RatioAbility.CREATE_STACKS.equalsIgnoreCase(effect.codedTarget)) {
            TextField countField = new HoverTextField(
                String.valueOf(effect.codedStackCount == null ? 1 : effect.codedStackCount), skin);
            countField.setTextFieldFilter((tf, c) -> Character.isDigit(c));
            countField.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    try { effect.codedStackCount = Integer.parseInt(countField.getText()); }
                    catch (NumberFormatException ignored) { }
                }
            });
            fields.add(new Label("Stacks to create (1-99)", skin))
                .padRight(8);
            fields.add(countField).growX().row();
            addMoveProgression(fields, effect,
                TechniqueMasteryProgressions.CODED_STACK_COUNT,
                () -> effect.codedStackCount == null ? 1 : effect.codedStackCount,
                masteryEligible);
        }
        addCodedParameterFields(fields, effect, masteryEligible);
        return fields;
    }

    private void addCodedParameterFields(
        Table fields,
        MoveData.StatusEffectData effect,
        boolean masteryEligible
    ) {
        effect.codedParameters = CodedAbilityRegistry.prepareEffectParameters(
            effect.codedParameters, effect.codedAbilityKey,
            effect.codedAction, effect.codedTarget);
        for (CodedAbilityRegistry.CodedParameter parameter
            : CodedAbilityRegistry.effectParameters(
                effect.codedAbilityKey, effect.codedAction, effect.codedTarget)) {
            TextField value = new HoverTextField(
                String.valueOf(effect.codedParameters.get(parameter.key())), skin);
            value.setTextFieldFilter((tf, c) -> Character.isDigit(c) || c == '-');
            value.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
                    try {
                        effect.codedParameters.put(
                            parameter.key(), Integer.parseInt(value.getText()));
                    } catch (NumberFormatException ignored) { }
                }
            });
            fields.add(new Label(parameter.label(), skin)).padRight(8);
            fields.add(value).growX().row();
            addMoveProgression(fields, effect, parameter.key(),
                () -> effect.codedParameters.getOrDefault(
                    parameter.key(), parameter.defaultValue()), masteryEligible);
        }
    }

    private void normalizeCodedSettings(MoveData.StatusEffectData effect) {
        if (RatioAbility.KEY.equalsIgnoreCase(effect.codedAbilityKey)) {
            if (!RatioAbility.APPLY_TO_MOVE.equalsIgnoreCase(effect.codedTarget)
                && !RatioAbility.CREATE_STACKS.equalsIgnoreCase(effect.codedTarget)) {
                effect.codedTarget = RatioAbility.APPLY_TO_MOVE;
            }
            if (RatioAbility.CREATE_STACKS.equalsIgnoreCase(effect.codedTarget)) {
                if (effect.codedStackCount == null) effect.codedStackCount = 1;
            } else {
                effect.codedStackCount = null;
            }
        } else if (NewShadowStyleAbility.KEY.equalsIgnoreCase(effect.codedAbilityKey)) {
            effect.codedStackCount = null;
            boolean validTarget = effect.codedTarget != null && repo.findById(effect.codedTarget)
                .filter(MoveEditorScreen::isSimpleDomainReactionMove)
                .isPresent();
            if (!validTarget) {
                effect.codedTarget = repo.getAll().stream()
                    .filter(move -> "Batto Sword Drawing".equals(move.name))
                    .findFirst()
                    .or(() -> repo.getAll().stream()
                        .filter(MoveEditorScreen::isSimpleDomainReactionMove)
                        .findFirst())
                    .map(move -> move.id)
                    .orElse(null);
            }
        } else {
            effect.codedTarget = null;
            effect.codedStackCount = null;
        }
        effect.codedParameters = CodedAbilityRegistry.prepareEffectParameters(
            effect.codedParameters, effect.codedAbilityKey,
            effect.codedAction, effect.codedTarget);
    }

    private void addMoveProgression(
        Table fields,
        MoveData.StatusEffectData effect,
        String field,
        java.util.function.IntSupplier literal,
        boolean masteryEligible
    ) {
        if (!masteryEligible) return;
        fields.add(new MasteryProgressionEditor(
            field,
            literal,
            () -> effect.masteryProgression,
            value -> effect.masteryProgression = value,
            this::markDirty,
            uiProfile,
            skin)).colspan(2).growX().row();
    }

    private static boolean masteryEligible(MoveData move) {
        return move != null && move.tags != null && move.tags.stream()
            .anyMatch(tag -> MoveTag.INNATE_TECHNIQUE.name().equalsIgnoreCase(tag));
    }

    private static void clearMasteryProgression(MoveData move) {
        if (move == null) return;
        clearMasteryProgression(move.onHitEffects);
        clearMasteryProgression(move.selfEffects);
        clearMasteryProgression(move.onBlockEffects);
        clearMasteryProgression(move.onParryEffects);
        clearMasteryProgression(move.onDodgeEffects);
        if (move.hitComponents != null) {
            for (MoveData.HitComponentData component : move.hitComponents) {
                if (component != null) clearMasteryProgression(component.onHitEffects);
            }
        }
        if (move.effects != null) {
            move.effects.stream().filter(java.util.Objects::nonNull).forEach(effect -> {
                effect.masteryProgression = null;
                effect.activationMasteryProgression = null;
                clearMasteryProgression(effect.condition);
            });
        }
    }

    private static void clearMasteryProgression(AbilityConditionData condition) {
        if (condition == null) return;
        condition.masteryProgression = null;
        if (condition.children != null) {
            condition.children.forEach(MoveEditorScreen::clearMasteryProgression);
        }
    }

    private static void clearMasteryProgression(
        List<MoveData.StatusEffectData> effects
    ) {
        if (effects == null) return;
        effects.stream().filter(java.util.Objects::nonNull)
            .forEach(effect -> effect.masteryProgression = null);
    }

    private static String moveLabel(MoveData move) {
        return move.id + " - " + move.name;
    }

    private static String moveIdFromLabel(String label) {
        if (label == null) return null;
        int separator = label.indexOf(" - ");
        return separator < 0 ? label : label.substring(0, separator);
    }

    private static boolean isSimpleDomainReactionMove(MoveData move) {
        try {
            return NewShadowStyleAbility.isValidReactionMove(move.toMove());
        } catch (Exception ignored) {
            return false;
        }
    }

    // =========================================================================
    // Conditional refresh
    // =========================================================================

    /** Show or hide complete purpose cards after a tag change. */
    private void refreshCategorySections(MoveData d) {
        markDirty();
        if (categorySectionsContainer != null) {
            categorySectionsContainer.setActor(buildCategorySections(d));
            // buildCategorySections creates (but does not populate) the AOE and
            // other conditional containers — populate them now.
            refreshConditionalFields(d);
        }
    }

    /** Re-render fields whose contents depend on another value in their card. */
    private void refreshConditionalFields(MoveData d) {
        markDirty();
        if (defenseFieldsContainer != null) defenseFieldsContainer.setActor(buildDefenseFields(d));
        if (defenseTargetingContainer != null) {
            defenseTargetingContainer.setActor(buildDefenseTargetingFields(d));
        }
        if (defenseEffectsContainer != null) {
            defenseEffectsContainer.setActor(buildDefenseResolutionEffects(d));
        }
        if (ceMinMaxContainer  != null) ceMinMaxContainer.setActor(buildCeMinMax(d));
        if (powerFieldsContainer != null) powerFieldsContainer.setActor(buildPowerFields(d));
        if (aoeFieldsContainer != null) {
            // The AOE sub-section only exists for ATTACK + AOE-tagged moves.
            if (hasTag(d, MoveTag.AOE) && hasTag(d, MoveTag.ATTACK)) {
                aoeFieldsContainer.setActor(buildAoeFields(d));
            } else {
                aoeFieldsContainer.setActor(new Table());
            }
        }
    }

    /**
     * Build the defensive targeting sub-section (inside the DEFENSE card): whose
     * timeline the active-defense window is conferred to (Self / Single Ally /
     * Multiple Allies / All Allies Except Self / All Allies Including Self), and
     * for MULTIPLE_ALLIES a target-count field. Mirrors {@link #buildAoeFields}
     * for attacks.
     */
    private Actor buildDefenseTargetingFields(MoveData d) {
        Table t = new Table(skin);
        t.defaults().left().pad(4);
        t.add(new Label("DEFENSE TARGETING", skin, "small")).padTop(8f).left().row();

        DefenseTargeting current = DefenseTargeting.fromName(d.defenseTargeting);
        d.defenseTargeting = current.name();
        if (d.defenseTargetCount < 2) d.defenseTargetCount = 2;

        EnumSelectBox<DefenseTargeting> targetingSelect = new EnumSelectBox<>(
            DefenseTargeting.class, current.name(), false,
            name -> {
                d.defenseTargeting = name;
                game.audio().play(SoundCue.UI_NAVIGATE);
                refreshConditionalFields(d);
            }, skin, uiProfile);
        t.add(labelledRow("Targeting (" + current.displayName() + ")", targetingSelect)).growX().row();

        if (current == DefenseTargeting.MULTIPLE_ALLIES) {
            t.add(labelledIntField("Target Count", d.defenseTargetCount, 2, 99,
                    v -> { d.defenseTargetCount = v; })).growX().row();
        }
        return t;
    }

    /**
     * Build the AOE targeting sub-section (inside the ATTACK card): the shape
     * dropdown (Multiple Targets / All Enemies / All Others) and, for the
     * MULTIPLE shape, a target-count field.
     */
    private Actor buildAoeFields(MoveData d) {
        Table t = new Table(skin);
        t.defaults().left().pad(4);
        t.add(new Label("AREA OF EFFECT", skin, "small")).padTop(8f).left().row();

        AoeType current = AoeType.fromName(d.aoeType);
        if (current == null) {
            // Default ALL_ENEMIES for a freshly AOE-tagged move so the dropdown
            // always shows a concrete selection.
            current = AoeType.ALL_ENEMIES;
            d.aoeType = current.name();
        }
        if (d.aoeTargetCount < 2) d.aoeTargetCount = 2;

        EnumSelectBox<AoeType> aoeTypeSelect = new EnumSelectBox<>(
            AoeType.class, current.name(), false,
            name -> {
                d.aoeType = name;
                game.audio().play(SoundCue.UI_NAVIGATE);
                refreshConditionalFields(d);
            }, skin, uiProfile);
        t.add(labelledRow("AOE Type (" + current.displayName() + ")", aoeTypeSelect)).growX().row();

        if (current == AoeType.MULTIPLE) {
            t.add(labelledIntField("Target Count", d.aoeTargetCount, 2, 99,
                    v -> { d.aoeTargetCount = v; })).growX().row();
        }
        return t;
    }

    /** Force and lock the weapon requirement for moves whose semantics require a weapon. */
    private void synchronizeWeaponRequirement(MoveData d) {
        boolean locked = weaponRequirementIsLocked(d);
        if (locked) d.weaponRequired = true;
        if (weaponRequiredCheckbox != null) {
            weaponRequiredCheckbox.setDisabled(locked);
            weaponRequiredCheckbox.setChecked(d.weaponRequired);
        }
    }

    private static boolean weaponRequirementIsLocked(MoveData d) {
        return hasTag(d, MoveTag.SWORD) || DefenseType.PARRY.name().equals(d.defenseType);
    }

    private static boolean hasTag(MoveData d, MoveTag tag) {
        return d.tags != null && d.tags.contains(tag.name());
    }

    /**
     * The battle board this move plans on, mirroring
     * {@link com.jjktbf.model.combat.BattlePlan#boardFor}: defence wins over
     * attack; pure utility falls back to the defensive board.
     */
    static String derivedTimelineName(MoveData d) {
        if (hasTag(d, MoveTag.DEFENSIVE)) return "DEFENSIVE";
        return hasTag(d, MoveTag.ATTACK) ? "OFFENSIVE" : "DEFENSIVE";
    }

    private Actor buildAccuracyPrioritySelector(
        MoveData move,
        AbilityEffectType type,
        String label
    ) {
        SelectBox<String> tiers = new DynamicSelectBox<>(skin, uiProfile);
        tiers.setItems("None", "Tier 1", "Tier 2", "Tier 3", "Tier 4", "Tier 5");
        int current = type == AbilityEffectType.NEVER_MISS
            ? move.getNeverMissTier() : move.getNeverHitTier();
        tiers.setSelected(current <= 0 ? "None" : "Tier " + current);
        tiers.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                String selected = tiers.getSelected();
                int tier = "None".equals(selected)
                    ? 0 : Integer.parseInt(selected.substring("Tier ".length()));
                move.setAccuracyPriorityTier(type, tier);
                game.audio().play(SoundCue.UI_NAVIGATE);
                markDirty();
                rebuildDetail();
            }
        });
        return labelledRow(label, tiers);
    }

    static MoveData normalizedCopyForSave(MoveData draft) {
        MoveData copy = deepCopy(draft);
        if (copy.hitComponents != null && hasTag(copy, MoveTag.ATTACK)) {
            synchronizeParentDamageTags(copy);
        }
        discardInactiveCategoryDetails(copy);
        synchronizeCombinedBasePower(copy);
        if (weaponRequirementIsLocked(copy)) copy.weaponRequired = true;
        return copy;
    }

    private static void discardInactiveCategoryDetails(MoveData d) {
        // Hybrid attack-launch settings only exist on Defensive+Attack moves.
        if (!d.isDefenceAttackHybrid()) {
            d.attackLaunchMode = null;
            d.attackLaunchCondition = null;
            d.attackLaunchChanceEnabled = null;
            d.attackLaunchChance = null;
            d.attackLaunchMoveId = null;
        } else if (attackLaunchReferencesMove(d)) {
            // A referenced move replaces this move's own attack definition.
            d.basePower = 0;
            d.hitComponents = new ArrayList<>();
            d.onHitEffects = new ArrayList<>();
            removeAccuracyPriority(d, AbilityEffectType.NEVER_MISS);
            if (d.effects != null) {
                d.effects.removeIf(effect -> effect != null
                    && MoveEffectTrigger.ON_HIT.name().equalsIgnoreCase(effect.trigger));
            }
        }
        if (!hasTag(d, MoveTag.ATTACK)) {
            d.basePower = 0;
            d.hitComponents = new ArrayList<>();
            d.baseAccuracy = 1.0;
            d.neverMiss = false;
            removeAccuracyPriority(d, AbilityEffectType.NEVER_MISS);
            d.onHitEffects = new ArrayList<>();
            if (d.effects != null) {
                d.effects.removeIf(effect -> effect != null
                    && MoveEffectTrigger.ON_HIT.name().equalsIgnoreCase(effect.trigger));
            }
        }
        if (!hasTag(d, MoveTag.DEFENSIVE)) {
            removeAccuracyPriority(d, AbilityEffectType.NEVER_HIT);
            d.defenseType = DefenseType.NONE.name();
            d.blockStyle = BlockStyle.PERCENTAGE.name();
            d.blockDuration = 0;
            d.blockAffectedTags = null;
            d.blockDamageReduction = 100;
            d.blockFlatReduction = 0;
            d.dodgeChance = 0;
            d.dodgeScope = "BOTH";
            d.parryStaggerTicks = 0;
            d.defenseTargeting = DefenseTargeting.SELF.name();
            d.defenseTargetCount = 2;
            d.onBlockEffects = new ArrayList<>();
            d.onParryEffects = new ArrayList<>();
            d.onDodgeEffects = new ArrayList<>();
            if (d.effects != null) {
                d.effects.removeIf(effect -> effect != null
                    && (MoveEffectTrigger.ON_BLOCK.name().equalsIgnoreCase(effect.trigger)
                        || MoveEffectTrigger.ON_PARRY.name().equalsIgnoreCase(effect.trigger)
                        || MoveEffectTrigger.ON_DODGE.name().equalsIgnoreCase(effect.trigger)));
            }
        } else if (d.effects != null) {
            if (!DefenseType.DODGE.name().equals(d.defenseType)) {
                removeAccuracyPriority(d, AbilityEffectType.NEVER_HIT);
            }
            MoveEffectTrigger active = switch (String.valueOf(d.defenseType)) {
                case "BLOCK" -> MoveEffectTrigger.ON_BLOCK;
                case "PARRY" -> MoveEffectTrigger.ON_PARRY;
                case "DODGE" -> MoveEffectTrigger.ON_DODGE;
                default -> null;
            };
            d.effects.removeIf(effect -> effect != null
                && isDefenseTrigger(effect.trigger)
                && (active == null || !active.name().equalsIgnoreCase(effect.trigger)));
        }
    }

    private static boolean isDefenseTrigger(String trigger) {
        return MoveEffectTrigger.ON_BLOCK.name().equalsIgnoreCase(trigger)
            || MoveEffectTrigger.ON_PARRY.name().equalsIgnoreCase(trigger)
            || MoveEffectTrigger.ON_DODGE.name().equalsIgnoreCase(trigger);
    }

    private static void removeAccuracyPriority(MoveData move, AbilityEffectType type) {
        if (move.effects != null) {
            move.effects.removeIf(effect -> effect != null
                && type.name().equalsIgnoreCase(effect.type));
        }
        if (type == AbilityEffectType.NEVER_MISS) move.neverMiss = false;
    }

    // =========================================================================
    // Small UI helpers
    // =========================================================================

    /** True if a technique with the given name (case-insensitive) exists in the technique repo. */
    private boolean techniqueExists(String name) {
        return techniqueRepo.nameExists(name);
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

    // -------------------------------------------------------------------------
    // Shikigami-summon selector helpers
    // -------------------------------------------------------------------------

    /** Return canonical SHIKIGAMI definitions for the summon selector. */
    private java.util.List<String> shikigamiOptions() {
        java.util.List<String> options = new java.util.ArrayList<>();
        for (CharacterData c : charRepo.getAll()) {
            if (isShikigami(c)) {
                options.add(c.id + " - " + c.name);
            }
        }
        return options;
    }

    /** The dropdown label currently representing the move's summon target. */
    private static String summonLabelFor(String summonCharacterId, java.util.List<String> options) {
        if (summonCharacterId == null || summonCharacterId.isBlank()) return "[none]";
        for (String option : options) {
            if (option.startsWith(summonCharacterId + " - ")) return option;
        }
        return summonCharacterId + " - (missing or not a shikigami)";
    }

    /** Extract the shikigami id from a dropdown label (or null for [none]). */
    private static String shikigamiIdFromLabel(String label, java.util.List<String> options) {
        if (label == null || "[none]".equals(label)) return null;
        for (String option : options) {
            if (option.equals(label)) return option.substring(0, option.indexOf(' '));
        }
        return null;
    }

    /** A hybrid's referenced attack move must exist and cannot be the move itself. */
    static String attackLaunchReferenceValidationError(
        MoveData move,
        List<MoveData> moves
    ) {
        String referencedId = move == null ? null : move.attackLaunchMoveId;
        if (referencedId == null || referencedId.isBlank()) return null;
        if (referencedId.trim().equals(move.id)) {
            return "The attack cannot reference the move itself.";
        }
        boolean exists = moves != null && moves.stream()
            .filter(java.util.Objects::nonNull)
            .anyMatch(candidate -> referencedId.trim().equals(candidate.id));
        if (!exists) {
            return "Referenced attack move " + referencedId + " does not exist.";
        }
        return null;
    }

    static String summonReferenceValidationError(
        String characterId,
        List<CharacterData> characters
    ) {
        if (characterId == null || characterId.isBlank()) return null;
        CharacterData target = characters == null ? null : characters.stream()
            .filter(java.util.Objects::nonNull)
            .filter(character -> characterId.equals(character.id))
            .findFirst().orElse(null);
        if (target == null) {
            return "Summon target " + characterId + " does not exist.";
        }
        if (!isShikigami(target)) {
            return "Summon target \"" + target.name + "\" must be a Shikigami.";
        }
        return null;
    }

    private static boolean isShikigami(CharacterData character) {
        if (character == null) return false;
        try {
            return character.effectiveType() == CharacterType.SHIKIGAMI;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

}
