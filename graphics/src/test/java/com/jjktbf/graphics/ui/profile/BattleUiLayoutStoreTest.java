package com.jjktbf.graphics.ui.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BattleUiLayoutStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void bundledProfilesAreIndependentAndValid() throws Exception {
        BattleUiLayoutStore store = new BattleUiLayoutStore(
            null, getClass().getClassLoader());

        BattleUiLayout mac = store.load(UiProfile.MAC);
        BattleUiLayout windows = store.load(UiProfile.WINDOWS);

        assertEquals(1512, mac.referenceWidth);
        assertEquals(982, mac.referenceHeight);
        assertEquals(2560, windows.referenceWidth);
        assertEquals(1440, windows.referenceHeight);
        assertEquals(1f, mac.execution.textGeometryScale, 0.0001f);
        assertEquals(1f, mac.planner.textGeometryScale, 0.0001f);
        assertEquals(1.5f, windows.execution.textGeometryScale, 0.0001f);
        assertEquals(1.5f, windows.planner.textGeometryScale, 0.0001f);
        assertEquals(217.5f, windows.execution.logHeightMax, 0.0001f);
        assertEquals(162f, windows.execution.hudHeightMax, 0.0001f);
        assertEquals(315f, windows.execution.nextRoundWidthMax, 0.0001f);
        assertEquals(87f, windows.planner.headerHeight, 0.0001f);
        assertEquals(213f, windows.planner.lockButtonWidth, 0.0001f);
        assertNotSame(mac.execution, windows.execution);
    }

    @Test
    void defaultsKeepMacGeometryAndApplyWindowsFallbacks() {
        BattleUiLayout mac = BattleUiLayout.defaults(UiProfile.MAC);
        BattleUiLayout windows = BattleUiLayout.defaults(UiProfile.WINDOWS);

        assertEquals(145f, mac.execution.logHeightMax, 0.0001f);
        assertEquals(108f, mac.execution.hudHeightMax, 0.0001f);
        assertEquals(58f, mac.planner.headerHeight, 0.0001f);
        assertEquals(1f, mac.planner.textGeometryScale, 0.0001f);
        assertEquals(217.5f, windows.execution.logHeightMax, 0.0001f);
        assertEquals(162f, windows.execution.hudHeightMax, 0.0001f);
        assertEquals(87f, windows.planner.headerHeight, 0.0001f);
        assertEquals(1.5f, windows.planner.textGeometryScale, 0.0001f);
    }

    @Test
    void profileTextGeometryInvariantIsValidated() {
        BattleUiLayout windows = BattleUiLayout.defaults(UiProfile.WINDOWS);
        windows.planner.textGeometryScale = 1f;

        assertThrows(IllegalArgumentException.class,
            () -> windows.validate(UiProfile.WINDOWS));
    }

    @Test
    void omittedAdditiveSchemaOneFieldsResolveToWindowsFallbacks() {
        BattleUiLayout windows = BattleUiLayout.defaults(UiProfile.WINDOWS);
        windows.execution.textGeometryScale = 0f;
        windows.planner.textGeometryScale = 0f;
        windows.planner.shortViewportHeightThreshold = 0f;

        windows.validate(UiProfile.WINDOWS);

        assertEquals(1.5f, windows.execution.textGeometryScale, 0.0001f);
        assertEquals(1.5f, windows.planner.textGeometryScale, 0.0001f);
        assertEquals(800f, windows.planner.shortViewportHeightThreshold, 0.0001f);
        assertEquals(272f, windows.planner.shortMoveCardHeight, 0.0001f);
    }

    @Test
    void sourceProfilesLoadIndependently() throws Exception {
        BattleUiLayoutStore store = new BattleUiLayoutStore(
            temporaryDirectory, getClass().getClassLoader());
        BattleUiLayout mac = BattleUiLayout.defaults(UiProfile.MAC);
        BattleUiLayout windows = BattleUiLayout.defaults(UiProfile.WINDOWS);
        mac.execution.hudScale = 1.37f;
        windows.execution.hudScale = 1.82f;
        Path sourceDirectory = temporaryDirectory.resolve(
            "graphics/src/main/resources/assets/ui/battle-layouts");
        Files.createDirectories(sourceDirectory);
        ObjectMapper mapper = new ObjectMapper();
        mapper.writeValue(sourceDirectory.resolve("mac.json").toFile(), mac);
        mapper.writeValue(sourceDirectory.resolve("windows.json").toFile(), windows);

        assertEquals(1.37f, store.load(UiProfile.MAC).execution.hudScale, 0.0001f);
        assertEquals(1.82f, store.load(UiProfile.WINDOWS).execution.hudScale, 0.0001f);
    }

    @Test
    void publicStoreFindsPinnedAuthoringCheckout() throws Exception {
        Path moves = temporaryDirectory.resolve("data/moves/all_moves.json");
        Path characters = temporaryDirectory.resolve("data/characters/all_characters.json");
        Files.createDirectories(moves.getParent());
        Files.createDirectories(characters.getParent());
        Files.writeString(moves, "{}");
        Files.writeString(characters, "{}");

        BattleUiLayout mac = BattleUiLayout.defaults(UiProfile.MAC);
        mac.execution.hudScale = 1.63f;
        Path sourceDirectory = temporaryDirectory.resolve(
            "graphics/src/main/resources/assets/ui/battle-layouts");
        Files.createDirectories(sourceDirectory);
        new ObjectMapper().writeValue(sourceDirectory.resolve("mac.json").toFile(), mac);

        String previousAuthoring = System.getProperty("jjktbf.authoring");
        String previousRoot = System.getProperty("jjktbf.authoring.root");
        try {
            System.setProperty("jjktbf.authoring", "true");
            System.setProperty("jjktbf.authoring.root", temporaryDirectory.toString());

            assertEquals(1.63f,
                new BattleUiLayoutStore().load(UiProfile.MAC).execution.hudScale,
                0.0001f);
        } finally {
            restoreProperty("jjktbf.authoring", previousAuthoring);
            restoreProperty("jjktbf.authoring.root", previousRoot);
        }
    }

    @Test
    void mismatchedSourceProfileIsRejected() throws Exception {
        BattleUiLayoutStore store = new BattleUiLayoutStore(
            temporaryDirectory, getClass().getClassLoader());
        BattleUiLayout windows = BattleUiLayout.defaults(UiProfile.WINDOWS);
        Path sourceDirectory = temporaryDirectory.resolve(
            "graphics/src/main/resources/assets/ui/battle-layouts");
        Files.createDirectories(sourceDirectory);
        new ObjectMapper().writeValue(sourceDirectory.resolve("mac.json").toFile(), windows);

        assertThrows(IllegalArgumentException.class,
            () -> store.load(UiProfile.MAC));
    }

    @Test
    void copyIsDeepEnoughForIndependentLiveDrafts() {
        BattleUiLayout original = BattleUiLayout.defaults(UiProfile.MAC);
        BattleUiLayout copy = original.copy();
        copy.execution.hudScale = 2f;
        copy.planner.headerHeight = 90f;

        assertEquals(1.25f, original.execution.hudScale, 0.0001f);
        assertEquals(58f, original.planner.headerHeight, 0.0001f);
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }
}
