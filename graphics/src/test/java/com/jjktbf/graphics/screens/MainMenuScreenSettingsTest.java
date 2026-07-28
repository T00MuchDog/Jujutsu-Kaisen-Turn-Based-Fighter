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
}
