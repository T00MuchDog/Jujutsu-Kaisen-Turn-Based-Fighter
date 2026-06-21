package com.jjktbf.graphics.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;

/**
 * Main menu screen — pixel-art themed, mouse + keyboard driven.
 *
 * Options (clickable TextButtons; number keys also work):
 *   1  NEW GAME          → CharacterSelectScreen
 *   2  MOVE EDITOR       → MoveEditorScreen
 *   3  CHARACTER EDITOR  → CharacterEditorScreen
 *   4  ABILITY EDITOR    → AbilityEditorScreen
 *   Esc / Q              Quit
 *
 * Mouse cursor is visible here (Scene2D Stage handles hit-testing).
 */
public class MainMenuScreen implements Screen {

    private final JJKGame     game;
    private final AssetLoader assets;
    private final Stage       stage;
    private final Table       root;

    public MainMenuScreen(JJKGame game, AssetLoader assets) {
        this.game   = game;
        this.assets = assets;
        this.stage  = new Stage(new ScreenViewport());

        this.root = new Table();
        root.setFillParent(true);
        root.pad(20);
        stage.addActor(root);

        buildMenu();
    }

    private void buildMenu() {
        // Title (uses the "title" style — large font)
        Label title = new Label("JJK TURN BASED FIGHTER", assets.editorSkin, "title");
        title.setAlignment(Align.center);
        title.setColor(new Color(0.992f, 0.835f, 0.314f, 1f)); // warm yellow
        root.add(title).colspan(2).padBottom(40).row();

        TextButton newGame   = makeButton("1. NEW GAME",         game::showCharacterSelect);
        TextButton moveEd    = makeButton("2. MOVE EDITOR",      game::showMoveEditor);
        TextButton charEd    = makeButton("3. CHARACTER EDITOR", game::showCharacterEditor);
        TextButton abilityEd = makeButton("4. ABILITY EDITOR",   game::showAbilityEditor);
        TextButton techEd    = makeButton("5. TECHNIQUE EDITOR", game::showTechniqueEditor);
        TextButton quit      = makeButton("ESC. QUIT",           () -> Gdx.app.exit());

        // Lay the buttons out in a centered vertical stack, sized generously.
        for (TextButton b : new TextButton[]{ newGame, moveEd, charEd, abilityEd, techEd, quit }) {
            root.add(b).width(360).height(44).pad(5).colspan(2).row();
        }

        // Keyboard shortcuts
        stage.addListener(new InputListener() {
            @Override public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Input.Keys.NUM_1) game.showCharacterSelect();
                else if (keycode == Input.Keys.NUM_2) game.showMoveEditor();
                else if (keycode == Input.Keys.NUM_3) game.showCharacterEditor();
                else if (keycode == Input.Keys.NUM_4) game.showAbilityEditor();
                else if (keycode == Input.Keys.NUM_5) game.showTechniqueEditor();
                else if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.Q) Gdx.app.exit();
                else return false;
                return true;
            }
        });

        return;
    }

    private TextButton makeButton(String label, Runnable onClick) {
        TextButton b = new TextButton(label, assets.editorSkin);
        b.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { onClick.run(); }
        });
        return b;
    }

    // -------------------------------------------------------------------------
    // Screen lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void show() {
        Gdx.input.setInputProcessor(stage);
    }

    @Override
    public void render(float delta) {
        // #CDDCFA — light blue, shared across all screens
        Gdx.gl.glClearColor(0.804f, 0.863f, 0.980f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        stage.act(delta);
        stage.draw();
    }

    @Override public void resize(int width, int height) { stage.getViewport().update(width, height, true); }
    @Override public void pause()  {}
    @Override public void resume() {}
    @Override public void hide()   {}

    @Override
    public void dispose() {
        stage.dispose();
    }
}
