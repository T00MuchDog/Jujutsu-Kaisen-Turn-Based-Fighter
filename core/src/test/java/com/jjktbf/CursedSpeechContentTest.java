package com.jjktbf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.model.character.coded.CursedSpeechAbility;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.move.MoveEffectTrigger;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursedSpeechContentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void authoredCommandsSurviveSelectionPreviewAndBattleConversion() throws IOException {
        JsonNode moves = array("moves/all_moves.json");
        List<JsonNode> commands = StreamSupport.stream(moves.spliterator(), false)
            .filter(move -> StreamSupport.stream(move.path("effects").spliterator(), false)
                .anyMatch(effect -> "CODED_MOVE_ACTION".equals(effect.path("type").asText())
                    && CursedSpeechAbility.KEY.equals(effect.path("codedAbilityKey").asText())
                    && CursedSpeechAbility.COMMAND.equals(effect.path("codedAction").asText())))
            .toList();
        assertFalse(commands.isEmpty(), "Expected at least one Cursed Speech command");

        for (JsonNode command : commands) {
            MoveData definition = MAPPER.treeToValue(command, MoveData.class);

            definition.toMove(); // CharacterSelectScreen preview
            Move battleMove = definition.toMove(); // JJKGame battle construction

            assertFalse(definition.neverMiss,
                definition.name + " still uses the legacy Never Miss boolean");
            assertEquals(1, definition.getNeverMissTier(),
                definition.name + " must author Never Miss tier 1");
            assertEquals(1, battleMove.getNeverMissTier());
            assertTrue(battleMove.effectsFor(MoveEffectTrigger.ACCURACY_CHECK, -1).stream()
                .anyMatch(effect -> AbilityEffectType.NEVER_MISS.name().equals(effect.type)
                    && Integer.valueOf(1).equals(effect.intValue)));
            assertNotNull(CursedSpeechAbility.commandMode(battleMove),
                definition.name + " lost its command effect during repeated conversion");
            assertEquals("Cursed Speech", battleMove.getRequiredTechniqueId());
            assertTrue(battleMove.hasTag("INNATE_TECHNIQUE"));
            assertTrue(battleMove.effectsFor(MoveEffectTrigger.ON_HIT, 0).stream()
                .anyMatch(effect -> AbilityEffectType.CODED_MOVE_ACTION.name()
                    .equals(effect.type)
                    && CursedSpeechAbility.KEY.equals(effect.codedAbilityKey)
                    && CursedSpeechAbility.COMMAND.equals(effect.codedAction)));
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

}
