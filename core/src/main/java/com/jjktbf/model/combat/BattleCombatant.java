package com.jjktbf.model.combat;

import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CombatStats;
import com.jjktbf.model.move.StatusEffect;
import com.jjktbf.model.move.StatusEffectType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Mutable battle-time wrapper around an immutable Character.
 *
 * Tracks all state that changes during a fight:
 *  - Current HP and CE pool
 *  - Active status effects
 *  - Black Flash State (BFS) and escalation counter
 *  - Defense buff from active defensive moves
 *  - The current round's planned action queue (timeline)
 *
 * The underlying Character is never mutated.
 */
public class BattleCombatant {

    private final Character character;

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

    // --- Full-block tracker ---
    /**
     * If the combatant has an active FULL_BLOCK move in the current timeline,
     * this stores the AP range [start, unleashPoint] of that block.
     * The block is "active" (protective) while the counter is still on it.
     * Set to -1/-1 when no full block is queued.
     */
    private int fullBlockStartTick;
    private int fullBlockEndTick;

    // --- Round's action timeline ---
    private Timeline timeline;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public BattleCombatant(Character character) {
        this.character               = character;
        this.currentHp               = character.getCombatStats().getMaxHp();
        this.currentCe               = character.getCombatStats().getMaxCursedEnergy();
        this.activeEffects           = new ArrayList<>();
        this.inBlackFlashState       = false;
        this.consecutiveBfsHits      = 0;
        this.bfsExpiresAfterRound    = -1;
        this.defenseBuffActive       = 0;
        this.defenseBuffExpiresAtTick = -1;
        this.fullBlockStartTick      = -1;
        this.fullBlockEndTick        = -1;
        this.timeline                = null;
    }

    // -------------------------------------------------------------------------
    // HP management
    // -------------------------------------------------------------------------

    public void applyDamage(int damage) {
        currentHp = Math.max(0, currentHp - damage);
    }

    public void restoreHp(int amount) {
        currentHp = Math.min(character.getCombatStats().getMaxHp(), currentHp + amount);
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
        currentCe = Math.min(character.getCombatStats().getMaxCursedEnergy(), currentCe + amount);
    }

    /** Restore a fraction of the max CE pool (used by Black Flash proc). */
    public void restoreCeFraction(double fraction) {
        int amount = (int) Math.round(character.getCombatStats().getMaxCursedEnergy() * fraction);
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
        if (!inBlackFlashState) {
            return CombatStats.BF_BASE_CHANCE;
        }
        int[] index = { Math.min(consecutiveBfsHits, CombatStats.BFS_BF_CHANCES.length - 1) };
        return CombatStats.BFS_BF_CHANCES[index[0]];
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
    // Full block
    // -------------------------------------------------------------------------

    public void setFullBlock(int startTick, int endTick) {
        this.fullBlockStartTick = startTick;
        this.fullBlockEndTick   = endTick;
    }

    public void clearFullBlock() {
        fullBlockStartTick = -1;
        fullBlockEndTick   = -1;
    }

    /**
     * Returns true if the action counter is currently within this combatant's full-block range,
     * meaning an incoming attack would be fully negated.
     */
    public boolean isFullBlockActiveAt(int currentTick) {
        return fullBlockStartTick != -1
            && currentTick >= fullBlockStartTick
            && currentTick <= fullBlockEndTick;
    }

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

    public Character getCharacter()     { return character; }
    public int getCurrentHp()           { return currentHp; }
    public int getCurrentCe()           { return currentCe; }
    public boolean isInBlackFlashState(){ return inBlackFlashState; }
    public int getConsecutiveBfsHits()  { return consecutiveBfsHits; }

    /**
     * Compute this combatant's current defense value (dynamic — depends on current CE).
     */
    public int computeCurrentDefense(int currentTick) {
        int baseDefense = CombatStats.computeDefense(
            character.getBaseStats(),
            currentCe,
            character.getCombatStats().getMaxCursedEnergy()
        );
        return baseDefense + getDefenseBuffAt(currentTick);
    }

    @Override
    public String toString() {
        return String.format("%s  HP:%d/%d  CE:%d/%d  BFS:%s",
            character.getName(),
            currentHp, character.getCombatStats().getMaxHp(),
            currentCe, character.getCombatStats().getMaxCursedEnergy(),
            inBlackFlashState ? "ACTIVE(x" + consecutiveBfsHits + ")" : "off"
        );
    }
}
