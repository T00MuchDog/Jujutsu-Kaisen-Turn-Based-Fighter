package com.jjktbf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppPathsOverrideTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void systemPropertyCanIsolateAClientProfile() {
        String previous = System.getProperty(AppPaths.DATA_ROOT_SYSTEM_PROPERTY);
        Path profile = temporaryDirectory.resolve("client-a");
        try {
            System.setProperty(AppPaths.DATA_ROOT_SYSTEM_PROPERTY, profile.toString());

            assertEquals(profile.toAbsolutePath().normalize(), AppPaths.root());
            assertTrue(AppPaths.root().toFile().isDirectory());
            assertEquals(AppPaths.root().resolve("data"), AppPaths.dataDir());
        } finally {
            if (previous == null) {
                System.clearProperty(AppPaths.DATA_ROOT_SYSTEM_PROPERTY);
            } else {
                System.setProperty(AppPaths.DATA_ROOT_SYSTEM_PROPERTY, previous);
            }
        }
    }

    @Test
    void unusableExplicitRootFailsInsteadOfSharingTheFallbackProfile() throws Exception {
        String previous = System.getProperty(AppPaths.DATA_ROOT_SYSTEM_PROPERTY);
        Path regularFile = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(regularFile, "occupied");
        try {
            System.setProperty(AppPaths.DATA_ROOT_SYSTEM_PROPERTY, regularFile.toString());

            assertThrows(IllegalStateException.class, AppPaths::root);
        } finally {
            if (previous == null) {
                System.clearProperty(AppPaths.DATA_ROOT_SYSTEM_PROPERTY);
            } else {
                System.setProperty(AppPaths.DATA_ROOT_SYSTEM_PROPERTY, previous);
            }
        }
    }
}
