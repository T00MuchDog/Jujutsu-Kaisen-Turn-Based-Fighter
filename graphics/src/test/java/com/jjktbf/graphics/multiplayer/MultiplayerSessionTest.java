package com.jjktbf.graphics.multiplayer;

import com.jjktbf.multiplayer.protocol.MatchStatus;
import com.jjktbf.multiplayer.protocol.PlanPlacement;
import com.jjktbf.multiplayer.protocol.CommandType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiplayerSessionTest {
    @Test
    void commandUsesLatestVersionAndNewerStateDoesNotImplyAcceptance() {
        MultiplayerSession session = connectedSession(7);

        MultiplayerSession.CommandStart started = session.beginPlanCommand(
            "command-one", List.of(new PlanPlacement("move-one", 3)));

        assertTrue(started.ready());
        assertEquals(MultiplayerTestData.MATCH_ID, started.command().matchId());
        assertEquals(7, started.command().expectedStateVersion());
        assertEquals(1, started.command().payload().placements().size());

        MultiplayerSession.StateUpdate equal = session.updateAuthoritativeState(
            MultiplayerTestData.state(7));
        assertTrue(equal.applied());
        assertTrue(session.pendingCommand().isPresent());

        MultiplayerSession.StateUpdate newer = session.updateAuthoritativeState(
            MultiplayerTestData.state(8));
        assertTrue(newer.applied());
        assertTrue(session.pendingCommand().isPresent());
        assertEquals(8, session.latestState().orElseThrow().stateVersion());
    }

    @Test
    void rejectsStaleStatesAndPreventsConcurrentLocalCommands() {
        MultiplayerSession session = connectedSession(5);
        assertTrue(session.beginPlanCommand("first", List.of()).ready());

        MultiplayerSession.CommandStart second =
            session.beginPlanCommand("second", List.of());
        MultiplayerSession.StateUpdate stale = session.updateAuthoritativeState(
            MultiplayerTestData.state(4));

        assertEquals(MultiplayerSession.CommandStartStatus.ALREADY_PENDING,
            second.status());
        assertFalse(stale.applied());
        assertEquals(5, session.latestState().orElseThrow().stateVersion());
    }

    @Test
    void terminalStateClearsPendingEvenWithoutVersionAdvance() {
        MultiplayerSession session = connectedSession(3);
        session.beginPlanCommand("pending", List.of());

        MultiplayerSession.StateUpdate ended = session.updateAuthoritativeState(
            MultiplayerTestData.state(3, MatchStatus.ENDED));

        assertEquals("pending", ended.clearedPendingCommand().commandId());
        assertTrue(session.pendingCommand().isEmpty());
        assertEquals(MultiplayerSession.CommandStartStatus.MATCH_ENDED,
            session.beginPlanCommand("late", List.of()).status());
    }

    @Test
    void nextRoundCommandUsesLatestVersionAndHasNoPlanPayload() {
        MultiplayerSession session = connectedSession(9);

        MultiplayerSession.CommandStart started =
            session.beginReadyNextRoundCommand("ready-command");

        assertTrue(started.ready());
        assertEquals(CommandType.READY_NEXT_ROUND, started.command().type());
        assertEquals(9, started.command().expectedStateVersion());
        assertEquals(null, started.command().payload());
        assertEquals(MultiplayerSession.CommandStartStatus.ALREADY_PENDING,
            session.beginReadyNextRoundCommand("second-ready").status());
    }

    private static MultiplayerSession connectedSession(long version) {
        MultiplayerSession session = new MultiplayerSession();
        session.setGuestCredentials(MultiplayerTestData.credentials("token"));
        session.setMatchSetup(MultiplayerTestData.setup(version));
        session.setConnectionState(MultiplayerSession.ConnectionState.CONNECTED);
        return session;
    }
}
