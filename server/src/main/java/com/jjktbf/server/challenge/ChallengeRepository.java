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
            "UPDATE challenge SET status = 'EXPIRED' "
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

    int countOpenByCreator(Connection connection, String playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM challenge "
                + "WHERE creator_player_id = ? AND status = 'OPEN'")) {
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
                + "created_at, expires_at, accepted_player_id, accepted_character_id, "
                + "accepted_at, match_id FROM challenge WHERE id = ?")) {
            statement.setString(1, challengeId);
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
                + "created_at, expires_at, accepted_player_id, accepted_character_id, "
                + "accepted_at, match_id FROM challenge "
                + "WHERE status = 'OPEN' AND expires_at > ? "
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
            "UPDATE challenge SET status = 'CANCELLED' "
                + "WHERE id = ? AND creator_player_id = ? AND status = 'OPEN'")) {
            statement.setString(1, challengeId);
            statement.setString(2, creatorPlayerId);
            return statement.executeUpdate();
        }
    }

    int acceptOpen(
        Connection connection,
        String challengeId,
        String acceptedPlayerId,
        String acceptedCharacterId,
        long acceptedAt,
        String matchId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE challenge SET status = 'ACCEPTED', accepted_player_id = ?, "
                + "accepted_character_id = ?, accepted_at = ?, match_id = ? "
                + "WHERE id = ? AND status = 'OPEN' AND expires_at > ?")) {
            statement.setString(1, acceptedPlayerId);
            statement.setString(2, acceptedCharacterId);
            statement.setLong(3, acceptedAt);
            statement.setString(4, matchId);
            statement.setString(5, challengeId);
            statement.setLong(6, acceptedAt);
            return statement.executeUpdate();
        }
    }

    private static ChallengeRecord map(ResultSet result) throws SQLException {
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
            result.getString("accepted_player_id"),
            result.getString("accepted_character_id"),
            nullableAcceptedAt,
            result.getString("match_id")
        );
    }
}
