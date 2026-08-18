package com.jjktbf.graphics;

import com.jjktbf.graphics.ui.profile.UiProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssetLoaderFontScaleTest {

    @Test
    void profilesDefinePlatformTextScale() {
        assertEquals(1.0f, UiProfile.MAC.textScale());
        assertEquals(1.5f, UiProfile.WINDOWS.textScale());
    }

    @Test
    void rasterSizeCombinesProfileScaleWithFourTimesOversampling() {
        assertEquals(60, AssetLoader.rasterSize(15, UiProfile.MAC));
        assertEquals(90, AssetLoader.rasterSize(15, UiProfile.WINDOWS));
        assertEquals(4f, AssetLoader.FONT_OVERSAMPLE);
    }
}
