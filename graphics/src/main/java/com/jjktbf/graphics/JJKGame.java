package com.jjktbf.graphics;

import com.badlogic.gdx.Game;
import com.jjktbf.AppPaths;
import com.jjktbf.controller.BattleController;
import com.jjktbf.graphics.screens.BattleScreen;
import com.jjktbf.graphics.screens.CharacterSelectScreen;
import com.jjktbf.graphics.screens.MainMenuScreen;
import com.jjktbf.graphics.screens.editors.AbilityEditorScreen;
import com.jjktbf.graphics.screens.editors.CharacterEditorScreen;
import com.jjktbf.graphics.screens.editors.MoveEditorScreen;
import com.jjktbf.graphics.screens.editors.TechniqueEditorScreen;
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

    // ── Screen instances ───────────────────────────────────────────────────────
    // The menu is rebuilt on return so inactive-stage pointer state cannot leak
    // across editor transitions. Other screens retain their reusable state.
    private MainMenuScreen        mainMenuScreen;
    private CharacterSelectScreen characterSelectScreen;
    private BattleScreen          battleScreen;
    private MoveEditorScreen       moveEditorScreen;
    private CharacterEditorScreen  characterEditorScreen;
    private AbilityEditorScreen    abilityEditorScreen;
    private TechniqueEditorScreen  techniqueEditorScreen;

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
        techniqueEditorScreen = new TechniqueEditorScreen(this, assets);

        setScreen(mainMenuScreen);
    }

    @Override
    public void dispose() {
        super.dispose();
        assets.dispose();
        // Individual screens dispose their own rendering resources.
        mainMenuScreen.dispose();
        characterSelectScreen.dispose();
        battleScreen.dispose();
        moveEditorScreen.dispose();
        characterEditorScreen.dispose();
        abilityEditorScreen.dispose();
        techniqueEditorScreen.dispose();
    }

    // -------------------------------------------------------------------------
    // Screen navigation
    // -------------------------------------------------------------------------

    public void showMainMenu() {
        mainMenuScreen.dispose();
        mainMenuScreen = new MainMenuScreen(this, assets);
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

    public void showTechniqueEditor() {
        setScreen(techniqueEditorScreen);
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
            try {
                Character player = playerData.toCharacter(moveRepo, abilityRepo);
                Character cpu    = cpuData.toCharacter(moveRepo, abilityRepo);
                BattleController controller = new BattleController(battleScreen);
                controller.runBattle(player, cpu);
            } catch (Throwable t) {
                // The battle runs on a daemon thread; an uncaught throw would
                // otherwise die silently. Write the stack trace to the per-user
                // logs directory so the cause is recoverable regardless of the
                // working directory (e.g. from a packaged app).
                try {
                    java.io.PrintWriter pw = new java.io.PrintWriter(
                        new java.io.FileWriter(AppPaths.logFile().toFile(), true));
                    pw.println("===== " + java.time.Instant.now() + " =====");
                    t.printStackTrace(pw);
                    pw.close();
                } catch (Exception ignored) {}
                throw new RuntimeException(t);
            }
        }, "battle-thread");

        battleThread.setDaemon(true); // exits when the main window closes
        battleThread.start();
    }
}
