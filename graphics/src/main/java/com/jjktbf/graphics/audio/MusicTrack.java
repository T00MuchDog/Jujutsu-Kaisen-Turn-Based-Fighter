package com.jjktbf.graphics.audio;

/** Long-form streamed tracks. Music loops until another track is requested. */
public enum MusicTrack {
    MENU("assets/audio/music/menu.ogg", 1f),
    BATTLE("assets/audio/music/battle_aizo.ogg", 1f);

    private final String assetPath;
    private final float gain;

    MusicTrack(String assetPath, float gain) {
        this.assetPath = assetPath;
        this.gain = gain;
    }

    public String assetPath() {
        return assetPath;
    }

    public float gain() {
        return gain;
    }
}
