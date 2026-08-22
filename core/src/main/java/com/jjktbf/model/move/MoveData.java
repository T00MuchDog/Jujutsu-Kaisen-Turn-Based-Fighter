package com.jjktbf.model.move;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectTarget;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.coded.CursedSpeechAbility;
import com.jjktbf.model.progression.TechniqueMasteryProgressionData;
import com.jjktbf.model.progression.TechniqueMasteryProgressions;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plain data object (DTO) for serialising/deserialising a Move to/from JSON.
 *
 * ID: 6-digit zero-padded integer string, auto-assigned by MoveRepository.
 *
 * Category is derived at runtime from the tags list — it is not stored separately.
 * Tags is the canonical representation; MoveCategory is computed via MoveCategory.fromTags().
 *
 * requiredTechniqueId stores the technique's ID string (e.g. "SHRINE").
 * Technique IDs live in TechniqueRepository and are resolved separately.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MoveData {

    public String id;           // 6-digit auto-assigned, e.g. "000003"
    public String name;
    public String description;

    /**
     * Authored move class ({@code SORCERER}, {@code CURSED_SPIRIT}, or
     * {@code SHIKIGAMI}). Missing values are legacy sorcerer moves.
     */
    public String moveType;

    /**
     * List of MoveTag enum names applied to this move.
     * e.g. ["PHYSICAL", "ATTACK"]  or  ["INNATE_TECHNIQUE", "ATTACK", "CURSED_ENERGY"]
     * The MoveCategory is derived from this set at conversion time.
     */
    public List<String> tags;

    /** Set by the legacy {@code weaponRequired} compat reader below. */
    private transient boolean legacyWeaponRequired;

    /**
     * Compat reader: the old {@code SWORD} weapon tag became the KATANA
     * weapon-type tag, so legacy saves are translated on load.
     */
    @com.fasterxml.jackson.annotation.JsonProperty("tags")
    private void readTags(List<String> storedTags) {
        List<String> normalized = new java.util.ArrayList<>();
        if (storedTags != null) {
            for (String tag : storedTags) {
                if (tag == null || tag.isBlank()) continue;
                normalized.add("SWORD".equalsIgnoreCase(tag.trim())
                    ? MoveTag.KATANA.name() : tag);
            }
        }
        this.tags = normalized;
        applyLegacyWeaponTag();
    }

    /**
     * Compat reader: the boolean "requires a weapon" toggle was replaced by the
     * weapon-type tags. Legacy weapon-required moves (parries and sword moves)
     * are all katana-flavoured, so the old flag translates to a KATANA tag.
     */
    @com.fasterxml.jackson.annotation.JsonProperty("weaponRequired")
    private void readWeaponRequired(boolean weaponRequired) {
        this.legacyWeaponRequired |= weaponRequired;
        applyLegacyWeaponTag();
    }

    private void applyLegacyWeaponTag() {
        if (!legacyWeaponRequired || hasWeaponTagName(tags)) return;
        if (tags == null) tags = new java.util.ArrayList<>();
        tags.add(MoveTag.KATANA.name());
    }

    private static boolean hasWeaponTagName(List<String> storedTags) {
        if (storedTags == null) return false;
        for (String tag : storedTags) {
            if (tag == null || tag.isBlank()) continue;
            try {
                if (MoveTag.WEAPON_TAGS.contains(MoveTag.valueOf(tag.trim().toUpperCase()))) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // Unknown tags are dropped elsewhere; not a weapon tag.
            }
        }
        return false;
    }

    public int     basePower;
    /** Null in legacy data; when present, this ordered list is authoritative. */
    public List<HitComponentData> hitComponents;
    public double  baseAccuracy   = 1.0;
    /** Legacy compatibility field; new move content uses a NEVER_MISS effect row. */
    public boolean neverMiss      = false;

    /** Legacy compatibility field migrated to a STUN_CURRENT_ACTION effect row. */
    @Deprecated
    public Boolean stun;

    /**
     * Backs the GUARD_BREAK move tag. When true, a successful hit ignores the
     * defender's blocking defensive moves (BLOCK).
     */
    public boolean guardBreak     = false;

    /**
     * Backs the HEAVY move tag. When true, an action segment carrying this move
     * cannot be cancelled by a stun-current-action effect.
     */
    public boolean heavy          = false;

    /**
     * Move potency tier (1–5). For attack and defensive moves, gates which
     * defences stop which attacks (a defence applies iff its potency ≥ the
     * attack's). Utility moves ignore it. Defaults to 1.
     */
    public int     potency        = 1;

    public int     apCost;
    public int     unleashPoint;

    public int     baseCeCost     = 0;
    /** Null in older saves; then inferred from a positive base CE cost on load. */
    public Boolean hasCeCost;
    public int     minCeCost      = 0;
    public int     maxCeCost      = 0;

    /** DefenseType enum name */
    public String  defenseType    = "NONE";

    /** BlockStyle enum name (PERCENTAGE / FLAT). Used only when defenseType = BLOCK. */
    public String  blockStyle     = "PERCENTAGE";

    /** Block/dodge/parry shared field: duration in AP ticks. 0 = use move's apCost. -1 = end of round. */
    public int     blockDuration = 0;
    /** Tags this block or parry affects. Null = all damage types. */
    public List<String> blockAffectedTags;
    /** PERCENTAGE block only: percentage of damage reduced (0-100). 100 = full block. */
    public int     blockDamageReduction = 100;
    /** FLAT block only: flat damage amount subtracted from incoming attacks. */
    public int     blockFlatReduction = 0;

    /** DODGE only: chance (0-100%) to avoid a matching incoming attack. */
    public int     dodgeChance    = 0;
    /** DODGE only: MELEE / RANGED / BOTH. */
    public String  dodgeScope     = "BOTH";

    /** PARRY only: AP ticks to stagger the attacker on a successful non-GUARD_BREAK parry. 0 = none. */
    public int     parryStaggerTicks = 0;

    /**
     * {@link DefenseTiming} enum name: FIXED (default) opens the window at the
     * fire tick; REACTION arms at the fire tick and triggers on the next
     * matching incoming attack, opening its window then.
     */
    public String  defenseTiming  = "FIXED";

    /**
     * How many incoming attacks this defence may contest while its window is
     * active. 0 = unlimited (applies to every matching attack in its window).
     * Never changes the window's duration — once the cap is spent the defence
     * stops applying even while the window is still open.
     */
    public int     defenseUses    = 0;

    /** List of on-hit StatusEffect descriptors */
    public List<StatusEffectData> onHitEffects;

    /** List of self StatusEffect descriptors */
    public List<StatusEffectData> selfEffects;

    /** Status effects applied to the defender when a BLOCK negates/reduces a hit. */
    public List<StatusEffectData> onBlockEffects;
    /** Status effects applied to the defender when a PARRY negates a hit. */
    public List<StatusEffectData> onParryEffects;
    /** Status effects applied to the defender when a DODGE avoids a hit. */
    public List<StatusEffectData> onDodgeEffects;

    /**
     * Canonical mutable composition of shared effect primitives. Each row owns
     * its move trigger, target, optional extra condition, and optional chance.
     * A non-null list is authoritative over the legacy attachment fields above.
     */
    public List<MoveEffectData> effects;

    /** Prerequisite stats: {"strength": 80, "speed": 60, ...} */
    public Map<String, Integer> prerequisites;

    /**
     * Human-readable technique name this move requires (e.g. "Shrine", "Blood Manipulation").
     * Null means no technique restriction.
     * The numeric technique ID is resolved via TechniqueRepository at load time.
     */
    public String  requiredTechniqueId;

    /**
     * Optional 6-digit cursed-tool ID that grants this move while equipped.
     * Unlike {@code GRANT_MOVE}, this assignment does not bypass move
     * requirements. Weapon-type grants are derived separately from {@link #tags}.
     */
    public String requiredCursedToolId;

    public boolean isFreeMove = false;

    /** Maximum placements per round. 0 means unlimited. */
    public int moveCap = 0;

    /**
     * Canonical shikigami character id summoned when this move reaches its unleash
     * point. Null/blank for non-summoning moves. Only valid SHIKIGAMI definitions
     * may be referenced (validated in editors and the authoritative catalog).
     */
    public String summonCharacterId;

    /**
     * {@link AoeType} enum name for an AOE move's authoritative targeting shape.
     * Blank/null on a non-AOE move; also blank on legacy AOE saves, which are
     * migrated to a default at load time (ALL_ENEMIES, or ALL_OTHERS when the
     * FRIENDLY_FIRE tag is present).
     */
    public String aoeType;

    /**
     * Target count for {@link AoeType#MULTIPLE} AOE moves. Ignored for the other
     * shapes. Defaults to 2; must be ≥ 2 when used.
     */
    public int aoeTargetCount = 2;

    /**
     * {@link com.jjktbf.model.move.DefenseTargeting} enum name for a defensive
     * move's authoritative targeting shape (whose timeline the active-defense
     * window is conferred to). Blank/null/invalid resolves to SELF at load time,
     * preserving legacy defensive moves.
     */
    public String defenseTargeting = "SELF";

    /**
     * Ally count for {@link com.jjktbf.model.move.DefenseTargeting#MULTIPLE_ALLIES}
     * defensive moves. Ignored for the other shapes. Defaults to 2; must be ≥ 2
     * when used.
     */
    public int defenseTargetCount = 2;

    /**
     * {@link AttackLaunchMode} enum name for a Defensive+Attack hybrid: when the
     * attack portion launches. ON_FIRE launches at the move's firing tick (right
     * after the defence is granted); ON_DEFENCE launches after this move's
     * defence successfully resolves an incoming attack. Null on non-hybrids.
     */
    public String attackLaunchMode;

    /**
     * Extra condition tree gating the hybrid attack's launch, evaluated like an
     * effect row's activation condition. Null = always launch.
     */
    public AbilityConditionData attackLaunchCondition;

    /** Whether the hybrid attack's launch rolls {@link #attackLaunchChance}. */
    public Boolean attackLaunchChanceEnabled;

    /** Hybrid attack launch chance (0-100) rolled when {@link #attackLaunchChanceEnabled}. */
    public Integer attackLaunchChance;

    /**
     * Existing move id launched as the hybrid attack instead of this move's own
     * hit components. Null/blank = custom attack defined by this move's attack
     * fields (hit components, accuracy, on-hit effects, ...).
     */
    public String attackLaunchMoveId;

    /** Cannot be assigned directly; an ability must add this move to the character. */
    public boolean mustBeGranted = false;

    /** Legacy shikigami classification retained for existing stored content. */
    public Boolean shikigamiMove;

    /** Resolve the canonical move class, including the legacy shikigami flag. */
    @JsonIgnore
    public MoveType effectiveMoveType() {
        if (moveType != null && !moveType.isBlank()) {
            return MoveType.fromStoredValue(moveType);
        }
        return Boolean.TRUE.equals(shikigamiMove) ? MoveType.SHIKIGAMI : MoveType.SORCERER;
    }

    // -------------------------------------------------------------------------
    // Hit component sub-DTO
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HitComponentData {
        public int basePower;
        /** Damage-type MoveTag names only; range and modifiers stay on the move. */
        public List<String> tags;
        /** Nonnegative offset from the parent move's unleash/fire tick. */
        public int delayTicks = 0;
        public boolean requiresPreviousConnection = false;
        public boolean avoidable = true;
        /**
         * Per-hit base accuracy as a fraction [0.0, 1.0]. A value &lt;= 0 in legacy
         * data means "inherit the parent move's baseAccuracy" — preserved so old
         * saves round-trip without silently changing every component to 0%.
         */
        public double baseAccuracy = -1.0;
        /** On-hit status effects applied when this specific component connects. */
        public List<StatusEffectData> onHitEffects;

        public HitComponent toHitComponent() {
            return toHitComponent(null);
        }

        private HitComponent toHitComponent(List<StatusEffectData> inheritedOnHitEffects) {
            EnumSet<MoveTag> parsed = EnumSet.noneOf(MoveTag.class);
            if (tags != null) {
                for (String tag : tags) {
                    if (tag == null || tag.isBlank()) continue;
                    MoveTag parsedTag;
                    try {
                        parsedTag = MoveTag.valueOf(tag.trim().toUpperCase());
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalArgumentException(
                            "Unknown hit-component tag: " + tag, exception);
                    }
                    parsed.add(parsedTag);
                }
            }
            // baseAccuracy <= 0 (the legacy "unset" marker) → inherit the move's.
            double acc = baseAccuracy >= 0.0
                ? Math.max(0.0, Math.min(1.0, baseAccuracy))
                : HitComponent.INHERIT_MOVE_ACCURACY;
            List<StatusEffectData> effectData = onHitEffects == null || onHitEffects.isEmpty()
                ? inheritedOnHitEffects : onHitEffects;
            List<StatusEffect> effects = toStatusEffects(effectData);
            return new HitComponent(
                basePower, parsed, delayTicks, requiresPreviousConnection, avoidable,
                acc, effects);
        }

        public static HitComponentData fromHitComponent(HitComponent component) {
            HitComponentData data = new HitComponentData();
            data.basePower = component.getBasePower();
            data.tags = component.getTags().stream().map(MoveTag::name).toList();
            data.delayTicks = component.getDelayTicks();
            data.requiresPreviousConnection = component.requiresPreviousConnection();
            data.avoidable = component.isAvoidable();
            // Only persist per-hit accuracy when it is actually authored; leaving
            // baseAccuracy at its -1.0 "inherit" default keeps legacy saves clean.
            if (component.hasOwnAccuracy()) {
                data.baseAccuracy = component.getBaseAccuracy();
            }
            if (!component.getOnHitEffects().isEmpty()) {
                data.onHitEffects = component.getOnHitEffects().stream()
                    .map(MoveData::toEffectData).toList();
            }
            return data;
        }
    }

    // -------------------------------------------------------------------------
    // Status effect sub-DTO
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StatusEffectData {
        /** StatusEffectType enum name */
        public String type;
        public int    durationRounds = 1;
        public int    durationTicks  = 0;
        public double magnitude      = 1.0;
        public Double perTickRemovalChance;

        /**
         * Coded-action binding. When {@code codedAbilityKey} is set (and {@code type}
         * is blank), this row is a coded action dispatched to the matching
         * {@link com.jjktbf.model.character.coded.CodedAbilityRuntime} rather than
         * applied as a status. This is how a technique move's hardcoded effect is
         * stored on an editable effect row (self or on-hit) instead of on the move.
         */
        public String codedAbilityKey;

        /** Action interpreted by {@link #codedAbilityKey} when this effect fires. */
        public String codedAction;

        /** Action-specific target/mode, such as APPLY_TO_MOVE or CREATE_STACKS. */
        public String codedTarget;

        /** Number of stacks to create when the coded target supports stacking. */
        public Integer codedStackCount;

        /** Allow-listed integer parameters owned by the selected coded action. */
        public Map<String, Integer> codedParameters;

        /** Optional per-field CTM formulas or benchmark tables. */
        public Map<String, TechniqueMasteryProgressionData> masteryProgression;

        /**
         * Summon flavour. When set (and {@code type}/{@code codedAbilityKey} are
         * blank), this row summons the named shikigami definition when it fires
         * instead of applying a status or coded action.
         */
        public String summonCharacterId;

        @JsonIgnore
        public boolean isCoded() {
            return codedAbilityKey != null && !codedAbilityKey.isBlank();
        }

        @JsonIgnore
        public boolean isSummon() {
            return summonCharacterId != null && !summonCharacterId.isBlank();
        }
    }

    // -------------------------------------------------------------------------
    // Derive MoveCategory from tags
    // -------------------------------------------------------------------------

    /**
     * Resolve the MoveCategory from the stored tags list.
     * Tags that don't map to MoveCategory slots (ATTACK, UTILITY, DEFENSIVE) are
     * used for BF eligibility and filtering but don't change the power formula.
     *
     * <p>UTILITY may combine with DEFENSIVE or ATTACK to author a hybrid whose
     * on-fire effect rows live in the UTILITY section: a DEFENSIVE+UTILITY move
     * stays DEFENSIVE, and an ATTACK+UTILITY move keeps its damaging category so
     * its hit components and power formula are unaffected. UTILITY alone (or with
     * no ATTACK tag) resolves to UTILITY.</p>
     */
    public MoveCategory derivedCategory() {
        if (tags == null || tags.isEmpty()) return MoveCategory.UTILITY;

        boolean hasPhysical    = tags.contains("PHYSICAL");
        boolean hasInnate      = tags.contains("INNATE_TECHNIQUE");
        boolean hasNonInnate   = tags.contains("NON_INNATE_TECHNIQUE");
        boolean hasCe          = tags.contains("CURSED_ENERGY");
        boolean hasDefensive   = tags.contains("DEFENSIVE");
        boolean hasUtility     = tags.contains("UTILITY");
        boolean hasAttack      = tags.contains("ATTACK");

        if (hasDefensive) return MoveCategory.DEFENSIVE;
        if (hasUtility && !hasAttack) return MoveCategory.UTILITY;

        // Triple hybrids
        if (hasPhysical && hasInnate && hasNonInnate)
            return MoveCategory.PHYSICAL_INNATE_NON_INNATE_TECHNIQUE;

        // Double hybrids
        if (hasPhysical && hasInnate)    return MoveCategory.PHYSICAL_INNATE_TECHNIQUE;
        if (hasPhysical && hasNonInnate) return MoveCategory.PHYSICAL_NON_INNATE_TECHNIQUE;
        if (hasPhysical && hasCe)        return MoveCategory.PHYSICAL_CURSED_ENERGY;
        if (hasInnate   && hasNonInnate) return MoveCategory.INNATE_NON_INNATE_TECHNIQUE;

        // Pure
        if (hasInnate)    return MoveCategory.INNATE_TECHNIQUE;
        if (hasNonInnate) return MoveCategory.NON_INNATE_TECHNIQUE;
        if (hasPhysical)  return MoveCategory.PHYSICAL;

        // Raw cursed-energy attacks are a standalone damaging category.
        if (hasCe) return MoveCategory.CURSED_ENERGY;

        // Unknown tag combinations degrade to UTILITY.
        return MoveCategory.UTILITY;
    }

    /**
     * Resolve the {@link MovePool} from the stored tags list.
     *
     * <p>Authoritative pool derivation: Combat Arts iff the raw tag set contains
     * PHYSICAL, else Jujutsu Arts. This is read directly from {@code tags}
     * (rather than from {@link #derivedCategory()}) because category collapses
     * away the PHYSICAL tag for defensive/utility moves — e.g. a
     * {@code [PHYSICAL, DEFENSIVE]} block derives to category {@code DEFENSIVE}
     * but must still count as a Combat Art for slot purposes.
     */
    public MovePool derivedPool() {
        return MovePool.fromTags(tags);
    }

    // -------------------------------------------------------------------------
    // Defense type helpers — use these instead of raw string comparisons
    // -------------------------------------------------------------------------

    /** True if this move uses percentage-based block reduction (defenceType = BLOCK, style = PERCENTAGE). */
    public boolean isPercentageBlock() {
        return DefenseType.BLOCK.name().equals(defenseType)
            && BlockStyle.PERCENTAGE.name().equals(blockStyle);
    }

    /** True if this move uses flat damage subtraction (defenceType = BLOCK, style = FLAT). */
    public boolean isFlatBlock() {
        return DefenseType.BLOCK.name().equals(defenseType)
            && BlockStyle.FLAT.name().equals(blockStyle);
    }

    /** True if this move has any active block (defenceType = BLOCK). */
    public boolean isAnyBlock() {
        return DefenseType.BLOCK.name().equals(defenseType);
    }

    /** True if this move is a parry (defenceType = PARRY). */
    public boolean isParry() {
        return DefenseType.PARRY.name().equals(defenseType);
    }

    /** True if this move is a dodge (defenceType = DODGE). */
    public boolean isDodge() {
        return DefenseType.DODGE.name().equals(defenseType);
    }

    /** True if this move carries an active defense window (BLOCK, PARRY, or DODGE). */
    public boolean hasActiveDefense() {
        return isAnyBlock() || isParry() || isDodge();
    }

    /**
     * True iff the raw tags contain both DEFENSIVE and ATTACK — a hybrid whose
     * attack portion launches per {@link #attackLaunchMode}. The DEFENSIVE tag
     * wins: a hybrid derives to the DEFENSIVE category and plays on the
     * defensive timeline.
     */
    public boolean isDefenceAttackHybrid() {
        return tags != null && tags.contains("DEFENSIVE") && tags.contains("ATTACK");
    }

    /** Highest explicitly authored Never Miss tier. */
    @JsonIgnore
    public int getNeverMissTier() {
        return accuracyPriorityTier(AbilityEffectType.NEVER_MISS);
    }

    /** Highest authored Never Hit tier. Absence is returned as 0, not an authored tier. */
    @JsonIgnore
    public int getNeverHitTier() {
        return accuracyPriorityTier(AbilityEffectType.NEVER_HIT);
    }

    /** Replace one move-level accuracy priority while keeping it in canonical effect data. */
    @JsonIgnore
    public void setAccuracyPriorityTier(AbilityEffectType type, int tier) {
        if (type == null || !type.isAccuracyPriority()) {
            throw new IllegalArgumentException("An accuracy-priority effect type is required.");
        }
        if (tier < 0 || tier > 5) {
            throw new IllegalArgumentException(
                "Accuracy priority tier must be none or between 1 and 5.");
        }
        java.util.ArrayList<MoveEffectData> updated = effects == null
            ? new java.util.ArrayList<>()
            : effects.stream().filter(java.util.Objects::nonNull).map(MoveEffectData::copy)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        MoveEffectData retained = null;
        java.util.Iterator<MoveEffectData> iterator = updated.iterator();
        while (iterator.hasNext()) {
            MoveEffectData effect = iterator.next();
            if (!type.name().equalsIgnoreCase(effect.type)) continue;
            if (retained == null) retained = effect;
            else iterator.remove();
        }
        if (tier == 0) {
            if (retained != null) updated.remove(retained);
        } else if (retained == null) {
            retained = type.createDefaultMoveEffect();
            retained.trigger = MoveEffectTrigger.ACCURACY_CHECK.name();
            retained.condition = com.jjktbf.model.character.AbilityConditionData.always();
            retained.intValue = tier;
            updated.add(retained);
        } else {
            retained.type = type.name();
            retained.trigger = MoveEffectTrigger.ACCURACY_CHECK.name();
            retained.hitComponentIndex = null;
            retained.condition = com.jjktbf.model.character.AbilityConditionData.always();
            retained.moveTag = null;
            retained.activationChanceEnabled = null;
            retained.activationChance = null;
            retained.activationMasteryProgression = null;
            retained.intValue = tier;
        }
        effects = updated;
        AbilityData.ensureEffectIds(effects);
        if (type == AbilityEffectType.NEVER_MISS) neverMiss = false;
    }

    private int accuracyPriorityTier(AbilityEffectType expected) {
        int tier = 0;
        if (effects == null) return tier;
        for (MoveEffectData effect : effects) {
            if (effect != null && expected.name().equalsIgnoreCase(effect.type)
                && MoveEffectTrigger.ACCURACY_CHECK.name().equalsIgnoreCase(effect.trigger)) {
                tier = Math.max(tier, effect.intValue == null ? 0 : effect.intValue);
            }
        }
        return tier;
    }

    // -------------------------------------------------------------------------
    // Conversion: MoveData → Move (domain object)
    // -------------------------------------------------------------------------

    public Move toMove() {
        return toMoveResolved(null);
    }

    /**
     * Build the domain Move, resolving a hybrid's referenced attack move
     * through the lookup (e.g. {@code moveRepo::findById}). A null lookup — or
     * an unresolvable id — leaves the reference unresolved; the launch then
     * no-ops at runtime rather than failing the build. Named distinctly from
     * {@link #toMove()} so {@code data::toMove} method references stay exact.
     */
    public Move toMoveResolved(java.util.function.Function<String, MoveData> attackLaunchMoves) {
        return toMoveResolved(attackLaunchMoves, new java.util.HashSet<>());
    }

    private Move toMoveResolved(
        java.util.function.Function<String, MoveData> attackLaunchMoves,
        Set<String> seen
    ) {
        MoveCategory cat = derivedCategory();
        Set<MoveTag> rawTags = parsedTags();
        validateProgressionEligibility(rawTags);

        Move.Builder b = new Move.Builder(id)
            .name(name)
            .moveType(effectiveMoveType())
            .description(description != null ? description : "")
            .category(cat)
            .pool(derivedPool())
            .basePower(basePower)
            .baseAccuracy(baseAccuracy)
            .neverMiss(neverMiss)
            .guardBreak(guardBreak)
            .heavy(heavy)
            .potency(potency)
            .apCost(apCost)
            .unleashPoint(unleashPoint)
            .baseCeCost(baseCeCost)
            .hasCeCost(hasCeCost != null ? hasCeCost : baseCeCost > 0)
            .minCeCost(minCeCost)
            .maxCeCost(maxCeCost)
            .defenseType(resolveDefenseType())
            .blockStyle(resolveBlockStyle())
            .blockDuration(blockDuration)
            .blockAffectedTags(blockAffectedTags)
            .blockDamageReduction(blockDamageReduction)
            .blockFlatReduction(blockFlatReduction)
            .dodgeChance(dodgeChance)
            .dodgeScope(dodgeScope)
            .parryStaggerTicks(parryStaggerTicks)
            .defenseTiming(DefenseTiming.fromName(defenseTiming))
            .defenseUses(defenseUses)
            .requiredTechniqueId(requiredTechniqueId)
            .requiredCursedToolId(requiredCursedToolId)
            .freeMove(isFreeMove)
            .mustBeGranted(mustBeGranted)
            .moveCap(moveCap)
            .summonCharacterId(summonCharacterId)
            .aoeType(AoeType.fromName(aoeType))
            .aoeTargetCount(aoeTargetCount >= 2 ? aoeTargetCount : 2)
            .defenseTargeting(DefenseTargeting.fromName(defenseTargeting))
            .defenseTargetCount(defenseTargetCount >= 2 ? defenseTargetCount : 2);

        // Defensive+Attack hybrid launch settings. Cleared for non-hybrids so
        // stale fields can never take effect on an ordinary move.
        boolean hybrid = isDefenceAttackHybrid();
        String launchMoveId = hybrid && attackLaunchMoveId != null && !attackLaunchMoveId.isBlank()
            ? attackLaunchMoveId.trim() : null;
        b.attackLaunchMode(hybrid ? AttackLaunchMode.fromName(attackLaunchMode) : null)
            .attackLaunchCondition(
                hybrid && attackLaunchCondition != null ? attackLaunchCondition.copy() : null)
            .attackLaunchChance(
                hybrid && Boolean.TRUE.equals(attackLaunchChanceEnabled),
                hybrid && attackLaunchChance != null
                    ? Math.max(0, Math.min(100, attackLaunchChance)) : 100)
            .attackLaunchMoveId(launchMoveId);
        // Resolve the referenced attack move eagerly, guarding against cycles.
        if (launchMoveId != null && attackLaunchMoves != null && seen.add(id)) {
            MoveData referenced = attackLaunchMoves.apply(launchMoveId);
            if (referenced != null) {
                b.attackLaunchMove(referenced.toMoveResolved(attackLaunchMoves, seen));
            }
        }

        if (effects != null) {
            java.util.ArrayList<MoveEffectData> copiedEffects = effects.stream()
                .filter(java.util.Objects::nonNull)
                .map(MoveEffectData::copy)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
            AbilityData.ensureEffectIds(copiedEffects);
            b.effects(copiedEffects);
        }

        if (!rawTags.isEmpty()) b.tags(rawTags);
        if (hitComponents != null && !hitComponents.isEmpty()) {
            // Legacy migration: on-hit effects used to live on the move itself.
            // Push any such legacy list onto each component that defines none of
            // its own, so saved multi-hit moves keep applying their effects per
            // connecting hit. Conversion must not mutate this repository DTO:
            // callers validate and build the same MoveData more than once.
            b.hitComponents(hitComponents.stream()
                .filter(java.util.Objects::nonNull)
                .map(component -> component.toHitComponent(onHitEffects))
                .toList());
        } else if (onHitEffects != null && !onHitEffects.isEmpty()) {
            // Legacy single-component data (no explicit hitComponents): seed the
            // synthesized fallback component via the builder so the move keeps
            // its on-hit behaviour.
            b.onHitEffects(toStatusEffects(onHitEffects));
        }
        if (prerequisites != null)  b.prerequisites(prerequisites);
        // On-hit effects now live per HitComponent — no move-level mapping here.
        if (selfEffects   != null)  b.selfEffects(toStatusEffects(selfEffects));
        if (onBlockEffects != null) b.onBlockEffects(toStatusEffects(onBlockEffects));
        if (onParryEffects != null) b.onParryEffects(toStatusEffects(onParryEffects));
        if (onDodgeEffects != null) b.onDodgeEffects(toStatusEffects(onDodgeEffects));

        return b.build();
    }

    /**
     * Resolve the {@link DefenseType}, tolerating the legacy
     * {@code PERCENTAGE_BLOCK} / {@code FLAT_BLOCK} enum names from older saves:
     * both map to {@link DefenseType#BLOCK} (with the matching {@link BlockStyle}
     * inferred for {@link #blockStyle} when it is blank).
     */
    private DefenseType resolveDefenseType() {
        String name = defenseType != null ? defenseType.trim().toUpperCase() : "NONE";
        return switch (name) {
            case "PERCENTAGE_BLOCK", "FLAT_BLOCK" -> DefenseType.BLOCK;
            default -> {
                try { yield DefenseType.valueOf(name); }
                catch (IllegalArgumentException e) { yield DefenseType.NONE; }
            }
        };
    }

    /**
     * Resolve the {@link BlockStyle} for a BLOCK move, inferring it from a legacy
     * {@code PERCENTAGE_BLOCK} / {@code FLAT_BLOCK} defence type when {@link #blockStyle}
     * is blank. Defaults to {@link BlockStyle#PERCENTAGE}.
     */
    private BlockStyle resolveBlockStyle() {
        String legacy = defenseType != null ? defenseType.trim().toUpperCase() : "";
        if ("FLAT_BLOCK".equals(legacy)) return BlockStyle.FLAT;
        if ("PERCENTAGE_BLOCK".equals(legacy)) return BlockStyle.PERCENTAGE;
        String name = blockStyle != null ? blockStyle.trim().toUpperCase() : "PERCENTAGE";
        return switch (name) {
            case "FLAT" -> BlockStyle.FLAT;
            default    -> BlockStyle.PERCENTAGE;
        };
    }

    private Set<MoveTag> parsedTags() {
        EnumSet<MoveTag> parsed = EnumSet.noneOf(MoveTag.class);
        if (tags == null) return parsed;
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) continue;
            try {
                parsed.add(MoveTag.valueOf(tag.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // Unknown future tags do not prevent older clients from loading the move.
            }
        }
        return parsed;
    }

    /** Every weapon-type tag authored on this move definition. */
    public Set<MoveTag> weaponTags() {
        return MoveTag.weaponTagsIn(parsedTags());
    }

    private void validateProgressionEligibility(Set<MoveTag> rawTags) {
        if (rawTags.contains(MoveTag.INNATE_TECHNIQUE)) return;
        List<StatusEffectData> legacyEffects = new java.util.ArrayList<>();
        if (onHitEffects != null) legacyEffects.addAll(onHitEffects);
        if (selfEffects != null) legacyEffects.addAll(selfEffects);
        if (onBlockEffects != null) legacyEffects.addAll(onBlockEffects);
        if (onParryEffects != null) legacyEffects.addAll(onParryEffects);
        if (onDodgeEffects != null) legacyEffects.addAll(onDodgeEffects);
        if (hitComponents != null) {
            for (HitComponentData component : hitComponents) {
                if (component != null && component.onHitEffects != null) {
                    legacyEffects.addAll(component.onHitEffects);
                }
            }
        }
        if (this.effects != null) {
            boolean hasUnifiedProgression = this.effects.stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(MoveData::hasMasteryProgression);
            if (hasUnifiedProgression) {
                throw new IllegalArgumentException(
                    "Only INNATE_TECHNIQUE moves may use mastery progression.");
            }
        }
        boolean hasProgression = legacyEffects.stream()
            .filter(java.util.Objects::nonNull)
            .anyMatch(effect -> effect.masteryProgression != null
                && !effect.masteryProgression.isEmpty());
        if (hasProgression) {
            throw new IllegalArgumentException(
                "Only INNATE_TECHNIQUE moves may use mastery progression.");
        }
    }

    private static boolean hasMasteryProgression(MoveEffectData effect) {
        if (effect.masteryProgression != null && !effect.masteryProgression.isEmpty()) return true;
        if (effect.activationMasteryProgression != null
            && !effect.activationMasteryProgression.isEmpty()) return true;
        return hasMasteryProgression(effect.condition);
    }

    private static boolean hasMasteryProgression(
        com.jjktbf.model.character.AbilityConditionData condition
    ) {
        if (condition == null) return false;
        if (condition.masteryProgression != null && !condition.masteryProgression.isEmpty()) {
            return true;
        }
        return condition.children != null
            && condition.children.stream().anyMatch(MoveData::hasMasteryProgression);
    }

    private static List<StatusEffect> toStatusEffects(List<StatusEffectData> dtos) {
        if (dtos == null) return List.of();
        java.util.ArrayList<StatusEffect> effects = new java.util.ArrayList<>();
        for (StatusEffectData d : dtos) {
            if (d == null) continue;
            // Summon row — enqueues a shikigami onto the wielder's team when it fires.
            if (d.isSummon()) {
                if (d.isCoded()) {
                    throw new IllegalArgumentException(
                        "A summon effect row cannot also be a coded action");
                }
                StatusEffect effect = new StatusEffect(d.summonCharacterId);
                effects.add(effect);
                continue;
            }
            // Coded action row — dispatched to a compiled runtime, not applied as a status.
            if (d.isCoded()) {
                if (!com.jjktbf.model.character.coded.CodedAbilityRegistry.supportsEffect(
                    d.codedAbilityKey, d.codedAction, d.codedTarget, d.codedStackCount)) {
                    throw new IllegalArgumentException("Invalid coded effect "
                        + d.codedAbilityKey + "/" + d.codedAction);
                }
                String parameterError = com.jjktbf.model.character.coded.CodedAbilityRegistry
                    .effectParameterValidationError(
                        d.codedAbilityKey, d.codedAction, d.codedTarget, d.codedParameters);
                if (parameterError != null) throw new IllegalArgumentException(parameterError);
                Set<String> allowed = new java.util.LinkedHashSet<>();
                if (d.codedStackCount != null) {
                    allowed.add(TechniqueMasteryProgressions.CODED_STACK_COUNT);
                }
                if (d.codedParameters != null) allowed.addAll(d.codedParameters.keySet());
                validateEffectProgression(d, allowed);
                StatusEffect effect = StatusEffect.coded(
                    d.codedAbilityKey, d.codedAction, d.codedTarget, d.codedStackCount,
                    d.codedParameters, d.masteryProgression);
                validateResolvedEffect(effect);
                effects.add(effect);
                continue;
            }
            if (d.type == null || d.type.isBlank()) continue;
            StatusEffectType type;
            try {
                type = StatusEffectType.fromName(d.type, d.magnitude);
            } catch (IllegalArgumentException ignored) {
                // Removed one-off and unknown statuses do not invalidate the move.
                continue;
            }
            Set<String> allowed = new java.util.LinkedHashSet<>();
            if (type.requiresTickDuration()) {
                allowed.add(TechniqueMasteryProgressions.DURATION_TICKS);
            } else {
                allowed.add(TechniqueMasteryProgressions.DURATION_ROUNDS);
                if (!type.requiresRoundDuration()) {
                    allowed.add(TechniqueMasteryProgressions.DURATION_TICKS);
                }
            }
            if (type.usesMagnitude()) allowed.add(TechniqueMasteryProgressions.MAGNITUDE);
            allowed.add(TechniqueMasteryProgressions.PER_TICK_REMOVAL_CHANCE);
            validateEffectProgression(d, allowed);
            double perTickRemovalChance = d.perTickRemovalChance == null
                ? type.defaultPerTickRemovalChance() : d.perTickRemovalChance;
            StatusEffect effect = new StatusEffect(type, d.durationRounds, d.durationTicks,
                StatusEffectType.normalizeStoredMagnitude(d.type, d.magnitude),
                perTickRemovalChance, d.masteryProgression);
            validateResolvedEffect(effect);
            effects.add(effect);
        }
        return effects;
    }

    private static void validateEffectProgression(StatusEffectData data, Set<String> allowed) {
        String error = TechniqueMasteryProgressions.validationError(
            data.masteryProgression, allowed);
        if (error != null) throw new IllegalArgumentException(error);
    }

    private static void validateResolvedEffect(StatusEffect effect) {
        if (effect.getMasteryProgression().isEmpty()) return;
        for (int mastery = 0; mastery <= 300; mastery++) {
            try {
                StatusEffect resolved =
                    com.jjktbf.model.progression.TechniqueMasteryResolver.resolve(effect, mastery);
                if (resolved.isCoded()) {
                    String parameterError = com.jjktbf.model.character.coded.CodedAbilityRegistry
                        .effectParameterValidationError(
                            resolved.getCodedAbilityKey(), resolved.getCodedAction(),
                            resolved.getCodedTarget(), resolved.getCodedParameters());
                    if (parameterError != null) {
                        throw new IllegalArgumentException(parameterError);
                    }
                    if (resolved.getCodedStackCount() != null
                        && !com.jjktbf.model.character.coded.CodedAbilityRegistry.supportsEffect(
                            resolved.getCodedAbilityKey(), resolved.getCodedAction(),
                            resolved.getCodedTarget(), resolved.getCodedStackCount())) {
                        throw new IllegalArgumentException("Invalid resolved coded stack count");
                    }
                }
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(
                    "Invalid move-effect mastery progression at CTM " + mastery + ": "
                        + exception.getMessage(), exception);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Conversion: Move (domain object) → MoveData
    // -------------------------------------------------------------------------

    /**
     * Reconstruct a MoveData from a {@link Move}.
     *
     * <p>Raw tags loaded from a {@code MoveData} are retained by {@link Move}, so
     * defensive and utility moves keep their underlying physical, CE, or technique
     * nature when saved back to data.
     */
    public static MoveData fromMove(Move move) {
        MoveData d = new MoveData();
        d.id                  = move.getId();
        d.name                = move.getName();
        d.description         = move.getDescription();
        d.moveType            = move.getMoveType().name();

        List<String> tagList = move.getTags().stream()
            .map(MoveTag::name)
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        MoveCategory cat = move.getCategory();
        if (tagList.isEmpty() && cat != null) {
            cat.getTags().forEach(t -> tagList.add(t.name()));
        }
        // Add ATTACK if it's a damaging move (non-defensive, non-utility with basePower > 0)
        if (move.getBasePower() > 0
                && cat != MoveCategory.DEFENSIVE
                && cat != MoveCategory.UTILITY
                && !tagList.contains(MoveTag.ATTACK.name())) {
            tagList.add("ATTACK");
        }
        d.tags = tagList;

        d.basePower           = move.getBasePower();
        // Zero-power legacy attacks expose a synthetic fallback component at
        // runtime. Keep those in the legacy shape instead of serializing an
        // explicit component that new authoring validation correctly rejects.
        if (move.getBasePower() > 0) {
            d.hitComponents = move.getHitComponents().stream()
                .map(HitComponentData::fromHitComponent)
                .toList();
        } else if (!move.getHitComponents().isEmpty()
            && !move.getHitComponents().get(0).getOnHitEffects().isEmpty()) {
            d.onHitEffects = move.getHitComponents().get(0).getOnHitEffects().stream()
                .map(MoveData::toEffectData)
                .toList();
        }
        d.baseAccuracy        = move.getBaseAccuracy();
        d.neverMiss           = move.hasLegacyNeverMiss();
        d.guardBreak          = move.isGuardBreak();
        d.heavy               = move.isHeavy();
        d.potency             = move.getPotency();
        d.apCost              = move.getApCost();
        d.unleashPoint        = move.getUnleashPoint();
        d.baseCeCost          = move.getBaseCeCost();
        d.hasCeCost           = move.hasCeCost();
        d.minCeCost           = move.getMinCeCost();
        d.maxCeCost           = move.getMaxCeCost();
        d.defenseType           = move.getDefenseType().name();
        d.blockStyle            = move.getBlockStyle() != null ? move.getBlockStyle().name() : "PERCENTAGE";
        d.blockDuration         = move.getBlockDuration();
        d.blockAffectedTags     = move.getBlockAffectedTags() != null
                                    ? new java.util.ArrayList<>(move.getBlockAffectedTags()) : null;
        d.blockDamageReduction  = move.getBlockDamageReduction();
        d.blockFlatReduction    = move.getBlockFlatReduction();
        d.dodgeChance           = move.getDodgeChance();
        d.dodgeScope            = move.getDodgeScope();
        d.parryStaggerTicks     = move.getParryStaggerTicks();
        d.defenseTiming         = move.getDefenseTiming().name();
        d.defenseUses           = move.getDefenseUses();
        d.requiredTechniqueId = move.getRequiredTechniqueId();
        d.requiredCursedToolId = move.getRequiredCursedToolId();
        d.isFreeMove          = move.isFreeMove();
        d.mustBeGranted       = move.mustBeGranted();
        d.moveCap             = move.getMoveCap();
        d.summonCharacterId   = move.getSummonCharacterId();
        if (move.getAoeType() != null) {
            d.aoeType         = move.getAoeType().name();
            d.aoeTargetCount  = move.getAoeTargetCount();
        }
        d.defenseTargeting    = move.getDefenseTargeting().name();
        d.defenseTargetCount  = move.getDefenseTargetCount();
        d.attackLaunchMode    = move.getAttackLaunchMode() != null
                                    ? move.getAttackLaunchMode().name() : null;
        d.attackLaunchCondition = move.getAttackLaunchCondition() != null
                                    ? move.getAttackLaunchCondition().copy() : null;
        if (move.isAttackLaunchChanceEnabled()) {
            d.attackLaunchChanceEnabled = true;
            d.attackLaunchChance       = move.getAttackLaunchChance();
        }
        d.attackLaunchMoveId  = move.getAttackLaunchMoveId();
        d.prerequisites       = move.getPrerequisites().isEmpty() ? null
                                    : new java.util.LinkedHashMap<>(move.getPrerequisites());

        if (move.usesUnifiedEffects()) {
            d.effects = move.getEffects().stream().map(MoveEffectData::copy).toList();
            d.summonCharacterId = null;
            d.onHitEffects = null;
            d.selfEffects = null;
            d.onBlockEffects = null;
            d.onParryEffects = null;
            d.onDodgeEffects = null;
            if (d.hitComponents != null) {
                d.hitComponents.forEach(component -> component.onHitEffects = null);
            }
        } else {
            // Positive-power on-hit effects are serialized per HitComponent. A
            // zero-power attack keeps its synthetic component in the legacy
            // move-level shape populated above.
            if (!move.getSelfEffects().isEmpty()) {
                d.selfEffects = move.getSelfEffects().stream().map(MoveData::toEffectData).toList();
            }
            if (!move.getOnBlockEffects().isEmpty()) {
                d.onBlockEffects = move.getOnBlockEffects().stream().map(MoveData::toEffectData).toList();
            }
            if (!move.getOnParryEffects().isEmpty()) {
                d.onParryEffects = move.getOnParryEffects().stream().map(MoveData::toEffectData).toList();
            }
            if (!move.getOnDodgeEffects().isEmpty()) {
                d.onDodgeEffects = move.getOnDodgeEffects().stream().map(MoveData::toEffectData).toList();
            }
        }
        return d;
    }

    /**
     * Convert legacy status/coded/summon attachment fields into the canonical
     * shared effect list. This mutates an editor draft, never repository data.
     */
    @JsonIgnore
    public boolean migrateLegacyEffects() {
        boolean legacyStun = Boolean.TRUE.equals(stun)
            || tags != null && tags.stream().anyMatch(MoveData::isLegacyStunTag);
        boolean changed = stun != null;
        if (tags != null && tags.stream().anyMatch(MoveData::isLegacyStunTag)) {
            tags = tags.stream().filter(tag -> !isLegacyStunTag(tag))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
            changed = true;
        }
        stun = null;
        java.util.ArrayList<MoveEffectData> migrated = effects == null
            ? new java.util.ArrayList<>()
            : effects.stream().filter(java.util.Objects::nonNull).map(MoveEffectData::copy)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        if (effects != null) {
            if (legacyStun && migrated.stream().noneMatch(MoveData::isStunEffect)) {
                migrated.add(stunCurrentActionEffect());
                changed = true;
            }
            effects = migrated;
            AbilityData.ensureEffectIds(effects);
            return changed;
        }
        if (summonCharacterId != null && !summonCharacterId.isBlank()) {
            MoveEffectData summon = AbilityEffectType.SUMMON_CHARACTER.createDefaultMoveEffect();
            summon.trigger = MoveEffectTrigger.ON_FIRE.name();
            summon.characterId = summonCharacterId;
            migrated.add(summon);
        }
        migrateEffects(selfEffects, MoveEffectTrigger.ON_FIRE, null,
            AbilityEffectTarget.SELF, migrated);
        migrateEffects(onBlockEffects, MoveEffectTrigger.ON_BLOCK, null,
            AbilityEffectTarget.SELF, migrated);
        migrateEffects(onParryEffects, MoveEffectTrigger.ON_PARRY, null,
            AbilityEffectTarget.SELF, migrated);
        migrateEffects(onDodgeEffects, MoveEffectTrigger.ON_DODGE, null,
            AbilityEffectTarget.SELF, migrated);
        if (hitComponents == null || hitComponents.isEmpty()) {
            migrateEffects(onHitEffects, MoveEffectTrigger.ON_HIT, null,
                AbilityEffectTarget.ENEMY, migrated);
        } else {
            for (int index = 0; index < hitComponents.size(); index++) {
                HitComponentData component = hitComponents.get(index);
                if (component != null) {
                    List<StatusEffectData> source = component.onHitEffects == null
                        || component.onHitEffects.isEmpty()
                            ? onHitEffects : component.onHitEffects;
                    migrateEffects(source, MoveEffectTrigger.ON_HIT, index,
                        AbilityEffectTarget.ENEMY, migrated);
                    component.onHitEffects = null;
                }
            }
        }
        effects = migrated;
        if (legacyStun) migrated.add(stunCurrentActionEffect());
        AbilityData.ensureEffectIds(effects);
        summonCharacterId = null;
        onHitEffects = null;
        selfEffects = null;
        onBlockEffects = null;
        onParryEffects = null;
        onDodgeEffects = null;
        return changed || !migrated.isEmpty();
    }

    private static boolean isLegacyStunTag(String tag) {
        return tag != null && "STUN".equalsIgnoreCase(tag.trim());
    }

    private static boolean isStunEffect(MoveEffectData effect) {
        return effect != null
            && AbilityEffectType.STUN_CURRENT_ACTION.name().equalsIgnoreCase(effect.type);
    }

    private static MoveEffectData stunCurrentActionEffect() {
        MoveEffectData effect = AbilityEffectType.STUN_CURRENT_ACTION.createDefaultMoveEffect();
        effect.trigger = MoveEffectTrigger.ON_HIT.name();
        effect.target = AbilityEffectTarget.ENEMY.name();
        return effect;
    }

    /** Upgrade the legacy attack boolean when an author opens the move editor. */
    @JsonIgnore
    public boolean migrateLegacyNeverMissTier() {
        boolean attackingMove = tags != null && tags.stream()
            .filter(java.util.Objects::nonNull)
            .anyMatch(MoveTag.ATTACK.name()::equalsIgnoreCase);
        if (!neverMiss || !attackingMove) return false;
        setAccuracyPriorityTier(AbilityEffectType.NEVER_MISS, Math.max(1, getNeverMissTier()));
        return true;
    }

    private static void migrateEffects(
        List<StatusEffectData> legacy,
        MoveEffectTrigger trigger,
        Integer hitComponentIndex,
        AbilityEffectTarget target,
        List<MoveEffectData> destination
    ) {
        if (legacy == null) return;
        for (StatusEffectData source : legacy) {
            if (source == null) continue;
            MoveEffectData effect;
            if (source.isSummon()) {
                effect = AbilityEffectType.SUMMON_CHARACTER.createDefaultMoveEffect();
                effect.characterId = source.summonCharacterId;
            } else if (source.isCoded()) {
                effect = AbilityEffectType.CODED_MOVE_ACTION.createDefaultMoveEffect();
                effect.codedAbilityKey = source.codedAbilityKey;
                effect.codedAction = source.codedAction;
                effect.codedTarget = source.codedTarget;
                effect.codedStackCount = source.codedStackCount;
                effect.codedParameters = TechniqueMasteryProgressions.copyIntegers(
                    source.codedParameters);
                effect.masteryProgression = TechniqueMasteryProgressions.copy(
                    source.masteryProgression);
                effect.target = target.name();
                if (com.jjktbf.model.character.coded.RatioAbility.CREATE_STACKS
                    .equalsIgnoreCase(source.codedTarget)) {
                    effect.target = AbilityEffectTarget.ENEMY.name();
                }
            } else if (source.type != null && !source.type.isBlank()) {
                effect = AbilityEffectType.APPLY_STATUS.createDefaultMoveEffect();
                effect.stringValue = source.type;
                effect.target = target.name();
                effect.durationRounds = source.durationRounds;
                effect.durationTicks = source.durationTicks;
                effect.magnitude = source.magnitude;
                effect.perTickRemovalChance = source.perTickRemovalChance;
                effect.masteryProgression = TechniqueMasteryProgressions.copy(
                    source.masteryProgression);
            } else {
                continue;
            }
            if (com.jjktbf.model.character.coded.CodedAbilityRegistry
                .executesBeforeHit(effect)) {
                effect.trigger = MoveEffectTrigger.ON_HIT.name();
                effect.hitComponentIndex = trigger == MoveEffectTrigger.ON_HIT
                    ? hitComponentIndex : null;
                effect.target = AbilityEffectTarget.ENEMY.name();
            } else {
                effect.trigger = trigger.name();
                effect.hitComponentIndex = hitComponentIndex;
            }
            destination.add(effect);
            migrateCursedSpeechOutcome(effect, destination);
        }
    }

    /** One-time data migration from the former all-in-one command action. */
    private static void migrateCursedSpeechOutcome(
        MoveEffectData command,
        List<MoveEffectData> destination
    ) {
        if (!AbilityEffectType.CODED_MOVE_ACTION.name().equals(command.type)
            || !CursedSpeechAbility.KEY.equalsIgnoreCase(command.codedAbilityKey)
            || !CursedSpeechAbility.COMMAND.equalsIgnoreCase(command.codedAction)) {
            return;
        }
        MoveEffectData outcome = switch (String.valueOf(command.codedTarget).toUpperCase()) {
            case CursedSpeechAbility.DONT_MOVE -> statusOutcome(StatusEffectType.STAGGER, 0, 6);
            case CursedSpeechAbility.BLAST_AWAY -> statusOutcome(StatusEffectType.STAGGER, 0, 3);
            case CursedSpeechAbility.SLEEP -> statusOutcome(StatusEffectType.SLEEP, 1, 0);
            case CursedSpeechAbility.PLUMMET -> statusOutcome(StatusEffectType.STAGGER, 0, 4);
            case CursedSpeechAbility.RETURN ->
                AbilityEffectType.DESUMMON_TARGET_SHIKIGAMI.createDefaultMoveEffect();
            case CursedSpeechAbility.DIE ->
                AbilityEffectType.INSTANT_KILL.createDefaultMoveEffect();
            default -> null;
        };
        if (outcome == null) return;
        outcome.trigger = command.trigger;
        outcome.hitComponentIndex = command.hitComponentIndex;
        outcome.target = AbilityEffectTarget.ENEMY.name();
        destination.add(outcome);
    }

    private static MoveEffectData statusOutcome(
        StatusEffectType type,
        int rounds,
        int ticks
    ) {
        MoveEffectData effect = AbilityEffectType.APPLY_STATUS.createDefaultMoveEffect();
        effect.stringValue = type.name();
        effect.target = AbilityEffectTarget.ENEMY.name();
        effect.durationRounds = rounds;
        effect.durationTicks = ticks;
        effect.magnitude = 0.0;
        effect.perTickRemovalChance = type.defaultPerTickRemovalChance();
        return effect;
    }

    /**
     * Serialize a single {@link StatusEffect} back to its DTO. A coded-action row
     * is emitted with its coded fields and a null {@code type}; a status row is
     * emitted with its {@code StatusEffectType} and the coded fields left blank.
     */
    private static StatusEffectData toEffectData(StatusEffect e) {
        StatusEffectData sd = new StatusEffectData();
        if (e.isSummon()) {
            sd.summonCharacterId = e.getSummonCharacterId();
            return sd;
        }
        if (e.isCoded()) {
            sd.codedAbilityKey = e.getCodedAbilityKey();
            sd.codedAction     = e.getCodedAction();
            sd.codedTarget     = e.getCodedTarget();
            sd.codedStackCount = e.getCodedStackCount();
            sd.codedParameters = TechniqueMasteryProgressions.copyIntegers(e.getCodedParameters());
            sd.masteryProgression = TechniqueMasteryProgressions.copy(e.getMasteryProgression());
            return sd;
        }
        sd.type           = e.getType().name();
        sd.durationRounds = e.getDurationRounds();
        sd.durationTicks  = e.getDurationTicks();
        sd.magnitude      = e.getMagnitude();
        sd.perTickRemovalChance = e.getPerTickRemovalChance();
        sd.masteryProgression = TechniqueMasteryProgressions.copy(e.getMasteryProgression());
        return sd;
    }
}
