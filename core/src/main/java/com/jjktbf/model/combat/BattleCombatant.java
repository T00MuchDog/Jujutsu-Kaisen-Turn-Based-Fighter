package com.jjktbf.model.combat;

import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityApplicator;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.BattleStatKey;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.CombatStats;
import com.jjktbf.model.character.coded.CodedAbilities;
import com.jjktbf.model.character.coded.CodedAbilityRegistry;
// Explicit import to avoid ambiguity with java.lang.Character
import com.jjktbf.model.character.Character;
import com.jjktbf.model.move.StatusEffect;
import com.jjktbf.model.move.StatusEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * Mutable battle-time wrapper around an immutable Character.
 *
 * Tracks all state that changes during a fight:
 *  - Current HP and CE pool (derived from ability-modified stats)
 *  - Active status effects
 *  - Black Flash State (BFS) and escalation counter
 *  - Defense buff from active defensive moves
 *  - The current round's planned action queue (timeline)
 *  - Ability flags (non-stat effects from passive abilities)
 *
 * On construction, AbilityApplicator runs over the character's abilities
 * and produces an ability-modified copy of CharacterStats. All combat
 * calculations use those modified stats rather than the raw base stats.
 *
 * The underlying Character is never mutated.
 */
public class BattleCombatant {

    private final Character character;

    // --- Battle-instance identity (assigned by BattleState at creation) ---
    /**
     * Stable battle-scoped instance id. Distinguishes duplicate live instances
     * of the same character definition (e.g. two summoned Divine Dogs). Assigned
     * by {@link BattleState} when the combatant joins the battle; null only
     * briefly for legacy construction paths that pre-date the team model.
     */
    private CombatantId instanceId;
    /** The team this combatant belongs to. */
    private BattleTeamId teamId;
    /** Stable roster (initial fighters) / creation (summons) order within the team. */
    private int rosterOrder;
    /** Runtime role: FIGHTER (defeat contributes to team loss) or SUMMON. */
    private CombatantRole role;
    /** For summons: the combatant that summoned this one. Null for fighters. */
    private CombatantId summonerId;
    /** Lifecycle within the battle. */
    private CombatantLifecycle lifecycle = CombatantLifecycle.ACTIVE;

    /**
     * Ability-modified stats. Used for all combat calculations instead of
     * character.getBaseStats(). Produced by AbilityApplicator at construction.
     */
    private final CharacterStats effectiveStats;

    /**
     * Ability-derived combat stats (computed from effectiveStats).
     * Used for HP, AP bar, slot counts, etc.
     */
    private final CombatStats effectiveCombatStats;

    /**
     * Non-stat ability effects (CE cost overrides, accuracy bonus, etc.).
     * Applied during combat resolution by CombatResolver / DamageCalculator.
     */
    private final AbilityApplicator.AbilityFlags abilityFlags;
    private final List<Ability> abilities;
    private final CodedAbilities codedAbilities;

    /** Conditional and timed effects added by active ability activation. */
    private final List<RuntimeAbilityEffect> runtimeAbilityEffects = new ArrayList<>();
    private final Map<String, Boolean> abilityConditionState = new HashMap<>();
    private final Map<String, List<AbilityTrigger>> abilityTriggerHistory = new HashMap<>();
    private boolean abilityFightStartProcessed;
    private int lastAbilityRoundStartRound;

    // --- Mutable battle state ---
    private int currentHp;
    private int currentCe;
    private int lastAbilityCostRound;
    private int poolClampDeferrals;

    private final List<StatusEffect> activeEffects;

    /**
     * Status effects that expired during the most recent {@link #tickStatusEffects()}.
     * Read (and cleared) by {@link #drainExpiredStatusEffects()} so the combat
     * engine can log each expiry once. Kept separate from {@link #activeEffects}
     * so the no-arg tick signature is unchanged.
     */
    private final List<StatusEffect> expiredThisTick = new ArrayList<>();

    // --- Black Flash State ---
    private boolean inBlackFlashState;
    /**
     * Number of consecutive Black Flashes landed while IN BFS.
     * Drives the escalating BF chance:
     *   0 = just entered BFS  → next roll is BFS_BF_CHANCES[0] = 10%
     *   1 → 20%, 2 → 35%, 3+ → 50%
     */
    private int consecutiveBfsHits;

    /** Round number on which BFS expires (inclusive). -1 if not in BFS. */
    private int bfsExpiresAfterRound;

    // --- Round's action timeline ---
    private Timeline timeline;

    // --- Round's two-board battle plan (offensive + defensive) ---
    private BattlePlan plan;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public BattleCombatant(Character character) {
        this(character, character.getAbilities());
    }

    /**
     * Full constructor — applies the given list of abilities before computing
     * effective stats. Use this when the character's AbilityRepository has
     * been loaded.
     */
    public BattleCombatant(Character character, List<Ability> abilities) {
        this.character = character;
        this.abilities = abilities == null ? List.of() : List.copyOf(abilities);
        this.codedAbilities = CodedAbilityRegistry.create(this, this.abilities);

        // Apply passive ability effects to produce modified stats + flags
        AbilityApplicator.ApplicationResult result =
            AbilityApplicator.apply(character.getBaseStats(), abilities);

        this.effectiveStats      = result.modifiedStats;
        this.effectiveCombatStats= new CombatStats(
            effectiveStats, result.flags.jujutsuArtSlots);
        this.abilityFlags        = result.flags;

        // HP and CE derived from effective (ability-modified) stats
        this.currentHp               = effectiveCombatStats.getMaxHp();
        this.currentCe               = effectiveCombatStats.getMaxCursedEnergy();
        this.lastAbilityCostRound    = 0;
        this.poolClampDeferrals      = 0;
        this.activeEffects           = new ArrayList<>();
        this.inBlackFlashState       = false;
        this.consecutiveBfsHits   = 0;
        this.bfsExpiresAfterRound = -1;
        this.timeline             = null;
        this.abilityFightStartProcessed = false;
        this.lastAbilityRoundStartRound = 0;
    }

    // -------------------------------------------------------------------------
    // HP management
    // -------------------------------------------------------------------------

    public void applyDamage(int damage) {
        receiveDamage(damage);
    }

    /**
     * Apply damage without a battle activation context. Conditional coded fatal
     * protection is available through the callback overload used by CombatResolver.
     */
    public int receiveDamage(int damage) {
        return receiveDamage(damage, ignored -> false);
    }

    /** Apply damage with a context-aware fatal-hit protection callback. */
    public int receiveDamage(int damage, IntPredicate preventFatalDamage) {
        int remaining = Math.max(0, (int) Math.round(
            modifyBattleStat(BattleStatKey.DAMAGE_TAKEN, damage)));
        if (remaining == 0) return 0;

        RuntimeAbilityEffect immunity = firstUsable(AbilityEffectType.IGNORE_DAMAGE);
        if (immunity != null) {
            consume(immunity);
            return 0;
        }

        for (RuntimeAbilityEffect shield : matching(AbilityEffectType.DAMAGE_SHIELD)) {
            if (remaining <= 0) break;
            int absorbed = Math.min(remaining, shield.remainingCapacity);
            remaining -= absorbed;
            shield.remainingCapacity -= absorbed;
            if (shield.remainingCapacity <= 0) runtimeAbilityEffects.remove(shield);
        }

        if (remaining >= currentHp) {
            if (currentHp > 0 && preventFatalDamage != null
                && preventFatalDamage.test(remaining)) {
                return 0;
            }
            RuntimeAbilityEffect survival = firstUsable(AbilityEffectType.SURVIVE_FATAL_DAMAGE);
            if (survival != null && currentHp > 0) {
                remaining = Math.max(0, currentHp - 1);
                consume(survival);
            }
        }
        int applied = Math.min(currentHp, remaining);
        currentHp -= applied;
        return applied;
    }

    /** Apply an instant kill without a battle activation context. */
    public int receiveInstantKill() {
        return receiveInstantKill(ignored -> false);
    }

    /** Apply instant kill with a context-aware fatal-hit protection callback. */
    public int receiveInstantKill(IntPredicate preventFatalDamage) {
        if (currentHp <= 0) return 0;
        int before = currentHp;
        if (preventFatalDamage != null && preventFatalDamage.test(before)) {
            return 0;
        }
        RuntimeAbilityEffect survival = firstUsable(AbilityEffectType.SURVIVE_FATAL_DAMAGE);
        if (survival != null) {
            currentHp = 1;
            consume(survival);
        } else {
            currentHp = 0;
        }
        return before - currentHp;
    }

    /** Restore HP and return the actual amount restored after caps/modifiers. */
    public int heal(int amount) {
        if (isDefeated()) return 0;
        int modified = Math.max(0, (int) Math.round(
            modifyBattleStat(BattleStatKey.HEALING, amount)));
        int restored = Math.min(Math.max(0, getMaxHp() - currentHp), modified);
        currentHp += restored;
        return restored;
    }

    public boolean isDefeated() {
        return currentHp <= 0;
    }

    // -------------------------------------------------------------------------
    // CE management
    // -------------------------------------------------------------------------

    /**
     * Drain CE for a move. Returns the actual amount drained (may be less than
     * requested if pool is nearly empty — move is still performed but CE goes to 0).
     */
    public int drainCe(int amount) {
        int drained = Math.min(currentCe, Math.max(0, amount));
        currentCe -= drained;
        return drained;
    }

    /** Restore CE and return the actual amount restored after caps/modifiers. */
    public int restoreCe(int amount) {
        int modified = Math.max(0, (int) Math.round(
            modifyBattleStat(BattleStatKey.CE_RESTORATION, amount)));
        int restored = Math.min(Math.max(0, getMaxCursedEnergy() - currentCe), modified);
        currentCe += restored;
        return restored;
    }

    /** Restore a fraction of the max CE pool (used by Black Flash proc). */
    public void restoreCeFraction(double fraction) {
        restoreCe((int) Math.round(getMaxCursedEnergy() * fraction));
    }

    public boolean hasAnyCe() {
        return currentCe > 0;
    }

    public boolean hasCe(int amount) {
        return currentCe >= amount;
    }

    /** Return true once per round so passive CE costs cannot be charged twice. */
    public boolean beginAbilityRoundCost(int roundNumber) {
        if (roundNumber <= lastAbilityCostRound) return false;
        lastAbilityCostRound = roundNumber;
        return true;
    }

    // -------------------------------------------------------------------------
    // Black Flash State
    // -------------------------------------------------------------------------

    /**
     * Enter Black Flash State. Resets or extends BFS to expire at end of (currentRound + 1).
     * @param currentRound the round in which the BF proc occurred
     */
    public void enterBlackFlashState(int currentRound) {
        if (!inBlackFlashState) {
            inBlackFlashState    = true;
            consecutiveBfsHits   = 0;
        }
        // Reset/extend duration each time BF procs
        bfsExpiresAfterRound = currentRound + 1;
    }

    /**
     * Record a consecutive BF hit within BFS (escalates the chance).
     * Call AFTER enterBlackFlashState on re-trigger.
     */
    public void recordBfsHit() {
        if (inBlackFlashState) {
            consecutiveBfsHits++;
        }
    }

    /**
     * Check and expire BFS at the end of a round.
     * @param completedRound the round that just ended
     */
    public void tickBfsExpiry(int completedRound) {
        if (inBlackFlashState && completedRound >= bfsExpiresAfterRound) {
            clearBlackFlashState();
        }
    }

    private void clearBlackFlashState() {
        inBlackFlashState     = false;
        consecutiveBfsHits    = 0;
        bfsExpiresAfterRound  = -1;
    }

    /**
     * Get the current Black Flash roll chance.
     * Returns BF_BASE_CHANCE if not in BFS.
     * Returns escalating BFS chance based on consecutive hits if in BFS.
     */
    public double getCurrentBfChance() {
        double base = inBlackFlashState
            ? CombatStats.BFS_BF_CHANCES[Math.min(consecutiveBfsHits, CombatStats.BFS_BF_CHANCES.length - 1)]
            : CombatStats.BF_BASE_CHANCE;
        // Add ability BF bonus, cap at 1.0
        double chance = modifyBattleStat(
            BattleStatKey.BLACK_FLASH_CHANCE,
            base + getAbilityFlags().bfChanceBonus);
        return Math.max(0.0, Math.min(1.0, chance));
    }

    public boolean isPoisonImmune() {
        return getAbilityFlags().poisonImmune;
    }

    public boolean hasSoulAwareAttacks() {
        return getAbilityFlags().soulAwareAttacks;
    }

    // -------------------------------------------------------------------------
    // Status effects
    // -------------------------------------------------------------------------

    public boolean addStatusEffect(StatusEffect effect) {
        if (effect == null || rejectsStatus(effect)) return false;
        activeEffects.add(effect);
        clampPoolsToMaximums();
        return true;
    }

    public boolean addStatusEffect(StatusEffect effect, BattleState.Phase phase) {
        if (effect == null || rejectsStatus(effect)) return false;
        int rounds = effect.getDurationRounds();
        int ticks = effect.getDurationTicks();
        if (rounds > 0) {
            boolean appliedAtRoundEnd = phase == BattleState.Phase.ROUND_END;
            boolean waitsForNextPlanning = phase == BattleState.Phase.RESOLUTION
                && ticks == 0 && affectsNextPlanning(effect.getType());
            if (appliedAtRoundEnd || waitsForNextPlanning) rounds++;
        }
        activeEffects.add(new StatusEffect(
            effect.getType(), rounds, ticks, effect.getMagnitude()));
        clampPoolsToMaximums();
        return true;
    }

    /** Convert a validated AUTO_STATUS_APPLY descriptor into a live status. */
    public boolean addAutomaticStatusEffect(AbilityEffectData effect) {
        return addAutomaticStatusEffect(effect, null);
    }

    public boolean addAutomaticStatusEffect(AbilityEffectData effect, BattleState.Phase phase) {
        if (effect == null || effect.stringValue == null) return false;
        try {
            double storedMagnitude = effect.magnitude != null ? effect.magnitude : 0.0;
            StatusEffectType type = StatusEffectType.fromName(
                effect.stringValue, storedMagnitude);
            int rounds = effect.durationRounds != null ? effect.durationRounds : 1;
            int ticks = effect.durationTicks != null ? effect.durationTicks : 0;
            double magnitude = StatusEffectType.normalizeStoredMagnitude(
                effect.stringValue, storedMagnitude);
            StatusEffect status = new StatusEffect(type, rounds, ticks, magnitude);
            return phase == null ? addStatusEffect(status) : addStatusEffect(status, phase);
        } catch (IllegalArgumentException ex) {
            System.err.println("[WARN] Invalid automatic status: " + effect.stringValue);
            return false;
        }
    }

    private boolean rejectsStatus(StatusEffect effect) {
        return effect.getType() == StatusEffectType.POISON && isPoisonImmune();
    }

    private static boolean affectsNextPlanning(StatusEffectType type) {
        return type.baseStat() == com.jjktbf.model.character.StatKey.SPEED
            || type.baseStat() == com.jjktbf.model.character.StatKey.COMBAT_ABILITY
            || type.baseStat() == com.jjktbf.model.character.StatKey.CURSED_ENERGY_EFFICIENCY
            || type.battleStat() == BattleStatKey.MAX_AP;
    }

    public boolean hasEffect(StatusEffectType type) {
        return activeEffects.stream().anyMatch(e -> e.getType() == type);
    }

    public List<StatusEffect> getActiveEffects() {
        return activeEffects;
    }

    /**
     * Tick down duration of all effects, removing those that have expired.
     *
     * <p>Effects that expire this tick (those reaching duration 0) are captured
     * for the combat engine to log via {@link #drainExpiredStatusEffects()}.
     * The capture is internal so this method's no-arg signature stays stable.
     */
    public void tickStatusEffects() {
        tickStatusEffectsWithoutClamping();
        clampPoolsToMaximums();
    }

    private void tickStatusEffectsWithoutClamping() {
        List<StatusEffect> remaining = new ArrayList<>();
        List<StatusEffect> expired = new ArrayList<>();
        for (StatusEffect e : activeEffects) {
            if (e.getDurationRounds() == -1) {
                remaining.add(e); // permanent until cleared
            } else if (e.getDurationRounds() > 0) {
                int rounds = e.getDurationRounds() - 1;
                if (rounds > 0 || e.getDurationTicks() > 0) {
                    remaining.add(new StatusEffect(
                        e.getType(), rounds, e.getDurationTicks(), e.getMagnitude()));
                } else {
                    expired.add(e);
                }
            } else {
                // Tick-only tails are advanced by the resolution clock.
                remaining.add(e);
            }
        }
        activeEffects.clear();
        activeEffects.addAll(remaining);
        expiredThisTick.clear();
        expiredThisTick.addAll(expired);
    }

    private void tickStatusEffectsOneTickWithoutClamping() {
        List<StatusEffect> remaining = new ArrayList<>();
        List<StatusEffect> expired = new ArrayList<>();
        for (StatusEffect effect : activeEffects) {
            if (effect.getDurationRounds() != 0) {
                remaining.add(effect);
            } else if (effect.getDurationTicks() > 1) {
                remaining.add(new StatusEffect(
                    effect.getType(), 0, effect.getDurationTicks() - 1, effect.getMagnitude()));
            } else {
                expired.add(effect);
            }
        }
        activeEffects.clear();
        activeEffects.addAll(remaining);
        expiredThisTick.clear();
        expiredThisTick.addAll(expired);
    }

    /**
     * Return (and clear) the status effects that expired during the most recent
     * {@link #tickStatusEffects()}, so the combat engine can emit one expiry log
     * line per effect. Each expiry is reported exactly once.
     *
     * @return the effects that expired this tick; empty if none did or if this
     *         was called between ticks
     */
    public List<StatusEffect> drainExpiredStatusEffects() {
        if (expiredThisTick.isEmpty()) return List.of();
        List<StatusEffect> drained = new ArrayList<>(expiredThisTick);
        expiredThisTick.clear();
        return drained;
    }

    public void removeEffect(StatusEffectType type) {
        activeEffects.removeIf(e -> e.getType() == type);
        clampPoolsToMaximums();
    }

    public int removeStatusEffects(StatusEffectType type) {
        int before = activeEffects.size();
        activeEffects.removeIf(effect -> effect.getType() == type);
        clampPoolsToMaximums();
        return before - activeEffects.size();
    }

    public int clearStatusEffects() {
        int removed = activeEffects.size();
        activeEffects.clear();
        clampPoolsToMaximums();
        return removed;
    }

    // -------------------------------------------------------------------------
    // Runtime ability effects
    // -------------------------------------------------------------------------

    public void addRuntimeAbilityEffect(AbilityEffectData effect) {
        addRuntimeAbilityEffect(effect, 0, BattleState.Phase.PLANNING);
    }

    public void addRuntimeAbilityEffect(
        AbilityEffectData effect,
        int currentRound,
        BattleState.Phase phase
    ) {
        if (effect == null || effect.type == null) return;
        runtimeAbilityEffects.add(new RuntimeAbilityEffect(effect, currentRound, phase));
        clampPoolsToMaximums();
    }

    public void tickRuntimeAbilityEffects(int completedRound) {
        tickRuntimeAbilityEffectsWithoutClamping(completedRound);
        clampPoolsToMaximums();
    }

    /** Expire runtime and status modifiers atomically before clamping resources. */
    public void tickRoundEffects(int completedRound) {
        tickRuntimeAbilityEffectsWithoutClamping(completedRound);
        tickStatusEffectsWithoutClamping();
        clampPoolsToMaximums();
    }

    /** Expire runtime and status modifiers atomically at an AP-tick boundary. */
    public void tickTimelineEffects() {
        tickRuntimeAbilityEffectsOneTickWithoutClamping();
        tickStatusEffectsOneTickWithoutClamping();
        clampPoolsToMaximums();
    }

    private void tickRuntimeAbilityEffectsWithoutClamping(int completedRound) {
        for (RuntimeAbilityEffect effect : new ArrayList<>(runtimeAbilityEffects)) {
            if (effect.remainingRounds <= 0) continue;
            if (completedRound < effect.notBeforeExpiryRound) continue;
            effect.remainingRounds--;
            if (effect.remainingRounds == 0 && effect.remainingTicks == 0) {
                runtimeAbilityEffects.remove(effect);
            }
        }
    }

    private void tickRuntimeAbilityEffectsOneTickWithoutClamping() {
        for (RuntimeAbilityEffect effect : new ArrayList<>(runtimeAbilityEffects)) {
            if (effect.remainingRounds != 0 || effect.remainingTicks <= 0) continue;
            effect.remainingTicks--;
            if (effect.remainingTicks == 0) runtimeAbilityEffects.remove(effect);
        }
    }

    public int getRemainingTimelineEffectTicks() {
        int statusTicks = activeEffects.stream()
            .filter(effect -> effect.getDurationRounds() == 0)
            .mapToInt(StatusEffect::getDurationTicks)
            .max().orElse(0);
        int runtimeTicks = runtimeAbilityEffects.stream()
            .filter(effect -> effect.remainingRounds == 0)
            .mapToInt(effect -> effect.remainingTicks)
            .max().orElse(0);
        int codedTicks = codedAbilities.getRemainingTimelineEffectTicks();
        return Math.max(Math.max(statusTicks, runtimeTicks), codedTicks);
    }

    public boolean consumeGuaranteedHit() {
        return consumeFirst(AbilityEffectType.GUARANTEE_NEXT_HIT);
    }

    public boolean consumeGuaranteedDodge() {
        return consumeFirst(AbilityEffectType.GUARANTEE_NEXT_DODGE);
    }

    public boolean consumeGuaranteedBlackFlash() {
        return consumeFirst(AbilityEffectType.GUARANTEE_NEXT_BLACK_FLASH);
    }

    public boolean consumeMoveCancellation() {
        return consumeFirst(AbilityEffectType.CANCEL_NEXT_MOVE);
    }

    public double modifyBattleStat(BattleStatKey key, double baseValue) {
        double additions = activeEffects.stream()
            .filter(effect -> effect.getType().isStatModifier())
            .filter(effect -> effect.getType().battleStat() == key)
            .mapToDouble(effect -> effect.getType().signedMagnitude(effect.getMagnitude()))
            .sum();
        double multiplier = 1.0;
        for (RuntimeAbilityEffect runtime : runtimeAbilityEffects) {
            AbilityEffectData effect = runtime.effect;
            AbilityEffectType type;
            try { type = AbilityEffectType.fromName(effect.type); }
            catch (IllegalArgumentException ex) { continue; }
            if (type != AbilityEffectType.BATTLE_STAT_ADD
                && type != AbilityEffectType.BATTLE_STAT_MULTIPLY) continue;
            BattleStatKey effectKey;
            try { effectKey = BattleStatKey.fromString(effect.stringValue); }
            catch (IllegalArgumentException ex) { continue; }
            if (effectKey != key) continue;
            if (type == AbilityEffectType.BATTLE_STAT_ADD) {
                additions += effect.doubleValue != null ? effect.doubleValue : 0.0;
            } else {
                multiplier *= effect.doubleValue != null ? effect.doubleValue : 1.0;
            }
        }
        return (baseValue + additions) * multiplier;
    }

    public int computeMoveCeCost(com.jjktbf.model.move.Move move) {
        int cost = CeEfficiencyCalculator.computeActualCost(
            move,
            getEffectiveStats().getCursedEnergyEfficiency(),
            getAbilityFlags());
        return Math.max(0, (int) Math.round(modifyBattleStat(BattleStatKey.CE_COST, cost)));
    }

    public boolean wasAbilityConditionTrue(String key) {
        return abilityConditionState.getOrDefault(key, false);
    }

    public void setAbilityConditionTrue(String key, boolean value) {
        abilityConditionState.put(key, value);
    }

    public void recordAbilityTrigger(String key, AbilityTrigger trigger) {
        List<AbilityTrigger> history = abilityTriggerHistory.computeIfAbsent(
            key, ignored -> new ArrayList<>());
        if (!history.isEmpty() && history.get(history.size() - 1) == trigger) return;
        history.add(trigger);
        if (history.size() > 64) history.remove(0);
    }

    public List<AbilityTrigger> getAbilityTriggerHistory(String key) {
        return List.copyOf(abilityTriggerHistory.getOrDefault(key, List.of()));
    }

    public void clearAbilityTriggerHistory(String key) {
        abilityTriggerHistory.remove(key);
    }

    public boolean beginAbilityFightStart() {
        if (abilityFightStartProcessed) return false;
        abilityFightStartProcessed = true;
        return true;
    }

    public boolean beginAbilityRoundStart(int roundNumber) {
        if (roundNumber <= lastAbilityRoundStartRound) return false;
        lastAbilityRoundStartRound = roundNumber;
        return true;
    }

    private boolean consumeFirst(AbilityEffectType type) {
        RuntimeAbilityEffect effect = firstUsable(type);
        if (effect == null) return false;
        consume(effect);
        return true;
    }

    private RuntimeAbilityEffect firstUsable(AbilityEffectType type) {
        return runtimeAbilityEffects.stream()
            .filter(effect -> type.name().equalsIgnoreCase(effect.effect.type))
            .filter(effect -> effect.remainingUses != 0)
            .findFirst()
            .orElse(null);
    }

    private List<RuntimeAbilityEffect> matching(AbilityEffectType type) {
        return runtimeAbilityEffects.stream()
            .filter(effect -> type.name().equalsIgnoreCase(effect.effect.type))
            .toList();
    }

    private void consume(RuntimeAbilityEffect effect) {
        if (effect.remainingUses == -1) return;
        effect.remainingUses--;
        if (effect.remainingUses <= 0) runtimeAbilityEffects.remove(effect);
    }

    private void clampPoolsToMaximums() {
        if (poolClampDeferrals > 0) return;
        currentHp = Math.min(currentHp, getMaxHp());
        currentCe = Math.min(currentCe, getMaxCursedEnergy());
    }

    void beginPoolClampDeferral() {
        poolClampDeferrals++;
    }

    void endPoolClampDeferral() {
        if (poolClampDeferrals > 0) poolClampDeferrals--;
        clampPoolsToMaximums();
    }

    boolean isPoolClampDeferred() {
        return poolClampDeferrals > 0;
    }

    private static final class RuntimeAbilityEffect {
        private final AbilityEffectData effect;
        private int remainingRounds;
        private int remainingTicks;
        private int remainingUses;
        private int remainingCapacity;
        private final int notBeforeExpiryRound;

        private RuntimeAbilityEffect(
            AbilityEffectData source,
            int currentRound,
            BattleState.Phase phase
        ) {
            effect = source.copy();
            remainingRounds = source.durationRounds == null ? -1 : source.durationRounds;
            remainingTicks = source.durationTicks == null ? 0 : source.durationTicks;
            StatusEffect.validateDuration(remainingRounds, remainingTicks);
            remainingUses = source.uses == null ? -1 : source.uses;
            remainingCapacity = source.intValue == null ? 0 : Math.max(0, source.intValue);
            boolean mustReachNextPlanning = phase == BattleState.Phase.ROUND_END
                || (remainingTicks == 0 && affectsPlanning(source)
                    && phase == BattleState.Phase.RESOLUTION);
            notBeforeExpiryRound = currentRound + (mustReachNextPlanning ? 1 : 0);
        }

        private static boolean affectsPlanning(AbilityEffectData effect) {
            AbilityEffectType type;
            try { type = AbilityEffectType.fromName(effect.type); }
            catch (IllegalArgumentException ex) { return false; }
            if (type == AbilityEffectType.TEMP_LOCK_MOVE_TAG) return true;
            if (type == AbilityEffectType.BATTLE_STAT_ADD
                || type == AbilityEffectType.BATTLE_STAT_MULTIPLY) {
                try {
                    BattleStatKey key = BattleStatKey.fromString(effect.stringValue);
                    return key == BattleStatKey.MAX_AP || key == BattleStatKey.CE_COST;
                } catch (IllegalArgumentException ignored) {
                    return false;
                }
            }
            if (type == AbilityEffectType.TEMP_STAT_ADD
                || type == AbilityEffectType.TEMP_STAT_MULTIPLY
                || type == AbilityEffectType.TEMP_STAT_SET_VALUE) {
                try {
                    com.jjktbf.model.character.StatKey key =
                        com.jjktbf.model.character.StatKey.fromString(effect.stat);
                    return key == com.jjktbf.model.character.StatKey.SPEED
                        || key == com.jjktbf.model.character.StatKey.COMBAT_ABILITY
                        || key == com.jjktbf.model.character.StatKey.CURSED_ENERGY_EFFICIENCY;
                } catch (IllegalArgumentException ignored) {
                    return false;
                }
            }
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Timeline
    // -------------------------------------------------------------------------

    public void setTimeline(Timeline timeline) {
        this.timeline = timeline;
    }

    public Timeline getTimeline() {
        return timeline;
    }

    // -------------------------------------------------------------------------
    // BattlePlan (two-board planning artifact)
    // -------------------------------------------------------------------------

    public void setPlan(BattlePlan plan) {
        this.plan = plan;
    }

    public BattlePlan getPlan() {
        return plan;
    }

    // -------------------------------------------------------------------------
    // Battle-instance identity (team/role/lifecycle)
    // -------------------------------------------------------------------------
    // These fields are assigned by BattleState when the combatant is created.
    // They are read widely (resolver, abilities, events, UI) but written only
    // through the package-private setters below so the BattleState remains the
    // single owner of identity.

    public CombatantId getInstanceId()            { return instanceId; }
    public BattleTeamId getTeamId()                { return teamId; }
    public int getRosterOrder()                    { return rosterOrder; }
    public CombatantRole getRole()                 { return role; }
    public CombatantId getSummonerId()             { return summonerId; }
    public CombatantLifecycle getLifecycle()       { return lifecycle; }

    public boolean isFighter()                     { return role == CombatantRole.FIGHTER; }
    public boolean isSummon()                      { return role == CombatantRole.SUMMON; }
    /** True while the combatant is still present in combat (not removed/defeated). */
    public boolean isActive()                      { return lifecycle == CombatantLifecycle.ACTIVE; }
    /** True once HP has hit 0 but before removal bookkeeping completes. */
    public boolean isLifecycleDefeated()           { return lifecycle == CombatantLifecycle.DEFEATED; }
    public boolean isRemoved()                     { return lifecycle == CombatantLifecycle.REMOVED; }

    /** Is {@code other} on the same team as this combatant? */
    public boolean isAlliedWith(BattleCombatant other) {
        return other != null && teamId != null && teamId.equals(other.teamId);
    }

    void assignIdentity(
        CombatantId instanceId,
        BattleTeamId teamId,
        int rosterOrder,
        CombatantRole role,
        CombatantId summonerId
    ) {
        if (this.instanceId != null || this.teamId != null || this.role != null) {
            if (Objects.equals(this.instanceId, instanceId)
                && Objects.equals(this.teamId, teamId)
                && this.rosterOrder == rosterOrder
                && this.role == role
                && Objects.equals(this.summonerId, summonerId)) {
                return;
            }
            throw new IllegalStateException("Combatant identity is already assigned");
        }
        if (rosterOrder < 0) {
            throw new IllegalArgumentException("rosterOrder cannot be negative");
        }
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(role, "role");
        if (role == CombatantRole.FIGHTER && summonerId != null) {
            throw new IllegalArgumentException("A fighter cannot have a summoner");
        }
        if (role == CombatantRole.SUMMON && summonerId == null) {
            throw new IllegalArgumentException("A summon must have a summoner");
        }
        this.instanceId = instanceId;
        this.teamId = teamId;
        this.rosterOrder = rosterOrder;
        this.role = role;
        this.summonerId = summonerId;
    }

    void markLifecycleDefeated() {
        if (lifecycle == CombatantLifecycle.ACTIVE) {
            lifecycle = CombatantLifecycle.DEFEATED;
        }
    }

    void markRemoved() {
        lifecycle = CombatantLifecycle.REMOVED;
    }

    // -------------------------------------------------------------------------
    // Convenience accessors
    // -------------------------------------------------------------------------

    public Character getCharacter()                        { return character; }
    public CharacterStats getEffectiveStats() {
        List<AbilityEffectData> modifiers = runtimeAbilityEffects.stream()
            .map(runtime -> runtime.effect)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Map<com.jjktbf.model.character.StatKey, Double> statusAmounts =
            new java.util.EnumMap<>(com.jjktbf.model.character.StatKey.class);
        for (StatusEffect effect : activeEffects) {
            com.jjktbf.model.character.StatKey stat = effect.getType().baseStat();
            if (stat == null) continue;
            statusAmounts.merge(stat,
                effect.getType().signedMagnitude(effect.getMagnitude()), Double::sum);
        }
        for (Map.Entry<com.jjktbf.model.character.StatKey, Double> entry
            : statusAmounts.entrySet()) {
            int amount = (int) Math.round(entry.getValue());
            if (amount != 0) {
                modifiers.add(AbilityEffectData.statAdd(entry.getKey().fieldName, amount));
            }
        }
        return AbilityApplicator.applyRuntimeStats(effectiveStats, modifiers);
    }

    public double getStatusMagnitude(StatusEffectType type) {
        return activeEffects.stream()
            .filter(effect -> effect.getType() == type)
            .mapToDouble(StatusEffect::getMagnitude)
            .sum();
    }
    public CombatStats getEffectiveCombatStats() {
        return new CombatStats(getEffectiveStats(), getAbilityFlags().jujutsuArtSlots);
    }
    public AbilityApplicator.AbilityFlags getAbilityFlags(){
        AbilityApplicator.AbilityFlags flags = abilityFlags.copy();
        for (RuntimeAbilityEffect runtime : runtimeAbilityEffects) {
            flags.addEffect(runtime.effect);
            if (AbilityEffectType.TEMP_LOCK_MOVE_TAG.name().equals(runtime.effect.type)
                && runtime.effect.moveTag != null) {
                flags.lockedMoveTags.add(runtime.effect.moveTag);
            }
        }
        return flags;
    }
    public int getMaxApBar() {
        double base = getEffectiveCombatStats().getMaxApBar() + getAbilityFlags().apBarBonus;
        return Math.max(0, (int) Math.round(modifyBattleStat(BattleStatKey.MAX_AP, base)));
    }
    public int getMaxHp() {
        return Math.max(1, (int) Math.round(modifyBattleStat(
            BattleStatKey.MAX_HP, getEffectiveCombatStats().getMaxHp())));
    }
    public int getMaxCursedEnergy() {
        return Math.max(0, (int) Math.round(modifyBattleStat(
            BattleStatKey.MAX_CE, getEffectiveCombatStats().getMaxCursedEnergy())));
    }
    public int getAccuracy() {
        return Math.max(0, (int) Math.round(modifyBattleStat(
            BattleStatKey.ACCURACY, getEffectiveCombatStats().getAccuracy())));
    }
    public int getEvasion() {
        return Math.max(0, (int) Math.round(modifyBattleStat(
            BattleStatKey.EVASION, getEffectiveCombatStats().getEvasion())));
    }
    public int getCurrentHp()                             { return currentHp; }
    public int getCurrentCe()                             { return currentCe; }
    public boolean isInBlackFlashState()                  { return inBlackFlashState; }
    public int getConsecutiveBfsHits()                    { return consecutiveBfsHits; }
    public int getBfsExpiresAfterRound()                  { return bfsExpiresAfterRound; }
    public List<Ability> getAbilities()                   { return abilities; }
    public CodedAbilities getCodedAbilities()              { return codedAbilities; }

    /**
     * Compute this combatant's current defense value (dynamic — depends on current CE).
     * Uses effective stats (ability-modified) and applies the defense multiplier flag.
     */
    public int computeCurrentDefense(int currentTick) {
        AbilityApplicator.AbilityFlags flags = getAbilityFlags();
        int baseDefense = flags.defenseFromDurabilityMultiplier == null
            ? CombatStats.computeDefense(
                getEffectiveStats(),
                currentCe,
                getMaxCursedEnergy())
            : (int) Math.round(
                com.jjktbf.model.character.StatScale.scale(
                    getEffectiveStats().getDurability())
                    * flags.defenseFromDurabilityMultiplier);
        double modified = baseDefense * flags.defenseMultiplier;
        return Math.max(0, (int) Math.round(modifyBattleStat(BattleStatKey.DEFENSE, modified)));
    }

    @Override
    public String toString() {
        return String.format("%s  HP:%d/%d  CE:%d/%d  BFS:%s",
            character.getName(),
            currentHp, getMaxHp(),
            currentCe, getMaxCursedEnergy(),
            inBlackFlashState ? "ACTIVE(x" + consecutiveBfsHits + ")" : "off"
        );
    }
}
