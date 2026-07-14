package com.jjktbf.model.combat;

import com.jjktbf.model.move.Move;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One action-timeline board: a fixed-length grid of AP dots on which
 * {@link ActionSegment}s are placed freely (gaps allowed).
 *
 * <p>A {@link BattlePlan} owns two of these — offensive and defensive — which
 * together form a combatant's round plan. This class is purely the spatial
 * board: it knows about dot occupancy and segment queries, but <b>not</b> about
 * AP/CE budgets (those live on {@link BattlePlan}, which enforces them across
 * both timelines).
 *
 * <p><b>Grid model.</b> Dots are 1-indexed: tick {@code 1..gridLength}. A
 * segment occupying ticks {@code [startTick, startTick + apCost - 1]} must lie
 * wholly within the grid and not overlap any existing segment. Multiple
 * segments may coexist with empty gaps between them.
 *
 * <p>Resolution-support queries ({@link #segmentAt}, {@link #nextSegmentAfter},
 * {@link #firingAt}, {@link #activeBlockAt}) are preserved so the (deferred)
 * cross-timeline ticker can sweep both boards by tick.
 */
public class Timeline {

    /** The fixed grid length for production timelines (both offensive & defensive). */
    public static final int DEFAULT_GRID_LENGTH = 150;

    private final int gridLength;
    private final List<ActionSegment> segments = new ArrayList<>();

    public Timeline() {
        this(DEFAULT_GRID_LENGTH);
    }

    /** Test/utility constructor allowing a smaller grid. */
    public Timeline(int gridLength) {
        this.gridLength = gridLength;
    }

    // -------------------------------------------------------------------------
    // Placement
    // -------------------------------------------------------------------------

    /**
     * Place a segment at an explicit start tick. The range
     * {@code [startTick, startTick + apCost - 1]} must lie within the grid and
     * be free of overlap.
     *
     * @return the created segment, or {@code null} if the placement is invalid
     *         (out of bounds or overlapping).
     */
    public ActionSegment placeAt(Move move, int startTick, int actualCeCost) {
        int endTick = startTick + move.getApCost() - 1;
        if (startTick < 1 || endTick > gridLength) return null;
        if (!isRangeFree(startTick, endTick)) return null;
        ActionSegment segment = new ActionSegment(move, startTick, actualCeCost);
        segments.add(segment);
        return segment;
    }

    /**
     * Convenience: place at the first free range that fits the move (leftmost).
     * Returns {@code null} if no gap large enough exists.
     */
    public ActionSegment placeAtFirstFit(Move move, int actualCeCost) {
        int need = move.getApCost();
        int cursor = 1;
        for (ActionSegment s : sortedByStart()) {
            int gap = s.getStartTick() - cursor;
            if (gap >= need) {
                return placeAt(move, cursor, actualCeCost);
            }
            cursor = Math.max(cursor, s.getEndTick() + 1);
        }
        if (gridLength - cursor + 1 >= need) {
            return placeAt(move, cursor, actualCeCost);
        }
        return null;
    }

    /** Remove a placed segment. No-op if not present. */
    public boolean remove(ActionSegment segment) {
        return segments.remove(segment);
    }

    /** True if no segment occupies any tick in {@code [startTick, endTick]}. */
    public boolean isRangeFree(int startTick, int endTick) {
        for (ActionSegment s : segments) {
            boolean overlaps = !(endTick < s.getStartTick() || startTick > s.getEndTick());
            if (overlaps) return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Queries (preserved for resolution)
    // -------------------------------------------------------------------------

    /** The segment whose range contains the tick, or null (empty gap). */
    public ActionSegment segmentAt(int tick) {
        for (ActionSegment s : segments) {
            if (tick >= s.getStartTick() && tick <= s.getEndTick()) return s;
        }
        return null;
    }

    /** The next segment strictly after the one containing the tick (for KNOCK_NEXT_SEGMENT). */
    public ActionSegment nextSegmentAfter(int tick) {
        ActionSegment current = segmentAt(tick);
        List<ActionSegment> sorted = sortedByStart();
        int startIdx = current == null ? -1 : sorted.indexOf(current);
        for (int i = 0; i < sorted.size(); i++) {
            ActionSegment s = sorted.get(i);
            if (current == null) {
                if (s.getStartTick() > tick) return s;
            } else if (i > startIdx) {
                return s;
            }
        }
        return null;
    }

    /** All non-stunned segments that fire at the given tick. */
    public List<ActionSegment> firingAt(int tick) {
        List<ActionSegment> firing = new ArrayList<>();
        for (ActionSegment s : segments) {
            if (!s.isStunned() && s.getFireTick() == tick) firing.add(s);
        }
        return firing;
    }

    public boolean hasActiveBlockAt(int tick) {
        return activeBlockAt(tick, null) != null;
    }

    public ActionSegment activeBlockAt(int tick, Move incomingMove) {
        for (ActionSegment s : segments) {
            Move move = s.getMove();
            if (s.isStunned() || !move.isActiveBlock()) continue;
            // A block fires iff it COVERS every damage tag the incoming attack
            // uses (attack tags ⊆ block tags). See Move#coveredByBlockTags.
            if (incomingMove != null && !incomingMove.coveredByBlockTags(move.getBlockAffectedTags())) continue;

            int start = s.getFireTick();
            int end = switch (move.getBlockDuration()) {
                case -1 -> gridLength;
                case 0  -> start + move.getApCost() - 1;
                default -> start + move.getBlockDuration() - 1;
            };
            if (tick >= start && tick <= end) return s;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Sum of every placed segment's AP cost (regardless of stun). */
    public int totalApUsed() {
        int sum = 0;
        for (ActionSegment s : segments) sum += s.getMove().getApCost();
        return sum;
    }

    public List<ActionSegment> getSegments() { return Collections.unmodifiableList(segments); }
    public int getGridLength()               { return gridLength; }
    public boolean isEmpty()                 { return segments.isEmpty(); }

    private List<ActionSegment> sortedByStart() {
        List<ActionSegment> copy = new ArrayList<>(segments);
        copy.sort((a, b) -> Integer.compare(a.getStartTick(), b.getStartTick()));
        return copy;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Timeline[").append(gridLength).append(" grid] ");
        for (ActionSegment s : sortedByStart()) sb.append(s).append(" | ");
        return sb.toString();
    }
}
