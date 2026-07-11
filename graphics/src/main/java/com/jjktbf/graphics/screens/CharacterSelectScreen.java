package com.jjktbf.graphics.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.ui.StatusBar;
import com.jjktbf.graphics.ui.battle.BattleUiAssets;
import com.jjktbf.model.character.AbilityRepository;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterRepository;
import com.jjktbf.model.character.CombatStats;
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
    private static final String[] STAT_LABELS = {
        "VIT", "STR", "DUR", "SPD", "CA",
        "CE RES", "CE EFF", "CE OUT", "JS", "CTM"
    };

    private enum Phase { PLAYER, CPU }

    private final JJKGame game;
    private final AssetLoader assets;
    private final SpriteBatch batch;
    private final CharacterRepository charRepo;
    private final MoveRepository moveRepo;
    private final AbilityRepository abilityRepo;
    private final Rectangle headerBounds = new Rectangle();
    private final Rectangle listBounds = new Rectangle();
    private final Rectangle detailBounds = new Rectangle();

    private List<CharacterData> characters = List.of();
    private int cursorIndex;
    private Phase phase = Phase.PLAYER;
    private CharacterData playerChoice;
    private String loadError;

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
        phase = Phase.PLAYER;
        cursorIndex = 0;
        playerChoice = null;
        loadError = null;
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
    @Override public void dispose() { batch.dispose(); }

    private void handleInput() {
        if (loadError != null) {
            if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) game.showMainMenu();
            return;
        }

        if (Gdx.input.isKeyJustPressed(Input.Keys.UP)) {
            cursorIndex = (cursorIndex - 1 + characters.size()) % characters.size();
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.DOWN)) {
            cursorIndex = (cursorIndex + 1) % characters.size();
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
        if (index >= 0 && index < characters.size()) cursorIndex = index;
    }

    private void confirmSelection() {
        if (phase == Phase.PLAYER) {
            playerChoice = characters.get(cursorIndex);
            phase = Phase.CPU;
            cursorIndex = 0;
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

        float centerX = detailBounds.x + detailBounds.width / 2f;
        float top = detailBounds.y + detailBounds.height;
        assets.fontLarge.setColor(BattleUiAssets.TEXT);
        drawBold(assets.fontLarge, character.name, centerX - textWidth(assets.fontLarge, character.name) / 2f,
            top - 24f);

        float spriteWidth = Math.min(112f, detailBounds.width * 0.23f);
        float spriteHeight = spriteWidth * 1.5f;
        float spriteX = centerX - spriteWidth / 2f;
        float spriteY = top - 48f - spriteHeight;
        assets.battleUi.palette.draw(batch, spriteX - 8f, spriteY - 8f, spriteWidth + 16f, spriteHeight + 16f);
        Texture sprite = assets.characterSprite(character.spriteAsset, assets.playerSprite);
        batch.draw(sprite, spriteX, spriteY, spriteWidth, spriteHeight);

        CombatStats combat = character.toCombatStats();
        float barWidth = Math.min(260f, detailBounds.width * 0.56f);
        float barX = centerX - barWidth / 2f;
        float ceY = spriteY - 52f;
        StatusBar hp = new StatusBar("HP", new Color(0.260f, 0.820f, 0.360f, 1f));
        hp.setBounds(barX, ceY + 30f, barWidth, 24f);
        hp.setValues(combat.getMaxHp(), combat.getMaxHp());
        hp.draw(batch, assets.fontSmall, assets.battleUi);
        StatusBar ce = new StatusBar("CE", new Color(0.220f, 0.500f, 0.940f, 1f));
        ce.setBounds(barX, ceY, barWidth, 24f);
        ce.setValues(combat.getMaxCursedEnergy(), combat.getMaxCursedEnergy());
        ce.draw(batch, assets.fontSmall, assets.battleUi);

        float statsTop = ceY - 20f;
        drawStats(character, statsTop);
        drawDescription(character.description, statsTop - 154f);
    }

    private void drawStats(CharacterData character, float topY) {
        int[] values = {
            character.vitality, character.strength, character.durability, character.speed, character.combatAbility,
            character.cursedEnergyReserves, character.cursedEnergyEfficiency, character.cursedEnergyOutput,
            character.jujutsuSkill, character.cursedTechniqueMastery
        };
        float leftX = detailBounds.x + 30f;
        float rightX = detailBounds.x + detailBounds.width * 0.54f;
        for (int i = 0; i < values.length; i++) {
            float x = i < 5 ? leftX : rightX;
            float y = topY - (i % 5) * 25f;
            assets.fontLarge.setColor(BattleUiAssets.TEXT);
            drawBold(assets.fontLarge, STAT_LABELS[i] + ": " + values[i], x, y);
        }
    }

    private void drawDescription(String description, float topY) {
        String text = description == null || description.isBlank() ? "No character description." : description;
        assets.fontSmall.setColor(BattleUiAssets.MUTED);
        assets.fontSmall.draw(batch, "DESCRIPTION", detailBounds.x + 30f, topY);
        assets.fontSmall.setColor(BattleUiAssets.TEXT);
        List<String> lines = wrap(assets.fontSmall, text, detailBounds.width - 60f);
        float lineY = topY - 16f;
        for (String line : lines) {
            if (lineY < detailBounds.y + 10f) break;
            assets.fontSmall.draw(batch, line, detailBounds.x + 30f, lineY);
            lineY -= assets.fontSmall.getLineHeight() + 3f;
        }
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
