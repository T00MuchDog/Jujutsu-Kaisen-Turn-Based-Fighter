package com.jjktbf.graphics.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;

import java.util.ArrayList;
import java.util.List;

/**
 * Main menu screen, using the same framed command palette as battle planning.
 *
 * Options are clickable; number keys remain available as shortcuts.
 *
 * Mouse cursor is visible here (Scene2D Stage handles hit-testing).
 */
public class MainMenuScreen implements Screen {

    private final JJKGame     game;
    private final AssetLoader assets;
    private final Stage       stage;
    private final Table       root;
    private final List<MenuButton> menuButtons = new ArrayList<>();
    private final List<Cell<MenuButton>> menuButtonCells = new ArrayList<>();
    private Table commands;
    private Cell<?> commandsCell;

    public MainMenuScreen(JJKGame game, AssetLoader assets) {
        this.game   = game;
        this.assets = assets;
        this.stage  = new Stage(new ScreenViewport());

        this.root = new Table();
        root.setFillParent(true);
        root.pad(28);
        stage.addActor(root);

        buildMenu();
        layoutMenu(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    private void buildMenu() {
        Table header = new Table(assets.editorSkin);
        header.setBackground(assets.editorSkin.getDrawable("battle-header"));
        header.pad(14);

        Label title = new Label("JJK TURN BASED FIGHTER", assets.editorSkin, "title");
        title.setAlignment(Align.left);
        header.add(title).left();
        root.add(header).growX().padBottom(18).row();

        commands = new Table(assets.editorSkin);
        commands.setBackground(assets.editorSkin.getDrawable("battle-palette"));
        commands.pad(16);
        Label commandTitle = new Label("SELECT MODE", assets.editorSkin, "title");
        commandTitle.setColor(new Color(1.000f, 0.835f, 0.180f, 1f));
        commands.add(commandTitle).left().padBottom(10).row();

        MenuButton newGame   = makeButton("START BATTLE", game::showCharacterSelect);
        MenuButton moveEd    = makeButton("MOVE EDITOR", game::showMoveEditor);
        MenuButton charEd    = makeButton("CHARACTER EDITOR", game::showCharacterEditor);
        MenuButton abilityEd = makeButton("ABILITY EDITOR", game::showAbilityEditor);
        MenuButton techEd    = makeButton("TECHNIQUE EDITOR", game::showTechniqueEditor);
        MenuButton quit      = makeButton("QUIT", () -> Gdx.app.exit());

        for (MenuButton button : new MenuButton[]{ newGame, moveEd, charEd, abilityEd, techEd, quit }) {
            menuButtons.add(button);
            menuButtonCells.add(commands.add(button).growX().height(46).pad(4));
            commands.row();
        }
        commandsCell = root.add(commands).width(540).top().expandY();
        root.row();

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

    private MenuButton makeButton(String label, Runnable onClick) {
        MenuButton b = new MenuButton(label, assets.editorSkin);
        b.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { onClick.run(); }
        });
        return b;
    }

    private void layoutMenu(int width, int height) {
        float scale = Math.min(1.75f, Math.max(0.80f,
            Math.min(width / 1024f, height / 600f)));
        float panelWidth = Math.min(width - 56f * scale, 540f * scale);
        root.pad(28f * scale);
        commands.pad(16f * scale);
        commandsCell.width(panelWidth);
        for (int i = 0; i < menuButtons.size(); i++) {
            menuButtons.get(i).getLabel().setFontScale(scale);
            menuButtonCells.get(i).height(46f * scale).pad(4f * scale);
        }
        root.invalidateHierarchy();
    }

    // -------------------------------------------------------------------------
    // Screen lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void show() {
        stage.unfocusAll();
        for (MenuButton button : menuButtons) button.clearHover();
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

    @Override public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
        layoutMenu(width, height);
    }
    @Override public void pause()  {}
    @Override public void resume() {}
    @Override public void hide()   {}

    @Override
    public void dispose() {
        stage.dispose();
    }

    /** Prevents a reused menu button retaining its prior screen's hover state. */
    private static final class MenuButton extends TextButton {
        private boolean suppressHover;

        private MenuButton(String text, com.badlogic.gdx.scenes.scene2d.ui.Skin skin) {
            super(text, skin, "primary");
            addListener(new InputListener() {
                @Override
                public void enter(InputEvent event, float x, float y, int pointer, com.badlogic.gdx.scenes.scene2d.Actor fromActor) {
                    suppressHover = false;
                }

                @Override
                public boolean mouseMoved(InputEvent event, float x, float y) {
                    suppressHover = false;
                    return false;
                }
            });
        }

        private void clearHover() {
            suppressHover = true;
        }

        @Override
        public boolean isOver() {
            return !suppressHover && super.isOver();
        }
    }
}
