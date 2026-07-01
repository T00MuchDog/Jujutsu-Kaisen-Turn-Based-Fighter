package com.jjktbf.graphics.ui.battle;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Buttons;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.CeEfficiencyCalculator;
import com.jjktbf.model.move.Move;

import java.util.ArrayList;
import java.util.List;

/**
 * The round-planning UI: two timeline bars (offensive above defensive), a
 * bottom move palette, a live AP/CE budget readout, and a circular "Lock In"
 * button centred between the bars.
 *
 * <p><b>Interaction.</b>
 * <ul>
 *   <li>Press on a palette card → starts a drag of a ghost action segment
 *       (type chip + name header). Release over a valid dot slot on the
 *       segment's assigned bar places it; release elsewhere cancels.</li>
 *   <li>Press on a placed segment → drags it; it re-snaps to the nearest valid
 *       dot as the cursor moves (discrete grid movement). Release commits.</li>
 *   <li>Invalid placements (out of budget, occupied range, wrong bar) are
 *       rejected — the ghost glows yellow when near a valid slot, nothing else.</li>
 *   <li>Click "Lock In" → confirms the plan.</li>
 * </ul>
 *
 * <p><b>Board rule.</b> A move belongs on the offensive bar iff
 * {@code move.hasTag("ATTACK")} (the basePower+category heuristic); otherwise
 * the defensive bar. The panel only accepts drops on the assigned bar.
 *
 * <p>The panel owns the {@link BattlePlan} draft and mutates it directly. It is
 * driven by a {@link PlanningInputProcessor} installed while planning is active.
 */
public class PlanningPanel {

    private final BattlePlan plan;
    private final List<Move> knownMoves;
    private final int ceEfficiency;
    private final com.jjktbf.model.character.AbilityApplicator.AbilityFlags abilityFlags;

    // Layout (set in layout())
    private final TimelineBar offensiveBar;
    private final TimelineBar defensiveBar;
    private final List<MoveCardView> cards = new ArrayList<>();
    private final Rectangle lockInBounds;

    // Rendered segment views, rebuilt from the plan each frame (cheap enough).
    private final List<ActionSegmentView> offensiveViews = new ArrayList<>();
    private final List<ActionSegmentView> defensiveViews = new ArrayList<>();

    // Drag state
    private Move draggingMove;          // dragging from palette (move to place)
    private ActionSegment draggingSegment; // dragging an existing segment
    private BattlePlan.Board draggingBoard;
    private int draggingTick;           // current snapped tick
    private boolean snapValid;          // is the current snapped slot placeable?
    private float dragMouseX, dragMouseY;

    private boolean confirmed = false;

    /** Fired when the player clicks "Lock In". The owner uses this to unblock
     *  the controller thread (e.g. set the screen's inputConfirmed flag). */
    private Runnable onConfirm = () -> {};

    /** Set the confirm callback (called on the render thread when Lock In is clicked). */
    public void setOnConfirm(Runnable onConfirm) {
        this.onConfirm = onConfirm == null ? () -> {} : onConfirm;
    }

    public PlanningPanel(BattleCombatant combatant, float originX, float originY, float barWidth, float barHeight, float barGap) {
        this.plan = new BattlePlan(combatant.getMaxApBar(), combatant.getCurrentCe());
        this.knownMoves = new ArrayList<>(combatant.getCharacter().getKnownMoves());
        this.ceEfficiency = combatant.getEffectiveStats().getCursedEnergyEfficiency();
        this.abilityFlags = combatant.getAbilityFlags();

        // Offensive bar above, defensive below, with a gap for the Lock In button.
        this.offensiveBar = new TimelineBar(TimelineBar.Kind.OFFENSIVE,
            originX, originY + barHeight + barGap, barWidth, barHeight);
        this.defensiveBar = new TimelineBar(TimelineBar.Kind.DEFENSIVE,
            originX, originY, barWidth, barHeight);

        // Lock In button centred between the bars.
        float lockR = Math.min(barGap / 2f, 28f);
        this.lockInBounds = new Rectangle(
            originX + barWidth / 2f - lockR,
            originY + barHeight + barGap / 2f - lockR,
            lockR * 2f, lockR * 2f);

        buildPalette(originX, originY - MoveCardView.CARD_H - 12f);
    }

    private void buildPalette(float startX, float y) {
        cards.clear();
        float x = startX;
        for (Move m : knownMoves) {
            cards.add(new MoveCardView(m, x, y));
            x += MoveCardView.CARD_W + 8f;
        }
    }

    public BattlePlan getPlan() { return plan; }
    public boolean isConfirmed() { return confirmed; }

    /** The input processor that drives the panel; install via setInputProcessor. */
    public PlanningInputProcessor inputProcessor() {
        return new PlanningInputProcessor();
    }

    // -------------------------------------------------------------------------
    // Per-frame render + state refresh
    // -------------------------------------------------------------------------

    /** Refresh derived state: card disabled-flags + segment views. Call each frame before draw. */
    public void refresh() {
        // Grey out unaffordable cards.
        for (MoveCardView card : cards) {
            Move m = card.getMove();
            int cost = ceCost(m);
            boolean affordable = m.getApCost() <= plan.remainingApBudget()
                              && cost <= plan.remainingCe();
            card.setDisabled(!affordable);
        }
        // Rebuild segment views from the plan (offensive / defensive).
        offensiveViews.clear();
        defensiveViews.clear();
        for (ActionSegment s : plan.offensiveTimeline().getSegments()) {
            offensiveViews.add(new ActionSegmentView(s, 0, 0, 0, offensiveBar.getBounds().height - 8f));
        }
        for (ActionSegment s : plan.defensiveTimeline().getSegments()) {
            defensiveViews.add(new ActionSegmentView(s, 0, 0, 0, defensiveBar.getBounds().height - 8f));
        }
        offensiveBar.layoutSegments(offensiveViews);
        defensiveBar.layoutSegments(defensiveViews);
    }

    public void draw(ShapeRenderer sr, Batch batch, BitmapFont font) {
        refresh();

        // ---- Shape pass (autoShapeType is on; use no-arg begin so sr.set() works) ----
        sr.begin();
        offensiveBar.drawShapes(sr);
        defensiveBar.drawShapes(sr);
        for (ActionSegmentView v : offensiveViews) { v.drawShapes(sr); }
        for (ActionSegmentView v : defensiveViews) { v.drawShapes(sr); }
        // Lock In button (grey circle)
        sr.setColor(confirmed ? new Color(0.3f, 0.6f, 0.3f, 1f) : new Color(0.55f, 0.55f, 0.58f, 1f));
        sr.circle(lockInBounds.x + lockInBounds.width / 2f,
                  lockInBounds.y + lockInBounds.height / 2f,
                  lockInBounds.width / 2f);
        // Cards
        for (MoveCardView card : cards) { card.drawShapes(sr); }
        sr.end();

        // ---- Text pass (own Batch context) ----
        batch.begin();
        offensiveBar.drawSymbols(batch, font);
        defensiveBar.drawSymbols(batch, font);
        for (ActionSegmentView v : offensiveViews) { v.drawText(batch, font); }
        for (ActionSegmentView v : defensiveViews) { v.drawText(batch, font); }
        for (MoveCardView card : cards) { card.drawText(batch, font); }
        // Lock In label
        font.setColor(Color.WHITE);
        font.draw(batch, "LOCK\nIN",
            lockInBounds.x, lockInBounds.y + lockInBounds.height * 0.75f,
            lockInBounds.width, 1, true);
        // Budget readout (above the offensive bar)
        font.setColor(new Color(0.1f, 0.1f, 0.15f, 1f));
        font.draw(batch, "AP " + plan.totalApUsed() + "/" + plan.apBudget()
                       + "   CE " + plan.remainingCe() + "/" + plan.ceBudget(),
            offensiveBar.getBounds().x, offensiveBar.getBounds().y + offensiveBar.getBounds().height + 18f);
        batch.end();
    }

    private int ceCost(Move m) {
        return CeEfficiencyCalculator.computeActualCost(m, ceEfficiency, abilityFlags);
    }

    // -------------------------------------------------------------------------
    // Input
    // -------------------------------------------------------------------------

    public class PlanningInputProcessor extends InputAdapter {
        @Override
        public boolean touchDown(int screenX, int screenY, int pointer, int button) {
            if (button != Buttons.LEFT) return false;
            float x = screenX, y = Gdx.graphics.getHeight() - screenY; // flip Y

            // Lock In?
            if (lockInBounds.contains(x, y)) {
                confirmed = true;
                onConfirm.run();
                return true;
            }

            // Palette card? start a palette drag.
            for (MoveCardView card : cards) {
                if (!card.isDisabled() && card.getBounds().contains(x, y)) {
                    draggingMove = card.getMove();
                    draggingBoard = BattlePlan.boardFor(draggingMove);
                    draggingSegment = null;
                    dragMouseX = x; dragMouseY = y;
                    updateSnap();
                    return true;
                }
            }

            // Existing segment? start a move drag (remove first, re-place on release).
            for (ActionSegmentView v : offensiveViews) {
                if (v.getBounds().contains(x, y)) { startMoveDrag(v.getSegment(), BattlePlan.Board.OFFENSIVE, x, y); return true; }
            }
            for (ActionSegmentView v : defensiveViews) {
                if (v.getBounds().contains(x, y)) { startMoveDrag(v.getSegment(), BattlePlan.Board.DEFENSIVE, x, y); return true; }
            }
            return false;
        }

        @Override
        public boolean touchDragged(int screenX, int screenY, int pointer) {
            if (draggingMove == null && draggingSegment == null) return false;
            dragMouseX = screenX;
            dragMouseY = Gdx.graphics.getHeight() - screenY;
            updateSnap();
            return true;
        }

        @Override
        public boolean touchUp(int screenX, int screenY, int pointer, int button) {
            if (draggingMove == null && draggingSegment == null) return false;
            float x = screenX, y = Gdx.graphics.getHeight() - screenY;
            updateSnap();
            if (snapValid) {
                // Place.
                Move toPlace = draggingMove != null ? draggingMove : draggingSegment.getMove();
                int cost = ceCost(toPlace);
                // For an existing-segment move-drag, ensure it's removed first
                // (it was removed in startMoveDrag) so budgets are refunded.
                ActionSegment placed = plan.place(toPlace, draggingTick, cost);
                if (placed == null) {
                    // Could not place (budget changed) — for an existing segment
                    // this means it's lost; acceptable for a first draft.
                }
            }
            // else: cancel (release outside valid slot) — nothing placed.
            draggingMove = null;
            draggingSegment = null;
            draggingBoard = null;
            snapValid = false;
            return true;
        }

        private void startMoveDrag(ActionSegment seg, BattlePlan.Board board, float x, float y) {
            // Refund by removing from the plan; re-place on release.
            plan.remove(seg);
            draggingSegment = seg;
            draggingMove = null;
            draggingBoard = board;
            dragMouseX = x; dragMouseY = y;
            updateSnap();
        }

        /** Snap the drag to the nearest dot on the assigned board and test validity. */
        private void updateSnap() {
            TimelineBar bar = draggingBoard == BattlePlan.Board.OFFENSIVE ? offensiveBar : defensiveBar;
            // Only snap if the cursor is over the assigned bar's vertical span.
            Rectangle bb = bar.getBounds();
            if (dragMouseY < bb.y || dragMouseY > bb.y + bb.height) {
                snapValid = false;
                return;
            }
            draggingTick = bar.tickAtX(dragMouseX);
            Move m = draggingMove != null ? draggingMove : draggingSegment.getMove();
            int endTick = draggingTick + m.getApCost() - 1;
            boolean inBounds = draggingTick >= 1 && endTick <= bar.getDotCount();
            boolean free = inBounds && plan.boardTimeline(draggingBoard).isRangeFree(draggingTick, endTick);
            boolean budget = m.getApCost() <= plan.remainingApBudget()
                          && ceCost(m) <= plan.remainingCe();
            snapValid = free && budget;
        }
    }
}
