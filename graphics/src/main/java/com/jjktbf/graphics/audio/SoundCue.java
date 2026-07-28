package com.jjktbf.graphics.audio;

/**
 * Registry of short, preloaded sound effects.
 *
 * Add a cue here, place its asset under {@code assets/audio}, then trigger the
 * cue through {@link GameAudio#play(SoundCue)} at the semantic event boundary.
 */
public enum SoundCue {
    UI_NAVIGATE(AudioChannel.UI_SFX, "assets/audio/sfx/ui/navigate.wav", 1f),
    UI_CONFIRM(AudioChannel.UI_SFX, "assets/audio/sfx/ui/confirm.wav", 1f),
    UI_BACK(AudioChannel.UI_SFX, "assets/audio/sfx/ui/back.wav", 1f),
    UI_PLAN_PLACE(AudioChannel.UI_SFX, "assets/audio/sfx/ui/plan_place.wav", 1f),
    UI_PLAN_REMOVE(AudioChannel.UI_SFX, "assets/audio/sfx/ui/plan_remove.wav", 1f),
    UI_PLAN_LOCK(AudioChannel.UI_SFX, "assets/audio/sfx/ui/plan_lock.wav", 1f),

    BATTLE_ATTACK_UNLEASH(
        AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/attack_unleash.wav", 1f),
    BATTLE_DEFENSE_UNLEASH(
        AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/defense_unleash.wav", 1f),
    BATTLE_UTILITY_UNLEASH(
        AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/utility_unleash.wav", 1f),
    BATTLE_HIT(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/hit.wav", 1f),
    BATTLE_BLOCK(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/block.wav", 1f),
    BATTLE_MISS(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/miss.wav", 1f),
    BATTLE_DODGE(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/dodge.wav", 1f),
    BATTLE_PARRY(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/parry.wav", 1f),
    BATTLE_BLACK_FLASH(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/black_flash.wav", 1f),
    BATTLE_VICTORY(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/victory.wav", 1f),
    BATTLE_DEFEAT(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/defeat.wav", 1f),
    BATTLE_DRAW(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/draw.wav", 1f);

    private final AudioChannel channel;
    private final String assetPath;
    private final float gain;

    SoundCue(AudioChannel channel, String assetPath, float gain) {
        this.channel = channel;
        this.assetPath = assetPath;
        this.gain = gain;
    }

    public AudioChannel channel() {
        return channel;
    }

    public String assetPath() {
        return assetPath;
    }

    public float gain() {
        return gain;
    }
}
