package com.jjktbf.graphics.screens.editors;

import com.jjktbf.model.character.AbilityRepository;
import com.jjktbf.model.character.StatKey;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveRepository;
import com.jjktbf.model.technique.InnateTechniqueData;
import com.jjktbf.model.technique.SkillTreeNodeData;
import com.jjktbf.model.technique.SkillTreePrerequisiteData;
import com.jjktbf.model.technique.TechniqueRepository;
import com.jjktbf.model.technique.TechniqueSkillTree;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Keeps persisted technique nodes aligned after move/ability editor saves. */
final class TechniqueTreeRepositorySync {

    private TechniqueTreeRepositorySync() { }

    static void synchronize() throws IOException {
        synchronize(null, Map.of(), null);
    }

    static void synchronize(String contentType, Map<String, String> remappedIds) throws IOException {
        synchronize(contentType, remappedIds, null);
    }

    static void synchronize(
        String contentType,
        Map<String, String> remappedIds,
        String deletedId
    ) throws IOException {
        TechniqueRepository techniques = new TechniqueRepository("data/techniques");
        MoveRepository moves = new MoveRepository("data/moves");
        AbilityRepository abilities = new AbilityRepository("data/abilities");
        techniques.load();
        moves.load();
        abilities.load();

        boolean changed = false;
        for (InnateTechniqueData technique : techniques.getAll()) {
            if (technique.skillTree != null && contentType != null) {
                int previousSize = technique.skillTree.size();
                technique.skillTree.removeIf(node -> node != null
                    && contentType.equalsIgnoreCase(node.contentType)
                    && deletedId != null && deletedId.equals(node.contentId));
                changed |= previousSize != technique.skillTree.size();
                for (SkillTreeNodeData node : technique.skillTree) {
                    if (node != null && contentType.equalsIgnoreCase(node.contentType)
                        && remappedIds.containsKey(node.contentId)) {
                        node.contentId = remappedIds.get(node.contentId);
                        changed = true;
                    }
                }
            }
            changed |= TechniqueSkillTree.synchronize(
                technique, moves.getAll(), abilities.getAll());
            changed |= synchronizeMovePrerequisites(technique, moves);
        }
        if (changed) techniques.save();
    }

    private static boolean synchronizeMovePrerequisites(
        InnateTechniqueData technique,
        MoveRepository moves
    ) {
        if (technique.skillTree == null) return false;
        boolean changed = false;
        for (SkillTreeNodeData node : technique.skillTree) {
            if (node == null || !SkillTreeNodeData.MOVE.equalsIgnoreCase(node.contentType)) continue;
            MoveData move = moves.findById(node.contentId).orElse(null);
            if (move == null) continue;
            List<SkillTreePrerequisiteData> values = valuePrerequisites(move);
            List<SkillTreePrerequisiteData> existing = node.prerequisites == null ? List.of()
                : node.prerequisites.stream()
                    .filter(Objects::nonNull)
                    .filter(requirement -> !SkillTreePrerequisiteData.NODE
                        .equalsIgnoreCase(requirement.type))
                    .toList();
            if (signatures(existing).equals(signatures(values))) continue;

            List<SkillTreePrerequisiteData> merged = new ArrayList<>();
            if (node.prerequisites != null) {
                node.prerequisites.stream()
                    .filter(Objects::nonNull)
                    .filter(requirement -> SkillTreePrerequisiteData.NODE
                        .equalsIgnoreCase(requirement.type))
                    .map(SkillTreePrerequisiteData::copy)
                    .forEach(merged::add);
            }
            merged.addAll(values);
            node.prerequisites = merged;
            changed = true;
        }
        return changed;
    }

    private static List<SkillTreePrerequisiteData> valuePrerequisites(MoveData move) {
        List<SkillTreePrerequisiteData> values = new ArrayList<>();
        if (move.prerequisites == null) return values;
        for (Map.Entry<String, Integer> entry : move.prerequisites.entrySet()) {
            try {
                StatKey stat = StatKey.fromString(entry.getKey());
                SkillTreePrerequisiteData requirement = new SkillTreePrerequisiteData();
                requirement.type = stat == StatKey.CURSED_TECHNIQUE_MASTERY
                    ? SkillTreePrerequisiteData.MASTERY : SkillTreePrerequisiteData.STAT;
                requirement.stat = stat == StatKey.CURSED_TECHNIQUE_MASTERY
                    ? null : stat.fieldName;
                requirement.minimum = entry.getValue();
                values.add(requirement);
            } catch (RuntimeException ignored) {
                // Move validation reports unknown prerequisite names.
            }
        }
        return values;
    }

    private static List<String> signatures(List<SkillTreePrerequisiteData> prerequisites) {
        return prerequisites.stream()
            .map(requirement -> String.valueOf(requirement.type) + ":"
                + String.valueOf(requirement.stat) + ":" + String.valueOf(requirement.minimum))
            .sorted()
            .toList();
    }
}
