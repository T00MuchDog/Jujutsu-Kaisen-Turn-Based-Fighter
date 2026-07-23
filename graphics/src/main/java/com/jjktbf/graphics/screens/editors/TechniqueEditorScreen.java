package com.jjktbf.graphics.screens.editors;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.ui.editor.AxisLockedScrollPane;
import com.jjktbf.graphics.ui.editor.EditorScreenBase;
import com.jjktbf.graphics.ui.editor.SkillTreeCanvas;
import com.jjktbf.graphics.ui.editor.ValidationResult;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.AbilityRepository;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterRepository;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveRepository;
import com.jjktbf.model.technique.InnateTechniqueData;
import com.jjktbf.model.technique.SkillTreeNodeData;
import com.jjktbf.model.technique.SkillTreePrerequisiteData;
import com.jjktbf.model.technique.TechniqueRepository;
import com.jjktbf.model.technique.TechniqueSkillTree;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Technique identity editor and authoring surface for its discovered technique tree. */
public class TechniqueEditorScreen extends EditorScreenBase<InnateTechniqueData> {

    private final TechniqueRepository repo;
    private final MoveRepository moveRepo;
    private final AbilityRepository abilityRepo;

    public TechniqueEditorScreen(JJKGame game, AssetLoader assets) {
        super(game, assets);
        repo = new TechniqueRepository("data/techniques");
        moveRepo = new MoveRepository("data/moves");
        abilityRepo = new AbilityRepository("data/abilities");
    }

    @Override protected String title() { return "TECHNIQUE EDITOR"; }

    @Override protected InnateTechniqueData newDraft() {
        InnateTechniqueData technique = new InnateTechniqueData();
        technique.name = "New Technique";
        technique.description = "";
        technique.skillTree = new ArrayList<>();
        return technique;
    }

    @Override protected InnateTechniqueData draftFromRecord(InnateTechniqueData stored) {
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

    @Override protected String idOf(InnateTechniqueData record) { return record.id; }
    @Override protected String nextId() { return repo.nextId(); }
    @Override protected void stampNewId(InnateTechniqueData draft) { draft.id = repo.nextId(); }

    @Override protected String listLabel(InnateTechniqueData record) {
        return record.name == null || record.name.isEmpty() ? "(unnamed)" : record.name;
    }

    @Override protected boolean isNewDraft(InnateTechniqueData draft) {
        return draft.id == null || draft.id.isEmpty() || repo.findById(draft.id).isEmpty();
    }

    @Override
    protected void reloadRecords() throws IOException {
        repo.load();
        moveRepo.load();
        abilityRepo.load();
        records.clear();
        records.addAll(repo.getAll());
    }

    @Override
    protected ValidationResult validateAndSave(InnateTechniqueData technique) {
        if (technique.name == null || technique.name.trim().isEmpty()) {
            return ValidationResult.error("Name is required.");
        }
        technique.name = technique.name.trim();
        for (InnateTechniqueData existing : repo.getAll()) {
            if (technique.id != null && technique.id.equals(existing.id)) continue;
            if (existing.name != null && technique.name.equalsIgnoreCase(existing.name)) {
                return ValidationResult.error(
                    "A technique named \"" + existing.name + "\" already exists.");
            }
        }

        String previousName = repo.findById(technique.id)
            .map(existing -> existing.name).orElse(null);
        CharacterRepository characterRepo = null;
        boolean renamed = previousName != null && !previousName.equals(technique.name);
        try {
            if (renamed) {
                characterRepo = new CharacterRepository("data/characters");
                characterRepo.load();
                rewriteTechniqueReferences(previousName, technique.name, characterRepo);
            }
            TechniqueSkillTree.synchronize(technique, moveRepo.getAll(), abilityRepo.getAll());
            applyAuthoredPrerequisites(technique);

            if (isNewDraft(technique)) {
                technique.id = null;
                repo.add(technique);
            } else {
                repo.update(technique);
            }
            repo.save();
            moveRepo.save();
            abilityRepo.save();
            if (characterRepo != null) characterRepo.save();
        } catch (Exception exception) {
            return ValidationResult.error("Save failed: " + exception.getMessage());
        }
        return ValidationResult.ok("Saved \"" + technique.name + "\".");
    }

    @Override
    protected ValidationResult delete(String id) {
        InnateTechniqueData technique = repo.findById(id).orElse(null);
        if (technique == null) return ValidationResult.error("Technique no longer exists.");
        boolean referencedByMove = moveRepo.getAll().stream().anyMatch(move ->
            move.requiredTechniqueId != null
                && move.requiredTechniqueId.equalsIgnoreCase(technique.name));
        boolean referencedByAbility = abilityRepo.getAll().stream().anyMatch(ability ->
            "TECHNIQUE".equalsIgnoreCase(ability.sourceType)
                && ability.sourceValue != null
                && ability.sourceValue.equalsIgnoreCase(technique.name));
        if (referencedByMove || referencedByAbility) {
            return ValidationResult.error(
                "Cannot delete a technique while moves or abilities belong to its technique tree.");
        }
        try {
            repo.delete(id);
            repo.save();
            return ValidationResult.ok("Deleted.");
        } catch (Exception exception) {
            return ValidationResult.error("Delete failed: " + exception.getMessage());
        }
    }

    @Override
    protected Actor buildDetailForm(InnateTechniqueData technique) {
        TechniqueSkillTree.synchronize(technique, moveRepo.getAll(), abilityRepo.getAll());
        Table form = formRoot();

        Table identity = formSection(form, "NAME");
        identity.add(idBadge(technique.id)).left().row();
        identity.add(labelledField("Name", technique.name, value -> technique.name = value))
            .growX().row();
        identity.add(labelledField("Description", technique.description,
            value -> technique.description = value)).growX().row();

        Table tree = formSection(form, "TECHNIQUE TREE");
        tree.add(formHint(
            "Drag nodes with left click. Right click a node to attach it or edit prerequisites."))
            .left().row();
        SkillTreeCanvas canvas = new SkillTreeCanvas(
            technique,
            moveRepo.getAll(),
            abilityRepo.getAll(),
            null,
            true,
            this::markDirty,
            message -> setStatus(message, false),
            null,
            skin);
        ScrollPane scroll = new AxisLockedScrollPane(canvas, skin);
        scroll.setFadeScrollBars(false);
        scroll.setFlickScroll(false);
        scroll.setScrollingDisabled(false, true);
        tree.add(scroll).height(SkillTreeCanvas.VIEW_HEIGHT + 24f).growX().row();
        return form;
    }

    private void rewriteTechniqueReferences(
        String previousName,
        String newName,
        CharacterRepository characterRepo
    ) {
        for (MoveData move : moveRepo.getAll()) {
            if (move.requiredTechniqueId != null
                && move.requiredTechniqueId.equalsIgnoreCase(previousName)) {
                move.requiredTechniqueId = newName;
            }
        }
        for (AbilityData ability : abilityRepo.getAll()) {
            if ("TECHNIQUE".equalsIgnoreCase(ability.sourceType)
                && ability.sourceValue != null
                && ability.sourceValue.equalsIgnoreCase(previousName)) {
                ability.sourceValue = newName;
            }
            if (ability.effects == null) continue;
            ability.effects.stream()
                .filter(java.util.Objects::nonNull)
                .filter(effect -> AbilityEffectType.UNLOCK_TECHNIQUE.name().equalsIgnoreCase(effect.type))
                .filter(effect -> effect.stringValue != null
                    && effect.stringValue.equalsIgnoreCase(previousName))
                .forEach(effect -> effect.stringValue = newName);
        }
        for (CharacterData character : characterRepo.getAll()) {
            if (character.innateTechniqueName != null
                && character.innateTechniqueName.equalsIgnoreCase(previousName)) {
                character.innateTechniqueName = newName;
            }
        }
    }

    private void applyAuthoredPrerequisites(InnateTechniqueData technique) {
        if (technique.skillTree == null) return;
        for (SkillTreeNodeData node : technique.skillTree) {
            if (node == null) continue;
            if (SkillTreeNodeData.MOVE.equalsIgnoreCase(node.contentType)) {
                MoveData move = moveRepo.findById(node.contentId).orElse(null);
                if (move == null) continue;
                Map<String, Integer> prerequisites = new LinkedHashMap<>();
                if (node.prerequisites != null) {
                    for (SkillTreePrerequisiteData requirement : node.prerequisites) {
                        if (requirement == null || requirement.minimum == null) continue;
                        String stat = null;
                        if (SkillTreePrerequisiteData.MASTERY.equalsIgnoreCase(requirement.type)) {
                            stat = "cursedTechniqueMastery";
                        } else if (SkillTreePrerequisiteData.STAT.equalsIgnoreCase(requirement.type)) {
                            stat = requirement.stat;
                        }
                        if (stat != null) prerequisites.merge(
                            stat, requirement.minimum, Math::max);
                    }
                }
                if (move.tags != null && move.tags.contains("INNATE_TECHNIQUE")) {
                    prerequisites.putIfAbsent("cursedTechniqueMastery", 0);
                }
                move.prerequisites = prerequisites.isEmpty() ? null : prerequisites;
            } else if (SkillTreeNodeData.ABILITY.equalsIgnoreCase(node.contentType)) {
                AbilityData ability = abilityRepo.findById(node.contentId).orElse(null);
                if (ability == null) continue;
                ability.masteryThreshold = node.prerequisites == null ? 0
                    : node.prerequisites.stream()
                        .filter(java.util.Objects::nonNull)
                        .filter(requirement -> SkillTreePrerequisiteData.MASTERY
                            .equalsIgnoreCase(requirement.type))
                        .map(requirement -> requirement.minimum)
                        .filter(java.util.Objects::nonNull)
                        .mapToInt(Integer::intValue)
                        .max().orElse(0);
            }
        }
    }
}
