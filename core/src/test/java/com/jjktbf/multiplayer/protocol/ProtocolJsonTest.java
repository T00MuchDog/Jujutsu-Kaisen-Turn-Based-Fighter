package com.jjktbf.multiplayer.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.model.character.coded.CodedAbilityState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void completeMatchStateRoundTrips() throws Exception {
        MatchState state = completeMatchState();

        String json = mapper.writeValueAsString(state);
        MatchState restored = mapper.readValue(json, MatchState.class);
        JsonNode tree = mapper.readTree(json);

        assertEquals(state, restored);
        assertEquals(42L, tree.get("stateVersion").longValue());
        assertEquals("Divergent Fist", tree.at("/players/0/character/knownMoves/0/name").textValue());
        assertEquals(ActionSegmentStatus.QUEUED, restored.players().get(0).character()
            .plan().queuedSegments().get(0).status());
        assertEquals(88, restored.player(PlayerSide.PLAYER_TWO).orElseThrow()
            .character().currentDefense());
        assertEquals(4, tree.at("/players/0/character/codedAbilities/0/currentValue").intValue());
        assertEquals(205, tree.at("/roundStartCharacterStates/0/currentHp").intValue());
        assertTrue(restored.players().get(0).readyForNextRound());
        assertThrows(UnsupportedOperationException.class, () -> restored.players().add(null));
        assertThrows(UnsupportedOperationException.class,
            () -> restored.roundStartCharacterStates().clear());
        assertThrows(UnsupportedOperationException.class,
            () -> restored.recentEvents().clear());
    }

    @Test
    void actionCommandRoundTripsAndCopiesIntent() throws Exception {
        List<PlanPlacement> placements = new ArrayList<>();
        placements.add(new PlanPlacement("000004", 13));
        placements.add(new PlanPlacement("000001", 48));
        ActionCommand command = ActionCommand.submitPlan("command-1", "match-1", 41, placements);
        placements.clear();

        String json = mapper.writeValueAsString(command);
        ActionCommand restored = mapper.readValue(json, ActionCommand.class);
        JsonNode tree = mapper.readTree(json);

        assertEquals(command, restored);
        assertEquals(CommandType.SUBMIT_PLAN, restored.type());
        assertEquals(2, restored.payload().placements().size());
        assertEquals(2, tree.at("/payload/placements/0").size(),
            "PlanPlacement must expose only moveId and startTick");
        assertEquals(List.of(), new SubmitPlanPayload(null).placements());
        assertThrows(UnsupportedOperationException.class,
            () -> restored.payload().placements().add(new PlanPlacement("other", 1)));
    }

    @Test
    void readyNextRoundCommandRoundTripsWithoutPayload() throws Exception {
        ActionCommand command = ActionCommand.readyNextRound("ready-1", "match-1", 42);

        String json = mapper.writeValueAsString(command);
        ActionCommand restored = mapper.readValue(json, ActionCommand.class);
        JsonNode tree = mapper.readTree(json);

        assertEquals(command, restored);
        assertEquals(CommandType.READY_NEXT_ROUND, restored.type());
        assertNull(restored.payload());
        assertTrue(tree.get("payload").isNull());
    }

    @Test
    void challengeSummaryRoundTrips() throws Exception {
        ChallengeSummary summary = new ChallengeSummary(
            "challenge-1",
            "player-1",
            "Guest Mantis",
            "character-1",
            "Yuji Itadori",
            ChallengeStatus.OPEN,
            ProtocolVersion.GAME_VERSION,
            ProtocolVersion.PROTOCOL_VERSION,
            ProtocolVersion.STANDARD_RULESET,
            1_700_000_000_000L,
            1_700_000_300_000L,
            "request-1",
            "player-2",
            "character-2",
            1_700_000_010_000L,
            null,
            null
        );

        String json = mapper.writeValueAsString(summary);
        ChallengeSummary restored = mapper.readValue(json, ChallengeSummary.class);
        JsonNode tree = mapper.readTree(json);

        assertEquals(summary, restored);
        assertEquals("OPEN", tree.get("status").textValue());
        assertEquals("Yuji Itadori", restored.hostCharacterName());
        assertEquals("player-2", restored.requestedPlayerId());
        assertEquals("request-1", restored.joinRequestId());
        assertFalse(tree.has("matchId"));
    }

    @Test
    void socketMessagesRoundTripForEveryExplicitType() throws Exception {
        MatchState state = completeMatchState();
        ActionCommand command = ActionCommand.submitPlan(
            "command-1", state.matchId(), state.stateVersion(), List.of(new PlanPlacement("000004", 13)));
        ErrorResponse error = new ErrorResponse(
            "STATE_VERSION_MISMATCH",
            "The match state changed.",
            Map.of("expected", "42", "actual", "43")
        );

        List<SocketMessage> messages = List.of(
            SocketMessage.joinMatch(state.matchId()),
            SocketMessage.matchJoined(state.matchId(), "player-1", "Guest Mantis", PlayerSide.PLAYER_ONE, state),
            SocketMessage.submitAction(command),
            SocketMessage.matchState(state),
            SocketMessage.commandRejected(state.matchId(), command.commandId(), error, state),
            SocketMessage.playerConnected(state.matchId(), "player-2", "Guest Crane", PlayerSide.PLAYER_TWO),
            SocketMessage.playerDisconnected(
                state.matchId(), "player-2", "Guest Crane", PlayerSide.PLAYER_TWO, 1_700_000_060_000L),
            SocketMessage.matchEnded(state),
            SocketMessage.ping(1_700_000_000_100L),
            SocketMessage.pong(1_700_000_000_100L),
            SocketMessage.error(state.matchId(), error)
        );

        assertEquals(List.of(MessageType.values()), messages.stream().map(SocketMessage::type).toList());
        for (SocketMessage message : messages) {
            String json = mapper.writeValueAsString(message);
            assertEquals(message, mapper.readValue(json, SocketMessage.class), message.type().name());
        }

        SocketMessage joined = messages.get(1);
        assertEquals(ProtocolVersion.GAME_VERSION, joined.gameVersion());
        assertEquals(ProtocolVersion.PROTOCOL_VERSION, joined.protocolVersion());
        assertEquals(42L, joined.stateVersion());
        assertEquals(1_700_000_060_000L, messages.get(6).disconnectDeadline());
        assertTrue(ProtocolVersion.isCompatible(
            joined.gameVersion(), joined.protocolVersion(), joined.ruleset()));
    }

    private static MatchState completeMatchState() {
        MoveState divergentFist = new MoveState(
            "000004",
            "Divergent Fist",
            "A delayed cursed-energy strike.",
            "PHYSICAL_CURSED_ENERGY",
            List.of("PHYSICAL", "CURSED_ENERGY", "ATTACK"),
            PlanBoard.OFFENSIVE,
            75,
            0.90,
            false,
            25,
            12,
            true,
            40,
            32,
            10,
            80,
            true,
            null
        );
        MoveState basicBlock = new MoveState(
            "000001",
            "Basic Block",
            "Guard against an incoming attack.",
            "DEFENSIVE",
            List.of("PHYSICAL", "DEFENSIVE"),
            PlanBoard.DEFENSIVE,
            0,
            1.0,
            true,
            10,
            1,
            false,
            0,
            0,
            0,
            0,
            true,
            null
        );
        ActionSegmentState queued = new ActionSegmentState(
            "segment-3-1",
            divergentFist.moveId(),
            divergentFist.name(),
            PlanBoard.OFFENSIVE,
            13,
            37,
            24,
            25,
            32,
            ActionSegmentStatus.QUEUED,
            null
        );
        ActionSegmentState resolved = new ActionSegmentState(
            "segment-2-2",
            basicBlock.moveId(),
            basicBlock.name(),
            PlanBoard.DEFENSIVE,
            5,
            14,
            5,
            10,
            0,
            ActionSegmentStatus.RESOLVED,
            5
        );
        PlanState playerOnePlan = new PlanState(
            3, 90, 35, 320, 32, List.of(queued), List.of(resolved));
        CharacterState playerOneCharacter = new CharacterState(
            "character-1",
            "Yuji Itadori",
            205,
            267,
            288,
            400,
            55,
            90,
            91,
            true,
            2,
            4,
            List.of(new StatusEffectState("FOCUS", "Focus", 1, 0.15)),
            List.of(new CodedAbilityState("MIRACLES", "Miracles", 4, 6)),
            List.of(divergentFist, basicBlock),
            playerOnePlan
        );
        CharacterState playerTwoCharacter = new CharacterState(
            "character-2",
            "Megumi Fushiguro",
            171,
            240,
            350,
            450,
            84,
            84,
            88,
            false,
            0,
            null,
            List.of(new StatusEffectState("DEFENSE_UP", "Defense Up", -1, 12.0)),
            List.of(),
            List.of(basicBlock),
            new PlanState(3, 84, 0, 350, 0, null, null)
        );
        PlayerState playerOne = new PlayerState(
            "player-1", "Guest Mantis", PlayerSide.PLAYER_ONE,
            true, true, true, null, playerOneCharacter);
        PlayerState playerTwo = new PlayerState(
            "player-2", "Guest Crane", PlayerSide.PLAYER_TWO,
            false, false, false, 1_700_000_060_000L, playerTwoCharacter);
        BattleEventState event = new BattleEventState(
            "event-17",
            BattleEventType.DAMAGE_DEALT,
            2,
            24,
            PlayerSide.PLAYER_ONE,
            playerOneCharacter.characterId(),
            playerOneCharacter.name(),
            PlayerSide.PLAYER_TWO,
            playerTwoCharacter.characterId(),
            playerTwoCharacter.name(),
            divergentFist.moveId(),
            divergentFist.name(),
            39,
            "Yuji Itadori dealt 39 damage."
        );

        return new MatchState(
            "match-1",
            MatchStatus.OPPONENT_DISCONNECTED,
            ProtocolVersion.GAME_VERSION,
            ProtocolVersion.PROTOCOL_VERSION,
            ProtocolVersion.STANDARD_RULESET,
            BattlePhase.PLANNING,
            3,
            0,
            List.of(playerOne, playerTwo),
            List.of(
                new RoundStartCharacterState(PlayerSide.PLAYER_ONE, 205, 214, 320, 400,
                    List.of(new CodedAbilityState("MIRACLES", "Miracles", 4, 6))),
                new RoundStartCharacterState(PlayerSide.PLAYER_TWO, 171, 214, 350, 400, List.of())
            ),
            null,
            null,
            null,
            42,
            List.of(event),
            1_700_000_000_000L
        );
    }
}
