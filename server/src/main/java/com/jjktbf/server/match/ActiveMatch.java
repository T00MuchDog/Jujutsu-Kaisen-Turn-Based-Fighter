package com.jjktbf.server.match;

import com.jjktbf.multiplayer.engine.HeadlessBattleSession;
import com.jjktbf.server.challenge.AcceptedMatchParticipant;
import com.jjktbf.server.challenge.AcceptedMatchSetup;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/** Mutable state guarded by synchronizing on the ActiveMatch instance. */
final class ActiveMatch {
    final AcceptedMatchSetup setup;
    final HeadlessBattleSession session;
    final Map<String, ActiveParticipant> participants = new LinkedHashMap<>();
    boolean completionPersisted;
    boolean completionBroadcast;
    ScheduledFuture<?> completionRetryTask;
    ScheduledFuture<?> cleanupTask;

    ActiveMatch(AcceptedMatchSetup setup, HeadlessBattleSession session) {
        this.setup = setup;
        this.session = session;
        participants.put(
            setup.playerOne().playerId(), new ActiveParticipant(setup.playerOne()));
        participants.put(
            setup.playerTwo().playerId(), new ActiveParticipant(setup.playerTwo()));
    }

    ActiveParticipant participant(String playerId) {
        return participants.get(playerId);
    }

    static final class ActiveParticipant {
        final AcceptedMatchParticipant accepted;
        MatchConnection connection;
        Long disconnectDeadline;
        ScheduledFuture<?> disconnectTask;

        ActiveParticipant(AcceptedMatchParticipant accepted) {
            this.accepted = accepted;
        }
    }
}
