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
import com.jjktbf.graphics.audio.SoundCue;
import com.jjktbf.graphics.ui.HoverScrollStage;
import com.jjktbf.model.combat.BattleConfiguration;
import com.jjktbf.model.combat.BattleFormat;
import com.jjktbf.model.combat.BattleStatMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Local battle format choice shown before character selection. */
public final class BattleFormatScreen implements Screen {
    private static final Color SCREEN_BACKGROUND = new Color(0.804f, 0.863f, 0.980f, 1f);

    private final JJKGame game;
    private final AssetLoader assets;
    private final Stage stage;
    private final Table root;
    private final Table formatPanel;
    private final TextButton statModeButton;
    private final Label statModeDescription;
    private final List<FormatButton> formatButtons = new ArrayList<>();
    private final List<Cell<FormatButton>> formatButtonCells = new ArrayList<>();

    private Consumer<BattleConfiguration> onFormatSelected;
    private BattleStatMode statMode = BattleStatMode.STANDARD;
    private int selectedButtonIndex = -1;
    private int hoveredButtonIndex = -1;
    private boolean keyboardNavigation;
    private boolean disposed;

    public BattleFormatScreen(JJKGame game, AssetLoader assets) {
        this.game = game;
        this.assets = assets;
        stage = new HoverScrollStage(new ScreenViewport());

        root = new Table();
        root.setFillParent(true);
        root.pad(28f);
        stage.addActor(root);

        Table header = new Table(assets.editorSkin);
        header.setBackground(assets.editorSkin.getDrawable("battle-header"));
        header.pad(14f);
        Label title = new Label("JJK TURN BASED FIGHTER", assets.editorSkin, "title");
        title.setAlignment(Align.left);
        header.add(title).left();
        root.add(header).growX().padBottom(10f).row();

        formatPanel = new Table(assets.editorSkin);
        formatPanel.setBackground(assets.editorSkin.getDrawable("battle-palette"));
        formatPanel.pad(16f);
        Label formatTitle = new Label("SELECT BATTLE FORMAT", assets.editorSkin, "title");
        formatTitle.setColor(new Color(1f, 0.835f, 0.180f, 1f));
        formatPanel.add(formatTitle).left().padBottom(6f).row();

        Label instructions = new Label(
            "LEFT/RIGHT: SELECT  |  E: TOGGLE STATS  |  ENTER: CONFIRM  |  ESC: BACK",
            assets.editorSkin,
            "small-white"
        );
        instructions.setColor(new Color(0.720f, 0.800f, 0.950f, 1f));
        formatPanel.add(instructions).left().padBottom(14f).row();

        statModeButton = new TextButton("", assets.editorSkin, "default");
        statModeButton.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                toggleStatMode();
            }
        });
        formatPanel.add(statModeButton).growX().height(48f).padBottom(6f).row();

        statModeDescription = new Label("", assets.editorSkin, "small-white");
        statModeDescription.setWrap(true);
        statModeDescription.setColor(new Color(0.720f, 0.800f, 0.950f, 1f));
        formatPanel.add(statModeDescription).growX().left().padBottom(12f).row();
        updateStatModeText();

        Table buttonRow = new Table();
        FormatButton oneOnOne = makeFormatButton("1V1", BattleFormat.ONE_V_ONE);
        FormatButton twoOnTwo = makeFormatButton("2V2", BattleFormat.TWO_V_TWO);
        formatButtons.add(oneOnOne);
        formatButtons.add(twoOnTwo);
        formatButtonCells.add(buttonRow.add(oneOnOne).growX().padRight(8f));
        formatButtonCells.add(buttonRow.add(twoOnTwo).growX().padLeft(8f));
        formatPanel.add(buttonRow).growX().expandY().fillY();
        root.add(formatPanel).growX().maxWidth(920f).expandY().top();

        stage.addCaptureListener(new InputListener() {
            @Override
            public boolean mouseMoved(InputEvent event, float x, float y) {
                enterCursorMode(event.getStageX(), event.getStageY());
                return false;
            }

            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Input.Keys.LEFT || keycode == Input.Keys.UP) {
                    moveSelection(-1);
                } else if (keycode == Input.Keys.RIGHT || keycode == Input.Keys.DOWN) {
                    moveSelection(1);
                } else if (keycode == Input.Keys.ENTER) {
                    activateSelection();
                } else if (keycode == Input.Keys.E) {
                    toggleStatMode();
                } else if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
                    game.audio().play(SoundCue.UI_BACK);
                    game.showMainMenu();
                } else {
                    return false;
                }
                event.cancel();
                return true;
            }
        });

        layout(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    /** Sets the local battle route to run after a format is chosen. */
    public void prepare(Consumer<BattleConfiguration> onFormatSelected) {
        this.onFormatSelected = Objects.requireNonNull(onFormatSelected, "onFormatSelected");
        statMode = BattleStatMode.STANDARD;
        updateStatModeText();
    }

    private FormatButton makeFormatButton(String label, BattleFormat format) {
        FormatButton button = new FormatButton(label, assets, () -> {
            game.audio().play(SoundCue.UI_CONFIRM);
            if (onFormatSelected != null) {
                onFormatSelected.accept(new BattleConfiguration(format, statMode));
            }
        });
        button.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                button.activate();
            }
        });
        return button;
    }

    private void moveSelection(int direction) {
        if (!keyboardNavigation) {
            if (hoveredButtonIndex < 0) {
                selectedButtonIndex = direction < 0 ? 0 : formatButtons.size() - 1;
                keyboardNavigation = true;
                updateHighlights();
                game.audio().play(SoundCue.UI_NAVIGATE);
                return;
            }
            selectedButtonIndex = hoveredButtonIndex;
            hoveredButtonIndex = -1;
            keyboardNavigation = true;
        }
        selectedButtonIndex = (selectedButtonIndex + direction + formatButtons.size())
            % formatButtons.size();
        updateHighlights();
        game.audio().play(SoundCue.UI_NAVIGATE);
    }

    private void toggleStatMode() {
        statMode = statMode == BattleStatMode.STANDARD
            ? BattleStatMode.EQUALIZED : BattleStatMode.STANDARD;
        updateStatModeText();
        game.audio().play(SoundCue.UI_NAVIGATE);
    }

    private void updateStatModeText() {
        statModeButton.setText("STAT MODE: " + statMode.toString().toUpperCase());
        statModeDescription.setText(statMode == BattleStatMode.EQUALIZED
            ? "Runtime stats are blended halfway toward 80. Move and ability loadouts stay unchanged."
            : "Runtime stats use their normal scaled values.");
    }

    private void activateSelection() {
        if (!keyboardNavigation) {
            selectedButtonIndex = hoveredButtonIndex >= 0 ? hoveredButtonIndex : 0;
            hoveredButtonIndex = -1;
            keyboardNavigation = true;
            updateHighlights();
        }
        formatButtons.get(selectedButtonIndex).activate();
    }

    private void enterCursorMode(float stageX, float stageY) {
        keyboardNavigation = false;
        selectedButtonIndex = -1;
        hoveredButtonIndex = findButtonAt(stageX, stageY);
        updateHighlights();
    }

    private int findButtonAt(float stageX, float stageY) {
        Actor target = stage.hit(stageX, stageY, true);
        for (int i = 0; i < formatButtons.size(); i++) {
            FormatButton button = formatButtons.get(i);
            if (target == button || (target != null && target.isDescendantOf(button))) {
                return i;
            }
        }
        return -1;
    }

    private void resetNavigation() {
        keyboardNavigation = false;
        selectedButtonIndex = -1;
        hoveredButtonIndex = -1;
        updateHighlights();
    }

    private void updateHighlights() {
        int highlighted = keyboardNavigation ? selectedButtonIndex : hoveredButtonIndex;
        for (int i = 0; i < formatButtons.size(); i++) {
            formatButtons.get(i).setHighlighted(i == highlighted);
        }
    }

    private void layout(int width, int height) {
        float scale = Math.min(1.75f, Math.max(0.80f,
            Math.min(width / 1024f, height / 600f)));
        root.pad(28f * scale);
        formatPanel.pad(16f * scale);
        float buttonHeight = Math.max(150f * scale,
            Math.min(300f * scale, height * 0.45f));
        for (int i = 0; i < formatButtons.size(); i++) {
            formatButtons.get(i).getLabel().setFontScale(2.2f * scale / AssetLoader.FONT_OVERSAMPLE);
            formatButtonCells.get(i).height(buttonHeight).pad(8f * scale);
        }
        root.invalidateHierarchy();
    }

    @Override
    public void show() {
        resetNavigation();
        Gdx.input.setCatchKey(Input.Keys.BACK, true);
        Gdx.input.setInputProcessor(stage);
    }

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(SCREEN_BACKGROUND.r, SCREEN_BACKGROUND.g, SCREEN_BACKGROUND.b,
            SCREEN_BACKGROUND.a);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        stage.act(delta);
        stage.draw();
    }

    @Override
    public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
        layout(width, height);
    }

    @Override public void pause() {}
    @Override public void resume() {}

    @Override
    public void hide() {
        stage.cancelTouchFocus();
        Gdx.input.setCatchKey(Input.Keys.BACK, false);
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        stage.dispose();
    }

    /** Same primary-button styling as the main menu, with explicit keyboard hover. */
    private static final class FormatButton extends TextButton {
        private final Runnable action;
        private boolean highlighted;

        private FormatButton(String text, AssetLoader assets, Runnable action) {
            super(text, assets.editorSkin, "primary");
            this.action = action;
            getLabel().setAlignment(Align.center);
        }

        private void activate() {
            setChecked(false);
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
