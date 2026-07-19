package com.jjktbf.server.challenge;

import com.jjktbf.multiplayer.protocol.ChallengeAcceptRequest;
import com.jjktbf.multiplayer.protocol.ChallengeCreateRequest;
import com.jjktbf.multiplayer.protocol.ChallengeDecisionRequest;
import com.jjktbf.multiplayer.protocol.ChallengeStatus;
import com.jjktbf.multiplayer.protocol.ChallengeSummary;
import com.jjktbf.multiplayer.protocol.MatchStatus;
import com.jjktbf.multiplayer.protocol.PlayerSide;
import com.jjktbf.multiplayer.protocol.ProtocolVersion;
import com.jjktbf.multiplayer.protocol.SessionIdentity;
import com.jjktbf.server.service.ServiceException;
import com.jjktbf.server.support.ServerTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChallengeServiceTest {
    private ServerTestFixture fixture;
    private String firstCharacter;
    private String secondCharacter;

    @BeforeEach
    void setUp() {
        fixture = new ServerTestFixture();
        firstCharacter = fixture.catalog().characterSummaries().get(0).characterId();
        secondCharacter = fixture.catalog().characterSummaries().get(1).characterId();
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void createsAndPersistsAnOpenChallenge() {
        SessionIdentity host = fixture.createGuest("Challenge Host");

        ChallengeSummary challenge = fixture.challengeService().createChallenge(
            host, ChallengeCreateRequest.standard(firstCharacter));

        assertEquals(host.playerId(), challenge.hostPlayerId());
        assertEquals(ChallengeStatus.OPEN, challenge.status());
        assertEquals(firstCharacter, challenge.hostCharacterId());
        assertEquals(fixture.clock().millis(), challenge.createdAt());
        assertEquals(Duration.ofMinutes(5).toMillis(),
            challenge.expiresAt() - challenge.createdAt());
        assertNull(challenge.requestedPlayerId());
        assertNull(challenge.requestedCharacterId());
        assertNull(challenge.requestedAt());
        assertNull(challenge.acceptedJoinRequestId());
        assertNull(challenge.matchId());
        assertEquals(1, count("challenge"));
        assertEquals(challenge, fixture.challengeService().createChallenge(
            host, ChallengeCreateRequest.standard(firstCharacter)));
        assertEquals(challenge, fixture.challengeService().getHostedChallenge(host));
        assertEquals(1, count("challenge"));
    }

    @Test
    void listingExcludesOwnNonOpenIncompatibleAndExpiredChallenges() {
        SessionIdentity caller = fixture.createGuest("List Caller");
        SessionIdentity visibleHost = fixture.createGuest("Visible Host");
        SessionIdentity otherHost = fixture.createGuest("Other Host");
        String thirdCharacter = fixture.catalog().characterSummaries().get(2).characterId();

        fixture.challengeService().createChallenge(
            caller, ChallengeCreateRequest.standard(firstCharacter));
        ChallengeSummary visible = fixture.challengeService().createChallenge(
            visibleHost, ChallengeCreateRequest.standard(firstCharacter));
        ChallengeSummary cancelled = fixture.challengeService().createChallenge(
            otherHost, ChallengeCreateRequest.standard(firstCharacter));
        fixture.challengeService().cancelChallenge(otherHost, cancelled.challengeId());
        ChallengeSummary incompatible = fixture.challengeService().createChallenge(
            visibleHost, ChallengeCreateRequest.standard(secondCharacter));
        ChallengeSummary expired = fixture.challengeService().createChallenge(
            visibleHost, ChallengeCreateRequest.standard(thirdCharacter));

        updateGameVersion(incompatible.challengeId(), "old-version");
        updateExpiry(expired.challengeId(), fixture.clock().millis());

        List<ChallengeSummary> listed = fixture.challengeService()
            .listOpenChallenges(caller).challenges();

        assertEquals(List.of(visible.challengeId()),
            listed.stream().map(ChallengeSummary::challengeId).toList());
        assertEquals(ChallengeStatus.EXPIRED,
            fixture.challengeService().getChallenge(expired.challengeId()).status());

        fixture.challengeService().requestJoin(
            caller, visible.challengeId(), ChallengeAcceptRequest.standard(secondCharacter));
        assertTrue(fixture.challengeService().listOpenChallenges(otherHost).challenges().stream()
            .noneMatch(item -> item.challengeId().equals(visible.challengeId())));
    }

    @Test
    void cancellationRequiresCreatorAndOpenState() {
        SessionIdentity host = fixture.createGuest("Cancel Host");
        SessionIdentity stranger = fixture.createGuest("Cancel Stranger");
        ChallengeSummary challenge = fixture.challengeService().createChallenge(
            host, ChallengeCreateRequest.standard(firstCharacter));
        fixture.challengeService().requestJoin(
            stranger,
            challenge.challengeId(),
            ChallengeAcceptRequest.standard(secondCharacter)
        );

        ServiceException forbidden = assertThrows(
            ServiceException.class,
            () -> fixture.challengeService().cancelChallenge(
                stranger, challenge.challengeId())
        );
        assertEquals("FORBIDDEN", forbidden.code());
        assertEquals(ChallengeStatus.OPEN,
            fixture.challengeService().getChallenge(challenge.challengeId()).status());

        ChallengeSummary cancelled = fixture.challengeService().cancelChallenge(
            host, challenge.challengeId());
        assertEquals(ChallengeStatus.CANCELLED, cancelled.status());
        assertNull(cancelled.requestedPlayerId());

        ServiceException noLongerOpen = assertThrows(
            ServiceException.class,
            () -> fixture.challengeService().cancelChallenge(host, challenge.challengeId())
        );
        assertEquals("CHALLENGE_CANCELLED", noLongerOpen.code());
    }

    @Test
    void operationsExpireStaleChallenges() {
        SessionIdentity host = fixture.createGuest("Expiry Host");
        SessionIdentity accepter = fixture.createGuest("Expiry Accepter");
        ChallengeSummary challenge = fixture.challengeService().createChallenge(
            host, ChallengeCreateRequest.standard(firstCharacter));
        fixture.challengeService().requestJoin(
            accepter,
            challenge.challengeId(),
            ChallengeAcceptRequest.standard(secondCharacter)
        );
        fixture.clock().advance(Duration.ofMinutes(6));

        ChallengeSummary expired = fixture.challengeService().getChallenge(challenge.challengeId());
        assertEquals(ChallengeStatus.EXPIRED, expired.status());
        assertNull(expired.requestedPlayerId());
        assertTrue(fixture.challengeService().listOpenChallenges(accepter)
            .challenges().isEmpty());
        ServiceException failure = assertThrows(
            ServiceException.class,
            () -> fixture.challengeService().requestJoin(
                accepter,
                challenge.challengeId(),
                ChallengeAcceptRequest.standard(secondCharacter))
        );
        assertEquals("CHALLENGE_EXPIRED", failure.code());
    }

    @Test
    void rejectsSelfJoinRequestWithoutCreatingAMatch() {
        SessionIdentity host = fixture.createGuest("Solo Host");
        ChallengeSummary challenge = fixture.challengeService().createChallenge(
            host, ChallengeCreateRequest.standard(firstCharacter));

        ServiceException failure = assertThrows(
            ServiceException.class,
            () -> fixture.challengeService().requestJoin(
                host,
                challenge.challengeId(),
                ChallengeAcceptRequest.standard(secondCharacter))
        );

        assertEquals("CANNOT_ACCEPT_OWN_CHALLENGE", failure.code());
        assertEquals(0, count("match_record"));
        assertEquals(ChallengeStatus.OPEN,
            fixture.challengeService().getChallenge(challenge.challengeId()).status());
    }

    @Test
    void joinRequestIsIdempotentAndHostAcceptancePersistsAssignedParticipants() {
        SessionIdentity host = fixture.createGuest("Match Host");
        SessionIdentity accepter = fixture.createGuest("Match Accepter");
        ChallengeSummary challenge = fixture.challengeService().createChallenge(
            host, ChallengeCreateRequest.standard(firstCharacter));

        ChallengeSummary requested = fixture.challengeService().requestJoin(
            accepter,
            challenge.challengeId(),
            ChallengeAcceptRequest.standard(secondCharacter)
        );
        ChallengeSummary retried = fixture.challengeService().requestJoin(
            accepter,
            challenge.challengeId(),
            ChallengeAcceptRequest.standard(secondCharacter)
        );

        assertEquals(accepter.playerId(), requested.requestedPlayerId());
        assertEquals(secondCharacter, requested.requestedCharacterId());
        assertEquals(fixture.clock().millis(), requested.requestedAt());
        assertEquals(requested, retried);
        assertEquals(requested, fixture.challengeService().getRequestedChallenge(accepter));
        assertEquals(0, count("match_record"));

        AcceptedMatchSetup setup = fixture.challengeService().acceptChallenge(
            host, challenge.challengeId(), ChallengeDecisionRequest.forChallenge(requested));

        assertEquals(MatchStatus.WAITING, setup.status());
        assertEquals(PlayerSide.PLAYER_ONE, setup.playerOne().side());
        assertEquals(host.playerId(), setup.playerOne().playerId());
        assertEquals(firstCharacter, setup.playerOne().characterId());
        assertEquals(PlayerSide.PLAYER_TWO, setup.playerTwo().side());
        assertEquals(accepter.playerId(), setup.playerTwo().playerId());
        assertEquals(secondCharacter, setup.playerTwo().characterId());
        assertNotEquals(setup.playerOne().character(), setup.playerTwo().character());

        fixture.database().withConnection(connection -> {
            try (PreparedStatement match = connection.prepareStatement(
                "SELECT challenge_id, status, server_seed FROM match_record WHERE id = ?")) {
                match.setString(1, setup.matchId());
                try (var result = match.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(challenge.challengeId(), result.getString("challenge_id"));
                    assertEquals("WAITING", result.getString("status"));
                    assertEquals(setup.serverSeed(), result.getLong("server_seed"));
                }
            }
            try (PreparedStatement participants = connection.prepareStatement(
                "SELECT player_id, side, character_id FROM match_participant "
                    + "WHERE match_id = ? ORDER BY side")) {
                participants.setString(1, setup.matchId());
                try (var result = participants.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(host.playerId(), result.getString("player_id"));
                    assertEquals("PLAYER_ONE", result.getString("side"));
                    assertEquals(firstCharacter, result.getString("character_id"));
                    assertTrue(result.next());
                    assertEquals(accepter.playerId(), result.getString("player_id"));
                    assertEquals("PLAYER_TWO", result.getString("side"));
                    assertEquals(secondCharacter, result.getString("character_id"));
                }
            }
            return null;
        });

        ChallengeSummary accepted = fixture.challengeService()
            .getChallenge(challenge.challengeId());
        assertEquals(ChallengeStatus.ACCEPTED, accepted.status());
        assertEquals(setup.matchId(), accepted.matchId());
        assertEquals(requested.joinRequestId(), accepted.acceptedJoinRequestId());
        assertNull(accepted.requestedPlayerId());
        assertNull(accepted.requestedCharacterId());
        assertNull(accepted.requestedAt());
        assertEquals(2, count("match_participant"));
        assertEquals(accepted, fixture.challengeService().getRequestedChallenge(accepter));
    }

    @Test
    void competingJoinRequestIsRejectedAndAcceptanceRetryReusesTheMatch() {
        SessionIdentity host = fixture.createGuest("Single Host");
        SessionIdentity first = fixture.createGuest("First Accepter");
        SessionIdentity second = fixture.createGuest("Second Accepter");
        ChallengeSummary challenge = fixture.challengeService().createChallenge(
            host, ChallengeCreateRequest.standard(firstCharacter));

        ChallengeSummary requested = fixture.challengeService().requestJoin(
            first,
            challenge.challengeId(),
            ChallengeAcceptRequest.standard(secondCharacter)
        );
        ServiceException failure = assertThrows(
            ServiceException.class,
            () -> fixture.challengeService().requestJoin(
                second,
                challenge.challengeId(),
                ChallengeAcceptRequest.standard(secondCharacter))
        );

        assertEquals("CHALLENGE_REQUEST_PENDING", failure.code());
        AcceptedMatchSetup firstSetup = fixture.challengeService().acceptChallenge(
            host, challenge.challengeId(), ChallengeDecisionRequest.forChallenge(requested));
        AcceptedMatchSetup retriedSetup = fixture.challengeService().acceptChallenge(
            host, challenge.challengeId(), ChallengeDecisionRequest.forChallenge(requested));
        assertEquals(firstSetup, retriedSetup);
        assertEquals(1, count("match_record"));
        assertEquals(2, count("match_participant"));
    }

    @Test
    void onlyHostCanAcceptOrRejectAndRejectionReopensTheChallenge() {
        SessionIdentity host = fixture.createGuest("Decision Host");
        SessionIdentity requester = fixture.createGuest("Decision Requester");
        SessionIdentity stranger = fixture.createGuest("Decision Stranger");
        ChallengeSummary challenge = fixture.challengeService().createChallenge(
            host, ChallengeCreateRequest.standard(firstCharacter));
        ChallengeSummary requested = fixture.challengeService().requestJoin(
            requester,
            challenge.challengeId(),
            ChallengeAcceptRequest.standard(secondCharacter)
        );

        assertEquals("FORBIDDEN", assertThrows(
            ServiceException.class,
            () -> fixture.challengeService().acceptChallenge(
                requester,
                challenge.challengeId(),
                ChallengeDecisionRequest.forChallenge(requested))
        ).code());
        assertEquals("FORBIDDEN", assertThrows(
            ServiceException.class,
            () -> fixture.challengeService().rejectJoinRequest(
                stranger,
                challenge.challengeId(),
                ChallengeDecisionRequest.forChallenge(requested))
        ).code());

        ChallengeSummary rejected = fixture.challengeService().rejectJoinRequest(
            host, challenge.challengeId(), ChallengeDecisionRequest.forChallenge(requested));
        assertEquals(ChallengeStatus.OPEN, rejected.status());
        assertNull(rejected.requestedPlayerId());
        assertNull(rejected.requestedCharacterId());
        assertNull(rejected.requestedAt());
        assertEquals(List.of(challenge.challengeId()),
            fixture.challengeService().listOpenChallenges(stranger).challenges().stream()
                .map(ChallengeSummary::challengeId)
                .toList());
        assertEquals(rejected, fixture.challengeService().rejectJoinRequest(
            host, challenge.challengeId(), ChallengeDecisionRequest.forChallenge(requested)));
    }

    @Test
    void requesterCanOnlyHoldOnePendingChallengeAndCanWithdrawIt() {
        SessionIdentity firstHost = fixture.createGuest("First Pending Host");
        SessionIdentity secondHost = fixture.createGuest("Second Pending Host");
        SessionIdentity requester = fixture.createGuest("Single Pending Requester");
        ChallengeSummary first = fixture.challengeService().createChallenge(
            firstHost, ChallengeCreateRequest.standard(firstCharacter));
        ChallengeSummary second = fixture.challengeService().createChallenge(
            secondHost, ChallengeCreateRequest.standard(firstCharacter));

        ChallengeSummary pending = fixture.challengeService().requestJoin(
            requester, first.challengeId(), ChallengeAcceptRequest.standard(secondCharacter));
        ServiceException limited = assertThrows(ServiceException.class, () ->
            fixture.challengeService().requestJoin(
                requester,
                second.challengeId(),
                ChallengeAcceptRequest.standard(secondCharacter)));
        assertEquals("TOO_MANY_PENDING_REQUESTS", limited.code());

        ChallengeSummary withdrawn = fixture.challengeService().withdrawJoinRequest(
            requester,
            first.challengeId(),
            ChallengeDecisionRequest.forChallenge(pending)
        );
        assertNull(withdrawn.joinRequestId());
        ChallengeSummary next = fixture.challengeService().requestJoin(
            requester, second.challengeId(), ChallengeAcceptRequest.standard(secondCharacter));
        assertEquals(requester.playerId(), next.requestedPlayerId());
    }

    @Test
    void staleHostDecisionCannotTargetAReplacementRequest() {
        SessionIdentity host = fixture.createGuest("Stale Decision Host");
        SessionIdentity requester = fixture.createGuest("Repeated Requester");
        ChallengeSummary challenge = fixture.challengeService().createChallenge(
            host, ChallengeCreateRequest.standard(firstCharacter));
        ChallengeSummary first = fixture.challengeService().requestJoin(
            requester, challenge.challengeId(), ChallengeAcceptRequest.standard(secondCharacter));
        ChallengeDecisionRequest staleDecision = ChallengeDecisionRequest.forChallenge(first);

        fixture.challengeService().rejectJoinRequest(
            host, challenge.challengeId(), staleDecision);
        ChallengeSummary replacement = fixture.challengeService().requestJoin(
            requester, challenge.challengeId(), ChallengeAcceptRequest.standard(secondCharacter));
        assertNotEquals(first.joinRequestId(), replacement.joinRequestId());

        ServiceException stale = assertThrows(ServiceException.class, () ->
            fixture.challengeService().acceptChallenge(
                host, challenge.challengeId(), staleDecision));
        assertEquals("CHALLENGE_NO_PENDING_REQUEST", stale.code());
        assertEquals(replacement.joinRequestId(), fixture.challengeService()
            .getChallenge(challenge.challengeId()).joinRequestId());
    }

    @Test
    void staleDecisionsCannotReuseAReplacementAcceptedMatch() {
        SessionIdentity host = fixture.createGuest("Accepted Retry Host");
        SessionIdentity requester = fixture.createGuest("Accepted Retry Requester");
        ChallengeSummary challenge = fixture.challengeService().createChallenge(
            host, ChallengeCreateRequest.standard(firstCharacter));
        ChallengeSummary first = fixture.challengeService().requestJoin(
            requester, challenge.challengeId(), ChallengeAcceptRequest.standard(secondCharacter));
        ChallengeDecisionRequest staleDecision = ChallengeDecisionRequest.forChallenge(first);
        fixture.challengeService().rejectJoinRequest(host, challenge.challengeId(), staleDecision);

        ChallengeSummary replacement = fixture.challengeService().requestJoin(
            requester, challenge.challengeId(), ChallengeAcceptRequest.standard(secondCharacter));
        AcceptedMatchSetup accepted = fixture.challengeService().acceptChallenge(
            host,
            challenge.challengeId(),
            ChallengeDecisionRequest.forChallenge(replacement)
        );

        ServiceException stale = assertThrows(ServiceException.class, () ->
            fixture.challengeService().acceptChallenge(
                host, challenge.challengeId(), staleDecision));
        assertEquals("CHALLENGE_NO_PENDING_REQUEST", stale.code());
        assertEquals(accepted.matchId(), fixture.challengeService()
            .getChallenge(challenge.challengeId()).matchId());
        assertEquals(1, count("match_record"));
    }

    @Test
    void staleWithdrawalCannotClearAReplacementRequest() {
        SessionIdentity host = fixture.createGuest("Withdrawal Host");
        SessionIdentity requester = fixture.createGuest("Withdrawal Requester");
        ChallengeSummary challenge = fixture.challengeService().createChallenge(
            host, ChallengeCreateRequest.standard(firstCharacter));
        ChallengeSummary first = fixture.challengeService().requestJoin(
            requester, challenge.challengeId(), ChallengeAcceptRequest.standard(secondCharacter));
        ChallengeDecisionRequest staleDecision = ChallengeDecisionRequest.forChallenge(first);
        fixture.challengeService().rejectJoinRequest(host, challenge.challengeId(), staleDecision);
        ChallengeSummary replacement = fixture.challengeService().requestJoin(
            requester, challenge.challengeId(), ChallengeAcceptRequest.standard(secondCharacter));

        ServiceException stale = assertThrows(ServiceException.class, () ->
            fixture.challengeService().withdrawJoinRequest(
                requester, challenge.challengeId(), staleDecision));

        assertEquals("CHALLENGE_NO_PENDING_REQUEST", stale.code());
        ChallengeSummary current = fixture.challengeService().getChallenge(challenge.challengeId());
        assertEquals(replacement.joinRequestId(), current.joinRequestId());
        assertNotNull(current.requestedPlayerId());
    }

    @Test
    void legacyAcceptedChallengeWithoutRequestIdentityIsNotRecoverable() {
        SessionIdentity host = fixture.createGuest("Legacy Host");
        SessionIdentity requester = fixture.createGuest("Legacy Requester");
        ChallengeSummary challenge = fixture.challengeService().createChallenge(
            host, ChallengeCreateRequest.standard(firstCharacter));
        ChallengeSummary pending = fixture.challengeService().requestJoin(
            requester, challenge.challengeId(), ChallengeAcceptRequest.standard(secondCharacter));
        fixture.challengeService().acceptChallenge(
            host, challenge.challengeId(), ChallengeDecisionRequest.forChallenge(pending));
        fixture.database().withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE challenge SET accepted_join_request_id = NULL WHERE id = ?")) {
                statement.setString(1, challenge.challengeId());
                statement.executeUpdate();
                return null;
            }
        });

        assertEquals("CHALLENGE_NOT_FOUND", assertThrows(
            ServiceException.class,
            () -> fixture.challengeService().getHostedChallenge(host)
        ).code());
        assertEquals("CHALLENGE_NOT_FOUND", assertThrows(
            ServiceException.class,
            () -> fixture.challengeService().getRequestedChallenge(requester)
        ).code());
    }

    @Test
    void validatesCompatibilityCharactersAndOpenLimit() {
        SessionIdentity host = fixture.createGuest("Validation Host");
        ChallengeCreateRequest incompatible = new ChallengeCreateRequest(
            firstCharacter,
            "old",
            ProtocolVersion.PROTOCOL_VERSION,
            ProtocolVersion.STANDARD_RULESET
        );
        assertEquals("INCOMPATIBLE_VERSION", assertThrows(
            ServiceException.class,
            () -> fixture.challengeService().createChallenge(host, incompatible)
        ).code());
        assertEquals("INVALID_CHARACTER", assertThrows(
            ServiceException.class,
            () -> fixture.challengeService().createChallenge(
                host, ChallengeCreateRequest.standard("unknown"))
        ).code());

        List<String> characters = fixture.catalog().characterSummaries().stream()
            .map(summary -> summary.characterId())
            .limit(fixture.config().maxOpenChallenges())
            .toList();
        List<ChallengeSummary> open = characters.stream()
            .map(character -> fixture.challengeService().createChallenge(
                host, ChallengeCreateRequest.standard(character)))
            .toList();
        assertEquals(fixture.config().maxOpenChallenges(), open.size());
        updateHostCharacter(open.get(0).challengeId(), secondCharacter);
        assertEquals("TOO_MANY_OPEN_CHALLENGES", assertThrows(
            ServiceException.class,
            () -> fixture.challengeService().createChallenge(
                host, ChallengeCreateRequest.standard(firstCharacter))
        ).code());
    }

    private int count(String table) {
        return fixture.database().withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table);
                 var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        });
    }

    private void updateGameVersion(String challengeId, String version) {
        fixture.database().withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE challenge SET game_version = ? WHERE id = ?")) {
                statement.setString(1, version);
                statement.setString(2, challengeId);
                statement.executeUpdate();
                return null;
            }
        });
    }

    private void updateExpiry(String challengeId, long expiresAt) {
        fixture.database().withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE challenge SET expires_at = ? WHERE id = ?")) {
                statement.setLong(1, expiresAt);
                statement.setString(2, challengeId);
                statement.executeUpdate();
                return null;
            }
        });
    }

    private void updateHostCharacter(String challengeId, String characterId) {
        fixture.database().withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE challenge SET host_character_id = ? WHERE id = ?")) {
                statement.setString(1, characterId);
                statement.setString(2, challengeId);
                statement.executeUpdate();
                return null;
            }
        });
    }
}
