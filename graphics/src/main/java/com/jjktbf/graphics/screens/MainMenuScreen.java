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
 * Main menu screen, using the same framed command palette as battle planning.
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
        root.pad(28);
        stage.addActor(root);

        buildMenu();
    }

    private void buildMenu() {
        Table header = new Table(assets.editorSkin);
        header.setBackground(assets.editorSkin.getDrawable("battle-header"));
        header.pad(14);

        Label title = new Label("JJK TURN BASED FIGHTER", assets.editorSkin, "title");
        title.setAlignment(Align.left);
        Label subtitle = new Label("TURN-BASED COMBAT COMMAND CENTER", assets.editorSkin, "small");
        subtitle.setColor(new Color(0.720f, 0.800f, 0.950f, 1f));
        header.add(title).left().row();
        header.add(subtitle).left().padTop(5);
        root.add(header).growX().padBottom(18).row();

        Table commands = new Table(assets.editorSkin);
        commands.setBackground(assets.editorSkin.getDrawable("battle-palette"));
        commands.pad(16);
        Label commandTitle = new Label("SELECT MODE", assets.editorSkin, "title");
        commandTitle.setColor(new Color(1.000f, 0.835f, 0.180f, 1f));
        commands.add(commandTitle).left().padBottom(10).row();

        TextButton newGame   = makeButton("1  START BATTLE",      "primary", game::showCharacterSelect);
        TextButton moveEd    = makeButton("2  MOVE EDITOR",       "default", game::showMoveEditor);
        TextButton charEd    = makeButton("3  CHARACTER EDITOR",  "default", game::showCharacterEditor);
        TextButton abilityEd = makeButton("4  ABILITY EDITOR",    "default", game::showAbilityEditor);
        TextButton techEd    = makeButton("5  TECHNIQUE EDITOR",  "default", game::showTechniqueEditor);
        TextButton quit      = makeButton("ESC  QUIT",             "default", () -> Gdx.app.exit());

        // The command cards echo the battle planner's move palette: a primary
        // green action followed by parchment navigation cards.
        for (TextButton b : new TextButton[]{ newGame, moveEd, charEd, abilityEd, techEd, quit }) {
            commands.add(b).growX().height(46).pad(4).row();
        }
        root.add(commands).growX().maxWidth(540).top().expandY().row();

        Label help = new Label("NUMBER KEYS: OPEN MODE   |   ESC: QUIT", assets.editorSkin, "small");
        help.setColor(assets.editorSkin.get("text-dim", Color.class));
        help.setAlignment(Align.center);
        root.add(help).growX().padTop(14);

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

    }

    private TextButton makeButton(String label, String style, Runnable onClick) {
        TextButton b = new TextButton(label, assets.editorSkin, style);
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
