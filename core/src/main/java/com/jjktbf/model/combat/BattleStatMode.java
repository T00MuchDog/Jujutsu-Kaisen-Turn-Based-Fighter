package com.jjktbf.model.combat;

import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.StatScale;

import java.util.Arrays;

/**
 * Battle-time policy applied after the normal raw-to-effective stat scale.
 * Equalized mode uses {@code round(80 + 0.5 * (scaledStat - 80))}.
 */
public enum BattleStatMode {
    STANDARD("STANDARD", "Standard Stats"),
    EQUALIZED("EQUALIZED_STATS", "Equalized Stats");

    public static final int EQUALIZED_BASELINE = CharacterStats.BASELINE;
    public static final double EQUALIZED_BLEND_FACTOR = 0.5;

    private final String rulesetId;
    private final String displayName;

    BattleStatMode(String rulesetId, String displayName) {
        this.rulesetId = rulesetId;
        this.displayName = displayName;
    }

    /** Stable multiplayer/persistence identifier for this battle rule. */
    public String rulesetId() {
        return rulesetId;
    }

    /** Apply this mode to a value that has already passed through its normal stat scale. */
    public int applyToScaled(int scaledStat) {
        if (this == STANDARD) return scaledStat;
        return (int) Math.round(EQUALIZED_BASELINE
            + EQUALIZED_BLEND_FACTOR * (scaledStat - EQUALIZED_BASELINE));
    }

    /** Apply the normal nonlinear scale, followed by this battle-time mode. */
    public int scale(int rawStat) {
        return applyToScaled(StatScale.scale(rawStat));
    }

    /** Apply the AP-specific scale, followed by this battle-time mode. */
    public int scaleForAp(int rawStat) {
        return applyToScaled(StatScale.scaleForAp(rawStat));
    }

    /**
     * Mastery progressions historically consume raw effective CTM in standard
     * battles. Equalized battles use the fully scaled and blended runtime CTM.
     */
    public int masteryForProgression(int rawEffectiveMastery) {
        return this == STANDARD ? rawEffectiveMastery : scale(rawEffectiveMastery);
    }

    public static BattleStatMode fromRuleset(String rulesetId) {
        return Arrays.stream(values())
            .filter(mode -> mode.rulesetId.equals(rulesetId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Unsupported battle ruleset: " + rulesetId));
    }

    public static boolean supportsRuleset(String rulesetId) {
        return Arrays.stream(values()).anyMatch(mode -> mode.rulesetId.equals(rulesetId));
    }

    @Override
    public String toString() {
        return displayName;
    }
}
