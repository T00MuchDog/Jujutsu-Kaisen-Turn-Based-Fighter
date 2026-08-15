package com.jjktbf.graphics.launch;

import com.jjktbf.graphics.ui.profile.UiProfile;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopLaunchOptionsTest {

    @Test
    void hostPlatformChoosesOnlyTheDefaultProfile() {
        DesktopLaunchOptions mac = DesktopLaunchOptions.parse(
            new String[0], new Properties(), DesktopPlatform.MAC);
        DesktopLaunchOptions windows = DesktopLaunchOptions.parse(
            new String[0], new Properties(), DesktopPlatform.WINDOWS);

        assertEquals(UiProfile.MAC, mac.uiProfile());
        assertEquals(UiProfile.WINDOWS, windows.uiProfile());
        assertEquals(GameLaunchMode.NORMAL_GAME, mac.mode());
        assertFalse(mac.windowed());
    }

    @Test
    void commandLineCanOverrideProfileOnEitherHost() {
        DesktopLaunchOptions options = DesktopLaunchOptions.parse(
            new String[] {"--ui-profile=WINDOWS"},
            new Properties(),
            DesktopPlatform.MAC);

        assertEquals(UiProfile.WINDOWS, options.uiProfile());
        assertEquals(DesktopPlatform.MAC, options.hostPlatform());
    }

    @Test
    void editorAndWindowOverridesAreParsedTogether() {
        DesktopLaunchOptions options = DesktopLaunchOptions.parse(
            new String[] {
                "--battle-ui-editor", "--ui-profile", "MAC",
                "--width", "1600", "--height=900"
            },
            new Properties(),
            DesktopPlatform.WINDOWS);

        assertTrue(options.battleUiEditor());
        assertTrue(options.windowed());
        assertEquals(1600, options.windowWidth());
        assertEquals(900, options.windowHeight());
        assertEquals(UiProfile.MAC, options.uiProfile());
    }

    @Test
    void invalidProfileAndDimensionsFailBeforeOpeningAWindow() {
        assertThrows(IllegalArgumentException.class, () -> DesktopLaunchOptions.parse(
            new String[] {"--ui-profile=LINUX"},
            new Properties(), DesktopPlatform.OTHER));
        assertThrows(IllegalArgumentException.class, () -> DesktopLaunchOptions.parse(
            new String[] {"--width=100"},
            new Properties(), DesktopPlatform.OTHER));
    }
}
