package com.jjktbf.model.combat;

import com.jjktbf.model.move.Move;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * A single move's occupancy on the AP timeline for one round.
 *
 * An ActionSegment records:
 *  - Which move is being performed
 *  - Where on the AP bar it starts (startTick, 1-indexed)
 *  - Its total AP width (== move.getApCost())
 *  - The absolute tick at which it fires (startTick + unleashPoint - 1)
 *  - Whether it has fired (the resolver actually executed it this round)
 *  - Whether it has been stunned (removed from the timeline by an interrupt or stun effect)
 *
 * The "action counter" sweeps from tick 1 to the end of the AP bar.
 * When the counter reaches a segment's fireTick, the move resolves.
 *
 * Priority rule for simultaneous fireTicks:
 *   Moves with unleashPoint == 1 (fireTick == startTick) are highest priority.
 *   All ties resolved by the combatant's Speed stat (higher Speed wins).
 *
 * This ordering applies to defenses too: a defensive move only contests an
 * attack landing on its tick if it has already markFired() — i.e. it won the
 * same-tick ordering. A slower same-tick defense does not contest a faster
 * attack. (Enforced via the requireFiredDefense gate in DamageCalculator.)
 */
public class ActionSegment {

    private final Move   move;
    private final int    startTick;
    private final int    fireTick;       // absolute tick: startTick + unleashPoint - 1
    private boolean      stunned;        // set true when interrupted or hit by a stun effect
    private boolean      fired;          // set true once the resolver actually executes this move

    /**
     * The CE cost actually charged for this segment (after efficiency scaling).
     * Stored here because CE is drained when the segment's startTick is reached.
     */
    private final int    actualCeCost;

    /**
     * Ordered target combatant instance ids for hostile selected-target moves.
     * An incomplete list is permitted while editing, but locking/submission is
     * rejected when the move's required target count is not met.
     *
     * <p>Once a move has fired, the resolver fixes the target: if the selected
     * target is invalid (defeated/removed), the resolver refills in stable roster
     * order up to the number originally selected. These are planned targets; the
     * resolver fixes the final target snapshot at fire time.
     */
    private List<CombatantId> targets;

    public ActionSegment(Move move, int startTick, int actualCeCost) {
        this(move, startTick, actualCeCost, List.of());
    }

    public ActionSegment(Move move, int startTick, int actualCeCost, CombatantId target) {
        this(move, startTick, actualCeCost, target == null ? List.of() : List.of(target));
    }

    public ActionSegment(
        Move move,
        int startTick,
        int actualCeCost,
        List<CombatantId> targets
    ) {
        this.move          = move;
        this.startTick     = startTick;
        this.fireTick      = startTick + move.getUnleashPoint() - 1;
        this.actualCeCost  = actualCeCost;
        setTargets(targets);
        this.stunned       = false;
        this.fired         = false;
    }

    public Move    getMove()          { return move; }
    public int     getStartTick()     { return startTick; }
    public int     getEndTick()       { return startTick + move.getApCost() - 1; }
    public int     getFireTick()      { return fireTick; }
    public int     getComponentImpactTick(int componentIndex) {
        return fireTick + move.getHitComponents().get(componentIndex).getDelayTicks();
    }
    public int     getFinalImpactTick() { return fireTick + move.getMaxHitDelayTicks(); }
    public int     getResolutionEndTick() { return Math.max(getEndTick(), getFinalImpactTick()); }
    public int     getActualCeCost()  { return actualCeCost; }
    public boolean isStunned()        { return stunned; }
    public boolean isInstant()        { return move.getUnleashPoint() == 1; }

    /** Ordered, distinct combatant instance ids explicitly selected for this move. */
    public List<CombatantId> getTargets() { return targets; }

    public void setTargets(List<CombatantId> targets) {
        LinkedHashSet<CombatantId> normalized = new LinkedHashSet<>();
        if (targets != null) {
            for (CombatantId target : targets) {
                if (target != null) normalized.add(target);
            }
        }
        this.targets = List.copyOf(normalized);
    }

    /** Singular compatibility view used by single-target callers. */
    public CombatantId getTarget() { return targets.isEmpty() ? null : targets.get(0); }

    /** Singular compatibility setter used by single-target callers. */
    public void setTarget(CombatantId target) {
        setTargets(target == null ? List.of() : List.of(target));
    }

    /** True when this segment needs explicit enemy targets to be valid. */
    public boolean     needsTarget()  { return BattlePlan.requiresTarget(move); }

    /** True once this segment's move has actually resolved this round. */
    public boolean hasFired()         { return fired; }

    /**
     * Mark this segment as fired. Called by the resolver the moment the move
     * actually executes. Once fired a move is final: its effects (including an
     * active defensive block's protection window) cannot be retroactively
     * cancelled by a later stun or interrupt on the same tick.
     */
    public void markFired()           { this.fired = true; }

    /**
     * Mark this segment as stunned (removed from the timeline).
     *
     * <p>Stunning a move that has <b>already fired</b> is a no-op. A stun is
     * meant to stop a move from occurring, not to deactivate one whose effects
     * are already in play — most importantly, a defensive block that already
     * fired keeps protecting for the rest of its AP window and cannot be
     * cancelled out from under itself by a stun landing on the same tick. This
     * choke-point guards every stun entry point (stun effect, interrupt, CE drain).
     */
    public void stun()                { if (!fired) this.stunned = true; }

    @Override
    public String toString() {
        return String.format("ActionSegment{%s ticks=[%d-%d] fire=%d CE=%d %s}",
            move.getName(), startTick, getEndTick(), fireTick, actualCeCost,
            stunned ? "STUNNED" : "");
    }
}
