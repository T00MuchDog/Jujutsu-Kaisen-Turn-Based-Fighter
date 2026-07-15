package com.jjktbf.model.combat;

import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectTarget;
import com.jjktbf.model.character.AbilityEffectTiming;

/**
 * Snapshot of the battle's current phase.
 *
 * The state machine flows as:
 *
 *   PLANNING → RESOLUTION → ROUND_END → PLANNING → ...
 *                                  ↓
 *                              BATTLE_OVER
 *
 * PLANNING:    Players (and AI) submit their move queues for the round.
 * RESOLUTION:  The action counter sweeps through the AP timeline; moves fire.
 * ROUND_END:   Status effects tick, BFS expiry checked, round counter increments.
 * BATTLE_OVER: One or both combatants are defeated.
 */
public class BattleState {

    public enum Phase {
        PLANNING,
        RESOLUTION,
        ROUND_END,
        BATTLE_OVER
    }

    private final BattleCombatant playerCombatant;
    private final BattleCombatant enemyCombatant;

    private Phase currentPhase;
    private int   roundNumber;

    /** The AP tick the action counter is currently on during RESOLUTION phase. */
    private int   currentTick;

    /** Set during BATTLE_OVER to indicate who won. Null if ongoing. */
    private BattleCombatant winner;

    public BattleState(BattleCombatant playerCombatant, BattleCombatant enemyCombatant) {
        this.playerCombatant = playerCombatant;
        this.enemyCombatant  = enemyCombatant;
        this.currentPhase    = Phase.PLANNING;
        this.roundNumber     = 1;
        this.currentTick     = 0;
        this.winner          = null;
        applyAutomaticStatuses(AbilityEffectTiming.FIGHT_START);
        applyAutomaticStatuses(AbilityEffectTiming.ROUND_START);
    }

    // -------------------------------------------------------------------------
    // Phase transitions
    // -------------------------------------------------------------------------

    public void transitionTo(Phase phase) {
        this.currentPhase = phase;
        if (phase == Phase.RESOLUTION) {
            this.currentTick = 1;
        }
    }

    public void advanceTick() {
        currentTick++;
    }

    public void endRound() {
        roundNumber++;
        currentTick = 0;
        applyAutomaticStatuses(AbilityEffectTiming.ROUND_START);
    }

    private void applyAutomaticStatuses(AbilityEffectTiming timing) {
        applyAutomaticStatusesFrom(playerCombatant, enemyCombatant, timing);
        applyAutomaticStatusesFrom(enemyCombatant, playerCombatant, timing);
    }

    private static void applyAutomaticStatusesFrom(
        BattleCombatant owner,
        BattleCombatant enemy,
        AbilityEffectTiming timing
    ) {
        for (AbilityEffectData effect : owner.getAbilityFlags().autoStatusEffects) {
            if (!timing.name().equals(effect.timing)) continue;
            BattleCombatant target = AbilityEffectTarget.ENEMY.name().equals(effect.target)
                ? enemy : owner;
            target.addAutomaticStatusEffect(effect);
        }
    }

    // -------------------------------------------------------------------------
    // Win condition check
    // -------------------------------------------------------------------------

    /**
     * Check if the battle is over. Sets winner and transitions to BATTLE_OVER.
     * @return true if battle ended
     */
    public boolean checkAndResolveBattleOver() {
        boolean playerDefeated = playerCombatant.isDefeated();
        boolean enemyDefeated  = enemyCombatant.isDefeated();

        if (playerDefeated || enemyDefeated) {
            currentPhase = Phase.BATTLE_OVER;
            if (!playerDefeated && enemyDefeated) {
                winner = playerCombatant;
            } else if (playerDefeated && !enemyDefeated) {
                winner = enemyCombatant;
            } else {
                winner = null; // simultaneous KO — draw
            }
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public BattleCombatant getPlayerCombatant() { return playerCombatant; }
    public BattleCombatant getEnemyCombatant()  { return enemyCombatant; }
    public Phase           getCurrentPhase()    { return currentPhase; }
    public int             getRoundNumber()     { return roundNumber; }
    public int             getCurrentTick()     { return currentTick; }
    public BattleCombatant getWinner()          { return winner; }
    public boolean         isBattleOver()       { return currentPhase == Phase.BATTLE_OVER; }

    @Override
    public String toString() {
        return String.format("BattleState{Round=%d Phase=%s Tick=%d | %s vs %s}",
            roundNumber, currentPhase, currentTick,
            playerCombatant, enemyCombatant);
    }
}
