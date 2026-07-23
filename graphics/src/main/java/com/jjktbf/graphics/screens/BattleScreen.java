package com.jjktbf.graphics.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
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
import com.jjktbf.graphics.multiplayer.MatchWebSocketClient;
import com.jjktbf.graphics.multiplayer.MultiplayerMatchService;
import com.jjktbf.graphics.multiplayer.MultiplayerSession;
import com.jjktbf.graphics.ui.CombatantPanel;
import com.jjktbf.graphics.ui.MiraclesMeter;
import com.jjktbf.graphics.ui.battle.BattleUiAssets;
import com.jjktbf.graphics.ui.battle.PlanningPanel;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.coded.CodedAbilityState;
import com.jjktbf.model.character.coded.MiraclesAbility;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CeEfficiencyCalculator;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.multiplayer.protocol.BattleEventState;
import com.jjktbf.multiplayer.protocol.BattleEventType;
import com.jjktbf.multiplayer.protocol.BattlePhase;
import com.jjktbf.multiplayer.protocol.CharacterState;
import com.jjktbf.multiplayer.protocol.ErrorResponse;
import com.jjktbf.multiplayer.protocol.MatchSetup;
import com.jjktbf.multiplayer.protocol.MatchState;
import com.jjktbf.multiplayer.protocol.MatchStatus;
import com.jjktbf.multiplayer.protocol.MoveState;
import com.jjktbf.multiplayer.protocol.PlayerSide;
import com.jjktbf.multiplayer.protocol.PlayerState;
import com.jjktbf.multiplayer.protocol.RoundStartCharacterState;
import com.jjktbf.multiplayer.protocol.SocketMessage;
import com.jjktbf.view.BattleView;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

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

    private enum BattleMode { LOCAL, MULTIPLAYER }

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
     * Per-tick hold during resolution, in milliseconds. A tick that fires a
     * move uses the longer {@link #SLOW_TICK_DURATION_MS} hold so the action
     * has room to breathe alongside the move-unleash animation; everything
     * else uses the short baseline so the sweep stays brisk. Whether a given
     * tick fired is taken reactively from MOVE_FIRED events
     * (see {@link #lastTickFired}).
     */
    private static final int   TICK_DURATION_MS        = 200;
    private static final int   SLOW_TICK_DURATION_MS   = 3000;
    /**
     * Move-unleash animation length. Fixed at 3 seconds and decoupled from the
     * firing-tick hold so it can outlast a tick; sized to dominate the slow
     * hold so a firing tick reads as a deliberate beat.
     */
    private static final float MOVE_EFFECT_DURATION_SECONDS = 3f;
    /** Chars revealed per second when typing a log line out letter-by-letter. */
    private static final float LOG_TYPE_RATE_CPS       = 30f;
    /**
     * Beat held after a log line finishes typing before it commits and the
     * battle advances, so each sentence gets a moment to land.
     */
    private static final float LOG_TYPE_TAIL_SECONDS   = 0.2f;
    /**
     * Pixels the log scrolls per mouse-wheel notch, in {@link #LOG_LINE_SPACING}
     * units (cap height). One notch ≈ one wrapped row, so a flick covers a few
     * lines without overshooting.
     */
    private static final float LOG_SCROLL_STEP_ROWS    = 1f;
    private static final float COMBATANT_HUD_SCALE     = 1.25f;

    private final JJKGame     game;
    private final AssetLoader assets;
    private final SpriteBatch batch;

    /** Guards against double-dispose of native batch resources. */
    private boolean disposed;

    // ── Panels ────────────────────────────────────────────────────────────────
    private CombatantPanel playerPanel;
    private CombatantPanel enemyPanel;
    private final MiraclesMeter miraclesMeter = new MiraclesMeter();
    private Texture playerSprite;
    private Texture enemySprite;
    private final Rectangle logBounds = new Rectangle();
    private final Rectangle nextRoundBounds = new Rectangle();

    // ── Event log ─────────────────────────────────────────────────────────────
    private final List<String> logLines = new ArrayList<>();
    /**
     * Progressive "typewriter" log reveal. Messages are queued (concurrently —
     * the battle thread enqueues, the render thread drains) and revealed one
     * character at a time by {@link #updateTyping(float)}, then committed to
     * {@link #logLines} after a short tail. {@link #committedLogSeq} is bumped
     * on each commit and read by the battle thread to gate advancement.
     * {@link #displayMessage} bypasses the gate while the execution UI is still
     * hidden (before the first planning phase), since there is nothing on screen
     * for the player to read along with — those lines still type out and commit,
     * they just don't block.
     */
    private final Queue<String> pendingTypingQueue = new ConcurrentLinkedQueue<>();
    private String typingLine;
    private int typingChars;
    private float typingCharTimer;
    private float typingTailTimer;
    /** Bumped on the render thread each time a typing line commits; read by the battle thread. */
    private volatile int committedLogSeq = 0;
    /**
     * Vertical scroll offset of the battle log, in pixels measured up from the
     * newest (bottom) line. Zero rests at the bottom; positive scrolls toward
     * older history. Only adjusted while awaiting the next round (dialogue idle);
     * reset to zero whenever a new line commits so the log snaps back to newest
     * as soon as dialogue resumes.
     */
    private float logScrollOffset = 0f;
    /**
     * Wheel listener installed as the input processor only while awaiting the
     * next round, so the log scrolls on actual scroll-wheel events (LibGDX has
     * no polled wheel API — it is delivered as a {@code scrolled} event). Mirrors
     * the editors' hover-scroll approach, but scoped to this single window so it
     * never competes with the planning panel's own input processor.
     */
    private final InputAdapter logScrollInput = new InputAdapter() {
        @Override
        public boolean scrolled(float amountX, float amountY) {
            if (!awaitingNextRound || typingInProgress()) return false;
            float x = Gdx.input.getX();
            float y = Gdx.graphics.getHeight() - Gdx.input.getY();
            if (!logBounds.contains(x, y)) return false;
            // amountY < 0 = wheel up (toward older history); invert so up scrolls back.
            adjustLogScroll(-amountY * LOG_SCROLL_STEP_ROWS);
            return true;
        }
    };
    /** Tracks whether {@link #logScrollInput} currently owns Gdx.input, to avoid re-setting it every frame. */
    private boolean logScrollInputAttached = false;

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
     * Absolute ticks at which some (non-stunned) segment fires this round,
     * precomputed from both combatants' timelines. Used only by the multiplayer
     * playback path to mark firing ticks for the slow hold; LOCAL uses the
     * reactive {@link #lastTickFired} flag instead.
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
    private PlanningPanel planningPanel;

    // ── Shared render state (written by controller thread, read by render) ────
    private volatile BattleCombatant renderPlayer;
    private volatile BattleCombatant renderEnemy;
    /**
     * LOCAL deferred HP. Seeded from the model at round boundaries and mutated
     * only on damage/heal/max-HP events (paired with the matching log line), so
     * the HP bar and HP text change when the "X took Y damage" line plays — not
     * when the move unleashes or the tick fires. Mirrors the multiplayer
     * {@code onlinePlayerHp}/{@code onlineEnemyHp} pattern. CE is left out: it
     * drains at the move's start tick, before the fire tick, so it stays live.
     * Volatile: written by the battle thread (applyLocalHpEvent) and read by the
     * render thread (updatePanels).
     */
    private volatile int localPlayerHp;
    private volatile int localPlayerMaxHp;
    private volatile int localEnemyHp;
    private volatile int localEnemyMaxHp;
    private volatile boolean         battleOver  = false;
    private volatile String          battleResult = "";
    private volatile String          battleResultReason = "";
    private volatile int             currentExecutionTick = 0;
    /**
     * Latched true the first time the planning panel is created, and stays true
     * afterwards. Until then we draw nothing — the battle thread hasn't reached
     * planning yet, so showing the execution HUD would flash it for a frame
     * before the planning panel appears.
     */
    private volatile boolean         executionUiActive = false;
    private volatile Thread          localBattleThread;

    // ── Online mode ───────────────────────────────────────────────────────────
    private BattleMode mode = BattleMode.LOCAL;
    private MatchSetup multiplayerSetup;
    private MultiplayerSession multiplayerSession;
    private MultiplayerMatchService multiplayerMatchService;
    private MultiplayerMatchService.Listener multiplayerListener;
    private MultiplayerSession.ConnectionState multiplayerConnectionState =
        MultiplayerSession.ConnectionState.DISCONNECTED;
    private MatchState multiplayerState;
    private PlayerState onlinePlayer;
    private PlayerState onlineEnemy;
    private Map<String, Move> onlineMoves = Map.of();
    private int onlinePlanningRound = -1;
    private final Set<String> loggedOnlineEventIds = new HashSet<>();
    private boolean onlineCommandPending;
    private boolean preserveMultiplayerSession;
    private long multiplayerRun;

    private List<BattleEventState> playbackEvents = List.of();
    private int playbackRound = -1;
    private int playbackEventIndex;
    private int playbackLastTick;
    private float playbackTickElapsedMs;
    private boolean playbackComplete;
    private int onlinePlayerHp;
    private int onlinePlayerMaxHp;
    private int onlinePlayerCe;
    private int onlinePlayerMaxCe;
    private CodedAbilityState onlinePlayerMiracles;
    private int onlineEnemyHp;
    private int onlineEnemyMaxHp;
    private int onlineEnemyCe;
    private int onlineEnemyMaxCe;

    public BattleScreen(JJKGame game, AssetLoader assets) {
        this.game   = game;
        this.assets = assets;
        this.batch  = new SpriteBatch();
        this.playerSprite = assets.playerSprite;
        this.enemySprite = assets.enemySprite;
    }

    /** Selects the blocking local controller path before this reusable screen is shown. */
    public void prepareLocal() {
        abortRequested = true;
        localBattleThread = null;
        detachMultiplayerListener();
        mode = BattleMode.LOCAL;
        multiplayerSetup = null;
        multiplayerSession = null;
        multiplayerMatchService = null;
        multiplayerState = null;
    }

    /** Selects the asynchronous authoritative path before this reusable screen is shown. */
    public void prepareMultiplayer(
        MatchSetup setup,
        MultiplayerSession session,
        MultiplayerMatchService matchService
    ) {
        abortRequested = true;
        localBattleThread = null;
        detachMultiplayerListener();
        mode = BattleMode.MULTIPLAYER;
        multiplayerSetup = Objects.requireNonNull(setup, "setup");
        multiplayerSession = Objects.requireNonNull(session, "session");
        multiplayerMatchService = Objects.requireNonNull(matchService, "matchService");
    }

    /** Associates local controller callbacks with the current battle run. */
    public void setLocalBattleThread(Thread battleThread) {
        localBattleThread = Objects.requireNonNull(battleThread, "battleThread");
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
        Gdx.input.setInputProcessor(null);
        logScrollInputAttached = false;
        planningPanel = null;
        logLines.clear();
        pendingTypingQueue.clear();
        typingLine = null;
        typingChars = 0;
        typingCharTimer = 0f;
        typingTailTimer = 0f;
        logScrollOffset = 0f;
        localPlayerHp = 0;
        localPlayerMaxHp = 0;
        localEnemyHp = 0;
        localEnemyMaxHp = 0;
        inputConfirmed = false;
        awaitingNextRound = false;
        nextRoundConfirmed = false;
        resolvingTicks = false;
        battleOver     = false;
        battleResultReason = "";
        abortRequested = false;
        executionUiActive = false;
        currentExecutionTick = 0;
        unleashedMoveIcon = null;
        unleashedMoveElapsed = 0f;
        playbackEvents = List.of();
        playbackRound = -1;
        playbackEventIndex = 0;
        playbackLastTick = 0;
        playbackTickElapsedMs = 0f;
        playbackComplete = false;
        onlinePlanningRound = -1;
        loggedOnlineEventIds.clear();
        onlineCommandPending = false;
        preserveMultiplayerSession = false;
        multiplayerState = null;
        onlinePlayer = null;
        onlineEnemy = null;
        onlinePlayerMiracles = null;
        miraclesMeter.clear();
        onlineMoves = Map.of();

        if (mode == BattleMode.MULTIPLAYER) {
            startMultiplayer();
        }
    }

    /** Last frame's delta, shared with widgets that animate (e.g. HP bars). */
    private float frameDelta = 0f;

    @Override
    public void render(float delta) {
        frameDelta = delta;
        updateMoveUnleashAnimation(delta);
        updateTyping(delta);
        if (mode == BattleMode.MULTIPLAYER) updateMultiplayerPlayback(delta);
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

        if (mode == BattleMode.MULTIPLAYER) {
            leaveMultiplayer();
            return;
        }

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
    @Override
    public void hide() {
        if (mode == BattleMode.MULTIPLAYER) {
            closePlanningPanel();
            detachMultiplayerListener();
        }
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        detachMultiplayerListener();
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
            // Install the wheel listener for the duration of this window so the
            // log scrolls on actual scroll-wheel events (LibGDX delivers the
            // wheel only as a `scrolled` event, not via a polled API). The
            // listener only handles the wheel, so the NEXT ROUND click below
            // still works through justTouched() polling.
            if (!logScrollInputAttached) {
                Gdx.input.setInputProcessor(logScrollInput);
                logScrollInputAttached = true;
            }

            float x = Gdx.input.getX();
            float y = Gdx.graphics.getHeight() - Gdx.input.getY();
            nextRoundHovered = nextRoundBounds.contains(x, y);

            if (Gdx.input.justTouched() && nextRoundHovered) {
                if (mode == BattleMode.MULTIPLAYER) {
                    submitReadyNextRound();
                } else {
                    nextRoundConfirmed = true;
                }
            }
            return;
        }

        // Left the await-next-round window — release the wheel listener so it
        // doesn't swallow input meant for the planning panel or next screen.
        if (logScrollInputAttached) {
            Gdx.input.setInputProcessor(null);
            logScrollInputAttached = false;
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
        drawExecutionBackground(sw, sh);
        if (enemyPanel != null && hasEnemyRenderState())
            enemyPanel.draw(batch, assets.fontMedium, assets.fontSmall, enemyCharacterName(), frameDelta);
        if (playerPanel != null && hasPlayerRenderState()) {
            playerPanel.draw(batch, assets.fontMedium, assets.fontSmall, playerCharacterName(), frameDelta);
            miraclesMeter.draw(batch, assets.battleUi);
        }
        drawLog(sw, sh);
        drawNextRoundButton();
        drawMoveUnleashAnimation(sw, sh);
        batch.end();

    }

    /** Draw the selected backdrop without distorting it at different viewport sizes. */
    private void drawExecutionBackground(float screenWidth, float screenHeight) {
        Texture background = assets.battleExecutionBackground;
        if (background == null) return;

        float scale = Math.max(
            screenWidth / background.getWidth(),
            screenHeight / background.getHeight()
        );
        float width = background.getWidth() * scale;
        float height = background.getHeight() * scale;
        batch.setColor(Color.WHITE);
        batch.draw(background, (screenWidth - width) / 2f, (screenHeight - height) / 2f, width, height);
    }

    /**
     * Render the battle log: newest entry at the bottom, older entries stacked
     * above it. While awaiting the next round (dialogue idle), the player can
     * scroll up through history with the mouse wheel; at all other times the
     * log is pinned to the newest line. Long lines wrap to a new row, and the
     * font stays fixed.
     */
    private void drawLog(float sw, float sh) {
        assets.battleUi.dialogue.draw(batch, logBounds.x, logBounds.y, logBounds.width, logBounds.height);
        assets.fontSmall.setColor(new Color(0.980f, 0.870f, 0.540f, 1f));
        assets.fontSmall.draw(batch, "BATTLE LOG", logBounds.x + 14f, logBounds.y + logBounds.height - 14f);

        BitmapFont logFont = assets.fontLog;
        float buttonSpace = awaitingNextRound ? nextRoundBounds.width + 24f : 0f;
        float textWidth = logBounds.width - 28f - buttonSpace;
        // Wrap the retained messages to the panel width (fixed font; no scaling).
        List<String> lines = wrapAll(logFont, textWidth);
        // Append the in-progress typing line (newest) — wrapped from the
        // revealed substring so multi-line messages reveal row by row.
        if (typingLine != null) {
            int shown = Math.min(typingChars, typingLine.length());
            lines.addAll(wrapText(logFont, typingLine.substring(0, shown), textWidth));
        }

        float lineStep = logFont.getCapHeight() * LOG_LINE_SPACING;
        // Drawable band inside the panel (below the title, above the baseplate).
        float bottomY = logBounds.y + 25f;
        float topY = logBounds.y + logBounds.height - 34f + lineStep;
        float visibleHeight = topY - bottomY;
        float contentHeight = lines.size() * lineStep;
        // Keep the offset within the now-current content range: it can grow
        // stale if lines were trimmed or the panel resized since the last wheel.
        logScrollOffset = clampLogScroll(logScrollOffset, contentHeight, visibleHeight);

        // Scissor to the panel interior so scrolled history never paints over
        // the title (drawn above) or spills past the panel edge. The batch uses
        // an ortho2D projection where world units map 1:1 to screen pixels
        // (see resize()), so the clip rectangle in world coords maps to pixels.
        // The title is drawn before this push, and the NEXT ROUND button is
        // drawn after this method returns, so neither is affected.
        Rectangle clip = new Rectangle(logBounds.x + 6f, logBounds.y + 6f,
            logBounds.width - 12f, logBounds.height - 12f);
        Rectangle scissors = new Rectangle();
        com.badlogic.gdx.graphics.OrthographicCamera clipCamera = new com.badlogic.gdx.graphics.OrthographicCamera();
        clipCamera.setToOrtho(false, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        com.badlogic.gdx.scenes.scene2d.utils.ScissorStack.calculateScissors(
            clipCamera, batch.getProjectionMatrix(), clip, scissors);
        boolean pushed = scissors.width > 0 && scissors.height > 0
            && com.badlogic.gdx.scenes.scene2d.utils.ScissorStack.pushScissors(scissors);

        // Bottom-anchor: walk newest → oldest, shifted down by the scroll
        // offset so older lines enter from the top. Lines scrolled below the
        // panel are drawn but clipped away; stop once a line's baseline clears
        // the top of the drawable band (older lines are all higher still).
        logFont.setColor(Color.WHITE);
        float y = bottomY - logScrollOffset;
        try {
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (y > topY) break;
                logFont.draw(batch, lines.get(i), logBounds.x + 14f, y);
                y += lineStep;
            }
            if (pushed) batch.flush();
        } finally {
            if (pushed) com.badlogic.gdx.scenes.scene2d.utils.ScissorStack.popScissors();
        }
    }

    /**
     * Clamp a candidate scroll offset to the valid range for the current log
     * content. {@code maxScroll} is how far history exceeds the visible band; a
     * log that fits entirely has no scroll room (offset pinned to zero).
     */
    private static float clampLogScroll(float offset, float contentHeight, float visibleHeight) {
        float maxScroll = Math.max(0f, contentHeight - visibleHeight);
        if (maxScroll <= 0f) return 0f;
        return Math.max(0f, Math.min(offset, maxScroll));
    }

    /**
     * Apply a wheel delta (in rows; positive = scroll up toward older history)
     * to the log scroll offset, clamped to the current content. Only meaningful
     * while awaiting the next round — the renderer ignores the offset otherwise.
     */
    private void adjustLogScroll(float rows) {
        if (rows == 0f) return;
        BitmapFont logFont = assets.fontLog;
        float lineStep = logFont.getCapHeight() * LOG_LINE_SPACING;
        float bottomY = logBounds.y + 25f;
        float topY = logBounds.y + logBounds.height - 34f + lineStep;
        float visibleHeight = topY - bottomY;
        float contentHeight = (logLines.size()
            + (typingLine != null ? 1 : 0)) * lineStep; // approx; renderer wraps precisely
        logScrollOffset = clampLogScroll(logScrollOffset + rows * lineStep, contentHeight, visibleHeight);
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
        String label = mode == BattleMode.MULTIPLAYER && localReadyForNextRound()
            ? "WAITING..." : "NEXT ROUND";
        GlyphLayout layout = new GlyphLayout(assets.fontMedium, label);
        assets.fontMedium.draw(batch, label,
            nextRoundBounds.x + (nextRoundBounds.width - layout.width) / 2f,
            nextRoundBounds.y + (nextRoundBounds.height + layout.height) / 2f);
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
        if (move.isDefensive() || move.hasTag("DEFENSIVE")) {
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
        assets.battleUi.header.draw(batch, x, y, width, 200f);
        assets.fontLarge.setColor(Color.WHITE);
        assets.fontLarge.draw(batch, "BATTLE OVER", x + 36f, y + 132f);
        assets.fontMedium.setColor(Color.YELLOW);
        assets.fontMedium.draw(batch, battleResult, x + 36f, y + 86f);
        assets.fontSmall.setColor(Color.LIGHT_GRAY);
        if (!battleResultReason.isBlank()) {
            assets.fontSmall.draw(batch, battleResultReason, x + 36f, y + 57f);
        }
        assets.fontSmall.draw(batch, "ESC: MAIN MENU", x + 36f, y + 28f);
        batch.end();

        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            if (mode == BattleMode.MULTIPLAYER) {
                leaveMultiplayer();
            } else {
                game.showMainMenu();
            }
        }
    }

    /** Append a message to the log and trim the oldest entries beyond the storage cap. */
    private void addLogLine(String message) {
        logLines.add(message);
        while (logLines.size() > LOG_MAX_STORED) logLines.remove(0);
        // New dialogue pins the view to the newest line; scrolling is only
        // useful while reviewing settled history between rounds.
        logScrollOffset = 0f;
    }

    /**
     * Queue a message for progressive letter-by-letter reveal. The battle
     * thread pairs this with {@link #waitForLogLine()} so a tick does not
     * advance until its log finishes typing.
     */
    private void queueLogLine(String message) {
        if (message == null || message.isBlank()) return;
        pendingTypingQueue.add(message);
    }

    /**
     * Advance the typewriter reveal on the render thread: pop queued messages
     * one at a time, reveal a character at {@link #LOG_TYPE_RATE_CPS}, then
     * hold for {@link #LOG_TYPE_TAIL_SECONDS} before committing the finished
     * line to {@link #logLines} and bumping {@link #committedLogSeq}.
     */
    private void updateTyping(float delta) {
        if (delta <= 0f) return;
        if (typingLine == null) {
            typingLine = pendingTypingQueue.poll();
            if (typingLine == null) return;
            typingChars = 0;
            typingCharTimer = 0f;
            typingTailTimer = 0f;
        }
        int total = typingLine.length();
        if (typingChars < total) {
            typingCharTimer += delta;
            float perChar = 1f / LOG_TYPE_RATE_CPS;
            while (typingCharTimer >= perChar && typingChars < total) {
                typingCharTimer -= perChar;
                typingChars++;
            }
            if (typingChars < total) return;
        }
        // Fully revealed — hold the tail, then commit.
        typingTailTimer += delta;
        if (typingTailTimer >= LOG_TYPE_TAIL_SECONDS) {
            addLogLine(typingLine);
            committedLogSeq++;
            typingLine = null;
            typingChars = 0;
            typingCharTimer = 0f;
            typingTailTimer = 0f;
        }
    }

    /**
     * Whether any log line is still mid-reveal or queued. Used by the
     * multiplayer playback accumulator to stall tick advancement until the log
     * catches up.
     */
    private boolean typingInProgress() {
        return typingLine != null || !pendingTypingQueue.isEmpty();
    }

    /**
     * Block the battle thread until the line queued immediately before this
     * call finishes typing and commits. Each LOCAL call queues exactly one
     * line then waits for the next {@link #committedLogSeq} bump, so multiple
     * lines in a tick type out in order. Returns promptly on abort.
     */
    private void waitForLogLine() {
        // Wait until the typewriter is fully idle — no line mid-reveal and the
        // queue drained — rather than just the next commit. A "next commit"
        // gate would mis-fire if earlier lines (e.g. the BATTLE START banner,
        // which doesn't block) were still queued ahead of this one.
        while (typingInProgress() && !abortRequested && isCurrentLocalBattleThread()) {
            sleepMs(16);
        }
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
        if (!isCurrentLocalBattleThread()) return;
        postLocal(() -> {
            renderPlayer = state.getPlayerCombatant();
            renderEnemy  = state.getEnemyCombatant();
            syncLocalHpFromModel();
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
        if (abortRequested || !isCurrentLocalBattleThread()) {
            return new BattlePlan(combatant.getMaxApBar(), combatant.getCurrentCe());
        }
        // This must happen on the controller thread before its wait loop. If it
        // only happens in the posted render callback, a prior round's confirmed
        // value can skip planning entirely.
        inputConfirmed = false;
        postLocal(() -> {
            renderPlayer = combatant;
            renderEnemy  = opponent;
            syncLocalHpFromModel();
            executionUiActive = true;
            planningPanel = new com.jjktbf.graphics.ui.battle.PlanningPanel(
                combatant, assets.battleUi, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            planningPanel.setOnConfirm(() -> { inputConfirmed = true; });
            Gdx.input.setInputProcessor(planningPanel.inputProcessor());
            updatePanels();
            inputConfirmed = false;
        });

        while (!inputConfirmed && !abortRequested && isCurrentLocalBattleThread()) {
            sleepMs(16);
        }

        // On abort, return an empty plan immediately — the controller will see
        // isAborted() and unwind without ever running this plan.
        if (abortRequested || !isCurrentLocalBattleThread()) {
            return new BattlePlan(combatant.getMaxApBar(), combatant.getCurrentCe());
        }

        // Read the plan on the render thread to avoid racing a drag-commit.
        final java.util.concurrent.atomic.AtomicReference<com.jjktbf.model.combat.BattlePlan> holder =
            new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.CountDownLatch panelClosed = new java.util.concurrent.CountDownLatch(1);
        Thread run = Thread.currentThread();
        Gdx.app.postRunnable(() -> {
            if (mode == BattleMode.LOCAL
                && localBattleThread == run
                && !abortRequested
                && game.getScreen() == this) {
                holder.set(planningPanel == null ? null : planningPanel.getPlan());
                Gdx.input.setInputProcessor(null);
                planningPanel = null;
            }
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
        if (abortRequested || !isCurrentLocalBattleThread()) return;

        for (CombatEvent e : events) {
            if (abortRequested || !isCurrentLocalBattleThread()) return;
            if (e.getType() == CombatEvent.Type.MOVE_FIRED) {
                lastTickFired = true;
                Move unleashedMove = e.getMove();
                postLocal(() -> playMoveUnleashAnimation(unleashedMove));
            }
            if (!e.getMessage().isBlank()) {
                final CombatEvent ev = e;
                final String msg = e.getMessage();
                // Apply this event's HP delta and enqueue its log line ON THE
                // BATTLE THREAD, before posting the render work. Doing this
                // inside the posted lambda left the queue empty at the moment
                // waitForLogLine() checked it, so the gate returned instantly
                // and every event's runnable piled up and ran back-to-back —
                // making HP appear to drop at the unleash line. Enqueuing here
                // guarantees the queue is non-empty when we wait.
                applyLocalHpEvent(ev);
                queueLogLine(msg);
                postLocal(this::updatePanels);
                // Round-start ability events fire before the first planning
                // phase flips executionUiActive; gating there would stall the
                // battle thread behind a blank screen. The lines still type
                // out, they just don't block (see displayMessage).
                if (executionUiActive) waitForLogLine();
            }
        }
    }

    @Override
    public void displayResolutionTick(int tick, BattleState state) {
        if (abortRequested || !isCurrentLocalBattleThread()) return;
        // Hold the previous tick before advancing. Only a tick that actually
        // fired a move (tracked reactively via lastTickFired) gets the long
        // slow hold; everything else uses the brisk baseline. The reactive
        // flag means a segment stunned mid-sweep won't trip a false slow-mo.
        if (resolvingTicks) {
            abortableSleepMs(isSlowTick(lastTickFired)
                ? SLOW_TICK_DURATION_MS : TICK_DURATION_MS);
        }
        lastTickFired = false;
        currentExecutionTick = tick;
        resolvingTicks = true;
    }

    @Override
    public void displayRoundEnd(BattleState state) {
        if (!isCurrentLocalBattleThread()) return;
        resolvingTicks = false;
        // The final tick's tail hold runs here: no further displayResolutionTick
        // call follows to apply it. Without this the last hit of a round would
        // jump straight to the "round complete" banner and skip its slow-mo.
        abortableSleepMs(isSlowTick(lastTickFired)
            ? SLOW_TICK_DURATION_MS : TICK_DURATION_MS);
        lastTickFired = false;
        postLocal(() -> {
            // Re-seed from the model so end-of-round maintenance (poison, max-HP
            // changes, etc.) converges the deferred bars back to the true HP.
            syncLocalHpFromModel();
            updatePanels();
        });
    }

    /**
     * Whether the tick being held should run in slow mode — i.e. it fired a
     * move. LOCAL uses the reactive flag (exact even under stunning);
     * multiplayer passes {@code firingTicks.contains(currentExecutionTick)}.
     */
    private static boolean isSlowTick(boolean fired) {
        return fired;
    }

    @Override
    public void awaitNextRound(BattleState state) {
        if (!isCurrentLocalBattleThread()) return;
        nextRoundConfirmed = false;
        postLocal(() -> {
            awaitingNextRound = true;
            nextRoundHovered = false;
        });

        while (!nextRoundConfirmed && !abortRequested && isCurrentLocalBattleThread()) {
            sleepMs(16);
        }

        postLocal(() -> {
            awaitingNextRound = false;
            nextRoundHovered = false;
        });
    }

    @Override
    public void displayBattleOver(BattleCombatant winner, BattleState state) {
        if (!isCurrentLocalBattleThread()) return;
        postLocal(() -> {
            if (winner == null) {
                battleResult = "DRAW!";
            } else {
                battleResult = winner.getCharacter().getName() + " WINS!";
            }
            battleOver = true;
        });
    }

    @Override
    public void displayMessage(String message) {
        if (abortRequested || !isCurrentLocalBattleThread()) return;
        // Enqueue on the battle thread so the queue is non-empty when we gate
        // (see displayCombatEvents for why enqueuing inside postLocal races).
        queueLogLine(message);
        // The only displayMessage call is the opening "BATTLE START" banner,
        // which runs before the first planning phase flips executionUiActive.
        // Blocking there would stall the battle thread behind a blank screen
        // (the execution HUD isn't drawn yet), so skip the gate until the UI
        // the player reads the log against is actually up. The line still
        // types out and commits; it just doesn't block here.
        if (executionUiActive) waitForLogLine();
    }

    /** Polled by the controller thread to unwind the loop on an Escape abort. */
    @Override
    public boolean isAborted() {
        return abortRequested || !isCurrentLocalBattleThread();
    }

    // -------------------------------------------------------------------------
    // Authoritative multiplayer flow (render thread)
    // -------------------------------------------------------------------------

    private void startMultiplayer() {
        if (multiplayerSetup == null || multiplayerMatchService == null) return;

        executionUiActive = true;
        multiplayerConnectionState = MultiplayerSession.ConnectionState.DISCONNECTED;
        long run = ++multiplayerRun;

        setCombatantSprites(
            assets.characterBattleSprite(
                game.multiplayerSpriteAsset(multiplayerSetup.playerCharacterId()),
                false,
                assets.playerSprite
            ),
            assets.characterBattleSprite(
                game.multiplayerSpriteAsset(multiplayerSetup.opponentCharacterId()),
                true,
                assets.enemySprite
            )
        );

        multiplayerListener = new MultiplayerBattleListener(run, multiplayerSetup.matchId());
        multiplayerMatchService.addListener(multiplayerListener);
        if (multiplayerSetup.state() != null) applyMultiplayerState(multiplayerSetup.state());

        multiplayerMatchService.connect(multiplayerSetup).whenComplete((ignored, failure) -> {
            if (failure != null) {
                postMultiplayer(run, () -> addLogLine(
                    "Could not connect to the authoritative match: " + safeMessage(failure)));
            }
        });
    }

    private void applyMultiplayerState(MatchState state) {
        if (state == null || multiplayerSetup == null
            || !multiplayerSetup.matchId().equals(state.matchId())) {
            return;
        }

        PlayerState local = state.player(multiplayerSetup.playerSide()).orElse(null);
        PlayerState opponent = state.player(opposite(multiplayerSetup.playerSide())).orElse(null);
        if (local == null || opponent == null
            || local.character() == null || opponent.character() == null) {
            addLogLine("The server returned an incomplete battle state.");
            return;
        }

        multiplayerState = state;
        onlinePlayer = local;
        onlineEnemy = opponent;
        if (!resolvingTicks || state.phase() == BattlePhase.PLANNING) {
            onlinePlayerMiracles = findMiraclesState(local.character().codedAbilities());
        }
        initOnlineMoves(local.character());
        initPanels();

        if (state.phase() == BattlePhase.PLANNING && !isTerminal(state.status())) {
            awaitingNextRound = false;
            nextRoundHovered = false;
            resolvingTicks = false;
            playbackComplete = false;
            currentExecutionTick = 0;
            onlinePlayerHp = local.character().currentHp();
            onlinePlayerMaxHp = local.character().maxHp();
            onlinePlayerCe = local.character().currentCe();
            onlinePlayerMaxCe = local.character().maxCe();
            onlineEnemyHp = opponent.character().currentHp();
            onlineEnemyMaxHp = opponent.character().maxHp();
            onlineEnemyCe = opponent.character().currentCe();
            onlineEnemyMaxCe = opponent.character().maxCe();
            updatePanels();
            logOnlineEvents(state.recentEvents());

            if (local.planSubmitted()) {
                if (planningPanel != null) planningPanel.lock();
            } else {
                if (planningPanel != null && onlinePlanningRound == state.roundNumber()
                    && !onlineCommandPending) {
                    planningPanel.unlock();
                }
                ensureOnlinePlanner(state.roundNumber(), local.character());
            }
            return;
        }

        if ((state.phase() == BattlePhase.ROUND_END
            || state.phase() == BattlePhase.BATTLE_OVER)
            && playbackRound != state.roundNumber()) {
            startMultiplayerPlayback(state);
        } else if (state.phase() == BattlePhase.BATTLE_OVER) {
            refreshTerminalPlayback(state);
        }
        updatePanels();
    }

    private void ensureOnlinePlanner(int roundNumber, CharacterState character) {
        if (onlinePlanningRound == roundNumber && planningPanel != null) return;
        if (multiplayerState == null
            || multiplayerState.status() != MatchStatus.ACTIVE
            || multiplayerConnectionState != MultiplayerSession.ConnectionState.CONNECTED
            || onlineCommandPending) {
            return;
        }

        Map<String, Integer> ceCosts = new HashMap<>();
        List<Move> availableMoves = new ArrayList<>();
        for (MoveState moveState : character.knownMoves()) {
            Move move = onlineMoves.get(moveState.moveId());
            if (move != null && moveState.available()) {
                availableMoves.add(move);
                ceCosts.put(move.getId(), moveState.effectiveCeCost());
            }
        }

        int apBudget = character.plan() == null
            ? character.maxAp() : character.plan().apBudget();
        int ceBudget = character.plan() == null
            ? character.currentCe() : character.plan().ceBudget();
        planningPanel = new PlanningPanel(
            availableMoves,
            ceCosts,
            apBudget,
            ceBudget,
            findMiraclesState(character.codedAbilities()),
            assets.battleUi,
            Gdx.graphics.getWidth(),
            Gdx.graphics.getHeight()
        );
        planningPanel.setOnConfirm(this::submitOnlinePlan);
        Gdx.input.setInputProcessor(planningPanel.inputProcessor());
        onlinePlanningRound = roundNumber;
    }

    private void initOnlineMoves(CharacterState character) {
        Map<String, Move> converted = new HashMap<>();
        for (MoveState state : character.knownMoves()) {
            try {
                converted.put(state.moveId(), toDisplayMove(state));
            } catch (RuntimeException failure) {
                addLogLine("Could not display move " + state.name() + ".");
            }
        }
        onlineMoves = Map.copyOf(converted);
    }

    static Move toDisplayMove(MoveState state) {
        MoveCategory category = MoveCategory.valueOf(state.category());
        EnumSet<MoveTag> tags = EnumSet.noneOf(MoveTag.class);
        for (String tagName : state.tags()) {
            try {
                tags.add(MoveTag.valueOf(tagName));
            } catch (IllegalArgumentException ignored) {
                // Unknown future tags are presentation-only on an older client.
            }
        }
        Map<String, Integer> prerequisites = new HashMap<>();
        if (category.getTags().contains(MoveTag.INNATE_TECHNIQUE)) {
            prerequisites.put("cursedTechniqueMastery", 0);
        }
        if (category.getTags().contains(MoveTag.NON_INNATE_TECHNIQUE)) {
            prerequisites.put("jujutsuSkill", 0);
        }

        Move.Builder builder = new Move.Builder(state.moveId())
            .name(state.name())
            .description(state.description())
            .category(category)
            .tags(tags)
            .basePower(state.basePower())
            .baseAccuracy(state.baseAccuracy())
            .neverMiss(state.neverMiss())
            .stun(tags.contains(MoveTag.STUN))
            .guardBreak(tags.contains(MoveTag.GUARD_BREAK))
            .heavy(tags.contains(MoveTag.HEAVY))
            .apCost(state.apCost())
            .unleashPoint(state.unleashPoint())
            .baseCeCost(state.baseCeCost())
            .hasCeCost(state.hasCeCost())
            .minCeCost(state.minCeCost())
            .maxCeCost(state.maxCeCost())
            .prerequisites(prerequisites)
            .freeMove(true);
        if (category.getTags().contains(MoveTag.INNATE_TECHNIQUE)) {
            builder.requiredTechniqueId("ONLINE_DISPLAY");
        }
        return builder.build();
    }

    private void submitOnlinePlan() {
        if (!canSubmitOnlinePlan() || planningPanel == null) {
            if (planningPanel != null) planningPanel.unlock();
            return;
        }
        MultiplayerMatchService.PlanSubmission submission =
            multiplayerMatchService.submitPlan(planningPanel.getPlacements());
        if (!submission.sent()) {
            planningPanel.unlock();
            addLogLine(submissionMessage(submission.status()));
            return;
        }
        onlineCommandPending = true;
        addLogLine("Plan locked. Waiting for the opponent.");
    }

    private boolean canSubmitOnlinePlan() {
        return multiplayerState != null
            && multiplayerState.status() == MatchStatus.ACTIVE
            && multiplayerState.phase() == BattlePhase.PLANNING
            && multiplayerConnectionState == MultiplayerSession.ConnectionState.CONNECTED
            && !onlineCommandPending
            && onlinePlayer != null
            && !onlinePlayer.planSubmitted();
    }

    private void startMultiplayerPlayback(MatchState state) {
        closePlanningPanel();
        playbackRound = state.roundNumber();
        playbackComplete = false;
        playbackEventIndex = 0;
        playbackTickElapsedMs = 0f;
        currentExecutionTick = 0;
        resolvingTicks = true;
        awaitingNextRound = false;
        battleOver = false;

        playbackEvents = state.recentEvents().stream()
            .filter(event -> event.roundNumber() == playbackRound)
            .toList();
        playbackLastTick = Math.max(
            state.currentTick(),
            playbackEvents.stream().mapToInt(BattleEventState::tick).max().orElse(0)
        );
        firingTicks = onlineFiringTicks(state);

        onlinePlayerHp = roundStartValue(
            state, multiplayerSetup.playerSide(), true, onlinePlayer.character().currentHp());
        onlinePlayerMaxHp = roundStartMaximum(
            state, multiplayerSetup.playerSide(), true, onlinePlayer.character().maxHp());
        onlinePlayerCe = roundStartValue(
            state, multiplayerSetup.playerSide(), false, onlinePlayer.character().currentCe());
        onlinePlayerMaxCe = roundStartMaximum(
            state, multiplayerSetup.playerSide(), false, onlinePlayer.character().maxCe());
        onlineEnemyHp = roundStartValue(
            state, opposite(multiplayerSetup.playerSide()), true, onlineEnemy.character().currentHp());
        onlineEnemyMaxHp = roundStartMaximum(
            state, opposite(multiplayerSetup.playerSide()), true, onlineEnemy.character().maxHp());
        onlineEnemyCe = roundStartValue(
            state, opposite(multiplayerSetup.playerSide()), false, onlineEnemy.character().currentCe());
        onlineEnemyMaxCe = roundStartMaximum(
            state, opposite(multiplayerSetup.playerSide()), false, onlineEnemy.character().maxCe());
        onlinePlayerMiracles = roundStartMiraclesState(
            state, multiplayerSetup.playerSide(), onlinePlayer.character().codedAbilities());
        processPlaybackEventsThrough(0);
        updatePanels();
    }

    private void updateMultiplayerPlayback(float delta) {
        if (!resolvingTicks || playbackComplete || multiplayerState == null) return;
        // Don't advance (or accumulate) while a log line is still typing, so
        // a tick that just queued messages can't outpace the typewriter.
        if (typingInProgress()) return;
        playbackTickElapsedMs += Math.max(0f, delta) * 1000f;

        while (resolvingTicks) {
            int duration = isSlowTick(firingTicks.contains(currentExecutionTick))
                ? SLOW_TICK_DURATION_MS : TICK_DURATION_MS;
            if (playbackTickElapsedMs < duration) return;
            playbackTickElapsedMs -= duration;

            if (currentExecutionTick >= playbackLastTick) {
                finishMultiplayerPlayback();
                return;
            }
            currentExecutionTick++;
            processPlaybackEventsThrough(currentExecutionTick);
            // If advancing the tick just queued log lines, hold further
            // advancement until they type out.
            if (typingInProgress()) return;
        }
    }

    private void processPlaybackEventsThrough(int tick) {
        while (playbackEventIndex < playbackEvents.size()
            && playbackEvents.get(playbackEventIndex).tick() <= tick) {
            BattleEventState event = playbackEvents.get(playbackEventIndex++);
            applyPlaybackEvent(event);
        }
        updatePanels();
    }

    private void refreshTerminalPlayback(MatchState state) {
        List<BattleEventState> merged = new ArrayList<>(playbackEvents);
        Set<String> eventIds = new HashSet<>();
        for (BattleEventState event : playbackEvents) {
            if (event.eventId() != null) eventIds.add(event.eventId());
        }
        for (BattleEventState event : state.recentEvents()) {
            if (event.roundNumber() == state.roundNumber()
                && (event.eventId() == null || eventIds.add(event.eventId()))) {
                merged.add(event);
            }
        }
        playbackEvents = List.copyOf(merged);
        playbackLastTick = Math.max(
            playbackLastTick,
            Math.max(
                state.currentTick(),
                playbackEvents.stream().mapToInt(BattleEventState::tick).max().orElse(0)
            )
        );
        processPlaybackEventsThrough(
            playbackComplete ? Integer.MAX_VALUE : currentExecutionTick);
        if (playbackComplete) showMultiplayerResult(state);
    }

    private void applyPlaybackEvent(BattleEventState event) {
        Integer value = event.value();
        if (value != null && event.type() == BattleEventType.DAMAGE_DEALT) {
            if (event.targetSide() == multiplayerSetup.playerSide()) {
                onlinePlayerHp = Math.max(0, onlinePlayerHp - value);
            } else if (event.targetSide() == opposite(multiplayerSetup.playerSide())) {
                onlineEnemyHp = Math.max(0, onlineEnemyHp - value);
            }
        } else if (value != null && event.type() == BattleEventType.HP_RESTORED) {
            // The server reports the already-capped amount. A following max-HP
            // event may establish the cap used during deferred round-end work.
            if (event.targetSide() == multiplayerSetup.playerSide()) {
                onlinePlayerHp += value;
            } else if (event.targetSide() == opposite(multiplayerSetup.playerSide())) {
                onlineEnemyHp += value;
            }
        } else if (value != null && event.type() == BattleEventType.MAX_HP_CHANGED) {
            if (event.targetSide() == multiplayerSetup.playerSide()) {
                onlinePlayerMaxHp = Math.max(1, value);
                onlinePlayerHp = Math.min(onlinePlayerHp, onlinePlayerMaxHp);
            } else if (event.targetSide() == opposite(multiplayerSetup.playerSide())) {
                onlineEnemyMaxHp = Math.max(1, value);
                onlineEnemyHp = Math.min(onlineEnemyHp, onlineEnemyMaxHp);
            }
        } else if (value != null && event.type() == BattleEventType.MAX_CE_CHANGED) {
            if (event.targetSide() == multiplayerSetup.playerSide()) {
                onlinePlayerMaxCe = Math.max(0, value);
                onlinePlayerCe = Math.min(onlinePlayerCe, onlinePlayerMaxCe);
            } else if (event.targetSide() == opposite(multiplayerSetup.playerSide())) {
                onlineEnemyMaxCe = Math.max(0, value);
                onlineEnemyCe = Math.min(onlineEnemyCe, onlineEnemyMaxCe);
            }
        } else if (value != null && event.type() == BattleEventType.CE_DRAINED) {
            drainPlaybackCe(
                event.targetSide() != null ? event.targetSide() : event.sourceSide(), value);
        } else if (value != null && event.type() == BattleEventType.CE_RESTORED) {
            restorePlaybackCe(
                event.targetSide() != null ? event.targetSide() : event.sourceSide(), value);
        }

        CodedAbilityState codedAbilityState = event.codedAbilityState();
        if (event.sourceSide() == multiplayerSetup.playerSide()
            && codedAbilityState != null
            && MiraclesAbility.KEY.equals(codedAbilityState.key())) {
            onlinePlayerMiracles = codedAbilityState;
        }

        if (event.type() == BattleEventType.MOVE_FIRED && event.moveId() != null) {
            Move move = findOnlineMove(event.sourceSide(), event.moveId());
            if (move != null) playMoveUnleashAnimation(move);
        }
        if (event.message() != null && !event.message().isBlank()
            && (event.eventId() == null || loggedOnlineEventIds.add(event.eventId()))) {
            queueLogLine(event.message());
        }
    }

    private void logOnlineEvents(List<BattleEventState> events) {
        for (BattleEventState event : events) {
            if (event.message() != null && !event.message().isBlank()
                && (event.eventId() == null || loggedOnlineEventIds.add(event.eventId()))) {
                queueLogLine(event.message());
            }
        }
    }

    private void drainPlaybackCe(PlayerSide side, int amount) {
        if (side == multiplayerSetup.playerSide()) {
            onlinePlayerCe = Math.max(0, onlinePlayerCe - amount);
        } else if (side == opposite(multiplayerSetup.playerSide())) {
            onlineEnemyCe = Math.max(0, onlineEnemyCe - amount);
        }
    }

    private void restorePlaybackCe(PlayerSide side, int amount) {
        // As with HP restoration, the event amount was capped authoritatively.
        if (side == multiplayerSetup.playerSide()) {
            onlinePlayerCe += amount;
        } else if (side == opposite(multiplayerSetup.playerSide())) {
            onlineEnemyCe += amount;
        }
    }

    private Move findOnlineMove(PlayerSide sourceSide, String moveId) {
        PlayerState source = sourceSide == multiplayerSetup.playerSide()
            ? onlinePlayer : onlineEnemy;
        if (source != null) {
            for (MoveState state : source.character().knownMoves()) {
                if (moveId.equals(state.moveId())) {
                    try {
                        return toDisplayMove(state);
                    } catch (RuntimeException ignored) {
                        return null;
                    }
                }
            }
        }
        return onlineMoves.get(moveId);
    }

    private void finishMultiplayerPlayback() {
        processPlaybackEventsThrough(Integer.MAX_VALUE);
        playbackComplete = true;
        resolvingTicks = false;
        onlinePlayerHp = onlinePlayer.character().currentHp();
        onlinePlayerMaxHp = onlinePlayer.character().maxHp();
        onlinePlayerCe = onlinePlayer.character().currentCe();
        onlinePlayerMaxCe = onlinePlayer.character().maxCe();
        onlineEnemyHp = onlineEnemy.character().currentHp();
        onlineEnemyMaxHp = onlineEnemy.character().maxHp();
        onlineEnemyCe = onlineEnemy.character().currentCe();
        onlineEnemyMaxCe = onlineEnemy.character().maxCe();
        onlinePlayerMiracles = findMiraclesState(onlinePlayer.character().codedAbilities());
        updatePanels();

        if (isTerminal(multiplayerState.status())
            || multiplayerState.phase() == BattlePhase.BATTLE_OVER) {
            showMultiplayerResult(multiplayerState);
            return;
        }
        awaitingNextRound = true;
        nextRoundHovered = false;
    }

    private void submitReadyNextRound() {
        if (multiplayerState == null || onlineCommandPending
            || localReadyForNextRound()
            || multiplayerConnectionState != MultiplayerSession.ConnectionState.CONNECTED) {
            return;
        }
        MultiplayerMatchService.PlanSubmission submission =
            multiplayerMatchService.readyNextRound();
        if (!submission.sent()) {
            addLogLine(submissionMessage(submission.status()));
            return;
        }
        onlineCommandPending = true;
    }

    private boolean localReadyForNextRound() {
        return onlinePlayer != null && onlinePlayer.readyForNextRound();
    }

    private void showMultiplayerResult(MatchState state) {
        awaitingNextRound = false;
        if (state.winnerSide() == null) {
            battleResult = "DRAW!";
        } else if (state.winnerSide() == multiplayerSetup.playerSide()) {
            battleResult = "VICTORY!";
        } else {
            battleResult = "DEFEAT!";
        }
        battleResultReason = state.endReason() == null
            ? "" : state.endReason().replace('_', ' ');
        battleOver = true;
    }

    private void closePlanningPanel() {
        planningPanel = null;
        Gdx.input.setInputProcessor(null);
    }

    private void leaveMultiplayer() {
        closePlanningPanel();
        detachMultiplayerListener();
        multiplayerRun++;
        if (multiplayerMatchService != null && !preserveMultiplayerSession) {
            multiplayerMatchService.disconnect();
        }
        game.showMultiplayerMenu();
    }

    private void detachMultiplayerListener() {
        if (multiplayerListener != null && multiplayerMatchService != null) {
            multiplayerMatchService.removeListener(multiplayerListener);
        }
        multiplayerListener = null;
    }

    private void postMultiplayer(long run, Runnable callback) {
        Gdx.app.postRunnable(() -> {
            if (mode == BattleMode.MULTIPLAYER
                && multiplayerRun == run
                && game.getScreen() == this) {
                callback.run();
            }
        });
    }

    private static int roundStartValue(
        MatchState state,
        PlayerSide side,
        boolean hp,
        int fallback
    ) {
        for (RoundStartCharacterState start : state.roundStartCharacterStates()) {
            if (start.side() == side) return hp ? start.currentHp() : start.currentCe();
        }
        return fallback;
    }

    private static int roundStartMaximum(
        MatchState state,
        PlayerSide side,
        boolean hp,
        int fallback
    ) {
        for (RoundStartCharacterState start : state.roundStartCharacterStates()) {
            if (start.side() == side) return hp ? start.maxHp() : start.maxCe();
        }
        return fallback;
    }

    private static Set<Integer> onlineFiringTicks(MatchState state) {
        Set<Integer> ticks = new HashSet<>();
        for (PlayerState player : state.players()) {
            if (player.character() == null || player.character().plan() == null) continue;
            player.character().plan().queuedSegments().forEach(segment -> ticks.add(segment.fireTick()));
            player.character().plan().resolvedSegments().stream()
                .filter(segment -> segment.status() == null
                    || !"STUNNED".equals(segment.status().name()))
                .forEach(segment -> ticks.add(segment.fireTick()));
        }
        return ticks;
    }

    private static PlayerSide opposite(PlayerSide side) {
        return side == PlayerSide.PLAYER_ONE ? PlayerSide.PLAYER_TWO : PlayerSide.PLAYER_ONE;
    }

    private static boolean isTerminal(MatchStatus status) {
        return status == MatchStatus.ENDED || status == MatchStatus.ABANDONED;
    }

    private static String submissionMessage(MultiplayerMatchService.SubmissionStatus status) {
        return switch (status) {
            case NO_MATCH -> "No active match is available.";
            case NOT_CONNECTED -> "The match is not connected yet.";
            case ALREADY_PENDING -> "A command is already waiting for the server.";
            case MATCH_ENDED -> "The match has already ended.";
            case SERVICE_CLOSED -> "The multiplayer service is closed.";
            case SENT -> "Command sent.";
        };
    }

    private static String safeMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null
            && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
            ? "The multiplayer service is unavailable." : message;
    }

    private final class MultiplayerBattleListener implements MultiplayerMatchService.Listener {
        private final long run;
        private final String matchId;

        private MultiplayerBattleListener(long run, String matchId) {
            this.run = run;
            this.matchId = matchId;
        }

        @Override
        public void onConnectionStateChanged(MultiplayerSession.ConnectionState state) {
            postMultiplayer(run, () -> {
                multiplayerConnectionState = state;
                if (state == MultiplayerSession.ConnectionState.CONNECTED) {
                    addLogLine("Connected. Both players can now plan their round.");
                    if (multiplayerState != null && onlinePlayer != null
                        && multiplayerState.phase() == BattlePhase.PLANNING
                        && !onlinePlayer.planSubmitted()) {
                        ensureOnlinePlanner(multiplayerState.roundNumber(), onlinePlayer.character());
                    }
                }
            });
        }

        @Override
        public void onReconnecting(int attempt, Duration delay) {
            postMultiplayer(run, () -> {
                onlineCommandPending = false;
                addLogLine("Connection interrupted. Retrying in "
                    + delay.toSeconds() + " second(s).");
            });
        }

        @Override
        public void onDisconnected(MatchWebSocketClient.DisconnectReason reason) {
            postMultiplayer(run, () -> {
                multiplayerConnectionState = MultiplayerSession.ConnectionState.DISCONNECTED;
                onlineCommandPending = false;
                if (reason == MatchWebSocketClient.DisconnectReason.RETRIES_EXHAUSTED
                    && (multiplayerState == null || !isTerminal(multiplayerState.status()))) {
                    preserveMultiplayerSession = true;
                    game.showMultiplayerDisconnected("Reconnect attempts were exhausted.");
                }
            });
        }

        @Override
        public void onMatchState(MatchState state) {
            if (matchId.equals(state.matchId())) {
                postMultiplayer(run, () -> applyMultiplayerState(state));
            }
        }

        @Override
        public void onPlayerConnectionChanged(SocketMessage message) {
            postMultiplayer(run, () -> {
                if (message.playerSide() != multiplayerSetup.playerSide()) {
                    addLogLine(message.type().name().contains("DISCONNECTED")
                        ? "Opponent disconnected. Waiting for their return."
                        : "Opponent connected.");
                }
            });
        }

        @Override
        public void onCommandCompleted(MultiplayerMatchService.CommandOutcome outcome) {
            postMultiplayer(run, () -> {
                onlineCommandPending = false;
                if (!outcome.accepted()) unlockPlannerIfPlanOpen();
            });
        }

        @Override
        public void onCommandRejected(String commandId, ErrorResponse error) {
            postMultiplayer(run, () -> {
                onlineCommandPending = false;
                unlockPlannerIfPlanOpen();
                addLogLine(error == null
                    ? "The server rejected the command."
                    : error.message());
            });
        }

        @Override
        public void onMatchEnded(MatchState state) {
            if (matchId.equals(state.matchId())) {
                postMultiplayer(run, () -> applyMultiplayerState(state));
            }
        }

        @Override
        public void onError(String code, String userMessage, Throwable cause) {
            postMultiplayer(run, () -> addLogLine("[" + code + "] " + userMessage));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void initPanels() {
        layoutExecutionUi(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    /** Recreates all execution widgets from the live viewport after a resize. */
    private void layoutExecutionUi(float width, float height) {
        float margin = Math.min(32f, Math.max(16f, Math.min(width, height) * 0.035f));

        float logHeight = Math.min(145f, Math.max(100f, height * 0.22f));
        float logTop = margin + logHeight;
        logBounds.set(margin, 3f, width - margin * 2f, logTop - 3f);

        float fieldBottom = logBounds.y + logBounds.height + 12f;
        float fieldTop = height - margin;
        float fieldHeight = Math.max(1f, fieldTop - fieldBottom);

        float hudWidth = Math.min(430f, Math.max(150f, width * 0.40f));
        hudWidth = Math.min(hudWidth, Math.max(1f, (width - margin * 2f - 12f) / 2f));
        hudWidth *= COMBATANT_HUD_SCALE;
        float hudHeight = Math.min(108f, Math.max(82f, fieldHeight * 0.29f)) * COMBATANT_HUD_SCALE;
        float playerHudY = fieldBottom + fieldHeight * 0.08f;
        float availableCenterGap = width - margin * 2f - hudWidth * 2f;
        float hudShift = Math.min(Math.min(70f, width * 0.035f),
            Math.max(0f, (availableCenterGap - 12f) / 2f));

        float enemyPlateSize = Math.min(fieldHeight * 0.84f, width * 0.38f);
        float playerPlateSize = Math.min(fieldHeight * 1.08f, width * 0.46f);
        float enemyCenterX = width - margin - width * 0.22f;
        float playerCenterX = margin + width * 0.22f;

        float enemySpriteSize = Math.min(fieldHeight * 0.50f, width * 0.20f);
        float playerSpriteSize = Math.min(fieldHeight * 0.58f, width * 0.23f);
        // Drop both complete fighter groups by the player's former gap to the log.
        float fighterDrop = 12f + fieldHeight * 0.12f;
        float enemySpriteY = fieldTop - enemySpriteSize - fighterDrop;
        float playerSpriteY = fieldBottom + fieldHeight * 0.12f - fighterDrop;
        // Then lower each plate again relative to its sprite, matching the authored footing.
        float plateDrop = fieldHeight * 0.045f;
        // The visible stone ellipse occupies about 28% of its square texture.
        // Seven percent of the texture is therefore roughly one quarter of the visible height.
        float enemyPlateCenterY = enemySpriteY + enemySpriteSize * 0.13f
            - plateDrop + enemyPlateSize * 0.07f;
        float playerPlateCenterY = playerSpriteY + playerSpriteSize * 0.13f - plateDrop;

        float enemyHudY = enemySpriteY + enemySpriteSize - hudHeight;

        Rectangle enemyPlate = new Rectangle(
            enemyCenterX - enemyPlateSize / 2f,
            enemyPlateCenterY - enemyPlateSize * 0.516f,
            enemyPlateSize,
            enemyPlateSize
        );
        Rectangle enemySpriteBounds = new Rectangle(
            enemyCenterX - enemySpriteSize / 2f,
            enemySpriteY,
            enemySpriteSize,
            enemySpriteSize
        );
        Rectangle enemyHud = new Rectangle(margin + hudShift, enemyHudY, hudWidth, hudHeight);
        enemyPanel = new CombatantPanel(enemySprite, assets.stoneBasePlate, assets.battleUi,
            enemyPlate, enemySpriteBounds, enemyHud, COMBATANT_HUD_SCALE);

        Rectangle playerPlate = new Rectangle(
            playerCenterX - playerPlateSize / 2f,
            playerPlateCenterY - playerPlateSize * 0.516f,
            playerPlateSize,
            playerPlateSize
        );
        Rectangle playerSpriteBounds = new Rectangle(
            playerCenterX - playerSpriteSize / 2f,
            playerSpriteY,
            playerSpriteSize,
            playerSpriteSize
        );
        Rectangle playerHud = new Rectangle(
            width - margin - hudWidth - hudShift, playerHudY, hudWidth, hudHeight);
        playerPanel = new CombatantPanel(playerSprite, assets.stoneBasePlate, assets.battleUi,
            playerPlate, playerSpriteBounds, playerHud, COMBATANT_HUD_SCALE);

        float miracleSize = Math.min(MiraclesMeter.sizeForViewport(height),
            Math.min(hudHeight, width * 0.11f));
        miraclesMeter.setBounds(
            Math.max(margin, playerHud.x - miracleSize - 14f),
            playerHud.y + (playerHud.height - miracleSize) / 2f,
            miracleSize
        );

        float nextRoundWidth = Math.min(210f, Math.max(150f, width * 0.20f));
        float nextRoundHeight = Math.min(54f, logBounds.height - 24f);
        nextRoundBounds.set(
            logBounds.x + logBounds.width - nextRoundWidth - 14f,
            logBounds.y + 14f,
            nextRoundWidth,
            nextRoundHeight
        );
        updatePanels();
    }

    /**
     * Seed the LOCAL deferred HP ints from the live model. Called at round
     * boundaries so the bars start each round accurate and end-of-round
     * maintenance (poison, max-HP changes) converges them back to the model.
     */
    private void syncLocalHpFromModel() {
        if (renderPlayer != null) {
            localPlayerHp    = renderPlayer.getCurrentHp();
            localPlayerMaxHp = renderPlayer.getMaxHp();
        }
        if (renderEnemy != null) {
            localEnemyHp    = renderEnemy.getCurrentHp();
            localEnemyMaxHp = renderEnemy.getMaxHp();
        }
    }

    /**
     * Apply an event's HP delta to the LOCAL deferred ints. Called in the same
     * posted runnable as the event's log line, so the bar/HP-text change lands
     * when that line plays. Only damage/heal/max-HP events touch HP; everything
     * else (notably MOVE_FIRED) leaves it alone — which is the whole point.
     * Target identity is by reference against the live render combatants.
     */
    private void applyLocalHpEvent(CombatEvent e) {
        int amount = e.getIntValue();
        boolean playerTarget = e.getTarget() == renderPlayer;
        boolean enemyTarget  = e.getTarget() == renderEnemy;
        switch (e.getType()) {
            case DAMAGE_DEALT:
                if (playerTarget)       localPlayerHp = Math.max(0, localPlayerHp - amount);
                else if (enemyTarget)   localEnemyHp  = Math.max(0, localEnemyHp  - amount);
                break;
            case HP_RESTORED:
                if (playerTarget)       localPlayerHp += amount;
                else if (enemyTarget)   localEnemyHp  += amount;
                break;
            case MAX_HP_CHANGED:
                if (playerTarget) {
                    localPlayerMaxHp = Math.max(1, amount);
                    localPlayerHp    = Math.min(localPlayerHp, localPlayerMaxHp);
                } else if (enemyTarget) {
                    localEnemyMaxHp = Math.max(1, amount);
                    localEnemyHp    = Math.min(localEnemyHp, localEnemyMaxHp);
                }
                break;
            default:
                break;
        }
    }

    private void updatePanels() {
        if (mode == BattleMode.MULTIPLAYER) {
            if (playerPanel != null && onlinePlayer != null) {
                playerPanel.update(
                    onlinePlayerHp, onlinePlayerMaxHp, onlinePlayerCe, onlinePlayerMaxCe);
            }
            miraclesMeter.setState(onlinePlayerMiracles);
            if (enemyPanel != null && onlineEnemy != null) {
                enemyPanel.update(
                    onlineEnemyHp, onlineEnemyMaxHp, onlineEnemyCe, onlineEnemyMaxCe);
            }
            return;
        }
        // LOCAL: HP comes from the deferred ints (so the bar follows the damage
        // log line); CE/max-CE stay live from the model since CE drains at the
        // move's start tick, before the fire tick.
        if (playerPanel != null && renderPlayer != null) {
            playerPanel.update(localPlayerHp, localPlayerMaxHp,
                renderPlayer.getCurrentCe(), renderPlayer.getMaxCursedEnergy());
        }
        miraclesMeter.setState(renderPlayer == null
            ? null : findMiraclesState(renderPlayer.getCodedAbilities().states()));
        if (enemyPanel != null && renderEnemy != null) {
            enemyPanel.update(localEnemyHp, localEnemyMaxHp,
                renderEnemy.getCurrentCe(), renderEnemy.getMaxCursedEnergy());
        }
    }

    private static CodedAbilityState findMiraclesState(List<CodedAbilityState> states) {
        if (states == null) return null;
        return states.stream()
            .filter(state -> MiraclesAbility.KEY.equals(state.key()))
            .findFirst()
            .orElse(null);
    }

    private static CodedAbilityState roundStartMiraclesState(
        MatchState state,
        PlayerSide side,
        List<CodedAbilityState> fallback
    ) {
        if (state.roundStartCharacterStates() == null) return findMiraclesState(fallback);
        return state.roundStartCharacterStates().stream()
            .filter(character -> character.side() == side)
            .map(character -> findMiraclesState(character.codedAbilities()))
            .filter(Objects::nonNull)
            .findFirst()
            .orElseGet(() -> findMiraclesState(fallback));
    }

    private boolean hasPlayerRenderState() {
        return mode == BattleMode.MULTIPLAYER ? onlinePlayer != null : renderPlayer != null;
    }

    private boolean hasEnemyRenderState() {
        return mode == BattleMode.MULTIPLAYER ? onlineEnemy != null : renderEnemy != null;
    }

    private String playerCharacterName() {
        return mode == BattleMode.MULTIPLAYER
            ? onlinePlayer.character().name()
            : renderPlayer.getCharacter().getName();
    }

    private String enemyCharacterName() {
        return mode == BattleMode.MULTIPLAYER
            ? onlineEnemy.character().name()
            : renderEnemy.getCharacter().getName();
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
        while (!abortRequested && isCurrentLocalBattleThread()
            && System.currentTimeMillis() < deadline) {
            sleepMs(Math.min(40L, deadline - System.currentTimeMillis() + 1));
        }
    }

    private boolean isCurrentLocalBattleThread() {
        return mode == BattleMode.LOCAL
            && localBattleThread != null
            && Thread.currentThread() == localBattleThread;
    }

    private void unlockPlannerIfPlanOpen() {
        if (planningPanel != null
            && (onlinePlayer == null || !onlinePlayer.planSubmitted())) {
            planningPanel.unlock();
        }
    }

    private void postLocal(Runnable callback) {
        Thread run = Thread.currentThread();
        Gdx.app.postRunnable(() -> {
            if (mode == BattleMode.LOCAL
                && localBattleThread == run
                && !abortRequested
                && game.getScreen() == this) {
                callback.run();
            }
        });
    }
}
