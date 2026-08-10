package com.jjktbf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.model.character.coded.CursedSpeechAbility;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.progression.TechniqueMasteryProgressionData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursedSpeechContentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> DEFAULT_COMBAT_MOVES = List.of(
        "000000", "000001", "000002", "000003",
        "000008", "000009", "000010", "000014");
    private static final List<String> DEFAULT_COMMANDS = List.of(
        "000069", "000070", "000071", "000072", "000073", "000075");
    private static final Set<String> COMMAND_IDS = Set.of(
        "000069", "000070", "000071", "000072",
        "000073", "000074", "000075", "000076");

    @Test
    void inumakiHasSpecifiedStatsAndExactlySixAssignedCommands() throws IOException {
        JsonNode inumaki = byId(array("characters/all_characters.json"), "000015");

        assertEquals("Toge Inumaki", inumaki.path("name").asText());
        assertEquals("assets/sprites/characters/inumaki_frontsprite.png",
            inumaki.path("spriteAsset").asText());
        assertEquals("Cursed Speech", inumaki.path("innateTechniqueName").asText());
        assertFalse(inumaki.path("hasWeapon").asBoolean());
        assertEquals(Map.of(
            "vitality", 45,
            "strength", 35,
            "durability", 50,
            "speed", 70,
            "cursedEnergyReserves", 45,
            "cursedEnergyEfficiency", 85,
            "cursedEnergyOutput", 65,
            "jujutsuSkill", 80,
            "combatAbility", 80,
            "cursedTechniqueMastery", 120
        ), Map.ofEntries(
            Map.entry("vitality", inumaki.path("vitality").asInt()),
            Map.entry("strength", inumaki.path("strength").asInt()),
            Map.entry("durability", inumaki.path("durability").asInt()),
            Map.entry("speed", inumaki.path("speed").asInt()),
            Map.entry("cursedEnergyReserves", inumaki.path("cursedEnergyReserves").asInt()),
            Map.entry("cursedEnergyEfficiency", inumaki.path("cursedEnergyEfficiency").asInt()),
            Map.entry("cursedEnergyOutput", inumaki.path("cursedEnergyOutput").asInt()),
            Map.entry("jujutsuSkill", inumaki.path("jujutsuSkill").asInt()),
            Map.entry("combatAbility", inumaki.path("combatAbility").asInt()),
            Map.entry("cursedTechniqueMastery", inumaki.path("cursedTechniqueMastery").asInt())
        ));

        List<String> assigned = strings(inumaki.path("moveIds"));
        assertEquals(DEFAULT_COMBAT_MOVES, assigned.subList(0, DEFAULT_COMBAT_MOVES.size()));
        assertEquals(DEFAULT_COMMANDS, assigned.stream().filter(COMMAND_IDS::contains).toList());
        assertEquals(6, assigned.stream().filter(COMMAND_IDS::contains).count());
        assertEquals(List.of("000074", "000076"),
            strings(inumaki.path("availableMoveIds")).stream()
                .filter(COMMAND_IDS::contains)
                .filter(id -> !assigned.contains(id))
                .toList());
        assertEquals(List.of("000031", "000032"), strings(inumaki.path("abilityIds")));
        assertEquals(List.of("000031", "000032"),
            strings(inumaki.path("availableAbilityIds")));
    }

    @Test
    void commandRowsMatchTheAuthoredSpec() throws IOException {
        JsonNode moves = array("moves/all_moves.json");
        List<CommandSpec> specs = List.of(
            new CommandSpec("000069", "Don't Move", "DONT_MOVE", 7, 1, 8, 4, 14, 0,
                0, 85, 4, "min(95,65+ctm/6)"),
            new CommandSpec("000070", "Blast Away", "BLAST_AWAY", 12, 4, 18, 9, 32, 40,
                20, 80, 8, "min(95,56+ctm/5)"),
            new CommandSpec("000071", "Sleep", "SLEEP", 10, 3, 16, 8, 28, 0,
                35, 75, 6, "min(95,51+ctm/5)"),
            new CommandSpec("000072", "Plummet", "PLUMMET", 15, 7, 26, 13, 46, 65,
                50, 70, 12, "min(90,46+ctm/5)"),
            new CommandSpec("000073", "Get Twisted", "GET_TWISTED", 17, 9, 36, 18, 63, 90,
                70, 60, 20, "min(85,36+ctm/5)"),
            new CommandSpec("000074", "Return", "RETURN", 10, 3, 18, 9, 32, 0,
                85, 80, 10, "min(95,56+ctm/5)"),
            new CommandSpec("000075", "Explode", "EXPLODE", 21, 14, 55, 28, 96, 135,
                100, 50, 36, "min(80,26+ctm/5)"),
            new CommandSpec("000076", "Die", "DIE", 24, 20, 85, 43, 149, 0,
                120, 25, 80, "min(60,1+ctm/5)")
        );

        for (CommandSpec spec : specs) {
            JsonNode move = byId(moves, spec.id());
            assertEquals(spec.name(), move.path("name").asText());
            assertEquals(List.of(
                "INNATE_TECHNIQUE", "CURSED_ENERGY", "ATTACK", "RANGED", "AOE"),
                strings(move.path("tags")));
            assertEquals(spec.power(), move.path("basePower").asInt());
            assertEquals(spec.ap(), move.path("apCost").asInt());
            assertEquals(spec.unleash(), move.path("unleashPoint").asInt());
            assertEquals(spec.ce(), move.path("baseCeCost").asInt());
            assertEquals(spec.minCe(), move.path("minCeCost").asInt());
            assertEquals(spec.maxCe(), move.path("maxCeCost").asInt());
            assertTrue(move.path("hasCeCost").asBoolean());
            assertTrue(move.path("neverMiss").asBoolean());
            assertFalse(move.path("isFreeMove").asBoolean());
            assertEquals("Cursed Speech", move.path("requiredTechniqueId").asText());
            assertEquals("MULTIPLE", move.path("aoeType").asText());
            assertEquals(3, move.path("aoeTargetCount").asInt());
            assertEquals(spec.unlockCtm(),
                move.path("prerequisites").path("cursedTechniqueMastery").asInt());

            JsonNode rows = move.path("onHitEffects");
            assertEquals(1, rows.size());
            JsonNode command = rows.get(0);
            assertEquals("CURSED_SPEECH", command.path("codedAbilityKey").asText());
            assertEquals("COMMAND", command.path("codedAction").asText());
            assertEquals(spec.mode(), command.path("codedTarget").asText());
            assertEquals(spec.literalChance(),
                command.path("codedParameters").path("baseChancePercent").asInt());
            assertEquals(spec.recoil(),
                command.path("codedParameters").path("baseRecoil").asInt());
            assertEquals("FORMULA", command.path("masteryProgression")
                .path("baseChancePercent").path("mode").asText());
            assertEquals(spec.formula(), command.path("masteryProgression")
                .path("baseChancePercent").path("formula").asText());
            TechniqueMasteryProgressionData progression = MAPPER.treeToValue(
                command.path("masteryProgression").path("baseChancePercent"),
                TechniqueMasteryProgressionData.class);
            assertNull(progression.validationError());
            assertEquals(spec.literalChance(), progression.resolve(120));
        }
    }

    @Test
    void techniqueTreeAndAbilitiesExposeTheFullProgression() throws IOException {
        JsonNode technique = byId(array("techniques/all_techniques.json"), "000003");
        assertEquals("Cursed Speech", technique.path("name").asText());
        assertEquals(List.of(
            "000031", "000069", "000070", "000071", "000072",
            "000032", "000073", "000074", "000075", "000076"),
            StreamSupport.stream(technique.path("skillTree").spliterator(), false)
                .map(node -> node.path("contentId").asText())
                .toList());

        Map<String, Integer> unlocks = Map.of(
            "000069", 0, "000070", 20, "000071", 35, "000072", 50,
            "000073", 70, "000074", 85, "000075", 100, "000076", 120);
        for (JsonNode node : technique.path("skillTree")) {
            if (!unlocks.containsKey(node.path("contentId").asText())) continue;
            JsonNode mastery = StreamSupport.stream(node.path("prerequisites").spliterator(), false)
                .filter(prerequisite -> "MASTERY".equals(prerequisite.path("type").asText()))
                .findFirst().orElseThrow();
            assertEquals(unlocks.get(node.path("contentId").asText()).intValue(),
                mastery.path("minimum").asInt());
        }

        JsonNode abilities = array("abilities/all_abilities.json");
        JsonNode root = byId(abilities, "000031");
        assertEquals("CURSED_SPEECH",
            root.path("effects").get(0).path("codedAbilityKey").asText());
        assertEquals("TECHNIQUE",
            root.path("effects").get(0).path("codedFeature").asText());

        JsonNode refined = byId(abilities, "000032");
        assertEquals(60, refined.path("masteryThreshold").asInt());
        JsonNode effect = refined.path("effects").get(0);
        assertEquals("REFINED_COMMANDS", effect.path("codedFeature").asText());
        assertEquals(10, effect.path("codedParameters").path("successBonusPercent").asInt());
        assertEquals("min(20,ctm/12)", effect.path("masteryProgression")
            .path("successBonusPercent").path("formula").asText());
        TechniqueMasteryProgressionData progression = MAPPER.treeToValue(
            effect.path("masteryProgression").path("successBonusPercent"),
            TechniqueMasteryProgressionData.class);
        assertNull(progression.validationError());
        assertEquals(10, progression.resolve(120));
    }

    @Test
    void authoredCommandsSurviveSelectionPreviewAndBattleConversion() throws IOException {
        JsonNode moves = array("moves/all_moves.json");
        for (String id : COMMAND_IDS) {
            MoveData definition = MAPPER.treeToValue(byId(moves, id), MoveData.class);

            definition.toMove(); // CharacterSelectScreen preview
            Move battleMove = definition.toMove(); // JJKGame battle construction

            assertNotNull(CursedSpeechAbility.commandMode(battleMove),
                definition.name + " lost its command effect during repeated conversion");
            assertTrue(battleMove.getHitComponents().get(0).getOnHitEffects().stream()
                .anyMatch(CursedSpeechAbility::isCommand));
        }
    }

    private static JsonNode array(String relativePath) throws IOException {
        Path path = List.of(
                Path.of("data", relativePath),
                Path.of("..", "data", relativePath))
            .stream()
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(() -> new IOException("Could not locate " + relativePath));
        JsonNode root = MAPPER.readTree(path.toFile());
        assertTrue(root.isArray(), relativePath + " must contain a JSON array");
        return root;
    }

    private static JsonNode byId(JsonNode array, String id) {
        JsonNode found = StreamSupport.stream(array.spliterator(), false)
            .filter(node -> id.equals(node.path("id").asText()))
            .findFirst().orElse(null);
        assertNotNull(found, "Missing content ID " + id);
        return found;
    }

    private static List<String> strings(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false)
            .map(JsonNode::asText)
            .toList();
    }

    private record CommandSpec(
        String id,
        String name,
        String mode,
        int ap,
        int unleash,
        int ce,
        int minCe,
        int maxCe,
        int power,
        int unlockCtm,
        int literalChance,
        int recoil,
        String formula
    ) { }
}
