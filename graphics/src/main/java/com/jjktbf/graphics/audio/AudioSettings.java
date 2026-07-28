package com.jjktbf.graphics.audio;

import java.util.Objects;

/** Persisted master and channel volumes, normalized to the range {@code [0, 1]}. */
public record AudioSettings(
    float masterVolume,
    float musicVolume,
    float uiSfxVolume,
    float battleSfxVolume,
    boolean muted
) {
    public AudioSettings {
        masterVolume = normalize(masterVolume);
        musicVolume = normalize(musicVolume);
        uiSfxVolume = normalize(uiSfxVolume);
        battleSfxVolume = normalize(battleSfxVolume);
    }

    public static AudioSettings defaults() {
        return new AudioSettings(1f, 1f, 1f, 1f, false);
    }

    public float channelVolume(AudioChannel channel) {
        return switch (Objects.requireNonNull(channel, "channel")) {
            case MUSIC -> musicVolume;
            case UI_SFX -> uiSfxVolume;
            case BATTLE_SFX -> battleSfxVolume;
        };
    }

    public float effectiveVolume(AudioChannel channel, float cueGain) {
        if (muted) return 0f;
        return masterVolume * channelVolume(channel) * normalize(cueGain);
    }

    public AudioSettings withMasterVolume(float volume) {
        return new AudioSettings(volume, musicVolume, uiSfxVolume, battleSfxVolume, muted);
    }

    public AudioSettings withChannelVolume(AudioChannel channel, float volume) {
        return switch (Objects.requireNonNull(channel, "channel")) {
            case MUSIC -> new AudioSettings(
                masterVolume, volume, uiSfxVolume, battleSfxVolume, muted);
            case UI_SFX -> new AudioSettings(
                masterVolume, musicVolume, volume, battleSfxVolume, muted);
            case BATTLE_SFX -> new AudioSettings(
                masterVolume, musicVolume, uiSfxVolume, volume, muted);
        };
    }

    public AudioSettings withMuted(boolean value) {
        return new AudioSettings(
            masterVolume, musicVolume, uiSfxVolume, battleSfxVolume, value);
    }

    private static float normalize(float volume) {
        if (!Float.isFinite(volume)) return 0f;
        return Math.max(0f, Math.min(1f, volume));
    }
}
