package com.jjktbf.server.auth;

import com.jjktbf.multiplayer.protocol.GuestCreateResponse;
import com.jjktbf.multiplayer.protocol.SessionIdentity;
import com.jjktbf.server.service.ServiceException;
import com.jjktbf.server.support.ServerTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuestAuthServiceTest {
    private ServerTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new ServerTestFixture();
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void createsRequestedAndGeneratedGuestsAndAuthenticatesThem() {
        GuestCreateResponse requested = fixture.authService()
            .createGuest(Optional.of("  Test Guest  "));
        GuestCreateResponse generated = fixture.authService().createGuest(Optional.empty());

        assertEquals("Test Guest", requested.identity().displayName());
        assertTrue(generated.identity().displayName().matches("Guest-[A-Za-z0-9_-]{12}"));
        assertTrue(requested.token().split("\\.", -1)[1].length() >= 43);

        SessionIdentity authenticated = fixture.authService().authenticate(requested.token());
        assertEquals(requested.identity(), authenticated);
    }

    @Test
    void persistsOnlyAKeyedTokenHash() {
        GuestCreateResponse guest = fixture.createGuestResponse("Hash Test");

        String storedHash = fixture.database().withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT token_hash FROM guest_session WHERE player_id = ?")) {
                statement.setString(1, guest.identity().playerId());
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getString(1);
                }
            }
        });

        assertNotEquals(guest.token(), storedHash);
        assertFalse(storedHash.contains(guest.token()));
        assertEquals(43, storedHash.length());
    }

    @Test
    void rejectsInvalidAndExpiredTokensWithStableCode() {
        GuestCreateResponse guest = fixture.createGuestResponse("Expiry Test");

        ServiceException invalid = assertThrows(
            ServiceException.class,
            () -> fixture.authService().authenticate("not-a-token")
        );
        assertEquals("INVALID_TOKEN", invalid.code());

        fixture.database().withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE guest_session SET expires_at = ? WHERE player_id = ?")) {
                statement.setLong(1, fixture.clock().millis());
                statement.setString(2, guest.identity().playerId());
                statement.executeUpdate();
                return null;
            }
        });
        ServiceException expired = assertThrows(
            ServiceException.class,
            () -> fixture.authService().authenticate(guest.token())
        );
        assertEquals("INVALID_TOKEN", expired.code());
    }

    @Test
    void validatesRequestedNamesAndCaseInsensitiveCollisions() {
        fixture.createGuest("Player One");

        ServiceException invalid = assertThrows(
            ServiceException.class,
            () -> fixture.authService().createGuest(Optional.of("bad!name"))
        );
        ServiceException taken = assertThrows(
            ServiceException.class,
            () -> fixture.authService().createGuest(Optional.of("player one"))
        );

        assertEquals("INVALID_DISPLAY_NAME", invalid.code());
        assertEquals("DISPLAY_NAME_TAKEN", taken.code());
    }
}
