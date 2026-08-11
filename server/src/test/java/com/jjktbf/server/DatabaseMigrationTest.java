package com.jjktbf.server;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseMigrationTest {
    @Test
    void upgradesExistingV3ChallengesToTeamRosters() throws Exception {
        String url = "jdbc:h2:mem:migration_"
            + UUID.randomUUID().toString().replace("-", "")
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
            + ";DB_CLOSE_DELAY=-1";

        migrate(url, "3");
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO guest_player "
                + "(id, display_name, normalized_display_name, created_at) "
                + "VALUES ('host', 'Host', 'host', 1), "
                + "('requester', 'Requester', 'requester', 1), "
                + "('accepted-player', 'Accepted', 'accepted', 1)");
            statement.executeUpdate("INSERT INTO challenge "
                + "(id, creator_player_id, creator_display_name, status, game_version, "
                + "protocol_version, ruleset, host_character_id, created_at, expires_at) "
                + "VALUES ('existing', 'host', 'Host', 'OPEN', '1.3.0', 11, "
                + "'STANDARD', '000000', 1, 999)");
            statement.executeUpdate("INSERT INTO challenge "
                + "(id, creator_player_id, creator_display_name, status, game_version, "
                + "protocol_version, ruleset, host_character_id, created_at, expires_at, "
                + "join_request_id, requested_player_id, requested_character_id, requested_at) "
                + "VALUES ('pending', 'host', 'Host', 'OPEN', '1.3.0', 11, "
                + "'STANDARD', '000001', 2, 999, 'request', 'requester', '000002', 3)");
            statement.executeUpdate("INSERT INTO challenge "
                + "(id, creator_player_id, creator_display_name, status, game_version, "
                + "protocol_version, ruleset, host_character_id, created_at, expires_at, "
                + "accepted_player_id, accepted_character_id, accepted_at, match_id, "
                + "accepted_join_request_id) VALUES ('accepted', 'host', 'Host', "
                + "'ACCEPTED', '1.3.0', 11, 'STANDARD', '000003', 4, 999, "
                + "'accepted-player', '000004', 5, 'match', 'accepted-request')");
            statement.executeUpdate("INSERT INTO match_record "
                + "(id, challenge_id, status, server_seed, game_version, protocol_version, "
                + "ruleset, created_at) VALUES ('match', 'accepted', 'WAITING', 7, "
                + "'1.3.0', 11, 'STANDARD', 4)");
            statement.executeUpdate("INSERT INTO match_participant "
                + "(match_id, player_id, side, character_id) VALUES "
                + "('match', 'host', 'PLAYER_ONE', '000003'), "
                + "('match', 'accepted-player', 'PLAYER_TWO', '000004')");
        }

        migrate(url, null);
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            try (var existing = statement.executeQuery(
                "SELECT format, host_character_ids FROM challenge WHERE id = 'existing'")) {
                assertTrue(existing.next());
                assertEquals("ONE_V_ONE", existing.getString("format"));
                assertEquals("000000", existing.getString("host_character_ids"));
            }
            try (var pending = statement.executeQuery(
                "SELECT requested_character_ids FROM challenge WHERE id = 'pending'")) {
                assertTrue(pending.next());
                assertEquals("000002", pending.getString("requested_character_ids"));
            }
            try (var accepted = statement.executeQuery(
                "SELECT accepted_character_ids FROM challenge WHERE id = 'accepted'")) {
                assertTrue(accepted.next());
                assertEquals("000004", accepted.getString("accepted_character_ids"));
            }
            try (var participants = statement.executeQuery(
                "SELECT character_ids FROM match_participant ORDER BY side")) {
                assertTrue(participants.next());
                assertEquals("000003", participants.getString("character_ids"));
                assertTrue(participants.next());
                assertEquals("000004", participants.getString("character_ids"));
            }

            statement.executeUpdate("INSERT INTO challenge "
                + "(id, creator_player_id, creator_display_name, status, game_version, "
                + "protocol_version, ruleset, format, host_character_ids, created_at, expires_at) "
                + "VALUES ('new', 'host', 'Host', 'OPEN', '1.4.1', 12, "
                + "'STANDARD', 'ONE_V_ONE', '000001', 2, 1000)");
        }
    }

    private static void migrate(String url, String target) {
        var configuration = Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }
}
