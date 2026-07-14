package com.jjktbf.model.move;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Immutable descriptor for a single move.
 *
 * A Move defines everything about what the move IS — it does not execute itself.
 * Execution (damage calculation, effect application, interrupt resolution) is handled
 * by the CombatResolver, keeping the model free from combat logic.
 *
 * Special moves:
 *  - BASIC_PUNCH and BASIC_BLOCK are always available to every character regardless
 *    of move slots (isFreeMove = true).
 *
 * CE cost:
 *  - baseCeCost is modified at use-time by the character's CE Efficiency stat.
 *  - minCeCost / maxCeCost are hard floors/ceilings that efficiency cannot breach.
 *  - hasCeCost distinguishes a CE move costing 0 from a move with no CE cost.
 *
 * Prerequisites:
 *  - A character cannot learn this move unless all prerequisite stat thresholds are met.
 *
 * Technique restriction:
 *  - If requiredTechniqueId is non-null, only characters who possess that specific
 *    innate technique can learn or use this move.
 */
public class Move {

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    /** Unique identifier used to reference this move from data files and character sheets. */
    private final String id;

    /** Display name. */
    private final String name;

    /** Flavour description shown to the player. */
    private final String description;

    /** Determines the Power formula and Black Flash eligibility. */
    private final MoveCategory category;

    /**
     * Base power of the move.
     * This raw number is scaled by the attacker's Power (from PowerCalculator)
     * and reduced by the defender's Defense inside DamageCalculator.
     * 0 for non-damaging moves.
     */
    private final int basePower;

    /**
     * Base accuracy as a fraction [0.0, 1.0].
     * 1.0 = 100% (still subject to Accuracy vs Evasion roll).
     * Moves that cannot miss use a sentinel value of Double.MAX_VALUE
     * or are flagged via neverMiss = true.
     */
    private final double baseAccuracy;

    /** If true, this move always hits regardless of Evasion. */
    private final boolean neverMiss;

    /**
     * If true, a successful hit stuns the defender's action segment(s) on the current
     * tick (removes them from the timeline). A modifier flag backing the STUN move tag;
     * not derived from {@link #category}.
     */
    private final boolean stun;

    /**
     * If true, a successful hit ignores the defender's blocking defensive moves
     * (PERCENTAGE_BLOCK / FLAT_BLOCK). Dodges and parries are unaffected; only blocks
     * are bypassed. Backs the GUARD_BREAK move tag; not derived from {@link #category}.
     */
    private final boolean guardBreak;

    /**
     * If true, an action segment carrying this move cannot be stunned by a STUN-tagged
     * hit (it is skipped by the stun effect). Interrupts are unaffected. Backs the
     * HEAVY move tag; not derived from {@link #category}.
     */
    private final boolean heavy;

    /**
     * Size of the action segment this move occupies on the AP timeline.
     * Min: 5,  Max: ~100.
     */
    private final int apCost;

    /**
     * The AP tick within the action segment at which the move is unleashed.
     * Range: [1, apCost].
     * Unleash at tick 1 = instant/highest priority.
     * Unleash at tick == apCost = full charge.
     */
    private final int unleashPoint;

    /** Base CE cost before efficiency scaling. May be 0 when {@link #hasCeCost} is true. */
    private final int baseCeCost;

    /** Whether this move has a CE cost at all (including an intentional cost of 0). */
    private final boolean hasCeCost;

    /** Hard minimum CE cost — efficiency cannot reduce below this. */
    private final int minCeCost;

    /** Hard maximum CE cost — efficiency cannot raise above this. */
    private final int maxCeCost;

    /** Whether this move can interrupt an opponent's queued actions. */
    private final InterruptType interruptType;

    /** Defensive behavior, if any. */
    private final DefenseType defenseType;

    /** Duration in AP ticks. 0 = use move's apCost. -1 = end of round. Applies to PERCENTAGE_BLOCK and FLAT_BLOCK. */
    private final int blockDuration;

    /** Tags this block affects. Null = all damage types. Applies to PERCENTAGE_BLOCK and FLAT_BLOCK. */
    private final List<String> blockAffectedTags;

    /** Percentage of damage reduced (0-100). 100 = full block. Used by PERCENTAGE_BLOCK. */
    private final int blockDamageReduction;

    /** Flat damage subtracted from incoming damage. Used by FLAT_BLOCK. */
    private final int blockFlatReduction;

    /** Status effects this move applies on hit (may be empty). */
    private final List<StatusEffect> onHitEffects;

    /** Status effects this move applies to the user on unleash (may be empty). */
    private final List<StatusEffect> selfEffects;

    /**
     * Stat prerequisites. Key = stat name matching CharacterStats getter convention,
     * Value = minimum required value.
     * A character cannot learn this move if any prerequisite is not met.
     */
    private final java.util.Map<String, Integer> prerequisites;

    /**
     * If non-null, the character must possess this specific innate technique
     * to learn or use this move (e.g. "BLOOD_MANIPULATION", "SHRINE").
     */
    private final String requiredTechniqueId;

    /** If true, this move does not consume a move slot when assigned to a character. */
    private final boolean isFreeMove;

    // -------------------------------------------------------------------------
    // Construction via Builder
    // -------------------------------------------------------------------------

    private Move(Builder b) {
        this.id                  = b.id;
        this.name                = b.name;
        this.description         = b.description;
        this.category            = b.category;
        this.basePower           = b.basePower;
        this.baseAccuracy        = b.baseAccuracy;
        this.neverMiss           = b.neverMiss;
        this.stun                = b.stun;
        this.guardBreak          = b.guardBreak;
        this.heavy               = b.heavy;
        this.apCost              = b.apCost;
        this.unleashPoint        = b.unleashPoint;
        this.baseCeCost          = b.baseCeCost;
        this.hasCeCost           = b.hasCeCost != null ? b.hasCeCost : b.baseCeCost > 0;
        this.minCeCost           = b.minCeCost;
        this.maxCeCost           = b.maxCeCost;
        this.interruptType        = b.interruptType;
        this.defenseType          = b.defenseType;
        this.blockDuration        = b.blockDuration;
        this.blockAffectedTags    = b.blockAffectedTags != null
            ? Collections.unmodifiableList(b.blockAffectedTags) : null;
        this.blockDamageReduction = b.blockDamageReduction;
        this.blockFlatReduction   = b.blockFlatReduction;
        this.onHitEffects        = Collections.unmodifiableList(b.onHitEffects);
        this.selfEffects         = Collections.unmodifiableList(b.selfEffects);
        this.prerequisites       = Collections.unmodifiableMap(b.prerequisites);
        this.requiredTechniqueId = b.requiredTechniqueId;
        this.isFreeMove          = b.isFreeMove;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getId()                         { return id; }
    public String getName()                       { return name; }
    public String getDescription()                { return description; }
    public MoveCategory getCategory()             { return category; }
    public int getBasePower()                     { return basePower; }
    public double getBaseAccuracy()               { return baseAccuracy; }
    public boolean isNeverMiss()                  { return neverMiss; }
    public boolean isStun()                       { return stun; }
    public boolean isGuardBreak()                 { return guardBreak; }
    public boolean isHeavy()                      { return heavy; }
    public int getApCost()                        { return apCost; }
    public int getUnleashPoint()                  { return unleashPoint; }
    public int getBaseCeCost()                    { return baseCeCost; }
    public boolean hasCeCost()                     { return hasCeCost; }
    public int getMinCeCost()                     { return minCeCost; }
    public int getMaxCeCost()                     { return maxCeCost; }
    public InterruptType getInterruptType()       { return interruptType; }
    public DefenseType getDefenseType()           { return defenseType; }
    public int getBlockDuration()                 { return blockDuration; }
    public List<String> getBlockAffectedTags()    { return blockAffectedTags; }
    public int getBlockDamageReduction()          { return blockDamageReduction; }
    public int getBlockFlatReduction()            { return blockFlatReduction; }
    public List<StatusEffect> getOnHitEffects()   { return onHitEffects; }
    public List<StatusEffect> getSelfEffects()    { return selfEffects; }
    public java.util.Map<String, Integer> getPrerequisites() { return prerequisites; }
    public String getRequiredTechniqueId()        { return requiredTechniqueId; }
    public boolean isFreeMove()                    { return isFreeMove; }

    public boolean isBlackFlashEligible() {
        return category.isBlackFlashEligible();
    }

    public boolean hasTag(String tagName) {
        if (tagName == null || tagName.isBlank()) return true;
        String normalized = tagName.trim().toUpperCase();
        if ("ATTACK".equals(normalized)) return basePower > 0 && category != MoveCategory.DEFENSIVE && category != MoveCategory.UTILITY;
        if ("STUN".equals(normalized)) return stun;
        if ("GUARD_BREAK".equals(normalized)) return guardBreak;
        if ("HEAVY".equals(normalized)) return heavy;
        if ("CURSED_ENERGY".equals(normalized)) {
            return category == MoveCategory.PHYSICAL_CURSED_ENERGY
                || category == MoveCategory.INNATE_TECHNIQUE
                || category == MoveCategory.NON_INNATE_TECHNIQUE
                || category == MoveCategory.PHYSICAL_INNATE_TECHNIQUE
                || category == MoveCategory.PHYSICAL_NON_INNATE_TECHNIQUE
                || category == MoveCategory.INNATE_NON_INNATE_TECHNIQUE
                || category == MoveCategory.PHYSICAL_INNATE_NON_INNATE_TECHNIQUE;
        }
        try {
            return category.getTags().contains(MoveTag.valueOf(normalized));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public boolean hasAllTags(List<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) return true;
        for (String tagName : tagNames) {
            if (!hasTag(tagName)) return false;
        }
        return true;
    }

    public boolean hasInterrupt() {
        return interruptType != InterruptType.NONE;
    }

    public boolean isDefensive() {
        return defenseType != DefenseType.NONE;
    }

    /**
     * Returns a short display string summarising this block's reduction amount.
     * e.g. "-50% damage" or "-30 flat dmg". Returns null if not a block.
     */
    public String blockDisplayInfo() {
        return switch (defenseType) {
            case PERCENTAGE_BLOCK -> "-" + blockDamageReduction + "% damage";
            case FLAT_BLOCK       -> "-" + blockFlatReduction + " flat dmg";
            default               -> null;
        };
    }

    /**
     * Returns a human-readable activation message for this block move, for use in combat events.
     * Returns null if this move is not a block.
     */
    public String blockActivationMessage(String characterName) {
        return switch (defenseType) {
            case PERCENTAGE_BLOCK -> characterName + " raises their block! (" + blockDamageReduction + "% damage reduction)";
            case FLAT_BLOCK       -> characterName + " raises their block! (-" + blockFlatReduction + " flat damage reduction)";
            default               -> null;
        };
    }

    /**
     * Returns true if this move acts as an active block (PERCENTAGE_BLOCK or FLAT_BLOCK).
     * Used by Timeline to identify blocks without knowing concrete DefenseType values.
     */
    public boolean isActiveBlock() {
        return defenseType == DefenseType.PERCENTAGE_BLOCK || defenseType == DefenseType.FLAT_BLOCK;
    }

    /**
     * Apply this move's block reduction to an incoming raw damage value.
     *
     * Returns the modified damage. Damage is never reduced below 1.
     * If this is a full PERCENTAGE_BLOCK (100%), returns 0 to signal a complete block.
     * Callers should treat a return value of 0 as BLOCKED outcome.
     *
     * Should only be called if isActiveBlock() is true.
     */
    public int applyBlockTo(int rawDamage) {
        return (int) Math.round(applyBlockTo((double) rawDamage));
    }

    public double applyBlockTo(double incomingDamage) {
        return switch (defenseType) {
            case PERCENTAGE_BLOCK -> {
                if (blockDamageReduction >= 100) yield 0; // full block
                yield Math.max(1.0, incomingDamage * (100 - blockDamageReduction) / 100.0);
            }
            case FLAT_BLOCK -> Math.max(1.0, incomingDamage - blockFlatReduction);
            default -> incomingDamage; // not a block move — no reduction
        };
    }

    /**
     * Resolve this move's interrupt effect against the defender's timeline at the given tick.
     * Returns the ActionSegment that was stunned, or null if no segment was targeted.
     *
     * Should only be called if hasInterrupt() is true.
     */
    public com.jjktbf.model.combat.ActionSegment resolveInterruptOn(
            int tick,
            com.jjktbf.model.combat.Timeline defenderTimeline) {
        if (defenderTimeline == null) return null;
        com.jjktbf.model.combat.ActionSegment target = switch (interruptType) {
            case KNOCK_CURRENT_SEGMENT -> defenderTimeline.segmentAt(tick);
            case KNOCK_NEXT_SEGMENT    -> defenderTimeline.nextSegmentAfter(tick);
            case NONE                  -> null;
        };
        if (target != null && !target.isStunned()) {
            target.stun();
            return target;
        }
        return null;
    }

    @Override
    public String toString() {
        return String.format("Move{%s [%s] AP=%d unleash=%d CE=%d}", name, category, apCost, unleashPoint, baseCeCost);
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static class Builder {
        private String id;
        private String name                  = "";
        private String description           = "";
        private MoveCategory category        = MoveCategory.PHYSICAL;
        private int basePower                = 0;
        private double baseAccuracy          = 1.0;
        private boolean neverMiss            = false;
        private boolean stun                 = false;
        private boolean guardBreak           = false;
        private boolean heavy                = false;
        private int apCost                   = 10;
        private int unleashPoint             = 10;
        private int baseCeCost               = 0;
        private Boolean hasCeCost             = null;
        private int minCeCost                = 0;
        private int maxCeCost                = 0;
        private InterruptType interruptType  = InterruptType.NONE;
        private DefenseType defenseType        = DefenseType.NONE;
        private int blockDuration              = 0;
        private List<String> blockAffectedTags = null;
        private int blockDamageReduction       = 100;
        private int blockFlatReduction         = 0;
        private List<StatusEffect> onHitEffects = List.of();
        private List<StatusEffect> selfEffects  = List.of();
        private java.util.Map<String, Integer> prerequisites = java.util.Map.of();
        private String requiredTechniqueId   = null;
        private boolean isFreeMove           = false;

        public Builder(String id) { this.id = id; }

        public Builder name(String v)                      { this.name = v; return this; }
        public Builder description(String v)               { this.description = v; return this; }
        public Builder category(MoveCategory v)            { this.category = v; return this; }
        public Builder basePower(int v)                    { this.basePower = v; return this; }
        public Builder baseAccuracy(double v)              { this.baseAccuracy = v; return this; }
        public Builder neverMiss(boolean v)                { this.neverMiss = v; return this; }
        public Builder stun(boolean v)                     { this.stun = v; return this; }
        public Builder guardBreak(boolean v)               { this.guardBreak = v; return this; }
        public Builder heavy(boolean v)                    { this.heavy = v; return this; }
        public Builder apCost(int v)                       { this.apCost = v; return this; }
        public Builder unleashPoint(int v)                 { this.unleashPoint = v; return this; }
        public Builder baseCeCost(int v)                   { this.baseCeCost = v; return this; }
        public Builder hasCeCost(boolean v)                { this.hasCeCost = v; return this; }
        public Builder minCeCost(int v)                    { this.minCeCost = v; return this; }
        public Builder maxCeCost(int v)                    { this.maxCeCost = v; return this; }
        public Builder interruptType(InterruptType v)      { this.interruptType = v; return this; }
        public Builder defenseType(DefenseType v)          { this.defenseType = v; return this; }
        public Builder blockDuration(int v)                { this.blockDuration = v; return this; }
        public Builder blockAffectedTags(List<String> v)   { this.blockAffectedTags = v; return this; }
        public Builder blockDamageReduction(int v)         { this.blockDamageReduction = v; return this; }
        public Builder blockFlatReduction(int v)           { this.blockFlatReduction = v; return this; }
        public Builder onHitEffects(List<StatusEffect> v)  { this.onHitEffects = v; return this; }
        public Builder selfEffects(List<StatusEffect> v)   { this.selfEffects = v; return this; }
        public Builder prerequisites(java.util.Map<String, Integer> v) { this.prerequisites = v; return this; }
        public Builder requiredTechniqueId(String v)       { this.requiredTechniqueId = v; return this; }
        public Builder freeMove(boolean v)                 { this.isFreeMove = v; return this; }

        public Move build() {
            if (id == null || id.isBlank()) throw new IllegalStateException("Move id is required");
            if (unleashPoint < 1 || unleashPoint > apCost)
                throw new IllegalStateException("unleashPoint must be in [1, apCost]");

            // Technique-tag invariant: moves bearing the INNATE_TECHNIQUE or
            // NON_INNATE_TECHNIQUE tag MUST declare their governing mastery stat
            // as a prerequisite (even if 0), and innate-technique moves MUST name
            // their technique. This is what lets a Technique's progression be
            // discovered and mastery-sorted at runtime, and keeps the editor's
            // save validation honest (it routes through this builder).
            if (category != null) {
                var tags = category.getTags();
                boolean isInnate    = tags.contains(MoveTag.INNATE_TECHNIQUE);
                boolean isNonInnate = tags.contains(MoveTag.NON_INNATE_TECHNIQUE);
                if (isInnate) {
                    if (requiredTechniqueId == null || requiredTechniqueId.isBlank()) {
                        throw new IllegalStateException(
                            "Innate-technique moves must set a requiredTechniqueId (name='" + name + "')");
                    }
                    if (!hasStatPrereq(prerequisites, "cursedtechniquemastery", "ctm")) {
                        throw new IllegalStateException(
                            "Innate-technique moves must declare a cursedTechniqueMastery prerequisite (name='" + name + "')");
                    }
                }
                if (isNonInnate) {
                    if (!hasStatPrereq(prerequisites, "jujutsuskill", "js")) {
                        throw new IllegalStateException(
                            "Non-innate-technique moves must declare a jujutsuSkill prerequisite (name='" + name + "')");
                    }
                }
            }

            return new Move(this);
        }

        /**
         * Case/underscore/whitespace-insensitive check that a prerequisite map
         * contains one of the candidate stat names (canonical or alias).
         */
        private static boolean hasStatPrereq(java.util.Map<String, Integer> prereqs,
                                             String canonical, String alias) {
            String canon = normalise(canonical);
            String ali   = normalise(alias);
            for (String key : prereqs.keySet()) {
                String k = normalise(key);
                if (k.equals(canon) || k.equals(ali)) return true;
            }
            return false;
        }

        private static String normalise(String s) {
            return s == null ? "" : s.toLowerCase().replace("_", "").replace(" ", "");
        }
    }
}
