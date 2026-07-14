package com.jjktbf.model.combat;

import com.jjktbf.model.move.Move;

/**
 * A single move's occupancy on the AP timeline for one round.
 *
 * An ActionSegment records:
 *  - Which move is being performed
 *  - Where on the AP bar it starts (startTick, 1-indexed)
 *  - Its total AP width (== move.getApCost())
 *  - The absolute tick at which it fires (startTick + unleashPoint - 1)
 *  - Whether it has been stunned (removed from the timeline by an interrupt or stun-tag hit)
 *
 * The "action counter" sweeps from tick 1 to the end of the AP bar.
 * When the counter reaches a segment's fireTick, the move resolves.
 *
 * Priority rule for simultaneous fireTicks:
 *   Moves with unleashPoint == 1 (fireTick == startTick) are highest priority.
 *   All ties resolved by the combatant's Speed stat (higher Speed wins).
 */
public class ActionSegment {

    private final Move   move;
    private final int    startTick;
    private final int    fireTick;       // absolute tick: startTick + unleashPoint - 1
    private boolean      stunned;        // set true when interrupted or hit by a stun-tag move

    /**
     * The CE cost actually charged for this segment (after efficiency scaling).
     * Stored here because CE is drained when the segment's startTick is reached.
     */
    private final int    actualCeCost;

    public ActionSegment(Move move, int startTick, int actualCeCost) {
        this.move          = move;
        this.startTick     = startTick;
        this.fireTick      = startTick + move.getUnleashPoint() - 1;
        this.actualCeCost  = actualCeCost;
        this.stunned       = false;
    }

    public Move    getMove()          { return move; }
    public int     getStartTick()     { return startTick; }
    public int     getEndTick()       { return startTick + move.getApCost() - 1; }
    public int     getFireTick()      { return fireTick; }
    public int     getActualCeCost()  { return actualCeCost; }
    public boolean isStunned()        { return stunned; }
    public boolean isInstant()        { return move.getUnleashPoint() == 1; }

    /** Mark this segment as stunned (removed from the timeline). */
    public void stun()                { this.stunned = true; }

    @Override
    public String toString() {
        return String.format("ActionSegment{%s ticks=[%d-%d] fire=%d CE=%d %s}",
            move.getName(), startTick, getEndTick(), fireTick, actualCeCost,
            stunned ? "STUNNED" : "");
    }
}
