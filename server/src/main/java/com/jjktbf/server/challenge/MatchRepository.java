package com.jjktbf.server.challenge;

import com.jjktbf.multiplayer.protocol.MatchStatus;
import com.jjktbf.multiplayer.protocol.PlayerSide;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;

final class MatchRepository {
    void insertMatch(
        Connection connection,
        String matchId,
        String challengeId,
        MatchStatus status,
        long serverSeed,
        String gameVersion,
        int protocolVersion,
        String ruleset,
        long createdAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO match_record ("
                + "id, challenge_id, status, server_seed, game_version, "
                + "protocol_version, ruleset, created_at, started_at, ended_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL)")) {
            statement.setString(1, matchId);
            statement.setString(2, challengeId);
            statement.setString(3, status.name());
            statement.setLong(4, serverSeed);
            statement.setString(5, gameVersion);
            statement.setInt(6, protocolVersion);
            statement.setString(7, ruleset);
            statement.setLong(8, createdAt);
            statement.executeUpdate();
        }
    }

    void insertParticipant(
        Connection connection,
        String matchId,
        String playerId,
        PlayerSide side,
        String characterId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO match_participant "
                + "(match_id, player_id, side, character_id, joined_at, disconnected_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, matchId);
            statement.setString(2, playerId);
            statement.setString(3, side.name());
            statement.setString(4, characterId);
            statement.setNull(5, Types.BIGINT);
            statement.setNull(6, Types.BIGINT);
            statement.executeUpdate();
        }
    }

    Optional<PersistedMatch> findByChallenge(Connection connection, String challengeId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id, status, server_seed, created_at FROM match_record WHERE challenge_id = ?")) {
            statement.setString(1, challengeId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PersistedMatch(
                    result.getString("id"),
                    MatchStatus.valueOf(result.getString("status")),
                    result.getLong("server_seed"),
                    result.getLong("created_at")
                ));
            }
        }
    }

    record PersistedMatch(
        String matchId,
        MatchStatus status,
        long serverSeed,
        long createdAt
    ) {
    }
}
