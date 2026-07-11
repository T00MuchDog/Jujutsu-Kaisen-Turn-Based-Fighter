package com.jjktbf.graphics.ui.battle;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Buttons;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.CeEfficiencyCalculator;
import com.jjktbf.model.move.Move;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen round planner with two discrete action timelines and a move-card
 * dock. All placement remains owned by {@link BattlePlan}; this class only maps
 * mouse input to a snapped board position and renders the draft.
 */
public class PlanningPanel {

    private static final float MARGIN = 34f;
    private static final float CARD_GAP = 10f;

    private final BattlePlan plan;
    private final List<Move> knownMoves = new ArrayList<>();
    private final int ceEfficiency;
    private final com.jjktbf.model.character.AbilityApplicator.AbilityFlags abilityFlags;
    private final BattleUiAssets ui;

    private final TimelineBar offensiveBar = new TimelineBar(TimelineBar.Kind.OFFENSIVE, 0f, 0f, 1f, 1f);
    private final TimelineBar defensiveBar = new TimelineBar(TimelineBar.Kind.DEFENSIVE, 0f, 0f, 1f, 1f);
    private final List<MoveCardView> cards = new ArrayList<>();
    private final List<ActionSegmentView> offensiveViews = new ArrayList<>();
    private final List<ActionSegmentView> defensiveViews = new ArrayList<>();
    private final Rectangle headerBounds = new Rectangle();
    private final Rectangle paletteBounds = new Rectangle();
    private final Rectangle lockInBounds = new Rectangle();

    private float screenWidth;
    private float screenHeight;
    private boolean compactLayout;

    private Move draggingMove;
    private ActionSegment draggingSegment;
    private BattlePlan.Board draggingBoard;
    private int originalTick;
    private int originalCeCost;
    private int draggingTick;
    private boolean snapValid;
    private float dragMouseX;
    private float dragMouseY;

    private ActionSegment hoveredSegment;
    private ActionSegment selectedSegment;
    private int hoveredCard = -1;
    private boolean lockHovered;
    private boolean confirmed;
    private Runnable onConfirm = () -> {};

    public PlanningPanel(BattleCombatant combatant, BattleUiAssets ui, float screenWidth, float screenHeight) {
        this.plan = new BattlePlan(combatant.getMaxApBar(), combatant.getCurrentCe());
        this.ceEfficiency = combatant.getEffectiveStats().getCursedEnergyEfficiency();
        this.abilityFlags = combatant.getAbilityFlags();
        this.ui = ui;
        for (Move move : combatant.getCharacter().getKnownMoves()) {
            if (abilityFlags.lockedMoveTags.stream().noneMatch(move::hasTag)) knownMoves.add(move);
        }
        resize(screenWidth, screenHeight);
    }

    public void setOnConfirm(Runnable onConfirm) {
        this.onConfirm = onConfirm == null ? () -> {} : onConfirm;
    }

    public BattlePlan getPlan() { return plan; }
    public boolean isConfirmed() { return confirmed; }

    public PlanningInputProcessor inputProcessor() {
        return new PlanningInputProcessor();
    }

    /** Reflows the full-screen workspace after a window resize. */
    public void resize(float width, float height) {
        screenWidth = width;
        screenHeight = height;
        compactLayout = width < 700f;

        float margin = Math.min(MARGIN, Math.max(18f, width * 0.045f));
        float headerH = compactLayout ? 88f : 58f;
        headerBounds.set(margin, height - margin - headerH, width - margin * 2f, headerH);
        if (compactLayout) {
            lockInBounds.set(headerBounds.x + headerBounds.width - 104f, headerBounds.y + 48f, 92f, 28f);
        } else {
            lockInBounds.set(headerBounds.x + headerBounds.width - 156f, headerBounds.y + 10f, 142f, headerH - 20f);
        }

        int columns = Math.max(1, (int) ((width - margin * 2f + CARD_GAP) / (MoveCardView.CARD_W + CARD_GAP)));
        int rows = Math.max(1, (int) Math.ceil(knownMoves.size() / (double) columns));
        float paletteHeight = 20f + rows * MoveCardView.CARD_H + (rows - 1) * CARD_GAP;
        paletteBounds.set(margin, margin, width - margin * 2f, paletteHeight);
        buildPalette(columns);

        float labelWidth = compactLayout ? 0f : Math.min(150f, Math.max(108f, width * 0.12f));
        float timelineX = margin + labelWidth;
        float timelineW = width - timelineX - margin;
        float timelineH = Math.min(84f, Math.max(54f, height * 0.115f));
        float boardAreaBottom = paletteBounds.y + paletteBounds.height + 26f;
        float boardAreaTop = headerBounds.y - 24f;
        float boardGap = compactLayout ? 30f : 18f;
        float boardGroupHeight = timelineH * 2f + boardGap;
        float defensiveY = boardAreaBottom + Math.max(0f, (boardAreaTop - boardAreaBottom - boardGroupHeight) / 2f);
        defensiveBar.setBounds(timelineX, defensiveY, timelineW, timelineH);
        offensiveBar.setBounds(timelineX, defensiveY + timelineH + boardGap, timelineW, timelineH);
    }

    private void buildPalette(int columns) {
        cards.clear();
        int rows = Math.max(1, (int) Math.ceil(knownMoves.size() / (double) columns));
        for (int i = 0; i < knownMoves.size(); i++) {
            int row = i / columns;
            int column = i % columns;
            float x = paletteBounds.x + 10f + column * (MoveCardView.CARD_W + CARD_GAP);
            float y = paletteBounds.y + 10f + (rows - row - 1) * (MoveCardView.CARD_H + CARD_GAP);
            cards.add(new MoveCardView(knownMoves.get(i), x, y));
        }
    }

    private void refresh() {
        for (int i = 0; i < cards.size(); i++) {
            MoveCardView card = cards.get(i);
            Move move = card.getMove();
            card.setDisabled(!plan.fitsBudgets(move, ceCost(move)));
            card.setHovered(i == hoveredCard);
            card.setDragging(move == draggingMove);
        }

        offensiveViews.clear();
        defensiveViews.clear();
        for (ActionSegment segment : plan.offensiveTimeline().getSegments()) {
            offensiveViews.add(new ActionSegmentView(segment, 0f, 0f, 0f, offensiveBar.getBounds().height - 12f));
        }
        for (ActionSegment segment : plan.defensiveTimeline().getSegments()) {
            defensiveViews.add(new ActionSegmentView(segment, 0f, 0f, 0f, defensiveBar.getBounds().height - 12f));
        }
        offensiveBar.layoutSegments(offensiveViews);
        defensiveBar.layoutSegments(defensiveViews);
        for (ActionSegmentView view : offensiveViews) {
            view.setHighlighted(view.getSegment() == hoveredSegment || view.getSegment() == selectedSegment);
        }
        for (ActionSegmentView view : defensiveViews) {
            view.setHighlighted(view.getSegment() == hoveredSegment || view.getSegment() == selectedSegment);
        }
    }

    public void draw(Batch batch, BitmapFont font, BitmapFont titleFont) {
        refresh();
        batch.begin();
        drawHeader(batch, font, titleFont);
        drawTimelineLabel(batch, font, offensiveBar, "OFFENSE", "ATTACK MOVES", ui.offenseIcon, BattleUiAssets.OFFENSE);
        drawTimelineLabel(batch, font, defensiveBar, "DEFENSE", "UTILITY + BLOCK", ui.defenseIcon, BattleUiAssets.DEFENSE);

        offensiveBar.draw(batch, ui, isDropTarget(BattlePlan.Board.OFFENSIVE));
        defensiveBar.draw(batch, ui, isDropTarget(BattlePlan.Board.DEFENSIVE));
        for (ActionSegmentView view : offensiveViews) view.draw(batch, font, ui);
        for (ActionSegmentView view : defensiveViews) view.draw(batch, font, ui);

        ui.palette.draw(batch, paletteBounds.x, paletteBounds.y, paletteBounds.width, paletteBounds.height);
        for (MoveCardView card : cards) card.draw(batch, font, ui, ceCost(card.getMove()));
        drawDragAvatar(batch, font);
        batch.end();
    }

    private void drawHeader(Batch batch, BitmapFont font, BitmapFont titleFont) {
        ui.header.draw(batch, headerBounds.x, headerBounds.y, headerBounds.width, headerBounds.height);
        titleFont.setColor(Color.WHITE);
        titleFont.draw(batch, compactLayout ? "PLAN ROUND" : "BUILD YOUR TIMELINE",
            headerBounds.x + 18f, headerBounds.y + (compactLayout ? 76f : 39f));
        float statX = compactLayout ? headerBounds.x + 12f : headerBounds.x + Math.min(340f, headerBounds.width * 0.42f);
        float statWidth = compactLayout ? 82f : 104f;
        drawStat(batch, font, statX, headerBounds.y + (compactLayout ? 12f : 15f), statWidth, "AP", plan.totalApUsed() + "/" + plan.apBudget());
        drawStat(batch, font, statX + statWidth + 8f, headerBounds.y + (compactLayout ? 12f : 15f),
            compactLayout ? 82f : 108f, "CE", plan.totalCeUsed() + "/" + plan.ceBudget());

        if (confirmed) {
            ui.lockButtonDisabled.draw(batch, lockInBounds.x, lockInBounds.y, lockInBounds.width, lockInBounds.height);
        } else if (lockHovered) {
            ui.lockButtonOver.draw(batch, lockInBounds.x, lockInBounds.y, lockInBounds.width, lockInBounds.height);
        } else {
            ui.lockButton.draw(batch, lockInBounds.x, lockInBounds.y, lockInBounds.width, lockInBounds.height);
        }
        font.setColor(Color.WHITE);
        font.draw(batch, confirmed ? (compactLayout ? "LOCKED" : "PLAN LOCKED") : (compactLayout ? "LOCK" : "LOCK IN"),
            lockInBounds.x + (compactLayout ? 17f : 22f), lockInBounds.y + (compactLayout ? 19f : 25f));
    }

    private void drawStat(Batch batch, BitmapFont font, float x, float y, float width, String label, String value) {
        ui.statPill.draw(batch, x, y, width, 29f);
        font.setColor(BattleUiAssets.MUTED);
        font.draw(batch, label, x + 8f, y + 19f);
        font.setColor(BattleUiAssets.TEXT);
        font.draw(batch, value, x + 31f, y + 19f);
    }

    private void drawTimelineLabel(Batch batch, BitmapFont font, TimelineBar bar, String label, String detail,
                                   com.badlogic.gdx.graphics.Texture icon, Color color) {
        Rectangle bounds = bar.getBounds();
        float x = compactLayout ? bounds.x + 4f : headerBounds.x + 4f;
        float y = compactLayout ? bounds.y + bounds.height + 9f : bounds.y + bounds.height / 2f;
        batch.draw(icon, x, y - 8f, 16f, 16f);
        font.setColor(color);
        font.draw(batch, label, x + 23f, y + 8f);
        if (!compactLayout) {
            font.setColor(BattleUiAssets.MUTED);
            font.draw(batch, detail, x + 23f, y - 7f);
        }
    }

    private void drawDragAvatar(Batch batch, BitmapFont font) {
        Move move = draggedMove();
        if (move == null) return;

        TimelineBar bar = barFor(draggingBoard);
        Rectangle barBounds = bar.getBounds();
        boolean overTrack = barBounds.contains(dragMouseX, dragMouseY);
        float width = overTrack ? bar.segmentWidth(move.getApCost()) : Math.max(132f, bar.segmentWidth(move.getApCost()));
        float height = overTrack ? barBounds.height - 12f : 48f;
        float x = overTrack ? bar.segmentLeft(draggingTick) : dragMouseX - width / 2f;
        float y = overTrack ? barBounds.y + 6f : dragMouseY - height / 2f;
        ActionSegmentView ghost = new ActionSegmentView(move, x, y, width, height);
        ghost.setHighlighted(true);
        ghost.draw(batch, font, ui);
    }

    private int ceCost(Move move) {
        return CeEfficiencyCalculator.computeActualCost(move, ceEfficiency, abilityFlags);
    }

    private TimelineBar barFor(BattlePlan.Board board) {
        return board == BattlePlan.Board.OFFENSIVE ? offensiveBar : defensiveBar;
    }

    private Move draggedMove() {
        return draggingMove != null ? draggingMove : draggingSegment == null ? null : draggingSegment.getMove();
    }

    private boolean isDropTarget(BattlePlan.Board board) {
        return draggedMove() != null && draggingBoard == board && barFor(board).getBounds().contains(dragMouseX, dragMouseY);
    }

    public class PlanningInputProcessor extends InputAdapter {
        @Override
        public boolean touchDown(int screenX, int screenY, int pointer, int button) {
            if (button != Buttons.LEFT || confirmed) return false;
            updatePointer(screenX, screenY);

            if (lockInBounds.contains(dragMouseX, dragMouseY)) {
                confirmed = true;
                onConfirm.run();
                return true;
            }

            for (int i = 0; i < cards.size(); i++) {
                MoveCardView card = cards.get(i);
                if (!card.isDisabled() && card.getBounds().contains(dragMouseX, dragMouseY)) {
                    selectedSegment = null;
                    draggingMove = card.getMove();
                    draggingSegment = null;
                    draggingBoard = BattlePlan.boardFor(draggingMove);
                    updateSnap();
                    return true;
                }
            }

            ActionSegmentView hit = hitSegment();
            if (hit != null) {
                selectedSegment = hit.getSegment();
                startMoveDrag(hit.getSegment(), BattlePlan.boardFor(hit.getMove()));
                return true;
            }

            selectedSegment = null;
            return false;
        }

        @Override
        public boolean touchDragged(int screenX, int screenY, int pointer) {
            if (draggedMove() == null) return false;
            updatePointer(screenX, screenY);
            updateSnap();
            return true;
        }

        @Override
        public boolean touchUp(int screenX, int screenY, int pointer, int button) {
            if (button != Buttons.LEFT || draggedMove() == null) return false;
            updatePointer(screenX, screenY);
            updateSnap();

            Move move = draggedMove();
            ActionSegment placed = snapValid ? plan.place(move, draggingTick, ceCost(move)) : null;
            if (placed != null) {
                selectedSegment = placed;
            } else if (draggingSegment != null) {
                // A cancelled relocation must never destroy an already planned move.
                selectedSegment = plan.place(draggingSegment.getMove(), originalTick, originalCeCost);
            }
            clearDrag();
            return true;
        }

        @Override
        public boolean mouseMoved(int screenX, int screenY) {
            updatePointer(screenX, screenY);
            updateHover();
            return hoveredCard >= 0 || hoveredSegment != null || lockHovered;
        }

        private void startMoveDrag(ActionSegment segment, BattlePlan.Board board) {
            originalTick = segment.getStartTick();
            originalCeCost = segment.getActualCeCost();
            plan.remove(segment);
            draggingSegment = segment;
            draggingMove = null;
            draggingBoard = board;
            updateSnap();
        }

        private void clearDrag() {
            draggingMove = null;
            draggingSegment = null;
            draggingBoard = null;
            snapValid = false;
            updateHover();
        }

        private void updatePointer(int screenX, int screenY) {
            dragMouseX = screenX;
            dragMouseY = Gdx.graphics.getHeight() - screenY;
        }

        private void updateSnap() {
            Move move = draggedMove();
            if (move == null || draggingBoard == null) {
                snapValid = false;
                return;
            }
            TimelineBar bar = barFor(draggingBoard);
            Rectangle bounds = bar.getBounds();
            if (!bounds.contains(dragMouseX, dragMouseY)) {
                snapValid = false;
                return;
            }
            draggingTick = bar.tickAtX(dragMouseX);
            int endTick = draggingTick + move.getApCost() - 1;
            snapValid = endTick <= bar.getDotCount()
                && plan.boardTimeline(draggingBoard).isRangeFree(draggingTick, endTick)
                && plan.fitsBudgets(move, ceCost(move));
        }

        private void updateHover() {
            hoveredCard = -1;
            lockHovered = !confirmed && lockInBounds.contains(dragMouseX, dragMouseY);
            for (int i = 0; i < cards.size(); i++) {
                if (cards.get(i).getBounds().contains(dragMouseX, dragMouseY)) {
                    hoveredCard = i;
                    break;
                }
            }
            ActionSegmentView hit = hitSegment();
            hoveredSegment = hit == null ? null : hit.getSegment();
        }

        private ActionSegmentView hitSegment() {
            for (ActionSegmentView view : offensiveViews) {
                if (view.getBounds().contains(dragMouseX, dragMouseY)) return view;
            }
            for (ActionSegmentView view : defensiveViews) {
                if (view.getBounds().contains(dragMouseX, dragMouseY)) return view;
            }
            return null;
        }
    }
}
