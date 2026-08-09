package com.jjktbf.graphics.ui.battle;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Buttons;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.Align;
import com.jjktbf.graphics.audio.SoundCue;
import com.jjktbf.graphics.ui.MiraclesMeter;
import com.jjktbf.graphics.ui.text.KeywordPopupPosition;
import com.jjktbf.graphics.ui.text.KeywordTextLayout;
import com.jjktbf.model.character.coded.CodedAbilityState;
import com.jjktbf.model.character.coded.MiraclesAbility;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.CeEfficiencyCalculator;
import com.jjktbf.model.combat.CombatantId;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.MoveAvailability;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.Move;
import com.jjktbf.multiplayer.protocol.PlanPlacement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Full-screen round planner with two discrete action timelines and a move-card
 * dock. All placement remains owned by {@link BattlePlan}; this class only maps
 * mouse input to a snapped board position and renders the draft.
 */
public class PlanningPanel {

    public record TargetOption(String instanceId, String label) { }

    private static final float MARGIN = 34f;
    /** Fixed gap between a timeline icon and the left edge of its bar. */
    private static final float LABEL_LEFT_GAP = 8f;
    private static final float TIMELINE_ICON_SIZE = 16f;
    private static final float TIMELINE_LABEL_GAP = 7f;
    private static final float CARD_GAP = 10f;
    private static final float PALETTE_PADDING = 10f;
    private static final float SCROLLBAR_HEIGHT = 6f;
    private static final float SCROLLBAR_MIN_THUMB_WIDTH = 28f;
    private static final float PALETTE_SCROLL_SPEED = 0.2f;
    private static final float PALETTE_SCROLL_SMOOTHING = 12f;
    private static final int PALETTE_COLUMNS = 5;
    private static final int PALETTE_ROWS = 2;
    private static final int PALETTE_PAGE_SIZE = PALETTE_COLUMNS * PALETTE_ROWS;
    private static final float DRAG_THRESHOLD = 5f;

    private final BattlePlan plan;
    private final String actorId;
    private final List<TargetOption> targetOptions;
    private final List<Move> knownMoves = new ArrayList<>();
    private final int ceEfficiency;
    private final com.jjktbf.model.character.AbilityApplicator.AbilityFlags abilityFlags;
    private final Map<String, Integer> authoritativeCeCosts;
    private final BattleCombatant localCombatant;
    private final Integer maxActiveSummons;
    private final int activeSummonCount;
    private BattleState localBattleState;
    private Map<String, String> moveRestrictions = Map.of();
    private final BattleUiAssets ui;
    private final MiraclesMeter miraclesMeter = new MiraclesMeter();

    /**
     * Battle-wide timeline grid length (dot count), derived from the stronger
     * fighter's AP tier. Drives the bars' dot count and the tier-scaled width.
     */
    private final int gridLength;
    private TimelineBar offensiveBar;
    private TimelineBar defensiveBar;
    private final List<MoveCardView> cards = new ArrayList<>();
    private final List<ActionSegmentView> offensiveViews = new ArrayList<>();
    private final List<ActionSegmentView> defensiveViews = new ArrayList<>();
    private final Rectangle headerBounds = new Rectangle();
    private final Rectangle paletteBounds = new Rectangle();
    private final Rectangle paletteViewportBounds = new Rectangle();
    private final Rectangle paletteScrollTrackBounds = new Rectangle();
    private final Rectangle paletteScrollThumbBounds = new Rectangle();
    private final Rectangle lockInBounds = new Rectangle();

    private float screenWidth;
    private float screenHeight;
    private boolean compactLayout;
    private float paletteContentWidth;
    private float paletteScrollX;
    private float paletteScrollTargetX;
    private float paletteScrollMax;
    private boolean draggingPaletteScrollbar;
    private float scrollbarDragStartX;
    private float scrollbarDragStartOffset;

    private Move draggingMove;
    private ActionSegment draggingSegment;
    private BattlePlan.Board draggingBoard;
    private int originalTick;
    private int originalCeCost;
    private CombatantId originalTarget;
    private int draggingTick;
    private boolean snapValid;
    private boolean clickingMoveCard;
    private boolean dragSoundPlayed;
    private ActionSegment pressedSegment;
    private BattlePlan.Board pressedBoard;
    private float pressMouseX;
    private float pressMouseY;
    private float dragMouseX;
    private float dragMouseY;

    private ActionSegment hoveredSegment;
    private ActionSegment selectedSegment;
    private int hoveredCard = -1;
    private boolean lockHovered;
    private boolean confirmed;
    private boolean allowManualUnlock;
    private String lockError;
    private ActionSegment targetMenuSegment;
    private final List<Rectangle> targetOptionBounds = new ArrayList<>();
    private Runnable onConfirm = () -> {};
    private Consumer<SoundCue> soundPlayer = cue -> {};

    public PlanningPanel(BattleCombatant combatant, BattleUiAssets ui, float screenWidth, float screenHeight) {
        this(Timeline.gridLengthForStrongestAp(combatant.getMaxApBar()), combatant,
            List.of(), ui, screenWidth, screenHeight);
    }

    /**
     * Builds the planner for a fight whose battle-wide grid length is known
     * ({@code Timeline.gridLengthForStrongestAp(max(player, enemy) AP)}). Local
     * play passes it in so the human's bar matches the AI's bar; the simpler
     * constructor above derives it from the combatant alone.
     */
    public PlanningPanel(
        int gridLength,
        BattleCombatant combatant,
        BattleUiAssets ui,
        float screenWidth,
        float screenHeight
    ) {
        this(gridLength, combatant, List.of(), ui, screenWidth, screenHeight);
    }

    public PlanningPanel(
        int gridLength,
        BattleCombatant combatant,
        List<BattleCombatant> targets,
        BattleUiAssets ui,
        float screenWidth,
        float screenHeight
    ) {
        this.gridLength = gridLength;
        this.plan = new BattlePlan(combatant.getMaxApBar(), combatant.getCurrentCe(), gridLength);
        this.actorId = combatant.getInstanceId() == null
            ? null : combatant.getInstanceId().value();
        this.targetOptions = targetOptions(targets);
        this.ceEfficiency = combatant.getEffectiveStats().getCursedEnergyEfficiency();
        this.abilityFlags = combatant.getAbilityFlags();
        this.authoritativeCeCosts = Map.of();
        this.localCombatant = combatant;
        this.maxActiveSummons = combatant.getAbilityFlags().maxActiveSummons;
        this.activeSummonCount = 0;
        this.ui = ui;
        miraclesMeter.setState(findMiraclesState(combatant.getCodedAbilities().states()));
        knownMoves.addAll(combatant.getCharacter().getKnownMoves());
        createBars();
        resize(screenWidth, screenHeight);
    }

    /**
     * Builds the same planner from server-declared online moves and budgets.
     * The server remains authoritative; this panel only creates placement intent.
     */
    public PlanningPanel(
        List<Move> moves,
        Map<String, Integer> ceCosts,
        int apBudget,
        int ceBudget,
        CodedAbilityState miraclesState,
        BattleUiAssets ui,
        float screenWidth,
        float screenHeight
    ) {
        this(Timeline.gridLengthForStrongestAp(apBudget),
            null, List.of(), moves, ceCosts, apBudget, ceBudget, miraclesState,
            ui, screenWidth, screenHeight);
    }

    /** Online planner with an explicit battle-wide grid length (see local overload). */
    public PlanningPanel(
        int gridLength,
        List<Move> moves,
        Map<String, Integer> ceCosts,
        int apBudget,
        int ceBudget,
        CodedAbilityState miraclesState,
        BattleUiAssets ui,
        float screenWidth,
        float screenHeight
    ) {
        this(gridLength, null, List.of(), moves, ceCosts, apBudget, ceBudget,
            miraclesState, null, 0, ui, screenWidth, screenHeight);
    }

    public PlanningPanel(
        int gridLength,
        String actorId,
        List<TargetOption> targetOptions,
        List<Move> moves,
        Map<String, Integer> ceCosts,
        int apBudget,
        int ceBudget,
        CodedAbilityState miraclesState,
        BattleUiAssets ui,
        float screenWidth,
        float screenHeight
    ) {
        this(gridLength, actorId, targetOptions, moves, ceCosts, apBudget, ceBudget,
            miraclesState, null, 0, ui, screenWidth, screenHeight);
    }

    public PlanningPanel(
        int gridLength,
        String actorId,
        List<TargetOption> targetOptions,
        List<Move> moves,
        Map<String, Integer> ceCosts,
        int apBudget,
        int ceBudget,
        CodedAbilityState miraclesState,
        Integer maxActiveSummons,
        int activeSummonCount,
        BattleUiAssets ui,
        float screenWidth,
        float screenHeight
    ) {
        this.gridLength = gridLength;
        this.plan = new BattlePlan(apBudget, ceBudget, gridLength);
        this.actorId = actorId;
        this.targetOptions = targetOptions == null ? List.of() : List.copyOf(targetOptions);
        this.ceEfficiency = 0;
        this.abilityFlags = null;
        this.authoritativeCeCosts = ceCosts == null ? Map.of() : Map.copyOf(ceCosts);
        this.localCombatant = null;
        this.maxActiveSummons = maxActiveSummons;
        this.activeSummonCount = Math.max(0, activeSummonCount);
        this.ui = ui;
        miraclesMeter.setState(miraclesState);
        if (moves != null) knownMoves.addAll(moves);
        createBars();
        resize(screenWidth, screenHeight);
    }

    public void setOnConfirm(Runnable onConfirm) {
        this.onConfirm = onConfirm == null ? () -> {} : onConfirm;
    }

    public void setSoundPlayer(Consumer<SoundCue> soundPlayer) {
        this.soundPlayer = soundPlayer == null ? cue -> {} : soundPlayer;
    }

    public void setBattleState(BattleState state) {
        this.localBattleState = state;
    }

    public void setMoveRestrictions(Map<String, String> restrictions) {
        this.moveRestrictions = restrictions == null ? Map.of() : Map.copyOf(restrictions);
    }

    /** Builds the two bars at the fight's battle-wide grid length (placeholder bounds; set in {@link #resize}). */
    private void createBars() {
        offensiveBar = new TimelineBar(TimelineBar.Kind.OFFENSIVE, 0f, 0f, 1f, 1f, gridLength);
        defensiveBar = new TimelineBar(TimelineBar.Kind.DEFENSIVE, 0f, 0f, 1f, 1f, gridLength);
    }

    public BattlePlan getPlan() { return plan; }
    public boolean isConfirmed() { return confirmed; }
    public String getActorId() { return actorId; }
    public String getLockError() { return lockError; }
    public List<TargetOption> getTargetOptions() { return targetOptions; }

    public void setAllowManualUnlock(boolean allowManualUnlock) {
        this.allowManualUnlock = allowManualUnlock;
    }

    /** Returns server-safe intent without exposing local domain objects. */
    public List<PlanPlacement> getPlacements() {
        return plan.allSegments().stream()
            .map(segment -> new PlanPlacement(
                segment.getMove().getId(),
                segment.getStartTick(),
                actorId,
                segment.getTarget() == null ? null : segment.getTarget().value()))
            .toList();
    }

    /** Reopens a locally rejected online plan without discarding its placements. */
    public void unlock() {
        confirmed = false;
        lockError = null;
    }

    /** Displays an already accepted authoritative plan as immutable. */
    public void lock() {
        cancelActiveDrag();
        confirmed = true;
    }

    private void cancelActiveDrag() {
        if (draggingSegment != null) {
            selectedSegment = plan.place(
                draggingSegment.getMove(), originalTick, originalCeCost, originalTarget);
        }
        draggingMove = null;
        draggingSegment = null;
        draggingBoard = null;
        pressedSegment = null;
        pressedBoard = null;
        draggingPaletteScrollbar = false;
        snapValid = false;
        clickingMoveCard = false;
        dragSoundPlayed = false;
        originalTarget = null;
    }

    public boolean chooseTarget(ActionSegment segment, String targetId) {
        if (segment == null || !segment.needsTarget()) return false;
        boolean valid = targetOptions.stream().anyMatch(option -> option.instanceId().equals(targetId));
        if (!valid) return false;
        segment.setTarget(new CombatantId(targetId));
        lockError = null;
        closeTargetMenu();
        return true;
    }

    private CombatantId defaultTarget(Move move) {
        if (!BattlePlan.requiresTarget(move) || targetOptions.isEmpty()) return null;
        return new CombatantId(targetOptions.get(0).instanceId());
    }

    public ActionSegment restorePlacement(Move move, int startTick, int ceCost, String targetId) {
        return plan.place(move, startTick, ceCost,
            targetId == null ? null : new CombatantId(targetId));
    }

    private static List<TargetOption> targetOptions(List<BattleCombatant> targets) {
        if (targets == null) return List.of();
        List<TargetOption> options = new ArrayList<>();
        for (BattleCombatant target : targets) {
            if (target == null || target.getInstanceId() == null || !target.isActive()) continue;
            options.add(new TargetOption(
                target.getInstanceId().value(),
                target.getCharacter().getName() + " #" + (target.getRosterOrder() + 1)));
        }
        return List.copyOf(options);
    }

    public PlanningInputProcessor inputProcessor() {
        return new PlanningInputProcessor();
    }

    /** Reflows the full-screen workspace after a window resize. */
    public void resize(float width, float height) {
        screenWidth = width;
        screenHeight = height;
        compactLayout = width < 700f;

        float margin = Math.min(MARGIN, Math.max(18f, width * 0.045f));
        float headerH = compactLayout ? 118f : 58f;
        headerBounds.set(margin, height - margin - headerH, width - margin * 2f, headerH);
        if (compactLayout) {
            lockInBounds.set(headerBounds.x + headerBounds.width - 104f, headerBounds.y + 78f, 92f, 28f);
        } else {
            lockInBounds.set(headerBounds.x + headerBounds.width - 156f, headerBounds.y + 10f, 142f, headerH - 20f);
        }

        int rows = paletteRowCount(knownMoves.size());
        float paletteHeight = 20f + rows * MoveCardView.CARD_H + (rows - 1) * CARD_GAP;
        float availablePaletteWidth = Math.max(1f, width - margin * 2f);
        float paletteWidth = Math.min(availablePaletteWidth, preferredPaletteWidth());
        paletteBounds.set((width - paletteWidth) / 2f, margin, paletteWidth, paletteHeight);
        buildPalette(rows);

        float labelWidth = compactLayout ? 0f : Math.min(150f, Math.max(108f, width * 0.12f));
        float timelineH = MiraclesMeter.timelineHeightForViewport(height);
        float boardAreaBottom = paletteBounds.y + paletteBounds.height + 26f;
        float boardAreaTop = headerBounds.y - 24f;
        if (miraclesMeter.isVisible()) {
            float miracleSize = MiraclesMeter.sizeForViewport(height);
            float miracleY = headerBounds.y - 16f - miracleSize;
            miraclesMeter.setBounds(headerBounds.x, miracleY, miracleSize);
            // Compact timeline labels sit above their bars, so leave them a little more clearance.
            boardAreaTop = miracleY - (compactLayout ? 28f : 16f);
        }

        // The bar grows with the fight's AP tier while keeping the dot spacing
        // fixed: dot spacing is calibrated so the original DEFAULT_GRID_LENGTH
        // (150) dots span the full width — exactly the old look. Lower tiers
        // render a shorter slice of that same bar (fewer dots, same spacing),
        // and the top tier (300) clamps to the full width. The bar plus its
        // left-hand label gutter is centered as a group, so the timeline grows
        // outward from the screen centre as the tier rises.
        float maxBarWidth = Math.max(1f, width - margin * 2f - labelWidth);
        float dotSpacing = maxBarWidth / (float) Timeline.DEFAULT_GRID_LENGTH;
        float timelineW = Math.min(maxBarWidth, gridLength * dotSpacing);
        float groupWidth = labelWidth + timelineW;
        float timelineX = (width - groupWidth) / 2f + labelWidth;

        float boardGap = compactLayout ? 30f : 18f;
        float boardGroupHeight = timelineH * 2f + boardGap;
        float defensiveY = boardAreaBottom + Math.max(0f, (boardAreaTop - boardAreaBottom - boardGroupHeight) / 2f);
        defensiveBar.setBounds(timelineX, defensiveY, timelineW, timelineH);
        offensiveBar.setBounds(timelineX, defensiveY + timelineH + boardGap, timelineW, timelineH);
    }

    private void buildPalette(int rows) {
        cards.clear();
        for (int i = 0; i < knownMoves.size(); i++) {
            cards.add(new MoveCardView(knownMoves.get(i), 0f, 0f));
        }

        paletteViewportBounds.set(
            paletteBounds.x + PALETTE_PADDING,
            paletteBounds.y + PALETTE_PADDING,
            Math.max(0f, paletteBounds.width - PALETTE_PADDING * 2f),
            Math.max(0f, paletteBounds.height - PALETTE_PADDING * 2f));
        int columns = paletteColumnCount(knownMoves.size());
        paletteContentWidth = columns == 0
            ? 0f
            : columns * MoveCardView.CARD_W + (columns - 1) * CARD_GAP;
        paletteScrollMax = Math.max(0f, paletteContentWidth - paletteViewportBounds.width);
        paletteScrollX = clamp(paletteScrollX, 0f, paletteScrollMax);
        paletteScrollTargetX = clamp(paletteScrollTargetX, 0f, paletteScrollMax);
        draggingPaletteScrollbar = false;
        layoutPaletteCards(rows);
        layoutPaletteScrollbar();
    }

    private void layoutPaletteCards(int rows) {
        for (int i = 0; i < cards.size(); i++) {
            int row = paletteRow(i);
            int column = paletteColumn(i);
            float x = paletteViewportBounds.x
                + column * (MoveCardView.CARD_W + CARD_GAP) - paletteScrollX;
            float y = paletteViewportBounds.y
                + (rows - row - 1) * (MoveCardView.CARD_H + CARD_GAP);
            cards.get(i).getBounds().setPosition(x, y);
        }
    }

    private void layoutPaletteScrollbar() {
        if (paletteScrollMax <= 0f) {
            paletteScrollTrackBounds.set(0f, 0f, 0f, 0f);
            paletteScrollThumbBounds.set(0f, 0f, 0f, 0f);
            return;
        }

        paletteScrollTrackBounds.set(
            paletteBounds.x + PALETTE_PADDING,
            paletteBounds.y + 2f,
            Math.max(0f, paletteBounds.width - PALETTE_PADDING * 2f),
            SCROLLBAR_HEIGHT);
        float thumbWidth = Math.max(
            SCROLLBAR_MIN_THUMB_WIDTH,
            paletteScrollTrackBounds.width * paletteViewportBounds.width / paletteContentWidth);
        thumbWidth = Math.min(paletteScrollTrackBounds.width, thumbWidth);
        float travel = paletteScrollTrackBounds.width - thumbWidth;
        float progress = paletteScrollMax == 0f ? 0f : paletteScrollX / paletteScrollMax;
        paletteScrollThumbBounds.set(
            paletteScrollTrackBounds.x + travel * progress,
            paletteScrollTrackBounds.y,
            thumbWidth,
            paletteScrollTrackBounds.height);
    }

    private void setPaletteScroll(float value) {
        paletteScrollX = clamp(value, 0f, paletteScrollMax);
        paletteScrollTargetX = paletteScrollX;
        layoutPaletteCards(paletteRowCount(cards.size()));
        layoutPaletteScrollbar();
    }

    private void setPaletteScrollTarget(float value) {
        paletteScrollTargetX = clamp(value, 0f, paletteScrollMax);
    }

    private void updatePaletteScrollAnimation(float delta) {
        float distance = paletteScrollTargetX - paletteScrollX;
        if (Math.abs(distance) < 0.1f) {
            if (distance == 0f) return;
            paletteScrollX = paletteScrollTargetX;
        } else {
            float elapsed = Math.min(Math.max(delta, 0f), 0.05f);
            float blend = 1f - (float) Math.exp(-PALETTE_SCROLL_SMOOTHING * elapsed);
            paletteScrollX += distance * blend;
        }
        layoutPaletteCards(paletteRowCount(cards.size()));
        layoutPaletteScrollbar();
        updateHoveredCard();
    }

    private static float preferredPaletteWidth() {
        return PALETTE_PADDING * 2f
            + PALETTE_COLUMNS * MoveCardView.CARD_W
            + (PALETTE_COLUMNS - 1) * CARD_GAP;
    }

    static int paletteRowCount(int cardCount) {
        return cardCount <= PALETTE_COLUMNS ? 1 : PALETTE_ROWS;
    }

    static int paletteRow(int cardIndex) {
        if (cardIndex < PALETTE_PAGE_SIZE) return cardIndex / PALETTE_COLUMNS;
        return (cardIndex - PALETTE_PAGE_SIZE) % PALETTE_ROWS;
    }

    static int paletteColumn(int cardIndex) {
        if (cardIndex < PALETTE_PAGE_SIZE) return cardIndex % PALETTE_COLUMNS;
        return PALETTE_COLUMNS + (cardIndex - PALETTE_PAGE_SIZE) / PALETTE_ROWS;
    }

    static int paletteColumnCount(int cardCount) {
        if (cardCount <= 0) return 0;
        if (cardCount <= PALETTE_COLUMNS) return cardCount;
        if (cardCount <= PALETTE_PAGE_SIZE) return PALETTE_COLUMNS;
        return PALETTE_COLUMNS
            + (int) Math.ceil((cardCount - PALETTE_PAGE_SIZE) / (double) PALETTE_ROWS);
    }

    private void refresh() {
        for (int i = 0; i < cards.size(); i++) {
            MoveCardView card = cards.get(i);
            Move move = card.getMove();
            boolean restricted = isMoveRestricted(move);
            card.setDisabled(restricted || !plan.canPlace(move, ceCost(move)));
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

    private boolean isMoveRestricted(Move move) {
        if (moveRestrictions.containsKey(move.getId())) return true;
        List<Move> alreadyPlannedMoves = plan.allSegments().stream()
            .map(ActionSegment::getMove)
            .toList();
        if (localCombatant != null) {
            return MoveAvailability.restrictionReason(
                localBattleState, localCombatant, move, alreadyPlannedMoves) != null;
        }
        return MoveAvailability.plannedSummonRestrictionReason(
            move, alreadyPlannedMoves, maxActiveSummons, activeSummonCount) != null;
    }

    public void draw(Batch batch, BitmapFont font, BitmapFont titleFont, BitmapFont statFont) {
        updatePaletteScrollAnimation(Gdx.graphics.getDeltaTime());
        refresh();
        batch.begin();
        drawHeader(batch, font, titleFont);
        miraclesMeter.draw(batch, ui, statFont);
        drawTimelineLabel(batch, font, offensiveBar, "OFFENSE", ui.offenseIcon, BattleUiAssets.OFFENSE);
        drawTimelineLabel(batch, font, defensiveBar, "DEFENSE", ui.defenseIcon, BattleUiAssets.DEFENSE);

        offensiveBar.draw(batch, ui, isDropTarget(BattlePlan.Board.OFFENSIVE));
        defensiveBar.draw(batch, ui, isDropTarget(BattlePlan.Board.DEFENSIVE));
        for (ActionSegmentView view : offensiveViews) view.draw(batch, font, ui);
        for (ActionSegmentView view : defensiveViews) view.draw(batch, font, ui);

        ui.palette.draw(batch, paletteBounds.x, paletteBounds.y, paletteBounds.width, paletteBounds.height);
        beginPaletteClip(batch);
        try {
            for (MoveCardView card : cards) {
                card.draw(batch, titleFont, statFont, ui, ceCost(card.getMove()));
            }
        } finally {
            endPaletteClip(batch);
        }
        drawPaletteScrollbar(batch);
        drawDragAvatar(batch, font);
        drawKeywordTooltip(batch, font, titleFont);
        drawTargetMenu(batch, font);
        batch.end();
    }

    private void beginPaletteClip(Batch batch) {
        batch.flush();
        float scaleX = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
        float scaleY = Gdx.graphics.getBackBufferHeight() / (float) Gdx.graphics.getHeight();
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glScissor(
            Math.round(paletteViewportBounds.x * scaleX),
            Math.round(paletteViewportBounds.y * scaleY),
            Math.round(paletteViewportBounds.width * scaleX),
            Math.round(paletteViewportBounds.height * scaleY));
    }

    private void endPaletteClip(Batch batch) {
        batch.flush();
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
    }

    private void drawPaletteScrollbar(Batch batch) {
        if (paletteScrollMax <= 0f) return;
        batch.setColor(BattleUiAssets.INK);
        batch.draw(
            ui.pixel,
            paletteScrollTrackBounds.x,
            paletteScrollTrackBounds.y,
            paletteScrollTrackBounds.width,
            paletteScrollTrackBounds.height);
        batch.setColor(BattleUiAssets.YELLOW);
        batch.draw(
            ui.pixel,
            paletteScrollThumbBounds.x,
            paletteScrollThumbBounds.y,
            paletteScrollThumbBounds.width,
            paletteScrollThumbBounds.height);
        batch.setColor(Color.WHITE);
    }

    private void drawKeywordTooltip(Batch batch, BitmapFont font, BitmapFont titleFont) {
        if (hoveredCard < 0 || hoveredCard >= cards.size() || draggedMove() != null) return;
        MoveCardView.KeywordHover hover = cards.get(hoveredCard).keywordAt(dragMouseX, dragMouseY);
        if (hover == null) return;

        float popupWidth = Math.max(1f, Math.min(320f, screenWidth - 20f));
        float padding = 12f;
        float contentWidth = Math.max(1f, popupWidth - padding * 2f);
        GlyphLayout heading = new GlyphLayout(
            titleFont,
            hover.text(),
            KeywordTextLayout.KEYWORD_ORANGE,
            contentWidth,
            Align.left,
            true);
        GlyphLayout description = new GlyphLayout(
            font,
            hover.entry().description(),
            BattleUiAssets.TEXT,
            contentWidth,
            Align.left,
            true);
        float popupHeight = padding * 2f + heading.height + 6f + description.height;
        KeywordPopupPosition.Position position = KeywordPopupPosition.place(
            hover.bounds().x,
            hover.bounds().y,
            hover.bounds().width,
            hover.bounds().height,
            popupWidth,
            popupHeight,
            screenWidth,
            screenHeight);

        ui.cardOver.draw(batch, position.x(), position.y(), popupWidth, popupHeight);
        float textTop = position.y() + popupHeight - padding;
        titleFont.draw(batch, heading, position.x() + padding, textTop);
        font.draw(batch, description, position.x() + padding, textTop - heading.height - 6f);
    }

    private void drawHeader(Batch batch, BitmapFont font, BitmapFont titleFont) {
        ui.header.draw(batch, headerBounds.x, headerBounds.y, headerBounds.width, headerBounds.height);
        titleFont.setColor(Color.WHITE);
        titleFont.draw(batch, compactLayout ? "PLAN ROUND" : "BUILD YOUR TIMELINE",
            headerBounds.x + 18f, headerBounds.y + (compactLayout ? 106f : 39f));
        float statX = compactLayout ? headerBounds.x + 12f : headerBounds.x + Math.min(340f, headerBounds.width * 0.42f);
        float statWidth = compactLayout ? 82f : 104f;
        drawStat(batch, font, statX, headerBounds.y + (compactLayout ? 12f : 15f), statWidth,
            "AP", plan.remainingApBudget(), plan.apBudget(), BattleUiAssets.YELLOW);
        drawStat(batch, font, statX + statWidth + 8f, headerBounds.y + (compactLayout ? 12f : 15f),
            compactLayout ? 82f : 108f, "CE", plan.remainingCe(), plan.ceBudget(), BattleUiAssets.CURSED_ENERGY);

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
        if (lockError != null) {
            font.setColor(BattleUiAssets.YELLOW);
            font.draw(batch, lockError, headerBounds.x + 18f, headerBounds.y + 13f);
        }
    }

    private void drawTargetMenu(Batch batch, BitmapFont font) {
        if (targetMenuSegment == null || targetOptions.isEmpty()) return;
        layoutTargetMenu();
        for (int i = 0; i < targetOptions.size(); i++) {
            Rectangle bounds = targetOptionBounds.get(i);
            boolean hovered = bounds.contains(dragMouseX, dragMouseY);
            (hovered ? ui.cardOver : ui.card).draw(
                batch, bounds.x, bounds.y, bounds.width, bounds.height);
            font.setColor(BattleUiAssets.TEXT);
            font.draw(batch, targetOptions.get(i).label(), bounds.x + 8f, bounds.y + 20f);
        }
    }

    private void layoutTargetMenu() {
        targetOptionBounds.clear();
        ActionSegmentView selectedView = viewFor(targetMenuSegment);
        float width = Math.min(240f, Math.max(140f, screenWidth - 20f));
        float rowHeight = 30f;
        float x = selectedView == null ? dragMouseX : selectedView.getBounds().x;
        float y = selectedView == null
            ? dragMouseY : selectedView.getBounds().y + selectedView.getBounds().height + 4f;
        x = clamp(x, 10f, Math.max(10f, screenWidth - width - 10f));
        float totalHeight = rowHeight * targetOptions.size();
        if (y + totalHeight > screenHeight - 10f) {
            y = Math.max(10f, (selectedView == null ? y : selectedView.getBounds().y) - totalHeight - 4f);
        }
        for (int i = 0; i < targetOptions.size(); i++) {
            targetOptionBounds.add(new Rectangle(
                x, y + (targetOptions.size() - i - 1) * rowHeight, width, rowHeight));
        }
    }

    private ActionSegmentView viewFor(ActionSegment segment) {
        for (ActionSegmentView view : offensiveViews) {
            if (view.getSegment() == segment) return view;
        }
        for (ActionSegmentView view : defensiveViews) {
            if (view.getSegment() == segment) return view;
        }
        return null;
    }

    private void openTargetMenu(ActionSegment segment) {
        if (segment == null || !segment.needsTarget() || targetOptions.isEmpty()) return;
        if (targetOptions.size() == 1) {
            chooseTarget(segment, targetOptions.get(0).instanceId());
            return;
        }
        targetMenuSegment = segment;
        layoutTargetMenu();
    }

    private void closeTargetMenu() {
        targetMenuSegment = null;
        targetOptionBounds.clear();
    }

    private boolean handleTargetMenuClick() {
        if (targetMenuSegment == null) return false;
        layoutTargetMenu();
        for (int i = 0; i < targetOptionBounds.size(); i++) {
            if (targetOptionBounds.get(i).contains(dragMouseX, dragMouseY)) {
                chooseTarget(targetMenuSegment, targetOptions.get(i).instanceId());
                soundPlayer.accept(SoundCue.UI_CONFIRM);
                return true;
            }
        }
        closeTargetMenu();
        return false;
    }

    private void drawStat(Batch batch, BitmapFont font, float x, float y, float width, String label,
                          int current, int maximum, Color fillColor) {
        ui.statPill.draw(batch, x, y, width, 29f);
        float fillRatio = maximum <= 0 ? 0f : Math.max(0f, Math.min(1f, current / (float) maximum));
        batch.setColor(fillColor);
        batch.draw(ui.pixel, x + 4f, y + 4f, (width - 8f) * fillRatio, 21f);
        batch.setColor(Color.WHITE);
        font.setColor(BattleUiAssets.MUTED);
        font.draw(batch, label, x + 8f, y + 19f);
        font.setColor(BattleUiAssets.TEXT);
        font.draw(batch, current + "/" + maximum, x + 31f, y + 19f);
    }

    private void drawTimelineLabel(Batch batch, BitmapFont font, TimelineBar bar, String label,
                                   com.badlogic.gdx.graphics.Texture icon, Color color) {
        Rectangle bounds = bar.getBounds();
        // Anchor the icon to the bar itself so tier-scaled timelines retain the
        // same gap. Desktop labels go to the icon's left so the bar cannot
        // paint over them when it is drawn afterwards.
        float iconX = compactLayout
            ? bounds.x + LABEL_LEFT_GAP
            : bounds.x - LABEL_LEFT_GAP - TIMELINE_ICON_SIZE;
        float y = compactLayout ? bounds.y + bounds.height + 9f : bounds.y + bounds.height / 2f;
        batch.draw(icon, iconX, y - TIMELINE_ICON_SIZE / 2f, TIMELINE_ICON_SIZE, TIMELINE_ICON_SIZE);
        font.setColor(color);
        if (compactLayout) {
            font.draw(batch, label, iconX + TIMELINE_ICON_SIZE + TIMELINE_LABEL_GAP, y + 8f);
        } else {
            GlyphLayout labelLayout = new GlyphLayout(font, label);
            font.draw(batch, label, iconX - TIMELINE_LABEL_GAP - labelLayout.width, y + 8f);
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
        Integer authoritativeCost = authoritativeCeCosts.get(move.getId());
        if (authoritativeCost != null) return authoritativeCost;
        return localCombatant != null
            ? localCombatant.computeMoveCeCost(move)
            : CeEfficiencyCalculator.computeActualCost(move, ceEfficiency, abilityFlags);
    }

    private static CodedAbilityState findMiraclesState(List<CodedAbilityState> states) {
        if (states == null) return null;
        return states.stream()
            .filter(state -> MiraclesAbility.KEY.equals(state.key()))
            .findFirst()
            .orElse(null);
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

    /** Returns the first AP tick at or to the right of {@code startTick} that fits the move. */
    private int firstAvailableTick(BattlePlan.Board board, int startTick, Move move) {
        TimelineBar bar = barFor(board);
        int lastStart = lastStartTick(move, bar.getDotCount());
        for (int tick = startTick; tick <= lastStart; tick++) {
            if (plan.boardTimeline(board).isRangeFree(
                tick, tick + move.getApCost() - 1)) return tick;
        }
        return -1;
    }

    /** Returns the nearest AP tick at or to the left of {@code startTick} that fits the move. */
    private int lastAvailableTick(BattlePlan.Board board, int startTick, Move move) {
        int lastStart = lastStartTick(move, barFor(board).getDotCount());
        for (int tick = Math.min(startTick, lastStart); tick >= 1; tick--) {
            if (plan.boardTimeline(board).isRangeFree(
                tick, tick + move.getApCost() - 1)) return tick;
        }
        return -1;
    }

    static int lastStartTick(Move move, int gridLength) {
        long occupancyLastStart = (long) gridLength - move.getApCost() + 1L;
        long impactLastStart = (long) gridLength - move.getUnleashPoint() + 1L
            - move.getMaxHitDelayTicks();
        long lastStart = Math.min(occupancyLastStart, impactLastStart);
        return lastStart < 1L ? 0 : (int) Math.min(Integer.MAX_VALUE, lastStart);
    }

    public class PlanningInputProcessor extends InputAdapter {
        @Override
        public boolean touchDown(int screenX, int screenY, int pointer, int button) {
            updatePointer(screenX, screenY);
            refresh();

            if (confirmed) {
                if (allowManualUnlock && button == Buttons.LEFT
                    && lockInBounds.contains(dragMouseX, dragMouseY)) {
                    unlock();
                    soundPlayer.accept(SoundCue.UI_CONFIRM);
                    return true;
                }
                return false;
            }

            if (button == Buttons.LEFT && handleTargetMenuClick()) return true;

            if (button == Buttons.RIGHT) {
                ActionSegmentView hit = hitSegment();
                if (hit == null || !plan.remove(hit.getSegment())) return false;
                if (selectedSegment == hit.getSegment()) selectedSegment = null;
                if (targetMenuSegment == hit.getSegment()) closeTargetMenu();
                hoveredSegment = null;
                soundPlayer.accept(SoundCue.UI_PLAN_REMOVE);
                return true;
            }

            if (button != Buttons.LEFT) return false;
            pressedSegment = null;
            pressedBoard = null;
            pressMouseX = dragMouseX;
            pressMouseY = dragMouseY;

            if (paletteScrollMax > 0f && paletteScrollTrackBounds.contains(dragMouseX, dragMouseY)) {
                if (!paletteScrollThumbBounds.contains(dragMouseX, dragMouseY)) {
                    scrollPaletteThumbTo(dragMouseX);
                }
                draggingPaletteScrollbar = true;
                scrollbarDragStartX = dragMouseX;
                scrollbarDragStartOffset = paletteScrollX;
                return true;
            }

            if (lockInBounds.contains(dragMouseX, dragMouseY)) {
                lockError = plan.missingTargetError();
                if (lockError != null) {
                    soundPlayer.accept(SoundCue.UI_DENIED);
                    return true;
                }
                confirmed = true;
                onConfirm.run();
                return true;
            }

            if (paletteViewportBounds.contains(dragMouseX, dragMouseY)) {
                for (int i = 0; i < cards.size(); i++) {
                    MoveCardView card = cards.get(i);
                    if (!card.isDisabled() && card.getBounds().contains(dragMouseX, dragMouseY)) {
                        selectedSegment = null;
                        draggingMove = card.getMove();
                        draggingSegment = null;
                        draggingBoard = BattlePlan.boardFor(draggingMove);
                        clickingMoveCard = true;
                        dragSoundPlayed = false;
                        updateSnap();
                        return true;
                    }
                }
            }

            ActionSegmentView hit = hitSegment();
            if (hit != null) {
                selectedSegment = hit.getSegment();
                pressedSegment = hit.getSegment();
                pressedBoard = BattlePlan.boardFor(hit.getMove());
                return true;
            }

            selectedSegment = null;
            return false;
        }

        @Override
        public boolean touchDragged(int screenX, int screenY, int pointer) {
            if (draggingPaletteScrollbar) {
                updatePointer(screenX, screenY);
                float trackTravel = paletteScrollTrackBounds.width - paletteScrollThumbBounds.width;
                if (trackTravel > 0f) {
                    setPaletteScroll(scrollbarDragStartOffset
                        + (dragMouseX - scrollbarDragStartX) * paletteScrollMax / trackTravel);
                    updateHover();
                }
                return true;
            }
            if (confirmed || (draggedMove() == null && pressedSegment == null)) return false;
            updatePointer(screenX, screenY);
            float deltaX = dragMouseX - pressMouseX;
            float deltaY = dragMouseY - pressMouseY;
            if (!dragSoundPlayed
                && deltaX * deltaX + deltaY * deltaY < DRAG_THRESHOLD * DRAG_THRESHOLD) {
                return true;
            }
            if (pressedSegment != null) {
                startMoveDrag(pressedSegment, pressedBoard);
                pressedSegment = null;
                pressedBoard = null;
            }
            if (!dragSoundPlayed) {
                soundPlayer.accept(SoundCue.UI_PICKUP);
                dragSoundPlayed = true;
            }
            clickingMoveCard = false;
            updateSnap();
            return true;
        }

        @Override
        public boolean touchUp(int screenX, int screenY, int pointer, int button) {
            if (confirmed || button != Buttons.LEFT) return false;
            updatePointer(screenX, screenY);
            if (draggingPaletteScrollbar) {
                draggingPaletteScrollbar = false;
                updateHover();
                return true;
            }
            if (pressedSegment != null) {
                ActionSegment clicked = pressedSegment;
                pressedSegment = null;
                pressedBoard = null;
                openTargetMenu(clicked);
                return true;
            }
            if (draggedMove() == null) return false;
            updateSnap();

            Move move = draggedMove();
            CombatantId target = draggingSegment == null ? defaultTarget(move) : originalTarget;
            boolean droppedOnTimeline = barFor(draggingBoard).getBounds().contains(dragMouseX, dragMouseY);
            ActionSegment placed = isMoveRestricted(move) ? null : clickingMoveCard
                ? plan.placeFirstFit(move, ceCost(move), target)
                : droppedOnTimeline && snapValid
                    ? plan.place(move, draggingTick, ceCost(move), target) : null;
            if (placed != null) {
                selectedSegment = placed;
                soundPlayer.accept(SoundCue.UI_PLAN_PLACE);
            } else if (droppedOnTimeline && draggingSegment != null) {
                // A cancelled relocation must never destroy an already planned move.
                selectedSegment = plan.place(
                    draggingSegment.getMove(), originalTick, originalCeCost, originalTarget);
                soundPlayer.accept(SoundCue.UI_DENIED);
            } else if (!droppedOnTimeline) {
                selectedSegment = null;
                soundPlayer.accept(draggingSegment == null
                    ? SoundCue.UI_DENIED : SoundCue.UI_PLAN_REMOVE);
            } else {
                soundPlayer.accept(SoundCue.UI_DENIED);
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

        @Override
        public boolean scrolled(float amountX, float amountY) {
            if (confirmed || draggedMove() != null || paletteScrollMax <= 0f) return false;
            updatePointer(Gdx.input.getX(), Gdx.input.getY());
            if (!paletteBounds.contains(dragMouseX, dragMouseY)) return false;

            float amount = Math.abs(amountX) > Math.abs(amountY) ? amountX : amountY;
            if (amount == 0f) return false;
            float step = Math.min(
                MoveCardView.CARD_W + CARD_GAP,
                Math.max(48f, paletteViewportBounds.width * 0.3f));
            setPaletteScrollTarget(
                paletteScrollTargetX + amount * step * PALETTE_SCROLL_SPEED);
            updateHover();
            return true;
        }

        private void scrollPaletteThumbTo(float pointerX) {
            float travel = paletteScrollTrackBounds.width - paletteScrollThumbBounds.width;
            if (travel <= 0f) return;
            float thumbX = clamp(
                pointerX - paletteScrollThumbBounds.width / 2f,
                paletteScrollTrackBounds.x,
                paletteScrollTrackBounds.x + travel);
            setPaletteScroll(
                (thumbX - paletteScrollTrackBounds.x) * paletteScrollMax / travel);
        }

        private void startMoveDrag(ActionSegment segment, BattlePlan.Board board) {
            originalTick = segment.getStartTick();
            originalCeCost = segment.getActualCeCost();
            originalTarget = segment.getTarget();
            closeTargetMenu();
            plan.remove(segment);
            draggingSegment = segment;
            draggingMove = null;
            draggingBoard = board;
            dragSoundPlayed = false;
            updateSnap();
        }

        private void clearDrag() {
            draggingMove = null;
            draggingSegment = null;
            draggingBoard = null;
            pressedSegment = null;
            pressedBoard = null;
            snapValid = false;
            clickingMoveCard = false;
            dragSoundPlayed = false;
            updateHover();
        }

        private void updatePointer(int screenX, int screenY) {
            dragMouseX = screenX;
            dragMouseY = screenHeight - screenY;
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
            int requestedTick = bar.tickAtX(dragMouseX);
            int requestedEnd = requestedTick + move.getApCost() - 1;
            int availableTick;
            if (requestedTick <= lastStartTick(move, bar.getDotCount())
                && plan.boardTimeline(draggingBoard).isRangeFree(requestedTick, requestedEnd)) {
                availableTick = requestedTick;
            } else {
                int leftTick = lastAvailableTick(draggingBoard, requestedTick, move);
                int rightTick = firstAvailableTick(draggingBoard, requestedTick, move);
                if (leftTick < 0) {
                    availableTick = rightTick;
                } else if (rightTick < 0) {
                    availableTick = leftTick;
                } else {
                    float snapMidpoint = (leftTick + rightTick + move.getApCost() - 1) / 2f;
                    availableTick = requestedTick <= snapMidpoint ? leftTick : rightTick;
                }
            }
            draggingTick = availableTick > 0 ? availableTick : requestedTick;
            snapValid = availableTick > 0
                && !isMoveRestricted(move)
                && plan.canPlace(move, ceCost(move));
        }

        private void updateHover() {
            lockHovered = !confirmed && lockInBounds.contains(dragMouseX, dragMouseY);
            updateHoveredCard();
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

    private void updateHoveredCard() {
        hoveredCard = -1;
        if (!paletteViewportBounds.contains(dragMouseX, dragMouseY)) return;
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).getBounds().contains(dragMouseX, dragMouseY)) {
                hoveredCard = i;
                return;
            }
        }
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
