package com.jjktbf.graphics.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioSettingsTest {
    @Test
    void normalizesVolumesAndMixesMasterWithTheSelectedChannel() {
        AudioSettings settings = new AudioSettings(0.5f, 0.8f, 2f, -1f, false);

        assertEquals(0.4f, settings.effectiveVolume(AudioChannel.MUSIC, 1f), 0.0001f);
        assertEquals(0.5f, settings.effectiveVolume(AudioChannel.UI_SFX, 1f), 0.0001f);
        assertEquals(0f, settings.effectiveVolume(AudioChannel.BATTLE_SFX, 1f), 0.0001f);
    }

    @Test
    void muteAndInvalidCueGainProduceSilence() {
        AudioSettings muted = AudioSettings.defaults().withMuted(true);

        assertEquals(0f, muted.effectiveVolume(AudioChannel.MUSIC, 1f));
        assertEquals(0f, AudioSettings.defaults().effectiveVolume(
            AudioChannel.UI_SFX, Float.NaN));
    }
}
