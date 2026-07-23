package com.jjktbf.model.character;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import static com.jjktbf.model.character.CharacterStats.MAX_STAT;

/**
 * DTO representing one effect primitive within an ability.
 *
 * Only the fields relevant to the effect type need to be populated.
 * Jackson's NON_NULL include policy keeps the JSON lean.
 *
 * <p>The authoritative field mapping is declared by
 * {@link AbilityEffectType#parameters()}. Only fields relevant to that type are
 * persisted.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AbilityEffectData {

    /** Which effect primitive this represents. */
    public String type;   // AbilityEffectType name

    // ── Stat-related ─────────────────────────────────────────────────────────
    /** Stat name (lowercase, e.g. "cursedEnergyEfficiency"). */
    public String stat;

    // ── Numeric values ────────────────────────────────────────────────────────
    /** Integer parameter: flat amount, set-value, bonus points, AP addition, CE per round. */
    public Integer intValue;

    /** Double parameter: multiply factor, BF chance addition, damage factor. */
    public Double doubleValue;

    // ── Move filtering ────────────────────────────────────────────────────────
    /** Move tag name to filter on (e.g. "PHYSICAL", "INNATE_TECHNIQUE"). Null = all moves. */
    public String moveTag;

    /** Move ID (6-digit) for GRANT_MOVE. */
    public String moveId;

    // ── Status effect automation ──────────────────────────────────────────────
    /**
     * Generic string value:
     *   AUTO_STATUS_APPLY → a supported StatusEffectType name (e.g. "ACCURACY_INCREASE")
     *   UNLOCK_TECHNIQUE  → technique name (e.g. "Limitless")
     */
    public String stringValue;

    /**
     * Target for AUTO_STATUS_APPLY: "SELF" or "ENEMY".
     */
    public String target;

    /**
     * Timing for AUTO_STATUS_APPLY: "FIGHT_START", "ROUND_START", or "ON_HIT".
     */
    public String timing;

    /** Duration for AUTO_STATUS_APPLY. -1 means permanent. */
    public Integer durationRounds;

    /** AP ticks after the configured rounds have elapsed. */
    public Integer durationTicks;

    /** Magnitude for AUTO_STATUS_APPLY. */
    public Double magnitude;

    /** Number of times a consumable effect may be used. -1 means unlimited. */
    public Integer uses;

    /** Field-by-field copy used by editor drafts and immutable domain objects. */
    public AbilityEffectData copy() {
        AbilityEffectData copy = new AbilityEffectData();
        copy.copyFrom(this);
        return copy;
    }

    /** Replace this DTO's values with another effect's values. */
    public void copyFrom(AbilityEffectData source) {
        this.type = source.type;
        this.stat = source.stat;
        this.intValue = source.intValue;
        this.doubleValue = source.doubleValue;
        this.moveTag = source.moveTag;
        this.moveId = source.moveId;
        this.stringValue = source.stringValue;
        this.target = source.target;
        this.timing = source.timing;
        this.durationRounds = source.durationRounds;
        this.durationTicks = source.durationTicks;
        this.magnitude = source.magnitude;
        this.uses = source.uses;
    }

    // ── Convenience constructors for editor use ───────────────────────────────

    public static AbilityEffectData statAdd(String stat, int amount) {
        AbilityEffectData e = new AbilityEffectData();
        e.type     = AbilityEffectType.STAT_ADD.name();
        e.stat     = stat;
        e.intValue = amount;
        return e;
    }

    /** Sets a stat to its maximum allowed value (300) via STAT_SET_VALUE. */
    public static AbilityEffectData statSetMax(String stat) {
        AbilityEffectData e = new AbilityEffectData();
        e.type     = AbilityEffectType.STAT_SET_VALUE.name();
        e.stat     = stat;
        e.intValue = MAX_STAT; // 300
        return e;
    }

    /** Sets a stat to 0 (N/A) via STAT_SET_MIN. */
    public static AbilityEffectData statSetMin(String stat) {
        AbilityEffectData e = new AbilityEffectData();
        e.type = AbilityEffectType.STAT_SET_MIN.name();
        e.stat = stat;
        return e;
    }

    public static AbilityEffectData statBonusPoints(int amount) {
        AbilityEffectData e = new AbilityEffectData();
        e.type     = AbilityEffectType.STAT_BONUS_POINTS.name();
        e.intValue = amount;
        return e;
    }

    public static AbilityEffectData ceCostToMinimum(String moveTag) {
        AbilityEffectData e = new AbilityEffectData();
        e.type    = AbilityEffectType.CE_COST_TO_MINIMUM.name();
        e.moveTag = moveTag;
        return e;
    }

    public static AbilityEffectData unlockTechnique(String techniqueName) {
        AbilityEffectData e = new AbilityEffectData();
        e.type        = AbilityEffectType.UNLOCK_TECHNIQUE.name();
        e.stringValue = techniqueName;
        return e;
    }

    public static AbilityEffectData moveAccuracyAdd(String moveTag, int amount) {
        AbilityEffectData e = new AbilityEffectData();
        e.type     = AbilityEffectType.MOVE_ACCURACY_ADD.name();
        e.moveTag  = moveTag;
        e.intValue = amount;
        return e;
    }

    @Override
    public String toString() {
        return type + "{"
            + (stat        != null ? " stat=" + stat : "")
            + (intValue    != null ? " int=" + intValue : "")
            + (doubleValue != null ? " dbl=" + doubleValue : "")
            + (moveTag     != null ? " tag=" + moveTag : "")
            + (moveId      != null ? " move=" + moveId : "")
            + (stringValue != null ? " str=" + stringValue : "")
            + (target      != null ? " tgt=" + target : "")
            + (timing      != null ? " time=" + timing : "")
            + (durationRounds != null ? " rounds=" + durationRounds : "")
            + (durationTicks != null ? " ticks=" + durationTicks : "")
            + (magnitude   != null ? " mag=" + magnitude : "")
            + (uses        != null ? " uses=" + uses : "")
            + " }";
    }
}
