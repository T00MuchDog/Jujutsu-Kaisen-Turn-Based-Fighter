package com.jjktbf.graphics.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterRepository;
import com.jjktbf.model.move.MoveRepository;

import java.io.IOException;
import java.util.List;

/**
 * Character selection screen.
 *
 * The player picks their character, then picks the CPU's character.
 * Selection is keyboard-driven: Up/Down to navigate, Enter to confirm,
 * Esc to go back to the main menu.
 *
 * Loads characters from the same JSON data files as the text mode.
 */
public class CharacterSelectScreen implements Screen {

    private static final String CHAR_DATA_DIR = "data/characters";
    private static final String MOVE_DATA_DIR = "data/moves";

    private enum Phase { PLAYER, CPU }

    private final JJKGame            game;
    private final AssetLoader        assets;
    private final SpriteBatch        batch;
    private final CharacterRepository charRepo;
    private final MoveRepository      moveRepo;

    private List<CharacterData> characters;
    private int    cursorIndex    = 0;
    private Phase  phase          = Phase.PLAYER;
    private CharacterData playerChoice = null;
    private String loadError      = null;

    public CharacterSelectScreen(JJKGame game, AssetLoader assets) {
        this.game      = game;
        this.assets    = assets;
        this.batch     = new SpriteBatch();
        this.charRepo  = new CharacterRepository(CHAR_DATA_DIR);
        this.moveRepo  = new MoveRepository(MOVE_DATA_DIR);
    }

    // -------------------------------------------------------------------------
    // Screen lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void show() {
        phase       = Phase.PLAYER;
        cursorIndex = 0;
        playerChoice = null;
        loadError   = null;

        try {
            moveRepo.load();
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
        handleInput();
        draw();
    }

    @Override public void resize(int w, int h) { batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h); }
    @Override public void pause()  {}
    @Override public void resume() {}
    @Override public void hide()   {}

    @Override
    public void dispose() {
        batch.dispose();
    }

    // -------------------------------------------------------------------------
    // Input
    // -------------------------------------------------------------------------

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
        if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) {
            if (phase == Phase.PLAYER) {
                playerChoice = characters.get(cursorIndex);
                phase        = Phase.CPU;
                cursorIndex  = 0;
            } else {
                CharacterData cpuChoice = characters.get(cursorIndex);
                game.startBattle(playerChoice, cpuChoice, moveRepo);
            }
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            if (phase == Phase.CPU) {
                phase       = Phase.PLAYER;
                cursorIndex = 0;
            } else {
                game.showMainMenu();
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

    private void draw() {
        float sw = Gdx.graphics.getWidth();
        float sh = Gdx.graphics.getHeight();

        batch.begin();

        if (loadError != null) {
            assets.fontSmall.setColor(Color.RED);
            assets.fontSmall.draw(batch, loadError, 20, sh * 0.5f);
            assets.fontSmall.draw(batch, "Press ESC to go back.", 20, sh * 0.5f - 20);
            batch.end();
            return;
        }

        String header = phase == Phase.PLAYER ? "SELECT YOUR CHARACTER" : "SELECT CPU CHARACTER";
        assets.fontMedium.setColor(Color.WHITE);
        assets.fontMedium.draw(batch, header, 40, sh - 40);

        float listY = sh - 80;
        float lineH = 20f;

        for (int i = 0; i < characters.size(); i++) {
            CharacterData cd = characters.get(i);
            String innate = cd.innateTechniqueName != null ? " [" + cd.innateTechniqueName + "]" : "";
            String label  = cd.name + innate;

            if (i == cursorIndex) {
                assets.fontSmall.setColor(Color.YELLOW);
                label = "> " + label;
            } else {
                assets.fontSmall.setColor(Color.WHITE);
                label = "  " + label;
            }

            assets.fontSmall.draw(batch, label, 40, listY - i * lineH);
        }

        assets.fontSmall.setColor(Color.LIGHT_GRAY);
        assets.fontSmall.draw(batch, "UP/DOWN: navigate   ENTER: confirm   ESC: back",
                20, 20);

        // Show player's selection if we're in CPU phase
        if (phase == Phase.CPU && playerChoice != null) {
            assets.fontSmall.setColor(Color.GREEN);
            assets.fontSmall.draw(batch, "Your character: " + playerChoice.name, 40, 50);
        }

        batch.end();
    }
}
