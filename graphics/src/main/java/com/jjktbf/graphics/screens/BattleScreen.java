package com.jjktbf.graphics.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.ui.CombatantPanel;
import com.jjktbf.graphics.ui.MoveCard;
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

    private static final int   LOG_LINES   = 6;
    private static final float CARD_MARGIN = 8f;
    private static final int   EVENT_DELAY_MS = 280;

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
        battleOver     = false;
    }

    @Override
    public void render(float delta) {
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
            planningPanel.draw(batch, assets.fontSmall, assets.fontMedium);
            return;
        }

        float sw = Gdx.graphics.getWidth();
        float sh = Gdx.graphics.getHeight();

        batch.begin();
        drawExecutionChrome(sw, sh);
        if (enemyPanel  != null && renderEnemy  != null)
            enemyPanel.draw(batch, assets.fontSmall, renderEnemy.getCharacter().getName());
        if (playerPanel != null && renderPlayer != null)
            playerPanel.draw(batch, assets.fontSmall, renderPlayer.getCharacter().getName());
        drawLog(sw, sh);
        if (awaitingInput && planningPanel == null) drawMoveCards();
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

    private void drawLog(float sw, float sh) {
        assets.battleUi.palette.draw(batch, logBounds.x, logBounds.y, logBounds.width, logBounds.height);
        assets.fontSmall.setColor(new Color(0.720f, 0.800f, 0.950f, 1f));
        assets.fontSmall.draw(batch, "BATTLE LOG", logBounds.x + 14f, logBounds.y + logBounds.height - 14f);

        float logY = logBounds.y + logBounds.height - 34f;
        float lineH = 17f;
        assets.fontSmall.setColor(Color.WHITE);
        int maxVisibleLines = Math.max(1, (int) ((logBounds.height - 42f) / lineH));
        int start = Math.max(0, logLines.size() - Math.min(LOG_LINES, maxVisibleLines));
        for (int i = start; i < logLines.size(); i++) {
            assets.fontSmall.draw(batch, shorten(logLines.get(i), logBounds.width - 28f),
                logBounds.x + 14f, logY - (i - start) * lineH);
        }
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

    private static String shorten(String text, float width) {
        int maxCharacters = Math.max(8, (int) (width / 5.5f));
        if (text.length() <= maxCharacters) return text;
        return text.substring(0, maxCharacters - 1) + ".";
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
        for (CombatEvent e : events) {
            if (!e.getMessage().isBlank()) {
                final String msg = e.getMessage();
                Gdx.app.postRunnable(() -> {
                    logLines.add(msg);
                    updatePanels();
                });
                sleepMs(EVENT_DELAY_MS);
            }
        }
        sleepMs(EVENT_DELAY_MS);
    }

    @Override
    public void displayRoundEnd(BattleState state) {
        Gdx.app.postRunnable(() -> {
            phaseLabel = "ROUND " + Math.max(1, state.getRoundNumber() - 1) + "  —  COMPLETE";
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
        Gdx.app.postRunnable(() -> logLines.add(message));
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
        float margin = Math.min(42f, Math.max(22f, width * 0.035f));
        float headerHeight = 60f;
        executionHeaderBounds.set(margin, height - margin - headerHeight, width - margin * 2f, headerHeight);

        float spriteWidth = Math.min(180f, Math.max(112f, width * 0.16f));
        float spriteHeight = spriteWidth * 1.5f;
        float spriteY = Math.max(90f, height * 0.40f - spriteHeight * 0.35f);
        playerPanel = new CombatantPanel(assets.playerSprite, assets.battleUi,
            margin + 26f, spriteY, spriteWidth, spriteHeight);
        enemyPanel = new CombatantPanel(assets.enemySprite, assets.battleUi,
            width - margin - spriteWidth - 26f, spriteY, spriteWidth, spriteHeight);

        float logWidth = Math.max(220f, width * 0.46f);
        float logHeight = Math.min(180f, Math.max(112f, height * 0.28f));
        logBounds.set((width - logWidth) / 2f, height * 0.34f, logWidth, logHeight);
        nextRoundBounds.set(width / 2f - 94f, margin, 188f, 46f);
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
