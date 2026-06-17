package com.jjktbf.graphics.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.ui.CombatantPanel;
import com.jjktbf.graphics.ui.MoveCard;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.combat.BattleCombatant;
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

    private final JJKGame     game;
    private final AssetLoader assets;
    private final SpriteBatch batch;
    private final ShapeRenderer sr;

    // ── Panels ────────────────────────────────────────────────────────────────
    private CombatantPanel playerPanel;
    private CombatantPanel enemyPanel;

    // ── Event log ─────────────────────────────────────────────────────────────
    private final List<String> logLines = new ArrayList<>();

    // ── Move selection state ──────────────────────────────────────────────────
    private List<MoveCard>  moveCards       = new ArrayList<>();
    private List<Move>      selectedQueue   = new ArrayList<>();
    private int             remainingAp     = 0;
    private int             projectedCe     = 0;
    private volatile boolean awaitingInput  = false;
    private volatile boolean inputConfirmed = false;

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
        this.sr     = new ShapeRenderer();
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
        sr.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
    }
    @Override public void pause()  {}
    @Override public void resume() {}
    @Override public void hide()   {}

    @Override
    public void dispose() {
        batch.dispose();
        sr.dispose();
    }

    // -------------------------------------------------------------------------
    // Input (render thread only)
    // -------------------------------------------------------------------------

    private void handleInput() {
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
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    }

    private void drawAll() {
        if (battleOver) { drawBattleOver(); return; }

        float sw = Gdx.graphics.getWidth();
        float sh = Gdx.graphics.getHeight();

        // Shape pass (bars)
        sr.begin(ShapeRenderer.ShapeType.Filled);
        if (enemyPanel  != null) enemyPanel.drawShapes(sr);
        if (playerPanel != null) playerPanel.drawShapes(sr);
        sr.end();

        // Sprite + text pass
        batch.begin();
        drawPhaseLabel(sw, sh);
        if (enemyPanel  != null && renderEnemy  != null)
            enemyPanel.draw(batch, assets.fontSmall, renderEnemy.getCharacter().getName());
        if (playerPanel != null && renderPlayer != null)
            playerPanel.draw(batch, assets.fontSmall, renderPlayer.getCharacter().getName());
        drawLog(sw, sh);
        if (awaitingInput) drawMoveCards();
        drawFooter(sw);
        batch.end();
    }

    private void drawPhaseLabel(float sw, float sh) {
        assets.fontMedium.setColor(Color.WHITE);
        assets.fontMedium.draw(batch, phaseLabel, 20, sh - 20);
    }

    private void drawLog(float sw, float sh) {
        float logY = sh * 0.42f;
        float lineH = 14f;
        assets.fontSmall.setColor(Color.LIGHT_GRAY);
        int start = Math.max(0, logLines.size() - LOG_LINES);
        for (int i = start; i < logLines.size(); i++) {
            assets.fontSmall.draw(batch, logLines.get(i), 20, logY - (i - start) * lineH);
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

    private void drawFooter(float sw) {
        assets.fontSmall.setColor(Color.DARK_GRAY);
        assets.fontSmall.draw(batch,
            awaitingInput ? "Click a move to queue. ENTER = confirm." : "",
            20, 10);
    }

    private void drawBattleOver() {
        float sw = Gdx.graphics.getWidth();
        float sh = Gdx.graphics.getHeight();
        batch.begin();
        assets.fontLarge.setColor(Color.WHITE);
        assets.fontLarge.draw(batch, "BATTLE OVER", 20, sh * 0.6f);
        assets.fontMedium.setColor(Color.YELLOW);
        assets.fontMedium.draw(batch, battleResult, 20, sh * 0.5f);
        assets.fontSmall.setColor(Color.LIGHT_GRAY);
        assets.fontSmall.draw(batch, "ESC: main menu", 20, sh * 0.35f);
        batch.end();

        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            game.showMainMenu();
        }
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

    @Override
    public void displayCombatEvents(List<CombatEvent> events, BattleState state) {
        for (CombatEvent e : events) {
            if (!e.getMessage().isBlank()) {
                final String msg = e.getMessage();
                Gdx.app.postRunnable(() -> {
                    logLines.add(msg);
                    updatePanels();
                });
                sleepMs(60); // brief pause between events
            }
        }
        sleepMs(300);
    }

    @Override
    public void displayRoundEnd(BattleState state) {
        Gdx.app.postRunnable(() -> {
            phaseLabel = "ROUND END";
            updatePanels();
        });
        sleepMs(500);
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
        float sw = Gdx.graphics.getWidth();
        float sh = Gdx.graphics.getHeight();
        enemyPanel  = new CombatantPanel(assets.enemySprite,  sw * 0.65f, sh * 0.55f);
        playerPanel = new CombatantPanel(assets.playerSprite, sw * 0.1f,  sh * 0.15f);
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
