package com.jjktbf.graphics.screens.editors;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Align;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.audio.SoundCue;
import com.jjktbf.graphics.ui.DynamicSelectBox;
import com.jjktbf.graphics.ui.editor.AxisLockedScrollPane;
import com.jjktbf.graphics.ui.editor.AssignmentPanel;
import com.jjktbf.graphics.ui.editor.EditorScreenBase;
import com.jjktbf.graphics.ui.editor.MoveAssignmentPanel;
import com.jjktbf.graphics.ui.editor.StatField;
import com.jjktbf.graphics.ui.editor.SkillTreeCanvas;
import com.jjktbf.graphics.ui.editor.ValidationResult;
import com.jjktbf.model.character.AbilityApplicator;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.AbilityRepository;
import com.jjktbf.model.character.AbilityResolver;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterType;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.CombatStats;
import com.jjktbf.model.character.CharacterRepository;
import com.jjktbf.model.character.SlotBudgetEnforcer;
import com.jjktbf.model.character.StatKey;
import com.jjktbf.model.character.StatTier;
import com.jjktbf.model.combat.PowerCalculator;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MovePool;
import com.jjktbf.model.move.MoveRepository;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.technique.InnateTechniqueData;
import com.jjktbf.model.technique.SkillTreeNodeData;
import com.jjktbf.model.technique.TechniqueRepository;
import com.jjktbf.model.technique.TechniqueSkillTree;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Graphical CRUD editor for {@link CharacterData}. Master-detail layout with:
 *   - Name / Innate Technique fields
 *   - 10× {@link StatField} sliders (with Manual / Point-Buy mode toggle)
 *   - Live derived-stat preview (HP, AP bar, Accuracy, Evasion, CE pool, per-category slots)
 *   - Move assignment panel (slot-gated, technique/prerequisite-filtered, DnD)
 *   - Ability assignment panel filtered by source and grant availability
 *
 * Save validates via {@link CharacterData#toCharacter(MoveRepository, AbilityRepository)}.
 */
public class CharacterEditorScreen extends EditorScreenBase<CharacterData> {

    private static final StatKey[] STAT_ORDER = {
        StatKey.VITALITY, StatKey.STRENGTH, StatKey.DURABILITY, StatKey.SPEED,
        StatKey.COMBAT_ABILITY, StatKey.CURSED_ENERGY_RESERVES,
        StatKey.CURSED_ENERGY_EFFICIENCY, StatKey.CURSED_ENERGY_OUTPUT,
        StatKey.JUJUTSU_SKILL, StatKey.CURSED_TECHNIQUE_MASTERY
    };
    private static final List<String> TIER_SECTIONS = List.of(StatTier.values()).stream()
        .map(CharacterEditorScreen::tierSectionName)
        .toList();

    private static final int STAT_MIN = 10;
    private static final int STAT_MAX = 300;
    private static final int BASELINE = 80;
    private static final int POINT_BUDGET_WITH_TECHNIQUE    = 1000;
    private static final int POINT_BUDGET_WITHOUT_TECHNIQUE = 1080;
    private static final float STAT_KEY_REPEAT_DELAY = 0.25f;
    private static final float STAT_KEY_REPEAT_INTERVAL = 0.04f;

    private final CharacterRepository  charRepo;
    private final MoveRepository       moveRepo;
    private final AbilityRepository   abilityRepo;
    private final TechniqueRepository techniqueRepo;

    // Form handles (refreshed on selection change)
    private StatField[] statFields;
    private Label derivedPreview;
    private Container<Actor> moveAssignmentContainer;
    private Container<Actor> abilityAssignmentContainer;
    private Container<Actor> skillTreeContainer;
    private CheckBox pointBuyToggle;
    private Label baseStatTotalLabel;
    private Label baseStatTierLabel;
    private Label budgetLabel;
    /** Toggles the authoring-only {@code directlySelectable} flag; refreshed on type change. */
    private CheckBox selectableControl;
    private int lastEditedStatIndex = -1;
    private int heldStatKey = -1;
    private float statKeyRepeatTimer;

    public CharacterEditorScreen(JJKGame game, AssetLoader assets) {
        super(game, assets);
        charRepo    = new CharacterRepository("data/characters");
        moveRepo    = new MoveRepository("data/moves");
        abilityRepo = new AbilityRepository("data/abilities");
        techniqueRepo = new TechniqueRepository("data/techniques");
        wireStatKeyInput();
    }

    // =========================================================================
    // Abstract hooks
    // =========================================================================

    @Override protected String title() { return "CHARACTER EDITOR"; }

    @Override
    public void render(float delta) {
        repeatHeldStatKey(delta);
        super.render(delta);
    }

    private void wireStatKeyInput() {
        stage.addCaptureListener(new InputListener() {
            @Override public boolean keyDown(InputEvent event, int keycode) {
                if (keycode != Input.Keys.LEFT && keycode != Input.Keys.RIGHT) return false;
                if (keycode == heldStatKey) {
                    event.cancel();
                    return true;
                }
                int direction = keycode == Input.Keys.LEFT ? -1 : 1;
                if (!adjustLastEditedStat(direction, true)) return false;
                heldStatKey = keycode;
                statKeyRepeatTimer = STAT_KEY_REPEAT_DELAY;
                event.cancel();
                return true;
            }

            @Override public boolean keyUp(InputEvent event, int keycode) {
                if (keycode != heldStatKey) return false;
                heldStatKey = -1;
                statKeyRepeatTimer = 0f;
                event.cancel();
                return true;
            }
        });
    }

    @Override protected CharacterData newDraft() {
        CharacterData cd = new CharacterData();
        cd.name = "New Character";
        cd.description = "";
        cd.spriteAsset = null;
        cd.innateTechniqueName = null;
        cd.cursedTechniqueMastery = 0;
        cd.moveIds    = new ArrayList<>();
        cd.availableMoveIds = new ArrayList<>();
        cd.abilityIds = new ArrayList<>();
        cd.availableAbilityIds = new ArrayList<>();
        return cd;
    }

    @Override protected CharacterData draftFromRecord(CharacterData stored) {
        // Deep copy: CharacterData has no copy constructor, so we copy every field.
        CharacterData d = new CharacterData();
        d.id                  = stored.id;
        d.name                = stored.name;
        d.description         = stored.description;
        d.title               = stored.title;
        d.spriteAsset         = stored.spriteAsset;
        d.type                = stored.type;
        d.directlySelectable  = stored.directlySelectable;
        d.innateTechniqueName = stored.innateTechniqueName;
        d.hasWeapon           = stored.hasWeapon;
        for (StatKey sk : STAT_ORDER) sk.set(d, sk.get(stored));
        if (d.innateTechniqueName == null) d.cursedTechniqueMastery = 0;
        d.moveIds    = stored.moveIds    != null ? new ArrayList<>(stored.moveIds)    : new ArrayList<>();
        d.availableMoveIds = stored.availableMoveIds != null
            ? new ArrayList<>(stored.availableMoveIds) : new ArrayList<>(d.moveIds);
        d.abilityIds = stored.abilityIds != null ? new ArrayList<>(stored.abilityIds) : new ArrayList<>();
        d.availableAbilityIds = stored.availableAbilityIds != null
            ? new ArrayList<>(stored.availableAbilityIds) : null;
        return d;
    }

    @Override protected String idOf(CharacterData r) { return r.id; }

    @Override protected String nextId() { return charRepo.nextId(); }

    @Override protected void stampNewId(CharacterData draft) { draft.id = charRepo.nextId(); }

    @Override protected String listLabel(CharacterData r) {
        String tech = r.innateTechniqueName != null ? " [" + r.innateTechniqueName + "]" : "";
        return r.name + tech;
    }

    @Override protected List<String> recordSections() {
        return TIER_SECTIONS;
    }

    @Override protected String recordSection(CharacterData record) {
        return characterTierSection(record);
    }

    static String characterTierSection(CharacterData record) {
        int total = 0;
        for (StatKey stat : STAT_ORDER) total += stat.get(record);
        return tierSectionName(StatTier.forBaseStatTotal(total));
    }

    private static String tierSectionName(StatTier tier) {
        return tier.displayName().toUpperCase(Locale.ROOT);
    }

    @Override protected boolean isNewDraft(CharacterData draft) {
        return draft.id == null || draft.id.isEmpty()
            || charRepo.findById(draft.id).isEmpty();
    }

    @Override
    protected void reloadRecords() throws IOException {
        charRepo.load();
        moveRepo.load();
        abilityRepo.load();
        techniqueRepo.load();
        records.clear();
        records.addAll(charRepo.getAll());
    }

    @Override
    protected ValidationResult validateAndSave(CharacterData d) {
        if (d.name == null || d.name.trim().isEmpty()) {
            return ValidationResult.error("Name is required.");
        }
        if (d.moveIds != null) {
            String missingMove = d.moveIds.stream()
                .filter(moveId -> moveId == null || moveRepo.findById(moveId).isEmpty())
                .map(String::valueOf)
                .findFirst().orElse(null);
            if (missingMove != null) {
                return ValidationResult.error(
                    "Remove missing move reference " + missingMove + " before saving.");
            }
        }
        if (d.availableMoveIds != null) {
            String missingAvailableMove = d.availableMoveIds.stream()
                .filter(moveId -> moveId == null || moveRepo.findById(moveId).isEmpty())
                .map(String::valueOf)
                .findFirst().orElse(null);
            if (missingAvailableMove != null) {
                return ValidationResult.error(
                    "Remove missing available move reference " + missingAvailableMove
                        + " before saving.");
            }
        }
        if (d.abilityIds != null) {
            String missingAbility = d.abilityIds.stream()
                .filter(abilityId -> abilityId == null || abilityRepo.findById(abilityId).isEmpty())
                .map(String::valueOf)
                .findFirst().orElse(null);
            if (missingAbility != null) {
                return ValidationResult.error(
                    "Remove missing ability reference " + missingAbility + " before saving.");
            }
        }
        if (d.availableAbilityIds != null) {
            String missingAvailableAbility = d.availableAbilityIds.stream()
                .filter(abilityId -> abilityId == null || abilityRepo.findById(abilityId).isEmpty())
                .map(String::valueOf)
                .findFirst().orElse(null);
            if (missingAvailableAbility != null) {
                return ValidationResult.error(
                    "Remove missing available ability reference " + missingAvailableAbility
                        + " before saving.");
            }
        }
        String lockedTreeNode = firstActiveLockedTreeNode(d);
        if (lockedTreeNode != null) {
            return ValidationResult.error(
                "Deactivate locked technique-tree node \"" + lockedTreeNode + "\" before saving.");
        }
        java.util.Set<String> referencedMoveIds = new java.util.LinkedHashSet<>(
            d.moveIds == null ? List.of() : d.moveIds);
        for (String moveId : referencedMoveIds) {
            MoveData move = moveRepo.findById(moveId).orElse(null);
            if (move == null) continue;
            try {
                move.toMove();
            } catch (Exception ex) {
                return ValidationResult.error(
                    "Referenced move \"" + move.name + "\" is invalid: " + ex.getMessage());
            }
        }
        if (pointBuyToggle != null && pointBuyToggle.isChecked()) {
            int remaining = pointBudgetFor(d) - pointsSpent(d);
            if (remaining < 0) {
                return ValidationResult.error(
                    "Point-buy budget exceeded by " + -remaining + " points.");
            }
        }
        // New drafts need a non-blank id for the Entity constructor to validate.
        if (isNewDraft(d) && (d.id == null || d.id.isBlank())) {
            d.id = charRepo.nextId();
        }
        try {
            d.toCharacter(moveRepo, abilityRepo, techniqueRepo);
        } catch (Exception e) {
            return ValidationResult.error("Invalid character: " + e.getMessage());
        }
        try {
            if (isNewDraft(d)) {
                // Clear so the repo assigns the canonical next id.
                d.id = null;
                charRepo.add(d);
            } else {
                charRepo.update(d);
            }
            charRepo.save();
        } catch (Exception e) {
            return ValidationResult.error("Save failed: " + e.getMessage());
        }
        return ValidationResult.ok("Saved \"" + d.name + "\".");
    }

    @Override
    protected ValidationResult delete(String id) {
        try {
            CharacterData deleted = charRepo.findById(id).orElse(null);
            if (deleted == null) return ValidationResult.error("Character no longer exists.");

            MoveData dependentMove = firstMoveSummoningCharacter(moveRepo.getAll(), id);
            if (dependentMove != null) {
                return ValidationResult.error(
                    "Cannot delete: move \"" + dependentMove.name
                        + "\" summons this character.");
            }
            AbilityData dependentAbility = firstAbilitySummoningCharacter(
                abilityRepo.getAll(), id);
            if (dependentAbility != null) {
                return ValidationResult.error(
                    "Cannot delete: ability \"" + dependentAbility.name
                        + "\" summons this character.");
            }

            Map<String, String> remappedIds = new java.util.LinkedHashMap<>();
            int nextIndex = 0;
            for (CharacterData character : charRepo.getAll()) {
                if (id.equals(character.id)) continue;
                remappedIds.put(character.id,
                    com.jjktbf.model.repo.BaseRepository.formatId(nextIndex++));
            }

            boolean movesChanged = remapMoveSummonReferences(
                moveRepo.getAll(), remappedIds);
            boolean abilitiesChanged = remapAbilitySummonReferences(
                abilityRepo.getAll(), remappedIds);
            charRepo.delete(id);
            // Save the character index first. If a later dependent save fails,
            // stale old IDs fail closed as missing rather than selecting the
            // wrong character from the pre-delete index.
            charRepo.save();
            if (movesChanged) moveRepo.save();
            if (abilitiesChanged) abilityRepo.save();
            TechniqueTreeRepositorySync.synchronize();

            return ValidationResult.ok("Deleted.");
        } catch (Exception e) {
            return ValidationResult.error("Delete failed: " + e.getMessage());
        }
    }

    static MoveData firstMoveSummoningCharacter(List<MoveData> moves, String characterId) {
        if (moves == null || characterId == null) return null;
        return moves.stream()
            .filter(java.util.Objects::nonNull)
            .filter(move -> moveSummonsCharacter(move, characterId))
            .findFirst().orElse(null);
    }

    private static boolean moveSummonsCharacter(MoveData move, String characterId) {
        if (move == null || characterId == null) return false;
        if (characterId.equals(move.summonCharacterId)) return true;
        if (move.effects != null && move.effects.stream()
            .filter(java.util.Objects::nonNull)
            .anyMatch(effect -> characterId.equals(effect.characterId))) return true;
        java.util.List<MoveData.StatusEffectData> legacy = new java.util.ArrayList<>();
        if (move.selfEffects != null) legacy.addAll(move.selfEffects);
        if (move.onHitEffects != null) legacy.addAll(move.onHitEffects);
        if (move.onBlockEffects != null) legacy.addAll(move.onBlockEffects);
        if (move.onParryEffects != null) legacy.addAll(move.onParryEffects);
        if (move.onDodgeEffects != null) legacy.addAll(move.onDodgeEffects);
        if (move.hitComponents != null) {
            move.hitComponents.stream().filter(java.util.Objects::nonNull)
                .filter(component -> component.onHitEffects != null)
                .forEach(component -> legacy.addAll(component.onHitEffects));
        }
        return legacy.stream().filter(java.util.Objects::nonNull)
            .anyMatch(effect -> characterId.equals(effect.summonCharacterId));
    }

    static AbilityData firstAbilitySummoningCharacter(
        List<AbilityData> abilities,
        String characterId
    ) {
        if (abilities == null || characterId == null) return null;
        return abilities.stream()
            .filter(java.util.Objects::nonNull)
            .filter(ability -> ability.effects != null && ability.effects.stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(effect -> characterId.equals(effect.characterId)))
            .findFirst().orElse(null);
    }

    /** Remap {@code MoveData.summonCharacterId} after character resequencing. */
    static boolean remapMoveSummonReferences(
        List<MoveData> moves,
        Map<String, String> remappedIds
    ) {
        if (moves == null || remappedIds == null || remappedIds.isEmpty()) return false;
        boolean changed = false;
        for (MoveData move : moves) {
            if (move == null) continue;
            String remapped = remappedIds.get(move.summonCharacterId);
            if (remapped != null && !remapped.equals(move.summonCharacterId)) {
                move.summonCharacterId = remapped;
                changed = true;
            }
            if (move.effects != null) {
                for (com.jjktbf.model.move.MoveEffectData effect : move.effects) {
                    if (effect == null || effect.characterId == null) continue;
                    remapped = remappedIds.get(effect.characterId);
                    if (remapped != null && !remapped.equals(effect.characterId)) {
                        effect.characterId = remapped;
                        changed = true;
                    }
                }
            }
            for (MoveData.StatusEffectData effect : legacyMoveEffects(move)) {
                if (effect == null || effect.summonCharacterId == null) continue;
                remapped = remappedIds.get(effect.summonCharacterId);
                if (remapped != null && !remapped.equals(effect.summonCharacterId)) {
                    effect.summonCharacterId = remapped;
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static List<MoveData.StatusEffectData> legacyMoveEffects(MoveData move) {
        if (move == null) return List.of();
        List<MoveData.StatusEffectData> effects = new java.util.ArrayList<>();
        if (move.selfEffects != null) effects.addAll(move.selfEffects);
        if (move.onHitEffects != null) effects.addAll(move.onHitEffects);
        if (move.onBlockEffects != null) effects.addAll(move.onBlockEffects);
        if (move.onParryEffects != null) effects.addAll(move.onParryEffects);
        if (move.onDodgeEffects != null) effects.addAll(move.onDodgeEffects);
        if (move.hitComponents != null) {
            move.hitComponents.stream().filter(java.util.Objects::nonNull)
                .filter(component -> component.onHitEffects != null)
                .forEach(component -> effects.addAll(component.onHitEffects));
        }
        return effects;
    }

    /** Remap {@code AbilityEffectData.characterId} after character resequencing. */
    static boolean remapAbilitySummonReferences(
        List<AbilityData> abilities,
        Map<String, String> remappedIds
    ) {
        if (abilities == null || remappedIds == null || remappedIds.isEmpty()) return false;
        boolean changed = false;
        for (AbilityData ability : abilities) {
            if (ability == null || ability.effects == null) continue;
            for (AbilityEffectData effect : ability.effects) {
                if (effect == null || effect.characterId == null) continue;
                String remapped = remappedIds.get(effect.characterId);
                if (remapped != null && !remapped.equals(effect.characterId)) {
                    effect.characterId = remapped;
                    changed = true;
                }
            }
        }
        return changed;
    }

    // =========================================================================
    // Detail form
    // =========================================================================

    @Override
    protected Actor buildDetailForm(CharacterData cd) {
        lastEditedStatIndex = -1;
        heldStatKey = -1;
        statKeyRepeatTimer = 0f;
        Table form = formRoot();

        // ── Identity ───────────────────────────────────────────────────────────
        Table identity = formSection(form, "NAME");
        identity.add(idBadge(cd.id)).left().row();
        identity.add(labelledField("Name", cd.name,
                s -> { cd.name = s; })).growX().row();
        identity.add(labelledField("Description", cd.description,
                s -> { cd.description = s; })).growX().row();
        identity.add(labelledField("Title / epithet (display only, blank = none)", cd.title,
                s -> { cd.title = (s == null || s.isBlank()) ? null : s; }))
            .growX().row();
        identity.add(labelledField("Sprite Asset (assets/sprites/characters/...)", cd.spriteAsset,
                s -> { cd.spriteAsset = (s == null || s.isBlank()) ? null : s; }))
            .growX().row();
        SelectBox<String> techniqueSelect = techniqueSelect(cd.innateTechniqueName);
        techniqueSelect.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                String previousTechnique = cd.innateTechniqueName;
                clearTechniqueSelections(cd, previousTechnique);
                cd.innateTechniqueName = techniqueNameFromLabel(techniqueSelect.getSelected());
                refreshCtmLock();
                if (cd.innateTechniqueName == null && statFields != null) {
                    StatKey.CURSED_TECHNIQUE_MASTERY.set(cd, 0);
                    statFields[StatKey.CURSED_TECHNIQUE_MASTERY.ordinal()]
                        .setValueProgrammatic(0);
                }
                refreshAllocationBounds(cd, true);
                refreshBaseStatTotalLabel(cd);
                refreshDerivedPreview(cd);
                refreshBudgetLabel(cd);
                rebuildAbilityAssignment(cd);
                rebuildMoveAssignment(cd);
                rebuildSkillTree(cd);
                markDirty();
            }
        });
        identity.add(labelledRow("Innate Technique", techniqueSelect)).growX().row();

        // ── Type + direct selectability ────────────────────────────────────────
        // The character type governs which concrete subclass is built and the
        // default roster visibility. Shikigami default to hidden from the fighter
        // roster (they are summoned, not chosen) but can be explicitly exposed.
        SelectBox<String> typeSelect = new SelectBox<>(skin);
        typeSelect.setItems(Arrays.stream(CharacterType.values())
            .map(t -> CharacterEditorScreen.this.labelForType(t))
            .toList()
            .toArray(new String[0]));
        typeSelect.setSelected(labelForType(cd.effectiveType()));
        typeSelect.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                CharacterType chosen = typeFromLabel(typeSelect.getSelected());
                cd.type = (chosen == CharacterType.SORCERER) ? null : chosen.name();
                // Reset an explicit override so the new type's default applies.
                cd.directlySelectable = null;
                refreshSelectableControl(cd);
                markDirty();
            }
        });
        identity.add(labelledRow("Type", typeSelect)).growX().row();

        CheckBox selectableCheckbox = new CheckBox(
            " Directly selectable (appears in fighter roster)", skin);
        selectableCheckbox.setChecked(cd.effectiveSelectable());
        selectableCheckbox.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                cd.directlySelectable = selectableCheckbox.isChecked();
                markDirty();
            }
        });
        identity.add(selectableCheckbox).growX().colspan(2).padTop(4f).row();
        selectableControl = selectableCheckbox;
        refreshSelectableControl(cd);

        // ── Stats (mode toggle + sliders) ───────────────────────────────────────
        Table stats = formSection(form, "STATS");
        pointBuyToggle = new CheckBox(" Point-Buy mode", skin);
        pointBuyToggle.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                game.audio().play(SoundCue.UI_TOGGLE);
                if (pointBuyToggle.isChecked()) {
                    applyPointBuy(cd);
                }
                pruneLockedTechniqueSelections(cd);
                refreshDerivedPreview(cd);
                refreshBudgetLabel(cd);
                rebuildAbilityAssignment(cd);
                rebuildMoveAssignment(cd);
                rebuildSkillTree(cd);
                markDirty();
            }
        });
        baseStatTotalLabel = new Label("", skin);
        baseStatTierLabel = new Label("", skin);
        baseStatTierLabel.setFontScale(1.15f / AssetLoader.FONT_OVERSAMPLE);
        Table baseStatSummary = new Table(skin);
        baseStatSummary.add(baseStatTotalLabel).center().row();
        baseStatSummary.add(baseStatTierLabel).center().padTop(2f).row();
        Table baseStatSummaryOverlay = new Table(skin);
        baseStatSummaryOverlay.top().right();
        baseStatSummaryOverlay.add(baseStatSummary).right().padRight(10f);
        Stack statsHeader = new Stack();
        statsHeader.add(StatField.tierHeader(pointBuyToggle, skin));
        statsHeader.add(baseStatSummaryOverlay);
        stats.add(statsHeader).growX().colspan(2).row();
        refreshBaseStatTotalLabel(cd);

        // Budget label (point-buy only)
        budgetLabel = new Label("", skin, "small");
        budgetLabel.setColor(skin.get("text-dirty", com.badlogic.gdx.graphics.Color.class));
        stats.add(budgetLabel).left().row();

        statFields = new StatField[STAT_ORDER.length];
        boolean hasTechnique = cd.innateTechniqueName != null;
        Map<StatKey, Integer> allocationMinimums =
            resolvedAbilities(cd).statAllocationMinimums();
        Map<StatKey, Integer> allocationMaximums =
            resolvedAbilities(cd).statAllocationMaximums();
        for (int i = 0; i < STAT_ORDER.length; i++) {
            StatKey sk = STAT_ORDER[i];
            int statIndex = i;
            boolean locked = (sk == StatKey.CURSED_TECHNIQUE_MASTERY && !hasTechnique);
            int fieldMinimum = sk == StatKey.CURSED_TECHNIQUE_MASTERY ? 0 : STAT_MIN;
            int allocationMinimum = locked ? 0
                : allocationMinimums.getOrDefault(sk, fieldMinimum);
            int allocationMaximum = locked ? 0
                : allocationMaximums.getOrDefault(sk, STAT_MAX);
            int val = Math.max(allocationMinimum,
                Math.min(allocationMaximum, sk.get(cd)));
            sk.set(cd, val);
            StatField sf = new StatField(sk.label, val, fieldMinimum, STAT_MAX, v -> {
                lastEditedStatIndex = statIndex;
                sk.set(cd, v);
                pruneLockedTechniqueSelections(cd);
                refreshBaseStatTotalLabel(cd);
                refreshDerivedPreview(cd);
                if (pointBuyToggle.isChecked()) refreshBudgetLabel(cd);
                rebuildAbilityAssignment(cd);
                rebuildMoveAssignment(cd);
                rebuildSkillTree(cd);
                markDirty();
            }, () -> lastEditedStatIndex = statIndex, locked, skin);
            sf.setEffectiveMinimum(allocationMinimum);
            sf.setEffectiveMaximum(allocationMaximum);
            statFields[i] = sf;
            stats.add(sf).growX().colspan(2).row();
        }

        // ── Derived preview ──────────────────────────────────────────────────────
        Table derived = formSection(form, "DERIVED STATS (LIVE)");
        derivedPreview = new Label("", skin, "small");
        derivedPreview.setAlignment(Align.left);
        derived.add(derivedPreview).growX().row();
        refreshDerivedPreview(cd);

        // ── Move assignment ────────────────────────────────────────────────────
        Table movesSection = formSection(form, "MOVE ASSIGNMENT");
        CheckBox hasWeaponToggle = new CheckBox(" has a weapon", skin);
        hasWeaponToggle.setChecked(cd.hasWeapon);
        hasWeaponToggle.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                game.audio().play(SoundCue.UI_TOGGLE);
                cd.hasWeapon = hasWeaponToggle.isChecked();
                rebuildMoveAssignment(cd);
                markDirty();
            }
        });
        movesSection.add(hasWeaponToggle).left().row();
        moveAssignmentContainer = new Container<>();
        moveAssignmentContainer.fillX();
        movesSection.add(moveAssignmentContainer).growX().row();
        rebuildMoveAssignment(cd);

        // ── Ability assignment ──────────────────────────────────────────────────
        Table abilitiesSection = formSection(form, "ABILITY ASSIGNMENT");
        abilityAssignmentContainer = new Container<>();
        abilitiesSection.add(abilityAssignmentContainer).growX().row();
        rebuildAbilityAssignment(cd);

        // ── Technique tree ───────────────────────────────────────────────
        Table skillTreeSection = formSection(form, "TECHNIQUE TREE");
        skillTreeContainer = new Container<>();
        skillTreeSection.add(skillTreeContainer).growX().row();
        rebuildSkillTree(cd);

        return form;
    }

    private boolean adjustLastEditedStat(int direction, boolean commitFocusedText) {
        if (statFields == null || lastEditedStatIndex < 0
            || lastEditedStatIndex >= statFields.length) return false;
        StatField field = statFields[lastEditedStatIndex];
        if (commitFocusedText && field.isTextEditorFocused(stage.getKeyboardFocus())) {
            field.commitTextValue();
        }
        return field.adjustBy(direction);
    }

    private void repeatHeldStatKey(float delta) {
        if (heldStatKey == -1) return;
        statKeyRepeatTimer -= delta;
        while (statKeyRepeatTimer <= 0f) {
            int direction = heldStatKey == Input.Keys.LEFT ? -1 : 1;
            if (!adjustLastEditedStat(direction, false)) {
                heldStatKey = -1;
                return;
            }
            statKeyRepeatTimer += STAT_KEY_REPEAT_INTERVAL;
        }
    }

    // =========================================================================
    // Technique tree
    // =========================================================================

    private void rebuildSkillTree(CharacterData character) {
        if (skillTreeContainer == null) return;
        List<InnateTechniqueData> techniques = accessibleTechniques(character);
        if (techniques.isEmpty()) {
            Label empty = new Label("Choose an innate technique to view its technique tree.", skin, "small");
            empty.setColor(skin.get("text-dim", com.badlogic.gdx.graphics.Color.class));
            skillTreeContainer.setActor(empty);
            return;
        }

        Set<String> displayedNames = techniques.stream()
            .map(technique -> technique.name.toLowerCase())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Table trees = new Table(skin);
        trees.defaults().growX().left().padBottom(8f);
        for (InnateTechniqueData technique : techniques) {
            Label heading = new Label(technique.name, skin);
            heading.setColor(skin.get("text-dark", com.badlogic.gdx.graphics.Color.class));
            trees.add(heading).left().row();
            SkillTreeCanvas canvas = new SkillTreeCanvas(
                technique,
                moveRepo.getAll(),
                abilityRepo.getAll(),
                character,
                false,
                () -> onTreeSelectionChanged(character, displayedNames),
                message -> setStatus(message, false),
                node -> treeActivationError(character, node),
                game.audio()::play,
                skin);
            ScrollPane scroll = new AxisLockedScrollPane(canvas, skin);
            scroll.setFadeScrollBars(false);
            scroll.setFlickScroll(false);
            scroll.setOverscroll(false, false);
            scroll.setScrollingDisabled(false, false);
            trees.add(scroll).height(SkillTreeCanvas.VIEW_HEIGHT + 24f).growX().row();
        }
        skillTreeContainer.setActor(trees);
        skillTreeContainer.height(
            techniques.size() * (SkillTreeCanvas.VIEW_HEIGHT + 60f));
    }

    private void onTreeSelectionChanged(CharacterData character, Set<String> displayedNames) {
        Set<String> accessibleNames = resolvedAbilities(character).accessibleTechniqueNames().stream()
            .map(String::toLowerCase)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (String previousName : displayedNames) {
            if (!accessibleNames.contains(previousName)) {
                clearTechniqueSelections(character, previousName);
            }
        }
        markDirty();
        refreshDerivedPreview(character);
        refreshBudgetLabel(character);
        rebuildMoveAssignment(character);
        rebuildAbilityAssignment(character);
        if (!accessibleNames.equals(displayedNames)) {
            com.badlogic.gdx.Gdx.app.postRunnable(() -> rebuildSkillTree(character));
        }
    }

    private String treeActivationError(CharacterData character, SkillTreeNodeData node) {
        if (SkillTreeNodeData.MOVE.equalsIgnoreCase(node.contentType)) {
            MoveData move = moveRepo.findById(node.contentId).orElse(null);
            return move == null ? "This move no longer exists."
                : moveAssignmentError(character, resolvedAbilities(character), move, false);
        }
        AbilityData ability = abilityRepo.findById(node.contentId).orElse(null);
        return ability == null ? "This ability no longer exists." : null;
    }

    private InnateTechniqueData currentTechnique(CharacterData character) {
        return character == null ? null : techniqueForName(character.innateTechniqueName);
    }

    private List<InnateTechniqueData> accessibleTechniques(CharacterData character) {
        if (character == null) return List.of();
        return resolvedAbilities(character).accessibleTechniqueNames().stream()
            .map(this::techniqueForName)
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    private void pruneLockedTechniqueSelections(CharacterData character) {
        for (InnateTechniqueData technique : accessibleTechniques(character)) {
            TechniqueSkillTree.pruneLockedSelections(technique, character);
        }
    }

    private InnateTechniqueData techniqueForName(String name) {
        InnateTechniqueData stored = techniqueRepo.findByName(name).orElse(null);
        if (stored == null) return null;
        InnateTechniqueData copy = new InnateTechniqueData();
        copy.id = stored.id;
        copy.name = stored.name;
        copy.description = stored.description;
        copy.skillTree = new ArrayList<>();
        if (stored.skillTree != null) {
            stored.skillTree.stream()
                .filter(java.util.Objects::nonNull)
                .map(SkillTreeNodeData::copy)
                .forEach(copy.skillTree::add);
        }
        TechniqueSkillTree.synchronize(copy, moveRepo.getAll(), abilityRepo.getAll());
        return copy;
    }

    private void clearTechniqueSelections(CharacterData character, String techniqueName) {
        InnateTechniqueData technique = techniqueForName(techniqueName);
        if (technique == null || technique.skillTree == null) return;
        for (SkillTreeNodeData node : technique.skillTree) {
            TechniqueSkillTree.setActive(node, character, false);
        }
    }

    private SelectBox<String> techniqueSelect(String currentName) {
        final String none = "[none]";
        List<String> names = new ArrayList<>();
        names.add(none);
        techniqueRepo.getAll().stream()
            .map(technique -> technique.name)
            .filter(java.util.Objects::nonNull)
            .forEach(names::add);
        if (currentName != null && names.stream().noneMatch(currentName::equalsIgnoreCase)) {
            names.add(currentName);
        }
        SelectBox<String> select = new DynamicSelectBox<>(skin);
        select.setItems(names.toArray(new String[0]));
        select.setSelected(currentName == null ? none : names.stream()
            .filter(currentName::equalsIgnoreCase).findFirst().orElse(currentName));
        return select;
    }

    private static String techniqueNameFromLabel(String label) {
        return label == null || "[none]".equals(label) ? null : label;
    }

    private boolean isLockedTechniqueMove(CharacterData character, String moveId) {
        if (character == null || moveId == null) return false;
        MoveData move = moveRepo.findById(moveId).orElse(null);
        if (move == null || move.requiredTechniqueId == null
            || !resolvedAbilities(character).hasTechnique(move.requiredTechniqueId)) {
            return false;
        }
        InnateTechniqueData technique = techniqueForName(move.requiredTechniqueId);
        SkillTreeNodeData node = TechniqueSkillTree.nodeForContent(
            technique, SkillTreeNodeData.MOVE, moveId);
        return node != null && !TechniqueSkillTree.isActive(node, character);
    }

    private String firstActiveLockedTreeNode(CharacterData character) {
        for (InnateTechniqueData technique : accessibleTechniques(character)) {
            if (technique.skillTree == null) continue;
            String locked = technique.skillTree.stream()
                .filter(node -> TechniqueSkillTree.isActive(node, character))
                .filter(node -> !TechniqueSkillTree.isUnlocked(technique, node, character))
                .map(this::skillTreeNodeName)
                .findFirst().orElse(null);
            if (locked != null) return locked;
        }
        return null;
    }

    private String skillTreeNodeName(SkillTreeNodeData node) {
        if (SkillTreeNodeData.MOVE.equalsIgnoreCase(node.contentType)) {
            return moveRepo.findById(node.contentId).map(move -> move.name)
                .orElse("Missing move " + node.contentId);
        }
        return abilityRepo.findById(node.contentId).map(ability -> ability.name)
            .orElse("Missing ability " + node.contentId);
    }

    // =========================================================================
    // Stat modes
    // =========================================================================

    /** Apply point-buy mode: reset all stats to baseline, enforce budget. */
    private void applyPointBuy(CharacterData cd) {
        Map<StatKey, Integer> minimums = resolvedAbilities(cd).statAllocationMinimums();
        Map<StatKey, Integer> maximums = resolvedAbilities(cd).statAllocationMaximums();
        for (StatKey sk : STAT_ORDER) {
            int value = sk == StatKey.CURSED_TECHNIQUE_MASTERY
                && cd.innateTechniqueName == null ? 0
                : Math.max(BASELINE, minimums.getOrDefault(sk, STAT_MIN));
            value = Math.min(value, maximums.getOrDefault(sk, STAT_MAX));
            sk.set(cd, value);
            statFields[sk.ordinal()].setValueProgrammatic(value);
        }
        refreshCtmLock();
        refreshBaseStatTotalLabel(cd);
        refreshDerivedPreview(cd);
        refreshBudgetLabel(cd);
    }

    /**
     * Sync the selectable checkbox to the current draft's effective selectability
     * and reflect the type-default as the checkbox's disabled hint. Shikigami
     * default to hidden; an author can still expose one explicitly.
     */
    private void refreshSelectableControl(CharacterData cd) {
        if (selectableControl == null) return;
        selectableControl.setChecked(cd.effectiveSelectable());
    }

    /** Human-readable label for a {@link CharacterType} in the Type dropdown. */
    private String labelForType(CharacterType type) {
        if (type == null) return "Sorcerer";
        return switch (type) {
            case SORCERER  -> "Sorcerer";
            case SHIKIGAMI -> "Shikigami";
        };
    }

    private CharacterType typeFromLabel(String label) {
        if (label == null) return CharacterType.SORCERER;
        for (CharacterType type : CharacterType.values()) {
            if (labelForType(type).equalsIgnoreCase(label.trim())) return type;
        }
        return CharacterType.SORCERER;
    }

    private void refreshBaseStatTotalLabel(CharacterData cd) {
        if (baseStatTotalLabel == null) return;
        int total = 0;
        for (StatKey stat : STAT_ORDER) total += stat.get(cd);
        baseStatTotalLabel.setText("Base Stat Total: " + total);
        baseStatTierLabel.setText(StatTier.forBaseStatTotal(total).displayName());
    }

    private void refreshCtmLock() {
        // Called after technique change. CTM is locked at 0 when no technique.
        // The statFields array is indexed by ordinal; CTE_MASTERY ordinal maps
        // directly to the statFields array index since STAT_ORDER matches.
        if (statFields == null) return;
        boolean locked = (draft != null && draft.innateTechniqueName == null);
        statFields[StatKey.CURSED_TECHNIQUE_MASTERY.ordinal()].setEditable(!locked);
    }

    /** Refresh ability-provided editor bounds, optionally clamping invalid allocations. */
    private void refreshAllocationBounds(CharacterData cd, boolean clampValues) {
        AbilityResolver.Result resolved = resolvedAbilities(cd);
        Map<StatKey, Integer> minimums = resolved.statAllocationMinimums();
        Map<StatKey, Integer> maximums = resolved.statAllocationMaximums();
        for (StatKey stat : STAT_ORDER) {
            boolean lockedCtm = stat == StatKey.CURSED_TECHNIQUE_MASTERY
                && cd.innateTechniqueName == null;
            int floor = lockedCtm ? 0 : minimums.getOrDefault(stat, STAT_MIN);
            int ceiling = lockedCtm ? 0 : maximums.getOrDefault(stat, STAT_MAX);
            if (clampValues) stat.set(cd, Math.max(floor, Math.min(ceiling, stat.get(cd))));
            if (statFields != null) {
                statFields[stat.ordinal()].setEffectiveMinimum(floor);
                statFields[stat.ordinal()].setEffectiveMaximum(ceiling);
                if (clampValues) statFields[stat.ordinal()].setValueProgrammatic(stat.get(cd));
            }
        }
    }

    private void clampAllocationsToBounds(CharacterData cd) {
        AbilityResolver.Result resolved = resolvedAbilities(cd);
        for (StatKey stat : STAT_ORDER) {
            if (stat == StatKey.CURSED_TECHNIQUE_MASTERY
                && cd.innateTechniqueName == null) continue;
            int floor = resolved.statAllocationMinimum(stat);
            int ceiling = resolved.statAllocationMaximum(stat);
            stat.set(cd, Math.max(floor, Math.min(ceiling, stat.get(cd))));
        }
    }

    private void refreshBudgetLabel(CharacterData cd) {
        if (budgetLabel == null) return;
        if (!pointBuyToggle.isChecked()) { budgetLabel.setText(""); return; }
        int abilityBonus = resolvedAbilities(cd).statBonusPoints();
        int budget = pointBudgetFor(cd);
        int spent = pointsSpent(cd);
        int remaining = budget - spent;
        String bonusText = abilityBonus == 0 ? "" : "  (ability bonus: "
            + (abilityBonus > 0 ? "+" : "") + abilityBonus + ")";
        budgetLabel.setText("Points: " + spent + " / " + budget + "  ("
            + Math.max(0, remaining) + " remaining)" + bonusText);
        budgetLabel.setColor(remaining >= 0
            ? skin.get("text-ok", com.badlogic.gdx.graphics.Color.class)
            : skin.get("text-error", com.badlogic.gdx.graphics.Color.class));
    }

    private int pointBudgetFor(CharacterData cd) {
        int base = cd.innateTechniqueName != null
            ? POINT_BUDGET_WITH_TECHNIQUE : POINT_BUDGET_WITHOUT_TECHNIQUE;
        return base + resolvedAbilities(cd).statBonusPoints();
    }

    private static int pointsSpent(CharacterData cd) {
        boolean hasTechnique = cd.innateTechniqueName != null;
        int spent = 0;
        for (StatKey stat : STAT_ORDER) {
            if (stat == StatKey.CURSED_TECHNIQUE_MASTERY && !hasTechnique) continue;
            spent += stat.get(cd) - BASELINE;
        }
        return spent;
    }

    private void refreshDerivedPreview(CharacterData cd) {
        if (derivedPreview == null) return;
        try {
            AbilityApplicator.ApplicationResult application = AbilityApplicator.apply(
                cd.toCharacterStats(), resolvedAbilities(cd).toDomainAbilities());
            CombatStats cs = new CombatStats(
                application.modifiedStats, application.flags.jujutsuArtSlots);
            // Compute slot usage per category.
            StringBuilder sb = new StringBuilder();
            sb.append("HP: ").append(cs.getMaxHp());
            sb.append("  |  AP bar: ").append(Math.max(0,
                cs.getMaxApBar() + application.flags.apBarBonus));
            sb.append("  |  Acc: ").append(cs.getAccuracy());
            sb.append("  |  Eva: ").append(cs.getEvasion());
            sb.append("  |  CE pool: ").append(cs.getMaxCursedEnergy());
            sb.append('\n');
            sb.append("Phys power: ").append(PowerCalculator.physical(application.modifiedStats));
            sb.append("  |  CE power: ").append(PowerCalculator.cursedEnergyBase(application.modifiedStats));
            sb.append('\n');
            CombatStats baseCombatStats = new CombatStats(
                cd.toCharacterStats(), application.flags.jujutsuArtSlots);
            sb.append("Base move slots  —  Combat Arts: ").append(baseCombatStats.getCombatArtsSlots());
            sb.append("  |  Jujutsu Arts: ").append(baseCombatStats.getJujutsuArtsSlots());

            derivedPreview.setText(sb.toString());
        } catch (Exception e) {
            derivedPreview.setText("(compute error: " + e.getMessage() + ")");
        }
    }

    // =========================================================================
    // Move assignment
    // =========================================================================

    private void rebuildMoveAssignment(CharacterData cd) {
        if (moveAssignmentContainer == null) return;
        moveAssignmentContainer.setActor(buildMoveAssignmentPanel(cd));
    }

    private MoveAssignmentPanel buildMoveAssignmentPanel(CharacterData cd) {
        return new MoveAssignmentPanel(new MoveAssignmentPanel.Controller() {
            @Override public List<AssignmentPanel.Item> availableItems(MovePool pool) {
                List<AssignmentPanel.Item> items = new ArrayList<>();
                List<MoveData> allMoves = moveRepo.getAll();
                List<String> assigned = cd.moveIds != null ? cd.moveIds : List.of();
                AbilityResolver.Result abilityResult = resolvedAbilities(cd);
                for (MoveData md : allMoves) {
                    if (md.derivedPool() != pool) continue;
                    if (md.mustBeGranted
                        && !abilityResult.availableMoveIds().contains(md.id)) continue;
                    if (isLockedTechniqueMove(cd, md.id)) continue;
                    if (assigned.contains(md.id)) continue;
                    String sub = md.tags != null ? String.join(", ", md.tags) : "";
                    String error = moveAssignmentError(cd, abilityResult, md, false);
                    AssignmentPanel.Item item = availableMoveItem(md, sub, pool, error);
                    if (item != null) items.add(item);
                }
                return items;
            }

            @Override public List<AssignmentPanel.Item> learnedItems(MovePool pool) {
                List<AssignmentPanel.Item> items = new ArrayList<>();
                List<String> assigned = cd.moveIds != null ? cd.moveIds : List.of();
                AbilityResolver.Result resolved = resolvedAbilities(cd);
                for (String mid : assigned) {
                    MoveData md = mid == null ? null : moveRepo.findById(mid).orElse(null);
                    if (md == null) {
                        if (pool != MovePool.COMBAT_ARTS) continue;
                        items.add(new AssignmentPanel.Item(
                            mid, "Missing move " + String.valueOf(mid),
                            "Click to remove this broken reference"));
                    } else {
                        if (md.derivedPool() != pool) continue;
                        String sub = md.tags != null ? String.join(", ", md.tags) : "";
                        String assignmentError = moveAssignmentError(cd, resolved, md, true);
                        if (assignmentError != null) {
                            sub += " | CONFLICT: " + assignmentError + " (remove this move)";
                        }
                        items.add(new AssignmentPanel.Item(md.id, md.name, sub));
                    }
                }
                return items;
            }

            @Override public boolean canLearn(String moveId) {
                MoveData md = moveRepo.findById(moveId).orElse(null);
                if (md == null) return false;
                if (isLockedTechniqueMove(cd, moveId)) return false;
                AbilityResolver.Result abilityResult = resolvedAbilities(cd);
                return moveAssignmentError(cd, abilityResult, md, false) == null;
            }

            @Override public void onLearn(String moveId) {
                if (cd.moveIds == null) cd.moveIds = new ArrayList<>();
                if (!cd.moveIds.contains(moveId)) cd.moveIds.add(moveId);
                markDirty();
                refreshDerivedPreview(cd);
                refreshBudgetLabel(cd);
                rebuildAbilityAssignment(cd);
                rebuildSkillTree(cd);
            }

            @Override public void onForget(String moveId) {
                if (cd.moveIds != null) cd.moveIds.remove(moveId);
                markDirty();
                refreshDerivedPreview(cd);
                refreshBudgetLabel(cd);
                rebuildAbilityAssignment(cd);
                rebuildSkillTree(cd);
            }

            @Override public int learnedCount(MovePool pool) {
                return SlotBudgetEnforcer.countUsage(getAssignedMovePoolList(cd))
                    .getOrDefault(pool, 0);
            }

            @Override public int learnedLimit(MovePool pool) {
                return SlotBudgetEnforcer.slotBudgetFor(combatStatsWithAbilitySlots(cd), pool);
            }
        }, game.audio()::play, skin);
    }

    /** Collect the {@link MovePool} of every assigned non-free move. */
    private List<MovePool> getAssignedMovePoolList(CharacterData cd) {
        List<MovePool> pools = new ArrayList<>();
        if (cd.moveIds == null) return pools;
        for (String mid : cd.moveIds) {
            MoveData md = moveRepo.findById(mid).orElse(null);
            if (md != null && !md.isFreeMove) {
                try {
                    pools.add(md.derivedPool());
                } catch (Exception ignored) {}
            }
        }
        return pools;
    }

    private static String lockingTag(AbilityResolver.Result abilities, MoveData move) {
        try {
            Move built = move.toMove();
            return abilities.lockedMoveTags().stream()
                .filter(built::hasTag)
                .findFirst()
                .orElse(null);
        } catch (Exception ex) {
            return null;
        }
    }

    private String moveAssignmentError(
        CharacterData character,
        AbilityResolver.Result abilities,
        MoveData move,
        boolean alreadyAssigned
    ) {
        Move built;
        try {
            built = move.toMove();
        } catch (Exception ex) {
            return "Move configuration is invalid: " + ex.getMessage();
        }
        if (move.mustBeGranted && !abilities.availableMoveIds().contains(move.id)) {
            return "This move must be granted by an ability.";
        }
        // A GRANT_MOVE-granted move bypasses all requirements, mirroring
        // Character.validateAndBuildMoveList. UNLOCK_MOVE-granted moves are
        // still subject to every check below.
        boolean bypass = abilities.grantedMoveIds().contains(move.id);
        if (bypass) return null;
        if (built.isWeaponRequired() && !character.hasWeapon) {
            return "Requires a weapon.";
        }
        String lockedTag = lockingTag(abilities, move);
        if (lockedTag != null) return "Locked by ability: " + lockedTag;

        if (move.requiredTechniqueId != null && !move.requiredTechniqueId.isBlank()
            && !abilities.hasTechnique(move.requiredTechniqueId)) {
            return "Needs technique " + move.requiredTechniqueId;
        }

        if (move.prerequisites != null) {
            CharacterStats stats = character.toCharacterStats();
            for (Map.Entry<String, Integer> prerequisite : move.prerequisites.entrySet()) {
                try {
                    StatKey stat = StatKey.fromString(prerequisite.getKey());
                    int actual = stat.get(stats);
                    if (actual < prerequisite.getValue()) {
                        return "Needs " + stat.label + " >= " + prerequisite.getValue()
                            + " (you have " + actual + ")";
                    }
                } catch (IllegalArgumentException ex) {
                    return "Move has an unknown prerequisite: " + prerequisite.getKey();
                }
            }
        }

        if (move.isFreeMove) return null;
        try {
            MovePool pool = move.derivedPool();
            int budget = SlotBudgetEnforcer.slotBudgetFor(
                combatStatsWithAbilitySlots(character), pool);
            int used = SlotBudgetEnforcer.countUsage(
                getAssignedMovePoolList(character)).getOrDefault(pool, 0);
            boolean withinBudget = alreadyAssigned ? used <= budget : used < budget;
            return withinBudget ? null : noAvailableSlotsError(pool);
        } catch (Exception ex) {
            return "Move configuration is invalid: " + ex.getMessage();
        }
    }

    private static String noAvailableSlotsError(MovePool pool) {
        return "No available " + pool + " slots";
    }

    static AssignmentPanel.Item availableMoveItem(
        MoveData move,
        String sublabel,
        MovePool pool,
        String error
    ) {
        if (error == null) return new AssignmentPanel.Item(move.id, move.name, sublabel);
        return noAvailableSlotsError(pool).equals(error)
            ? new AssignmentPanel.Item(move.id, move.name, sublabel, true, error) : null;
    }

    // =========================================================================
    // Ability assignment
    // =========================================================================

    private void rebuildAbilityAssignment(CharacterData cd) {
        if (abilityAssignmentContainer == null) return;
        abilityAssignmentContainer.setActor(buildAbilityAssignmentPanel(cd));
    }

    private AssignmentPanel buildAbilityAssignmentPanel(CharacterData cd) {
        return new AssignmentPanel(new AssignmentPanel.Controller() {
            @Override public List<AssignmentPanel.Item> availableItems() {
                List<AssignmentPanel.Item> items = new ArrayList<>();
                List<String> assigned = cd.abilityIds != null ? cd.abilityIds : List.of();
                AbilityResolver.Result resolved = resolvedAbilities(cd);
                for (String abilityId : resolved.availableAbilityIds()) {
                    AbilityData ad = abilityRepo.findById(abilityId).orElse(null);
                    if (ad == null || assigned.contains(ad.id)) continue;
                    String sub = abilitySublabel(ad);
                    String conflict = abilityAssignmentConflict(cd, ad.id);
                    items.add(conflict == null
                        ? new AssignmentPanel.Item(ad.id, ad.name, sub)
                        : new AssignmentPanel.Item(ad.id, ad.name, sub, true, conflict));
                }
                return items;
            }

            @Override public List<AssignmentPanel.Item> assignedItems() {
                List<AssignmentPanel.Item> items = new ArrayList<>();
                List<String> assigned = cd.abilityIds != null ? cd.abilityIds : List.of();
                for (String aid : assigned) {
                    AbilityData ad = aid == null ? null : abilityRepo.findById(aid).orElse(null);
                    if (ad == null) {
                        items.add(new AssignmentPanel.Item(
                            aid, "Missing ability " + String.valueOf(aid),
                            "Click to remove this broken reference"));
                    } else {
                        String sub = abilitySublabel(ad);
                        if (!resolvedAbilities(cd).availableAbilityIds().contains(ad.id)) {
                            sub += " | UNAVAILABLE: " + sourceRequirement(ad);
                        }
                        items.add(new AssignmentPanel.Item(ad.id, ad.name, sub));
                    }
                }
                return items;
            }

            @Override public boolean canAssign(String id) {
                AbilityData ability = abilityRepo.findById(id).orElse(null);
                if (ability == null) return false;
                return resolvedAbilities(cd).availableAbilityIds().contains(id)
                    && abilityAssignmentConflict(cd, id) == null;
            }

            @Override public void onAssign(String id) {
                if (cd.abilityIds == null) cd.abilityIds = new ArrayList<>();
                if (!cd.abilityIds.contains(id)) cd.abilityIds.add(id);
                autoEquipGrantedAbilities(cd);
                refreshAllocationBounds(cd, true);
                markDirty();
                refreshBaseStatTotalLabel(cd);
                refreshDerivedPreview(cd);
                refreshBudgetLabel(cd);
                rebuildMoveAssignment(cd);
                rebuildAbilityAssignment(cd);
                rebuildSkillTree(cd);
            }

            @Override public void onUnassign(String id) {
                if (cd.abilityIds != null) cd.abilityIds.remove(id);
                refreshAllocationBounds(cd, false);
                markDirty();
                refreshDerivedPreview(cd);
                refreshBudgetLabel(cd);
                rebuildMoveAssignment(cd);
                rebuildAbilityAssignment(cd);
                rebuildSkillTree(cd);
            }

            @Override public String budgetSummary() {
                return "Only available abilities can be assigned.";
            }
        }, game.audio()::play, skin);
    }

    private AbilityResolver.Result resolvedAbilities(CharacterData cd) {
        techniqueRepo.getAll().forEach(technique -> TechniqueSkillTree.synchronize(
            technique, moveRepo.getAll(), abilityRepo.getAll()));
        return AbilityResolver.resolve(
            cd, abilityRepo, this::isValidMoveDefinition, techniqueRepo);
    }

    private CombatStats combatStatsWithAbilitySlots(CharacterData cd) {
        AbilityApplicator.ApplicationResult application = AbilityApplicator.apply(
            cd.toCharacterStats(), resolvedAbilities(cd).toDomainAbilities());
        return new CombatStats(cd.toCharacterStats(), application.flags.jujutsuArtSlots);
    }

    /**
     * Auto-equips abilities granted by already-assigned (parent) abilities.
     *
     * Runs only as a consequence of assigning a parent: after the parent is added,
     * {@link AbilityResolver} propagates every {@code GRANT_ABILITY} effect to a fixed
     * point, so transitively-granted children appear in the available pool. We then
     * assign every such child that is currently available and conflict-free. Re-resolving
     * after each add lets grant chains (grandchildren) cascade in one pass.
     *
     * This does nothing on unequip: removing a parent (or child) never cascades here.
     */
    private void autoEquipGrantedAbilities(CharacterData cd) {
        if (cd.abilityIds == null) cd.abilityIds = new ArrayList<>();
        Set<String> queued = new LinkedHashSet<>();
        while (true) {
            List<String> available = resolvedAbilities(cd).availableAbilityIds();
            boolean changed = false;
            for (String parentId : new ArrayList<>(cd.abilityIds)) {
                for (String childId : grantedAbilityIds(parentId)) {
                    if (cd.abilityIds.contains(childId) || queued.contains(childId)) continue;
                    if (!available.contains(childId)) continue;           // not yet in available section
                    if (abilityAssignmentConflict(cd, childId) != null) continue; // prerequisites unmet
                    cd.abilityIds.add(childId);
                    queued.add(childId);
                    changed = true;
                }
            }
            if (!changed) break;
        }
    }

    /** IDs granted by an ability's {@code GRANT_ABILITY} effects (6-digit ability ids). */
    private List<String> grantedAbilityIds(String parentId) {
        List<String> ids = new ArrayList<>();
        AbilityData parent = abilityRepo.findById(parentId).orElse(null);
        if (parent == null || parent.effects == null || !parent.isPassive()) return ids;
        for (AbilityEffectData effect : parent.effects) {
            if (effect == null || effect.type == null || effect.abilityId == null
                || effect.abilityId.isBlank()) continue;
            if (!AbilityEffectType.GRANT_ABILITY.name().equalsIgnoreCase(
                effect.type.trim())) continue;
            ids.add(effect.abilityId);
        }
        return ids;
    }

    private boolean isValidMoveDefinition(String moveId) {
        if (moveId == null) return false;
        return moveRepo.findById(moveId)
            .map(move -> {
                try {
                    move.toMove();
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            })
            .orElse(false);
    }

    private String abilityAssignmentConflict(CharacterData cd, String abilityId) {
        CharacterData probe = draftFromRecord(cd);
        if (probe.abilityIds == null) probe.abilityIds = new ArrayList<>();
        if (!probe.abilityIds.contains(abilityId)) probe.abilityIds.add(abilityId);
        try {
            clampAllocationsToBounds(probe);
            probe.toCharacter(moveRepo, abilityRepo, techniqueRepo);
            return null;
        } catch (Exception ex) {
            return "Cannot assign: " + ex.getMessage();
        }
    }

    private static String abilitySublabel(AbilityData ability) {
        String category = ability.category == null ? "PASSIVE" : ability.category;
        String source = ability.sourceType == null ? "CHARACTER" : ability.sourceType;
        return category + " (" + source + ")";
    }

    private static String sourceRequirement(AbilityData ability) {
        String source = ability.sourceType == null ? "CHARACTER" : ability.sourceType.toUpperCase();
        return switch (source) {
            case "TECHNIQUE" -> "activate in the " + ability.sourceValue + " technique tree";
            case "MOVE" -> "know move " + ability.sourceValue;
            case "STAT_THRESHOLD" -> ability.sourceValue;
            case "ABILITY" -> "have ability " + ability.sourceValue;
            case "SHIKIGAMI" -> "be a Shikigami";
            default -> "assign directly";
        };
    }

}
