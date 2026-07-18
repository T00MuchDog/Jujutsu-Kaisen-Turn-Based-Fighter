package com.jjktbf.graphics.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.ui.CombatantPanel;
import com.jjktbf.graphics.ui.battle.BattleUiAssets;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CeEfficiencyCalculator;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.view.BattleView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Graphics implementation of BattleView.
 *
 * Layout:
 *   Top half    — enemy panel (sprite + HP/CE bars)
 *   Middle      — event log (last N messages)
 *   Bottom half — player panel + planning panel
 *
 * Threading note:
 *   BattleController calls promptBattlePlan() synchronously.
 *   The graphics loop must therefore run the controller on a background thread
 *   (started by JJKGame.startBattle) while posting render state back to the
 *   LibGDX render thread via Gdx.app.postRunnable().
 *
 *   promptBattlePlan() blocks the controller thread until the player
 *   clicks "Lock In" to confirm their plan.
 */
public class BattleScreen implements Screen, BattleView {

    /** Max raw messages retained in the battle log; older ones are dropped. */
    private static final int   LOG_MAX_STORED = 50;
    /**
     * Vertical step between battle-log lines, as a multiple of the font's cap
     * height (the visible glyph height). Cap height is used rather than line
     * height because the ratio of line-height to cap-height varies a lot between
     * fonts (Press Start 2P ≈ 1.15, Atlantis International ≈ 1.8), so spacing
     * relative to line height would drift whenever the font changes. Cap height
     * tracks the actual on-screen text size, keeping the gap consistent.
     */
    private static final float LOG_LINE_SPACING = 1.7f;
    /**
     * Per-tick hold during resolution, in milliseconds. Ticks in "slow mode" —
     * the tick before a move fires, the firing tick itself, and the tick after
     * — use the longer slow hold so the action has room to breathe; everything
     * else uses the short baseline so the sweep stays brisk. Whether a given
     * tick fired is taken reactively from MOVE_FIRED events
     * (see {@link #lastTickFired}); whether the *next* tick will fire is taken
     * from the precomputed {@link #firingTicks} set.
     */
    private static final int   TICK_DURATION_MS        = 200;
    private static final int   SLOW_TICK_DURATION_MS   = 1000;
    /** Brief beat between consecutive log messages within a single tick. */
    private static final int   EVENT_DELAY_MS          = TICK_DURATION_MS / 2;
    /** Move-unleash animation length — sized to fill a firing tick's slow hold. */
    private static final float MOVE_EFFECT_DURATION_SECONDS = SLOW_TICK_DURATION_MS / 1000f;

    private final JJKGame     game;
    private final AssetLoader assets;
    private final SpriteBatch batch;

    /** Guards against double-dispose of native batch resources. */
    private boolean disposed;

    // ── Panels ────────────────────────────────────────────────────────────────
    private CombatantPanel playerPanel;
    private CombatantPanel enemyPanel;
    private Texture playerSprite;
    private Texture enemySprite;
    private final Rectangle executionHeaderBounds = new Rectangle();
    private final Rectangle logBounds = new Rectangle();
    private final Rectangle nextRoundBounds = new Rectangle();

    // ── Event log ─────────────────────────────────────────────────────────────
    private final List<String> logLines = new ArrayList<>();

    // ── Move unleash animation (render-thread state) ──────────────────────────
    private Texture unleashedMoveIcon;
    private float unleashedMoveElapsed;

    // ── Move selection state ──────────────────────────────────────────────────
    private volatile boolean inputConfirmed = false;
    private volatile boolean awaitingNextRound = false;
    private volatile boolean nextRoundConfirmed = false;
    /** True while resolution tick calls are streaming; used to pace between ticks. */
    private volatile boolean resolvingTicks = false;
    /**
     * Whether the tick most recently shown fired a move. Set in
     * {@link #displayCombatEvents} when a MOVE_FIRED event appears, then read by
     * the next {@link #displayResolutionTick} (or by
     * {@link #displayRoundEnd} for the final tick) when choosing that tick's
     * hold. Resetting it after each hold matters for non-firing ticks, which
     * produce no events and so would otherwise inherit a stale true. Touched
     * only on the battle thread, so it needs no synchronization.
     */
    private boolean lastTickFired = false;
    /**
     * Tick number whose hold is currently being paid — i.e. the tick whose
     * pacing we are deciding. Tracked so the pacing logic can ask "is this tick
     * itself a firing tick / adjacent to one?". The hold for tick N is applied
     * at the start of the {@code displayResolutionTick(N+1)} call (and in
     * {@link #displayRoundEnd} for the final tick), so this is N, not N+1.
     */
    private int heldTick = 0;
    /**
     * Absolute ticks at which some (non-stunned) segment fires this round,
     * precomputed from both combatants' timelines. Drives the "slow mode"
     * lookahead: a tick goes slow not only when it (or the previous tick)
     * fired, but also when the <em>next</em>> tick will fire — the wind-up
     * before a move unleashes. Rebuilt per round on the first tick of the
     * sweep. Touched only on the battle thread.
     */
    private Set<Integer> firingTicks = new HashSet<>();
    private boolean nextRoundHovered = false;

    /**
     * Set on the render thread when the player presses Escape to leave a battle
     * early. Read by the controller (battle) thread via {@link #isAborted()} to
     * unwind the loop, and polled by this screen's own blocking spin-waits and
     * paced sleeps so an abort unblocks promptly instead of running to a KO.
     */
    private volatile boolean abortRequested = false;

    // ── Planning panel (two-board timeline UI) ─────────────────────────────────
    private com.jjktbf.graphics.ui.battle.PlanningPanel planningPanel;

    // ── Shared render state (written by controller thread, read by render) ────
    private volatile BattleCombatant renderPlayer;
    private volatile BattleCombatant renderEnemy;
    private volatile String          phaseLabel = "";
    private volatile boolean         battleOver  = false;
    private volatile String          battleResult = "";
    private volatile int             currentExecutionTick = 0;
    /**
     * Latched true the first time the planning panel is created, and stays true
     * afterwards. Until then we draw nothing — the battle thread hasn't reached
     * planning yet, so showing the execution HUD would flash it for a frame
     * before the planning panel appears.
     */
    private volatile boolean         executionUiActive = false;

    public BattleScreen(JJKGame game, AssetLoader assets) {
        this.game   = game;
        this.assets = assets;
        this.batch  = new SpriteBatch();
        this.playerSprite = assets.playerSprite;
        this.enemySprite = assets.enemySprite;
    }

    /** Set the selected characters' side-appropriate battle sprites. */
    public void setCombatantSprites(Texture playerSprite, Texture enemySprite) {
        this.playerSprite = playerSprite != null ? playerSprite : assets.playerSprite;
        this.enemySprite = enemySprite != null ? enemySprite : assets.enemySprite;
    }

    // -------------------------------------------------------------------------
    // Screen lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void show() {
        logLines.clear();
        inputConfirmed = false;
        awaitingNextRound = false;
        nextRoundConfirmed = false;
        resolvingTicks = false;
        battleOver     = false;
        abortRequested = false;
        executionUiActive = false;
        currentExecutionTick = 0;
        unleashedMoveIcon = null;
        unleashedMoveElapsed = 0f;
    }

    /** Last frame's delta, shared with widgets that animate (e.g. HP bars). */
    private float frameDelta = 0f;

    @Override
    public void render(float delta) {
        frameDelta = delta;
        updateMoveUnleashAnimation(delta);
        clearScreen();
        // Escape aborts from any phase, including planning (where the
        // PlanningInputProcessor owns Gdx.input, so handleInput() never runs).
        // isKeyJustPressed() is polled, so it fires regardless of the active
        // input processor.
        if (!battleOver && Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            abortBattle();
        }
        handleInput();
        drawAll();
    }

    /**
     * Leave the battle early and return to the main menu. Sets the abort flag
     * (polled by the controller thread to unwind the loop) and, since the
     * controller may be blocked in one of our own view calls, unblocks those
     * spins too. The actual screen switch happens via postRunnable so it lands
     * on the render thread; hide()/show() tear the planning panel down.
     */
    private void abortBattle() {
        if (abortRequested) return; // already leaving — don't re-trigger
        abortRequested = true;

        // Unblock whichever controller-thread view call is parked right now.
        inputConfirmed    = true; // promptBattlePlan
        nextRoundConfirmed = true; // awaitNextRound

        // If the planning panel holds the input processor, release it so the
        // main menu's own processor takes over cleanly on the next screen.
        Gdx.app.postRunnable(() -> {
            planningPanel = null;
            Gdx.input.setInputProcessor(null);
            game.showMainMenu();
        });
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
        if (disposed) return;
        disposed = true;
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

        // Until the battle thread reaches the first planning phase there is
        // nothing to show — drawing the execution HUD here would flash it for a
        // few frames before the planning panel appears.
        if (!executionUiActive) return;

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
        drawNextRoundButton();
        drawMoveUnleashAnimation(sw, sh);
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

        if (resolvingTicks) drawExecutionTick(sw, sh);
    }

    private void drawExecutionTick(float sw, float sh) {
        BitmapFont timerFont = assets.fontLarge;
        float originalScaleX = timerFont.getData().scaleX;
        float originalScaleY = timerFont.getData().scaleY;
        float screenScale = Math.max(0.95f, Math.min(1.5f,
            Math.min(sw / 1024f, sh / 600f)));
        timerFont.getData().setScale(originalScaleX * screenScale, originalScaleY * screenScale);

        String text = "TICK " + currentExecutionTick;
        GlyphLayout layout = new GlyphLayout(timerFont, text);
        timerFont.setColor(Color.BLACK);
        timerFont.draw(batch, text, (sw - layout.width) / 2f, executionHeaderBounds.y - 10f * screenScale);
        timerFont.getData().setScale(originalScaleX, originalScaleY);
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
        float bottomY = logBounds.y + 22f;
        float lineStep = logFont.getCapHeight() * LOG_LINE_SPACING;

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

    private void updateMoveUnleashAnimation(float delta) {
        if (unleashedMoveIcon == null) return;
        unleashedMoveElapsed += Math.max(0f, delta);
        if (unleashedMoveElapsed >= MOVE_EFFECT_DURATION_SECONDS) {
            unleashedMoveIcon = null;
        }
    }

    private void drawMoveUnleashAnimation(float screenWidth, float screenHeight) {
        if (unleashedMoveIcon == null) return;

        float progress = Math.min(1f, unleashedMoveElapsed / MOVE_EFFECT_DURATION_SECONDS);
        float easedGrowth = 1f - (1f - progress) * (1f - progress);
        float viewportSize = Math.min(screenWidth, screenHeight);
        float startSize = Math.max(40f, Math.min(64f, viewportSize * 0.09f));
        float endSize = Math.max(startSize, Math.min(240f, viewportSize * 0.34f));
        float size = startSize + (endSize - startSize) * easedGrowth;

        batch.setColor(1f, 1f, 1f, 1f - progress);
        batch.draw(unleashedMoveIcon,
            (screenWidth - size) / 2f,
            (screenHeight - size) / 2f,
            size,
            size);
        batch.setColor(Color.WHITE);
    }

    private void playMoveUnleashAnimation(Move move) {
        if (move == null) return;
        if (move.isDefensive()) {
            unleashedMoveIcon = assets.battleUi.defenseEffectIcon;
        } else if (move.getCategory() == MoveCategory.UTILITY) {
            unleashedMoveIcon = assets.battleUi.utilityEffectIcon;
        } else {
            unleashedMoveIcon = assets.battleUi.attackEffectIcon;
        }
        unleashedMoveElapsed = 0f;
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
            executionUiActive = true;
            planningPanel = new com.jjktbf.graphics.ui.battle.PlanningPanel(
                combatant, assets.battleUi, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            planningPanel.setOnConfirm(() -> { inputConfirmed = true; });
            Gdx.input.setInputProcessor(planningPanel.inputProcessor());
            updatePanels();
            inputConfirmed = false;
        });

        while (!inputConfirmed && !abortRequested) {
            sleepMs(16);
        }

        // On abort, return an empty plan immediately — the controller will see
        // isAborted() and unwind without ever running this plan.
        if (abortRequested) {
            return new BattlePlan(combatant.getMaxApBar(), combatant.getCurrentCe());
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
        // Wait for the render-thread cleanup. Blocking on the latch lets the
        // thread park instead of busy-spinning; an interrupt (e.g. during
        // shutdown) restores the flag and returns the plan gathered so far.
        try {
            panelClosed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        BattlePlan result = holder.get();
        if (result == null) {
            // Fallback: empty plan (bank the round) — should not normally happen.
            result = new BattlePlan(combatant.getMaxApBar(), combatant.getCurrentCe());
        }
        return result;
    }



    @Override
    public void displayCombatEvents(List<CombatEvent> events, BattleState state) {
        if (abortRequested) return;
        Gdx.app.postRunnable(() -> phaseLabel = "ROUND " + state.getRoundNumber() + "  —  RESOLVING");

        for (CombatEvent e : events) {
            if (abortRequested) return;
            if (e.getType() == CombatEvent.Type.MOVE_FIRED) {
                lastTickFired = true;
                Move unleashedMove = e.getMove();
                Gdx.app.postRunnable(() -> playMoveUnleashAnimation(unleashedMove));
            }
            if (!e.getMessage().isBlank()) {
                final String msg = e.getMessage();
                Gdx.app.postRunnable(() -> {
                    addLogLine(msg);
                    updatePanels();
                });
                abortableSleepMs(EVENT_DELAY_MS);
            }
        }
    }

    @Override
    public void displayResolutionTick(int tick, BattleState state) {
        if (abortRequested) return;
        // Rebuild the round's firing-tick set when a new sweep starts, so the
        // lookahead ("tick before firing") pacing reflects this round's plan.
        if (!resolvingTicks) {
            firingTicks = collectFiringTicks(state);
            heldTick = 0;
        }
        // Hold the previous tick before advancing. A tick runs in slow mode when
        // it sits next to a move firing: the tick before (wind-up), the firing
        // tick itself, or the tick after (follow-through). lastTickFired covers
        // the firing tick reactively from actual MOVE_FIRED events — so a
        // segment stunned mid-sweep doesn't trip a false slow-mo — while the
        // precomputed firingTicks set supplies the wind-up/after lookahead.
        if (resolvingTicks) {
            abortableSleepMs(isSlowTick(heldTick, lastTickFired, firingTicks)
                ? SLOW_TICK_DURATION_MS : TICK_DURATION_MS);
        }
        lastTickFired = false;
        heldTick = tick;
        currentExecutionTick = tick;
        resolvingTicks = true;
        Gdx.app.postRunnable(() -> phaseLabel = "ROUND " + state.getRoundNumber() + "  —  RESOLVING");
    }

    @Override
    public void displayRoundEnd(BattleState state) {
        resolvingTicks = false;
        // The final tick's tail hold runs here: no further displayResolutionTick
        // call follows to apply it. Without this the last hit of a round would
        // jump straight to the "round complete" banner and skip its slow-mo.
        abortableSleepMs(isSlowTick(heldTick, lastTickFired, firingTicks)
            ? SLOW_TICK_DURATION_MS : TICK_DURATION_MS);
        lastTickFired = false;
        Gdx.app.postRunnable(() -> {
            phaseLabel = "ROUND " + Math.max(1, state.getRoundNumber() - 1) + " COMPLETE";
            updatePanels();
        });
    }

    /**
     * Absolute ticks at which some non-stunned segment fires across both
     * combatants' timelines this round. Used for slow-mode lookahead.
     */
    private static Set<Integer> collectFiringTicks(BattleState state) {
        Set<Integer> ticks = new HashSet<>();
        addFiringTicks(state.getPlayerCombatant(), ticks);
        addFiringTicks(state.getEnemyCombatant(), ticks);
        return ticks;
    }

    private static void addFiringTicks(BattleCombatant combatant, Set<Integer> into) {
        if (combatant == null) return;
        Timeline tl = combatant.getTimeline();
        if (tl == null) return;
        for (ActionSegment s : tl.getSegments()) {
            if (!s.isStunned()) into.add(s.getFireTick());
        }
    }

    /**
     * Whether the tick being held should run in slow mode. True when the tick
     * itself fired (reactive flag — exact even under stunning), or when either
     * neighbour is a precomputed firing tick (wind-up before, follow-through
     * after).
     */
    private static boolean isSlowTick(int heldTick, boolean lastTickFired, Set<Integer> firingTicks) {
        if (lastTickFired) return true;
        if (heldTick <= 0 || firingTicks == null) return false;
        return firingTicks.contains(heldTick - 1) || firingTicks.contains(heldTick + 1);
    }

    @Override
    public void awaitNextRound(BattleState state) {
        nextRoundConfirmed = false;
        Gdx.app.postRunnable(() -> {
            awaitingNextRound = true;
            nextRoundHovered = false;
        });

        while (!nextRoundConfirmed && !abortRequested) {
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
        if (abortRequested) return;
        Gdx.app.postRunnable(() -> addLogLine(message));
        abortableSleepMs(100);
    }

    /** Polled by the controller thread to unwind the loop on an Escape abort. */
    @Override
    public boolean isAborted() {
        return abortRequested;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void initPanels() {
        layoutExecutionUi(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    /** Scale applied to both combatant panels (sprites + bars). */
    private static final float COMBATANT_SCALE = 1.105f;

    /** Recreates all execution widgets from the live viewport after a resize. */
    private void layoutExecutionUi(float width, float height) {
        float margin = Math.min(42f, Math.max(26f, width * 0.035f));
        float headerHeight = 54f;
        executionHeaderBounds.set(margin, height - margin - headerHeight, width - margin * 2f, headerHeight);

        float portraitScale = Math.max(0f, Math.min(1f, (height - 600f) / 480f));
        float baseSpriteWidth = 90f + 160f * portraitScale;
        float baseSpriteHeight = baseSpriteWidth * 1.5f;
        // Sprites and bars grow by COMBATANT_SCALE.
        float spriteWidth = baseSpriteWidth * COMBATANT_SCALE;
        float spriteHeight = baseSpriteHeight * COMBATANT_SCALE;

        float sideInset = margin + 54f + 36f * portraitScale;
        float s = COMBATANT_SCALE;

        // Player (bottom-left of screen): pin the bar block's bottom-left corner
        // so the panel grows upward and to the right. The bars hang below the
        // sprite; pinning their lowest-leftmost point keeps them grounded while
        // the sprite rises. At s=1 these reduce to the original positions.
        float playerX = sideInset + 17f * (s - 1f);
        float playerY = margin + 66f + 58f * portraitScale + 84f * (s - 1f);
        playerPanel = new CombatantPanel(playerSprite, assets.battleUi,
            playerX, playerY, spriteWidth, spriteHeight, s);

        // Log box, sized and positioned below the header. Dimensions track the
        // same height-driven portraitScale the sprites use, so the box shrinks
        // and grows in lockstep with the combatant panels (no fixed floor that
        // would leave it oversized on a small window). Capped to the viewport.
        float logWidth  = Math.min(width * 0.63f, 260f + 420f * portraitScale);
        float logHeight = 150f + 105f * portraitScale;
        // Extend the log box a couple of pixels downward so the bottom line isn't clipped.
        logBounds.set(margin, executionHeaderBounds.y - 14f - logHeight - 2f, logWidth, logHeight + 2f);

        // Symmetric vertical gaps: match the gap between the header and the enemy
        // name plate to the gap between the log box bottom and the player name plate.
        // Name plate top = spriteTop + 42*scale (8*scale gap + 34*scale plate height).
        float namePlateRise = 42f * s;
        float playerTop = playerY + spriteHeight + namePlateRise;
        float gap = logBounds.y - playerTop;

        // Enemy (top-right of screen): pin the panel's top-right corner (name
        // plate top + frame right) so it grows downward and to the left. The
        // name plate top sits `gap` pixels below the header, mirroring the player.
        float enemyX = width - sideInset + 10f - spriteWidth - 10f * s;
        float enemyY = executionHeaderBounds.y - gap - spriteHeight - namePlateRise;
        enemyPanel = new CombatantPanel(enemySprite, assets.battleUi,
            enemyX, enemyY, spriteWidth, spriteHeight, s);

        // Align the Next Round button's right edge with the enemy sprite's right edge.
        float enemyRight = enemyX + spriteWidth;
        float nextRoundWidth = 210f;
        nextRoundBounds.set(enemyRight - nextRoundWidth, margin, nextRoundWidth, 52f);
        updatePanels();
    }

    private void updatePanels() {
        if (playerPanel != null && renderPlayer != null) playerPanel.update(renderPlayer);
        if (enemyPanel  != null && renderEnemy  != null) enemyPanel.update(renderEnemy);
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // Restore the interrupt flag so callers' abort/shutdown checks can
            // observe it; matches HttpApiClient / MatchWebSocketClient.
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sleep that wakes promptly on an abort. Used for the paced pauses during
     * resolution (event delay, tick delay) and message display — without it, an
     * Escape press during a multi-tick resolution would leave the battle thread
     * grinding through long sleeps in the background after the player has
     * already returned to the menu.
     */
    private void abortableSleepMs(long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (!abortRequested && System.currentTimeMillis() < deadline) {
            sleepMs(Math.min(40L, deadline - System.currentTimeMillis() + 1));
        }
    }
}
