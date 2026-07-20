package com.jjktbf.multiplayer.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityConditionType;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.CeEfficiencyCalculator;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.multiplayer.protocol.ActionCommand;
import com.jjktbf.multiplayer.protocol.BattleEventType;
import com.jjktbf.multiplayer.protocol.BattlePhase;
import com.jjktbf.multiplayer.protocol.CommandResult;
import com.jjktbf.multiplayer.protocol.MatchState;
import com.jjktbf.multiplayer.protocol.MatchStatus;
import com.jjktbf.multiplayer.protocol.PlanPlacement;
import com.jjktbf.multiplayer.protocol.PlayerSide;
import com.jjktbf.multiplayer.protocol.PlayerState;
import com.jjktbf.multiplayer.protocol.RoundStartCharacterState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadlessBattleSessionTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-01-02T03:04:05Z"),
        ZoneOffset.UTC
    );

    @Test
    void validPlansResolveServerSideAndHoldRoundEndPlaybackState() {
        Move attack = ceAttack("CE_ATTACK", 1, 40);
        HeadlessBattleSession session = session(10L, attack, attack);
        int canonicalCost = CeEfficiencyCalculator.computeActualCost(attack, 160);
        long initialVersion = session.getStateVersion();
        List<RoundStartCharacterState> roundStartResources =
            session.snapshot().roundStartCharacterStates();

        CommandResult first = session.applyCommand(
            "player-1",
            command(session, "command-1", new PlanPlacement(attack.getId(), 1))
        );

        assertTrue(first.accepted());
        assertEquals(initialVersion + 1, first.state().stateVersion());
        PlayerState submitted = first.state().player(PlayerSide.PLAYER_ONE).orElseThrow();
        assertTrue(submitted.planSubmitted());
        assertEquals(canonicalCost, submitted.character().plan().ceUsed());
        assertEquals(
            submitted.character().maxAp() - attack.getApCost(),
            submitted.character().currentAp()
        );

        CommandResult second = session.applyCommand(
            "player-2",
            command(session, "command-2", new PlanPlacement(attack.getId(), 1))
        );

        assertTrue(second.accepted());
        assertEquals(initialVersion + 2, second.state().stateVersion());
        assertEquals(BattlePhase.ROUND_END, second.state().phase());
        assertEquals(1, second.state().roundNumber());
        assertEquals(roundStartResources, second.state().roundStartCharacterStates());
        for (PlayerState player : second.state().players()) {
            assertEquals(player.character().maxCe() - canonicalCost, player.character().currentCe());
            assertTrue(player.planSubmitted());
            assertFalse(player.readyForNextRound());
            assertEquals(1, player.character().plan().roundNumber());
            assertEquals(1, player.character().plan().resolvedSegments().size());
        }
        assertFalse(session.snapshotFor("player-2").player(PlayerSide.PLAYER_ONE).orElseThrow()
            .character().plan().resolvedSegments().isEmpty());
        assertTrue(second.events().stream().anyMatch(
            event -> event.type() == BattleEventType.CE_DRAINED
                && event.value() == canonicalCost
        ));
        assertFalse(second.events().stream().anyMatch(
            event -> event.type() == BattleEventType.ROUND_START && event.roundNumber() == 2
        ));
    }

    @Test
    void invalidCommandLeavesCompleteStateUnchanged() {
        Move attack = physicalAttack("KNOWN", 10, true);
        HeadlessBattleSession session = session(11L, attack, attack);
        MatchState before = session.snapshot();

        CommandResult result = session.applyCommand(
            "player-1",
            command(session, "bad-command", new PlanPlacement("NOT_KNOWN", 1))
        );

        assertFalse(result.accepted());
        assertEquals("INVALID_MOVE", result.error().code());
        assertEquals(before, result.state());
        assertEquals(before, session.snapshot());
    }

    @Test
    void planOwnershipAndOneSubmissionPerRoundAreEnforced() {
        Move playerOneMove = physicalAttack("PLAYER_ONE_MOVE", 10, true);
        Move playerTwoMove = physicalAttack("PLAYER_TWO_MOVE", 10, true);
        HeadlessBattleSession session = session(12L, playerOneMove, playerTwoMove);

        CommandResult wrongOwner = session.applyCommand(
            "player-1",
            command(session, "wrong-owner", new PlanPlacement(playerTwoMove.getId(), 1))
        );
        assertFalse(wrongOwner.accepted());
        assertEquals("INVALID_MOVE", wrongOwner.error().code());
        assertEquals(2, session.getStateVersion());

        assertTrue(session.applyCommand(
            "player-1",
            command(session, "first-plan", new PlanPlacement(playerOneMove.getId(), 1))
        ).accepted());

        CommandResult repeatedPlan = session.applyCommand(
            "player-1",
            command(session, "second-plan", new PlanPlacement(playerOneMove.getId(), 10))
        );
        assertFalse(repeatedPlan.accepted());
        assertEquals("PLAN_ALREADY_SUBMITTED", repeatedPlan.error().code());
        assertEquals(3, session.getStateVersion());

        CommandResult outsider = session.applyCommand(
            "outsider",
            command(session, "outsider-plan", new PlanPlacement(playerTwoMove.getId(), 1))
        );
        assertFalse(outsider.accepted());
        assertEquals("PLAYER_NOT_IN_MATCH", outsider.error().code());
    }

    @Test
    void fixedSeedProducesIdenticalSnapshotsAndEvents() {
        Move attack = physicalAttack("RANDOM_ATTACK", 70, false);
        HeadlessBattleSession first = session(987654321L, attack, attack);
        HeadlessBattleSession second = session(987654321L, attack, attack);

        submitBoth(first, attack, attack);
        submitBoth(second, attack, attack);

        assertEquals(first.snapshot(), second.snapshot());
    }

    @Test
    void staleAndDuplicateCommandsAreRejectedWithoutVersionChanges() {
        Move attack = physicalAttack("ATTACK", 10, true);
        HeadlessBattleSession session = session(13L, attack, attack);
        long activeVersion = session.getStateVersion();
        ActionCommand accepted = command(
            session, "accepted-command", new PlanPlacement(attack.getId(), 1));

        assertTrue(session.applyCommand("player-1", accepted).accepted());
        assertEquals(activeVersion + 1, session.getStateVersion());

        CommandResult duplicate = session.applyCommand("player-1", accepted);
        assertFalse(duplicate.accepted());
        assertEquals("DUPLICATE_COMMAND", duplicate.error().code());
        assertEquals(activeVersion + 1, session.getStateVersion());

        CommandResult stale = session.applyCommand(
            "player-2",
            commandAt(
                "stale-command", activeVersion - 1, new PlanPlacement(attack.getId(), 1))
        );
        assertFalse(stale.accepted());
        assertEquals("STALE_STATE_VERSION", stale.error().code());
        assertEquals(activeVersion + 1, session.getStateVersion());
    }

    @Test
    void bothPlayersCanSubmitFromTheSameSharedPlanningVersion() {
        Move attack = physicalAttack("SIMULTANEOUS_ATTACK", 10, true);
        HeadlessBattleSession session = session(131L, attack, attack);
        long sharedVersion = session.getStateVersion();

        CommandResult first = session.applyCommand(
            "player-1",
            commandAt("shared-first", sharedVersion, new PlanPlacement(attack.getId(), 1))
        );
        CommandResult second = session.applyCommand(
            "player-2",
            commandAt("shared-second", sharedVersion, new PlanPlacement(attack.getId(), 1))
        );

        assertTrue(first.accepted());
        assertTrue(second.accepted());
        assertEquals(sharedVersion + 2, session.getStateVersion());
    }

    @Test
    void roundEndRequiresBothReadyCommandsFromTheirSharedVersion() {
        Move attack = physicalAttack("READY_ATTACK", 1, true);
        HeadlessBattleSession session = session(133L, attack, attack);
        submitBoth(session, attack, attack);
        MatchState roundEnd = session.snapshot();
        long sharedReadyVersion = roundEnd.stateVersion();
        ActionCommand firstReady = readyAt("ready-first", sharedReadyVersion);
        ActionCommand secondReady = readyAt("ready-second", sharedReadyVersion);

        CommandResult first = session.applyCommand("player-1", firstReady);

        assertTrue(first.accepted());
        assertTrue(first.events().isEmpty());
        assertEquals(sharedReadyVersion + 1, first.state().stateVersion());
        assertEquals(BattlePhase.ROUND_END, first.state().phase());
        assertEquals(1, first.state().roundNumber());
        assertEquals(roundEnd.recentEvents(), first.state().recentEvents());
        assertTrue(first.state().player(PlayerSide.PLAYER_ONE).orElseThrow()
            .readyForNextRound());
        assertFalse(first.state().player(PlayerSide.PLAYER_TWO).orElseThrow()
            .readyForNextRound());
        assertTrue(first.state().players().stream().allMatch(PlayerState::planSubmitted));

        CommandResult second = session.applyCommand("player-2", secondReady);

        assertTrue(second.accepted());
        assertEquals(sharedReadyVersion + 2, second.state().stateVersion());
        assertEquals(BattlePhase.PLANNING, second.state().phase());
        assertEquals(2, second.state().roundNumber());
        assertEquals(0, second.state().currentTick());
        assertEquals(second.events(), second.state().recentEvents());
        assertTrue(second.events().stream().anyMatch(
            event -> event.type() == BattleEventType.ROUND_START && event.roundNumber() == 2
        ));
        assertTrue(second.events().stream().allMatch(event -> event.roundNumber() == 2));
        for (PlayerState player : second.state().players()) {
            assertFalse(player.planSubmitted());
            assertFalse(player.readyForNextRound());
            assertTrue(player.character().plan().queuedSegments().isEmpty());
            assertTrue(player.character().plan().resolvedSegments().isEmpty());
        }
    }

    @Test
    void readyCommandsRejectWrongPhaseAndDuplicateReadinessWithoutMutation() {
        Move attack = physicalAttack("READY_REJECTION_ATTACK", 1, true);
        HeadlessBattleSession session = session(134L, attack, attack);
        long planningVersion = session.getStateVersion();

        CommandResult wrongPhase = session.applyCommand(
            "player-1",
            readyAt("ready-during-planning", planningVersion)
        );

        assertFalse(wrongPhase.accepted());
        assertEquals("WRONG_PHASE", wrongPhase.error().code());
        assertEquals(planningVersion, session.getStateVersion());

        submitBoth(session, attack, attack);
        assertTrue(session.applyCommand(
            "player-1",
            readyAt("accepted-ready", session.getStateVersion())
        ).accepted());
        long versionAfterReady = session.getStateVersion();

        CommandResult duplicateReady = session.applyCommand(
            "player-1",
            readyAt("duplicate-ready", versionAfterReady)
        );
        CommandResult planDuringRoundEnd = session.applyCommand(
            "player-2",
            command(session, "plan-during-round-end", new PlanPlacement(attack.getId(), 1))
        );

        assertFalse(duplicateReady.accepted());
        assertEquals("READY_ALREADY_SUBMITTED", duplicateReady.error().code());
        assertFalse(planDuringRoundEnd.accepted());
        assertEquals("WRONG_PHASE", planDuringRoundEnd.error().code());
        assertEquals(versionAfterReady, session.getStateVersion());
    }

    @Test
    void planningAndPlaybackRepeatAcrossMultipleReadyGatedRounds() {
        Move attack = physicalAttack("REPEATED_ROUND_ATTACK", 1, true);
        HeadlessBattleSession session = session(135L, attack, attack);

        submitBoth(session, attack, attack, "round-1");
        assertEquals(BattlePhase.ROUND_END, session.getPhase());
        assertEquals(1, session.getRoundNumber());
        long firstReadyVersion = session.getStateVersion();
        assertTrue(session.applyCommand(
            "player-1", readyAt("round-1-ready-1", firstReadyVersion)).accepted());
        assertTrue(session.applyCommand(
            "player-2", readyAt("round-1-ready-2", firstReadyVersion)).accepted());

        MatchState roundTwoPlanning = session.snapshot();
        List<RoundStartCharacterState> roundTwoStart =
            roundTwoPlanning.roundStartCharacterStates();
        assertEquals(BattlePhase.PLANNING, roundTwoPlanning.phase());
        assertEquals(2, roundTwoPlanning.roundNumber());

        submitBoth(session, attack, attack, "round-2");
        MatchState roundTwoEnd = session.snapshot();
        assertEquals(BattlePhase.ROUND_END, roundTwoEnd.phase());
        assertEquals(2, roundTwoEnd.roundNumber());
        assertEquals(roundTwoStart, roundTwoEnd.roundStartCharacterStates());
        assertTrue(roundTwoEnd.players().stream().allMatch(player ->
            player.character().plan().roundNumber() == 2));

        long secondReadyVersion = session.getStateVersion();
        assertTrue(session.applyCommand(
            "player-2", readyAt("round-2-ready-2", secondReadyVersion)).accepted());
        CommandResult nextRound = session.applyCommand(
            "player-1", readyAt("round-2-ready-1", secondReadyVersion));

        assertTrue(nextRound.accepted());
        assertEquals(BattlePhase.PLANNING, nextRound.state().phase());
        assertEquals(3, nextRound.state().roundNumber());
        assertTrue(nextRound.state().recentEvents().stream().allMatch(
            event -> event.roundNumber() == 3));
    }

    @Test
    void viewerSnapshotConcealsOnlyTheOpponentsUnresolvedPlan() {
        Move attack = ceAttack("SECRET_ATTACK", 10, 30);
        HeadlessBattleSession session = session(132L, attack, attack);

        assertTrue(session.applyCommand(
            "player-1",
            command(session, "secret-plan", new PlanPlacement(attack.getId(), 7))
        ).accepted());

        PlayerState ownerView = session.snapshotFor("player-1")
            .player(PlayerSide.PLAYER_ONE).orElseThrow();
        PlayerState opponentView = session.snapshotFor("player-2")
            .player(PlayerSide.PLAYER_ONE).orElseThrow();
        assertFalse(ownerView.character().plan().queuedSegments().isEmpty());
        assertTrue(opponentView.planSubmitted());
        assertTrue(opponentView.character().plan().queuedSegments().isEmpty());
        assertEquals(0, opponentView.character().plan().apUsed());
        assertEquals(opponentView.character().maxAp(), opponentView.character().currentAp());

        session.forceEnd(null, MatchStatus.ABANDONED, "TEST_END");
        PlayerState terminalOpponentView = session.snapshotFor("player-2")
            .player(PlayerSide.PLAYER_ONE).orElseThrow();
        assertTrue(terminalOpponentView.character().plan().queuedSegments().isEmpty());
    }

    @Test
    void stateVersionIncrementsExactlyOnceForEachAcceptedCommand() {
        Move attack = physicalAttack("VERSION_ATTACK", 1, true);
        HeadlessBattleSession session = session(14L, attack, attack);
        long activeVersion = session.getStateVersion();

        CommandResult first = session.applyCommand(
            "player-1",
            command(session, "version-1", new PlanPlacement(attack.getId(), 1))
        );
        CommandResult second = session.applyCommand(
            "player-2",
            command(session, "version-2", new PlanPlacement(attack.getId(), 1))
        );

        assertEquals(activeVersion + 1, first.state().stateVersion());
        assertEquals(activeVersion + 2, second.state().stateVersion());
        assertEquals(activeVersion + 2, session.getStateVersion());
    }

    @Test
    void knockoutCompletesMatchWithCanonicalWinner() {
        Move knockout = physicalAttack("KNOCKOUT", 10_000, true);
        Move harmless = physicalAttack("HARMLESS", 1, true);
        HeadlessBattleSession session = session(15L, knockout, harmless);
        List<RoundStartCharacterState> roundStart =
            session.snapshot().roundStartCharacterStates();

        assertTrue(session.applyCommand(
            "player-1",
            command(session, "ko-plan", new PlanPlacement(knockout.getId(), 1))
        ).accepted());
        CommandResult result = session.applyCommand(
            "player-2",
            ActionCommand.submitPlan(
                "empty-plan", "match-1", session.getStateVersion(), List.of())
        );

        assertTrue(result.accepted());
        assertEquals(MatchStatus.ENDED, result.state().status());
        assertEquals(BattlePhase.BATTLE_OVER, result.state().phase());
        assertEquals(PlayerSide.PLAYER_ONE, result.state().winnerSide());
        assertEquals("player-1", result.state().winnerPlayerId());
        assertEquals("KNOCKOUT", result.state().endReason());
        assertEquals(0, result.state().player(PlayerSide.PLAYER_TWO).orElseThrow()
            .character().currentHp());
        assertEquals(roundStart, result.state().roundStartCharacterStates());
        assertTrue(result.state().players().stream().allMatch(PlayerState::planSubmitted));
        assertTrue(result.state().players().stream().noneMatch(PlayerState::readyForNextRound));
        assertFalse(result.state().player(PlayerSide.PLAYER_ONE).orElseThrow()
            .character().plan().resolvedSegments().isEmpty());
        assertTrue(result.events().stream().anyMatch(
            event -> event.type() == BattleEventType.BATTLE_OVER
        ));
    }

    @Test
    void authoritativeSnapshotRoundTripsThroughJson() throws Exception {
        Move attack = ceAttack("JSON_ATTACK", 1, 30);
        HeadlessBattleSession session = session(16L, attack, attack);
        session.applyCommand(
            "player-1",
            command(session, "json-command", new PlanPlacement(attack.getId(), 7))
        );

        ObjectMapper mapper = new ObjectMapper();
        MatchState state = session.snapshot();
        String json = mapper.writeValueAsString(state);
        MatchState restored = mapper.readValue(json, MatchState.class);

        assertEquals(state, restored);
        assertEquals("JSON_ATTACK", restored.player(PlayerSide.PLAYER_ONE).orElseThrow()
            .character().plan().queuedSegments().get(0).moveId());
        assertNotNull(restored.players().get(0).character().knownMoves().get(0).board());
    }

    @Test
    void moveSnapshotsPreserveRawNatureAndRoleTags() {
        Move physicalBlock = new Move.Builder("PHYSICAL_BLOCK")
            .name("Physical Block")
            .description("A physical defensive move.")
            .category(MoveCategory.DEFENSIVE)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.DEFENSIVE))
            .apCost(5)
            .unleashPoint(1)
            .freeMove(true)
            .build();

        var snapshot = session(160L, physicalBlock, physicalBlock).snapshot();
        var move = snapshot.player(PlayerSide.PLAYER_ONE).orElseThrow()
            .character().knownMoves().get(0);

        assertTrue(move.tags().contains(MoveTag.PHYSICAL.name()));
        assertTrue(move.tags().contains(MoveTag.DEFENSIVE.name()));
    }

    @Test
    void connectionLifecycleAndForcedEndAreVersionedOnlyOnChanges() {
        Move attack = physicalAttack("CONNECTION_ATTACK", 1, true);
        HeadlessBattleSession session = session(17L, attack, attack);
        long deadline = 1_800_000_000_000L;
        long activeVersion = session.getStateVersion();

        MatchState disconnected = session.setConnected("player-2", false, deadline);
        assertEquals(MatchStatus.OPPONENT_DISCONNECTED, disconnected.status());
        assertEquals(activeVersion + 1, disconnected.stateVersion());
        PlayerState disconnectedPlayer = disconnected.player(PlayerSide.PLAYER_TWO).orElseThrow();
        assertFalse(disconnectedPlayer.connected());
        assertEquals(deadline, disconnectedPlayer.disconnectDeadline());

        assertEquals(
            activeVersion + 1,
            session.setConnected("player-2", false, deadline).stateVersion());

        MatchState reconnected = session.setConnected("player-2", true, deadline);
        assertEquals(MatchStatus.ACTIVE, reconnected.status());
        assertEquals(activeVersion + 2, reconnected.stateVersion());
        assertTrue(reconnected.player(PlayerSide.PLAYER_TWO).orElseThrow().connected());
        assertNull(reconnected.player(PlayerSide.PLAYER_TWO).orElseThrow().disconnectDeadline());

        MatchState ended = session.forceEnd(null, MatchStatus.ABANDONED, "ABANDONED");
        assertEquals(MatchStatus.ABANDONED, ended.status());
        assertEquals(BattlePhase.BATTLE_OVER, ended.phase());
        assertEquals(activeVersion + 3, ended.stateVersion());
        assertNull(ended.winnerSide());
        assertNull(ended.winnerPlayerId());
        assertEquals("ABANDONED", ended.endReason());
    }

    @Test
    void forceEndDuringRoundGatePreservesResolvedRoundPlayback() {
        Move attack = physicalAttack("PLAYBACK_ATTACK", 1, true);
        HeadlessBattleSession session = session(171L, attack, attack);
        submitBoth(session, attack, attack);
        MatchState roundEnd = session.snapshot();
        assertEquals(BattlePhase.ROUND_END, roundEnd.phase());

        MatchState ended = session.forceEnd(
            "player-1", MatchStatus.ENDED, "DISCONNECT_TIMEOUT");

        assertEquals(roundEnd.roundNumber(), ended.roundNumber());
        assertTrue(ended.recentEvents().size() > 1);
        assertTrue(ended.recentEvents().stream()
            .allMatch(event -> event.roundNumber() == ended.roundNumber()));
        assertEquals(BattleEventType.BATTLE_OVER,
            ended.recentEvents().get(ended.recentEvents().size() - 1).type());
    }

    @Test
    void configurableRoundLimitEndsAnUnresolvedFightAsDraw() {
        Move harmless = physicalAttack("ROUND_LIMIT_ATTACK", 1, true);
        HeadlessBattleSession session = session(18L, harmless, harmless, 1);

        submitBoth(session, harmless, harmless);
        MatchState state = session.snapshot();

        assertEquals(MatchStatus.ENDED, state.status());
        assertEquals(BattlePhase.BATTLE_OVER, state.phase());
        assertEquals("MAX_ROUNDS_REACHED", state.endReason());
        assertNull(state.winnerPlayerId());
        assertEquals(1, state.roundNumber());
        assertEquals(4, state.stateVersion());
        assertTrue(state.players().stream().allMatch(PlayerState::planSubmitted));
        assertTrue(state.players().stream().noneMatch(PlayerState::readyForNextRound));
        assertEquals(2, state.roundStartCharacterStates().size());
        assertTrue(state.recentEvents().stream().anyMatch(
            event -> event.type() == BattleEventType.BATTLE_OVER
        ));
    }

    @Test
    void sessionWaitsForBothPlayersAndBlocksCommandsDuringDisconnects() {
        Move attack = physicalAttack("WAITING_ATTACK", 1, true);
        HeadlessBattleSession session = waitingSession(19L, attack, attack, 50);

        MatchState initial = session.snapshot();
        assertEquals(MatchStatus.WAITING, initial.status());
        assertFalse(initial.players().get(0).connected());
        assertFalse(initial.players().get(1).connected());
        CommandResult beforeJoin = session.applyCommand(
            "player-1",
            command(session, "before-join", new PlanPlacement(attack.getId(), 1))
        );
        assertFalse(beforeJoin.accepted());
        assertEquals("MATCH_NOT_READY", beforeJoin.error().code());

        MatchState firstJoin = session.setConnected("player-1", true);
        assertEquals(MatchStatus.WAITING, firstJoin.status());
        assertEquals(1, firstJoin.stateVersion());
        assertTrue(session.hasJoined("player-1"));
        assertFalse(session.hasJoined("player-2"));

        MatchState secondJoin = session.setConnected("player-2", true);
        assertEquals(MatchStatus.ACTIVE, secondJoin.status());
        assertEquals(2, secondJoin.stateVersion());

        MatchState disconnected = session.setConnected("player-2", false, 1234L);
        assertEquals(MatchStatus.OPPONENT_DISCONNECTED, disconnected.status());
        CommandResult whileDisconnected = session.applyCommand(
            "player-1",
            command(session, "while-disconnected", new PlanPlacement(attack.getId(), 1))
        );
        assertFalse(whileDisconnected.accepted());
        assertEquals("OPPONENT_DISCONNECTED", whileDisconnected.error().code());
    }

    @Test
    void roundEndPassiveKnockoutEndsAuthoritativeSession() {
        Move attack = physicalAttack("ROUND_END_ATTACK", 1, true);
        AbilityData ability = new AbilityData();
        ability.id = "ROUND_END_KILL";
        ability.name = "Round End Kill";
        ability.category = "PASSIVE";
        ability.sourceType = "CHARACTER";
        ability.activationCondition = AbilityConditionType.PHASE_REACHED.createDefault();
        ability.activationCondition.phase = "ROUND_END";
        ability.effects = List.of(AbilityEffectType.INSTANT_KILL.createDefault());
        CharacterStats stats = new CharacterStats.Builder().build();
        Character playerOne = new SorcererCharacter(
            "character-1", "Character One", stats, null,
            List.of(attack), List.of(new Ability(ability)));
        Character playerTwo = new SorcererCharacter(
            "character-2", "Character Two", stats, null, List.of(attack));
        HeadlessBattleSession session = new HeadlessBattleSession(
            "match-1",
            new MatchParticipant("player-1", "Player One", playerOne, PlayerSide.PLAYER_ONE),
            new MatchParticipant("player-2", "Player Two", playerTwo, PlayerSide.PLAYER_TWO),
            10L,
            HeadlessBattleSession.DEFAULT_MAX_ROUNDS,
            FIXED_CLOCK);
        session.setConnected("player-1", true);
        session.setConnected("player-2", true);

        assertTrue(session.applyCommand(
            "player-1", command(session, "round-end-1", new PlanPlacement(attack.getId(), 1)))
            .accepted());
        CommandResult result = session.applyCommand(
            "player-2", command(session, "round-end-2", new PlanPlacement(attack.getId(), 1)));

        assertTrue(result.accepted());
        assertEquals(MatchStatus.ENDED, result.state().status());
        assertEquals(BattlePhase.BATTLE_OVER, result.state().phase());
        assertEquals("player-1", result.state().winnerPlayerId());
        assertTrue(result.events().stream().anyMatch(event -> event.type() == BattleEventType.BATTLE_OVER));
    }

    private static void submitBoth(HeadlessBattleSession session, Move first, Move second) {
        submitBoth(session, first, second, "");
    }

    private static void submitBoth(
        HeadlessBattleSession session,
        Move first,
        Move second,
        String commandSuffix
    ) {
        assertTrue(session.applyCommand(
            "player-1",
            command(session, "command-1" + commandSuffix, new PlanPlacement(first.getId(), 1))
        ).accepted());
        assertTrue(session.applyCommand(
            "player-2",
            command(session, "command-2" + commandSuffix, new PlanPlacement(second.getId(), 1))
        ).accepted());
    }

    private static ActionCommand readyAt(String commandId, long version) {
        return ActionCommand.readyNextRound(commandId, "match-1", version);
    }

    private static ActionCommand command(
        HeadlessBattleSession session,
        String commandId,
        PlanPlacement... placements
    ) {
        return commandAt(commandId, session.getStateVersion(), placements);
    }

    private static ActionCommand commandAt(
        String commandId,
        long version,
        PlanPlacement... placements
    ) {
        return ActionCommand.submitPlan(commandId, "match-1", version, List.of(placements));
    }

    private static HeadlessBattleSession session(long seed, Move first, Move second) {
        return session(seed, first, second, HeadlessBattleSession.DEFAULT_MAX_ROUNDS);
    }

    private static HeadlessBattleSession session(long seed, Move first, Move second, int maxRounds) {
        HeadlessBattleSession session = waitingSession(seed, first, second, maxRounds);
        session.setConnected("player-1", true);
        session.setConnected("player-2", true);
        return session;
    }

    private static HeadlessBattleSession waitingSession(
        long seed,
        Move first,
        Move second,
        int maxRounds
    ) {
        CharacterStats stats = new CharacterStats.Builder()
            .cursedEnergyEfficiency(160)
            .build();
        Character playerOne = new SorcererCharacter(
            "character-1", "Character One", stats, null, List.of(first));
        Character playerTwo = new SorcererCharacter(
            "character-2", "Character Two", stats, null, List.of(second));
        return new HeadlessBattleSession(
            "match-1",
            new MatchParticipant("player-1", "Player One", playerOne, PlayerSide.PLAYER_ONE),
            new MatchParticipant("player-2", "Player Two", playerTwo, PlayerSide.PLAYER_TWO),
            seed,
            maxRounds,
            FIXED_CLOCK
        );
    }

    private static Move physicalAttack(String id, int power, boolean neverMiss) {
        return new Move.Builder(id)
            .name(id)
            .description("A test physical attack.")
            .category(MoveCategory.PHYSICAL)
            .basePower(power)
            .baseAccuracy(0.75)
            .neverMiss(neverMiss)
            .apCost(5)
            .unleashPoint(1)
            .freeMove(true)
            .build();
    }

    private static Move ceAttack(String id, int power, int baseCeCost) {
        return new Move.Builder(id)
            .name(id)
            .description("A test cursed-energy attack.")
            .category(MoveCategory.CURSED_ENERGY)
            .basePower(power)
            .neverMiss(true)
            .apCost(5)
            .unleashPoint(1)
            .baseCeCost(baseCeCost)
            .hasCeCost(true)
            .minCeCost(1)
            .maxCeCost(baseCeCost * 2)
            .freeMove(true)
            .build();
    }
}
