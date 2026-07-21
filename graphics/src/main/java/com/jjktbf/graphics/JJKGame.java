package com.jjktbf.graphics;

import com.badlogic.gdx.Game;
import com.jjktbf.AppPaths;
import com.jjktbf.controller.BattleController;
import com.jjktbf.graphics.multiplayer.ChallengeService;
import com.jjktbf.graphics.multiplayer.ClientNetworkConfig;
import com.jjktbf.graphics.multiplayer.GuestAccountService;
import com.jjktbf.graphics.multiplayer.GuestCredentialsStore;
import com.jjktbf.graphics.multiplayer.HttpApiClient;
import com.jjktbf.graphics.multiplayer.MatchWebSocketClient;
import com.jjktbf.graphics.multiplayer.MultiplayerMatchService;
import com.jjktbf.graphics.multiplayer.MultiplayerSession;
import com.jjktbf.graphics.screens.BattleScreen;
import com.jjktbf.graphics.screens.CharacterSelectScreen;
import com.jjktbf.graphics.screens.ChallengeBrowserScreen;
import com.jjktbf.graphics.screens.HostChallengeScreen;
import com.jjktbf.graphics.screens.MainMenuScreen;
import com.jjktbf.graphics.screens.MultiplayerDisconnectedScreen;
import com.jjktbf.graphics.screens.MultiplayerMenuScreen;
import com.jjktbf.graphics.screens.editors.AbilityEditorScreen;
import com.jjktbf.graphics.screens.editors.CharacterEditorScreen;
import com.jjktbf.graphics.screens.editors.MoveEditorScreen;
import com.jjktbf.graphics.screens.editors.TechniqueEditorScreen;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterRepository;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.AbilityRepository;
import com.jjktbf.model.move.MoveRepository;
import com.jjktbf.model.technique.TechniqueRepository;
import com.jjktbf.multiplayer.protocol.MatchSetup;

import java.io.IOException;
import java.util.List;

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

    public static final String DEFAULT_MULTIPLAYER_CHARACTER_ID = "000000";

    public record MultiplayerFighter(String id, String name) {
        public MultiplayerFighter {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("fighter id must not be blank");
            }
            if (name == null || name.isBlank()) {
                name = "Unnamed fighter";
            }
        }

        @Override
        public String toString() {
            return name + "  [" + id + "]";
        }
    }

    private AssetLoader assets;

    // One application-lifetime multiplayer service graph.
    private ClientNetworkConfig clientNetworkConfig;
    private MultiplayerSession multiplayerSession;
    private HttpApiClient httpApiClient;
    private GuestCredentialsStore guestCredentialsStore;
    private GuestAccountService guestAccountService;
    private ChallengeService challengeService;
    private MatchWebSocketClient matchWebSocketClient;
    private MultiplayerMatchService multiplayerMatchService;

    private CharacterRepository multiplayerCharacterRepository;
    private List<MultiplayerFighter> multiplayerRoster = List.of();
    private String multiplayerRosterError;
    private String multiplayerConfigurationError;
    private String selectedMultiplayerCharacterId = DEFAULT_MULTIPLAYER_CHARACTER_ID;

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
    private MultiplayerMenuScreen multiplayerMenuScreen;
    private HostChallengeScreen hostChallengeScreen;
    private ChallengeBrowserScreen challengeBrowserScreen;
    private MultiplayerDisconnectedScreen multiplayerDisconnectedScreen;

    // -------------------------------------------------------------------------
    // LibGDX lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void create() {
        assets = new AssetLoader();
        assets.load();

        try {
            clientNetworkConfig = ClientNetworkConfig.load();
        } catch (IllegalArgumentException exception) {
            multiplayerConfigurationError =
                "Multiplayer configuration is invalid: " + exception.getMessage();
            clientNetworkConfig = new ClientNetworkConfig(
                ClientNetworkConfig.DEFAULT_HTTP_URL,
                ClientNetworkConfig.DEFAULT_WEBSOCKET_URL
            );
        }
        multiplayerSession = new MultiplayerSession();
        httpApiClient = new HttpApiClient(clientNetworkConfig);
        guestCredentialsStore = new GuestCredentialsStore();
        guestAccountService = new GuestAccountService(
            httpApiClient, guestCredentialsStore, multiplayerSession);
        challengeService = new ChallengeService(httpApiClient, multiplayerSession);
        matchWebSocketClient = new MatchWebSocketClient(clientNetworkConfig);
        multiplayerMatchService = new MultiplayerMatchService(
            multiplayerSession, matchWebSocketClient);

        multiplayerCharacterRepository = new CharacterRepository("data/characters");
        reloadMultiplayerRoster();

        mainMenuScreen        = new MainMenuScreen(this, assets);
        characterSelectScreen = new CharacterSelectScreen(this, assets);
        battleScreen          = new BattleScreen(this, assets);
        moveEditorScreen      = new MoveEditorScreen(this, assets);
        characterEditorScreen = new CharacterEditorScreen(this, assets);
        abilityEditorScreen   = new AbilityEditorScreen(this, assets);
        techniqueEditorScreen = new TechniqueEditorScreen(this, assets);
        multiplayerMenuScreen = new MultiplayerMenuScreen(
            this, assets, guestAccountService);
        hostChallengeScreen = new HostChallengeScreen(
            this, assets, guestAccountService, challengeService);
        challengeBrowserScreen = new ChallengeBrowserScreen(
            this, assets, guestAccountService, challengeService);
        multiplayerDisconnectedScreen = new MultiplayerDisconnectedScreen(
            this,
            assets,
            guestAccountService,
            challengeService,
            multiplayerMatchService
        );

        setScreen(mainMenuScreen);
    }

    @Override
    public void dispose() {
        // Match service owns the socket lifecycle, so closing it is the only
        // MatchWebSocketClient close. HTTP and guest file workers are separate.
        if (multiplayerMatchService != null) {
            multiplayerMatchService.close();
        } else if (matchWebSocketClient != null) {
            matchWebSocketClient.close();
        }
        if (guestAccountService != null) guestAccountService.close();
        if (httpApiClient != null) httpApiClient.close();

        super.dispose();
        // Screens release their stages/schedulers before the shared skin and
        // textures disappear. Every screen instance is disposed exactly once.
        if (mainMenuScreen != null) mainMenuScreen.dispose();
        if (characterSelectScreen != null) characterSelectScreen.dispose();
        if (battleScreen != null) battleScreen.dispose();
        if (moveEditorScreen != null) moveEditorScreen.dispose();
        if (characterEditorScreen != null) characterEditorScreen.dispose();
        if (abilityEditorScreen != null) abilityEditorScreen.dispose();
        if (techniqueEditorScreen != null) techniqueEditorScreen.dispose();
        if (multiplayerMenuScreen != null) multiplayerMenuScreen.dispose();
        if (hostChallengeScreen != null) hostChallengeScreen.dispose();
        if (challengeBrowserScreen != null) challengeBrowserScreen.dispose();
        if (multiplayerDisconnectedScreen != null) multiplayerDisconnectedScreen.dispose();
        if (assets != null) assets.dispose();
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

    public void showMultiplayerMenu() {
        setScreen(multiplayerMenuScreen);
    }

    public void showHostChallenge() {
        setScreen(hostChallengeScreen);
    }

    public void showChallengeBrowser() {
        setScreen(challengeBrowserScreen);
    }

    public void showMultiplayerBattle(MatchSetup setup) {
        multiplayerSession.setMatchSetup(setup);
        battleScreen.prepareMultiplayer(setup, multiplayerSession, multiplayerMatchService);
        setScreen(battleScreen);
    }

    public void showMultiplayerDisconnected(String error) {
        MultiplayerSession.Snapshot snapshot = multiplayerSession.snapshot();
        setScreen(multiplayerDisconnectedScreen);
        multiplayerDisconnectedScreen.begin(
            snapshot.matchSetup(), snapshot.latestState(), error);
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

    public void reloadMultiplayerRoster() {
        try {
            multiplayerCharacterRepository.load();
            multiplayerRoster = multiplayerCharacterRepository.getAll().stream()
                .filter(character -> character.id != null && !character.id.isBlank())
                .map(character -> new MultiplayerFighter(character.id, character.name))
                .toList();
            multiplayerRosterError = multiplayerRoster.isEmpty()
                ? "The local fighter roster is empty." : null;
            boolean selectedStillExists = multiplayerRoster.stream()
                .anyMatch(fighter -> fighter.id().equals(selectedMultiplayerCharacterId));
            if (!selectedStillExists) {
                selectedMultiplayerCharacterId = DEFAULT_MULTIPLAYER_CHARACTER_ID;
            }
        } catch (IOException | RuntimeException failure) {
            multiplayerRosterError = "The local fighter roster could not be loaded.";
            System.err.println("Multiplayer roster load failed: "
                + failure.getClass().getSimpleName());
            if (multiplayerRoster.isEmpty()) {
                selectedMultiplayerCharacterId = DEFAULT_MULTIPLAYER_CHARACTER_ID;
            }
        }
    }

    public List<MultiplayerFighter> getMultiplayerRoster() {
        return multiplayerRoster;
    }

    public String getMultiplayerRosterError() {
        return multiplayerRosterError;
    }

    public String getMultiplayerConfigurationError() {
        return multiplayerConfigurationError;
    }

    public String getSelectedMultiplayerCharacterId() {
        return selectedMultiplayerCharacterId;
    }

    public void setSelectedMultiplayerCharacterId(String characterId) {
        if (characterId == null || characterId.isBlank()) {
            selectedMultiplayerCharacterId = DEFAULT_MULTIPLAYER_CHARACTER_ID;
        } else {
            selectedMultiplayerCharacterId = characterId;
        }
    }

    public String multiplayerFighterName(String characterId) {
        return multiplayerRoster.stream()
            .filter(fighter -> fighter.id().equals(characterId))
            .map(MultiplayerFighter::name)
            .findFirst()
            .orElse(DEFAULT_MULTIPLAYER_CHARACTER_ID.equals(characterId)
                ? "Canonical fighter" : "Local fighter");
    }

    /** Returns bundled visual metadata for an authoritative character ID. */
    public String multiplayerSpriteAsset(String characterId) {
        if (characterId == null) return null;
        return multiplayerCharacterRepository.getAll().stream()
            .filter(character -> characterId.equals(character.id))
            .map(character -> character.spriteAsset)
            .filter(spriteAsset -> spriteAsset != null && !spriteAsset.isBlank())
            .findFirst()
            .orElse(null);
    }

    /**
     * Start a battle between two chosen characters.
     *
     * The BattleController runs on a background thread so its blocking
     * promptBattlePlan() call does not stall the LibGDX render loop.
     * BattleScreen uses Gdx.app.postRunnable() to push state updates back
     * to the render thread safely.
     */
    public void startBattle(CharacterData playerData, CharacterData cpuData,
                            MoveRepository moveRepo, AbilityRepository abilityRepo,
                            TechniqueRepository techniqueRepo) {
        battleScreen.prepareLocal();
        battleScreen.setCombatantSprites(
            assets.characterBattleSprite(playerData.spriteAsset, false, assets.playerSprite),
            assets.characterBattleSprite(cpuData.spriteAsset, true, assets.enemySprite));
        setScreen(battleScreen);

        Thread battleThread = new Thread(() -> {
            try {
                Character player = playerData.toCharacter(moveRepo, abilityRepo, techniqueRepo);
                Character cpu    = cpuData.toCharacter(moveRepo, abilityRepo, techniqueRepo);
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

        battleScreen.setLocalBattleThread(battleThread);
        battleThread.setDaemon(true); // exits when the main window closes
        battleThread.start();
    }
}
