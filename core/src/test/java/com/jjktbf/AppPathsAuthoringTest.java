package com.jjktbf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies authoring mode redirects repository data reads and writes to the
 * tracked source {@code data/} directory (the files bundled into releases)
 * instead of the per-user directory, and that startup seeding is skipped so it
 * cannot overwrite uncommitted edits.
 */
class AppPathsAuthoringTest {

    @TempDir
    Path temporaryDirectory;

    /** Restore a system property to its prior value (or clear it). */
    private static void restore(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    /** Create a fake project root with the source {@code data/} catalogs. */
    private Path fakeProjectRoot() throws Exception {
        Path project = temporaryDirectory.resolve("repo");
        Path data = project.resolve("data");
        Path moves = data.resolve("moves");
        Path characters = data.resolve("characters");
        Files.createDirectories(moves);
        Files.createDirectories(characters);
        Files.writeString(moves.resolve("all_moves.json"), "[]");
        Files.writeString(characters.resolve("all_characters.json"), "[]");
        return project;
    }

    @Test
    void authoringModeIsOffByDefault() {
        String previous = System.getProperty(AppPaths.AUTHORING_SYSTEM_PROPERTY);
        try {
            System.clearProperty(AppPaths.AUTHORING_SYSTEM_PROPERTY);
            assertFalse(AppPaths.isAuthoringMode());
        } finally {
            restore(AppPaths.AUTHORING_SYSTEM_PROPERTY, previous);
        }
    }

    @Test
    void authoringModeResolvesToSourceDataDirectory() throws Exception {
        Path project = fakeProjectRoot();
        String previousFlag = System.getProperty(AppPaths.AUTHORING_SYSTEM_PROPERTY);
        String previousRoot = System.getProperty(AppPaths.AUTHORING_ROOT_SYSTEM_PROPERTY);
        try {
            System.setProperty(AppPaths.AUTHORING_SYSTEM_PROPERTY, "true");
            System.setProperty(AppPaths.AUTHORING_ROOT_SYSTEM_PROPERTY, project.toString());

            File resolved = AppPaths.resolve("data/characters");
            assertEquals(project.resolve("data/characters").toAbsolutePath().normalize(),
                         resolved.toPath().toAbsolutePath().normalize());
        } finally {
            restore(AppPaths.AUTHORING_SYSTEM_PROPERTY, previousFlag);
            restore(AppPaths.AUTHORING_ROOT_SYSTEM_PROPERTY, previousRoot);
        }
    }

    @Test
    void authoringModeIsNoOpWhenSourceDataDirIsAbsent() throws Exception {
        String previousFlag = System.getProperty(AppPaths.AUTHORING_SYSTEM_PROPERTY);
        String previousRoot = System.getProperty(AppPaths.AUTHORING_ROOT_SYSTEM_PROPERTY);
        try {
            System.setProperty(AppPaths.AUTHORING_SYSTEM_PROPERTY, "true");
            // Point at a directory with no source data/ catalogs.
            System.setProperty(AppPaths.AUTHORING_ROOT_SYSTEM_PROPERTY,
                               temporaryDirectory.toString());

            assertNull(AppPaths.authoringDataDir(),
                       "authoringDataDir() should be null when no source data/ is found");
        } finally {
            restore(AppPaths.AUTHORING_SYSTEM_PROPERTY, previousFlag);
            restore(AppPaths.AUTHORING_ROOT_SYSTEM_PROPERTY, previousRoot);
        }
    }

    @Test
    void authoringModeSkipsStartupSeeding() {
        String previousFlag = System.getProperty(AppPaths.AUTHORING_SYSTEM_PROPERTY);
        try {
            System.setProperty(AppPaths.AUTHORING_SYSTEM_PROPERTY, "true");
            // Must not throw and must not touch any per-user files.
            AppPaths.seedDataIfAbsent();
        } finally {
            restore(AppPaths.AUTHORING_SYSTEM_PROPERTY, previousFlag);
        }
    }
}
