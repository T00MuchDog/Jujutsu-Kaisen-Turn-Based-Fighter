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
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.ImageButton;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Timer;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.jjktbf.AppPaths;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.audio.AudioChannel;
import com.jjktbf.graphics.audio.AudioSettings;
import com.jjktbf.graphics.audio.SoundCue;
import com.jjktbf.graphics.ui.ContentSizedDialog;
import com.jjktbf.graphics.ui.HoverScrollStage;
import com.jjktbf.graphics.ui.editor.HoverTextField;
import com.jjktbf.graphics.ui.profile.UiProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Main menu screen, using the same framed command palette as battle planning.
 *
 * Options are clickable and can be selected with the arrow keys; number keys
 * remain available as shortcuts.
 *
 * Mouse cursor is visible here (Scene2D Stage handles hit-testing).
 */
public class MainMenuScreen implements Screen {

    private static final float WINDOWS_MAX_RESPONSIVE_SCALE = 1.75f;
    private static final float WINDOWS_HEADER_X = 49f;
    private static final float WINDOWS_HEADER_WIDTH_INSET = 98f;
    private static final float WINDOWS_HEADER_HEIGHT = 126f;
    private static final float WINDOWS_HEADER_BOTTOM_FROM_TOP = 154f;
    private static final float WINDOWS_SETTINGS_X = 70f;
    private static final float WINDOWS_SETTINGS_TOP_FROM_TOP = 158f;
    private static final float WINDOWS_SETTINGS_SIZE_PER_SCALE = 184f;
    private static final float WINDOWS_MENU_HALF_WIDTH = 567f;
    private static final float WINDOWS_MENU_FIXED_HEIGHT = 62.5f;
    private static final float WINDOWS_MENU_SCALED_HEIGHT = 861f;
    private static final float WINDOWS_MENU_HALF_FIXED_HEIGHT = 31.25f;
    private static final float WINDOWS_MENU_HALF_SCALED_HEIGHT = 430.5f;
    private static final float WINDOWS_AUTHOR_MENU_SCALED_HEIGHT = 974.4f;
    private static final float WINDOWS_AUTHOR_MENU_HALF_SCALED_HEIGHT = 487.2f;
    private static final float WINDOWS_MENU_SIDE_CLEARANCE_BASE = 74f;
    private static final float WINDOWS_MENU_SIDE_CLEARANCE_SCALED = 322f;
    private static final float WINDOWS_MENU_MIN_HALF_WIDTH = 99f;
    private static final float WINDOWS_COMMAND_PADDING = 33.6f;
    private static final float WINDOWS_BUTTON_HEIGHT = 96.6f;
    private static final float WINDOWS_BUTTON_PADDING = 8.4f;
    private static final float WINDOWS_TITLE_FONT_SCALE = 0.25f;
    private static final float WINDOWS_BUTTON_FONT_SCALE = 0.525f;
    private static final float WINDOWS_COMMAND_VIEWPORT_INSET = 168f;

    private enum NavigationMode {
        NONE,
        CURSOR,
        KEYBOARD
    }

    private final JJKGame     game;
    private final AssetLoader assets;
    private final Stage       stage;
    private final Table       root;
    private final boolean     windowsLayout;
    private final boolean     authoringMenu;
    private final List<MenuButton> menuButtons = new ArrayList<>();
    private final List<Cell<MenuButton>> menuButtonCells = new ArrayList<>();
    private int selectedButtonIndex = -1;
    private int hoveredButtonIndex = -1;
    private int lastHighlightedButtonIndex = -1;
    private NavigationMode navigationMode = NavigationMode.NONE;
    /** Guards against double-dispose of native stage resources. */
    private boolean disposed;
    private Table header;
    private Label title;
    private FixedSizeTable commands;
    private ScrollPane commandsScroll;
    private Label commandTitle;
    private Cell<?> commandsCell;
    private ImageButton settingsButton;
    private Cell<ImageButton> settingsButtonCell;
    private Dialog settingsDialog;
    private boolean exitPending;

    public MainMenuScreen(JJKGame game, AssetLoader assets) {
        this.game   = game;
        this.assets = assets;
        this.stage  = new HoverScrollStage(new ScreenViewport());
        this.windowsLayout = game.activeUiProfile() == UiProfile.WINDOWS;
        this.authoringMenu = AppPaths.isAuthoringMode();

        this.root = new Table();
        if (!windowsLayout) {
            root.setFillParent(true);
            root.pad(28);
            stage.addActor(root);
        }

        buildMenu();
        layoutMenu(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    private void buildMenu() {
        header = new Table(assets.editorSkin);
        header.setBackground(assets.editorSkin.getDrawable("battle-header"));
        header.pad(14);

        title = new Label("JJK TURN BASED FIGHTER", assets.editorSkin, "title");
        if (windowsLayout) {
            title.setAlignment(Align.center);
            header.add(title).grow().center();
            stage.addActor(header);
        } else {
            title.setAlignment(Align.left);
            header.add(title).left();
            root.add(header).growX().padBottom(10).row();
        }

        // This pointer-only control must never participate in arrow-key navigation.
        settingsButton = makeSettingsButton();
        if (windowsLayout) {
            settingsButton.getImageCell().expand().fill();
            stage.addActor(settingsButton);
        } else {
            settingsButtonCell = root.add(settingsButton).left().size(46).padBottom(8);
            root.row();
        }

        commands = new FixedSizeTable(assets.editorSkin);
        commands.setBackground(assets.editorSkin.getDrawable("battle-palette"));
        commands.pad(16);
        commandTitle = new Label("SELECT MODE", assets.editorSkin, "title");
        commandTitle.setColor(new Color(1.000f, 0.835f, 0.180f, 1f));
        commands.add(commandTitle).left().padBottom(10).row();

        MenuButton singlePlayer = makeButton("SINGLE PLAYER", game::showSinglePlayerBattle);
        MenuButton multiplayer  = makeButton("MULTIPLAYER", game::showMultiplayerMenu);
        MenuButton moveEd    = makeButton("MOVE EDITOR", game::showMoveEditor);
        MenuButton charEd    = makeButton("CHARACTER EDITOR", game::showCharacterEditor);
        MenuButton abilityEd = makeButton("ABILITY EDITOR", game::showAbilityEditor);
        MenuButton techEd    = makeButton("TECHNIQUE EDITOR", game::showTechniqueEditor);
        MenuButton toolEd    = makeButton("CURSED TOOL EDITOR", game::showCursedToolEditor);
        MenuButton quit      = makeButton("QUIT", this::exitApplication);

        List<MenuButton> buttons = new ArrayList<>(List.of(singlePlayer, multiplayer));
        if (authoringMenu) {
            buttons.add(makeButton("AUTHOR BATTLE (CONTROL BOTH SIDES)", game::showAuthorBattle));
        }
        buttons.addAll(List.of(charEd, moveEd, abilityEd, techEd, toolEd, quit));
        for (MenuButton button : buttons) {
            menuButtons.add(button);
            menuButtonCells.add(commands.add(button).growX().height(46).pad(4));
            commands.row();
        }
        if (windowsLayout) {
            commandsScroll = new ScrollPane(commands, new ScrollPane.ScrollPaneStyle());
            commandsScroll.setScrollingDisabled(true, false);
            commandsScroll.setOverscroll(false, false);
            commandsScroll.setFlickScroll(false);
            stage.addActor(commandsScroll);
        } else {
            commandsCell = root.add(commands).width(540);
            root.row();
        }

        // Capture movement before child widgets so a mouse move always leaves keyboard mode.
        stage.addCaptureListener(new InputListener() {
            @Override public boolean mouseMoved(InputEvent event, float x, float y) {
                if (isSettingsOpen()) return false;
                enterCursorMode(event.getStageX(), event.getStageY());
                return false;
            }

            @Override public boolean keyDown(InputEvent event, int keycode) {
                if (isSettingsOpen()) {
                    if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
                        game.audio().play(SoundCue.UI_BACK);
                        closeSettings();
                        event.cancel();
                        return true;
                    }
                    return false;
                }
                if (activateShortcut(keycode)) return true;
                if (keycode == Input.Keys.UP) moveSelection(-1);
                else if (keycode == Input.Keys.DOWN) moveSelection(1);
                else if (keycode == Input.Keys.ENTER) activateSelection();
                else if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.Q) exitApplication();
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
        boolean changed = selectedButtonIndex != index;
        selectedButtonIndex = index;
        lastHighlightedButtonIndex = index;
        updateHighlights();
        revealWindowsMenuButton(index);
        if (changed) game.audio().play(SoundCue.UI_NAVIGATE);
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
        if (windowsLayout && commandsScroll != null) {
            commandsScroll.setScrollPercentY(0f);
        }
    }

    private MenuButton makeButton(String label, Runnable onClick) {
        MenuButton b = new MenuButton(label, assets.editorSkin, () -> {
            if (!"QUIT".equals(label)) game.audio().play(SoundCue.UI_CONFIRM);
            onClick.run();
        });
        b.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { b.activate(); }
        });
        return b;
    }

    private ImageButton makeSettingsButton() {
        ImageButton.ImageButtonStyle style = new ImageButton.ImageButtonStyle();
        style.imageUp = assets.editorSkin.getDrawable("settings-icon");
        style.imageOver = assets.editorSkin.getDrawable("settings-icon-highlighted");
        style.imageDown = style.imageOver;

        ImageButton button = new ImageButton(style);
        button.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) {
                game.audio().play(SoundCue.UI_CONFIRM);
                showSettings();
            }
        });
        return button;
    }

    private boolean activateShortcut(int keycode) {
        int buttonIndex = switch (keycode) {
            case Input.Keys.NUM_1 -> 0;
            case Input.Keys.NUM_2 -> 1;
            case Input.Keys.NUM_3 -> 2;
            case Input.Keys.NUM_4 -> 3;
            case Input.Keys.NUM_5 -> 4;
            case Input.Keys.NUM_6 -> 5;
            case Input.Keys.NUM_7 -> 6;
            case Input.Keys.NUM_8 -> 7;
            case Input.Keys.NUM_9 -> 8;
            default -> -1;
        };
        if (buttonIndex < 0 || buttonIndex >= menuButtons.size()) return false;
        menuButtons.get(buttonIndex).activate();
        return true;
    }

    private void exitApplication() {
        if (exitPending) return;
        exitPending = true;
        game.audio().play(SoundCue.UI_BACK);
        Timer.schedule(new Timer.Task() {
            @Override public void run() {
                Gdx.app.exit();
            }
        }, 0.16f);
    }

    private void showSettings() {
        if (isSettingsOpen()) return;
        resetNavigation();

        Skin skin = assets.editorSkin;
        ContentSizedDialog dialog = new ContentSizedDialog(
            "SETTINGS", skin, game.activeUiProfile());
        dialog.setModal(true);
        dialog.setMovable(false);
        dialog.setResizable(false);

        TextButton close = new TextButton("X", closeButtonStyle(skin));
        close.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) {
                game.audio().play(SoundCue.UI_BACK);
                closeSettings();
            }
        });
        float closeSize = windowsLayout ? 45f : 30f;
        dialog.getTitleTable().add(close).right().size(closeSize).padLeft(8f).padRight(2f);

        AudioSettings settings = game.audio().settings();
        Table content = dialog.getContentTable();
        content.pad(10f, 16f, 14f, 16f);
        addVolumeRow(content, "MUSIC", Math.round(settings.musicVolume() * 100f), value -> {
            AudioSettings current = game.audio().settings();
            game.audio().previewSettings(
                current.withChannelVolume(AudioChannel.MUSIC, value / 100f));
        });
        int effectsVolume = Math.round(
            (settings.uiSfxVolume() + settings.battleSfxVolume()) * 50f);
        addVolumeRow(content, "EFFECTS", effectsVolume, value -> {
            float volume = value / 100f;
            AudioSettings current = game.audio().settings();
            game.audio().previewSettings(
                current.withChannelVolume(AudioChannel.UI_SFX, volume)
                    .withChannelVolume(AudioChannel.BATTLE_SFX, volume));
        });

        settingsDialog = dialog;
        dialog.show(stage);
    }

    private void addVolumeRow(
        Table content,
        String name,
        int initialValue,
        IntConsumer onChange
    ) {
        int initial = Math.max(0, Math.min(100, initialValue));
        Label nameLabel = new Label(name, assets.editorSkin);
        Label minimum = new Label("0", assets.editorSkin, "small");
        Label maximum = new Label("100", assets.editorSkin, "small");
        minimum.setColor(assets.editorSkin.get("text-dim", Color.class));
        maximum.setColor(assets.editorSkin.get("text-dim", Color.class));

        Slider slider = new Slider(0f, 100f, 1f, false, assets.editorSkin);
        slider.setValue(initial);
        TextField valueField = new HoverTextField(String.valueOf(initial), assets.editorSkin);
        valueField.setTextFieldFilter((field, character) -> Character.isDigit(character));
        valueField.setMaxLength(3);
        valueField.setAlignment(Align.center);

        boolean[] syncing = {false};
        int[] appliedValue = {initial};
        slider.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                if (syncing[0]) return;
                int value = Math.round(slider.getValue());
                valueField.setText(String.valueOf(value));
                if (value == appliedValue[0]) return;
                appliedValue[0] = value;
                onChange.accept(value);
            }
        });

        Runnable commitField = () -> {
            int value = parseVolumePercent(valueField.getText(), Math.round(slider.getValue()));
            boolean changed = value != appliedValue[0];
            syncing[0] = true;
            slider.setValue(value);
            valueField.setText(String.valueOf(value));
            syncing[0] = false;
            if (changed) {
                appliedValue[0] = value;
                onChange.accept(value);
            }
        };
        valueField.addListener(new InputListener() {
            @Override public boolean keyDown(InputEvent event, int keycode) {
                if (keycode != Input.Keys.ENTER) return false;
                commitField.run();
                return true;
            }
        });
        valueField.addListener(new FocusListener() {
            @Override public void keyboardFocusChanged(
                FocusEvent event,
                Actor actor,
                boolean focused
            ) {
                if (!focused) commitField.run();
            }
        });

        content.add(nameLabel).colspan(4).left().padTop(6f).padBottom(2f);
        content.row();
        float minimumWidth = windowsLayout ? 30f : 20f;
        float maximumWidth = windowsLayout ? 51f : 34f;
        float valueWidth = windowsLayout ? 87f : 58f;
        float valueHeight = windowsLayout ? 51f : 34f;
        content.add(minimum).right().width(minimumWidth).padRight(5f);
        content.add(slider).growX().minWidth(100f).prefWidth(180f).height(32f).padRight(5f);
        content.add(maximum).left().width(maximumWidth).padRight(8f);
        content.add(valueField).width(valueWidth).height(valueHeight);
        content.row();
    }

    private static TextButton.TextButtonStyle closeButtonStyle(Skin skin) {
        TextButton.TextButtonStyle style = new TextButton.TextButtonStyle(
            skin.get(TextButton.TextButtonStyle.class));
        style.up = null;
        style.down = null;
        style.over = null;
        style.fontColor = Color.GRAY;
        style.downFontColor = Color.DARK_GRAY;
        style.overFontColor = Color.LIGHT_GRAY;
        return style;
    }

    static int parseVolumePercent(String text, int fallback) {
        int safeFallback = Math.max(0, Math.min(100, fallback));
        if (text == null || text.isBlank()) return safeFallback;
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(text.trim())));
        } catch (NumberFormatException ignored) {
            return safeFallback;
        }
    }

    private boolean isSettingsOpen() {
        return settingsDialog != null && settingsDialog.getStage() != null;
    }

    private void closeSettings() {
        if (settingsDialog == null) return;
        stage.cancelTouchFocus();
        stage.setKeyboardFocus(null);
        stage.setScrollFocus(null);
        game.audio().persistSettings();
        settingsDialog.remove();
        settingsDialog = null;
        resetNavigation();
    }

    private void layoutMenu(int width, int height) {
        float scale = Math.min(1.75f, Math.max(0.80f,
            Math.min(width / 1024f, height / 600f)));
        if (windowsLayout) {
            layoutWindowsMenu(width, height, scale);
            return;
        }

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
        settingsButtonCell.size(46f * scale);
        root.invalidateHierarchy();
    }

    private void layoutWindowsMenu(int width, int height, float responsiveScale) {
        float referenceScale = responsiveScale / WINDOWS_MAX_RESPONSIVE_SCALE;
        float menuHeight = WINDOWS_MENU_FIXED_HEIGHT
            + (authoringMenu
                ? WINDOWS_AUTHOR_MENU_SCALED_HEIGHT
                : WINDOWS_MENU_SCALED_HEIGHT) * referenceScale;
        float menuY = height * 0.5f - WINDOWS_MENU_HALF_FIXED_HEIGHT
            - (authoringMenu
                ? WINDOWS_AUTHOR_MENU_HALF_SCALED_HEIGHT
                : WINDOWS_MENU_HALF_SCALED_HEIGHT) * referenceScale;
        float menuHalfWidth = windowsCommandViewportHalfWidth(width, referenceScale);
        float menuWidth = menuHalfWidth * 2f;
        float viewportHeight = windowsCommandViewportHeight(height, menuHeight);
        float viewportY = windowsCommandViewportY(height, menuHeight, menuY);
        float settingsSize = WINDOWS_SETTINGS_SIZE_PER_SCALE * responsiveScale;

        header.setBounds(
            WINDOWS_HEADER_X,
            height - WINDOWS_HEADER_BOTTOM_FROM_TOP,
            width - WINDOWS_HEADER_WIDTH_INSET,
            WINDOWS_HEADER_HEIGHT);
        settingsButton.setBounds(
            WINDOWS_SETTINGS_X,
            height - WINDOWS_SETTINGS_TOP_FROM_TOP
                - WINDOWS_SETTINGS_SIZE_PER_SCALE * responsiveScale,
            settingsSize,
            settingsSize);
        commandsScroll.setBounds(
            width * 0.5f - menuHalfWidth,
            viewportY,
            menuWidth,
            viewportHeight);
        commands.setExplicitPrefSize(menuWidth, menuHeight);
        title.setFontScale(WINDOWS_TITLE_FONT_SCALE);
        commandTitle.setFontScale(WINDOWS_TITLE_FONT_SCALE);
        commands.pad(WINDOWS_COMMAND_PADDING * referenceScale);
        for (int i = 0; i < menuButtons.size(); i++) {
            menuButtons.get(i).getLabel().setFontScale(
                WINDOWS_BUTTON_FONT_SCALE * referenceScale);
            menuButtonCells.get(i)
                .height(WINDOWS_BUTTON_HEIGHT * referenceScale)
                .pad(WINDOWS_BUTTON_PADDING * referenceScale);
        }
        header.invalidateHierarchy();
        commands.invalidateHierarchy();
        commandsScroll.invalidateHierarchy();
        commandsScroll.validate();
        if (selectedButtonIndex >= 0) {
            revealWindowsMenuButton(selectedButtonIndex);
        }
    }

    static float windowsCommandViewportHeight(float screenHeight, float menuHeight) {
        return Math.min(menuHeight,
            Math.max(1f, screenHeight - WINDOWS_COMMAND_VIEWPORT_INSET * 2f));
    }

    static float windowsCommandViewportHalfWidth(float screenWidth, float referenceScale) {
        float availableHalfWidth = screenWidth * 0.5f
            - WINDOWS_MENU_SIDE_CLEARANCE_BASE
            - WINDOWS_MENU_SIDE_CLEARANCE_SCALED * referenceScale;
        return Math.min(WINDOWS_MENU_HALF_WIDTH * referenceScale,
            Math.max(WINDOWS_MENU_MIN_HALF_WIDTH, availableHalfWidth));
    }

    static float windowsCommandViewportY(
        float screenHeight,
        float menuHeight,
        float unclippedMenuY
    ) {
        float viewportHeight = windowsCommandViewportHeight(screenHeight, menuHeight);
        return viewportHeight < menuHeight
            ? (screenHeight - viewportHeight) * 0.5f : unclippedMenuY;
    }

    private void revealWindowsMenuButton(int index) {
        if (!windowsLayout || commandsScroll == null || index < 0
            || index >= menuButtons.size()) {
            return;
        }
        float responsiveScale = Math.min(1.75f, Math.max(0.80f,
            Math.min(stage.getWidth() / 1024f, stage.getHeight() / 600f)));
        float referenceScale = responsiveScale / WINDOWS_MAX_RESPONSIVE_SCALE;
        float rowHeight = (WINDOWS_BUTTON_HEIGHT + WINDOWS_BUTTON_PADDING * 2f)
            * referenceScale;
        float rowY = WINDOWS_COMMAND_PADDING * referenceScale
            + (menuButtons.size() - 1 - index) * rowHeight;
        commandsScroll.scrollTo(0f, rowY, 0f, rowHeight, false, true);
        commandsScroll.updateVisualScroll();
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
    @Override public void hide()   { closeSettings(); }

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

    private static final class FixedSizeTable extends Table {
        private float explicitPrefWidth;
        private float explicitPrefHeight;

        private FixedSizeTable(Skin skin) {
            super(skin);
        }

        private void setExplicitPrefSize(float width, float height) {
            explicitPrefWidth = width;
            explicitPrefHeight = height;
            invalidateHierarchy();
        }

        @Override
        public float getPrefWidth() {
            return explicitPrefWidth > 0f ? explicitPrefWidth : super.getPrefWidth();
        }

        @Override
        public float getPrefHeight() {
            return explicitPrefHeight > 0f ? explicitPrefHeight : super.getPrefHeight();
        }
    }
}
