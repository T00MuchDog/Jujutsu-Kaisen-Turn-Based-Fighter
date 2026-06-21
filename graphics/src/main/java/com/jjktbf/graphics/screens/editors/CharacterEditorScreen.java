package com.jjktbf.graphics.screens.editors;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
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
import com.jjktbf.graphics.ui.editor.ValidationResult;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityRepository;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.CombatStats;
import com.jjktbf.model.character.CharacterRepository;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.character.SlotBudgetEnforcer;
import com.jjktbf.model.character.StatKey;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveRepository;
import com.jjktbf.model.move.MoveTag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Graphical CRUD editor for {@link CharacterData}. Master-detail layout with:
 *   - Name / Innate Technique fields
 *   - 10× {@link StatField} sliders (with Manual / Point-Buy mode toggle)
 *   - Live derived-stat preview (HP, AP bar, Accuracy, Evasion, CE pool, per-category slots)
 *   - Move assignment panel (slot-gated, technique/prerequisite-filtered, DnD)
 *   - Ability assignment panel (ungated, DnD)
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

    // Form handles (refreshed on selection change)
    private StatField[] statFields;
    private Label derivedPreview;
    private Container<Actor> moveAssignmentContainer;
    private Container<Actor> abilityAssignmentContainer;
    private CheckBox pointBuyToggle;
    private Label budgetLabel;

    public CharacterEditorScreen(JJKGame game, AssetLoader assets) {
        super(game, assets);
        charRepo    = new CharacterRepository("data/characters");
        moveRepo    = new MoveRepository("data/moves");
        abilityRepo = new AbilityRepository("data/abilities");
    }

    // =========================================================================
    // Abstract hooks
    // =========================================================================

    @Override protected String title() { return "CHARACTER EDITOR"; }

    @Override protected CharacterData newDraft() {
        CharacterData cd = new CharacterData();
        cd.name = "New Character";
        cd.innateTechniqueName = null;
        cd.moveIds    = new ArrayList<>();
        cd.abilityIds = new ArrayList<>();
        return cd;
    }

    @Override protected CharacterData draftFromRecord(CharacterData stored) {
        // Deep copy: CharacterData has no copy constructor, so we copy every field.
        CharacterData d = new CharacterData();
        d.id                  = stored.id;
        d.name                = stored.name;
        d.innateTechniqueName = stored.innateTechniqueName;
        for (StatKey sk : STAT_ORDER) sk.set(d, sk.get(stored));
        d.moveIds    = stored.moveIds    != null ? new ArrayList<>(stored.moveIds)    : new ArrayList<>();
        d.abilityIds = stored.abilityIds != null ? new ArrayList<>(stored.abilityIds) : new ArrayList<>();
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
        records.clear();
        records.addAll(charRepo.getAll());
    }

    @Override
    protected ValidationResult validateAndSave(CharacterData d) {
        if (d.name == null || d.name.trim().isEmpty()) {
            return ValidationResult.error("Name is required.");
        }
        // New drafts need a non-blank id for the Entity constructor to validate.
        if (isNewDraft(d) && (d.id == null || d.id.isBlank())) {
            d.id = charRepo.nextId();
        }
        try {
            d.toCharacter(moveRepo, abilityRepo);
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
        Table form = new Table(skin);
        form.defaults().left().pad(4);
        form.pad(8);

        // ── Identity ───────────────────────────────────────────────────────────
        form.add(sectionHeader("IDENTITY")).growX().colspan(2).row();
        form.add(idBadge(cd.id)).colspan(2).padBottom(2).row();
        form.add(labelledField("Name", cd.name,
                s -> { cd.name = s; })).growX().colspan(2).row();
        form.add(labelledField("Innate Technique (blank = none)",
                cd.innateTechniqueName,
                s -> {
                    cd.innateTechniqueName = (s == null || s.isBlank()) ? null : s;
                    refreshCtmLock();
                    refreshDerivedPreview(cd);
                    // Clear CTM when technique removed
                    if (cd.innateTechniqueName == null && statFields != null) {
                        StatKey.CURSED_TECHNIQUE_MASTERY.set(cd, 0);
                        statFields[StatKey.CURSED_TECHNIQUE_MASTERY.ordinal()]
                            .setValueProgrammatic(0);
                    }
                })).growX().colspan(2).row();

        // ── Mode toggle ─────────────────────────────────────────────────────────
        form.add(sectionHeader("STATS")).growX().colspan(2).row();
        pointBuyToggle = new CheckBox(" Point-Buy mode (1000 pts)", skin);
        pointBuyToggle.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                if (pointBuyToggle.isChecked()) {
                    applyPointBuy(cd);
                } else {
                    refreshDerivedPreview(cd);
                }
                markDirty();
            }
        });
        form.add(pointBuyToggle).colspan(2).row();

        // Budget label (point-buy only)
        budgetLabel = new Label("", skin, "small");
        budgetLabel.setColor(skin.get("text-dirty", com.badlogic.gdx.graphics.Color.class));
        form.add(budgetLabel).colspan(2).row();

        // ── Stat sliders ─────────────────────────────────────────────────────────
        statFields = new StatField[STAT_ORDER.length];
        boolean hasTechnique = cd.innateTechniqueName != null;
        for (int i = 0; i < STAT_ORDER.length; i++) {
            StatKey sk = STAT_ORDER[i];
            int val = sk.get(cd);
            boolean locked = (sk == StatKey.CURSED_TECHNIQUE_MASTERY && !hasTechnique);
            StatField sf = new StatField(sk.label, val, STAT_MIN, STAT_MAX, v -> {
                sk.set(cd, v);
                refreshDerivedPreview(cd);
                if (pointBuyToggle.isChecked()) refreshBudgetLabel(cd);
                markDirty();
            }, locked, skin);
            statFields[i] = sf;
            form.add(sf).growX().colspan(2).row();
        }

        // ── Derived preview ──────────────────────────────────────────────────────
        form.add(sectionHeader("DERIVED STATS (live)")).growX().colspan(2).row();
        derivedPreview = new Label("", skin, "small");
        derivedPreview.setAlignment(Align.left);
        form.add(derivedPreview).growX().colspan(2).row();
        refreshDerivedPreview(cd);

        // ── Move assignment ────────────────────────────────────────────────────
        form.add(sectionHeader("MOVE ASSIGNMENT")).growX().colspan(2).row();
        moveAssignmentContainer = new Container<>();
        form.add(moveAssignmentContainer).growX().colspan(2).row();
        rebuildMoveAssignment(cd);

        // ── Ability assignment ──────────────────────────────────────────────────
        form.add(sectionHeader("ABILITY ASSIGNMENT")).growX().colspan(2).row();
        abilityAssignmentContainer = new Container<>();
        form.add(abilityAssignmentContainer).growX().colspan(2).row();
        rebuildAbilityAssignment(cd);

        return form;
    }

    // =========================================================================
    // Stat modes
    // =========================================================================

    /** Apply point-buy mode: reset all stats to baseline, enforce budget. */
    private void applyPointBuy(CharacterData cd) {
        for (StatKey sk : STAT_ORDER) {
            sk.set(cd, BASELINE);
            statFields[sk.ordinal()].setValueProgrammatic(BASELINE);
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
        boolean hasTech = cd.innateTechniqueName != null;
        int budget = hasTech ? POINT_BUDGET_WITH_TECHNIQUE : POINT_BUDGET_WITHOUT_TECHNIQUE;
        int spent = 0;
        for (StatKey sk : STAT_ORDER) {
            int v = sk.get(cd);
            if (sk == StatKey.CURSED_TECHNIQUE_MASTERY && !hasTech) continue;
            spent += (v - BASELINE);
        }
        int remaining = budget - spent;
        budgetLabel.setText("Points: " + spent + " / " + budget + "  ("
            + Math.max(0, remaining) + " remaining)");
        budgetLabel.setColor(remaining >= 0
            ? skin.get("text-ok", com.badlogic.gdx.graphics.Color.class)
            : skin.get("text-error", com.badlogic.gdx.graphics.Color.class));
    }

    private void refreshDerivedPreview(CharacterData cd) {
        if (derivedPreview == null) return;
        try {
            CombatStats cs = cd.toCombatStats();
            // Compute slot usage per category.
            StringBuilder sb = new StringBuilder();
            sb.append("HP: ").append(cs.getMaxHp());
            sb.append("  |  AP bar: ").append(cs.getMaxApBar());
            sb.append("  |  Acc: ").append(cs.getAccuracy());
            sb.append("  |  Eva: ").append(cs.getEvasion());
            sb.append("  |  CE pool: ").append(cs.getMaxCursedEnergy());
            sb.append('\n');
            sb.append("Phys power: ").append(cs.getPhysicalPowerComponent());
            sb.append("  |  CE power: ").append(cs.getCursedEnergyPowerComponent());
            sb.append('\n');
            sb.append("Move slots  —  Phys: ").append(cs.getPhysicalMoveSlots());
            sb.append("  |  Jujutsu: ").append(cs.getJujutsuTechniqueSlots());
            sb.append("  |  CT: ").append(cs.getCursedTechniqueSlots());

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
                int ctm = cd.cursedTechniqueMastery;
                for (MoveData md : allMoves) {
                    if (assigned.contains(md.id)) continue;
                    String sub = md.tags != null ? String.join(", ", md.tags) : "";
                    // Technique moves whose CTM prerequisite the character hasn't
                    // reached are shown LOCKED (greyed, unclickable) so the
                    // progression ladder is visible rather than silently hidden.
                    Integer ctmReq = masteryPrereq(md, "cursedtechniquemastery", "ctm");
                    if (ctmReq != null && ctmReq > ctm) {
                        items.add(new AssignmentPanel.Item(
                            md.id, md.name, sub, true,
                            "🔒 needs CTM ≥ " + ctmReq + " (you have " + ctm + ")"));
                    } else {
                        items.add(new AssignmentPanel.Item(md.id, md.name, sub));
                    }
                }
                return items;
            }

            @Override public List<AssignmentPanel.Item> assignedItems() {
                List<AssignmentPanel.Item> items = new ArrayList<>();
                List<String> assigned = cd.moveIds != null ? cd.moveIds : List.of();
                for (String mid : assigned) {
                    MoveData md = moveRepo.findById(mid).orElse(null);
                    if (md != null) {
                        String sub = md.tags != null ? String.join(", ", md.tags) : "";
                        items.add(new AssignmentPanel.Item(md.id, md.name, sub));
                    }
                }
                return items;
            }

            @Override public boolean canAssign(String moveId) {
                MoveData md = moveRepo.findById(moveId).orElse(null);
                if (md == null) return false;
                // Technique restriction
                if (md.requiredTechniqueId != null && !md.requiredTechniqueId.isEmpty()) {
                    if (cd.innateTechniqueName == null
                        || !cd.innateTechniqueName.equalsIgnoreCase(md.requiredTechniqueId)) {
                        return false;
                    }
                }
                // Stat prerequisites
                if (md.prerequisites != null) {
                    CharacterStats cs = cd.toCharacterStats();
                    for (Map.Entry<String, Integer> e : md.prerequisites.entrySet()) {
                        try {
                            StatKey sk = StatKey.fromString(e.getKey());
                            if (sk.get(cs) < e.getValue()) return false;
                        } catch (IllegalArgumentException ignored) {
                            // unknown stat name — allow anyway
                        }
                    }
                }
                // Slot budget (only for slot-gated, non-free moves)
                if (md.isFreeMove) return true;
                try {
                    Move move = md.toMove();
                    MoveCategory cat = move.getCategory();
                    if (!SlotBudgetEnforcer.isSlotGated(cat)) return true;
                    CombatStats combat = cd.toCombatStats();
                    int budget = SlotBudgetEnforcer.slotBudgetFor(
                        combat, cd.toCharacterStats(), cat);
                    int used = SlotBudgetEnforcer.countUsage(
                        getAssignedMoveCategoryList(cd)).getOrDefault(cat, 0);
                    return used < budget;
                } catch (Exception e) {
                    // Can't compute category — allow (will be caught on save).
                    return true;
                }
            }

            @Override public void onAssign(String moveId) {
                if (cd.moveIds == null) cd.moveIds = new ArrayList<>();
                if (!cd.moveIds.contains(moveId)) cd.moveIds.add(moveId);
                markDirty();
                refreshDerivedPreview(cd);
                rebuildMoveAssignment(cd);
            }

            @Override public void onUnassign(String moveId) {
                if (cd.moveIds != null) cd.moveIds.remove(moveId);
                markDirty();
                refreshDerivedPreview(cd);
                rebuildMoveAssignment(cd);
            }

            @Override public String budgetSummary() {
                try {
                    CombatStats cs = cd.toCombatStats();
                    Map<MoveCategory, Integer> usage =
                        SlotBudgetEnforcer.countUsage(getAssignedMoveCategoryList(cd));
                    StringBuilder sb = new StringBuilder();
                    sb.append("Slots —  Phys: ").append(cs.getPhysicalMoveSlots())
                      .append("  Jujutsu: ").append(cs.getJujutsuTechniqueSlots())
                      .append("  CT: ").append(cs.getCursedTechniqueSlots());
                    for (MoveCategory cat : usage.keySet()) {
                        int used = usage.get(cat);
                        int budget = SlotBudgetEnforcer.slotBudgetFor(
                            cs, cd.toCharacterStats(), cat);
                        sb.append("  |  ").append(cat.name()).append(": ").append(used).append('/').append(budget);
                    }
                    return sb.toString();
                } catch (Exception e) {
                    return "(slot computation error)";
                }
            }
        }, skin);
    }

    /**
     * Look up a mastery prerequisite value from a move's prerequisite map by
     * canonical name and alias (case/underscore/whitespace-insensitive).
     * Returns null if the stat isn't a prerequisite.
     */
    private static Integer masteryPrereq(MoveData md, String canonical, String alias) {
        if (md.prerequisites == null) return null;
        String canon = normaliseStat(canonical);
        String ali   = normaliseStat(alias);
        for (Map.Entry<String, Integer> e : md.prerequisites.entrySet()) {
            String k = normaliseStat(e.getKey());
            if (k.equals(canon) || k.equals(ali)) return e.getValue();
        }
        return null;
    }

    private static String normaliseStat(String s) {
        return s == null ? "" : s.toLowerCase().replace("_", "").replace(" ", "");
    }

    /** Collect MoveCategory for every assigned, slot-gated, non-free move. */
    private List<MoveCategory> getAssignedMoveCategoryList(CharacterData cd) {
        List<MoveCategory> cats = new ArrayList<>();
        if (cd.moveIds == null) return cats;
        for (String mid : cd.moveIds) {
            MoveData md = moveRepo.findById(mid).orElse(null);
            if (md != null && !md.isFreeMove) {
                try {
                    Move move = md.toMove();
                    MoveCategory cat = move.getCategory();
                    if (SlotBudgetEnforcer.isSlotGated(cat)) {
                        cats.add(cat);
                    }
                } catch (Exception ignored) {}
            }
        }
        return cats;
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
                for (AbilityData ad : all) {
                    if (!assigned.contains(ad.id)) {
                        String sub = ad.category + (ad.sourceType != null ? " (" + ad.sourceType + ")" : "");
                        items.add(new AssignmentPanel.Item(ad.id, ad.name, sub));
                    }
                }
                return items;
            }

            @Override public List<AssignmentPanel.Item> assignedItems() {
                List<AssignmentPanel.Item> items = new ArrayList<>();
                List<String> assigned = cd.abilityIds != null ? cd.abilityIds : List.of();
                for (String aid : assigned) {
                    AbilityData ad = abilityRepo.findById(aid).orElse(null);
                    if (ad != null) {
                        String sub = ad.category + (ad.sourceType != null ? " (" + ad.sourceType + ")" : "");
                        items.add(new AssignmentPanel.Item(ad.id, ad.name, sub));
                    }
                }
                return items;
            }

            @Override public boolean canAssign(String id) { return true; } // no gating

            @Override public void onAssign(String id) {
                if (cd.abilityIds == null) cd.abilityIds = new ArrayList<>();
                if (!cd.abilityIds.contains(id)) cd.abilityIds.add(id);
                markDirty();
                rebuildAbilityAssignment(cd);
            }

            @Override public void onUnassign(String id) {
                if (cd.abilityIds != null) cd.abilityIds.remove(id);
                markDirty();
                rebuildAbilityAssignment(cd);
            }

            @Override public String budgetSummary() { return ""; }
        }, skin);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Label sectionHeader(String text) {
        Label l = new Label(text, skin, "title");
        l.setColor(skin.get("text-dim", com.badlogic.gdx.graphics.Color.class));
        l.setAlignment(Align.left);
        return l;
    }
}
