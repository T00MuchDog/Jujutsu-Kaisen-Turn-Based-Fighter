package com.jjktbf.server.challenge;

import com.jjktbf.multiplayer.protocol.ChallengeAcceptRequest;
import com.jjktbf.multiplayer.protocol.ChallengeCreateRequest;
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
        assertNull(challenge.matchId());
        assertEquals(1, count("challenge"));
    }

    @Test
    void listingExcludesOwnNonOpenIncompatibleAndExpiredChallenges() {
        SessionIdentity caller = fixture.createGuest("List Caller");
        SessionIdentity visibleHost = fixture.createGuest("Visible Host");
        SessionIdentity otherHost = fixture.createGuest("Other Host");

        fixture.challengeService().createChallenge(
            caller, ChallengeCreateRequest.standard(firstCharacter));
        ChallengeSummary visible = fixture.challengeService().createChallenge(
            visibleHost, ChallengeCreateRequest.standard(firstCharacter));
        ChallengeSummary cancelled = fixture.challengeService().createChallenge(
            otherHost, ChallengeCreateRequest.standard(firstCharacter));
        fixture.challengeService().cancelChallenge(otherHost, cancelled.challengeId());
        ChallengeSummary incompatible = fixture.challengeService().createChallenge(
            visibleHost, ChallengeCreateRequest.standard(firstCharacter));
        ChallengeSummary expired = fixture.challengeService().createChallenge(
            visibleHost, ChallengeCreateRequest.standard(firstCharacter));

        updateGameVersion(incompatible.challengeId(), "old-version");
        updateExpiry(expired.challengeId(), fixture.clock().millis());

        List<ChallengeSummary> listed = fixture.challengeService()
            .listOpenChallenges(caller).challenges();

        assertEquals(List.of(visible.challengeId()),
            listed.stream().map(ChallengeSummary::challengeId).toList());
        assertEquals(ChallengeStatus.EXPIRED,
            fixture.challengeService().getChallenge(expired.challengeId()).status());
    }

    @Test
    void cancellationRequiresCreatorAndOpenState() {
        SessionIdentity host = fixture.createGuest("Cancel Host");
        SessionIdentity stranger = fixture.createGuest("Cancel Stranger");
        ChallengeSummary challenge = fixture.challengeService().createChallenge(
            host, ChallengeCreateRequest.standard(firstCharacter));

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
        fixture.clock().advance(Duration.ofMinutes(6));

        assertEquals(ChallengeStatus.EXPIRED,
            fixture.challengeService().getChallenge(challenge.challengeId()).status());
        assertTrue(fixture.challengeService().listOpenChallenges(accepter)
            .challenges().isEmpty());
        ServiceException failure = assertThrows(
            ServiceException.class,
            () -> fixture.challengeService().acceptChallenge(
                accepter,
                challenge.challengeId(),
                ChallengeAcceptRequest.standard(secondCharacter))
        );
        assertEquals("CHALLENGE_EXPIRED", failure.code());
    }

    @Test
    void rejectsSelfAcceptanceWithoutCreatingAMatch() {
        SessionIdentity host = fixture.createGuest("Solo Host");
        ChallengeSummary challenge = fixture.challengeService().createChallenge(
            host, ChallengeCreateRequest.standard(firstCharacter));

        ServiceException failure = assertThrows(
            ServiceException.class,
            () -> fixture.challengeService().acceptChallenge(
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
    void acceptancePersistsSeedMatchAndServerAssignedParticipants() {
        SessionIdentity host = fixture.createGuest("Match Host");
        SessionIdentity accepter = fixture.createGuest("Match Accepter");
        ChallengeSummary challenge = fixture.challengeService().createChallenge(
            host, ChallengeCreateRequest.standard(firstCharacter));

        AcceptedMatchSetup setup = fixture.challengeService().acceptChallenge(
            accepter,
            challenge.challengeId(),
            ChallengeAcceptRequest.standard(secondCharacter)
        );

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
        assertEquals(2, count("match_participant"));
    }

    @Test
    void aSecondAcceptanceCannotCreateAnotherMatch() {
        SessionIdentity host = fixture.createGuest("Single Host");
        SessionIdentity first = fixture.createGuest("First Accepter");
        SessionIdentity second = fixture.createGuest("Second Accepter");
        ChallengeSummary challenge = fixture.challengeService().createChallenge(
            host, ChallengeCreateRequest.standard(firstCharacter));

        fixture.challengeService().acceptChallenge(
            first,
            challenge.challengeId(),
            ChallengeAcceptRequest.standard(secondCharacter)
        );
        ServiceException failure = assertThrows(
            ServiceException.class,
            () -> fixture.challengeService().acceptChallenge(
                second,
                challenge.challengeId(),
                ChallengeAcceptRequest.standard(secondCharacter))
        );

        assertEquals("CHALLENGE_ALREADY_ACCEPTED", failure.code());
        assertEquals(1, count("match_record"));
        assertEquals(2, count("match_participant"));
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

        for (int i = 0; i < fixture.config().maxOpenChallenges(); i++) {
            fixture.challengeService().createChallenge(
                host, ChallengeCreateRequest.standard(firstCharacter));
        }
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
}
