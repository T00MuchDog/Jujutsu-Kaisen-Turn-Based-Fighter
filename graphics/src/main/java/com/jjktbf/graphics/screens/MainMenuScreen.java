package com.jjktbf.graphics.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;

/**
 * Main menu screen.
 *
 * Options:
 *   1  New Game         → CharacterSelectScreen
 *   2  Move Editor      → MoveEditorScreen
 *   3  Character Editor → CharacterEditorScreen
 *   4  Ability Editor   → AbilityEditorScreen
 *   Esc  Quit
 *
 * Keyboard-driven for now (pixel art aesthetic — no mouse cursor needed).
 */
public class MainMenuScreen implements Screen {

    private static final String[] OPTIONS = {
        "1. NEW GAME",
        "2. MOVE EDITOR",
        "3. CHARACTER EDITOR",
        "4. ABILITY EDITOR",
        "ESC. QUIT"
    };

    private final JJKGame     game;
    private final AssetLoader assets;
    private final SpriteBatch batch;

    public MainMenuScreen(JJKGame game, AssetLoader assets) {
        this.game   = game;
        this.assets = assets;
        this.batch  = new SpriteBatch();
    }

    // -------------------------------------------------------------------------
    // Screen lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void show() {}

    @Override
    public void render(float delta) {
        clearScreen();
        handleInput();
        drawMenu();
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
        if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_1)) game.showCharacterSelect();
        if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_2)) game.showMoveEditor();
        if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_3)) game.showCharacterEditor();
        if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_4)) game.showAbilityEditor();
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) Gdx.app.exit();
    }

    // -------------------------------------------------------------------------
    // Draw
    // -------------------------------------------------------------------------

    private void clearScreen() {
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    }

    private void drawMenu() {
        float sw = Gdx.graphics.getWidth();
        float sh = Gdx.graphics.getHeight();

        batch.begin();

        // Title
        assets.fontLarge.setColor(Color.WHITE);
        String title = "JJK TURN BASED FIGHTER";
        assets.fontLarge.draw(batch, title, sw * 0.5f - title.length() * 5f, sh * 0.75f);

        // Options
        assets.fontSmall.setColor(Color.WHITE);
        float startY = sh * 0.55f;
        float lineH  = 22f;
        for (int i = 0; i < OPTIONS.length; i++) {
            assets.fontSmall.draw(batch, OPTIONS[i], sw * 0.3f, startY - i * lineH);
        }

        batch.end();
    }
}
