package com.jjktbf.server.match;

import com.jjktbf.multiplayer.protocol.MatchStatus;
import com.jjktbf.multiplayer.protocol.PlayerSide;
import com.jjktbf.server.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Focused persistence boundary for active match lifecycle changes. */
public final class MatchPersistenceRepository {
    private final Database database;

    public MatchPersistenceRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    void recordJoined(
        String matchId,
        String playerId,
        MatchStatus status,
        long joinedAt
    ) {
        database.transaction(connection -> {
            requireParticipantUpdate(updateJoined(connection, matchId, playerId, joinedAt));
            updateLiveStatus(connection, matchId, status, joinedAt);
            return null;
        });
    }

    void recordDisconnected(
        String matchId,
        String playerId,
        MatchStatus status,
        long disconnectedAt
    ) {
        database.transaction(connection -> {
            requireParticipantUpdate(
                updateDisconnected(connection, matchId, playerId, disconnectedAt));
            updateLiveStatus(connection, matchId, status, disconnectedAt);
            return null;
        });
    }

    void recordCompletion(
        String matchId,
        MatchStatus status,
        String winnerPlayerId,
        MatchResultType resultType,
        String reason,
        long completedAt
    ) {
        if (status != MatchStatus.ENDED && status != MatchStatus.ABANDONED) {
            throw new IllegalArgumentException("Completion status must be terminal");
        }
        Objects.requireNonNull(resultType, "resultType");
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE match_record SET status = ?, ended_at = COALESCE(ended_at, ?) "
                    + "WHERE id = ?")) {
                statement.setString(1, status.name());
                statement.setLong(2, completedAt);
                statement.setString(3, matchId);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Persisted match does not exist: " + matchId);
                }
            }

            if (!resultExists(connection, matchId)) {
                insertResult(
                    connection,
                    matchId,
                    winnerPlayerId,
                    resultType,
                    reason,
                    completedAt
                );
            }
            return null;
        });
    }

    public Optional<StoredMatch> findMatch(String matchId) {
        return database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, challenge_id, status, server_seed, game_version, "
                    + "protocol_version, ruleset, created_at, started_at, ended_at "
                    + "FROM match_record WHERE id = ?")) {
                statement.setString(1, matchId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(readMatch(result)) : Optional.empty();
                }
            }
        });
    }

    public List<StoredParticipant> findParticipants(String matchId) {
        return database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT match_id, player_id, side, character_id, joined_at, disconnected_at "
                    + "FROM match_participant WHERE match_id = ? ORDER BY side")) {
                statement.setString(1, matchId);
                try (ResultSet result = statement.executeQuery()) {
                    List<StoredParticipant> participants = new ArrayList<>();
                    while (result.next()) {
                        participants.add(new StoredParticipant(
                            result.getString("match_id"),
                            result.getString("player_id"),
                            PlayerSide.valueOf(result.getString("side")),
                            result.getString("character_id"),
                            nullableLong(result, "joined_at"),
                            nullableLong(result, "disconnected_at")
                        ));
                    }
                    return List.copyOf(participants);
                }
            }
        });
    }

    public Optional<StoredResult> findResult(String matchId) {
        return database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT match_id, winner_player_id, result_type, reason, completed_at "
                    + "FROM match_result WHERE match_id = ?")) {
                statement.setString(1, matchId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new StoredResult(
                        result.getString("match_id"),
                        result.getString("winner_player_id"),
                        MatchResultType.valueOf(result.getString("result_type")),
                        result.getString("reason"),
                        result.getLong("completed_at")
                    ));
                }
            }
        });
    }

    public int countResults(String matchId) {
        return database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM match_result WHERE match_id = ?")) {
                statement.setString(1, matchId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        });
    }

    private static int updateJoined(
        Connection connection,
        String matchId,
        String playerId,
        long joinedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE match_participant "
                + "SET joined_at = COALESCE(joined_at, ?), disconnected_at = NULL "
                + "WHERE match_id = ? AND player_id = ?")) {
            statement.setLong(1, joinedAt);
            statement.setString(2, matchId);
            statement.setString(3, playerId);
            return statement.executeUpdate();
        }
    }

    private static int updateDisconnected(
        Connection connection,
        String matchId,
        String playerId,
        long disconnectedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE match_participant SET disconnected_at = ? "
                + "WHERE match_id = ? AND player_id = ?")) {
            statement.setLong(1, disconnectedAt);
            statement.setString(2, matchId);
            statement.setString(3, playerId);
            return statement.executeUpdate();
        }
    }

    private static void updateLiveStatus(
        Connection connection,
        String matchId,
        MatchStatus status,
        long changedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE match_record SET status = ?, "
                + "started_at = CASE WHEN ? = 'ACTIVE' "
                + "THEN COALESCE(started_at, ?) ELSE started_at END "
                + "WHERE id = ? AND status NOT IN ('ENDED', 'ABANDONED')")) {
            statement.setString(1, status.name());
            statement.setString(2, status.name());
            statement.setLong(3, changedAt);
            statement.setString(4, matchId);
            statement.executeUpdate();
        }
    }

    private static boolean resultExists(Connection connection, String matchId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT 1 FROM match_result WHERE match_id = ?")) {
            statement.setString(1, matchId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void insertResult(
        Connection connection,
        String matchId,
        String winnerPlayerId,
        MatchResultType resultType,
        String reason,
        long completedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO match_result "
                + "(match_id, winner_player_id, result_type, reason, completed_at) "
                + "VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, matchId);
            if (winnerPlayerId == null) {
                statement.setNull(2, Types.VARCHAR);
            } else {
                statement.setString(2, winnerPlayerId);
            }
            statement.setString(3, resultType.name());
            if (reason == null) {
                statement.setNull(4, Types.VARCHAR);
            } else {
                statement.setString(4, reason);
            }
            statement.setLong(5, completedAt);
            statement.executeUpdate();
        }
    }

    private static StoredMatch readMatch(ResultSet result) throws SQLException {
        return new StoredMatch(
            result.getString("id"),
            result.getString("challenge_id"),
            MatchStatus.valueOf(result.getString("status")),
            result.getLong("server_seed"),
            result.getString("game_version"),
            result.getInt("protocol_version"),
            result.getString("ruleset"),
            result.getLong("created_at"),
            nullableLong(result, "started_at"),
            nullableLong(result, "ended_at")
        );
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static void requireParticipantUpdate(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("Persisted match participant does not exist");
        }
    }

    public record StoredMatch(
        String matchId,
        String challengeId,
        MatchStatus status,
        long serverSeed,
        String gameVersion,
        int protocolVersion,
        String ruleset,
        long createdAt,
        Long startedAt,
        Long endedAt
    ) {
    }

    public record StoredParticipant(
        String matchId,
        String playerId,
        PlayerSide side,
        String characterId,
        Long joinedAt,
        Long disconnectedAt
    ) {
    }

    public record StoredResult(
        String matchId,
        String winnerPlayerId,
        MatchResultType resultType,
        String reason,
        long completedAt
    ) {
    }
}
