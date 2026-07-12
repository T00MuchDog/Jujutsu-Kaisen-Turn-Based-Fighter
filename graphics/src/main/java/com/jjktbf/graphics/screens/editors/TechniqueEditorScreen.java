package com.jjktbf.graphics.screens.editors;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.ui.editor.EditorScreenBase;
import com.jjktbf.graphics.ui.editor.ValidationResult;
import com.jjktbf.model.technique.InnateTechniqueData;
import com.jjktbf.model.technique.TechniqueRepository;

import java.io.IOException;

/**
 * Graphical CRUD editor for {@link InnateTechniqueData}. Master-detail layout,
 * mouse + keyboard driven, pixel-art themed.
 *
 * <p>A technique owns only its identity: a {@code name} (matched
 * case-insensitively against {@code MoveData.requiredTechniqueId} and
 * {@code CharacterData.innateTechniqueName}) and a {@code description}. Its
 * move/ability progression is <b>discovered</b> at runtime, not stored — so this
 * editor intentionally exposes only those two fields.
 *
 * <p>The detail form additionally lists (read-only) the moves and abilities that
 * currently reference this technique's name, so the author can see what the
 * progression looks like without leaving the editor.
 */
public class TechniqueEditorScreen extends EditorScreenBase<InnateTechniqueData> {

    private final TechniqueRepository repo;

    public TechniqueEditorScreen(JJKGame game, AssetLoader assets) {
        super(game, assets);
        repo = new TechniqueRepository("data/techniques");
    }

    // =========================================================================
    // Abstract hooks
    // =========================================================================

    @Override protected String title() { return "TECHNIQUE EDITOR"; }

    @Override protected InnateTechniqueData newDraft() {
        InnateTechniqueData t = new InnateTechniqueData();
        t.name        = "New Technique";
        t.description = "";
        return t;
    }

    @Override protected InnateTechniqueData draftFromRecord(InnateTechniqueData stored) {
        InnateTechniqueData d = new InnateTechniqueData();
        d.id          = stored.id;
        d.name        = stored.name;
        d.description = stored.description;
        return d;
    }

    @Override protected String idOf(InnateTechniqueData r) { return r.id; }

    @Override protected String nextId() { return repo.nextId(); }

    @Override protected void stampNewId(InnateTechniqueData draft) { draft.id = repo.nextId(); }

    @Override protected String listLabel(InnateTechniqueData r) {
        return r.name == null || r.name.isEmpty() ? "(unnamed)" : r.name;
    }

    @Override protected boolean isNewDraft(InnateTechniqueData draft) {
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
    protected ValidationResult validateAndSave(InnateTechniqueData d) {
        if (d.name == null || d.name.trim().isEmpty()) {
            return ValidationResult.error("Name is required.");
        }
        // Guard against duplicate names (case-insensitive) — two techniques with
        // the same name would make move/character technique references ambiguous.
        for (InnateTechniqueData existing : repo.getAll()) {
            if (d.id != null && d.id.equals(existing.id)) continue; // skip self on edit
            if (d.name.equalsIgnoreCase(existing.name)) {
                return ValidationResult.error("A technique named \"" + existing.name + "\" already exists.");
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
    protected Actor buildDetailForm(InnateTechniqueData d) {
        Table form = formRoot();

        Table identity = formSection(form, "IDENTITY");
        identity.add(idBadge(d.id)).left().row();
        identity.add(labelledField("Name", d.name,
                s -> { d.name = s; })).growX().row();
        identity.add(labelledField("Description", d.description,
                s -> { d.description = s; })).growX().row();

        Table refs = formSection(form, "REFERENCING MOVES & ABILITIES");
        refs.add(formHint("(discovered at runtime from the move/ability editors — read-only here)"))
            .left().row();

        // Read-only list of moves that reference this technique. Computed live
        // from a fresh MoveRepository so editor-side move edits are reflected.
        int moveCount = 0;
        try {
            com.jjktbf.model.move.MoveRepository moveRepo = new com.jjktbf.model.move.MoveRepository("data/moves");
            moveRepo.load();
            for (com.jjktbf.model.move.MoveData md : moveRepo.getAll()) {
                if (md.requiredTechniqueId != null && md.requiredTechniqueId.equalsIgnoreCase(d.name)) {
                    refs.add(referenceLabel(md.name + "  (move)")).left().row();
                    moveCount++;
                }
            }
        } catch (IOException e) {
            refs.add(formHint("(could not load moves: " + e.getMessage() + ")")).left().row();
        }
        if (moveCount == 0) {
            refs.add(formHint("(no moves reference this technique yet)")).left().row();
        }

        // Read-only list of abilities sourced from this technique.
        int abilityCount = 0;
        try {
            com.jjktbf.model.character.AbilityRepository abilityRepo =
                new com.jjktbf.model.character.AbilityRepository("data/abilities");
            abilityRepo.load();
            for (com.jjktbf.model.character.AbilityData ad : abilityRepo.getAll()) {
                if ("TECHNIQUE".equalsIgnoreCase(ad.sourceType)
                    && ad.sourceValue != null && ad.sourceValue.equalsIgnoreCase(d.name)) {
                    refs.add(referenceLabel(ad.name + "  (ability, mastery ≥ " + ad.masteryThreshold + ")"))
                        .left().row();
                    abilityCount++;
                }
            }
        } catch (IOException e) {
            refs.add(formHint("(could not load abilities: " + e.getMessage() + ")")).left().row();
        }
        if (abilityCount == 0) {
            refs.add(formHint("(no abilities reference this technique yet)")).left().row();
        }

        return form;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Label referenceLabel(String text) {
        Label l = new Label("• " + text, skin, "small");
        l.setColor(skin.get("text-dark", com.badlogic.gdx.graphics.Color.class));
        return l;
    }
}
