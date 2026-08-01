package com.jjktbf.model.combat;

import com.jjktbf.model.move.Move;

import java.util.ArrayList;
import java.util.List;

/**
 * A combatant's round plan: two action-timeline boards (offensive + defensive)
 * sharing one AP budget and one CE budget.
 *
 * <p><b>Grid.</b> Both timelines share a battle-wide grid length derived from
 * the strongest fighter's AP tier
 * ({@link Timeline#gridLengthForStrongestAp}); the default is
 * {@value #GRID_LENGTH}. Segments are placed freely (gaps allowed) on the dots.
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
 * <p>{@link #toLegacyTimeline()} merges the two boards into a single old-style
 * {@link Timeline} so today's {@link CombatResolver} can process a two-board
 * plan.
 */
public class BattlePlan {

    /** Fixed grid length (dot count) for both timelines. */
    public static final int GRID_LENGTH = Timeline.DEFAULT_GRID_LENGTH;

    /** Which board a segment lives on. */
    public enum Board { OFFENSIVE, DEFENSIVE }

    private final Timeline offensive;
    private final Timeline defensive;

    private final int apBudget;
    private final int ceBudget;

    private int apUsed = 0;
    private int ceUsed = 0;

    /**
     * Builds a plan whose timeline grid length is derived from the owner's
     * AP budget's tier (see {@link Timeline#gridLengthForStrongestAp}). Use
     * {@link #BattlePlan(int, int, int)} when the battle-wide length is known
     * (it is computed from {@code max(player, enemy)} AP).
     */
    public BattlePlan(int apBudget, int ceBudget) {
        this(apBudget, ceBudget, Timeline.gridLengthForStrongestAp(apBudget));
    }

    /**
     * Builds a plan on an explicit battle-wide grid length. Both timelines use
     * this length; it should be
     * {@code Timeline.gridLengthForStrongestAp(max(player, enemy) AP)}.
     */
    public BattlePlan(int apBudget, int ceBudget, int gridLength) {
        this.apBudget = apBudget;
        this.ceBudget = ceBudget;
        this.offensive = new Timeline(gridLength);
        this.defensive = new Timeline(gridLength);
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

    /** Number of times this move is currently placed across both boards. */
    public int selectedUses(Move move) {
        if (move == null) return 0;
        return (int) allSegments().stream()
            .filter(segment -> move.getId().equals(segment.getMove().getId()))
            .count();
    }

    /** Whether this move has another use available under its per-round cap. */
    public boolean hasRemainingUses(Move move) {
        return move != null && (move.getMoveCap() == 0
            || selectedUses(move) < move.getMoveCap());
    }

    /** Does this move fit the AP and CE budgets? */
    public boolean fitsBudgets(Move move, int ceCost) {
        return move != null
            && move.getApCost() <= remainingApBudget()
            && ceCost <= remainingCe();
    }

    /** Does this move fit the budgets and have a use remaining this round? */
    public boolean canPlace(Move move, int ceCost) {
        return hasRemainingUses(move) && fitsBudgets(move, ceCost);
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
        if (!canPlace(move, ceCost)) return null;
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
        if (!canPlace(move, ceCost)) return null;
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

    /** The battle-wide grid length (dot count) of both timelines. */
    public int gridLength() { return offensive.getGridLength(); }

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

    // -------------------------------------------------------------------------
    // Legacy compatibility — keeps today's CombatResolver running unchanged
    // -------------------------------------------------------------------------

    /**
     * Merge both boards into a single old-style {@link Timeline} (a grid of
     * this plan's length holding every segment from both boards). This is a
     * stopgap so the
     * current single-timeline resolver can process a two-board plan while the
     * execution refactor (cross-board ticker) is pending.
     */
    public Timeline toLegacyTimeline() {
        Timeline merged = new Timeline(offensive.getGridLength());
        // Insert each segment directly, bypassing placeAt's no-overlap check.
        // The offensive and defensive boards are independent grids: a defensive
        // segment is intentionally allowed to overlap an offensive one (they
        // are on different boards). Calling placeAt here would treat that valid
        // cross-board overlap as a collision and silently drop the second
        // segment — so a defensive move placed under an offensive move would
        // never make it into the merged timeline and never fire.
        for (ActionSegment s : allSegments()) {
            merged.addSegment(new ActionSegment(s.getMove(), s.getStartTick(), s.getActualCeCost()));
        }
        return merged;
    }
}
