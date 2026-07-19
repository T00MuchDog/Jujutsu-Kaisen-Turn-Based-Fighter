package com.jjktbf.server.challenge;

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
    int expireOpen(Connection connection, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE challenge SET status = 'EXPIRED', join_request_id = NULL, "
                + "requested_player_id = NULL, "
                + "requested_character_id = NULL, requested_at = NULL "
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
        String gameVersion,
        int protocolVersion,
        String ruleset
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM challenge "
                + "WHERE creator_player_id = ? AND status = 'OPEN' "
                + "AND game_version = ? AND protocol_version = ? AND ruleset = ?")) {
            statement.setString(1, playerId);
            statement.setString(2, gameVersion);
            statement.setInt(3, protocolVersion);
            statement.setString(4, ruleset);
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
                + "game_version, protocol_version, ruleset, host_character_id, "
                + "created_at, expires_at, accepted_player_id, accepted_character_id, "
                + "accepted_at, match_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL)")) {
            statement.setString(1, challenge.challengeId());
            statement.setString(2, challenge.creatorPlayerId());
            statement.setString(3, challenge.creatorDisplayName());
            statement.setString(4, challenge.status().name());
            statement.setString(5, challenge.gameVersion());
            statement.setInt(6, challenge.protocolVersion());
            statement.setString(7, challenge.ruleset());
            statement.setString(8, challenge.hostCharacterId());
            statement.setLong(9, challenge.createdAt());
            statement.setLong(10, challenge.expiresAt());
            statement.executeUpdate();
        }
    }

    Optional<ChallengeRecord> findById(Connection connection, String challengeId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id, creator_player_id, creator_display_name, status, "
                + "game_version, protocol_version, ruleset, host_character_id, "
                + "created_at, expires_at, join_request_id, requested_player_id, requested_character_id, "
                + "requested_at, accepted_player_id, accepted_character_id, accepted_at, "
                + "accepted_join_request_id, match_id FROM challenge WHERE id = ?")) {
            statement.setString(1, challengeId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    Optional<ChallengeRecord> findByMatchId(Connection connection, String matchId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id, creator_player_id, creator_display_name, status, "
                + "game_version, protocol_version, ruleset, host_character_id, "
                + "created_at, expires_at, join_request_id, requested_player_id, "
                + "requested_character_id, requested_at, accepted_player_id, "
                + "accepted_character_id, accepted_at, accepted_join_request_id, "
                + "match_id FROM challenge WHERE match_id = ?")) {
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
                + "c.game_version, c.protocol_version, c.ruleset, c.host_character_id, "
                + "c.created_at, c.expires_at, c.join_request_id, c.requested_player_id, "
                + "c.requested_character_id, c.requested_at, c.accepted_player_id, "
                + "c.accepted_character_id, c.accepted_at, c.accepted_join_request_id, "
                + "c.match_id FROM challenge c LEFT JOIN match_record m ON m.id = c.match_id "
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
                + "c.game_version, c.protocol_version, c.ruleset, c.host_character_id, "
                + "c.created_at, c.expires_at, c.join_request_id, c.requested_player_id, "
                + "c.requested_character_id, c.requested_at, c.accepted_player_id, "
                + "c.accepted_character_id, c.accepted_at, c.accepted_join_request_id, "
                + "c.match_id FROM challenge c LEFT JOIN match_record m ON m.id = c.match_id "
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
        String characterId,
        String gameVersion,
        int protocolVersion,
        String ruleset
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id, creator_player_id, creator_display_name, status, "
                + "game_version, protocol_version, ruleset, host_character_id, "
                + "created_at, expires_at, join_request_id, requested_player_id, "
                + "requested_character_id, requested_at, accepted_player_id, "
                + "accepted_character_id, accepted_at, accepted_join_request_id, "
                + "match_id FROM challenge WHERE creator_player_id = ? AND status = 'OPEN' "
                + "AND host_character_id = ? AND game_version = ? "
                + "AND protocol_version = ? AND ruleset = ? "
                + "ORDER BY created_at DESC, id DESC")) {
            statement.setString(1, playerId);
            statement.setString(2, characterId);
            statement.setString(3, gameVersion);
            statement.setInt(4, protocolVersion);
            statement.setString(5, ruleset);
            statement.setMaxRows(1);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    List<ChallengeRecord> listCompatibleOpen(
        Connection connection,
        String excludedPlayerId,
        String gameVersion,
        int protocolVersion,
        String ruleset,
        long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id, creator_player_id, creator_display_name, status, "
                + "game_version, protocol_version, ruleset, host_character_id, "
                + "created_at, expires_at, join_request_id, requested_player_id, requested_character_id, "
                + "requested_at, accepted_player_id, accepted_character_id, accepted_at, "
                + "accepted_join_request_id, match_id FROM challenge "
                + "WHERE status = 'OPEN' AND requested_player_id IS NULL AND expires_at > ? "
                + "AND creator_player_id <> ? AND game_version = ? "
                + "AND protocol_version = ? AND ruleset = ? "
                + "ORDER BY created_at ASC, id ASC")) {
            statement.setLong(1, now);
            statement.setString(2, excludedPlayerId);
            statement.setString(3, gameVersion);
            statement.setInt(4, protocolVersion);
            statement.setString(5, ruleset);
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
                + "requested_character_id = NULL, requested_at = NULL "
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
        String requestedCharacterId,
        long requestedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE challenge SET join_request_id = ?, requested_player_id = ?, "
                + "requested_character_id = ?, requested_at = ? "
                + "WHERE id = ? AND status = 'OPEN' AND expires_at > ? "
                + "AND requested_player_id IS NULL")) {
            statement.setString(1, joinRequestId);
            statement.setString(2, requestedPlayerId);
            statement.setString(3, requestedCharacterId);
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
                + "accepted_character_id = requested_character_id, accepted_at = ?, "
                + "match_id = ?, accepted_join_request_id = join_request_id, "
                + "join_request_id = NULL, requested_player_id = NULL, "
                + "requested_character_id = NULL, "
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
                + "requested_character_id = NULL, requested_at = NULL "
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
                + "requested_character_id = NULL, requested_at = NULL "
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
        return new ChallengeRecord(
            result.getString("id"),
            result.getString("creator_player_id"),
            result.getString("creator_display_name"),
            ChallengeStatus.valueOf(result.getString("status")),
            result.getString("game_version"),
            result.getInt("protocol_version"),
            result.getString("ruleset"),
            result.getString("host_character_id"),
            result.getLong("created_at"),
            result.getLong("expires_at"),
            result.getString("join_request_id"),
            result.getString("requested_player_id"),
            result.getString("requested_character_id"),
            nullableRequestedAt,
            result.getString("accepted_player_id"),
            result.getString("accepted_character_id"),
            nullableAcceptedAt,
            result.getString("accepted_join_request_id"),
            result.getString("match_id")
        );
    }
}
