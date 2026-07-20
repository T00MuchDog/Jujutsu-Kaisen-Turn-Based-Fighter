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
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CeEfficiencyCalculator;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.Timeline;
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
    private final MiraclesMeter miraclesMeter = new MiraclesMeter();
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
    private PlanningPanel planningPanel;

    // ── Shared render state (written by controller thread, read by render) ────
    private volatile BattleCombatant renderPlayer;
    private volatile BattleCombatant renderEnemy;
    private volatile String          phaseLabel = "";
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
        planningPanel = null;
        logLines.clear();
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
        logLines.clear();

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
        if (enemyPanel != null && hasEnemyRenderState())
            enemyPanel.draw(batch, assets.fontLog, assets.fontLarge, enemyCharacterName(), frameDelta);
        if (playerPanel != null && hasPlayerRenderState()) {
            playerPanel.draw(batch, assets.fontLog, assets.fontLarge, playerCharacterName(), frameDelta);
            miraclesMeter.draw(batch, assets.fontSmall, assets.battleUi);
        }
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
        String label = mode == BattleMode.MULTIPLAYER && localReadyForNextRound()
            ? "WAITING..." : "NEXT ROUND";
        assets.fontMedium.draw(batch, label, nextRoundBounds.x + 16f,
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
            phaseLabel   = "PLAN YOUR ROUND";
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
        postLocal(() -> phaseLabel = "ROUND " + state.getRoundNumber() + "  —  RESOLVING");

        for (CombatEvent e : events) {
            if (abortRequested || !isCurrentLocalBattleThread()) return;
            if (e.getType() == CombatEvent.Type.MOVE_FIRED) {
                lastTickFired = true;
                Move unleashedMove = e.getMove();
                postLocal(() -> playMoveUnleashAnimation(unleashedMove));
            }
            if (!e.getMessage().isBlank()) {
                final String msg = e.getMessage();
                postLocal(() -> {
                    addLogLine(msg);
                    updatePanels();
                });
                abortableSleepMs(EVENT_DELAY_MS);
            }
        }
    }

    @Override
    public void displayResolutionTick(int tick, BattleState state) {
        if (abortRequested || !isCurrentLocalBattleThread()) return;
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
        postLocal(() -> phaseLabel = "ROUND " + state.getRoundNumber() + "  —  RESOLVING");
    }

    @Override
    public void displayRoundEnd(BattleState state) {
        if (!isCurrentLocalBattleThread()) return;
        resolvingTicks = false;
        // The final tick's tail hold runs here: no further displayResolutionTick
        // call follows to apply it. Without this the last hit of a round would
        // jump straight to the "round complete" banner and skip its slow-mo.
        abortableSleepMs(isSlowTick(heldTick, lastTickFired, firingTicks)
            ? SLOW_TICK_DURATION_MS : TICK_DURATION_MS);
        lastTickFired = false;
        postLocal(() -> {
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
            phaseLabel = "BATTLE OVER";
            battleOver = true;
        });
    }

    @Override
    public void displayMessage(String message) {
        if (abortRequested || !isCurrentLocalBattleThread()) return;
        postLocal(() -> addLogLine(message));
        abortableSleepMs(100);
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
        phaseLabel = "CONNECTING TO MATCH";
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
            phaseLabel = state.status() == MatchStatus.WAITING
                ? "WAITING FOR OPPONENT"
                : "ROUND " + state.roundNumber() + "  —  PLANNING";
            updatePanels();
            logOnlineEvents(state.recentEvents());

            if (local.planSubmitted()) {
                if (planningPanel != null) planningPanel.lock();
                phaseLabel = "ROUND " + state.roundNumber() + "  —  PLAN LOCKED";
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
        phaseLabel = "PLAN YOUR ROUND";
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
        phaseLabel = "ROUND " + multiplayerState.roundNumber() + "  —  LOCKING PLAN";
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
        phaseLabel = "ROUND " + playbackRound + "  —  RESOLVING";

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
        playbackTickElapsedMs += Math.max(0f, delta) * 1000f;

        while (resolvingTicks) {
            int duration = isSlowTick(
                currentExecutionTick,
                firingTicks.contains(currentExecutionTick),
                firingTicks
            )
                ? SLOW_TICK_DURATION_MS : TICK_DURATION_MS;
            if (playbackTickElapsedMs < duration) return;
            playbackTickElapsedMs -= duration;

            if (currentExecutionTick >= playbackLastTick) {
                finishMultiplayerPlayback();
                return;
            }
            currentExecutionTick++;
            processPlaybackEventsThrough(currentExecutionTick);
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
            if (event.targetSide() == multiplayerSetup.playerSide()) {
                onlinePlayerHp = Math.min(onlinePlayerMaxHp, onlinePlayerHp + value);
            } else if (event.targetSide() == opposite(multiplayerSetup.playerSide())) {
                onlineEnemyHp = Math.min(onlineEnemyMaxHp, onlineEnemyHp + value);
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
            changePlaybackCe(event.targetSide() != null ? event.targetSide() : event.sourceSide(), -value);
        } else if (value != null && event.type() == BattleEventType.CE_RESTORED) {
            changePlaybackCe(event.targetSide() != null ? event.targetSide() : event.sourceSide(), value);
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
            addLogLine(event.message());
        }
    }

    private void logOnlineEvents(List<BattleEventState> events) {
        for (BattleEventState event : events) {
            if (event.message() != null && !event.message().isBlank()
                && (event.eventId() == null || loggedOnlineEventIds.add(event.eventId()))) {
                addLogLine(event.message());
            }
        }
    }

    private void changePlaybackCe(PlayerSide side, int delta) {
        if (side == multiplayerSetup.playerSide()) {
            onlinePlayerCe = Math.max(0,
                Math.min(onlinePlayerMaxCe, onlinePlayerCe + delta));
        } else if (side == opposite(multiplayerSetup.playerSide())) {
            onlineEnemyCe = Math.max(0,
                Math.min(onlineEnemyMaxCe, onlineEnemyCe + delta));
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
        phaseLabel = "ROUND " + playbackRound + " COMPLETE";
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
        phaseLabel = "ROUND " + playbackRound + " COMPLETE  —  WAITING";
    }

    private boolean localReadyForNextRound() {
        return onlinePlayer != null && onlinePlayer.readyForNextRound();
    }

    private void showMultiplayerResult(MatchState state) {
        awaitingNextRound = false;
        phaseLabel = "BATTLE OVER";
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
        miraclesMeter.setPosition(
            playerX + spriteWidth + 24f * s,
            playerY + spriteHeight / 2f
        );

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
        if (playerPanel != null && renderPlayer != null) playerPanel.update(renderPlayer);
        miraclesMeter.setState(renderPlayer == null
            ? null : findMiraclesState(renderPlayer.getCodedAbilities().states()));
        if (enemyPanel != null && renderEnemy != null) enemyPanel.update(renderEnemy);
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
