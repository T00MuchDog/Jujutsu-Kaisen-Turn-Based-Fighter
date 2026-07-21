package com.jjktbf.model.technique;

import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.StatKey;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.repo.BaseRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Synchronization and unlock rules shared by both skill-tree editor views. */
public final class TechniqueSkillTree {

    private static final float START_X = 40f;
    private static final float START_Y = 40f;
    private static final float COLUMN_GAP = 320f;
    private static final float ROW_GAP = 150f;
    private static final int ROWS_PER_COLUMN = 3;

    private TechniqueSkillTree() { }

    /**
     * Make the authored tree contain exactly the moves and abilities currently
     * assigned to the technique. Existing node ids, positions, and requirements
     * are preserved.
     */
    public static boolean synchronize(
        InnateTechniqueData technique,
        List<MoveData> moves,
        List<AbilityData> abilities
    ) {
        if (technique == null) return false;
        if (technique.skillTree == null) technique.skillTree = new ArrayList<>();

        Map<String, MoveData> expectedMoves = new LinkedHashMap<>();
        if (moves != null) {
            for (MoveData move : moves) {
                if (move != null && techniqueMatches(technique.name, move.requiredTechniqueId)
                    && move.id != null && !move.id.isBlank()) {
                    expectedMoves.put(move.id, move);
                }
            }
        }
        Map<String, AbilityData> expectedAbilities = new LinkedHashMap<>();
        if (abilities != null) {
            for (AbilityData ability : abilities) {
                if (ability != null && "TECHNIQUE".equalsIgnoreCase(ability.sourceType)
                    && techniqueMatches(technique.name, ability.sourceValue)
                    && ability.id != null && !ability.id.isBlank()) {
                    expectedAbilities.put(ability.id, ability);
                }
            }
        }

        boolean changed = normalizeExistingNodes(technique);
        Set<String> retainedContent = new HashSet<>();
        List<SkillTreeNodeData> retained = new ArrayList<>();
        for (SkillTreeNodeData node : technique.skillTree) {
            boolean expected = node != null && node.contentId != null
                && ((SkillTreeNodeData.MOVE.equalsIgnoreCase(node.contentType)
                        && expectedMoves.containsKey(node.contentId))
                    || (SkillTreeNodeData.ABILITY.equalsIgnoreCase(node.contentType)
                        && expectedAbilities.containsKey(node.contentId)));
            String contentKey = node == null ? "" : contentKey(node.contentType, node.contentId);
            if (!expected || !retainedContent.add(contentKey)) {
                changed = true;
                continue;
            }
            retained.add(node);
        }
        if (retained.size() != technique.skillTree.size()) {
            technique.skillTree = retained;
        }

        for (MoveData move : expectedMoves.values()) {
            if (!retainedContent.contains(contentKey(SkillTreeNodeData.MOVE, move.id))) {
                technique.skillTree.add(newNode(technique, SkillTreeNodeData.MOVE, move.id,
                    statPrerequisites(move.prerequisites)));
                retainedContent.add(contentKey(SkillTreeNodeData.MOVE, move.id));
                changed = true;
            }
        }
        for (AbilityData ability : expectedAbilities.values()) {
            if (!retainedContent.contains(contentKey(SkillTreeNodeData.ABILITY, ability.id))) {
                List<SkillTreePrerequisiteData> prerequisites = new ArrayList<>();
                if (ability.masteryThreshold > 0) {
                    prerequisites.add(valuePrerequisite(
                        SkillTreePrerequisiteData.MASTERY, null, ability.masteryThreshold));
                }
                technique.skillTree.add(newNode(technique, SkillTreeNodeData.ABILITY,
                    ability.id, prerequisites));
                retainedContent.add(contentKey(SkillTreeNodeData.ABILITY, ability.id));
                changed = true;
            }
        }

        Set<String> validNodeIds = technique.skillTree.stream()
            .map(node -> node.id)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (SkillTreeNodeData node : technique.skillTree) {
            if (node.prerequisites == null) {
                node.prerequisites = new ArrayList<>();
                changed = true;
                continue;
            }
            int before = node.prerequisites.size();
            node.prerequisites.removeIf(requirement -> requirement == null
                || (SkillTreePrerequisiteData.NODE.equalsIgnoreCase(requirement.type)
                    && !validNodeIds.contains(requirement.nodeId)));
            if (node.prerequisites.size() != before) changed = true;
        }
        return changed;
    }

    /** True when all authored prerequisites are currently satisfied. */
    public static boolean isUnlocked(
        InnateTechniqueData technique,
        SkillTreeNodeData node,
        CharacterData character
    ) {
        return unmetPrerequisites(technique, node, character).isEmpty();
    }

    /** Player-facing reasons why a node cannot currently be activated. */
    public static List<String> unmetPrerequisites(
        InnateTechniqueData technique,
        SkillTreeNodeData node,
        CharacterData character
    ) {
        return unmetPrerequisites(technique, node, character, new HashSet<>());
    }

    private static List<String> unmetPrerequisites(
        InnateTechniqueData technique,
        SkillTreeNodeData node,
        CharacterData character,
        Set<String> visiting
    ) {
        if (technique == null || node == null || character == null) return List.of("Unavailable");
        if (node.id == null || !visiting.add(node.id)) {
            return List.of("Cyclic node prerequisite");
        }
        List<String> unmet = new ArrayList<>();
        if (node.prerequisites == null) return unmet;
        for (SkillTreePrerequisiteData requirement : node.prerequisites) {
            if (requirement == null || requirement.type == null) continue;
            if (SkillTreePrerequisiteData.MASTERY.equalsIgnoreCase(requirement.type)) {
                int minimum = requirement.minimum == null ? 0 : requirement.minimum;
                if (character.cursedTechniqueMastery < minimum) {
                    unmet.add("Needs Cursed Technique Mastery >= " + minimum);
                }
            } else if (SkillTreePrerequisiteData.STAT.equalsIgnoreCase(requirement.type)) {
                try {
                    StatKey stat = StatKey.fromString(requirement.stat);
                    int minimum = requirement.minimum == null ? 0 : requirement.minimum;
                    if (stat.get(character) < minimum) {
                        unmet.add("Needs " + stat.label + " >= " + minimum);
                    }
                } catch (RuntimeException ex) {
                    unmet.add("Unknown stat prerequisite");
                }
            } else if (SkillTreePrerequisiteData.NODE.equalsIgnoreCase(requirement.type)) {
                SkillTreeNodeData prerequisiteNode = nodeById(technique, requirement.nodeId);
                Set<String> branch = new HashSet<>(visiting);
                if (prerequisiteNode == null || !isActive(prerequisiteNode, character)
                    || !unmetPrerequisites(technique, prerequisiteNode, character, branch).isEmpty()) {
                    unmet.add("Needs " + nodeLabel(prerequisiteNode, requirement.nodeId));
                }
            }
        }
        return unmet;
    }

    public static boolean isActive(SkillTreeNodeData node, CharacterData character) {
        if (node == null || character == null || node.contentId == null) return false;
        List<String> ids = SkillTreeNodeData.MOVE.equalsIgnoreCase(node.contentType)
            ? character.moveIds : character.abilityIds;
        return ids != null && ids.contains(node.contentId);
    }

    public static void setActive(SkillTreeNodeData node, CharacterData character, boolean active) {
        if (node == null || character == null || node.contentId == null) return;
        if (SkillTreeNodeData.MOVE.equalsIgnoreCase(node.contentType)) {
            if (character.moveIds == null) character.moveIds = new ArrayList<>();
            updateSelection(character.moveIds, node.contentId, active);
        } else if (SkillTreeNodeData.ABILITY.equalsIgnoreCase(node.contentType)) {
            if (character.abilityIds == null) character.abilityIds = new ArrayList<>();
            updateSelection(character.abilityIds, node.contentId, active);
        }
    }

    /** Remove active descendants whose node prerequisites are no longer met. */
    public static boolean pruneLockedSelections(
        InnateTechniqueData technique,
        CharacterData character
    ) {
        if (technique == null || technique.skillTree == null || character == null) return false;
        boolean anyChanged = false;
        boolean changed;
        do {
            changed = false;
            for (SkillTreeNodeData node : technique.skillTree) {
                if (isActive(node, character) && !isUnlocked(technique, node, character)) {
                    setActive(node, character, false);
                    changed = true;
                    anyChanged = true;
                }
            }
        } while (changed);
        return anyChanged;
    }

    public static SkillTreeNodeData nodeById(InnateTechniqueData technique, String nodeId) {
        if (technique == null || technique.skillTree == null || nodeId == null) return null;
        return technique.skillTree.stream()
            .filter(node -> node != null && nodeId.equals(node.id))
            .findFirst().orElse(null);
    }

    public static SkillTreeNodeData nodeForContent(
        InnateTechniqueData technique,
        String contentType,
        String contentId
    ) {
        if (technique == null || technique.skillTree == null || contentId == null) return null;
        return technique.skillTree.stream()
            .filter(node -> node != null && contentId.equals(node.contentId)
                && contentType.equalsIgnoreCase(node.contentType))
            .findFirst().orElse(null);
    }

    public static InnateTechniqueData techniqueByName(
        List<InnateTechniqueData> techniques,
        String name
    ) {
        if (techniques == null || name == null) return null;
        return techniques.stream()
            .filter(technique -> technique != null && technique.name != null
                && name.equalsIgnoreCase(technique.name))
            .findFirst().orElse(null);
    }

    private static boolean normalizeExistingNodes(InnateTechniqueData technique) {
        boolean changed = false;
        Set<String> usedIds = new HashSet<>();
        for (SkillTreeNodeData node : technique.skillTree) {
            if (node == null) continue;
            if (node.id == null || node.id.isBlank() || !usedIds.add(node.id)) {
                node.id = nextNodeId(technique, usedIds);
                usedIds.add(node.id);
                changed = true;
            }
            if (node.prerequisites == null) {
                node.prerequisites = new ArrayList<>();
                changed = true;
            }
        }
        return changed;
    }

    private static SkillTreeNodeData newNode(
        InnateTechniqueData technique,
        String contentType,
        String contentId,
        List<SkillTreePrerequisiteData> prerequisites
    ) {
        int index = firstFreeLayoutSlot(technique);
        SkillTreeNodeData node = new SkillTreeNodeData();
        node.id = nextNodeId(technique, Set.of());
        node.contentType = contentType;
        node.contentId = contentId;
        node.x = START_X + (index / ROWS_PER_COLUMN) * COLUMN_GAP;
        node.y = START_Y + (index % ROWS_PER_COLUMN) * ROW_GAP;
        node.prerequisites = prerequisites;
        return node;
    }

    private static int firstFreeLayoutSlot(InnateTechniqueData technique) {
        for (int index = 0; ; index++) {
            float x = START_X + (index / ROWS_PER_COLUMN) * COLUMN_GAP;
            float y = START_Y + (index % ROWS_PER_COLUMN) * ROW_GAP;
            boolean occupied = technique.skillTree.stream()
                .filter(Objects::nonNull)
                .anyMatch(node -> Math.abs(node.x - x) < 1f && Math.abs(node.y - y) < 1f);
            if (!occupied) return index;
        }
    }

    private static String nextNodeId(InnateTechniqueData technique, Set<String> extraUsed) {
        Set<String> used = new HashSet<>(extraUsed);
        if (technique.skillTree != null) {
            technique.skillTree.stream().filter(Objects::nonNull)
                .map(node -> node.id).filter(Objects::nonNull).forEach(used::add);
        }
        for (int index = 0; ; index++) {
            String candidate = "node-" + BaseRepository.formatId(index);
            if (!used.contains(candidate)) return candidate;
        }
    }

    private static List<SkillTreePrerequisiteData> statPrerequisites(Map<String, Integer> stats) {
        List<SkillTreePrerequisiteData> prerequisites = new ArrayList<>();
        if (stats == null) return prerequisites;
        for (Map.Entry<String, Integer> entry : stats.entrySet()) {
            try {
                StatKey stat = StatKey.fromString(entry.getKey());
                String type = stat == StatKey.CURSED_TECHNIQUE_MASTERY
                    ? SkillTreePrerequisiteData.MASTERY : SkillTreePrerequisiteData.STAT;
                prerequisites.add(valuePrerequisite(type,
                    type.equals(SkillTreePrerequisiteData.STAT) ? stat.fieldName : null,
                    entry.getValue()));
            } catch (IllegalArgumentException ignored) {
                // The move editor rejects unknown stat names; do not create an unusable node rule.
            }
        }
        return prerequisites;
    }

    private static SkillTreePrerequisiteData valuePrerequisite(
        String type,
        String stat,
        Integer minimum
    ) {
        SkillTreePrerequisiteData requirement = new SkillTreePrerequisiteData();
        requirement.type = type;
        requirement.stat = stat;
        requirement.minimum = minimum;
        return requirement;
    }

    private static void updateSelection(List<String> ids, String id, boolean selected) {
        if (selected) {
            if (!ids.contains(id)) ids.add(id);
        } else {
            ids.removeIf(id::equals);
        }
    }

    private static String nodeLabel(SkillTreeNodeData node, String fallback) {
        if (node == null) return "node " + String.valueOf(fallback);
        return node.contentType.toLowerCase(Locale.ROOT) + " " + node.contentId;
    }

    private static String contentKey(String type, String id) {
        return String.valueOf(type).toUpperCase(Locale.ROOT) + ":" + id;
    }

    private static boolean techniqueMatches(String techniqueName, String reference) {
        return techniqueName != null && reference != null
            && techniqueName.equalsIgnoreCase(reference);
    }
}
