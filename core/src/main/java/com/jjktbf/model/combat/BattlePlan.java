package com.jjktbf.model.combat;

import com.jjktbf.model.move.Move;

import java.util.ArrayList;
import java.util.List;

/**
 * A combatant's round plan: two action-timeline boards (offensive + defensive)
 * sharing one AP budget and one CE budget.
 *
 * <p><b>Grid.</b> Both timelines are a fixed {@value #GRID_LENGTH}-dot board.
 * Segments are placed freely (gaps allowed) on the dots.
 *
 * <p><b>Budgets.</b> {@code apBudget} (the stat-derived maxApBar, repurposed)
 * caps the total AP that may be placed across <em>both</em> timelines.
 * {@code ceBudget} caps the total CE cost. Placing a segment deducts from both;
 * removing restores both. The CE budget here is the <em>predicted</em> pool used
 * during planning — the combatant's real {@code currentCe} is untouched and
 * carries into the (deferred) execution phase unchanged.
 *
 * <p><b>Bar assignment rule.</b> A move belongs on the offensive timeline iff
 * {@code move.hasTag("ATTACK")} (the basePower+category heuristic); otherwise it
 * belongs on the defensive timeline. This is enforced by {@link #place}.
 *
 * <p>Resolution-support queries ({@link #firingSegmentsAt}, {@link #segmentAt},
 * {@link #activeBlockAt}) cross both timelines so the (future) ticker can sweep
 * both boards by tick. {@link #toLegacyTimeline()} merges the two boards into a
 * single old-style {@link Timeline} so today's {@link CombatResolver} keeps
 * running unchanged while the execution refactor is pending.
 */
public class BattlePlan {

    /** Fixed grid length (dot count) for both timelines. */
    public static final int GRID_LENGTH = Timeline.DEFAULT_GRID_LENGTH;

    /** Which board a segment lives on. */
    public enum Board { OFFENSIVE, DEFENSIVE }

    private final Timeline offensive = new Timeline(GRID_LENGTH);
    private final Timeline defensive = new Timeline(GRID_LENGTH);

    private final int apBudget;
    private final int ceBudget;

    private int apUsed = 0;
    private int ceUsed = 0;

    public BattlePlan(int apBudget, int ceBudget) {
        this.apBudget = apBudget;
        this.ceBudget = ceBudget;
    }

    // -------------------------------------------------------------------------
    // Budget queries
    // -------------------------------------------------------------------------

    public int remainingApBudget() { return apBudget - apUsed; }
    public int remainingCe()       { return ceBudget - ceUsed; }
    public int totalApUsed()       { return apUsed; }
    public int totalCeUsed()       { return ceUsed; }
    public int apBudget()          { return apBudget; }
    public int ceBudget()          { return ceBudget; }

    /** Does this move fit the budgets if placed (CE cost passed in)? */
    public boolean fitsBudgets(Move move, int ceCost) {
        return move.getApCost() <= remainingApBudget()
            && ceCost <= remainingCe();
    }

    // -------------------------------------------------------------------------
    // Placement
    // -------------------------------------------------------------------------

    /**
     * Place a move on its assigned board at {@code tick}. Validates the board
     * assignment rule, grid bounds, occupancy, and both budgets.
     *
     * @param ceCost the efficiency-scaled CE cost to charge
     * @return the created segment, or {@code null} if placement is invalid
     *         (wrong board, out of bounds, overlapping, or over budget).
     */
    public ActionSegment place(Move move, int tick, int ceCost) {
        if (!fitsBudgets(move, ceCost)) return null;
        Board board = boardFor(move);
        Timeline tl = boardTimeline(board);
        ActionSegment segment = tl.placeAt(move, tick, ceCost);
        if (segment == null) return null;
        apUsed += move.getApCost();
        ceUsed += ceCost;
        return segment;
    }

    /** Place at the first free range on the move's board that fits it. */
    public ActionSegment placeFirstFit(Move move, int ceCost) {
        if (!fitsBudgets(move, ceCost)) return null;
        Timeline tl = boardTimeline(boardFor(move));
        ActionSegment segment = tl.placeAtFirstFit(move, ceCost);
        if (segment == null) return null;
        apUsed += move.getApCost();
        ceUsed += ceCost;
        return segment;
    }

    /** Remove a placed segment from whichever board holds it; refunds budgets. */
    public boolean remove(ActionSegment segment) {
        if (offensive.remove(segment) || defensive.remove(segment)) {
            apUsed -= segment.getMove().getApCost();
            ceUsed -= segment.getActualCeCost();
            return true;
        }
        return false;
    }

    /** Remove every placed segment (full reset); refunds all budgets. */
    public void clear() {
        for (ActionSegment s : new ArrayList<>(offensive.getSegments())) remove(s);
        for (ActionSegment s : new ArrayList<>(defensive.getSegments())) remove(s);
    }

    // -------------------------------------------------------------------------
    // Board assignment + queries
    // -------------------------------------------------------------------------

    /** Which board a move must live on, per the attack/defense split rule. */
    public static Board boardFor(Move move) {
        return move.hasTag("ATTACK") ? Board.OFFENSIVE : Board.DEFENSIVE;
    }

    public Timeline offensiveTimeline() { return offensive; }
    public Timeline defensiveTimeline() { return defensive; }

    public Timeline boardTimeline(Board board) {
        return board == Board.OFFENSIVE ? offensive : defensive;
    }

    /** All segments across both boards. */
    public List<ActionSegment> allSegments() {
        List<ActionSegment> all = new ArrayList<>();
        all.addAll(offensive.getSegments());
        all.addAll(defensive.getSegments());
        return all;
    }

    /** Segment covering the tick on the given board, or null. */
    public ActionSegment segmentAt(Board board, int tick) {
        return boardTimeline(board).segmentAt(tick);
    }

    /**
     * All segments firing at the tick across both boards. Offensive yields first
     * (the planned execution rule: on a same-tick tie, offensive fires before
     * defensive for the same combatant; cross-combatant ties break on Speed).
     */
    public List<ActionSegment> firingSegmentsAt(int tick) {
        List<ActionSegment> firing = new ArrayList<>();
        firing.addAll(offensive.firingAt(tick));
        firing.addAll(defensive.firingAt(tick));
        return firing;
    }

    public ActionSegment activeBlockAt(Board board, int tick, Move incomingMove) {
        return boardTimeline(board).activeBlockAt(tick, incomingMove);
    }

    // -------------------------------------------------------------------------
    // Legacy compatibility — keeps today's CombatResolver running unchanged
    // -------------------------------------------------------------------------

    /**
     * Merge both boards into a single old-style {@link Timeline} (a 150-wide
     * grid holding every segment from both boards). This is a stopgap so the
     * current single-timeline resolver can process a two-board plan while the
     * execution refactor (cross-board ticker) is pending.
     */
    public Timeline toLegacyTimeline() {
        Timeline merged = new Timeline(GRID_LENGTH);
        for (ActionSegment s : allSegments()) {
            // Re-place by hand (placeAt enforces no-overlap; boards are disjoint
            // by construction so no overlap occurs).
            merged.placeAt(s.getMove(), s.getStartTick(), s.getActualCeCost());
        }
        return merged;
    }
}
