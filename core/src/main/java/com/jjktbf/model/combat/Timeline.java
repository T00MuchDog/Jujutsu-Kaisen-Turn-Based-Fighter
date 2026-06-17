package com.jjktbf.model.combat;

import com.jjktbf.model.move.Move;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The AP timeline for one combatant during one round.
 *
 * Represents the full AP bar as a sequence of ActionSegments packed from tick 1.
 * Empty AP between or after segments is implicit (no move occupying that tick).
 *
 * Rules:
 *  - Segments are placed sequentially; a new segment starts at (previous segment's end + 1).
 *  - Total AP of all segments cannot exceed maxApBar.
 *  - BLOCK defensive moves register their tick range for active-block queries during resolution.
 */
public class Timeline {

    private final int            maxApBar;
    private final List<ActionSegment> segments;
    private int                   nextAvailableTick;

    public Timeline(int maxApBar) {
        this.maxApBar           = maxApBar;
        this.segments           = new ArrayList<>();
        this.nextAvailableTick  = 1;
    }

    /**
     * Attempt to add an action segment to the timeline.
     *
     * @param move          the move to queue
     * @param actualCeCost  CE cost after efficiency scaling
     * @return the created ActionSegment, or null if insufficient AP remains
     */
    public ActionSegment addMove(Move move, int actualCeCost) {
        int remaining = maxApBar - (nextAvailableTick - 1);
        if (move.getApCost() > remaining) {
            return null; // not enough AP
        }
        ActionSegment segment = new ActionSegment(move, nextAvailableTick, actualCeCost);
        segments.add(segment);
        nextAvailableTick += move.getApCost();
        return segment;
    }

    /**
     * Remaining AP points available for more moves this round.
     */
    public int remainingAp() {
        return maxApBar - (nextAvailableTick - 1);
    }

    /**
     * Retrieve the ActionSegment whose range [startTick, endTick] contains the given tick.
     * Returns null if no segment covers that tick (empty AP gap).
     */
    public ActionSegment segmentAt(int tick) {
        for (ActionSegment segment : segments) {
            if (tick >= segment.getStartTick() && tick <= segment.getEndTick()) {
                return segment;
            }
        }
        return null;
    }

    /**
     * Returns the next ActionSegment after the one containing the given tick.
     * Used for KNOCK_NEXT_SEGMENT interrupt resolution.
     */
    public ActionSegment nextSegmentAfter(int tick) {
        ActionSegment current = segmentAt(tick);
        if (current == null) return null;

        int idx = segments.indexOf(current);
        if (idx + 1 < segments.size()) {
            return segments.get(idx + 1);
        }
        return null;
    }

    /**
     * Get all non-knocked-out ActionSegments that fire at the given tick.
     */
    public List<ActionSegment> firingAt(int tick) {
        List<ActionSegment> firing = new ArrayList<>();
        for (ActionSegment segment : segments) {
            if (!segment.isKnockedOut() && segment.getFireTick() == tick) {
                firing.add(segment);
            }
        }
        return firing;
    }

    /**
     * Check if an active block (PERCENTAGE_BLOCK or FLAT_BLOCK) is covering the given tick.
     */
    public boolean hasActiveBlockAt(int tick) {
        return activeBlockAt(tick, null) != null;
    }

    public ActionSegment activeBlockAt(int tick, Move incomingMove) {
        for (ActionSegment segment : segments) {
            Move move = segment.getMove();
            if (segment.isKnockedOut() || !move.isActiveBlock()) continue;
            if (incomingMove != null && !incomingMove.hasAllTags(move.getBlockAffectedTags())) continue;

            int start = segment.getFireTick();
            int end = switch (move.getBlockDuration()) {
                case -1 -> maxApBar;
                case 0  -> start + move.getApCost() - 1;
                default -> start + move.getBlockDuration() - 1;
            };
            if (tick >= start && tick <= end) return segment;
        }
        return null;
    }

    /**
     * The total AP consumed by all segments (including knocked-out ones — AP is spent).
     */
    public int totalApUsed() {
        return nextAvailableTick - 1;
    }

    public List<ActionSegment> getSegments() { return Collections.unmodifiableList(segments); }
    public int getMaxApBar()                 { return maxApBar; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Timeline[");
        sb.append(maxApBar).append(" AP] ");
        for (ActionSegment segment : segments) {
            sb.append(segment).append(" | ");
        }
        return sb.toString();
    }
}
