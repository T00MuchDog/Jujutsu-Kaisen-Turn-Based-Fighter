package com.jjktbf.graphics.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.ui.CombatantPanel;
import com.jjktbf.graphics.ui.MoveCard;
import com.jjktbf.graphics.ui.battle.BattleUiAssets;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CeEfficiencyCalculator;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.move.Move;
import com.jjktbf.view.BattleView;

import java.util.ArrayList;
import java.util.List;

/**
 * Graphics implementation of BattleView.
 *
 * Layout:
 *   Top half    — enemy panel (sprite + HP/CE bars)
 *   Middle      — event log (last N messages)
 *   Bottom half — player panel + move cards
 *
 * Threading note:
 *   BattleController calls promptMoveSelection() synchronously.
 *   The graphics loop must therefore run the controller on a background thread
 *   (started by JJKGame.startBattle) while posting render state back to the
 *   LibGDX render thread via Gdx.app.postRunnable().
 *
 *   promptMoveSelection() blocks the controller thread until the player
 *   presses ENTER to confirm their move queue.
 */
public class BattleScreen implements Screen, BattleView {

    /** Max raw messages retained in the battle log; older ones are dropped. */
    private static final int   LOG_MAX_STORED = 50;
    /** Vertical spacing multiplier between battle-log lines (1.0 = single line height). */
    private static final float LOG_LINE_SPACING = 1.8f;
    private static final float CARD_MARGIN = 8f;
    private static final int   EVENT_DELAY_MS = 520;
    /** Pause applied when the AP tick advances during resolution — slows the sweep. */
    private static final int   TICK_DELAY_MS = 2000;

    private final JJKGame     game;
    private final AssetLoader assets;
    private final SpriteBatch batch;

    // ── Panels ────────────────────────────────────────────────────────────────
    private CombatantPanel playerPanel;
    private CombatantPanel enemyPanel;
    private final Rectangle executionHeaderBounds = new Rectangle();
    private final Rectangle logBounds = new Rectangle();
    private final Rectangle nextRoundBounds = new Rectangle();

    // ── Event log ─────────────────────────────────────────────────────────────
    private final List<String> logLines = new ArrayList<>();

    // ── Move selection state ──────────────────────────────────────────────────
    private List<MoveCard>  moveCards       = new ArrayList<>();
    private List<Move>      selectedQueue   = new ArrayList<>();
    private int             remainingAp     = 0;
    private int             projectedCe     = 0;
    private volatile boolean awaitingInput  = false;
    private volatile boolean inputConfirmed = false;
    private volatile boolean awaitingNextRound = false;
    private volatile boolean nextRoundConfirmed = false;
    /** True while resolution tick calls are streaming; used to pace between ticks. */
    private volatile boolean resolvingTicks = false;
    private boolean nextRoundHovered = false;

    // ── Planning panel (two-board timeline UI) ─────────────────────────────────
    private com.jjktbf.graphics.ui.battle.PlanningPanel planningPanel;

    // ── Shared render state (written by controller thread, read by render) ────
    private volatile BattleCombatant renderPlayer;
    private volatile BattleCombatant renderEnemy;
    private volatile String          phaseLabel = "";
    private volatile boolean         battleOver  = false;
    private volatile String          battleResult = "";

    public BattleScreen(JJKGame game, AssetLoader assets) {
        this.game   = game;
        this.assets = assets;
        this.batch  = new SpriteBatch();
    }

    // -------------------------------------------------------------------------
    // Screen lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void show() {
        logLines.clear();
        selectedQueue.clear();
        moveCards.clear();
        awaitingInput  = false;
        inputConfirmed = false;
        awaitingNextRound = false;
        nextRoundConfirmed = false;
        resolvingTicks = false;
        battleOver     = false;
    }

    /** Last frame's delta, shared with widgets that animate (e.g. HP bars). */
    private float frameDelta = 0f;

    @Override
    public void render(float delta) {
        frameDelta = delta;
        clearScreen();
        handleInput();
        drawAll();
    }

    @Override public void resize(int w, int h) {
        batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        if (planningPanel != null) planningPanel.resize(w, h);
        layoutExecutionUi(w, h);
    }
    @Override public void pause()  {}
    @Override public void resume() {}
    @Override public void hide()   {}

    @Override
    public void dispose() {
        batch.dispose();
    }

    // -------------------------------------------------------------------------
    // Input (render thread only)
    // -------------------------------------------------------------------------

    private void handleInput() {
        // The new two-board PlanningPanel owns its own drag input processor and
        // Lock In button — skip the legacy click-to-toggle / ENTER flow entirely
        // while it is active.
        if (planningPanel != null) return;

        if (awaitingNextRound) {
            float x = Gdx.input.getX();
            float y = Gdx.graphics.getHeight() - Gdx.input.getY();
            nextRoundHovered = nextRoundBounds.contains(x, y);
            if (Gdx.input.justTouched() && nextRoundHovered) {
                nextRoundConfirmed = true;
            }
            return;
        }

        if (!awaitingInput) return;

        // Click or touch on a move card
        if (Gdx.input.justTouched()) {
            float tx = Gdx.input.getX();
            float ty = Gdx.graphics.getHeight() - Gdx.input.getY(); // flip Y
            for (MoveCard card : moveCards) {
                if (!card.isDisabled() && card.contains(tx, ty)) {
                    toggleCard(card);
                    break;
                }
            }
        }

        // ENTER confirms the queue
        if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) {
            awaitingInput  = false;
            inputConfirmed = true;
        }
    }

    private void toggleCard(MoveCard card) {
        if (card.isSelected()) {
            // Deselect — restore AP and CE
            card.setSelected(false);
            selectedQueue.remove(card.getMove());
            remainingAp += card.getMove().getApCost();
            projectedCe += CeEfficiencyCalculator.computeActualCost(
                    card.getMove(),
                    renderPlayer.getEffectiveStats().getCursedEnergyEfficiency(), renderPlayer.getAbilityFlags());
            refreshCardStates();
        } else {
            // Attempt to select
            if (card.getMove().getApCost() <= remainingAp) {
                int ceCost = CeEfficiencyCalculator.computeActualCost(
                        card.getMove(),
                        renderPlayer.getEffectiveStats().getCursedEnergyEfficiency(), renderPlayer.getAbilityFlags());
                if (ceCost <= projectedCe) {
                    card.setSelected(true);
                    selectedQueue.add(card.getMove());
                    remainingAp -= card.getMove().getApCost();
                    projectedCe -= ceCost;
                    refreshCardStates();
                }
            }
        }
    }

    private void refreshCardStates() {
        for (MoveCard card : moveCards) {
            if (!card.isSelected()) {
                boolean apOk = card.getMove().getApCost() <= remainingAp;
                int ce = CeEfficiencyCalculator.computeActualCost(
                        card.getMove(),
                        renderPlayer.getEffectiveStats().getCursedEnergyEfficiency(), renderPlayer.getAbilityFlags());
                boolean ceOk = ce <= projectedCe;
                card.setDisabled(!apOk || !ceOk);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Draw
    // -------------------------------------------------------------------------

    private void clearScreen() {
        // #CDDCFA — light blue, shared across all screens
        Gdx.gl.glClearColor(0.804f, 0.863f, 0.980f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    }

    private void drawAll() {
        if (battleOver) { drawBattleOver(); return; }

        // Planning is a dedicated workspace. Drawing the combat HUD behind it
        // made both the board and the move cards compete for attention.
        if (planningPanel != null) {
            planningPanel.draw(batch, assets.fontSmall, assets.fontMedium, assets.fontLarge);
            return;
        }

        float sw = Gdx.graphics.getWidth();
        float sh = Gdx.graphics.getHeight();

        batch.begin();
        drawExecutionChrome(sw, sh);
        if (enemyPanel  != null && renderEnemy  != null)
            enemyPanel.draw(batch, assets.fontLog, assets.fontSmall, renderEnemy.getCharacter().getName(), frameDelta);
        if (playerPanel != null && renderPlayer != null)
            playerPanel.draw(batch, assets.fontLog, assets.fontSmall, renderPlayer.getCharacter().getName(), frameDelta);
        drawLog(sw, sh);
        if (awaitingInput && planningPanel == null && !moveCards.isEmpty()) drawMoveCards();
        drawNextRoundButton();
        batch.end();

    }

    private void drawExecutionChrome(float sw, float sh) {
        if (executionHeaderBounds.width <= 0f) layoutExecutionUi(sw, sh);
        assets.battleUi.header.draw(batch, executionHeaderBounds.x, executionHeaderBounds.y,
            executionHeaderBounds.width, executionHeaderBounds.height);
        assets.fontMedium.setColor(Color.WHITE);
        assets.fontMedium.draw(batch, phaseLabel, executionHeaderBounds.x + 18f,
            executionHeaderBounds.y + executionHeaderBounds.height - 18f);

        assets.fontSmall.setColor(new Color(0.720f, 0.800f, 0.950f, 1f));
        assets.fontSmall.draw(batch, "BATTLE EXECUTION", executionHeaderBounds.x + 20f,
            executionHeaderBounds.y + 14f);
    }

    /**
     * Render the battle log: newest entry at the bottom, older entries stacked
     * above it, and any that no longer fit simply drop off the top. No auto-
     * resizing — long lines wrap to a new line, and the font stays fixed.
     */
    private void drawLog(float sw, float sh) {
        assets.battleUi.palette.draw(batch, logBounds.x, logBounds.y, logBounds.width, logBounds.height);
        assets.fontSmall.setColor(new Color(0.720f, 0.800f, 0.950f, 1f));
        assets.fontSmall.draw(batch, "BATTLE LOG", logBounds.x + 14f, logBounds.y + logBounds.height - 14f);

        BitmapFont logFont = assets.fontLog;
        // Wrap the retained messages to the panel width (fixed font; no scaling).
        List<String> lines = wrapAll(logFont, logBounds.width - 28f);

        // Usable area sits below the "BATTLE LOG" title.
        float topY    = logBounds.y + logBounds.height - 34f;
        float bottomY = logBounds.y + 14f;
        float lineStep = logFont.getLineHeight() * LOG_LINE_SPACING;

        // Bottom-anchor: walk newest → oldest; stop as soon as a line would clip
        // the top, so overflowed entries disappear off the top of the box.
        logFont.setColor(Color.WHITE);
        float y = bottomY;
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (y > topY) break;
            logFont.draw(batch, lines.get(i), logBounds.x + 14f, y);
            y += lineStep;
        }
    }

    /** Wrap every retained message to {@code width} and return the flat list of lines (oldest first). */
    private List<String> wrapAll(BitmapFont font, float width) {
        List<String> all = new ArrayList<>();
        for (String message : logLines) {
            all.addAll(wrapText(font, message, width));
        }
        return all;
    }

    private void drawMoveCards() {
        for (MoveCard card : moveCards) {
            card.draw(batch, assets.fontSmall);
        }
        // AP / CE status line above cards
        float cardRowY = MoveCard.CARD_H + CARD_MARGIN * 2 + 10;
        assets.fontSmall.setColor(Color.WHITE);
        assets.fontSmall.draw(batch,
            "AP remaining: " + remainingAp + "    CE remaining: " + projectedCe
            + "    ENTER to confirm",
            20, cardRowY);
    }

    private void drawNextRoundButton() {
        if (!awaitingNextRound) return;
        if (nextRoundHovered) {
            assets.battleUi.lockButtonOver.draw(batch, nextRoundBounds.x, nextRoundBounds.y,
                nextRoundBounds.width, nextRoundBounds.height);
        } else {
            assets.battleUi.lockButton.draw(batch, nextRoundBounds.x, nextRoundBounds.y,
                nextRoundBounds.width, nextRoundBounds.height);
        }
        assets.fontMedium.setColor(Color.WHITE);
        assets.fontMedium.draw(batch, "NEXT ROUND", nextRoundBounds.x + 16f,
            nextRoundBounds.y + nextRoundBounds.height - 15f);
    }

    private void drawBattleOver() {
        float sw = Gdx.graphics.getWidth();
        float sh = Gdx.graphics.getHeight();
        batch.begin();
        float width = Math.min(420f, sw - 48f);
        float x = (sw - width) / 2f;
        float y = sh * 0.35f;
        assets.battleUi.header.draw(batch, x, y, width, 180f);
        assets.fontLarge.setColor(Color.WHITE);
        assets.fontLarge.draw(batch, "BATTLE OVER", x + 36f, y + 132f);
        assets.fontMedium.setColor(Color.YELLOW);
        assets.fontMedium.draw(batch, battleResult, x + 36f, y + 86f);
        assets.fontSmall.setColor(Color.LIGHT_GRAY);
        assets.fontSmall.draw(batch, "ESC: MAIN MENU", x + 36f, y + 36f);
        batch.end();

        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            game.showMainMenu();
        }
    }

    /** Append a message to the log and trim the oldest entries beyond the storage cap. */
    private void addLogLine(String message) {
        logLines.add(message);
        while (logLines.size() > LOG_MAX_STORED) logLines.remove(0);
    }

    private static List<String> wrapText(BitmapFont font, String text, float width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (line.isEmpty() && new GlyphLayout(font, word).width > width) {
                for (int i = 0; i < word.length(); i++) {
                    String candidate = line + String.valueOf(word.charAt(i));
                    if (!line.isEmpty() && new GlyphLayout(font, candidate).width > width) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    line.append(word.charAt(i));
                }
                continue;
            }
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (new GlyphLayout(font, candidate).width <= width) {
                line.setLength(0);
                line.append(candidate);
            } else {
                if (!line.isEmpty()) lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    // -------------------------------------------------------------------------
    // BattleView implementation (called from controller background thread)
    // -------------------------------------------------------------------------

    @Override
    public void displayRoundStart(BattleState state) {
        Gdx.app.postRunnable(() -> {
            renderPlayer = state.getPlayerCombatant();
            renderEnemy  = state.getEnemyCombatant();
            phaseLabel   = "ROUND " + state.getRoundNumber() + "  —  PLANNING";
            initPanels();
            updatePanels();
        });
        sleepMs(200);
    }

    @Override
    public List<Move> promptMoveSelection(BattleCombatant combatant, BattleCombatant opponent) {
        inputConfirmed = false;
        Gdx.app.postRunnable(() -> {
            renderPlayer  = combatant;
            renderEnemy   = opponent;
            phaseLabel    = "SELECT YOUR MOVES";
            remainingAp   = combatant.getMaxApBar();
            projectedCe   = combatant.getCurrentCe();
            selectedQueue = new ArrayList<>();
            buildMoveCards(combatant);
            updatePanels();
            awaitingInput  = true;
            inputConfirmed = false;
        });

        // Block the controller thread until the player confirms
        while (!inputConfirmed) {
            sleepMs(16);
        }

        List<Move> result = new ArrayList<>(selectedQueue);
        Gdx.app.postRunnable(() -> {
            moveCards.clear();
            selectedQueue.clear();
        });
        return result;
    }

    /**
     * Build and run the two-board timeline planning UI. Posts panel construction
     * to the render thread, installs the panel's drag input processor, and blocks
     * the controller thread until the player clicks "Lock In".
     *
     * <p>The plan is built live by the panel; on confirm we return it directly.
     */
    @Override
    public BattlePlan promptBattlePlan(BattleCombatant combatant, BattleCombatant opponent) {
        // This must happen on the controller thread before its wait loop. If it
        // only happens in the posted render callback, a prior round's confirmed
        // value can skip planning entirely.
        inputConfirmed = false;
        Gdx.app.postRunnable(() -> {
            renderPlayer = combatant;
            renderEnemy  = opponent;
            phaseLabel   = "PLAN YOUR ROUND";
            planningPanel = new com.jjktbf.graphics.ui.battle.PlanningPanel(
                combatant, assets.battleUi, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            planningPanel.setOnConfirm(() -> { inputConfirmed = true; });
            Gdx.input.setInputProcessor(planningPanel.inputProcessor());
            updatePanels();
            awaitingInput  = true;
            inputConfirmed = false;
        });

        while (!inputConfirmed) {
            sleepMs(16);
        }

        // Read the plan on the render thread to avoid racing a drag-commit.
        final java.util.concurrent.atomic.AtomicReference<com.jjktbf.model.combat.BattlePlan> holder =
            new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.CountDownLatch panelClosed = new java.util.concurrent.CountDownLatch(1);
        Gdx.app.postRunnable(() -> {
            holder.set(planningPanel == null ? null : planningPanel.getPlan());
            Gdx.input.setInputProcessor(null);
            planningPanel = null;
            awaitingInput = false;
            panelClosed.countDown();
        });
        // Wait for the render-thread cleanup, even if the defensive fallback is
        // needed. Waiting on holder.get() would otherwise spin forever on null.
        while (panelClosed.getCount() != 0) { sleepMs(4); }
        BattlePlan result = holder.get();
        if (result == null) {
            // Fallback: empty plan (bank the round) — should not normally happen.
            result = new BattlePlan(combatant.getMaxApBar(), combatant.getCurrentCe());
        }
        return result;
    }



    @Override
    public void displayCombatEvents(List<CombatEvent> events, BattleState state) {
        Gdx.app.postRunnable(() -> phaseLabel = "ROUND " + state.getRoundNumber() + "  —  RESOLVING");

        // Each call now corresponds to one tick of real engine progression (the
        // controller drives the resolver tick-by-tick). Pause between successive
        // resolution calls so the sweep reads at a deliberate cadence; skip the
        // pause on the first call of a sequence.
        if (resolvingTicks) sleepMs(TICK_DELAY_MS);
        resolvingTicks = true;

        for (CombatEvent e : events) {
            if (!e.getMessage().isBlank()) {
                final String msg = e.getMessage();
                Gdx.app.postRunnable(() -> {
                    addLogLine(msg);
                    updatePanels();
                });
                sleepMs(EVENT_DELAY_MS);
            }
        }
    }

    @Override
    public void displayRoundEnd(BattleState state) {
        resolvingTicks = false;
        Gdx.app.postRunnable(() -> {
            phaseLabel = "ROUND " + Math.max(1, state.getRoundNumber() - 1) + " COMPLETE";
            updatePanels();
        });
    }

    @Override
    public void awaitNextRound(BattleState state) {
        nextRoundConfirmed = false;
        Gdx.app.postRunnable(() -> {
            awaitingInput = false;
            awaitingNextRound = true;
            nextRoundHovered = false;
        });

        while (!nextRoundConfirmed) {
            sleepMs(16);
        }

        Gdx.app.postRunnable(() -> {
            awaitingNextRound = false;
            nextRoundHovered = false;
        });
    }

    @Override
    public void displayBattleOver(BattleCombatant winner, BattleState state) {
        Gdx.app.postRunnable(() -> {
            if (winner == null) {
                battleResult = "DRAW!";
            } else {
                battleResult = winner.getCharacter().getName() + " WINS!";
            }
            phaseLabel = "BATTLE OVER";
            battleOver = true;
        });
    }

    @Override
    public void displayMessage(String message) {
        Gdx.app.postRunnable(() -> addLogLine(message));
        sleepMs(100);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void initPanels() {
        layoutExecutionUi(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    /** Recreates all execution widgets from the live viewport after a resize. */
    private void layoutExecutionUi(float width, float height) {
        float margin = Math.min(42f, Math.max(26f, width * 0.035f));
        float headerHeight = 54f;
        executionHeaderBounds.set(margin, height - margin - headerHeight, width - margin * 2f, headerHeight);

        float portraitScale = Math.max(0f, Math.min(1f, (height - 600f) / 480f));
        float spriteWidth = 90f + 160f * portraitScale;
        float spriteHeight = spriteWidth * 1.5f;
        float sideInset = margin + 54f + 36f * portraitScale;
        float playerY = margin + 66f + 58f * portraitScale;
        float enemyY = executionHeaderBounds.y - 14f - spriteHeight - 36f;
        playerPanel = new CombatantPanel(assets.playerSprite, assets.battleUi,
            sideInset, playerY, spriteWidth, spriteHeight);
        enemyPanel = new CombatantPanel(assets.enemySprite, assets.battleUi,
            width - sideInset - spriteWidth, enemyY, spriteWidth, spriteHeight);

        float baseLogWidth = Math.min(390f, Math.max(220f, width * 0.46f));
        float baseLogHeight = Math.min(180f, Math.max(112f, height * 0.28f));
        float logWidth = Math.min(baseLogWidth * 1.8f, width * 0.63f);
        float logHeight = baseLogHeight * 1.4f;
        logBounds.set(margin, executionHeaderBounds.y - 14f - logHeight, logWidth, logHeight);
        nextRoundBounds.set(width - margin - 210f, margin, 210f, 52f);
        updatePanels();
    }

    private void updatePanels() {
        if (playerPanel != null && renderPlayer != null) playerPanel.update(renderPlayer);
        if (enemyPanel  != null && renderEnemy  != null) enemyPanel.update(renderEnemy);
    }

    private void buildMoveCards(BattleCombatant combatant) {
        moveCards.clear();
        List<Move> moves = combatant.getCharacter().getKnownMoves();
        float startX = CARD_MARGIN;
        float cardY  = CARD_MARGIN;
        for (Move m : moves) {
            if (combatant.getAbilityFlags().lockedMoveTags.stream().anyMatch(m::hasTag)) continue;
            MoveCard card = new MoveCard(m, startX, cardY,
                    assets.cardNormal, assets.cardSelected, assets.cardDisabled);
            moveCards.add(card);
            startX += MoveCard.CARD_W + CARD_MARGIN;
        }
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
