package com.jjktbf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityResolver;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.technique.InnateTechniqueData;
import com.jjktbf.model.technique.SkillTreeNodeData;
import com.jjktbf.model.technique.SkillTreePrerequisiteData;
import com.jjktbf.model.technique.TechniqueSkillTree;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TechniqueSkillTreeTest {

    @Test
    void synchronizationDiscoversMembersAndMigratesLegacyPrerequisites() {
        InnateTechniqueData technique = technique();
        MoveData move = move("M1", "Miracles");
        move.prerequisites = Map.of("strength", 90, "cursedTechniqueMastery", 40);
        AbilityData ability = ability("A1", "Miracles");
        ability.masteryThreshold = 60;

        assertTrue(TechniqueSkillTree.synchronize(
            technique, List.of(move), List.of(ability)));
        assertEquals(2, technique.skillTree.size());

        SkillTreeNodeData moveNode = node(technique, SkillTreeNodeData.MOVE, "M1");
        assertEquals(2, moveNode.prerequisites.size());
        assertTrue(moveNode.prerequisites.stream().anyMatch(requirement ->
            SkillTreePrerequisiteData.STAT.equals(requirement.type)
                && "strength".equals(requirement.stat) && requirement.minimum == 90));
        assertTrue(moveNode.prerequisites.stream().anyMatch(requirement ->
            SkillTreePrerequisiteData.MASTERY.equals(requirement.type)
                && requirement.minimum == 40));

        SkillTreeNodeData abilityNode = node(technique, SkillTreeNodeData.ABILITY, "A1");
        assertEquals(60, abilityNode.prerequisites.get(0).minimum);
        assertFalse(TechniqueSkillTree.synchronize(
            technique, List.of(move), List.of(ability)));
    }

    @Test
    void nodeAndStatRequirementsControlActivationAndPruneDescendants() {
        InnateTechniqueData technique = technique();
        MoveData move = move("M1", "Miracles");
        AbilityData ability = ability("A1", "Miracles");
        TechniqueSkillTree.synchronize(technique, List.of(move), List.of(ability));
        SkillTreeNodeData moveNode = node(technique, SkillTreeNodeData.MOVE, "M1");
        SkillTreeNodeData abilityNode = node(technique, SkillTreeNodeData.ABILITY, "A1");

        SkillTreePrerequisiteData strength = new SkillTreePrerequisiteData();
        strength.type = SkillTreePrerequisiteData.STAT;
        strength.stat = "strength";
        strength.minimum = 100;
        moveNode.prerequisites.add(strength);

        SkillTreePrerequisiteData previousNode = new SkillTreePrerequisiteData();
        previousNode.type = SkillTreePrerequisiteData.NODE;
        previousNode.nodeId = moveNode.id;
        previousNode.attached = true;
        abilityNode.prerequisites.add(previousNode);

        CharacterData character = new CharacterData();
        character.innateTechniqueName = "Miracles";
        character.strength = 99;
        character.moveIds = new ArrayList<>();
        character.abilityIds = new ArrayList<>();
        assertFalse(TechniqueSkillTree.isUnlocked(technique, moveNode, character));

        character.strength = 100;
        assertTrue(TechniqueSkillTree.isUnlocked(technique, moveNode, character));
        TechniqueSkillTree.setActive(moveNode, character, true);
        assertTrue(TechniqueSkillTree.isUnlocked(technique, abilityNode, character));
        TechniqueSkillTree.setActive(abilityNode, character, true);
        assertTrue(character.availableAbilityIds.contains("A1"));
        assertFalse(character.abilityIds.contains("A1"));

        TechniqueSkillTree.setActive(moveNode, character, false);
        assertTrue(TechniqueSkillTree.pruneLockedSelections(technique, character));
        assertFalse(TechniqueSkillTree.isActive(abilityNode, character));
        assertFalse(character.availableAbilityIds.contains("A1"));
    }

    @Test
    void techniqueAbilitiesRequireExplicitTreeActivation() {
        AbilityData ability = ability("A1", "Miracles");
        InnateTechniqueData technique = technique();
        TechniqueSkillTree.synchronize(technique, List.of(), List.of(ability));
        SkillTreePrerequisiteData mastery = new SkillTreePrerequisiteData();
        mastery.type = SkillTreePrerequisiteData.MASTERY;
        mastery.minimum = 100;
        technique.skillTree.get(0).prerequisites.add(mastery);
        CharacterData character = new CharacterData();
        character.innateTechniqueName = "Miracles";
        character.abilityIds = new ArrayList<>();
        character.availableAbilityIds = new ArrayList<>();
        character.cursedTechniqueMastery = 99;

        assertFalse(AbilityResolver.resolve(
            character, List.of(ability), ignored -> true, List.of(technique))
            .containsAbility("A1"));
        character.abilityIds.add("A1");
        assertFalse(AbilityResolver.resolve(
            character, List.of(ability), ignored -> true, List.of(technique))
            .containsAbility("A1"));
        character.cursedTechniqueMastery = 100;
        assertFalse(AbilityResolver.resolve(
            character, List.of(ability), ignored -> true, List.of(technique))
            .containsAbility("A1"));
        character.availableAbilityIds.add("A1");
        assertTrue(AbilityResolver.resolve(
            character, List.of(ability), ignored -> true, List.of(technique))
            .containsAbility("A1"));
    }

    @Test
    void treeMetadataSurvivesJsonRoundTrip() throws Exception {
        InnateTechniqueData technique = technique();
        AbilityData ability = ability("A1", "Miracles");
        TechniqueSkillTree.synchronize(
            technique, List.of(move("M1", "Miracles")), List.of(ability));
        SkillTreeNodeData moveNode = node(technique, SkillTreeNodeData.MOVE, "M1");
        SkillTreeNodeData abilityNode = node(technique, SkillTreeNodeData.ABILITY, "A1");
        moveNode.x = 432f;
        moveNode.y = 123f;
        SkillTreePrerequisiteData connection = new SkillTreePrerequisiteData();
        connection.type = SkillTreePrerequisiteData.NODE;
        connection.nodeId = moveNode.id;
        connection.attached = true;
        abilityNode.prerequisites.add(connection);

        ObjectMapper mapper = new ObjectMapper();
        InnateTechniqueData restored = mapper.readValue(
            mapper.writeValueAsString(technique), InnateTechniqueData.class);

        assertNotNull(restored.skillTree);
        SkillTreeNodeData restoredMove = node(restored, SkillTreeNodeData.MOVE, "M1");
        SkillTreeNodeData restoredAbility = node(restored, SkillTreeNodeData.ABILITY, "A1");
        assertEquals(moveNode.id, restoredMove.id);
        assertEquals(432f, restoredMove.x);
        assertEquals(123f, restoredMove.y);
        assertTrue(restoredAbility.prerequisites.get(0).hasAttachment());
    }

    private static InnateTechniqueData technique() {
        InnateTechniqueData technique = new InnateTechniqueData();
        technique.id = "T1";
        technique.name = "Miracles";
        technique.skillTree = new ArrayList<>();
        return technique;
    }

    private static MoveData move(String id, String technique) {
        MoveData move = new MoveData();
        move.id = id;
        move.name = id;
        move.requiredTechniqueId = technique;
        return move;
    }

    private static AbilityData ability(String id, String technique) {
        AbilityData ability = new AbilityData();
        ability.id = id;
        ability.name = id;
        ability.category = "PASSIVE";
        ability.sourceType = "TECHNIQUE";
        ability.sourceValue = technique;
        ability.effects = List.of();
        return ability;
    }

    private static SkillTreeNodeData node(
        InnateTechniqueData technique,
        String type,
        String contentId
    ) {
        return technique.skillTree.stream()
            .filter(node -> type.equals(node.contentType) && contentId.equals(node.contentId))
            .findFirst().orElseThrow();
    }
}
