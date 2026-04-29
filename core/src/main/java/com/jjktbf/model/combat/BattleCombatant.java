package com.jjktbf.model.combat;

import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityApplicator;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.CombatStats;
// Explicit import to avoid ambiguity with java.lang.Character
import com.jjktbf.model.character.Character;
import com.jjktbf.model.move.StatusEffect;
import com.jjktbf.model.move.StatusEffectType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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

    // --- Mutable battle state ---
    private int currentHp;
    private int currentCe;

    private final List<StatusEffect> activeEffects;

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

    // --- Dynamic defense modifier (from STAT_BUFF defensive moves) ---
    /** Extra defense added by an active defensive buff. 0 if none. */
    private int defenseBuffActive;
    /** AP tick in the current round at which the defense buff expires. -1 = lasts the round. */
    private int defenseBuffExpiresAtTick;

    // --- Block tracker ---
    /**
     * If the combatant has an active BLOCK move in the current timeline,
     * this stores the AP range [start, end] of that block.
     * The block is "active" (protective) while the counter is within this range.
     * Set to -1/-1 when no block is queued.
     */
    private int blockStartTick;
    private int blockEndTick;
    private int blockDamageReduction = 100;

    // --- Round's action timeline ---
    private Timeline timeline;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public BattleCombatant(Character character) {
        this(character, List.of());
    }

    /**
     * Full constructor — applies the given list of abilities before computing
     * effective stats. Use this when the character's AbilityRepository has
     * been loaded.
     */
    public BattleCombatant(Character character, List<Ability> abilities) {
        this.character = character;

        // Apply passive ability effects to produce modified stats + flags
        AbilityApplicator.ApplicationResult result =
            AbilityApplicator.apply(character.getBaseStats(), abilities);

        this.effectiveStats      = result.modifiedStats;
        this.effectiveCombatStats= new CombatStats(effectiveStats);
        this.abilityFlags        = result.flags;

        // HP and CE derived from effective (ability-modified) stats
        this.currentHp               = effectiveCombatStats.getMaxHp();
        this.currentCe               = effectiveCombatStats.getMaxCursedEnergy();
        this.activeEffects           = new ArrayList<>();
        this.inBlackFlashState       = false;
        this.consecutiveBfsHits      = 0;
        this.bfsExpiresAfterRound    = -1;
        this.defenseBuffActive       = 0;
        this.defenseBuffExpiresAtTick = -1;
        this.blockStartTick        = -1;
        this.blockEndTick          = -1;
        this.blockDamageReduction = 100;
        this.timeline                = null;
    }

    // -------------------------------------------------------------------------
    // HP management
    // -------------------------------------------------------------------------

    public void applyDamage(int damage) {
        currentHp = Math.max(0, currentHp - damage);
    }

    public void restoreHp(int amount) {
        currentHp = Math.min(effectiveCombatStats.getMaxHp(), currentHp + amount);
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
        int drained = Math.min(currentCe, amount);
        currentCe -= drained;
        return drained;
    }

    public void restoreCe(int amount) {
        currentCe = Math.min(effectiveCombatStats.getMaxCursedEnergy(), currentCe + amount);
    }

    /** Restore a fraction of the max CE pool (used by Black Flash proc). */
    public void restoreCeFraction(double fraction) {
        int amount = (int) Math.round(effectiveCombatStats.getMaxCursedEnergy() * fraction);
        restoreCe(amount);
    }

    public boolean hasAnyCe() {
        return currentCe > 0;
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
        return Math.min(1.0, base + abilityFlags.bfChanceBonus);
    }

    // -------------------------------------------------------------------------
    // Defense buff
    // -------------------------------------------------------------------------

    public void applyDefenseBuff(int amount, int expiresAtTick) {
        this.defenseBuffActive        = amount;
        this.defenseBuffExpiresAtTick = expiresAtTick;
    }

    public int getDefenseBuffAt(int currentTick) {
        if (defenseBuffExpiresAtTick == -1 || currentTick <= defenseBuffExpiresAtTick) {
            return defenseBuffActive;
        }
        return 0;
    }

    public void clearDefenseBuff() {
        defenseBuffActive        = 0;
        defenseBuffExpiresAtTick = -1;
    }

    // -------------------------------------------------------------------------
    // Block
    // -------------------------------------------------------------------------

    public void setBlock(int startTick, int endTick, int damageReduction) {
        this.blockStartTick = startTick;
        this.blockEndTick   = endTick;
        this.blockDamageReduction = damageReduction;
    }

    public void clearBlock() {
        blockStartTick = -1;
        blockEndTick   = -1;
        blockDamageReduction = 100;
    }

    /**
     * Returns true if the action counter is currently within this combatant's block range,
     * meaning an incoming attack would be reduced by blockDamageReduction %.
     */
    public boolean isBlockActiveAt(int currentTick) {
        return blockStartTick != -1
            && currentTick >= blockStartTick
            && currentTick <= blockEndTick;
    }

    public int getBlockDamageReduction() { return blockDamageReduction; }

    // -------------------------------------------------------------------------
    // Status effects
    // -------------------------------------------------------------------------

    public void addStatusEffect(StatusEffect effect) {
        activeEffects.add(effect);
    }

    public boolean hasEffect(StatusEffectType type) {
        return activeEffects.stream().anyMatch(e -> e.getType() == type);
    }

    public List<StatusEffect> getActiveEffects() {
        return activeEffects;
    }

    /** Tick down duration of all effects, removing those that have expired. */
    public void tickStatusEffects() {
        Iterator<StatusEffect> it = activeEffects.iterator();
        // We replace expired effects with new ones reduced by 1 round
        List<StatusEffect> remaining = new ArrayList<>();
        while (it.hasNext()) {
            StatusEffect e = it.next();
            if (e.getDurationRounds() == -1) {
                remaining.add(e); // permanent until cleared
            } else if (e.getDurationRounds() > 1) {
                remaining.add(new StatusEffect(e.getType(), e.getDurationRounds() - 1, e.getMagnitude()));
            }
            // duration 1 → expires, not added
        }
        activeEffects.clear();
        activeEffects.addAll(remaining);
    }

    public void removeEffect(StatusEffectType type) {
        activeEffects.removeIf(e -> e.getType() == type);
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
    // Convenience accessors
    // -------------------------------------------------------------------------

    public Character getCharacter()                        { return character; }
    public CharacterStats getEffectiveStats()              { return effectiveStats; }
    public CombatStats getEffectiveCombatStats()           { return effectiveCombatStats; }
    public AbilityApplicator.AbilityFlags getAbilityFlags(){ return abilityFlags; }
    public int getCurrentHp()                             { return currentHp; }
    public int getCurrentCe()                             { return currentCe; }
    public boolean isInBlackFlashState()                  { return inBlackFlashState; }
    public int getConsecutiveBfsHits()                    { return consecutiveBfsHits; }

    /**
     * Compute this combatant's current defense value (dynamic — depends on current CE).
     * Uses effective stats (ability-modified) and applies the defense multiplier flag.
     */
    public int computeCurrentDefense(int currentTick) {
        int baseDefense = CombatStats.computeDefense(
            effectiveStats,
            currentCe,
            effectiveCombatStats.getMaxCursedEnergy()
        );
        int buffed = baseDefense + getDefenseBuffAt(currentTick);
        return (int) Math.round(buffed * abilityFlags.defenseMultiplier);
    }

    @Override
    public String toString() {
        return String.format("%s  HP:%d/%d  CE:%d/%d  BFS:%s",
            character.getName(),
            currentHp, effectiveCombatStats.getMaxHp(),
            currentCe, effectiveCombatStats.getMaxCursedEnergy(),
            inBlackFlashState ? "ACTIVE(x" + consecutiveBfsHits + ")" : "off"
        );
    }
}
