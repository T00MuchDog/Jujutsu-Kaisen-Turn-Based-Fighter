package com.jjktbf.graphics.screens;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MainMenuScreenSettingsTest {
    @Test
    void volumeTextIsClampedAndFallsBackWhenIncomplete() {
        assertEquals(0, MainMenuScreen.parseVolumePercent("-5", 50));
        assertEquals(64, MainMenuScreen.parseVolumePercent("64", 50));
        assertEquals(100, MainMenuScreen.parseVolumePercent("250", 50));
        assertEquals(50, MainMenuScreen.parseVolumePercent("", 50));
        assertEquals(50, MainMenuScreen.parseVolumePercent("not a number", 50));
    }

    @Test
    void windowsCommandViewportStaysCenteredBelowHeaderAtConstrainedHeights() {
        assertEquals(384f, MainMenuScreen.windowsCommandViewportHeight(720f, 650.4f));
        assertEquals(168f, MainMenuScreen.windowsCommandViewportY(720f, 650.4f, 34.8f));

        assertEquals(744f, MainMenuScreen.windowsCommandViewportHeight(1080f, 921f));
        assertEquals(168f, MainMenuScreen.windowsCommandViewportY(1080f, 921f, 79.5f));
    }

    @Test
    void windowsCommandViewportKeepsReferenceBoundsWhenMenuFits() {
        assertEquals(923.5f, MainMenuScreen.windowsCommandViewportHeight(1440f, 923.5f));
        assertEquals(258.25f, MainMenuScreen.windowsCommandViewportY(
            1440f, 923.5f, 258.25f));
    }

    @Test
    void windowsCommandViewportClearsSideControlsAndStaysCentered() {
        assertEquals(345.2f,
            MainMenuScreen.windowsCommandViewportHalfWidth(1280f, 1.2f / 1.75f),
            0.001f);
        assertEquals(567f,
            MainMenuScreen.windowsCommandViewportHalfWidth(2560f, 1f));
    }
}
