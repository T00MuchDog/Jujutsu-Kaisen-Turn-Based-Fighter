package com.jjktbf.graphics.ui.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertNotSame(mac.execution, windows.execution);
    }

    @Test
    void saveAndReloadStayWithinTheSelectedProfileFile() throws Exception {
        BattleUiLayoutStore store = new BattleUiLayoutStore(
            temporaryDirectory, getClass().getClassLoader());
        BattleUiLayout mac = BattleUiLayout.defaults(UiProfile.MAC);
        BattleUiLayout windows = BattleUiLayout.defaults(UiProfile.WINDOWS);
        mac.execution.hudScale = 1.37f;
        windows.execution.hudScale = 1.82f;

        Path macPath = store.save(UiProfile.MAC, mac);
        Path windowsPath = store.save(UiProfile.WINDOWS, windows);

        assertTrue(Files.isRegularFile(macPath));
        assertTrue(Files.isRegularFile(windowsPath));
        assertEquals(1.37f, store.load(UiProfile.MAC).execution.hudScale, 0.0001f);
        assertEquals(1.82f, store.load(UiProfile.WINDOWS).execution.hudScale, 0.0001f);
    }

    @Test
    void mismatchedProfileCannotOverwriteAnotherProfile() {
        BattleUiLayoutStore store = new BattleUiLayoutStore(
            temporaryDirectory, getClass().getClassLoader());
        BattleUiLayout windows = BattleUiLayout.defaults(UiProfile.WINDOWS);

        assertThrows(IllegalArgumentException.class,
            () -> store.save(UiProfile.MAC, windows));
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
}
