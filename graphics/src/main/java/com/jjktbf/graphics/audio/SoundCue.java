package com.jjktbf.graphics.audio;

/**
 * Registry of short, preloaded sound effects.
 *
 * Add a cue here, place its asset under {@code assets/audio}, then trigger the
 * cue through {@link GameAudio#play(SoundCue)} at the semantic event boundary.
 */
public enum SoundCue {
    UI_NAVIGATE(AudioChannel.UI_SFX, "assets/audio/sfx/ui/navigate.wav", 0.58f),
    UI_CONFIRM(AudioChannel.UI_SFX, "assets/audio/sfx/ui/confirm.wav", 0.68f),
    UI_BACK(AudioChannel.UI_SFX, "assets/audio/sfx/ui/back.wav", 0.62f),
    UI_TOGGLE(AudioChannel.UI_SFX, "assets/audio/sfx/ui/toggle.wav", 0.56f),
    UI_DELETE(AudioChannel.UI_SFX, "assets/audio/sfx/ui/delete.wav", 0.70f),
    UI_DENIED(AudioChannel.UI_SFX, "assets/audio/sfx/ui/denied.wav", 0.62f),
    UI_PICKUP(AudioChannel.UI_SFX, "assets/audio/sfx/ui/pickup.wav", 0.58f),
    UI_DROP(AudioChannel.UI_SFX, "assets/audio/sfx/ui/drop.wav", 0.62f),
    UI_PLAN_PLACE(AudioChannel.UI_SFX, "assets/audio/sfx/ui/plan_place.wav", 0.72f),
    UI_PLAN_REMOVE(AudioChannel.UI_SFX, "assets/audio/sfx/ui/plan_remove.wav", 0.68f),
    UI_PLAN_LOCK(AudioChannel.UI_SFX, "assets/audio/sfx/ui/plan_lock.wav", 0.76f),

    BATTLE_ATTACK_UNLEASH(
        AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/attack_unleash.wav", 0.72f),
    BATTLE_DEFENSE_UNLEASH(
        AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/defense_unleash.wav", 0.68f),
    BATTLE_UTILITY_UNLEASH(
        AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/utility_unleash.wav", 0.66f),
    BATTLE_HIT(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/hit.wav", 0.78f),
    BATTLE_BLOCK(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/block.wav", 1f),
    BATTLE_MISS(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/miss.wav", 0.62f),
    BATTLE_DODGE(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/dodge.wav", 0.66f),
    BATTLE_PARRY(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/parry.wav", 0.76f),
    BATTLE_BLACK_FLASH(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/black_flash.wav", 0.82f),
    BATTLE_DAMAGE_IGNORED(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/ignored.wav", 0.62f),
    BATTLE_HEAL(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/heal.wav", 0.66f),
    BATTLE_CE_DRAIN(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/ce_drain.wav", 0.46f),
    BATTLE_CE_RESTORE(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/ce_restore.wav", 0.58f),
    BATTLE_STUN(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/stun.wav", 0.68f),
    BATTLE_STATUS_APPLY(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/status_apply.wav", 0.52f),
    BATTLE_STATUS_EXPIRE(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/status_expire.wav", 0.46f),
    BATTLE_ABILITY(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/ability.wav", 0.64f),
    BATTLE_RATIO(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/ratio.wav", 0.74f),
    BATTLE_ROUND_START(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/round_start.wav", 0.62f),
    BATTLE_ROUND_END(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/round_end.wav", 0.58f),
    BATTLE_VICTORY(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/victory.wav", 0.78f),
    BATTLE_DEFEAT(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/defeat.wav", 0.72f),
    BATTLE_DRAW(AudioChannel.BATTLE_SFX, "assets/audio/sfx/battle/draw.wav", 0.68f);

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
