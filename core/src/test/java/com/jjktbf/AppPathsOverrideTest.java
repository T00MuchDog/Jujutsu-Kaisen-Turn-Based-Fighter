package com.jjktbf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void firstInstallMarkerDoesNotTreatAnUpgradeAsANewProfile() throws Exception {
        String previousRoot = System.getProperty(AppPaths.DATA_ROOT_SYSTEM_PROPERTY);
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        Path resourceRoot = temporaryDirectory.resolve("resources");
        Path freshProfile = temporaryDirectory.resolve("fresh-profile");
        Path existingProfile = temporaryDirectory.resolve("existing-profile");
        writeBundledData(resourceRoot);
        Files.createDirectories(existingProfile);
        Files.writeString(existingProfile.resolve("data-release-version"), "1.0.0\n");

        try (URLClassLoader resourceLoader = new URLClassLoader(
            new java.net.URL[] {resourceRoot.toUri().toURL()}, null)) {
            Thread.currentThread().setContextClassLoader(resourceLoader);

            System.setProperty(AppPaths.DATA_ROOT_SYSTEM_PROPERTY, freshProfile.toString());
            AppPaths.seedDataIfAbsent();
            assertTrue(AppPaths.isFirstInstallProfile());

            System.setProperty(AppPaths.DATA_ROOT_SYSTEM_PROPERTY, existingProfile.toString());
            AppPaths.seedDataIfAbsent();
            assertFalse(AppPaths.isFirstInstallProfile());
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
            if (previousRoot == null) {
                System.clearProperty(AppPaths.DATA_ROOT_SYSTEM_PROPERTY);
            } else {
                System.setProperty(AppPaths.DATA_ROOT_SYSTEM_PROPERTY, previousRoot);
            }
        }
    }

    private static void writeBundledData(Path resourceRoot) throws Exception {
        writeResource(resourceRoot, "jjktbf-version.properties", "game.version=1.0.1\n");
        writeResource(resourceRoot, "data/moves/all_moves.json", "[]");
        writeResource(resourceRoot, "data/abilities/all_abilities.json", "[]");
        writeResource(resourceRoot, "data/techniques/all_techniques.json", "[]");
        writeResource(resourceRoot, "data/characters/all_characters.json", "[]");
        writeResource(resourceRoot, "data/keyword_descriptions.json", "[]");
    }

    private static void writeResource(Path root, String relativePath, String contents) throws Exception {
        Path resource = root.resolve(relativePath);
        Files.createDirectories(resource.getParent());
        Files.writeString(resource, contents);
    }
}
