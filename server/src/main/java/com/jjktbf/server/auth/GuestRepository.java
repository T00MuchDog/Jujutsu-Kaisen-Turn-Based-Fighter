package com.jjktbf.server.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

final class GuestRepository {
    boolean displayNameExists(Connection connection, String normalizedName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT 1 FROM guest_player WHERE normalized_display_name = ?")) {
            statement.setString(1, normalizedName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    void insertGuest(
        Connection connection,
        String playerId,
        String displayName,
        String normalizedName,
        long createdAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO guest_player "
                + "(id, display_name, normalized_display_name, created_at) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, playerId);
            statement.setString(2, displayName);
            statement.setString(3, normalizedName);
            statement.setLong(4, createdAt);
            statement.executeUpdate();
        }
    }

    void insertSession(
        Connection connection,
        String sessionId,
        String playerId,
        String tokenHash,
        long createdAt,
        long expiresAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO guest_session "
                + "(id, player_id, token_hash, created_at, expires_at, revoked_at) "
                + "VALUES (?, ?, ?, ?, ?, NULL)")) {
            statement.setString(1, sessionId);
            statement.setString(2, playerId);
            statement.setString(3, tokenHash);
            statement.setLong(4, createdAt);
            statement.setLong(5, expiresAt);
            statement.executeUpdate();
        }
    }

    Optional<GuestSessionRecord> findSession(Connection connection, String sessionId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT s.id, s.player_id, p.display_name, s.token_hash, "
                + "s.expires_at, s.revoked_at "
                + "FROM guest_session s "
                + "JOIN guest_player p ON p.id = s.player_id "
                + "WHERE s.id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                long revokedAt = result.getLong("revoked_at");
                Long nullableRevokedAt = result.wasNull() ? null : revokedAt;
                return Optional.of(new GuestSessionRecord(
                    result.getString("id"),
                    result.getString("player_id"),
                    result.getString("display_name"),
                    result.getString("token_hash"),
                    result.getLong("expires_at"),
                    nullableRevokedAt
                ));
            }
        }
    }
}
