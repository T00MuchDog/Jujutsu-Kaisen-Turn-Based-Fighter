package com.jjktbf.model.character;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
// Used by statSetMax convenience method
import static com.jjktbf.model.character.CharacterStats.MAX_STAT;

/**
 * DTO representing one effect primitive within an ability.
 *
 * Only the fields relevant to the effect type need to be populated.
 * Jackson's NON_NULL include policy keeps the JSON lean.
 *
 * Field usage by AbilityEffectType:
 *
 *   STAT_ADD             → stat, intValue
 *   STAT_MULTIPLY        → stat, doubleValue
 *   STAT_SET_MAX         → stat
 *   STAT_SET_VALUE       → stat, intValue
 *   STAT_BONUS_POINTS    → intValue
 *   CE_COST_TO_MINIMUM   → moveTag (null = all)
 *   CE_COST_MULTIPLY     → moveTag (null = all), doubleValue
 *   MOVE_ACCURACY_ADD    → moveTag (null = all), intValue
 *   DAMAGE_MULTIPLY      → moveTag (null = all), doubleValue
 *   GRANT_MOVE           → moveId
 *   BF_CHANCE_ADD        → doubleValue
 *   UNLOCK_TECHNIQUE     → stringValue (technique name)
 *   MODIFY_DEFENSE       → doubleValue
 *   MODIFY_AP_BAR        → intValue
 *   AUTO_STATUS_APPLY    → stringValue (StatusEffectType), target, timing
 *   BLOCK_MOVE_TAG       → moveTag
 *   COST_CE_PER_ROUND    → intValue
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

    /** Move ID (6-digit) for GRANT_MOVE and queued-active references. */
    public String moveId;

    // ── Status effect automation ──────────────────────────────────────────────
    /**
     * Generic string value:
     *   AUTO_STATUS_APPLY → StatusEffectType name (e.g. "BARRIER")
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
            + " }";
    }
}
