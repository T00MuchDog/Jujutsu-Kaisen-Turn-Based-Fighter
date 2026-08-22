package com.jjktbf.graphics.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.controller.BattleController;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.audio.SoundCue;
import com.jjktbf.graphics.ui.StatusBar;
import com.jjktbf.graphics.ui.battle.BattleUiAssets;
import com.jjktbf.graphics.ui.editor.ScrollAxes;
import com.jjktbf.graphics.ui.battle.MoveCardView;
import com.jjktbf.graphics.ui.profile.UiProfile;
import com.jjktbf.model.character.AbilityRepository;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterRepository;
import com.jjktbf.model.character.CombatStats;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveRepository;
import com.jjktbf.model.technique.TechniqueRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Master-detail character selection screen for player and CPU choices. */
public class CharacterSelectScreen implements Screen {

    private static final String CHAR_DATA_DIR = "data/characters";
    private static final String MOVE_DATA_DIR = "data/moves";
    private static final String ABILITY_DATA_DIR = "data/abilities";
    private static final String TECHNIQUE_DATA_DIR = "data/techniques";
    private static final float ROW_HEIGHT = 44f;
    private static final float WINDOWS_ROW_HEIGHT = 66f;
    private static final int MOVE_COLUMNS = 5;
    private static final float MOVE_CARD_GAP = 8f;
    private static final float WINDOWS_MOVE_CARD_GAP = 12f;
    private static final float MOVE_PANEL_GAP = 12f;
    private static final float WINDOWS_MOVE_PANEL_GAP = 18f;
    private static final float MOVE_PANEL_PADDING = 10f;
    private static final float WINDOWS_MOVE_PANEL_PADDING = 15f;
    private static final float MOVE_PANEL_HEADER_HEIGHT = 24f;
    private static final float WINDOWS_MOVE_PANEL_HEADER_HEIGHT = 36f;
    private static final float MOVE_SCROLLBAR_WIDTH = 10f;
    private static final float WINDOWS_MOVE_SCROLLBAR_WIDTH = 15f;
    private static final float COMPACT_MOVE_CARD_HEIGHT_RATIO = 0.58f;
    private static final float WINDOWS_COMPACT_MOVE_CARD_HEIGHT_RATIO = 0.87f;
    private static final float COMPACT_MOVE_CARD_MIN_HEIGHT = 48f;
    private static final float WINDOWS_COMPACT_MOVE_CARD_MIN_HEIGHT = 72f;
    private static final float COMPACT_MOVE_CARD_MAX_HEIGHT = 72f;
    private static final float WINDOWS_COMPACT_MOVE_CARD_MAX_HEIGHT = 108f;
    private static final float MIN_CHARACTER_INFO_HEIGHT = 205f;
    private static final float WINDOWS_MIN_CHARACTER_INFO_HEIGHT = 307.5f;
    private static final float DESCRIPTION_TARGET_HEIGHT = 45f;
    private static final float WINDOWS_DESCRIPTION_TARGET_HEIGHT = 67.5f;
    private static final float HEADER_HEIGHT = 58f;
    private static final float WINDOWS_HEADER_HEIGHT = 87f;
    private static final String[] STAT_LABELS = {
        "Vitality", "Strength", "Durability", "Speed", "Combat Ability",
        "CE Reserves", "CE Efficiency", "CE Output", "Jujutsu Skill", "CT Mastery"
    };

    private enum Phase { PLAYER, CPU }

    /**
     * Roster format for the battle being set up. ONE_V_ONE picks one fighter
     * per side (the legacy flow); TWO_V_TWO picks two. Set on entry via
     * {@link #prepare(BattleFormat)}.
     */
    private com.jjktbf.model.combat.BattleFormat format =
        com.jjktbf.model.combat.BattleFormat.ONE_V_ONE;
    private com.jjktbf.model.combat.BattleStatMode statMode =
        com.jjktbf.model.combat.BattleStatMode.STANDARD;
    private BattleController.ControlMode controlMode =
        BattleController.ControlMode.PLAYER_VS_AI;

    private final JJKGame game;
    private final AssetLoader assets;
    private final boolean windowsLayout;
    private final SpriteBatch batch;
    private final CharacterRepository charRepo;
    private final MoveRepository moveRepo;
    private final AbilityRepository abilityRepo;
    private final TechniqueRepository techniqueRepo;
    private final com.jjktbf.model.weapon.CursedToolRepository cursedToolRepo;
    /** Guards against double-dispose of native batch resources. */
    private boolean disposed;
    private final Rectangle headerBounds = new Rectangle();
    private final Rectangle listBounds = new Rectangle();
    private final Rectangle rosterViewportBounds = new Rectangle();
    private final Rectangle detailBounds = new Rectangle();
    private final Rectangle movesViewportBounds = new Rectangle();
    private final InputAdapter inputAdapter = new InputAdapter() {
        @Override
        public boolean scrolled(float amountX, float amountY) {
            // The moves list scrolls vertically; snap the gesture to its dominant axis
            // so a horizontal trackpad swipe doesn't leak its vertical component in.
            float[] dominant = ScrollAxes.dominant(amountX, amountY);
            if (scrollRoster(dominant[1])) return true;
            return scrollLearnedMoves(dominant[1]);
        }
    };

    private List<CharacterData> characters = List.of();
    private CharacterData movesCharacter;
    private List<Move> learnedMoves = List.of();
    private int cursorIndex;
    private Phase phase = Phase.PLAYER;
    /**
     * Picks for the current side, in slot order. For ONE_V_ONE each side fills
     * one slot (playerChoice is kept as a convenience for the 1-slot case); for
     * TWO_V_TWO each side fills two. A character may not be picked twice within
     * the same side.
     */
    private final java.util.List<CharacterData> playerPicks = new java.util.ArrayList<>();
    private final java.util.List<CharacterData> cpuPicks = new java.util.ArrayList<>();
    private CharacterData playerChoice;
    private String loadError;
    private String learnedMovesError;
    private float movesScrollOffset;
    private float movesScrollMax;
    private float rosterScrollOffset;
    private float rosterScrollMax;
    /**
     * Set on entry so the first frame ignores input. Guards against a stale
     * ENTER poll leaking across the event-driven transition from the main menu.
     */
    private boolean inputSuspended;

    public CharacterSelectScreen(JJKGame game, AssetLoader assets) {
        this.game = game;
        this.assets = assets;
        windowsLayout = game.activeUiProfile() == UiProfile.WINDOWS;
        batch = new SpriteBatch();
        charRepo = new CharacterRepository(CHAR_DATA_DIR);
        moveRepo = new MoveRepository(MOVE_DATA_DIR);
        abilityRepo = new AbilityRepository(ABILITY_DATA_DIR);
        techniqueRepo = new TechniqueRepository(TECHNIQUE_DATA_DIR);
        cursedToolRepo = new com.jjktbf.model.weapon.CursedToolRepository("data/tools");
    }

    /**
     * Set the roster format before the screen is shown. Defaults to ONE_V_ONE
     * so callers that never call this (and the legacy no-arg
     * {@link JJKGame#showCharacterSelect()}) keep the original 1-pick behaviour.
     */
    public void prepare(com.jjktbf.model.combat.BattleFormat format) {
        prepare(format, BattleController.ControlMode.PLAYER_VS_AI);
    }

    public void prepare(
        com.jjktbf.model.combat.BattleFormat format,
        BattleController.ControlMode controlMode
    ) {
        prepare(
            format,
            com.jjktbf.model.combat.BattleStatMode.STANDARD,
            controlMode);
    }

    public void prepare(
        com.jjktbf.model.combat.BattleFormat format,
        com.jjktbf.model.combat.BattleStatMode statMode,
        BattleController.ControlMode controlMode
    ) {
        this.format = format != null ? format
            : com.jjktbf.model.combat.BattleFormat.ONE_V_ONE;
        this.statMode = statMode != null ? statMode
            : com.jjktbf.model.combat.BattleStatMode.STANDARD;
        this.controlMode = controlMode != null ? controlMode
            : BattleController.ControlMode.PLAYER_VS_AI;
    }

    @Override
    public void show() {
        // Keyboard input is polled, while this adapter receives mouse-wheel events
        // for the learned-moves panel. Taking ownership also prevents the previous
        // screen's Stage from handling keyboard input while this screen is visible.
        Gdx.input.setInputProcessor(inputAdapter);
        // The main menu switches here in response to an event-driven ENTER. That
        // event is consumed by Scene2D, but the underlying "just pressed" flag is
        // only cleared at the next frame boundary — so this screen's first render()
        // would otherwise see a phantom ENTER and auto-confirm the first row.
        // Ignore input for one frame until the stale flag has drained.
        inputSuspended = true;
        phase = Phase.PLAYER;
        cursorIndex = 0;
        playerChoice = null;
        playerPicks.clear();
        cpuPicks.clear();
        loadError = null;
        movesCharacter = null;
        learnedMoves = List.of();
        learnedMovesError = null;
        resetRosterScroll();
        resetMoveScroll();
        try {
            moveRepo.load();
            abilityRepo.load();
            techniqueRepo.load();
            charRepo.load();
            cursedToolRepo.load();
            // Battles resolve combatant sprites through JJKGame's shared
            // repository, which is otherwise only loaded at startup — reload it
            // here so editor saves reach battles without an app restart.
            game.reloadMultiplayerRoster();
            // Only directly-selectable definitions appear in the fighter roster.
            // Hidden definitions (e.g. summon-only shikigami) are filtered out.
            characters = charRepo.getAll().stream()
                .filter(CharacterData::effectiveSelectable)
                .toList();
            if (characters.isEmpty()) {
                loadError = "No characters found. Use Character Editor to create one.";
            }
        } catch (IOException e) {
            loadError = "Failed to load data: " + e.getMessage();
        }
    }

    @Override
    public void render(float delta) {
        clearScreen();
        layout(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        handleInput();
        draw();
    }

    @Override public void resize(int width, int height) {
        batch.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
        layout(width, height);
    }
    @Override public void pause() {}
    @Override public void resume() {}
    @Override public void hide() {}
    @Override public void dispose() {
        if (disposed) return;
        disposed = true;
        batch.dispose();
    }

    private void handleInput() {
        // Skip the first frame after entering: the main menu's event-driven
        // ENTER is consumed by Scene2D, but its polled "just pressed" flag only
        // clears at the next frame boundary, so this frame would otherwise see a
        // phantom ENTER. By ignoring input once, the flag drains naturally.
        if (inputSuspended) {
            inputSuspended = false;
            return;
        }
        if (loadError != null) {
            if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
                game.audio().play(SoundCue.UI_BACK);
                game.showMainMenu();
            }
            return;
        }

        if (Gdx.input.isKeyJustPressed(Input.Keys.UP)) {
            cursorIndex = (cursorIndex - 1 + characters.size()) % characters.size();
            revealRosterCursor();
            resetMoveScroll();
            game.audio().play(SoundCue.UI_NAVIGATE);
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.DOWN)) {
            cursorIndex = (cursorIndex + 1) % characters.size();
            revealRosterCursor();
            resetMoveScroll();
            game.audio().play(SoundCue.UI_NAVIGATE);
        }
        if (Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)) {
            selectRowAt(Gdx.input.getX(), Gdx.graphics.getHeight() - Gdx.input.getY());
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) confirmSelection();
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            game.audio().play(SoundCue.UI_BACK);
            if (!undoLastPick()) {
                game.showMainMenu();
            }
        }
    }

    private void selectRowAt(float x, float y) {
        if (!(windowsLayout ? rosterViewportBounds : listBounds).contains(x, y)) return;
        float firstRowTop = listBounds.y + listBounds.height
            - (windowsLayout ? 69f : 46f);
        int index = (int) ((firstRowTop + rosterScrollOffset - y) / rowHeight());
        if (index >= 0 && index < characters.size()) {
            if (index == cursorIndex) {
                confirmSelection();
            } else {
                cursorIndex = index;
                resetMoveScroll();
                game.audio().play(SoundCue.UI_NAVIGATE);
            }
        }
    }

    private void confirmSelection() {
        game.audio().play(SoundCue.UI_CONFIRM);
        CharacterData picked = characters.get(cursorIndex);
        java.util.List<CharacterData> currentPicks = currentPicks();
        // Disallow picking the same fighter twice within one side.
        if (currentPicks.stream().anyMatch(c -> c.id.equals(picked.id))) {
            return;
        }
        currentPicks.add(picked);
        if (phase == Phase.PLAYER) {
            playerChoice = picked; // convenience for the 1-slot header display
        }

        if (currentPicks.size() < format.fightersPerSide()) {
            // More slots to fill on this side.
            cursorIndex = 0;
            resetRosterScroll();
            resetMoveScroll();
            return;
        }

        // This side is full. Move to the other side, or start the battle.
        if (phase == Phase.PLAYER) {
            phase = Phase.CPU;
            cursorIndex = 0;
            resetRosterScroll();
            resetMoveScroll();
        } else {
            startConfiguredBattle();
        }
    }

    /** Picks accumulated so far for the side currently being filled. */
    private java.util.List<CharacterData> currentPicks() {
        return phase == Phase.PLAYER ? playerPicks : cpuPicks;
    }

    /**
     * Number of slots already filled across both sides. Used to step back on ESC
     * and to decide whether a side has more slots to fill.
     */
    private int totalPicksFilled() {
        return playerPicks.size() + cpuPicks.size();
    }

    /**
     * Pop the most recent pick (ESC-to-undo). Returns true if a pick was undone,
     * false if there is nothing to undo (the caller then exits the screen).
     */
    private boolean undoLastPick() {
        if (!cpuPicks.isEmpty()) {
            cpuPicks.remove(cpuPicks.size() - 1);
            if (cpuPicks.isEmpty()) phase = Phase.PLAYER;
            cursorIndex = 0;
            resetRosterScroll();
            resetMoveScroll();
            return true;
        }
        if (!playerPicks.isEmpty()) {
            playerPicks.remove(playerPicks.size() - 1);
            playerChoice = playerPicks.isEmpty() ? null : playerPicks.get(playerPicks.size() - 1);
            phase = Phase.PLAYER;
            cursorIndex = 0;
            resetRosterScroll();
            resetMoveScroll();
            return true;
        }
        return false;
    }

    private void startConfiguredBattle() {
        if (format == com.jjktbf.model.combat.BattleFormat.TWO_V_TWO) {
            game.startTeamBattle(
                new java.util.ArrayList<>(playerPicks),
                new java.util.ArrayList<>(cpuPicks),
                moveRepo, abilityRepo, techniqueRepo, controlMode, statMode);
        } else {
            // ONE_V_ONE (or any single-fighter format): use the legacy entry point.
            game.startBattle(
                playerPicks.get(0), cpuPicks.get(0),
                moveRepo, abilityRepo, techniqueRepo, controlMode, statMode);
        }
    }

    private void clearScreen() {
        Gdx.gl.glClearColor(0.804f, 0.863f, 0.980f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    }

    private void layout(float width, float height) {
        float margin = Math.min(36f, Math.max(20f, width * 0.035f));
        float headerHeight = windowsLayout ? WINDOWS_HEADER_HEIGHT : HEADER_HEIGHT;
        headerBounds.set(margin, height - margin - headerHeight, width - margin * 2f, headerHeight);
        float contentTop = headerBounds.y - 14f;
        float listWidth = Math.max(230f, width * 0.29f);
        listBounds.set(margin, margin, listWidth, contentTop - margin);
        if (windowsLayout) {
            rosterViewportBounds.set(
                listBounds.x + 8f,
                listBounds.y + 8f,
                Math.max(0f, listBounds.width - 16f),
                Math.max(0f, listBounds.height - 77f));
            rosterScrollMax = Math.max(0f,
                characters.size() * WINDOWS_ROW_HEIGHT - rosterViewportBounds.height);
            rosterScrollOffset = clamp(rosterScrollOffset, 0f, rosterScrollMax);
            revealRosterCursor();
        }
        detailBounds.set(listBounds.x + listBounds.width + 14f, margin,
            width - (listBounds.x + listBounds.width + 14f) - margin, contentTop - margin);
    }

    private void draw() {
        batch.begin();
        if (loadError != null) {
            drawError();
            batch.end();
            return;
        }

        drawHeader();
        drawRoster();
        drawCharacterPage(characters.get(cursorIndex));
        batch.end();
    }

    private void drawError() {
        assets.battleUi.header.draw(batch, headerBounds.x, headerBounds.y,
            headerBounds.width, headerBounds.height);
        assets.fontSmall.setColor(Color.RED);
        assets.fontSmall.draw(batch, loadError, headerBounds.x + 18f,
            headerBounds.y + (windowsLayout ? 51f : 34f));
    }

    private void drawHeader() {
        assets.battleUi.header.draw(batch, headerBounds.x, headerBounds.y,
            headerBounds.width, headerBounds.height);
        int slot = currentPicks().size() + 1; // 1-indexed slot being filled now
        int slots = format.fightersPerSide();
        String opposingSide = controlMode == BattleController.ControlMode.HUMAN_CONTROLS_BOTH_TEAMS
            ? "ENEMY" : "CPU";
        String title = phase == Phase.PLAYER
            ? (slots == 1 ? "SELECT YOUR CHARACTER"
                         : "SELECT PLAYER FIGHTER " + slot + "/" + slots)
            : (slots == 1 ? "SELECT " + opposingSide + " CHARACTER"
                         : "SELECT " + opposingSide + " FIGHTER " + slot + "/" + slots);
        assets.fontMedium.setColor(BattleUiAssets.YELLOW);
        assets.fontMedium.draw(batch, title, headerBounds.x + 18f,
            headerBounds.y + (windowsLayout ? 58.5f : 39f));
        assets.fontSmall.setColor(new Color(0.720f, 0.800f, 0.950f, 1f));
        String state = phase == Phase.CPU && !playerPicks.isEmpty()
            ? picksSummary("PLAYER", playerPicks) + "  |  " + statMode + "  |  ENTER: START"
            : "UP/DOWN: SELECT  |  ENTER: CONFIRM  |  " + statMode;
        assets.fontSmall.draw(batch, state, headerBounds.x + 20f,
            headerBounds.y + (windowsLayout ? 25.5f : 17f));
    }

    private static String picksSummary(String label, java.util.List<CharacterData> picks) {
        StringBuilder sb = new StringBuilder(label).append(": ");
        for (int i = 0; i < picks.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(picks.get(i).name);
        }
        return sb.toString();
    }

    private void drawRoster() {
        assets.battleUi.palette.draw(batch, listBounds.x, listBounds.y, listBounds.width, listBounds.height);
        assets.fontSmall.setColor(new Color(0.720f, 0.800f, 0.950f, 1f));
        assets.fontSmall.draw(batch, "AVAILABLE CHARACTERS", listBounds.x + 14f,
            listBounds.y + listBounds.height - (windowsLayout ? 22.5f : 15f));

        float rowHeight = rowHeight();
        float rowTop = listBounds.y + listBounds.height - (windowsLayout ? 69f : 46f);
        java.util.List<CharacterData> sidePicks = currentPicks();
        if (windowsLayout) beginClip(rosterViewportBounds);
        for (int i = 0; i < characters.size(); i++) {
            float rowY = rowTop - (i + 1) * rowHeight + rosterScrollOffset;
            if (i == cursorIndex) {
                assets.battleUi.cardOver.draw(batch, listBounds.x + 8f, rowY,
                    listBounds.width - 16f, rowHeight - (windowsLayout ? 6f : 4f));
            }
            CharacterData character = characters.get(i);
            // Dim a character already picked on the side currently being filled.
            boolean alreadyPicked = sidePicks.stream().anyMatch(c -> c.id.equals(character.id));
            assets.fontMedium.setColor(i == cursorIndex
                ? BattleUiAssets.TEXT
                : (alreadyPicked ? new Color(0.55f, 0.55f, 0.55f, 1f) : Color.WHITE));
            assets.fontMedium.draw(batch, character.name, listBounds.x + 18f,
                rowY + (windowsLayout ? 40.5f : 27f));
        }
        // Pick badges (P1/P2 on player side, C1/C2 on cpu side) next to names.
        drawPickBadges(rowTop, "P", playerPicks);
        drawPickBadges(rowTop,
            controlMode == BattleController.ControlMode.HUMAN_CONTROLS_BOTH_TEAMS ? "E" : "C",
            cpuPicks);
        if (windowsLayout) endClip();
    }

    /** Draw a side's slot badges (P1/P2 or C1/C2) next to picked roster rows. */
    private void drawPickBadges(float rowTop, String prefix, java.util.List<CharacterData> picks) {
        float rowHeight = rowHeight();
        for (int slot = 0; slot < picks.size(); slot++) {
            CharacterData picked = picks.get(slot);
            int row = characters.stream().filter(c -> c.id.equals(picked.id))
                .mapToInt(characters::indexOf).findFirst().orElse(-1);
            if (row < 0) continue;
            float rowY = rowTop - (row + 1) * rowHeight + rosterScrollOffset;
            assets.fontSmall.setColor(BattleUiAssets.YELLOW);
            assets.fontSmall.draw(batch, prefix + (slot + 1), listBounds.x + 14f,
                rowY + (windowsLayout ? 21f : 14f));
        }
    }

    private void drawCharacterPage(CharacterData character) {
        assets.battleUi.card.draw(batch, detailBounds.x, detailBounds.y,
            detailBounds.width, detailBounds.height);

        float pad = windowsLayout ? 30f : 20f;
        float innerLeft = detailBounds.x + pad;
        float innerRight = detailBounds.x + detailBounds.width - pad;
        float innerTop = detailBounds.y + detailBounds.height - pad;
        float innerWidth = innerRight - innerLeft;

        // Name — top-left corner, prominent.
        assets.fontXLarge.setColor(BattleUiAssets.TEXT);
        drawBold(assets.fontXLarge, character.name, innerLeft, innerTop);
        String baseStatTotalText = "Base Stat Total: " + baseStatTotal(character);
        assets.fontMedium.setColor(BattleUiAssets.TEXT);
        drawBold(assets.fontMedium, baseStatTotalText,
            innerRight - textWidth(assets.fontMedium, baseStatTotalText), innerTop);

        // Content region sits below the name.
        float contentTop = innerTop - (windowsLayout ? 72f : 48f);
        float contentBottom = detailBounds.y + pad;
        float contentHeight = contentTop - contentBottom;
        List<Move> moves = learnedMovesFor(character);
        float movePanelPadding = movePanelPadding();
        float movePanelHeaderHeight = movePanelHeaderHeight();
        float moveCardGap = moveCardGap();

        float estimatedCardWidth = compactMoveCardWidth(innerWidth - movePanelPadding * 2f);
        float estimatedCardHeight = compactMoveCardHeight(estimatedCardWidth);
        int moveRows = Math.max(1, (int) Math.ceil(moves.size() / (double) MOVE_COLUMNS));
        float visibleMoveRows = Math.min(2, moveRows);
        float desiredMovesHeight = moves.isEmpty()
            ? movePanelHeaderHeight + movePanelPadding * 2f
            : movePanelHeaderHeight + movePanelPadding * 2f
                + visibleMoveRows * estimatedCardHeight
                + (visibleMoveRows - 1f) * moveCardGap;
        float minimumInfoHeight = Math.min(
            windowsLayout ? WINDOWS_MIN_CHARACTER_INFO_HEIGHT : MIN_CHARACTER_INFO_HEIGHT,
            contentHeight * 0.58f);
        float maximumMovesHeight = Math.max(0f,
            contentHeight - minimumInfoHeight - movePanelGap());
        float movesPanelHeight = Math.min(desiredMovesHeight, maximumMovesHeight);
        float sectionGap = movesPanelHeight > 0f ? movePanelGap() : 0f;
        float infoBottom = contentBottom + movesPanelHeight + sectionGap;
        float infoHeight = contentTop - infoBottom;

        // Left column: profile sprite with HP/CE bars, sized around the moves panel.
        float leftWidth = Math.min(innerWidth * 0.52f, windowsLayout ? 540f : 360f);
        float leftCenterX = innerLeft + leftWidth / 2f;
        float barHeight = windowsLayout ? 42f : 28f;
        float barGap = windowsLayout ? 12f : 8f;
        boolean hasCursedTechnique = character.innateTechniqueName != null
            && !character.innateTechniqueName.isBlank();
        float techniqueGap = hasCursedTechnique ? (windowsLayout ? 24f : 16f) : 0f;
        float techniqueHeight = hasCursedTechnique ? assets.fontSmall.getCapHeight() * 2f : 0f;
        float barsAndSpacing = (windowsLayout ? 36f : 24f)
            + barHeight * 2f + barGap + techniqueGap + techniqueHeight;
        float spriteSize = Math.min(leftWidth, Math.max(0f, infoHeight - barsAndSpacing));
        spriteSize = Math.min(spriteSize, windowsLayout ? 504f : 336f);
        if (spriteSize > 0f) {
            float spriteX = leftCenterX - spriteSize / 2f;
            float spriteY = contentTop - spriteSize;
            assets.battleUi.palette.draw(batch, spriteX - 10f, spriteY - 10f,
                spriteSize + 20f, spriteSize + 20f);
            Texture sprite = assets.characterSprite(character.spriteAsset, assets.playerSprite);
            batch.draw(sprite, spriteX, spriteY, spriteSize, spriteSize);

            float barWidth = spriteSize;
            float barX = leftCenterX - barWidth / 2f;
            float hpY = spriteY - (windowsLayout ? 36f : 24f) - barHeight;
            float ceY = hpY - barGap - barHeight;
            CombatStats combat = new CombatStats(character.toCharacterStats(), statMode);
            float statusBarTextGeometryScale = windowsLayout ? 1.5f : 1f;
            StatusBar hp = new StatusBar(
                "HP", new Color(0.260f, 0.820f, 0.360f, 1f), statusBarTextGeometryScale);
            hp.setBounds(barX, hpY, barWidth, barHeight);
            hp.setValues(combat.getMaxHp(), combat.getMaxHp());
            hp.draw(batch, assets.fontMedium, assets.battleUi, true);
            StatusBar ce = new StatusBar(
                "CE", new Color(0.220f, 0.500f, 0.940f, 1f), statusBarTextGeometryScale);
            ce.setBounds(barX, ceY, barWidth, barHeight);
            ce.setValues(combat.getMaxCursedEnergy(), combat.getMaxCursedEnergy());
            ce.draw(batch, assets.fontMedium, assets.battleUi, true);
            if (hasCursedTechnique) {
                assets.fontSmall.setColor(Color.BLACK);
                float originalScaleX = assets.fontSmall.getData().scaleX;
                float originalScaleY = assets.fontSmall.getData().scaleY;
                assets.fontSmall.getData().setScale(originalScaleX * 2f, originalScaleY * 2f);
                float techniqueX = leftCenterX - textWidth(assets.fontSmall,
                    character.innateTechniqueName) / 2f;
                assets.fontSmall.draw(batch, character.innateTechniqueName, techniqueX,
                    ceY - techniqueGap);
                assets.fontSmall.getData().setScale(originalScaleX, originalScaleY);
            }
        }

        // Right column: compact stats leave the remaining vertical space for the description.
        float rightX = innerLeft + leftWidth + (windowsLayout ? 36f : 24f);
        float rightWidth = innerRight - rightX;
        float descriptionTargetHeight = windowsLayout
            ? WINDOWS_DESCRIPTION_TARGET_HEIGHT : DESCRIPTION_TARGET_HEIGHT;
        float statsRowHeight = Math.min(windowsLayout ? 34.5f : 23f,
            Math.max(windowsLayout ? 22.5f : 15f,
                (infoHeight - descriptionTargetHeight - (windowsLayout ? 21f : 14f))
                    / STAT_LABELS.length));
        BitmapFont detailFont = statsRowHeight < (windowsLayout ? 33f : 22f)
            ? assets.fontSmall : assets.fontMedium;
        drawStats(character, rightX, rightWidth, contentTop, statsRowHeight, detailFont);
        float descriptionTop = contentTop - STAT_LABELS.length * statsRowHeight
            - (windowsLayout ? 21f : 14f);
        drawDescription(character.description, rightX, rightWidth, descriptionTop, infoBottom, detailFont);

        drawLearnedMoves(moves, innerLeft, contentBottom, innerWidth, movesPanelHeight);
    }

    private void drawStats(CharacterData character, float x, float width, float topY, float rowHeight,
                           BitmapFont font) {
        int[] values = {
            character.vitality, character.strength, character.durability, character.speed, character.combatAbility,
            character.cursedEnergyReserves, character.cursedEnergyEfficiency, character.cursedEnergyOutput,
            character.jujutsuSkill, character.cursedTechniqueMastery
        };
        for (int i = 0; i < values.length; i++) {
            float y = topY - i * rowHeight;
            String value = String.valueOf(values[i]);
            float valueX = x + width - textWidth(font, value);
            font.setColor(BattleUiAssets.TEXT);
            drawBold(font, STAT_LABELS[i], x, y);
            drawBold(font, value, valueX, y);
        }
    }

    private static int baseStatTotal(CharacterData character) {
        return character.vitality + character.strength + character.durability + character.speed
            + character.combatAbility + character.cursedEnergyReserves + character.cursedEnergyEfficiency
            + character.cursedEnergyOutput + character.jujutsuSkill + character.cursedTechniqueMastery;
    }

    private void drawDescription(String description, float x, float width, float topY, float bottomY,
                                 BitmapFont font) {
        if (topY < bottomY + font.getCapHeight()) return;
        String text = description == null || description.isBlank() ? "No character description." : description;
        font.setColor(BattleUiAssets.MUTED);
        font.draw(batch, "DESCRIPTION", x, topY);
        font.setColor(BattleUiAssets.TEXT);
        List<String> lines = wrap(font, text, width);
        float lineY = topY - (windowsLayout ? 27f : 18f);
        for (String line : lines) {
            if (lineY < bottomY + (windowsLayout ? 15f : 10f)) break;
            font.draw(batch, line, x, lineY);
            lineY -= font.getLineHeight() + (windowsLayout ? 4.5f : 3f);
        }
    }

    private List<Move> learnedMovesFor(CharacterData character) {
        if (movesCharacter == character) return learnedMoves;

        movesCharacter = character;
        resetMoveScroll();
        learnedMovesError = null;
        try {
            learnedMoves = character.toCharacter(
                moveRepo, abilityRepo, techniqueRepo, cursedToolRepo).getKnownMoves();
        } catch (Exception e) {
            learnedMoves = List.of();
            learnedMovesError = e.getMessage();
        }
        return learnedMoves;
    }

    private void drawLearnedMoves(List<Move> moves, float x, float y, float width, float height) {
        movesViewportBounds.set(0f, 0f, 0f, 0f);
        movesScrollMax = 0f;
        if (height <= 0f) return;

        float movePanelPadding = movePanelPadding();
        float movePanelHeaderHeight = movePanelHeaderHeight();
        float moveCardGap = moveCardGap();
        float moveScrollbarWidth = moveScrollbarWidth();
        assets.battleUi.palette.draw(batch, x, y, width, height);
        String title = moves.isEmpty() ? "LEARNED MOVES" : "LEARNED MOVES (" + moves.size() + ")";
        assets.fontSmall.setColor(new Color(0.720f, 0.800f, 0.950f, 1f));
        assets.fontSmall.draw(batch, title, x + movePanelPadding,
            y + height - (windowsLayout ? 12f : 8f));

        if (learnedMovesError != null) {
            assets.fontSmall.setColor(Color.RED);
            assets.fontSmall.draw(batch, "MOVE DATA UNAVAILABLE", x + movePanelPadding,
                y + Math.max(movePanelPadding + assets.fontSmall.getCapHeight(), height / 2f));
            return;
        }
        if (moves.isEmpty()) {
            assets.fontSmall.setColor(BattleUiAssets.MUTED);
            assets.fontSmall.draw(batch, "No learned moves.", x + movePanelPadding,
                y + Math.max(movePanelPadding + assets.fontSmall.getCapHeight(), height / 2f));
            return;
        }

        float viewportHeight = height - movePanelHeaderHeight - movePanelPadding * 2f;
        if (viewportHeight <= 0f) return;

        float availableWidth = width - movePanelPadding * 2f;
        if (availableWidth <= 0f) return;
        float unscrolledCardWidth = compactMoveCardWidth(availableWidth);
        float unscrolledCardHeight = compactMoveCardHeight(unscrolledCardWidth);
        int rows = (int) Math.ceil(moves.size() / (double) MOVE_COLUMNS);
        float unscrolledContentHeight = rows * unscrolledCardHeight + (rows - 1) * moveCardGap;
        boolean hasScrollbar = unscrolledContentHeight > viewportHeight;
        float cardAreaWidth = availableWidth
            - (hasScrollbar ? moveScrollbarWidth + (windowsLayout ? 6f : 4f) : 0f);
        if (cardAreaWidth <= 0f) return;
        float cardWidth = compactMoveCardWidth(cardAreaWidth);
        float cardHeight = compactMoveCardHeight(cardWidth);
        float contentHeight = rows * cardHeight + (rows - 1) * moveCardGap;

        movesViewportBounds.set(x + movePanelPadding, y + movePanelPadding,
            cardAreaWidth, viewportHeight);
        movesScrollMax = Math.max(0f, contentHeight - viewportHeight);
        movesScrollOffset = clamp(movesScrollOffset, 0f, movesScrollMax);

        float gridWidth = MOVE_COLUMNS * cardWidth + (MOVE_COLUMNS - 1) * moveCardGap;
        float gridX = movesViewportBounds.x + Math.max(0f, (cardAreaWidth - gridWidth) / 2f);
        beginClip(movesViewportBounds);
        for (int i = 0; i < moves.size(); i++) {
            int row = i / MOVE_COLUMNS;
            int column = i % MOVE_COLUMNS;
            float cardX = gridX + column * (cardWidth + moveCardGap);
            float cardY = movesViewportBounds.y + movesViewportBounds.height - cardHeight
                - row * (cardHeight + moveCardGap) + movesScrollOffset;
            drawCompactMoveCard(moves.get(i), cardX, cardY, cardWidth, cardHeight);
        }
        endClip();

        if (movesScrollMax > 0f) {
            drawMovesScrollbar(x + width - movePanelPadding - moveScrollbarWidth,
                movesViewportBounds.y, moveScrollbarWidth, movesViewportBounds.height, contentHeight);
        }
    }

    private float compactMoveCardWidth(float availableWidth) {
        return Math.min(MoveCardView.CARD_W, Math.max(0.1f,
            (availableWidth - (MOVE_COLUMNS - 1) * moveCardGap()) / MOVE_COLUMNS));
    }

    private float compactMoveCardHeight(float cardWidth) {
        float minimumHeight = windowsLayout
            ? WINDOWS_COMPACT_MOVE_CARD_MIN_HEIGHT : COMPACT_MOVE_CARD_MIN_HEIGHT;
        float maximumHeight = windowsLayout
            ? WINDOWS_COMPACT_MOVE_CARD_MAX_HEIGHT : COMPACT_MOVE_CARD_MAX_HEIGHT;
        float heightRatio = windowsLayout
            ? WINDOWS_COMPACT_MOVE_CARD_HEIGHT_RATIO : COMPACT_MOVE_CARD_HEIGHT_RATIO;
        return Math.max(minimumHeight, Math.min(maximumHeight, cardWidth * heightRatio));
    }

    private void drawCompactMoveCard(Move move, float x, float y, float width, float height) {
        assets.battleUi.card.draw(batch, x, y, width, height);

        Color type = MoveCardView.typeColorFor(move);
        batch.setColor(type);
        batch.draw(assets.battleUi.pixel,
            x + (windowsLayout ? 12f : 8f),
            y + (windowsLayout ? 12f : 8f),
            windowsLayout ? 9f : 6f,
            height - (windowsLayout ? 24f : 16f));
        batch.setColor(Color.WHITE);

        float textX = x + (windowsLayout ? 33f : 22f);
        float textWidth = width - (windowsLayout ? 45f : 30f);
        float roleIconSize = Math.min(windowsLayout ? 24f : 16f,
            Math.max(windowsLayout ? 15f : 10f, height - (windowsLayout ? 51f : 34f)));
        int nameLines = height >= (windowsLayout ? 87f : 58f) ? 2 : 1;
        assets.fontSmall.setColor(BattleUiAssets.TEXT);
        drawCompactMoveName(move.getName(), textX,
            y + height - (windowsLayout ? 15f : 10f),
            textWidth - roleIconSize - (windowsLayout ? 6f : 4f), nameLines);
        batch.draw(MoveCardView.roleIconFor(move, assets.battleUi),
            x + width - roleIconSize - (windowsLayout ? 12f : 8f),
            y + height - roleIconSize - (windowsLayout ? 9f : 6f),
            roleIconSize, roleIconSize);

        assets.fontSmall.setColor(type);
        drawFittedText(assets.fontSmall, MoveCardView.typeNameFor(move), textX,
            y + (windowsLayout ? 21f : 14f), textWidth);
    }

    private void drawCompactMoveName(String name, float x, float topY, float width, int maxLines) {
        BitmapFont font = assets.fontSmall;
        float originalScaleX = font.getData().scaleX;
        float originalScaleY = font.getData().scaleY;
        List<String> lines = List.of(name == null || name.isBlank() ? "-" : name);

        for (float scale = 1f; scale >= 0.55f; scale -= 0.10f) {
            font.getData().setScale(originalScaleX * scale, originalScaleY * scale);
            lines = wrap(font, name == null || name.isBlank() ? "-" : name, width);
            boolean allLinesFit = lines.stream().allMatch(line -> textWidth(font, line) <= width);
            if (lines.size() <= maxLines && allLinesFit) break;
        }

        boolean needsTruncation = lines.size() > maxLines
            || lines.stream().anyMatch(line -> textWidth(font, line) > width);
        if (needsTruncation) {
            lines = new ArrayList<>(lines.subList(0, Math.min(maxLines, lines.size())));
            int last = lines.size() - 1;
            lines.set(last, ellipsize(font, lines.get(last), width));
        }
        for (int i = 0; i < lines.size(); i++) {
            font.draw(batch, lines.get(i), x, topY - i * font.getLineHeight());
        }
        font.getData().setScale(originalScaleX, originalScaleY);
    }

    private static String ellipsize(BitmapFont font, String text, float width) {
        String suffix = "...";
        String result = text;
        while (result.length() > 1 && textWidth(font, result + suffix) > width) {
            result = result.substring(0, result.length() - 1);
        }
        return result + suffix;
    }

    private void drawFittedText(BitmapFont font, String text, float x, float y, float width) {
        float originalScaleX = font.getData().scaleX;
        float originalScaleY = font.getData().scaleY;
        for (float scale = 1f; scale >= 0.55f; scale -= 0.10f) {
            font.getData().setScale(originalScaleX * scale, originalScaleY * scale);
            if (textWidth(font, text) <= width) break;
        }
        font.draw(batch, text, x, y);
        font.getData().setScale(originalScaleX, originalScaleY);
    }

    private void beginClip(Rectangle bounds) {
        batch.flush();
        float scaleX = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
        float scaleY = Gdx.graphics.getBackBufferHeight() / (float) Gdx.graphics.getHeight();
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glScissor(Math.round(bounds.x * scaleX), Math.round(bounds.y * scaleY),
            Math.round(bounds.width * scaleX), Math.round(bounds.height * scaleY));
    }

    private void endClip() {
        batch.flush();
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
    }

    private void drawMovesScrollbar(float x, float y, float width, float height, float contentHeight) {
        batch.setColor(BattleUiAssets.INK);
        batch.draw(assets.battleUi.pixel, x, y, width, height);

        float thumbHeight = Math.max(windowsLayout ? 27f : 18f,
            height * height / contentHeight);
        float progress = movesScrollMax <= 0f ? 0f : movesScrollOffset / movesScrollMax;
        float thumbY = y + (height - thumbHeight) * (1f - progress);
        batch.setColor(BattleUiAssets.YELLOW);
        batch.draw(assets.battleUi.pixel,
            x + (windowsLayout ? 3f : 2f),
            thumbY,
            width - (windowsLayout ? 6f : 4f),
            thumbHeight);
        batch.setColor(Color.WHITE);
    }

    private boolean scrollLearnedMoves(float amount) {
        if (amount == 0f || movesScrollMax <= 0f) return false;
        float pointerX = Gdx.input.getX();
        float pointerY = Gdx.graphics.getHeight() - Gdx.input.getY();
        if (!movesViewportBounds.contains(pointerX, pointerY)) return false;

        float step = Math.max(windowsLayout ? 36f : 24f,
            Math.min(windowsLayout ? 120f : 80f, movesViewportBounds.height * 0.55f));
        movesScrollOffset = clamp(movesScrollOffset + amount * step, 0f, movesScrollMax);
        return true;
    }

    private boolean scrollRoster(float amount) {
        if (!windowsLayout || amount == 0f || rosterScrollMax <= 0f) return false;
        float pointerX = Gdx.input.getX();
        float pointerY = Gdx.graphics.getHeight() - Gdx.input.getY();
        if (!rosterViewportBounds.contains(pointerX, pointerY)) return false;

        rosterScrollOffset = clamp(
            rosterScrollOffset + amount * WINDOWS_ROW_HEIGHT,
            0f,
            rosterScrollMax);
        return true;
    }

    private void revealRosterCursor() {
        if (!windowsLayout || characters.isEmpty()) return;
        rosterScrollOffset = rosterScrollOffsetForSelection(
            rosterScrollOffset,
            cursorIndex,
            characters.size(),
            WINDOWS_ROW_HEIGHT,
            rosterViewportBounds.height);
    }

    static float rosterScrollOffsetForSelection(
        float currentOffset,
        int selectedIndex,
        int rowCount,
        float rowHeight,
        float viewportHeight
    ) {
        float maximumOffset = Math.max(0f, rowCount * rowHeight - viewportHeight);
        float offset = clamp(currentOffset, 0f, maximumOffset);
        float rowTop = selectedIndex * rowHeight;
        float rowBottom = rowTop + rowHeight;
        if (rowTop < offset) {
            offset = rowTop;
        } else if (rowBottom > offset + viewportHeight) {
            offset = rowBottom - viewportHeight;
        }
        return clamp(offset, 0f, maximumOffset);
    }

    private float rowHeight() {
        return windowsLayout ? WINDOWS_ROW_HEIGHT : ROW_HEIGHT;
    }

    private float moveCardGap() {
        return windowsLayout ? WINDOWS_MOVE_CARD_GAP : MOVE_CARD_GAP;
    }

    private float movePanelGap() {
        return windowsLayout ? WINDOWS_MOVE_PANEL_GAP : MOVE_PANEL_GAP;
    }

    private float movePanelPadding() {
        return windowsLayout ? WINDOWS_MOVE_PANEL_PADDING : MOVE_PANEL_PADDING;
    }

    private float movePanelHeaderHeight() {
        return windowsLayout ? WINDOWS_MOVE_PANEL_HEADER_HEIGHT : MOVE_PANEL_HEADER_HEIGHT;
    }

    private float moveScrollbarWidth() {
        return windowsLayout ? WINDOWS_MOVE_SCROLLBAR_WIDTH : MOVE_SCROLLBAR_WIDTH;
    }

    private void resetRosterScroll() {
        rosterScrollOffset = 0f;
    }

    private void resetMoveScroll() {
        movesScrollOffset = 0f;
        movesScrollMax = 0f;
        movesViewportBounds.set(0f, 0f, 0f, 0f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void drawBold(BitmapFont font, String text, float x, float y) {
        font.draw(batch, text, x, y);
        font.draw(batch, text, x + 1f, y);
    }

    private static float textWidth(BitmapFont font, String text) {
        return new GlyphLayout(font, text).width;
    }

    private static List<String> wrap(BitmapFont font, String text, float width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (textWidth(font, candidate) <= width) {
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
}
