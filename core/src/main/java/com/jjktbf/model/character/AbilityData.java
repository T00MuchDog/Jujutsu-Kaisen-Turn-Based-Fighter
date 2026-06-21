package com.jjktbf.model.character;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * DTO for one ability definition, stored in data/abilities/all_abilities.json.
 *
 * An ability has two layers of text:
 *
 *   flavourText   — immersive, in-universe description. Shown to the player
 *                   during gameplay (on the character sheet, ability inspect screen).
 *                   Written as if describing the ability from within the JJK world.
 *                   Example: "A rare ocular Jujutsu passed down through the Gojo clan..."
 *
 *   mechanicText  — plain mechanical description for the designer / player who wants
 *                   to understand exactly what the ability does.
 *                   Contains KEYWORDS in ALL_CAPS that the editor and UI can highlight.
 *                   Example: "Sets CURSED_ENERGY_EFFICIENCY to MAX. Reduces all CE costs
 *                   to their MINIMUM. Grants +20 ACCURACY on all moves."
 *
 * Keywords are not stored separately — they are words in ALL_CAPS within mechanicText.
 * The UI layer extracts and highlights them via regex at render time.
 *
 * Source types:
 *   CHARACTER      — intrinsic to the character (no prerequisite)
 *   TECHNIQUE      — requires possessing a named innate technique
 *   MOVE           — requires knowing a specific move (by ID)
 *   STAT_THRESHOLD — requires a stat to be at or above a threshold ("strength>=200")
 *   ABILITY        — granted by possessing another specific ability (by ID or name)
 *                    e.g. "Precog" is granted by having "Heavenly Restriction"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AbilityData {

    public String id;          // 6-digit auto-assigned
    public String name;

    /** In-universe flavour text. Shown to the player. */
    public String flavourText;

    /**
     * Mechanical description with ALL_CAPS keywords.
     * Keywords are highlighted in the UI for quick scanning.
     * Example: "Sets CURSED_ENERGY_EFFICIENCY to MAX. All CE costs reduced to MINIMUM."
     */
    public String mechanicText;

    /** "PASSIVE" or "ACTIVE" */
    public String category;

    /** "CHARACTER", "TECHNIQUE", "MOVE", or "STAT_THRESHOLD" */
    public String sourceType;

    /**
     * Source qualifier:
     *   TECHNIQUE      → technique name (e.g. "Limitless")
     *   MOVE           → move ID (e.g. "000005")
     *   STAT_THRESHOLD → "stat>=value" (e.g. "cursedTechniqueMastery>=200")
     *   ABILITY        → ability ID or ability name (e.g. "000003" or "Heavenly Restriction")
     *   CHARACTER      → null
     */
    public String sourceValue;

    /** The list of effect primitives this ability applies. */
    public List<AbilityEffectData> effects;

    // ── Active-only fields ────────────────────────────────────────────────────

    /**
     * Sub-type for ACTIVE abilities: "QUEUED" or "TRIGGERED".
     * Null for PASSIVE abilities.
     */
    public String activeSubType;

    /**
     * For QUEUED actives: the move ID that represents this active ability.
     * The active IS a move — created in the move editor, referenced here.
     */
    public String activeMoveId;

    /**
     * For TRIGGERED actives: the trigger condition (AbilityTrigger enum name).
     * Null for PASSIVE and QUEUED actives.
     */
    public String triggerCondition;

    /**
     * Numeric threshold for trigger conditions that need one
     * (e.g. ON_HP_BELOW → 30 means "fires when HP is below 30%").
     * Zero for conditions with no threshold.
     */
    public int triggerThreshold;

    /**
     * Mastery threshold at which a technique-sourced ability unlocks and is
     * auto-granted to a character. For {@code sourceType == "TECHNIQUE"}
     * abilities, this is compared against the character's cursed-technique-mastery
     * (or a substitute stat for Copy-like abilities) — see
     * {@link com.jjktbf.model.technique.InnateTechnique#abilities}. Ignored for
     * non-technique abilities. Zero = unlocked from the start.
     */
    public int masteryThreshold;

    // ── Derived helpers ───────────────────────────────────────────────────────

    public boolean isPassive()  { return "PASSIVE".equalsIgnoreCase(category); }
    public boolean isActive()   { return "ACTIVE".equalsIgnoreCase(category); }
    public boolean isQueued()   { return "QUEUED".equalsIgnoreCase(activeSubType); }
    public boolean isTriggered(){ return "TRIGGERED".equalsIgnoreCase(activeSubType); }

    /**
     * Compute how many STAT_BONUS_POINTS this ability grants.
     * Used by the character editor to adjust the point-buy budget.
     */
    public int statBonusPoints() {
        if (effects == null) return 0;
        return effects.stream()
            .filter(e -> AbilityEffectType.STAT_BONUS_POINTS.name().equals(e.type))
            .mapToInt(e -> e.intValue != null ? e.intValue : 0)
            .sum();
    }

    /** One-line summary for list views. */
    public String summaryLine() {
        String cat = (category != null ? category : "?");
        String src = sourceType != null ? sourceType : "";
        String srcVal = sourceValue != null ? " (" + sourceValue + ")" : "";
        return String.format("[%s] [%s%s]", cat, src, srcVal);
    }
}
