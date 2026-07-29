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
 * <p>Resolution-support queries ({@link #firingAt}, {@link #activeDefenseAt})
 * are preserved so the (deferred) cross-timeline ticker can sweep both boards by tick.
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
        long endTickLong = (long) startTick + move.getApCost() - 1L;
        long fireTick = (long) startTick + move.getUnleashPoint() - 1L;
        long finalImpactTick = fireTick + move.getMaxHitDelayTicks();
        if (startTick < 1 || endTickLong > gridLength || finalImpactTick > gridLength) return null;
        int endTick = (int) endTickLong;
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

    /**
     * Insert an already-constructed segment directly, <b>without</b> the bounds
     * or overlap checks that {@link #placeAt} enforces.
     *
     * <p>For merge use only: when two independent boards (e.g. a
     * {@link BattlePlan}'s offensive and defensive timelines) are merged into a
     * single legacy timeline, the source segments were already validated when
     * placed on their own board. A segment on board A is intentionally allowed
     * to overlap a segment on board B (they are different boards), so the
     * single-board no-overlap rule must not be re-applied during the merge —
     * otherwise the second-overlapping segment would be silently dropped and
     * never fire. See {@link BattlePlan#toLegacyTimeline()}.
     */
    void addSegment(ActionSegment segment) {
        segments.add(segment);
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

    /** All non-stunned segments that fire at the given tick. */
    public List<ActionSegment> firingAt(int tick) {
        List<ActionSegment> firing = new ArrayList<>();
        for (ActionSegment s : segments) {
            if (!s.isStunned() && s.getFireTick() == tick) firing.add(s);
        }
        return firing;
    }

    /** True when a non-stunned action segment occupies the given tick. */
    public boolean hasActiveSegmentAt(int tick) {
        for (ActionSegment s : segments) {
            if (!s.isStunned() && tick >= s.getStartTick() && tick <= s.getEndTick()) {
                return true;
            }
        }
        return false;
    }

    /** True while a segment occupies AP or still has a committed impact pending. */
    public boolean hasResolutionAt(int tick) {
        for (ActionSegment segment : segments) {
            if (segment.isStunned() && !segment.hasFired()) continue;
            if (tick >= segment.getStartTick() && tick <= segment.getResolutionEndTick()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find the active defense segment of the requested {@code type} whose window
     * covers {@code tick} and which applies to {@code incomingMove}. Returns the
     * first match, or null if none.
     *
     * <p>Coverage rules per defense type:
     * <ul>
     *   <li>{@link com.jjktbf.model.move.DefenseType#BLOCK BLOCK} — uses
     *       {@link Move#coveredByBlockTags(List)} (attack tags ⊆ block tags).</li>
     *   <li>{@link com.jjktbf.model.move.DefenseType#PARRY PARRY} — parries are
     *       weapon-based and type-agnostic; any attack with the ATTACK nature
     *       can be parried.</li>
     *   <li>{@link com.jjktbf.model.move.DefenseType#DODGE DODGE} — uses
     *       {@link Move#dodgeAppliesTo(Move)} (MELEE / RANGED / BOTH scope).</li>
     * </ul>
     * Stunned segments are skipped. The window is computed from
     * {@link Move#getBlockDuration()} with the same semantics as the legacy block
     * window (0 = apCost, -1 = end of round, &gt;0 = N ticks from unleash).
     */
    public ActionSegment activeDefenseAt(int tick, Move incomingMove,
                                         com.jjktbf.model.move.DefenseType type) {
        com.jjktbf.model.move.HitComponent component = incomingMove == null
            || incomingMove.getHitComponents().isEmpty()
            ? null : incomingMove.getHitComponents().get(0);
        return activeDefenseAt(tick, incomingMove, component, type, false);
    }

    /** Component-aware defense query; damage coverage uses the component's type. */
    public ActionSegment activeDefenseAt(
        int tick,
        Move incomingMove,
        com.jjktbf.model.move.HitComponent component,
        com.jjktbf.model.move.DefenseType type
    ) {
        return activeDefenseAt(tick, incomingMove, component, type, false);
    }

    /** Component-aware defense query that can exclude defenses not yet unleashed. */
    public ActionSegment activeDefenseAt(
        int tick,
        Move incomingMove,
        com.jjktbf.model.move.HitComponent component,
        com.jjktbf.model.move.DefenseType type,
        boolean requireFired
    ) {
        for (ActionSegment s : segments) {
            Move move = s.getMove();
            if (s.isStunned() || (requireFired && !s.hasFired())
                || move.getDefenseType() != type) continue;
            if (incomingMove != null) {
                if (type == com.jjktbf.model.move.DefenseType.BLOCK
                        && !incomingMove.coveredByBlockTags(
                            move.getBlockAffectedTags(), component)) continue;
                if (type == com.jjktbf.model.move.DefenseType.DODGE
                        && !move.dodgeAppliesTo(incomingMove)) continue;
                // PARRY: no tag/scope filter — any attack is parryable.
            }
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

    /**
     * Convenience for any active defense (BLOCK / PARRY / DODGE) covering the
     * tick. Returns the first matching segment regardless of type.
     */
    public ActionSegment activeDefenseAt(int tick, Move incomingMove) {
        for (ActionSegment s : segments) {
            Move move = s.getMove();
            if (s.isStunned() || !move.isActiveDefense()) continue;
            if (incomingMove != null) {
                com.jjktbf.model.move.DefenseType dt = move.getDefenseType();
                if (dt == com.jjktbf.model.move.DefenseType.BLOCK
                        && !incomingMove.coveredByBlockTags(move.getBlockAffectedTags())) continue;
                if (dt == com.jjktbf.model.move.DefenseType.DODGE
                        && !move.dodgeAppliesTo(incomingMove)) continue;
            }
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

    /** Legacy alias kept for callers that query any blocking defense. */
    public ActionSegment activeBlockAt(int tick, Move incomingMove) {
        return activeDefenseAt(tick, incomingMove, com.jjktbf.model.move.DefenseType.BLOCK);
    }

    public ActionSegment activeBlockAt(
        int tick,
        Move incomingMove,
        com.jjktbf.model.move.HitComponent component
    ) {
        return activeDefenseAt(
            tick, incomingMove, component, com.jjktbf.model.move.DefenseType.BLOCK);
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
