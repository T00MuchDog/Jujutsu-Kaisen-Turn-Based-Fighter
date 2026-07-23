package com.jjktbf.graphics.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Actor;
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
import com.jjktbf.graphics.ui.HoverScrollStage;

import java.util.ArrayList;
import java.util.List;

/**
 * Main menu screen, using the same framed command palette as battle planning.
 *
 * Options are clickable and can be selected with the arrow keys; number keys
 * remain available as shortcuts.
 *
 * Mouse cursor is visible here (Scene2D Stage handles hit-testing).
 */
public class MainMenuScreen implements Screen {

    private enum NavigationMode {
        NONE,
        CURSOR,
        KEYBOARD
    }

    private final JJKGame     game;
    private final AssetLoader assets;
    private final Stage       stage;
    private final Table       root;
    private final List<MenuButton> menuButtons = new ArrayList<>();
    private final List<Cell<MenuButton>> menuButtonCells = new ArrayList<>();
    private int selectedButtonIndex = -1;
    private int hoveredButtonIndex = -1;
    private int lastHighlightedButtonIndex = -1;
    private NavigationMode navigationMode = NavigationMode.NONE;
    /** Guards against double-dispose of native stage resources. */
    private boolean disposed;
    private Table commands;
    private Cell<?> commandsCell;

    public MainMenuScreen(JJKGame game, AssetLoader assets) {
        this.game   = game;
        this.assets = assets;
        this.stage  = new HoverScrollStage(new ScreenViewport());

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

        MenuButton singlePlayer = makeButton("SINGLE PLAYER", game::showCharacterSelect);
        MenuButton multiplayer  = makeButton("MULTIPLAYER", game::showMultiplayerMenu);
        MenuButton moveEd    = makeButton("MOVE EDITOR", game::showMoveEditor);
        MenuButton charEd    = makeButton("CHARACTER EDITOR", game::showCharacterEditor);
        MenuButton abilityEd = makeButton("ABILITY EDITOR", game::showAbilityEditor);
        MenuButton techEd    = makeButton("TECHNIQUE EDITOR", game::showTechniqueEditor);
        MenuButton quit      = makeButton("QUIT", () -> Gdx.app.exit());

        for (MenuButton button : new MenuButton[]{
            singlePlayer, multiplayer, moveEd, charEd, abilityEd, techEd, quit
        }) {
            menuButtons.add(button);
            menuButtonCells.add(commands.add(button).growX().height(46).pad(4));
            commands.row();
        }
        commandsCell = root.add(commands).width(540).top().expandY();
        root.row();

        // Capture movement before child widgets so a mouse move always leaves keyboard mode.
        stage.addCaptureListener(new InputListener() {
            @Override public boolean mouseMoved(InputEvent event, float x, float y) {
                enterCursorMode(event.getStageX(), event.getStageY());
                return false;
            }

            @Override public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Input.Keys.UP) moveSelection(-1);
                else if (keycode == Input.Keys.DOWN) moveSelection(1);
                else if (keycode == Input.Keys.ENTER) activateSelection();
                else if (keycode == Input.Keys.NUM_1) game.showCharacterSelect();
                else if (keycode == Input.Keys.NUM_2) game.showMoveEditor();
                else if (keycode == Input.Keys.NUM_3) game.showCharacterEditor();
                else if (keycode == Input.Keys.NUM_4) game.showAbilityEditor();
                else if (keycode == Input.Keys.NUM_5) game.showTechniqueEditor();
                else if (keycode == Input.Keys.NUM_6) Gdx.app.exit();
                else if (keycode == Input.Keys.NUM_7) game.showMultiplayerMenu();
                else if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.Q) Gdx.app.exit();
                else return false;
                return true;
            }
        });

    }

    private void moveSelection(int direction) {
        enterKeyboardMode();
        if (selectedButtonIndex < 0) {
            selectKeyboardButton(direction > 0 ? 0 : menuButtons.size() - 1);
            return;
        }
        selectKeyboardButton((selectedButtonIndex + direction + menuButtons.size()) % menuButtons.size());
    }

    private void enterKeyboardMode() {
        if (navigationMode == NavigationMode.KEYBOARD) return;

        selectedButtonIndex = hoveredButtonIndex >= 0
            ? hoveredButtonIndex : lastHighlightedButtonIndex;
        hoveredButtonIndex = -1;
        navigationMode = NavigationMode.KEYBOARD;
        if (selectedButtonIndex >= 0) lastHighlightedButtonIndex = selectedButtonIndex;
        updateHighlights();
    }

    private void enterCursorMode(float stageX, float stageY) {
        navigationMode = NavigationMode.CURSOR;
        selectedButtonIndex = -1;
        hoveredButtonIndex = findButtonAt(stageX, stageY);
        if (hoveredButtonIndex >= 0) lastHighlightedButtonIndex = hoveredButtonIndex;
        updateHighlights();
    }

    private void selectKeyboardButton(int index) {
        selectedButtonIndex = index;
        lastHighlightedButtonIndex = index;
        updateHighlights();
    }

    private void activateSelection() {
        if (navigationMode == NavigationMode.CURSOR) enterKeyboardMode();
        if (navigationMode == NavigationMode.KEYBOARD && selectedButtonIndex >= 0) {
            menuButtons.get(selectedButtonIndex).activate();
        }
    }

    private int findButtonAt(float stageX, float stageY) {
        Actor target = stage.hit(stageX, stageY, true);
        for (int i = 0; i < menuButtons.size(); i++) {
            MenuButton button = menuButtons.get(i);
            if (target == button || (target != null && target.isDescendantOf(button))) return i;
        }
        return -1;
    }

    private void updateHighlights() {
        int highlightedButtonIndex = switch (navigationMode) {
            case CURSOR -> hoveredButtonIndex;
            case KEYBOARD -> selectedButtonIndex;
            case NONE -> -1;
        };
        for (int i = 0; i < menuButtons.size(); i++) {
            menuButtons.get(i).setHighlighted(i == highlightedButtonIndex);
        }
    }

    private void resetNavigation() {
        navigationMode = NavigationMode.NONE;
        selectedButtonIndex = -1;
        hoveredButtonIndex = -1;
        lastHighlightedButtonIndex = -1;
        updateHighlights();
    }

    private MenuButton makeButton(String label, Runnable onClick) {
        MenuButton b = new MenuButton(label, assets.editorSkin, onClick);
        b.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { b.activate(); }
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
            // Editor fonts are oversampled (glyphs rendered FONT_OVERSAMPLE× too
            // large). Scene2D's setFontScale is absolute and overwrites the font's
            // base scale, so divide by FONT_OVERSAMPLE to keep on-screen size correct.
            menuButtons.get(i).getLabel().setFontScale(scale / AssetLoader.FONT_OVERSAMPLE);
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
        resetNavigation();
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
        if (disposed) return;
        disposed = true;
        stage.dispose();
    }

    /** Draws a hover state only when selected by the active input mode. */
    private static final class MenuButton extends TextButton {
        private final Runnable action;
        private boolean highlighted;

        private MenuButton(String text, com.badlogic.gdx.scenes.scene2d.ui.Skin skin, Runnable action) {
            super(text, skin, "primary");
            this.action = action;
        }

        private void activate() {
            action.run();
        }

        private void setHighlighted(boolean highlighted) {
            this.highlighted = highlighted;
        }

        @Override
        public boolean isOver() {
            return highlighted;
        }
    }
}
