package com.jjktbf.graphics.screens.editors;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.Align;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.ui.editor.EditorScreenBase;
import com.jjktbf.graphics.ui.editor.EffectListEditor;
import com.jjktbf.graphics.ui.editor.EnumSelectBox;
import com.jjktbf.graphics.ui.editor.ValidationResult;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.AbilityRepository;
import com.jjktbf.model.character.AbilityTrigger;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Graphical CRUD editor for {@link AbilityData}. Master-detail layout with:
 *   - Name / flavour / mechanic text
 *   - Category (PASSIVE/ACTIVE)
 *   - Source type + value (conditional fields)
 *   - Active sub-fields (queued move-id, trigger condition + threshold)
 *   - Effect list editor (the 22 AbilityEffectType values)
 *
 * Save validates name, category consistency, and that each effect's type
 * resolves via {@link AbilityEffectType#valueOf(String)}.
 */
public class AbilityEditorScreen extends EditorScreenBase<AbilityData> {

    private final AbilityRepository repo;
    private final MoveRepository     moveRepo;

    // Handles for conditional sub-sections
    private Container<Actor> sourceValueContainer;
    private Container<Actor> activeSubContainer;
    private Label moveListHint;

    public AbilityEditorScreen(JJKGame game, AssetLoader assets) {
        super(game, assets);
        repo     = new AbilityRepository("data/abilities");
        moveRepo = new MoveRepository("data/moves");
    }

    // =========================================================================
    // Abstract hooks
    // =========================================================================

    @Override protected String title() { return "ABILITY EDITOR"; }

    @Override protected AbilityData newDraft() {
        AbilityData a = new AbilityData();
        a.name         = "New Ability";
        a.flavourText  = "";
        a.mechanicText = "";
        a.category     = "PASSIVE";
        a.sourceType   = "CHARACTER";
        a.sourceValue  = null;
        a.effects      = new ArrayList<>();
        a.activeSubType     = null;
        a.activeMoveId      = null;
        a.triggerCondition  = null;
        a.triggerThreshold  = 0;
        return a;
    }

    @Override protected AbilityData draftFromRecord(AbilityData stored) {
        AbilityData d = new AbilityData();
        d.id              = stored.id;
        d.name            = stored.name;
        d.flavourText     = stored.flavourText;
        d.mechanicText    = stored.mechanicText;
        d.category        = stored.category;
        d.sourceType      = stored.sourceType;
        d.sourceValue     = stored.sourceValue;
        d.effects         = stored.effects != null
            ? new ArrayList<>(stored.effects) : new ArrayList<>();
        d.activeSubType    = stored.activeSubType;
        d.activeMoveId     = stored.activeMoveId;
        d.triggerCondition = stored.triggerCondition;
        d.triggerThreshold = stored.triggerThreshold;
        return d;
    }

    @Override protected String idOf(AbilityData r) { return r.id; }

    @Override protected String nextId() { return repo.nextId(); }

    @Override protected void stampNewId(AbilityData draft) { draft.id = repo.nextId(); }

    @Override protected String listLabel(AbilityData r) {
        String cat = r.category != null ? " (" + r.category + ")" : "";
        return r.name + cat;
    }

    @Override protected boolean isNewDraft(AbilityData draft) {
        return draft.id == null || draft.id.isEmpty()
            || repo.findById(draft.id).isEmpty();
    }

    @Override
    protected void reloadRecords() throws IOException {
        repo.load();
        moveRepo.load();
        records.clear();
        records.addAll(repo.getAll());
    }

    @Override
    protected ValidationResult validateAndSave(AbilityData d) {
        if (d.name == null || d.name.trim().isEmpty()) {
            return ValidationResult.error("Name is required.");
        }
        if (d.category == null
            || (!d.category.equalsIgnoreCase("PASSIVE") && !d.category.equalsIgnoreCase("ACTIVE"))) {
            return ValidationResult.error("Category must be PASSIVE or ACTIVE.");
        }
        if (d.isActive()) {
            if (d.activeSubType == null
                || (!d.activeSubType.equalsIgnoreCase("QUEUED")
                 && !d.activeSubType.equalsIgnoreCase("TRIGGERED"))) {
                return ValidationResult.error("Active abilities need an active sub-type (QUEUED/TRIGGERED).");
            }
            if (d.isTriggered() && d.triggerCondition == null) {
                return ValidationResult.error("Triggered abilities need a trigger condition.");
            }
        }
        // Validate every effect's type resolves.
        if (d.effects != null) {
            for (AbilityEffectData e : d.effects) {
                try { AbilityEffectType.valueOf(e.type); }
                catch (Exception ex) {
                    return ValidationResult.error("Effect has invalid type: " + e.type);
                }
            }
        }
        try {
            if (isNewDraft(d)) {
                d.id = null;
                repo.add(d);
            } else {
                repo.update(d);
            }
            repo.save();
        } catch (Exception e) {
            return ValidationResult.error("Save failed: " + e.getMessage());
        }
        return ValidationResult.ok("Saved \"" + d.name + "\".");
    }

    @Override
    protected ValidationResult delete(String id) {
        try {
            repo.delete(id);
            repo.save();
            return ValidationResult.ok("Deleted.");
        } catch (Exception e) {
            return ValidationResult.error("Delete failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // Detail form
    // =========================================================================

    @Override
    protected Actor buildDetailForm(AbilityData d) {
        Table form = new Table(skin);
        form.defaults().left().pad(4);
        form.pad(8);

        // ── Identity ───────────────────────────────────────────────────────────
        form.add(sectionHeader("IDENTITY")).growX().colspan(2).row();
        form.add(idBadge(d.id)).colspan(2).padBottom(2).row();
        form.add(labelledField("Name", d.name,
                s -> { d.name = s; })).growX().colspan(2).row();
        form.add(labelledField("Flavour Text", d.flavourText,
                s -> { d.flavourText = s; })).growX().colspan(2).row();
        form.add(labelledField("Mechanic Text", d.mechanicText,
                s -> { d.mechanicText = s; })).growX().colspan(2).row();

        // ── Category ───────────────────────────────────────────────────────────
        form.add(sectionHeader("CATEGORY")).growX().colspan(2).row();
        form.add(new Label("Category", skin)).padRight(8);
        form.add(new EnumSelectBox<>(CategoryEnum.class, d.category, false,
                s -> { d.category = s; refreshConditionalSections(d); }, skin)).growX().row();

        // ── Source ──────────────────────────────────────────────────────────────
        form.add(sectionHeader("SOURCE")).growX().colspan(2).row();
        form.add(new Label("Source Type", skin)).padRight(8);
        form.add(new EnumSelectBox<>(SourceTypeEnum.class, d.sourceType, false,
                s -> { d.sourceType = s; refreshConditionalSections(d); }, skin)).growX().row();
        sourceValueContainer = new Container<>();
        sourceValueContainer.setActor(buildSourceValue(d));
        form.add(sourceValueContainer).growX().colspan(2).row();

        // Mastery threshold — when this ability is technique-sourced
        // (sourceType=TECHNIQUE), this is the cursed-technique-mastery value at
        // which the technique auto-grants it. Ignored for other source types.
        form.add(labelledIntField("Mastery Threshold (TECHNIQUE abilities)", d.masteryThreshold, 0, 999,
                v -> { d.masteryThreshold = v; })).growX().colspan(2).row();

        // ── Active sub-fields (only when ACTIVE) ────────────────────────────────
        form.add(sectionHeader("ACTIVE SETTINGS")).growX().colspan(2).row();
        activeSubContainer = new Container<>();
        activeSubContainer.setActor(buildActiveSub(d));
        form.add(activeSubContainer).growX().colspan(2).row();

        // Move list hint (useful when source/active references a move id)
        moveListHint = new Label("", skin, "small");
        moveListHint.setColor(skin.get("text-dim", com.badlogic.gdx.graphics.Color.class));
        form.add(moveListHint).growX().colspan(2).row();
        refreshMoveHint();

        // ── Effects ──────────────────────────────────────────────────────────────
        form.add(sectionHeader("EFFECTS")).growX().colspan(2).row();
        form.add(new EffectListEditor(d.effects, this::markDirty, this::rebuildDetail, skin))
            .growX().colspan(2).row();

        return form;
    }

    // =========================================================================
    // Conditional sub-sections
    // =========================================================================

    private Actor buildSourceValue(AbilityData d) {
        Table t = new Table(skin);
        t.defaults().left().pad(4);
        String st = d.sourceType == null ? "CHARACTER" : d.sourceType.toUpperCase();
        switch (st) {
            case "TECHNIQUE":
                t.add(labelledField("Technique Name", d.sourceValue,
                        s -> { d.sourceValue = s; })).growX().row();
                t.add(hint("(match a character's innate technique name)")).row();
                break;
            case "MOVE":
                t.add(labelledField("Move ID (6-digit)", d.sourceValue,
                        s -> { d.sourceValue = s; })).growX().row();
                t.add(hint("(see move list below)")).row();
                break;
            case "STAT_THRESHOLD":
                t.add(labelledField("Threshold (e.g. cursedTechniqueMastery>=200)",
                        d.sourceValue,
                        s -> { d.sourceValue = s; })).growX().row();
                t.add(hint("(format: stat>=value)")).row();
                break;
            case "ABILITY":
                t.add(labelledField("Ability ID or Name", d.sourceValue,
                        s -> { d.sourceValue = s; })).growX().row();
                t.add(hint("(see ability list on the left)")).row();
                break;
            case "CHARACTER":
            default:
                t.add(hint("(no source value needed for CHARACTER source)")).row();
                d.sourceValue = null;
                break;
        }
        return t;
    }

    private Actor buildActiveSub(AbilityData d) {
        Table t = new Table(skin);
        t.defaults().left().pad(4);
        if (!d.isActive()) {
            t.add(hint("(active settings apply only to ACTIVE abilities)")).row();
            return t;
        }
        // Sub-type
        t.add(new Label("Active Sub-type", skin)).padRight(8);
        t.add(new EnumSelectBox<>(ActiveSubEnum.class, d.activeSubType, true,
                s -> { d.activeSubType = s; refreshConditionalSections(d); }, skin)).growX().row();

        if (d.isQueued()) {
            t.add(labelledField("Active Move ID (queued move)", d.activeMoveId,
                    s -> { d.activeMoveId = s; })).growX().row();
            t.add(hint("(see move list below)")).row();
        } else if (d.isTriggered()) {
            t.add(new Label("Trigger Condition", skin)).padRight(8);
            t.add(new EnumSelectBox<>(AbilityTrigger.class, d.triggerCondition, false,
                    s -> { d.triggerCondition = s; refreshConditionalSections(d); }, skin)).growX().row();

            if ("ON_HP_BELOW".equalsIgnoreCase(d.triggerCondition)) {
                t.add(labelledIntField("Trigger Threshold (% HP)", d.triggerThreshold, 0, 100,
                        v -> { d.triggerThreshold = v; })).growX().row();
            }
        }
        return t;
    }

    private void refreshMoveHint() {
        if (moveListHint == null) return;
        List<MoveData> moves = moveRepo.getAll();
        StringBuilder sb = new StringBuilder("Move IDs: ");
        int n = 0;
        for (MoveData md : moves) {
            if (n > 0) sb.append(", ");
            sb.append(md.id).append("=").append(md.name);
            if (++n >= 8) { sb.append(", ..."); break; }
        }
        if (n == 0) sb.append("(none — create moves first)");
        moveListHint.setText(sb.toString());
    }

    private void refreshConditionalSections(AbilityData d) {
        markDirty();
        if (sourceValueContainer != null) sourceValueContainer.setActor(buildSourceValue(d));
        if (activeSubContainer  != null) activeSubContainer.setActor(buildActiveSub(d));
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

    private Label hint(String text) {
        Label l = new Label(text, skin, "small");
        l.setColor(skin.get("text-dim", com.badlogic.gdx.graphics.Color.class));
        return l;
    }

    // ── Placeholder enums to drive the String-backed category/source/sub fields ─
    // These exist only so EnumSelectBox has a typed enum to list; the actual
    // stored values are the String fields on AbilityData.
    private enum CategoryEnum   { PASSIVE, ACTIVE }
    private enum SourceTypeEnum { CHARACTER, TECHNIQUE, MOVE, STAT_THRESHOLD, ABILITY }
    private enum ActiveSubEnum  { QUEUED, TRIGGERED }
}
