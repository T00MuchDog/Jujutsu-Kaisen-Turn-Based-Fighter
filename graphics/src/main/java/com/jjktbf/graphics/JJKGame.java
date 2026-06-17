package com.jjktbf.graphics;

import com.badlogic.gdx.Game;
import com.jjktbf.controller.BattleController;
import com.jjktbf.graphics.screens.BattleScreen;
import com.jjktbf.graphics.screens.CharacterSelectScreen;
import com.jjktbf.graphics.screens.MainMenuScreen;
import com.jjktbf.graphics.screens.editors.AbilityEditorScreen;
import com.jjktbf.graphics.screens.editors.CharacterEditorScreen;
import com.jjktbf.graphics.screens.editors.MoveEditorScreen;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.AbilityRepository;
import com.jjktbf.model.move.MoveRepository;

/**
 * Root LibGDX ApplicationListener.
 *
 * Manages screen transitions and owns the single shared AssetLoader.
 * All screens receive a reference to this class so they can trigger navigation.
 *
 * Lifecycle:
 *   create()  — called once by LibGDX after the window and GL context exist.
 *               Loads all assets via AssetLoader, then shows the main menu.
 *   dispose() — called once when the window closes. Disposes all assets.
 */
public class JJKGame extends Game {

    private AssetLoader assets;

    // ── Reusable screen instances (created once, reused across visits) ─────────
    private MainMenuScreen        mainMenuScreen;
    private CharacterSelectScreen characterSelectScreen;
    private BattleScreen          battleScreen;
    private MoveEditorScreen      moveEditorScreen;
    private CharacterEditorScreen characterEditorScreen;
    private AbilityEditorScreen   abilityEditorScreen;

    // -------------------------------------------------------------------------
    // LibGDX lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void create() {
        assets = new AssetLoader();
        assets.load();

        mainMenuScreen        = new MainMenuScreen(this, assets);
        characterSelectScreen = new CharacterSelectScreen(this, assets);
        battleScreen          = new BattleScreen(this, assets);
        moveEditorScreen      = new MoveEditorScreen(this, assets);
        characterEditorScreen = new CharacterEditorScreen(this, assets);
        abilityEditorScreen   = new AbilityEditorScreen(this, assets);

        setScreen(mainMenuScreen);
    }

    @Override
    public void dispose() {
        super.dispose();
        assets.dispose();
        // Individual screens dispose their own SpriteBatches/ShapeRenderers
        mainMenuScreen.dispose();
        characterSelectScreen.dispose();
        battleScreen.dispose();
        moveEditorScreen.dispose();
        characterEditorScreen.dispose();
        abilityEditorScreen.dispose();
    }

    // -------------------------------------------------------------------------
    // Screen navigation
    // -------------------------------------------------------------------------

    public void showMainMenu() {
        setScreen(mainMenuScreen);
    }

    public void showCharacterSelect() {
        setScreen(characterSelectScreen);
    }

    public void showMoveEditor() {
        setScreen(moveEditorScreen);
    }

    public void showCharacterEditor() {
        setScreen(characterEditorScreen);
    }

    public void showAbilityEditor() {
        setScreen(abilityEditorScreen);
    }

    /**
     * Start a battle between two chosen characters.
     *
     * The BattleController runs on a background thread so its blocking
     * promptMoveSelection() call does not stall the LibGDX render loop.
     * BattleScreen uses Gdx.app.postRunnable() to push state updates back
     * to the render thread safely.
     */
    public void startBattle(CharacterData playerData, CharacterData cpuData,
                            MoveRepository moveRepo, AbilityRepository abilityRepo) {
        setScreen(battleScreen);

        Thread battleThread = new Thread(() -> {
            Character player = playerData.toCharacter(moveRepo, abilityRepo);
            Character cpu    = cpuData.toCharacter(moveRepo, abilityRepo);
            BattleController controller = new BattleController(battleScreen);
            controller.runBattle(player, cpu);
        }, "battle-thread");

        battleThread.setDaemon(true); // exits when the main window closes
        battleThread.start();
    }
}
