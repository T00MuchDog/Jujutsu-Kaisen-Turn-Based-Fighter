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
                + "VALUES ('player', 'Host', 'host', 1)");
            statement.executeUpdate("INSERT INTO challenge "
                + "(id, creator_player_id, creator_display_name, status, game_version, "
                + "protocol_version, ruleset, host_character_id, created_at, expires_at) "
                + "VALUES ('existing', 'player', 'Host', 'OPEN', '1.3.0', 11, "
                + "'STANDARD', '000000', 1, 999)");
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

            statement.executeUpdate("INSERT INTO challenge "
                + "(id, creator_player_id, creator_display_name, status, game_version, "
                + "protocol_version, ruleset, format, host_character_ids, created_at, expires_at) "
                + "VALUES ('new', 'player', 'Host', 'OPEN', '1.4.0', 12, "
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
