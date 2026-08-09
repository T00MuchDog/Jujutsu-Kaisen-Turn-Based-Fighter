package com.jjktbf.server.challenge;

import com.jjktbf.model.combat.BattleFormat;
import com.jjktbf.multiplayer.protocol.ChallengeStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class ChallengeRepository {
    private static final int MAX_LISTED_CHALLENGES = 100;

    /**
     * Canonical column list for every challenge SELECT. {@code host_character_ids},
     * {@code requested_character_ids}, {@code accepted_character_ids} hold the
     * ordered roster (comma-joined); the legacy single columns are read only for
     * back-fill during migration recovery and are otherwise ignored.
     */
    private static final String SELECT_COLUMNS =
        "id, creator_player_id, creator_display_name, status, "
            + "game_version, protocol_version, ruleset, format, "
            + "host_character_ids, created_at, expires_at, join_request_id, "
            + "requested_player_id, requested_character_ids, requested_at, "
            + "accepted_player_id, accepted_character_ids, accepted_at, "
            + "accepted_join_request_id, match_id ";

    int expireOpen(Connection connection, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE challenge SET status = 'EXPIRED', join_request_id = NULL, "
                + "requested_player_id = NULL, "
                + "requested_character_ids = NULL, requested_at = NULL "
                + "WHERE status = 'OPEN' AND expires_at <= ?")) {
            statement.setLong(1, now);
            return statement.executeUpdate();
        }
    }

    boolean lockCreator(Connection connection, String playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM guest_player WHERE id = ? FOR UPDATE")) {
            statement.setString(1, playerId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    int countOpenByCreator(
        Connection connection,
        String playerId,
        BattleFormat format,
        String gameVersion,
        int protocolVersion,
        String ruleset
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM challenge "
                + "WHERE creator_player_id = ? AND status = 'OPEN' AND format = ? "
                + "AND game_version = ? AND protocol_version = ? AND ruleset = ?")) {
            statement.setString(1, playerId);
            statement.setString(2, format.name());
            statement.setString(3, gameVersion);
            statement.setInt(4, protocolVersion);
            statement.setString(5, ruleset);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    int countPendingByRequester(Connection connection, String playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM challenge WHERE status = 'OPEN' "
                + "AND requested_player_id = ?")) {
            statement.setString(1, playerId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    void insert(Connection connection, ChallengeRecord challenge) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO challenge ("
                + "id, creator_player_id, creator_display_name, status, "
                + "game_version, protocol_version, ruleset, format, host_character_ids, "
                + "created_at, expires_at, accepted_player_id, accepted_character_ids, "
                + "accepted_at, match_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL)")) {
            statement.setString(1, challenge.challengeId());
            statement.setString(2, challenge.creatorPlayerId());
            statement.setString(3, challenge.creatorDisplayName());
            statement.setString(4, challenge.status().name());
            statement.setString(5, challenge.gameVersion());
            statement.setInt(6, challenge.protocolVersion());
            statement.setString(7, challenge.ruleset());
            statement.setString(8, challenge.format().name());
            statement.setString(9, RosterCodec.encode(challenge.hostCharacterIds()));
            statement.setLong(10, challenge.createdAt());
            statement.setLong(11, challenge.expiresAt());
            statement.executeUpdate();
        }
    }

    Optional<ChallengeRecord> findById(Connection connection, String challengeId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT " + SELECT_COLUMNS + "FROM challenge WHERE id = ?")) {
            statement.setString(1, challengeId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    Optional<ChallengeRecord> findByMatchId(Connection connection, String matchId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT " + SELECT_COLUMNS + "FROM challenge WHERE match_id = ?")) {
            statement.setString(1, matchId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    Optional<ChallengeRecord> findRecoverableRequest(
        Connection connection,
        String playerId,
        String gameVersion,
        int protocolVersion,
        String ruleset
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT c.id, c.creator_player_id, c.creator_display_name, c.status, "
                + "c.game_version, c.protocol_version, c.ruleset, c.format, "
                + "c.host_character_ids, c.created_at, c.expires_at, c.join_request_id, "
                + "c.requested_player_id, c.requested_character_ids, c.requested_at, "
                + "c.accepted_player_id, c.accepted_character_ids, c.accepted_at, "
                + "c.accepted_join_request_id, c.match_id FROM challenge c "
                + "LEFT JOIN match_record m ON m.id = c.match_id "
                + "WHERE c.game_version = ? AND c.protocol_version = ? AND c.ruleset = ? AND ("
                + "(c.status = 'OPEN' AND c.requested_player_id = ?) "
                + "OR (c.status = 'ACCEPTED' AND c.accepted_player_id = ? "
                + "AND c.accepted_join_request_id IS NOT NULL "
                + "AND m.status IN ('WAITING', 'ACTIVE'))) "
                + "ORDER BY COALESCE(c.accepted_at, c.requested_at) DESC, c.id DESC")) {
            statement.setString(1, gameVersion);
            statement.setInt(2, protocolVersion);
            statement.setString(3, ruleset);
            statement.setString(4, playerId);
            statement.setString(5, playerId);
            statement.setMaxRows(1);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    Optional<ChallengeRecord> findRecoverableHosted(
        Connection connection,
        String playerId,
        String gameVersion,
        int protocolVersion,
        String ruleset
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT c.id, c.creator_player_id, c.creator_display_name, c.status, "
                + "c.game_version, c.protocol_version, c.ruleset, c.format, "
                + "c.host_character_ids, c.created_at, c.expires_at, c.join_request_id, "
                + "c.requested_player_id, c.requested_character_ids, c.requested_at, "
                + "c.accepted_player_id, c.accepted_character_ids, c.accepted_at, "
                + "c.accepted_join_request_id, c.match_id FROM challenge c "
                + "LEFT JOIN match_record m ON m.id = c.match_id "
                + "WHERE c.creator_player_id = ? AND c.game_version = ? "
                + "AND c.protocol_version = ? AND c.ruleset = ? AND (c.status = 'OPEN' "
                + "OR (c.status = 'ACCEPTED' AND c.accepted_join_request_id IS NOT NULL "
                + "AND m.status IN ('WAITING', 'ACTIVE'))) "
                + "ORDER BY COALESCE(c.accepted_at, c.created_at) DESC, c.id DESC")) {
            statement.setString(1, playerId);
            statement.setString(2, gameVersion);
            statement.setInt(3, protocolVersion);
            statement.setString(4, ruleset);
            statement.setMaxRows(1);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    Optional<ChallengeRecord> findMatchingOpenByCreator(
        Connection connection,
        String playerId,
        BattleFormat format,
        List<String> characterIds,
        String gameVersion,
        int protocolVersion,
        String ruleset
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT " + SELECT_COLUMNS + "FROM challenge "
                + "WHERE creator_player_id = ? AND status = 'OPEN' AND format = ? "
                + "AND host_character_ids = ? AND game_version = ? "
                + "AND protocol_version = ? AND ruleset = ? "
                + "ORDER BY created_at DESC, id DESC")) {
            statement.setString(1, playerId);
            statement.setString(2, format.name());
            statement.setString(3, RosterCodec.encode(characterIds));
            statement.setString(4, gameVersion);
            statement.setInt(5, protocolVersion);
            statement.setString(6, ruleset);
            statement.setMaxRows(1);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    List<ChallengeRecord> listCompatibleOpen(
        Connection connection,
        String excludedPlayerId,
        BattleFormat format,
        String gameVersion,
        int protocolVersion,
        String ruleset,
        long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT " + SELECT_COLUMNS + "FROM challenge "
                + "WHERE status = 'OPEN' AND requested_player_id IS NULL AND expires_at > ? "
                + "AND creator_player_id <> ? AND format = ? "
                + "AND game_version = ? AND protocol_version = ? AND ruleset = ? "
                + "ORDER BY created_at ASC, id ASC")) {
            statement.setLong(1, now);
            statement.setString(2, excludedPlayerId);
            statement.setString(3, format == null ? null : format.name());
            statement.setString(4, gameVersion);
            statement.setInt(5, protocolVersion);
            statement.setString(6, ruleset);
            statement.setMaxRows(MAX_LISTED_CHALLENGES);
            try (ResultSet result = statement.executeQuery()) {
                List<ChallengeRecord> challenges = new ArrayList<>();
                while (result.next()) {
                    challenges.add(map(result));
                }
                return challenges;
            }
        }
    }

    int cancelOpen(Connection connection, String challengeId, String creatorPlayerId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE challenge SET status = 'CANCELLED', join_request_id = NULL, "
                + "requested_player_id = NULL, "
                + "requested_character_ids = NULL, requested_at = NULL "
                + "WHERE id = ? AND creator_player_id = ? AND status = 'OPEN'")) {
            statement.setString(1, challengeId);
            statement.setString(2, creatorPlayerId);
            return statement.executeUpdate();
        }
    }

    int requestJoinOpen(
        Connection connection,
        String challengeId,
        String joinRequestId,
        String requestedPlayerId,
        List<String> requestedCharacterIds,
        long requestedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE challenge SET join_request_id = ?, requested_player_id = ?, "
                + "requested_character_ids = ?, requested_at = ? "
                + "WHERE id = ? AND status = 'OPEN' AND expires_at > ? "
                + "AND requested_player_id IS NULL")) {
            statement.setString(1, joinRequestId);
            statement.setString(2, requestedPlayerId);
            statement.setString(3, RosterCodec.encode(requestedCharacterIds));
            statement.setLong(4, requestedAt);
            statement.setString(5, challengeId);
            statement.setLong(6, requestedAt);
            return statement.executeUpdate();
        }
    }

    int acceptPendingOpen(
        Connection connection,
        String challengeId,
        String creatorPlayerId,
        String expectedRequestId,
        String expectedRequesterId,
        long expectedRequestedAt,
        long acceptedAt,
        String matchId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE challenge SET status = 'ACCEPTED', "
                + "accepted_player_id = requested_player_id, "
                + "accepted_character_ids = requested_character_ids, accepted_at = ?, "
                + "match_id = ?, accepted_join_request_id = join_request_id, "
                + "join_request_id = NULL, requested_player_id = NULL, "
                + "requested_character_ids = NULL, "
                + "requested_at = NULL WHERE id = ? AND creator_player_id = ? "
                + "AND status = 'OPEN' AND expires_at > ? "
                + "AND join_request_id = ? AND requested_player_id = ? AND requested_at = ?")) {
            statement.setLong(1, acceptedAt);
            statement.setString(2, matchId);
            statement.setString(3, challengeId);
            statement.setString(4, creatorPlayerId);
            statement.setLong(5, acceptedAt);
            statement.setString(6, expectedRequestId);
            statement.setString(7, expectedRequesterId);
            statement.setLong(8, expectedRequestedAt);
            return statement.executeUpdate();
        }
    }

    int rejectPendingOpen(
        Connection connection,
        String challengeId,
        String creatorPlayerId,
        String expectedRequestId,
        String expectedRequesterId,
        long expectedRequestedAt,
        long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE challenge SET join_request_id = NULL, requested_player_id = NULL, "
                + "requested_character_ids = NULL, requested_at = NULL "
                + "WHERE id = ? AND creator_player_id = ? AND status = 'OPEN' "
                + "AND expires_at > ? AND join_request_id = ? "
                + "AND requested_player_id = ? AND requested_at = ?")) {
            statement.setString(1, challengeId);
            statement.setString(2, creatorPlayerId);
            statement.setLong(3, now);
            statement.setString(4, expectedRequestId);
            statement.setString(5, expectedRequesterId);
            statement.setLong(6, expectedRequestedAt);
            return statement.executeUpdate();
        }
    }

    int withdrawPendingOpen(
        Connection connection,
        String challengeId,
        String requesterPlayerId,
        String expectedRequestId,
        long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE challenge SET join_request_id = NULL, requested_player_id = NULL, "
                + "requested_character_ids = NULL, requested_at = NULL "
                + "WHERE id = ? AND status = 'OPEN' AND expires_at > ? "
                + "AND requested_player_id = ? AND join_request_id = ?")) {
            statement.setString(1, challengeId);
            statement.setLong(2, now);
            statement.setString(3, requesterPlayerId);
            statement.setString(4, expectedRequestId);
            return statement.executeUpdate();
        }
    }

    Optional<String> findPlayerDisplayName(Connection connection, String playerId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT display_name FROM guest_player WHERE id = ?")) {
            statement.setString(1, playerId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                    ? Optional.of(result.getString("display_name"))
                    : Optional.empty();
            }
        }
    }

    private static ChallengeRecord map(ResultSet result) throws SQLException {
        long requestedAt = result.getLong("requested_at");
        Long nullableRequestedAt = result.wasNull() ? null : requestedAt;
        long acceptedAt = result.getLong("accepted_at");
        Long nullableAcceptedAt = result.wasNull() ? null : acceptedAt;
        BattleFormat format = BattleFormat.valueOf(result.getString("format"));
        return new ChallengeRecord(
            result.getString("id"),
            result.getString("creator_player_id"),
            result.getString("creator_display_name"),
            ChallengeStatus.valueOf(result.getString("status")),
            result.getString("game_version"),
            result.getInt("protocol_version"),
            result.getString("ruleset"),
            format,
            RosterCodec.decodeOrEmpty(result.getString("host_character_ids")),
            result.getLong("created_at"),
            result.getLong("expires_at"),
            result.getString("join_request_id"),
            result.getString("requested_player_id"),
            RosterCodec.decodeOrEmpty(result.getString("requested_character_ids")),
            nullableRequestedAt,
            result.getString("accepted_player_id"),
            RosterCodec.decodeOrEmpty(result.getString("accepted_character_ids")),
            nullableAcceptedAt,
            result.getString("accepted_join_request_id"),
            result.getString("match_id")
        );
    }
}
