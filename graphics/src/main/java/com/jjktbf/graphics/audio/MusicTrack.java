package com.jjktbf.graphics.audio;

import java.util.concurrent.ThreadLocalRandom;

/** Long-form streamed tracks. Music loops until another track is requested. */
public enum MusicTrack {
    MENU("assets/audio/music/menu.ogg", 1f),
    BATTLE_AIZO("assets/audio/music/battle_aizo.ogg", 1f),
    BATTLE_ABODE_OF_BLUE("assets/audio/music/battle_AbodeOfBlue.ogg", 1f),
    BATTLE_SPECIALZ("assets/audio/music/battle_specialz.ogg", 1f);

    /** Tracks eligible to open a battle, each with an equal chance. */
    private static final MusicTrack[] BATTLE_TRACKS = {
        BATTLE_AIZO, BATTLE_ABODE_OF_BLUE, BATTLE_SPECIALZ
    };

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

    /** Returns one of the battle tracks, each with equal probability. */
    public static MusicTrack randomBattleTrack() {
        return BATTLE_TRACKS[ThreadLocalRandom.current().nextInt(BATTLE_TRACKS.length)];
    }
}
