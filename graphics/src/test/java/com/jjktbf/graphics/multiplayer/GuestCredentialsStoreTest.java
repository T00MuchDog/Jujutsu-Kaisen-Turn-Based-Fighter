package com.jjktbf.graphics.multiplayer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuestCredentialsStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicallyRoundTripsAndClearsCredentials() throws Exception {
        Path file = temporaryDirectory.resolve("multiplayer/guest-session.json");
        GuestCredentialsStore store = new GuestCredentialsStore(file);
        GuestCredentials credentials = MultiplayerTestData.credentials("private-token");

        assertFalse(credentials.toString().contains("private-token"));
        assertTrue(store.load().isEmpty());
        store.save(credentials);

        assertEquals(credentials, store.load().orElseThrow());
        try (var files = Files.list(file.getParent())) {
            assertEquals(Set.of("guest-session.json"),
                files.map(path -> path.getFileName().toString()).collect(
                    java.util.stream.Collectors.toSet()));
        }

        store.clear();
        assertFalse(Files.exists(file));
    }

    @Test
    void usesOwnerOnlyPermissionsOnPosixFileSystems() throws Exception {
        Path file = temporaryDirectory.resolve("private/guest-session.json");
        GuestCredentialsStore store = new GuestCredentialsStore(file);
        store.save(MultiplayerTestData.credentials("private-token"));

        if (!Files.getFileStore(file).supportsFileAttributeView("posix")) {
            return;
        }
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(file)
        );
        assertEquals(
            PosixFilePermissions.fromString("rwx------"),
            Files.getPosixFilePermissions(file.getParent())
        );
    }
}
