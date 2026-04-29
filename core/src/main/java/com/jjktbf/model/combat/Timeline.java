package com.jjktbf.model.combat;

import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.Move;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The AP timeline for one combatant during one round.
 *
 * Represents the full AP bar as a sequence of MoveBlocks packed from tick 1.
 * Empty AP between or after blocks is implicit (no block occupying that tick).
 *
 * Rules:
 *  - Blocks are placed sequentially; a new block starts at (previous block's end + 1).
 *  - Total AP of all blocks cannot exceed maxApBar.
 *  - The FULL_BLOCK defensive move, if present, registers its tick range for
 *    real-time active-block queries during resolution.
 */
public class Timeline {

    private final int            maxApBar;
    private final List<MoveBlock> blocks;
    private int                   nextAvailableTick;

    public Timeline(int maxApBar) {
        this.maxApBar           = maxApBar;
        this.blocks             = new ArrayList<>();
        this.nextAvailableTick  = 1;
    }

    /**
     * Attempt to add a move block to the timeline.
     *
     * @param move          the move to queue
     * @param actualCeCost  CE cost after efficiency scaling
     * @return the created MoveBlock, or null if insufficient AP remains
     */
    public MoveBlock addMove(Move move, int actualCeCost) {
        int remaining = maxApBar - (nextAvailableTick - 1);
        if (move.getApCost() > remaining) {
            return null; // not enough AP
        }
        MoveBlock block = new MoveBlock(move, nextAvailableTick, actualCeCost);
        blocks.add(block);
        nextAvailableTick += move.getApCost();
        return block;
    }

    /**
     * Remaining AP points available for more moves this round.
     */
    public int remainingAp() {
        return maxApBar - (nextAvailableTick - 1);
    }

    /**
     * Retrieve the MoveBlock whose range [startTick, endTick] contains the given tick.
     * Returns null if no block covers that tick (empty AP gap).
     */
    public MoveBlock blockAt(int tick) {
        for (MoveBlock b : blocks) {
            if (tick >= b.getStartTick() && tick <= b.getEndTick()) {
                return b;
            }
        }
        return null;
    }

    /**
     * Returns the next MoveBlock after the one containing the given tick.
     * Used for KNOCK_NEXT_BLOCK interrupt resolution.
     */
    public MoveBlock nextBlockAfter(int tick) {
        MoveBlock current = blockAt(tick);
        if (current == null) return null;

        int idx = blocks.indexOf(current);
        if (idx + 1 < blocks.size()) {
            return blocks.get(idx + 1);
        }
        return null;
    }

    /**
     * Get all non-knocked-out MoveBlocks that fire at the given tick.
     */
    public List<MoveBlock> firingAt(int tick) {
        List<MoveBlock> firing = new ArrayList<>();
        for (MoveBlock b : blocks) {
            if (!b.isKnockedOut() && b.getFireTick() == tick) {
                firing.add(b);
            }
        }
        return firing;
    }

    /**
     * Check if a FULL_BLOCK defensive move is covering the given tick.
     * A full block is "active" if the counter is still inside its AP block range.
     */
    public boolean hasActiveFullBlockAt(int tick) {
        for (MoveBlock b : blocks) {
            if (!b.isKnockedOut()
                && b.getMove().getDefenseType() == DefenseType.BLOCK
                && tick >= b.getStartTick()
                && tick <= b.getEndTick()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a BLOCK defensive move is covering the given tick.
     * When active, applies blockDamageReduction to incoming damage.
     */
    public boolean hasActiveBlockAt(int tick) {
        for (MoveBlock b : blocks) {
            if (!b.isKnockedOut()
                && b.getMove().getDefenseType() == DefenseType.BLOCK
                && tick >= b.getStartTick()
                && tick <= b.getEndTick()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The total AP consumed by all blocks (including knocked-out ones — AP is spent).
     */
    public int totalApUsed() {
        return nextAvailableTick - 1;
    }

    public List<MoveBlock> getBlocks()  { return Collections.unmodifiableList(blocks); }
    public int getMaxApBar()            { return maxApBar; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Timeline[");
        sb.append(maxApBar).append(" AP] ");
        for (MoveBlock b : blocks) {
            sb.append(b).append(" | ");
        }
        return sb.toString();
    }
}
