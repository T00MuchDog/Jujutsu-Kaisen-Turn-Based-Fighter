package com.jjktbf.model.move;

import java.util.Collections;
import java.util.EnumSet;
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

    /** Original data tags, retained for UI, filtering, and lossless DTO round-trips. */
    private final Set<MoveTag> tags;

    /**
     * Which move pool (Combat Arts / Jujutsu Arts) this move draws its slot from.
     * Orthogonal to {@link #category} — derived from the raw PHYSICAL tag.
     * @see MovePool
     */
    private final MovePool pool;

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
     * ({@link DefenseType#BLOCK}). Dodges and parries are unaffected; only blocks
     * are bypassed. Backs the GUARD_BREAK move tag; not derived from {@link #category}.
     */
    private final boolean guardBreak;

    /**
     * Move potency tier (1–5). For attack moves, gates which defensive moves can
     * stop them; for defensive moves, gates which attacks they can stop. A defence
     * only applies when {@code defence.potency >= attack.potency}. Always 1 for
     * utility moves (unused). Backs the {@code potency} data field.
     */
    private final int potency;

    /**
     * If true, this move can only be used by a character who has a weapon
     * ({@code CharacterData.hasWeapon}). Forced on for {@link DefenseType#PARRY}
     * moves. Backs the {@code weaponRequired} data field.
     */
    private final boolean weaponRequired;

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

    /** Defensive behavior, if any. */
    private final DefenseType defenseType;

    /** Reduction formula used when {@link #defenseType} is {@link DefenseType#BLOCK}. */
    private final BlockStyle blockStyle;

    /**
     * Duration in AP ticks of the active defense window. 0 = use move's apCost.
     * -1 = end of round. Applies to {@link DefenseType#BLOCK}, {@link DefenseType#PARRY},
     * and {@link DefenseType#DODGE}.
     */
    private final int blockDuration;

    /**
     * The full set of damage tags this block can stop (null/empty = all). The
     * block fires against an incoming attack iff it COVERS every damage tag the
     * attack uses — i.e. the attack's category tags are a subset of this list.
     * See {@link #coveredByBlockTags(List)}. Applies to {@link DefenseType#BLOCK}.
     */
    private final List<String> blockAffectedTags;

    /** Percentage of damage reduced (0-100). 100 = full block. Used by {@link BlockStyle#PERCENTAGE}. */
    private final int blockDamageReduction;

    /** Flat damage subtracted from incoming damage. Used by {@link BlockStyle#FLAT}. */
    private final int blockFlatReduction;

    /** {@link DefenseType#DODGE}: chance (0–100%) to avoid a matching incoming attack. */
    private final int dodgeChance;

    /** {@link DefenseType#DODGE}: which attack ranges this dodge reacts to (MELEE / RANGED / BOTH). */
    private final String dodgeScope;

    /**
     * {@link DefenseType#PARRY}: AP ticks to {@link StatusEffectType#STAGGER stagger}
     * the attacker on a successful parry of a non-GUARD_BREAK attack. 0 = no stagger.
     */
    private final int parryStaggerTicks;

    /** Status effects this move applies on hit (may be empty). */
    private final List<StatusEffect> onHitEffects;

    /** Status effects applied to the defender when a {@link DefenseType#BLOCK} negates/reduces a hit. */
    private final List<StatusEffect> onBlockEffects;

    /** Status effects applied to the defender when a {@link DefenseType#PARRY} negates a hit. */
    private final List<StatusEffect> onParryEffects;

    /** Status effects applied to the defender when a {@link DefenseType#DODGE} avoids a hit. */
    private final List<StatusEffect> onDodgeEffects;

    /**
     * Status effects this move applies to the user on unleash (may be empty).
     * Applied by the combat engine when the move fires, for every move type
     * (damaging, defensive, and utility) — independent of whether the attack
     * later hits, misses, or is blocked.
     *
     * <p>A self-effect row may also carry a coded action (see
     * {@link StatusEffect#isCoded()}) — this is how a technique move's hardcoded
     * effect is expressed as an editable effect row instead of state on the Move.
     */
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

    /** If true, this move can only enter a character's pool through an ability grant. */
    private final boolean mustBeGranted;

    // -------------------------------------------------------------------------
    // Construction via Builder
    // -------------------------------------------------------------------------

    private Move(Builder b) {
        this.id                  = b.id;
        this.name                = b.name;
        this.description         = b.description;
        this.category            = b.category;
        this.tags                = immutableTags(b.tags, b.category);
        this.pool                = b.pool != null ? b.pool : MovePool.fromCategory(b.category);
        this.basePower           = b.basePower;
        this.baseAccuracy        = b.baseAccuracy;
        this.neverMiss           = b.neverMiss;
        this.stun                = b.stun;
        this.guardBreak          = b.guardBreak;
        this.heavy               = b.heavy;
        this.potency             = b.potency;
        this.weaponRequired      = b.weaponRequired;
        this.apCost              = b.apCost;
        this.unleashPoint        = b.unleashPoint;
        this.baseCeCost          = b.baseCeCost;
        this.hasCeCost           = b.hasCeCost != null ? b.hasCeCost : b.baseCeCost > 0;
        this.minCeCost           = b.minCeCost;
        this.maxCeCost           = b.maxCeCost;
        this.defenseType          = b.defenseType;
        this.blockStyle           = b.blockStyle != null ? b.blockStyle : BlockStyle.PERCENTAGE;
        this.blockDuration        = b.blockDuration;
        this.blockAffectedTags    = b.blockAffectedTags != null
            ? Collections.unmodifiableList(b.blockAffectedTags) : null;
        this.blockDamageReduction = b.blockDamageReduction;
        this.blockFlatReduction   = b.blockFlatReduction;
        this.dodgeChance          = b.dodgeChance;
        this.dodgeScope           = b.dodgeScope;
        this.parryStaggerTicks    = b.parryStaggerTicks;
        this.onHitEffects        = Collections.unmodifiableList(b.onHitEffects);
        this.selfEffects         = Collections.unmodifiableList(b.selfEffects);
        this.onBlockEffects      = Collections.unmodifiableList(b.onBlockEffects);
        this.onParryEffects      = Collections.unmodifiableList(b.onParryEffects);
        this.onDodgeEffects      = Collections.unmodifiableList(b.onDodgeEffects);
        this.prerequisites       = Collections.unmodifiableMap(b.prerequisites);
        this.requiredTechniqueId = b.requiredTechniqueId;
        this.isFreeMove          = b.isFreeMove;
        this.mustBeGranted       = b.mustBeGranted;
    }

    private static Set<MoveTag> immutableTags(Set<MoveTag> source, MoveCategory category) {
        EnumSet<MoveTag> copy = EnumSet.noneOf(MoveTag.class);
        if (source != null) copy.addAll(source);
        else if (category != null) copy.addAll(category.getTags());
        return Collections.unmodifiableSet(copy);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getId()                         { return id; }
    public String getName()                       { return name; }
    public String getDescription()                { return description; }
    public MoveCategory getCategory()             { return category; }
    public Set<MoveTag> getTags()                 { return tags; }
    public MovePool getPool()                     { return pool; }
    public int getBasePower()                     { return basePower; }
    public double getBaseAccuracy()               { return baseAccuracy; }
    public boolean isNeverMiss()                  { return neverMiss; }
    public boolean isStun()                       { return stun; }
    public boolean isGuardBreak()                 { return guardBreak; }
    public boolean isHeavy()                      { return heavy; }
    public int getPotency()                       { return potency; }
    public boolean isWeaponRequired()             { return weaponRequired; }
    public int getApCost()                        { return apCost; }
    public int getUnleashPoint()                  { return unleashPoint; }
    public int getBaseCeCost()                    { return baseCeCost; }
    public boolean hasCeCost()                     { return hasCeCost; }
    public int getMinCeCost()                     { return minCeCost; }
    public int getMaxCeCost()                     { return maxCeCost; }
    public DefenseType getDefenseType()           { return defenseType; }
    public BlockStyle getBlockStyle()             { return blockStyle; }
    public int getBlockDuration()                 { return blockDuration; }
    public List<String> getBlockAffectedTags()    { return blockAffectedTags; }
    public int getBlockDamageReduction()          { return blockDamageReduction; }
    public int getBlockFlatReduction()            { return blockFlatReduction; }
    public int getDodgeChance()                   { return dodgeChance; }
    public String getDodgeScope()                 { return dodgeScope; }
    public int getParryStaggerTicks()             { return parryStaggerTicks; }
    public List<StatusEffect> getOnHitEffects()   { return onHitEffects; }
    public List<StatusEffect> getSelfEffects()    { return selfEffects; }
    public List<StatusEffect> getOnBlockEffects() { return onBlockEffects; }
    public List<StatusEffect> getOnParryEffects() { return onParryEffects; }
    public List<StatusEffect> getOnDodgeEffects() { return onDodgeEffects; }
    public java.util.Map<String, Integer> getPrerequisites() { return prerequisites; }
    public String getRequiredTechniqueId()        { return requiredTechniqueId; }
    public boolean isFreeMove()                    { return isFreeMove; }
    public boolean mustBeGranted()                 { return mustBeGranted; }

    public boolean isBlackFlashEligible() {
        return category.isBlackFlashEligible();
    }

    public boolean hasTag(String tagName) {
        if (tagName == null || tagName.isBlank()) return true;
        String normalized = tagName.trim().toUpperCase();
        if ("ATTACK".equals(normalized)) {
            return tags.contains(MoveTag.ATTACK)
                || basePower > 0 && category != MoveCategory.DEFENSIVE && category != MoveCategory.UTILITY;
        }
        if ("STUN".equals(normalized)) return stun;
        if ("GUARD_BREAK".equals(normalized)) return guardBreak;
        if ("HEAVY".equals(normalized)) return heavy;
        if ("CURSED_ENERGY".equals(normalized)) {
            return tags.contains(MoveTag.CURSED_ENERGY)
                || category == MoveCategory.CURSED_ENERGY
                || category == MoveCategory.PHYSICAL_CURSED_ENERGY
                || category == MoveCategory.INNATE_TECHNIQUE
                || category == MoveCategory.NON_INNATE_TECHNIQUE
                || category == MoveCategory.PHYSICAL_INNATE_TECHNIQUE
                || category == MoveCategory.PHYSICAL_NON_INNATE_TECHNIQUE
                || category == MoveCategory.INNATE_NON_INNATE_TECHNIQUE
                || category == MoveCategory.PHYSICAL_INNATE_NON_INNATE_TECHNIQUE;
        }
        try {
            MoveTag tag = MoveTag.valueOf(normalized);
            return tags.contains(tag) || category.getTags().contains(tag);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Whether this move carries the {@link MoveTag#MELEE} range subcategory.
     *
     * <p>Range (melee/ranged) is a first-class, queryable property of a move —
     * an attack subcategory orthogonal to {@link MoveCategory}. Defensive moves
     * like deflections, abilities, and other systems query this to react to the
     * incoming attack's range, exactly like they query the damage category. It
     * is derived from the raw tag set (never stored as a redundant flag), so the
     * tag set remains the single source of truth.
     */
    public boolean isMelee() {
        return tags.contains(MoveTag.MELEE);
    }

    /**
     * Whether this move carries the {@link MoveTag#RANGED} range subcategory.
     * See {@link #isMelee()} — RANGED is the complementary range subcategory.
     */
    public boolean isRanged() {
        return tags.contains(MoveTag.RANGED);
    }

    /**
     * Whether this move targets an area rather than one combatant. Moves without
     * {@link MoveTag#AOE} are single-target by default.
     */
    public boolean isAoe() {
        return tags.contains(MoveTag.AOE);
    }

    /** Whether this move targets exactly one combatant. */
    public boolean isSingleTarget() {
        return !isAoe();
    }

    /**
     * Block-tag coverage check.
     *
     * <p>A block with {@code blockAffectedTags} fires against this incoming move
     * iff it covers every dimension the attack actually uses. There are two
     * orthogonal dimensions:
     *
     * <ul>
     *   <li><b>Damage dimension</b> — enforced iff the block declares at least
     *       one damage-nature tag ({@link MoveTag#TYPE_TAGS}). The block must
     *       <em>cover every damage tag the attack uses</em> — i.e. this move's
     *       {@link MoveCategory#getTags() category tags} are a <em>subset</em>
     *       of the block's declared damage tags. A block that declares only
     *       range tags (or none at all) imposes <b>no</b> damage-type
     *       restriction and stops attacks of any damage nature.</li>
     *   <li><b>Range dimension</b> — enforced iff the block declares at least
     *       one range tag ({@link MoveTag#RANGE_TAGS}). The incoming attack
     *       must carry every range tag the block names — i.e. a {@code [MELEE]}
     *       block requires {@link #isMelee()} on the attack. Range is queried
     *       through the {@link #isMelee()} / {@link #isRanged()} accessors
     *       because it is a first-class move subcategory, not a tag-poke.</li>
     * </ul>
     *
     * <p>Damage subset direction (unchanged): a block declares the full set of
     * damage types it can stop; an attack slips through if it uses even one tag
     * the block does not cover.
     * <ul>
     *   <li>Block = {@code [PHYSICAL]} vs attack {@code PHYSICAL+CURSED_ENERGY}
     *       → not covered (CE slips through).</li>
     *   <li>Block = {@code [PHYSICAL, CURSED_ENERGY]} vs a pure {@code PHYSICAL}
     *       attack → covered (the block's coverage is a superset of the attack).</li>
     * </ul>
     *
     * <p>Range-only example: a {@code [MELEE]} deflection block stops any
     * melee attack (physical, cursed energy, or technique) but lets a
     * {@code RANGED} attack — or an untagged attack — straight through.
     *
     * @param blockTags  the block's affected-tags list (null/empty = covers all)
     * @return true if this attack is fully covered by the block's tag set
     */
    public boolean coveredByBlockTags(List<String> blockTags) {
        if (blockTags == null || blockTags.isEmpty()) return true;
        // Normalise the block's tags once for cheap contains() checks.
        java.util.Set<String> covered = new java.util.HashSet<>();
        for (String t : blockTags) covered.add(t.trim().toUpperCase());

        // Range dimension (only present on attacks; range is a first-class
        // move subcategory, queried via isMelee()/isRanged() rather than read
        // out of any MoveCategory). The block must name every range tag it
        // cares about AND the attack must satisfy each via its accessor.
        if (covered.contains(MoveTag.MELEE.name()) && !isMelee()) return false;
        if (covered.contains(MoveTag.RANGED.name()) && !isRanged()) return false;

        // Damage dimension — enforced only when the block actually declares a
        // damage-nature tag. A range-only block (e.g. a [MELEE] deflection)
        // declares no damage tag, so it skips this check entirely and stops
        // attacks of any damage nature that satisfy the range dimension above.
        boolean blockDeclaresDamageTag = false;
        for (MoveTag typeTag : MoveTag.TYPE_TAGS) {
            if (covered.contains(typeTag.name())) {
                blockDeclaresDamageTag = true;
                break;
            }
        }
        if (!blockDeclaresDamageTag) return true;

        for (MoveTag attackTag : category.getTags()) {
            if (!covered.contains(attackTag.name())) return false;
        }
        return true;
    }

    public boolean isDefensive() {
        return category == MoveCategory.DEFENSIVE;
    }

    /** True iff this move is a {@link DefenseType#BLOCK}. */
    public boolean isBlock() {
        return defenseType == DefenseType.BLOCK;
    }

    /** True iff this move is a {@link DefenseType#PARRY}. */
    public boolean isParry() {
        return defenseType == DefenseType.PARRY;
    }

    /** True iff this move is a {@link DefenseType#DODGE}. */
    public boolean isDodge() {
        return defenseType == DefenseType.DODGE;
    }

    /**
     * True iff this move carries an active defense window that the timeline must
     * track (BLOCK, PARRY, or DODGE). SHIELD has no behaviour yet. Replaces the
     * former {@code isActiveBlock()}.
     */
    public boolean isActiveDefense() {
        return defenseType == DefenseType.BLOCK
            || defenseType == DefenseType.PARRY
            || defenseType == DefenseType.DODGE;
    }

    /**
     * Whether this dodge reacts to the given incoming attack's range. A DODGE
     * move with scope BOTH reacts to anything; MELEE/RANGED react only to the
     * matching range. Non-dodge moves return false.
     */
    public boolean dodgeAppliesTo(Move incoming) {
        if (defenseType != DefenseType.DODGE || incoming == null) return false;
        String scope = dodgeScope == null ? "BOTH" : dodgeScope.trim().toUpperCase();
        return switch (scope) {
            case "MELEE"  -> incoming.isMelee();
            case "RANGED" -> incoming.isRanged();
            default       -> true; // BOTH
        };
    }

    /**
     * Whether a successful parry of the given incoming attack should stagger the
     * attacker: the move must be a PARRY, the incoming attack must NOT carry
     * GUARD_BREAK, and {@code parryStaggerTicks} must be positive.
     */
    public boolean parryStaggersAttacker(Move incoming) {
        if (defenseType != DefenseType.PARRY) return false;
        if (incoming != null && incoming.isGuardBreak()) return false;
        return parryStaggerTicks > 0;
    }

    /**
     * Returns a human-readable activation message for this defensive move, for
     * use in combat events. Returns null if this move is not an active defense.
     */
    public String defenseActivationMessage(String characterName) {
        return switch (defenseType) {
            case BLOCK -> switch (blockStyle) {
                case PERCENTAGE -> characterName + " raises their block! (" + blockDamageReduction + "% damage reduction)";
                case FLAT       -> characterName + " raises their block! (-" + blockFlatReduction + " flat damage reduction)";
            };
            case PARRY -> characterName + " readies a parry!";
            case DODGE -> characterName + " stands ready to dodge!";
            default    -> null;
        };
    }

    /**
     * Returns a human-readable expiry message for this defensive move, fired at
     * the tick where its AP window ends. Returns null if this move is not an
     * active defense.
     */
    public String defenseExpiryMessage(String characterName) {
        return switch (defenseType) {
            case BLOCK -> characterName + " drops their guard!";
            case PARRY -> characterName + " lowers their parry stance.";
            case DODGE -> characterName + " drops their ready stance.";
            default    -> null;
        };
    }

    /**
     * Apply this move's block reduction to an incoming raw damage value.
     *
     * Returns the modified damage. Damage is never reduced below 1.
     * If this is a full PERCENTAGE block (100%), returns 0 to signal a complete block.
     * Callers should treat a return value of 0 as BLOCKED outcome.
     *
     * Should only be called if {@link #isBlock()} is true.
     */
    public int applyBlockTo(int rawDamage) {
        return (int) Math.round(applyBlockTo((double) rawDamage));
    }

    public double applyBlockTo(double incomingDamage) {
        if (defenseType != DefenseType.BLOCK) return incomingDamage;
        return switch (blockStyle) {
            case PERCENTAGE -> {
                if (blockDamageReduction >= 100) yield 0; // full block
                yield Math.max(1.0, incomingDamage * (100 - blockDamageReduction) / 100.0);
            }
            case FLAT -> Math.max(1.0, incomingDamage - blockFlatReduction);
        };
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
        private Set<MoveTag> tags;
        private MovePool pool;
        private int basePower                = 0;
        private double baseAccuracy          = 1.0;
        private boolean neverMiss            = false;
        private boolean stun                 = false;
        private boolean guardBreak           = false;
        private boolean heavy                = false;
        private int potency                  = 1;
        private boolean weaponRequired       = false;
        private int apCost                   = 10;
        private int unleashPoint             = 10;
        private int baseCeCost               = 0;
        private Boolean hasCeCost             = null;
        private int minCeCost                = 0;
        private int maxCeCost                = 0;
        private DefenseType defenseType        = DefenseType.NONE;
        private BlockStyle blockStyle          = BlockStyle.PERCENTAGE;
        private int blockDuration              = 0;
        private List<String> blockAffectedTags = null;
        private int blockDamageReduction       = 100;
        private int blockFlatReduction         = 0;
        private int dodgeChance                = 0;
        private String dodgeScope              = "BOTH";
        private int parryStaggerTicks          = 0;
        private List<StatusEffect> onHitEffects = List.of();
        private List<StatusEffect> selfEffects  = List.of();
        private List<StatusEffect> onBlockEffects = List.of();
        private List<StatusEffect> onParryEffects = List.of();
        private List<StatusEffect> onDodgeEffects = List.of();
        private java.util.Map<String, Integer> prerequisites = java.util.Map.of();
        private String requiredTechniqueId   = null;
        private boolean isFreeMove           = false;
        private boolean mustBeGranted        = false;

        public Builder(String id) { this.id = id; }

        public Builder name(String v)                      { this.name = v; return this; }
        public Builder description(String v)               { this.description = v; return this; }
        public Builder category(MoveCategory v)            { this.category = v; return this; }
        public Builder tags(Set<MoveTag> v)                { this.tags = v == null ? null : Set.copyOf(v); return this; }
        public Builder pool(MovePool v)                    { this.pool = v; return this; }
        public Builder basePower(int v)                    { this.basePower = v; return this; }
        public Builder baseAccuracy(double v)              { this.baseAccuracy = v; return this; }
        public Builder neverMiss(boolean v)                { this.neverMiss = v; return this; }
        public Builder stun(boolean v)                     { this.stun = v; return this; }
        public Builder guardBreak(boolean v)               { this.guardBreak = v; return this; }
        public Builder heavy(boolean v)                    { this.heavy = v; return this; }
        public Builder potency(int v)                      { this.potency = v; return this; }
        public Builder weaponRequired(boolean v)           { this.weaponRequired = v; return this; }
        public Builder apCost(int v)                       { this.apCost = v; return this; }
        public Builder unleashPoint(int v)                 { this.unleashPoint = v; return this; }
        public Builder baseCeCost(int v)                   { this.baseCeCost = v; return this; }
        public Builder hasCeCost(boolean v)                { this.hasCeCost = v; return this; }
        public Builder minCeCost(int v)                    { this.minCeCost = v; return this; }
        public Builder maxCeCost(int v)                    { this.maxCeCost = v; return this; }
        public Builder defenseType(DefenseType v)          { this.defenseType = v; return this; }
        public Builder blockStyle(BlockStyle v)            { this.blockStyle = v; return this; }
        public Builder blockDuration(int v)                { this.blockDuration = v; return this; }
        public Builder blockAffectedTags(List<String> v)   { this.blockAffectedTags = v; return this; }
        public Builder blockDamageReduction(int v)         { this.blockDamageReduction = v; return this; }
        public Builder blockFlatReduction(int v)           { this.blockFlatReduction = v; return this; }
        public Builder dodgeChance(int v)                  { this.dodgeChance = v; return this; }
        public Builder dodgeScope(String v)                { this.dodgeScope = v; return this; }
        public Builder parryStaggerTicks(int v)            { this.parryStaggerTicks = v; return this; }
        public Builder onHitEffects(List<StatusEffect> v)  { this.onHitEffects = v; return this; }
        public Builder selfEffects(List<StatusEffect> v)   { this.selfEffects = v; return this; }
        public Builder onBlockEffects(List<StatusEffect> v){ this.onBlockEffects = v; return this; }
        public Builder onParryEffects(List<StatusEffect> v){ this.onParryEffects = v; return this; }
        public Builder onDodgeEffects(List<StatusEffect> v){ this.onDodgeEffects = v; return this; }
        public Builder prerequisites(java.util.Map<String, Integer> v) { this.prerequisites = v; return this; }
        public Builder requiredTechniqueId(String v)       { this.requiredTechniqueId = v; return this; }
        public Builder freeMove(boolean v)                 { this.isFreeMove = v; return this; }
        public Builder mustBeGranted(boolean v)            { this.mustBeGranted = v; return this; }

        public Move build() {
            if (id == null || id.isBlank()) throw new IllegalStateException("Move id is required");
            if (unleashPoint < 1 || unleashPoint > apCost)
                throw new IllegalStateException("unleashPoint must be in [1, apCost]");

            // Potency lives on attack and defensive moves (gates which defences
            // stop which attacks). Utility moves don't participate, so clamp to 1.
            if (category == MoveCategory.UTILITY) {
                potency = 1;
            } else if (potency < 1) {
                potency = 1;
            }
            // A parry requires a weapon by definition.
            if (defenseType == DefenseType.PARRY) {
                weaponRequired = true;
            }

            // Technique-tag invariant: moves bearing the INNATE_TECHNIQUE or
            // NON_INNATE_TECHNIQUE tag MUST declare their governing mastery stat
            // as a prerequisite (even if 0), and innate-technique moves MUST name
            // their technique. This is what lets a Technique's progression be
            // discovered and mastery-sorted at runtime, and keeps the editor's
            // save validation honest (it routes through this builder).
            if (category != null) {
                var tags = this.tags != null ? this.tags : category.getTags();
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
