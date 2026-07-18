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
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.ui.StatusBar;
import com.jjktbf.graphics.ui.battle.BattleUiAssets;
import com.jjktbf.graphics.ui.battle.MoveCardView;
import com.jjktbf.model.character.AbilityRepository;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterRepository;
import com.jjktbf.model.character.CombatStats;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Master-detail character selection screen for player and CPU choices. */
public class CharacterSelectScreen implements Screen {

    private static final String CHAR_DATA_DIR = "data/characters";
    private static final String MOVE_DATA_DIR = "data/moves";
    private static final String ABILITY_DATA_DIR = "data/abilities";
    private static final float ROW_HEIGHT = 44f;
    private static final int MOVE_COLUMNS = 5;
    private static final float MOVE_CARD_GAP = 8f;
    private static final float MOVE_PANEL_GAP = 12f;
    private static final float MOVE_PANEL_PADDING = 10f;
    private static final float MOVE_PANEL_HEADER_HEIGHT = 24f;
    private static final float MOVE_SCROLLBAR_WIDTH = 10f;
    private static final float MIN_CHARACTER_INFO_HEIGHT = 205f;
    private static final float DESCRIPTION_TARGET_HEIGHT = 45f;
    private static final String[] STAT_LABELS = {
        "Vitality", "Strength", "Durability", "Speed", "Combat Ability",
        "CE Reserves", "CE Efficiency", "CE Output", "Jujutsu Skill", "CT Mastery"
    };

    private enum Phase { PLAYER, CPU }

    private final JJKGame game;
    private final AssetLoader assets;
    private final SpriteBatch batch;
    private final CharacterRepository charRepo;
    private final MoveRepository moveRepo;
    private final AbilityRepository abilityRepo;
    /** Guards against double-dispose of native batch resources. */
    private boolean disposed;
    private final Rectangle headerBounds = new Rectangle();
    private final Rectangle listBounds = new Rectangle();
    private final Rectangle detailBounds = new Rectangle();
    private final Rectangle movesViewportBounds = new Rectangle();
    private final InputAdapter inputAdapter = new InputAdapter() {
        @Override
        public boolean scrolled(float amountX, float amountY) {
            return scrollLearnedMoves(amountY != 0f ? amountY : amountX);
        }
    };

    private List<CharacterData> characters = List.of();
    private CharacterData movesCharacter;
    private List<Move> learnedMoves = List.of();
    private int cursorIndex;
    private Phase phase = Phase.PLAYER;
    private CharacterData playerChoice;
    private String loadError;
    private String learnedMovesError;
    private float movesScrollOffset;
    private float movesScrollMax;

    public CharacterSelectScreen(JJKGame game, AssetLoader assets) {
        this.game = game;
        this.assets = assets;
        batch = new SpriteBatch();
        charRepo = new CharacterRepository(CHAR_DATA_DIR);
        moveRepo = new MoveRepository(MOVE_DATA_DIR);
        abilityRepo = new AbilityRepository(ABILITY_DATA_DIR);
    }

    @Override
    public void show() {
        // Keyboard input is polled, while this adapter receives mouse-wheel events
        // for the learned-moves panel. Taking ownership also prevents the previous
        // screen's Stage from handling keyboard input while this screen is visible.
        Gdx.input.setInputProcessor(inputAdapter);
        phase = Phase.PLAYER;
        cursorIndex = 0;
        playerChoice = null;
        loadError = null;
        movesCharacter = null;
        learnedMoves = List.of();
        learnedMovesError = null;
        resetMoveScroll();
        try {
            moveRepo.load();
            abilityRepo.load();
            charRepo.load();
            characters = charRepo.getAll();
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
        if (loadError != null) {
            if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) game.showMainMenu();
            return;
        }

        if (Gdx.input.isKeyJustPressed(Input.Keys.UP)) {
            cursorIndex = (cursorIndex - 1 + characters.size()) % characters.size();
            resetMoveScroll();
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.DOWN)) {
            cursorIndex = (cursorIndex + 1) % characters.size();
            resetMoveScroll();
        }
        if (Gdx.input.justTouched()) {
            selectRowAt(Gdx.input.getX(), Gdx.graphics.getHeight() - Gdx.input.getY());
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) confirmSelection();
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            if (phase == Phase.CPU) {
                phase = Phase.PLAYER;
                cursorIndex = 0;
            } else {
                game.showMainMenu();
            }
        }
    }

    private void selectRowAt(float x, float y) {
        if (!listBounds.contains(x, y)) return;
        float firstRowTop = listBounds.y + listBounds.height - 46f;
        int index = (int) ((firstRowTop - y) / ROW_HEIGHT);
        if (index >= 0 && index < characters.size() && index != cursorIndex) {
            cursorIndex = index;
            resetMoveScroll();
        }
    }

    private void confirmSelection() {
        if (phase == Phase.PLAYER) {
            playerChoice = characters.get(cursorIndex);
            phase = Phase.CPU;
            cursorIndex = 0;
            resetMoveScroll();
        } else {
            game.startBattle(playerChoice, characters.get(cursorIndex), moveRepo, abilityRepo);
        }
    }

    private void clearScreen() {
        Gdx.gl.glClearColor(0.804f, 0.863f, 0.980f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    }

    private void layout(float width, float height) {
        float margin = Math.min(36f, Math.max(20f, width * 0.035f));
        float headerHeight = 58f;
        headerBounds.set(margin, height - margin - headerHeight, width - margin * 2f, headerHeight);
        float contentTop = headerBounds.y - 14f;
        float listWidth = Math.max(230f, width * 0.29f);
        listBounds.set(margin, margin, listWidth, contentTop - margin);
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
        assets.fontSmall.draw(batch, loadError, headerBounds.x + 18f, headerBounds.y + 34f);
    }

    private void drawHeader() {
        assets.battleUi.header.draw(batch, headerBounds.x, headerBounds.y,
            headerBounds.width, headerBounds.height);
        String title = phase == Phase.PLAYER ? "SELECT YOUR CHARACTER" : "SELECT CPU CHARACTER";
        assets.fontMedium.setColor(BattleUiAssets.YELLOW);
        assets.fontMedium.draw(batch, title, headerBounds.x + 18f, headerBounds.y + 39f);
        assets.fontSmall.setColor(new Color(0.720f, 0.800f, 0.950f, 1f));
        String state = phase == Phase.CPU && playerChoice != null
            ? "PLAYER: " + playerChoice.name + "  |  ENTER: START"
            : "UP/DOWN: SELECT  |  ENTER: CONFIRM";
        assets.fontSmall.draw(batch, state, headerBounds.x + 20f, headerBounds.y + 17f);
    }

    private void drawRoster() {
        assets.battleUi.palette.draw(batch, listBounds.x, listBounds.y, listBounds.width, listBounds.height);
        assets.fontSmall.setColor(new Color(0.720f, 0.800f, 0.950f, 1f));
        assets.fontSmall.draw(batch, "AVAILABLE CHARACTERS", listBounds.x + 14f,
            listBounds.y + listBounds.height - 15f);

        float rowTop = listBounds.y + listBounds.height - 46f;
        for (int i = 0; i < characters.size(); i++) {
            float rowY = rowTop - (i + 1) * ROW_HEIGHT;
            if (i == cursorIndex) {
                assets.battleUi.cardOver.draw(batch, listBounds.x + 8f, rowY,
                    listBounds.width - 16f, ROW_HEIGHT - 4f);
            }
            CharacterData character = characters.get(i);
            assets.fontMedium.setColor(i == cursorIndex ? BattleUiAssets.TEXT : Color.WHITE);
            assets.fontMedium.draw(batch, character.name, listBounds.x + 18f, rowY + 27f);
        }
    }

    private void drawCharacterPage(CharacterData character) {
        assets.battleUi.card.draw(batch, detailBounds.x, detailBounds.y,
            detailBounds.width, detailBounds.height);

        float pad = 20f;
        float innerLeft = detailBounds.x + pad;
        float innerRight = detailBounds.x + detailBounds.width - pad;
        float innerTop = detailBounds.y + detailBounds.height - pad;
        float innerWidth = innerRight - innerLeft;

        // Name — top-left corner, prominent.
        assets.fontXLarge.setColor(BattleUiAssets.TEXT);
        drawBold(assets.fontXLarge, character.name, innerLeft, innerTop);

        // Content region sits below the name.
        float contentTop = innerTop - 48f;
        float contentBottom = detailBounds.y + pad;
        float contentHeight = contentTop - contentBottom;
        List<Move> moves = learnedMovesFor(character);

        float estimatedCardScale = moveCardScale(innerWidth - MOVE_PANEL_PADDING * 2f);
        float estimatedCardHeight = MoveCardView.CARD_H * estimatedCardScale;
        int moveRows = Math.max(1, (int) Math.ceil(moves.size() / (double) MOVE_COLUMNS));
        float visibleMoveRows = Math.min(2, moveRows);
        float desiredMovesHeight = moves.isEmpty()
            ? MOVE_PANEL_HEADER_HEIGHT + MOVE_PANEL_PADDING * 2f
            : MOVE_PANEL_HEADER_HEIGHT + MOVE_PANEL_PADDING * 2f
                + visibleMoveRows * estimatedCardHeight
                + (visibleMoveRows - 1f) * MOVE_CARD_GAP;
        float minimumInfoHeight = Math.min(MIN_CHARACTER_INFO_HEIGHT, contentHeight * 0.58f);
        float maximumMovesHeight = Math.max(0f,
            contentHeight - minimumInfoHeight - MOVE_PANEL_GAP);
        float movesPanelHeight = Math.min(desiredMovesHeight, maximumMovesHeight);
        float sectionGap = movesPanelHeight > 0f ? MOVE_PANEL_GAP : 0f;
        float infoBottom = contentBottom + movesPanelHeight + sectionGap;
        float infoHeight = contentTop - infoBottom;

        // Left column: profile sprite with HP/CE bars, sized around the moves panel.
        float leftWidth = Math.min(innerWidth * 0.52f, 360f);
        float leftCenterX = innerLeft + leftWidth / 2f;
        float barHeight = 28f;
        float barGap = 8f;
        float barsAndSpacing = 24f + barHeight * 2f + barGap;
        float spriteSize = Math.min(leftWidth, Math.max(0f, infoHeight - barsAndSpacing));
        spriteSize = Math.min(spriteSize, 336f); // ~3x the original 112px display width
        if (spriteSize > 0f) {
            float spriteX = leftCenterX - spriteSize / 2f;
            float spriteY = contentTop - spriteSize;
            assets.battleUi.palette.draw(batch, spriteX - 10f, spriteY - 10f,
                spriteSize + 20f, spriteSize + 20f);
            Texture sprite = assets.characterSprite(character.spriteAsset, assets.playerSprite);
            batch.draw(sprite, spriteX, spriteY, spriteSize, spriteSize);

            float barWidth = spriteSize;
            float barX = leftCenterX - barWidth / 2f;
            float hpY = spriteY - 24f - barHeight;
            float ceY = hpY - barGap - barHeight;
            CombatStats combat = character.toCombatStats();
            StatusBar hp = new StatusBar("HP", new Color(0.260f, 0.820f, 0.360f, 1f));
            hp.setBounds(barX, hpY, barWidth, barHeight);
            hp.setValues(combat.getMaxHp(), combat.getMaxHp());
            hp.draw(batch, assets.fontMedium, assets.battleUi);
            StatusBar ce = new StatusBar("CE", new Color(0.220f, 0.500f, 0.940f, 1f));
            ce.setBounds(barX, ceY, barWidth, barHeight);
            ce.setValues(combat.getMaxCursedEnergy(), combat.getMaxCursedEnergy());
            ce.draw(batch, assets.fontMedium, assets.battleUi);
        }

        // Right column: compact stats leave the remaining vertical space for the description.
        float rightX = innerLeft + leftWidth + 24f;
        float rightWidth = innerRight - rightX;
        float statsRowHeight = Math.min(23f, Math.max(15f,
            (infoHeight - DESCRIPTION_TARGET_HEIGHT - 14f) / STAT_LABELS.length));
        BitmapFont detailFont = statsRowHeight < 22f ? assets.fontSmall : assets.fontMedium;
        drawStats(character, rightX, rightWidth, contentTop, statsRowHeight, detailFont);
        float descriptionTop = contentTop - STAT_LABELS.length * statsRowHeight - 14f;
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

    private void drawDescription(String description, float x, float width, float topY, float bottomY,
                                 BitmapFont font) {
        if (topY < bottomY + font.getCapHeight()) return;
        String text = description == null || description.isBlank() ? "No character description." : description;
        font.setColor(BattleUiAssets.MUTED);
        font.draw(batch, "DESCRIPTION", x, topY);
        font.setColor(BattleUiAssets.TEXT);
        List<String> lines = wrap(font, text, width);
        float lineY = topY - 18f;
        for (String line : lines) {
            if (lineY < bottomY + 10f) break;
            font.draw(batch, line, x, lineY);
            lineY -= font.getLineHeight() + 3f;
        }
    }

    private List<Move> learnedMovesFor(CharacterData character) {
        if (movesCharacter == character) return learnedMoves;

        movesCharacter = character;
        resetMoveScroll();
        learnedMovesError = null;
        try {
            learnedMoves = character.toCharacter(moveRepo, abilityRepo).getKnownMoves();
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

        assets.battleUi.palette.draw(batch, x, y, width, height);
        String title = moves.isEmpty() ? "LEARNED MOVES" : "LEARNED MOVES (" + moves.size() + ")";
        assets.fontSmall.setColor(new Color(0.720f, 0.800f, 0.950f, 1f));
        assets.fontSmall.draw(batch, title, x + MOVE_PANEL_PADDING, y + height - 8f);

        if (learnedMovesError != null) {
            assets.fontSmall.setColor(Color.RED);
            assets.fontSmall.draw(batch, "MOVE DATA UNAVAILABLE", x + MOVE_PANEL_PADDING,
                y + Math.max(MOVE_PANEL_PADDING + assets.fontSmall.getCapHeight(), height / 2f));
            return;
        }
        if (moves.isEmpty()) {
            assets.fontSmall.setColor(BattleUiAssets.MUTED);
            assets.fontSmall.draw(batch, "No learned moves.", x + MOVE_PANEL_PADDING,
                y + Math.max(MOVE_PANEL_PADDING + assets.fontSmall.getCapHeight(), height / 2f));
            return;
        }

        float viewportHeight = height - MOVE_PANEL_HEADER_HEIGHT - MOVE_PANEL_PADDING * 2f;
        if (viewportHeight <= 0f) return;

        float availableWidth = width - MOVE_PANEL_PADDING * 2f;
        if (availableWidth <= 0f) return;
        float unscrolledScale = moveCardScale(availableWidth);
        float unscrolledCardHeight = MoveCardView.CARD_H * unscrolledScale;
        int rows = (int) Math.ceil(moves.size() / (double) MOVE_COLUMNS);
        float unscrolledContentHeight = rows * unscrolledCardHeight + (rows - 1) * MOVE_CARD_GAP;
        boolean hasScrollbar = unscrolledContentHeight > viewportHeight;
        float cardAreaWidth = availableWidth - (hasScrollbar ? MOVE_SCROLLBAR_WIDTH + 4f : 0f);
        if (cardAreaWidth <= 0f) return;
        float cardScale = moveCardScale(cardAreaWidth);
        float cardWidth = MoveCardView.CARD_W * cardScale;
        float cardHeight = MoveCardView.CARD_H * cardScale;
        float contentHeight = rows * cardHeight + (rows - 1) * MOVE_CARD_GAP;

        movesViewportBounds.set(x + MOVE_PANEL_PADDING, y + MOVE_PANEL_PADDING,
            cardAreaWidth, viewportHeight);
        movesScrollMax = Math.max(0f, contentHeight - viewportHeight);
        movesScrollOffset = clamp(movesScrollOffset, 0f, movesScrollMax);

        float gridWidth = MOVE_COLUMNS * cardWidth + (MOVE_COLUMNS - 1) * MOVE_CARD_GAP;
        float gridX = movesViewportBounds.x + Math.max(0f, (cardAreaWidth - gridWidth) / 2f);
        beginClip(movesViewportBounds);
        for (int i = 0; i < moves.size(); i++) {
            int row = i / MOVE_COLUMNS;
            int column = i % MOVE_COLUMNS;
            float cardX = gridX + column * (cardWidth + MOVE_CARD_GAP);
            float cardY = movesViewportBounds.y + movesViewportBounds.height - cardHeight
                - row * (cardHeight + MOVE_CARD_GAP) + movesScrollOffset;
            drawScaledMoveCard(moves.get(i), cardX, cardY, cardScale);
        }
        endClip();

        if (movesScrollMax > 0f) {
            drawMovesScrollbar(x + width - MOVE_PANEL_PADDING - MOVE_SCROLLBAR_WIDTH,
                movesViewportBounds.y, MOVE_SCROLLBAR_WIDTH, movesViewportBounds.height, contentHeight);
        }
    }

    private static float moveCardScale(float availableWidth) {
        float cardWidth = Math.max(0.1f,
            (availableWidth - (MOVE_COLUMNS - 1) * MOVE_CARD_GAP) / MOVE_COLUMNS);
        return Math.min(1f, cardWidth / MoveCardView.CARD_W);
    }

    private void drawScaledMoveCard(Move move, float x, float y, float scale) {
        Matrix4 originalTransform = new Matrix4(batch.getTransformMatrix());
        Matrix4 scaledTransform = new Matrix4(originalTransform)
            .translate(x, y, 0f)
            .scale(scale, scale, 1f);
        batch.setTransformMatrix(scaledTransform);
        new MoveCardView(move, 0f, 0f).draw(batch, assets.fontMedium, assets.fontSmall,
            assets.battleUi, move.getBaseCeCost());
        batch.setTransformMatrix(originalTransform);
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

        float thumbHeight = Math.max(18f, height * height / contentHeight);
        float progress = movesScrollMax <= 0f ? 0f : movesScrollOffset / movesScrollMax;
        float thumbY = y + (height - thumbHeight) * (1f - progress);
        batch.setColor(BattleUiAssets.YELLOW);
        batch.draw(assets.battleUi.pixel, x + 2f, thumbY, width - 4f, thumbHeight);
        batch.setColor(Color.WHITE);
    }

    private boolean scrollLearnedMoves(float amount) {
        if (amount == 0f || movesScrollMax <= 0f) return false;
        float pointerX = Gdx.input.getX();
        float pointerY = Gdx.graphics.getHeight() - Gdx.input.getY();
        if (!movesViewportBounds.contains(pointerX, pointerY)) return false;

        float step = Math.max(24f, Math.min(80f, movesViewportBounds.height * 0.55f));
        movesScrollOffset = clamp(movesScrollOffset + amount * step, 0f, movesScrollMax);
        return true;
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
