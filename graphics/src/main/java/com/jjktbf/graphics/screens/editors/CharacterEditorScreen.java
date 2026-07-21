package com.jjktbf.graphics.screens.editors;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Align;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.ui.editor.AssignmentPanel;
import com.jjktbf.graphics.ui.editor.EditorScreenBase;
import com.jjktbf.graphics.ui.editor.StatField;
import com.jjktbf.graphics.ui.editor.SkillTreeCanvas;
import com.jjktbf.graphics.ui.editor.ValidationResult;
import com.jjktbf.model.character.AbilityApplicator;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityRepository;
import com.jjktbf.model.character.AbilityResolver;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.CombatStats;
import com.jjktbf.model.character.CharacterRepository;
import com.jjktbf.model.character.SlotBudgetEnforcer;
import com.jjktbf.model.character.StatKey;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Graphical CRUD editor for {@link CharacterData}. Master-detail layout with:
 *   - Name / Innate Technique fields
 *   - 10× {@link StatField} sliders (with Manual / Point-Buy mode toggle)
 *   - Live derived-stat preview (HP, AP bar, Accuracy, Evasion, CE pool, per-category slots)
 *   - Move assignment panel (slot-gated, technique/prerequisite-filtered, DnD)
 *   - Ability assignment panel (direct abilities plus automatic source grants)
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

    private static final int STAT_MIN = 10;
    private static final int STAT_MAX = 300;
    private static final int BASELINE = 80;
    private static final int POINT_BUDGET_WITH_TECHNIQUE    = 1000;
    private static final int POINT_BUDGET_WITHOUT_TECHNIQUE = 1080;

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
    private Label budgetLabel;

    public CharacterEditorScreen(JJKGame game, AssetLoader assets) {
        super(game, assets);
        charRepo    = new CharacterRepository("data/characters");
        moveRepo    = new MoveRepository("data/moves");
        abilityRepo = new AbilityRepository("data/abilities");
        techniqueRepo = new TechniqueRepository("data/techniques");
    }

    // =========================================================================
    // Abstract hooks
    // =========================================================================

    @Override protected String title() { return "CHARACTER EDITOR"; }

    @Override protected CharacterData newDraft() {
        CharacterData cd = new CharacterData();
        cd.name = "New Character";
        cd.description = "";
        cd.spriteAsset = null;
        cd.innateTechniqueName = null;
        cd.cursedTechniqueMastery = 0;
        cd.moveIds    = new ArrayList<>();
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
        d.spriteAsset         = stored.spriteAsset;
        d.innateTechniqueName = stored.innateTechniqueName;
        for (StatKey sk : STAT_ORDER) sk.set(d, sk.get(stored));
        if (d.innateTechniqueName == null) d.cursedTechniqueMastery = 0;
        d.moveIds    = stored.moveIds    != null ? new ArrayList<>(stored.moveIds)    : new ArrayList<>();
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
        referencedMoveIds.addAll(resolvedAbilities(d).grantedMoveIds());
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
            charRepo.delete(id);
            charRepo.save();
            return ValidationResult.ok("Deleted.");
        } catch (Exception e) {
            return ValidationResult.error("Delete failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // Detail form
    // =========================================================================

    @Override
    protected Actor buildDetailForm(CharacterData cd) {
        Table form = formRoot();

        // ── Identity ───────────────────────────────────────────────────────────
        Table identity = formSection(form, "NAME");
        identity.add(idBadge(cd.id)).left().row();
        identity.add(labelledField("Name", cd.name,
                s -> { cd.name = s; })).growX().row();
        identity.add(labelledField("Description", cd.description,
                s -> { cd.description = s; })).growX().row();
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
                refreshDerivedPreview(cd);
                refreshBudgetLabel(cd);
                rebuildAbilityAssignment(cd);
                rebuildMoveAssignment(cd);
                rebuildSkillTree(cd);
                markDirty();
            }
        });
        identity.add(labelledRow("Innate Technique", techniqueSelect)).growX().row();

        // ── Stats (mode toggle + sliders) ───────────────────────────────────────
        Table stats = formSection(form, "STATS");
        pointBuyToggle = new CheckBox(" Point-Buy mode", skin);
        pointBuyToggle.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
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
        stats.add(pointBuyToggle).left().row();

        // Budget label (point-buy only)
        budgetLabel = new Label("", skin, "small");
        budgetLabel.setColor(skin.get("text-dirty", com.badlogic.gdx.graphics.Color.class));
        stats.add(budgetLabel).left().row();

        statFields = new StatField[STAT_ORDER.length];
        boolean hasTechnique = cd.innateTechniqueName != null;
        for (int i = 0; i < STAT_ORDER.length; i++) {
            StatKey sk = STAT_ORDER[i];
            int val = sk.get(cd);
            boolean locked = (sk == StatKey.CURSED_TECHNIQUE_MASTERY && !hasTechnique);
            int fieldMinimum = sk == StatKey.CURSED_TECHNIQUE_MASTERY ? 0 : STAT_MIN;
            StatField sf = new StatField(sk.label, val, fieldMinimum, STAT_MAX, v -> {
                sk.set(cd, v);
                pruneLockedTechniqueSelections(cd);
                refreshDerivedPreview(cd);
                if (pointBuyToggle.isChecked()) refreshBudgetLabel(cd);
                rebuildAbilityAssignment(cd);
                rebuildMoveAssignment(cd);
                rebuildSkillTree(cd);
                markDirty();
            }, locked, skin);
            statFields[i] = sf;
            stats.add(sf).growX().row();
        }

        // ── Derived preview ──────────────────────────────────────────────────────
        Table derived = formSection(form, "DERIVED STATS (LIVE)");
        derivedPreview = new Label("", skin, "small");
        derivedPreview.setAlignment(Align.left);
        derived.add(derivedPreview).growX().row();
        refreshDerivedPreview(cd);

        // ── Move assignment ────────────────────────────────────────────────────
        Table movesSection = formSection(form, "MOVE ASSIGNMENT");
        moveAssignmentContainer = new Container<>();
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
                skin);
            ScrollPane scroll = new ScrollPane(canvas, skin);
            scroll.setFadeScrollBars(false);
            scroll.setFlickScroll(false);
            scroll.setScrollingDisabled(false, true);
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
            if (resolvedAbilities(character).grantedMoveIds().contains(node.contentId)) {
                return "This move is already granted by an active ability.";
            }
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
        SelectBox<String> select = new SelectBox<>(skin);
        select.setItems(names.toArray(new String[0]));
        select.setSelected(currentName == null ? none : names.stream()
            .filter(currentName::equalsIgnoreCase).findFirst().orElse(currentName));
        return select;
    }

    private static String techniqueNameFromLabel(String label) {
        return label == null || "[none]".equals(label) ? null : label;
    }

    private boolean isCurrentTechniqueMove(CharacterData character, String moveId) {
        if (character == null || moveId == null) return false;
        MoveData move = moveRepo.findById(moveId).orElse(null);
        return move != null && move.requiredTechniqueId != null
            && resolvedAbilities(character).hasTechnique(move.requiredTechniqueId);
    }

    private String techniqueAbilityAvailabilityError(CharacterData character, AbilityData ability) {
        if (!isTechniqueSource(ability)) return null;
        AbilityResolver.Result resolved = resolvedAbilities(character);
        if (ability.sourceValue == null || !resolved.hasTechnique(ability.sourceValue)) {
            return "Needs technique " + String.valueOf(ability.sourceValue);
        }
        InnateTechniqueData technique = techniqueForName(ability.sourceValue);
        if (technique == null) return "Technique definition is missing";
        SkillTreeNodeData node = TechniqueSkillTree.nodeForContent(
            technique, SkillTreeNodeData.ABILITY, ability.id);
        if (node == null) return "This ability is not in the technique tree";
        if (!TechniqueSkillTree.isUnlocked(technique, node, character)) {
            List<String> unmet = TechniqueSkillTree.unmetPrerequisites(technique, node, character);
            return unmet.isEmpty() ? "Technique-tree node is locked" : String.join("; ", unmet);
        }
        return TechniqueSkillTree.isActive(node, character)
            ? null : "Toggle this ability in the technique tree first";
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
        for (StatKey sk : STAT_ORDER) {
            int value = sk == StatKey.CURSED_TECHNIQUE_MASTERY
                && cd.innateTechniqueName == null ? 0 : BASELINE;
            sk.set(cd, value);
            statFields[sk.ordinal()].setValueProgrammatic(value);
        }
        refreshCtmLock();
        refreshDerivedPreview(cd);
        refreshBudgetLabel(cd);
    }

    private void refreshCtmLock() {
        // Called after technique change. CTM is locked at 0 when no technique.
        // The statFields array is indexed by ordinal; CTE_MASTERY ordinal maps
        // directly to the statFields array index since STAT_ORDER matches.
        if (statFields == null) return;
        boolean locked = (draft != null && draft.innateTechniqueName == null);
        statFields[StatKey.CURSED_TECHNIQUE_MASTERY.ordinal()].setEditable(!locked);
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
            CombatStats cs = new CombatStats(application.modifiedStats);
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
            CombatStats baseCombatStats = cd.toCombatStats();
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

    private AssignmentPanel buildMoveAssignmentPanel(CharacterData cd) {
        return new AssignmentPanel(new AssignmentPanel.Controller() {
            @Override public List<AssignmentPanel.Item> availableItems() {
                List<AssignmentPanel.Item> items = new ArrayList<>();
                List<MoveData> allMoves = moveRepo.getAll();
                List<String> assigned = cd.moveIds != null ? cd.moveIds : List.of();
                AbilityResolver.Result abilityResult = resolvedAbilities(cd);
                List<String> granted = abilityResult.grantedMoveIds();
                for (MoveData md : allMoves) {
                    if (isCurrentTechniqueMove(cd, md.id)) continue;
                    if (assigned.contains(md.id) || granted.contains(md.id)) continue;
                    String sub = md.tags != null ? String.join(", ", md.tags) : "";
                    String error = moveAssignmentError(cd, abilityResult, md, false);
                    items.add(error == null
                        ? new AssignmentPanel.Item(md.id, md.name, sub)
                        : new AssignmentPanel.Item(md.id, md.name, sub, true, error));
                }
                return items;
            }

            @Override public List<AssignmentPanel.Item> assignedItems() {
                List<AssignmentPanel.Item> items = new ArrayList<>();
                List<String> assigned = cd.moveIds != null ? cd.moveIds : List.of();
                List<String> granted = resolvedAbilities(cd).grantedMoveIds();
                for (String mid : assigned) {
                    if (isCurrentTechniqueMove(cd, mid)) continue;
                    MoveData md = mid == null ? null : moveRepo.findById(mid).orElse(null);
                    if (md == null) {
                        items.add(new AssignmentPanel.Item(
                            mid, "Missing move " + String.valueOf(mid),
                            "Click to remove this broken reference"));
                    } else {
                        String sub = md.tags != null ? String.join(", ", md.tags) : "";
                        if (granted.contains(mid)) {
                            sub += " | Granted by ability; remove the redundant assignment";
                        }
                        String assignmentError = granted.contains(mid) ? null
                            : moveAssignmentError(cd, resolvedAbilities(cd), md, true);
                        if (assignmentError != null) {
                            sub += " | CONFLICT: " + assignmentError + " (remove this move)";
                        }
                        items.add(new AssignmentPanel.Item(md.id, md.name, sub));
                    }
                }
                for (String moveId : resolvedAbilities(cd).grantedMoveIds()) {
                    if (isCurrentTechniqueMove(cd, moveId)) continue;
                    if (assigned.contains(moveId)) continue;
                    MoveData md = moveRepo.findById(moveId).orElse(null);
                    if (md != null) {
                        items.add(new AssignmentPanel.Item(
                            md.id, md.name, "Granted by ability", true, "Granted by ability"));
                    }
                }
                return items;
            }

            @Override public boolean canAssign(String moveId) {
                MoveData md = moveRepo.findById(moveId).orElse(null);
                if (md == null) return false;
                AbilityResolver.Result abilityResult = resolvedAbilities(cd);
                if (abilityResult.grantedMoveIds().contains(moveId)) return false;
                return moveAssignmentError(cd, abilityResult, md, false) == null;
            }

            @Override public void onAssign(String moveId) {
                if (cd.moveIds == null) cd.moveIds = new ArrayList<>();
                if (!cd.moveIds.contains(moveId)) cd.moveIds.add(moveId);
                markDirty();
                refreshDerivedPreview(cd);
                refreshBudgetLabel(cd);
                rebuildAbilityAssignment(cd);
            }

            @Override public void onUnassign(String moveId) {
                if (cd.moveIds != null) cd.moveIds.remove(moveId);
                markDirty();
                refreshDerivedPreview(cd);
                refreshBudgetLabel(cd);
                rebuildAbilityAssignment(cd);
            }

            @Override public String budgetSummary() {
                try {
                    CombatStats cs = cd.toCombatStats();
                    Map<MovePool, Integer> usage =
                        SlotBudgetEnforcer.countUsage(getAssignedMovePoolList(cd));
                    int combatUsed  = usage.getOrDefault(MovePool.COMBAT_ARTS, 0);
                    int jujutsuUsed = usage.getOrDefault(MovePool.JUJUTSU_ARTS, 0);
                    return "Slots —  Combat Arts: " + combatUsed + '/' + cs.getCombatArtsSlots()
                        + "  |  Jujutsu Arts: " + jujutsuUsed + '/' + cs.getJujutsuArtsSlots();
                } catch (Exception e) {
                    return "(slot computation error)";
                }
            }
        }, skin);
    }

    /** Collect the {@link MovePool} of every assigned non-free move. */
    private List<MovePool> getAssignedMovePoolList(CharacterData cd) {
        List<MovePool> pools = new ArrayList<>();
        if (cd.moveIds == null) return pools;
        List<String> granted = resolvedAbilities(cd).grantedMoveIds();
        for (String mid : cd.moveIds) {
            if (granted.contains(mid)) continue;
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
        try {
            move.toMove();
        } catch (Exception ex) {
            return "Move configuration is invalid: " + ex.getMessage();
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
            int budget = SlotBudgetEnforcer.slotBudgetFor(character.toCombatStats(), pool);
            int used = SlotBudgetEnforcer.countUsage(
                getAssignedMovePoolList(character)).getOrDefault(pool, 0);
            boolean withinBudget = alreadyAssigned ? used <= budget : used < budget;
            return withinBudget ? null : "No available " + pool + " slots";
        } catch (Exception ex) {
            return "Move configuration is invalid: " + ex.getMessage();
        }
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
                List<AbilityData> all = abilityRepo.getAll();
                List<String> assigned = cd.abilityIds != null ? cd.abilityIds : List.of();
                AbilityResolver.Result resolved = resolvedAbilities(cd);
                for (AbilityData ad : all) {
                    if (assigned.contains(ad.id) || resolved.containsAbility(ad.id)) continue;
                    String sub = abilitySublabel(ad);
                    if (isTechniqueSource(ad)) {
                        String availabilityError = techniqueAbilityAvailabilityError(cd, ad);
                        items.add(availabilityError == null
                            ? new AssignmentPanel.Item(ad.id, ad.name, sub)
                            : new AssignmentPanel.Item(
                                ad.id, ad.name, sub, true, availabilityError));
                    } else if (isCharacterSource(ad)) {
                        String conflict = abilityAssignmentConflict(cd, ad.id);
                        items.add(conflict == null
                            ? new AssignmentPanel.Item(ad.id, ad.name, sub)
                            : new AssignmentPanel.Item(ad.id, ad.name, sub, true, conflict));
                    } else {
                        String reason = sourceRequirement(ad);
                        items.add(new AssignmentPanel.Item(ad.id, ad.name, sub, true, reason));
                    }
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
                        if (isTechniqueSource(ad)) {
                            String availabilityError = techniqueAbilityAvailabilityError(cd, ad);
                            if (availabilityError != null) {
                                sub += " | UNAVAILABLE: " + availabilityError;
                            }
                        } else if (!isCharacterSource(ad)) {
                            sub = "Explicit reference; source rules still apply";
                        }
                        items.add(new AssignmentPanel.Item(ad.id, ad.name, sub));
                    }
                }
                for (AbilityData ad : resolvedAbilities(cd).abilities()) {
                    if (assigned.contains(ad.id)) continue;
                    items.add(new AssignmentPanel.Item(
                        ad.id, ad.name, abilitySublabel(ad), true, "Auto-granted: " + sourceRequirement(ad)));
                }
                return items;
            }

            @Override public boolean canAssign(String id) {
                AbilityData ability = abilityRepo.findById(id).orElse(null);
                if (ability == null) return false;
                if (isTechniqueSource(ability)) {
                    return techniqueAbilityAvailabilityError(cd, ability) == null
                        && abilityAssignmentConflict(cd, id) == null;
                }
                return isCharacterSource(ability) && abilityAssignmentConflict(cd, id) == null;
            }

            @Override public void onAssign(String id) {
                if (cd.abilityIds == null) cd.abilityIds = new ArrayList<>();
                if (!cd.abilityIds.contains(id)) cd.abilityIds.add(id);
                markDirty();
                refreshDerivedPreview(cd);
                refreshBudgetLabel(cd);
                rebuildMoveAssignment(cd);
                rebuildSkillTree(cd);
            }

            @Override public void onUnassign(String id) {
                if (cd.abilityIds != null) cd.abilityIds.remove(id);
                markDirty();
                refreshDerivedPreview(cd);
                refreshBudgetLabel(cd);
                rebuildMoveAssignment(cd);
                rebuildSkillTree(cd);
            }

            @Override public String budgetSummary() {
                List<String> explicit = cd.abilityIds != null ? cd.abilityIds : List.of();
                List<String> automatic = resolvedAbilities(cd).abilities().stream()
                    .filter(ability -> !explicit.contains(ability.id))
                    .map(ability -> ability.name)
                    .toList();
                return automatic.isEmpty()
                    ? "Technique abilities unlocked in the technique tree appear in AVAILABLE."
                    : "Auto-granted: " + String.join(", ", automatic);
            }
        }, skin);
    }

    private AbilityResolver.Result resolvedAbilities(CharacterData cd) {
        techniqueRepo.getAll().forEach(technique -> TechniqueSkillTree.synchronize(
            technique, moveRepo.getAll(), abilityRepo.getAll()));
        return AbilityResolver.resolve(
            cd, abilityRepo, this::isValidMoveDefinition, techniqueRepo);
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
            probe.toCharacter(moveRepo, abilityRepo, techniqueRepo);
            return null;
        } catch (Exception ex) {
            return "Cannot assign: " + ex.getMessage();
        }
    }

    private static boolean isCharacterSource(AbilityData ability) {
        return ability.sourceType == null || "CHARACTER".equalsIgnoreCase(ability.sourceType);
    }

    private static boolean isTechniqueSource(AbilityData ability) {
        return ability != null && "TECHNIQUE".equalsIgnoreCase(ability.sourceType);
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
            default -> "assign directly";
        };
    }

}
