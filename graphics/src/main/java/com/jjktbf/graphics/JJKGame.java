package com.jjktbf.graphics;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.jjktbf.AppPaths;
import com.jjktbf.controller.BattleController;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleConfiguration;
import com.jjktbf.model.combat.BattleFormat;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleStatMode;
import com.jjktbf.model.combat.BattleTeamId;
import com.jjktbf.graphics.audio.GameAudio;
import com.jjktbf.graphics.audio.MusicTrack;
import com.jjktbf.graphics.launch.DesktopLaunchOptions;
import com.jjktbf.graphics.launch.DesktopPlatform;
import com.jjktbf.graphics.launch.GameLaunchMode;
import com.jjktbf.graphics.multiplayer.ChallengeService;
import com.jjktbf.graphics.multiplayer.ClientNetworkConfig;
import com.jjktbf.graphics.multiplayer.GuestAccountService;
import com.jjktbf.graphics.multiplayer.GuestCredentialsStore;
import com.jjktbf.graphics.multiplayer.HttpApiClient;
import com.jjktbf.graphics.multiplayer.MatchWebSocketClient;
import com.jjktbf.graphics.multiplayer.MultiplayerMatchService;
import com.jjktbf.graphics.multiplayer.MultiplayerSession;
import com.jjktbf.graphics.screens.BattleScreen;
import com.jjktbf.graphics.screens.BattleFormatScreen;
import com.jjktbf.graphics.screens.CharacterSelectScreen;
import com.jjktbf.graphics.screens.ChallengeBrowserScreen;
import com.jjktbf.graphics.screens.HostChallengeScreen;
import com.jjktbf.graphics.screens.MainMenuScreen;
import com.jjktbf.graphics.screens.MultiplayerDisconnectedScreen;
import com.jjktbf.graphics.screens.MultiplayerMenuScreen;
import com.jjktbf.graphics.screens.editors.AbilityEditorScreen;
import com.jjktbf.graphics.screens.editors.BattleUiEditorScreen;
import com.jjktbf.graphics.screens.editors.CharacterEditorScreen;
import com.jjktbf.graphics.screens.editors.MoveEditorScreen;
import com.jjktbf.graphics.screens.editors.TechniqueEditorScreen;
import com.jjktbf.graphics.ui.profile.BattleUiLayout;
import com.jjktbf.graphics.ui.profile.BattleUiLayoutStore;
import com.jjktbf.graphics.ui.profile.UiProfile;
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
 * Manages screen transitions and owns the shared visual and audio assets.
 * All screens receive a reference to this class so they can trigger navigation.
 *
 * Lifecycle:
 *   create()  — called once by LibGDX after the window and GL context exist.
 *               Loads all assets via AssetLoader, then shows the main menu.
 *   dispose() — called once when the window closes. Disposes all assets.
 */
public class JJKGame extends Game {

    public static final String DEFAULT_MULTIPLAYER_CHARACTER_ID = "000000";

    private final DesktopLaunchOptions launchOptions;
    private BattleUiLayoutStore battleUiLayoutStore;
    private BattleUiLayout battleUiLayout;
    private String battleUiLayoutLoadWarning;

    // Optional one-shot action run at the very end of create(), once assets and
    // screens are set up but before the first frame. Used by the desktop
    // launcher to enter native fullscreen (macOS) after the window exists.
    private Runnable onCreatedAction;

    /** Set an action to run once at the end of create(). Nullable. */
    public void setOnCreatedAction(Runnable action) {
        this.onCreatedAction = action;
    }

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
    private GameAudio audio;

    // Authoring-mode overlay: a small persistent "AUTHORING" badge drawn in the
    // top-right corner over every screen, so the developer can see at a glance
    // that saves are going to the source data/ files. Player builds never set
    // the flag, so this is a no-op (isAuthoringMode() is false) for releases.
    private SpriteBatch overlayBatch;
    private final GlyphLayout overlayLayout = new GlyphLayout();
    private static final Color AUTHORING_BADGE_COLOR = new Color(
        0xFF / 255f, 0xE3 / 255f, 0x2E / 255f, 1f); // #FFE32E, the hover yellow

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
    // N-fighter multiplayer selection: a chosen format plus an ordered roster
    // (one slot for 1v1, two for 2v2). Slot 0 is the legacy "primary" fighter
    // exposed via getSelectedMultiplayerCharacterId() for older callers.
    private com.jjktbf.model.combat.BattleFormat selectedMultiplayerFormat =
        com.jjktbf.model.combat.BattleFormat.ONE_V_ONE;
    private BattleStatMode selectedMultiplayerStatMode = BattleStatMode.STANDARD;
    private List<String> selectedMultiplayerCharacterIds =
        List.of(DEFAULT_MULTIPLAYER_CHARACTER_ID);

    // ── Screen instances ───────────────────────────────────────────────────────
    // The menu and editors are rebuilt on entry so inactive-stage pointer state
    // cannot leak across transitions. Other screens retain their reusable state.
    private MainMenuScreen        mainMenuScreen;
    private BattleFormatScreen    battleFormatScreen;
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
    private BattleUiEditorScreen battleUiEditorScreen;

    public JJKGame() {
        this(new DesktopLaunchOptions(
            DesktopPlatform.OTHER,
            GameLaunchMode.NORMAL_GAME,
            UiProfile.MAC,
            false,
            1280,
            720));
    }

    public JJKGame(DesktopLaunchOptions launchOptions) {
        this.launchOptions = java.util.Objects.requireNonNull(launchOptions, "launchOptions");
    }

    // -------------------------------------------------------------------------
    // LibGDX lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void create() {
        battleUiLayoutStore = new BattleUiLayoutStore();
        try {
            battleUiLayout = battleUiLayoutStore.load(launchOptions.uiProfile());
        } catch (IOException | IllegalArgumentException failure) {
            battleUiLayoutLoadWarning = "Could not load " + launchOptions.uiProfile()
                + ": " + failure.getMessage() + ". Factory defaults are active; save to repair it.";
            System.err.println("Warning: " + battleUiLayoutLoadWarning);
            battleUiLayout = BattleUiLayout.defaults(launchOptions.uiProfile());
        }
        assets = new AssetLoader();
        assets.load();
        audio = new GameAudio();
        if (AppPaths.isAuthoringMode()) {
            overlayBatch = new SpriteBatch();
        }

        if (launchOptions.battleUiEditor()) {
            battleUiEditorScreen = new BattleUiEditorScreen(
                this,
                assets,
                battleUiLayoutStore,
                launchOptions.uiProfile(),
                battleUiLayout,
                battleUiLayoutLoadWarning);
            setScreen(battleUiEditorScreen);
            runOnCreatedAction();
            return;
        }

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
        battleFormatScreen    = new BattleFormatScreen(this, assets);
        characterSelectScreen = new CharacterSelectScreen(this, assets);
        battleScreen          = new BattleScreen(this, assets, battleUiLayout);
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

        showScreen(mainMenuScreen, MusicTrack.MENU);

        // Run the launcher's one-shot startup action (e.g. entering native
        // fullscreen on macOS) now that everything — including the GLFW
        // window — is initialized.
        runOnCreatedAction();
    }

    private void runOnCreatedAction() {
        if (onCreatedAction == null) return;
        onCreatedAction.run();
        onCreatedAction = null;
    }

    /**
     * Render the active screen, then (in authoring mode only) draw a small
     * "AUTHORING" badge in the top-right corner over the finished frame. This
     * is the single draw-after-every-screen hook, so the badge persists across
     * menus, editors, battle, and character select regardless of how each
     * screen renders.
     */
    @Override
    public void render() {
        super.render();
        // The editor already shows authoring/profile diagnostics. Omitting this
        // badge also gives F1 a completely clean logical-canvas preview.
        if (launchOptions.battleUiEditor()) return;
        if (overlayBatch == null) return;
        int w = Gdx.graphics.getWidth();
        int h = Gdx.graphics.getHeight();
        float margin = 12f;
        BitmapFont font = assets.fontSmall;
        String label = "AUTHORING";
        overlayLayout.setText(font, label);
        float x = w - margin - overlayLayout.width;
        float y = h - margin; // baseline near the top (draw is baseline-anchored)
        overlayBatch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        overlayBatch.begin();
        font.setColor(AUTHORING_BADGE_COLOR);
        font.draw(overlayBatch, label, x, y);
        overlayBatch.end();
    }

    @Override
    public void pause() {
        super.pause();
        if (audio != null) audio.pause();
    }

    @Override
    public void resume() {
        super.resume();
        if (audio != null) audio.resume();
    }

    @Override
    public void dispose() {
        if (overlayBatch != null) {
            overlayBatch.dispose();
            overlayBatch = null;
        }
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
        if (battleFormatScreen != null) battleFormatScreen.dispose();
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
        if (battleUiEditorScreen != null) battleUiEditorScreen.dispose();
        if (audio != null) audio.dispose();
        if (assets != null) assets.dispose();
    }

    // -------------------------------------------------------------------------
    // Screen navigation
    // -------------------------------------------------------------------------

    public void showMainMenu() {
        mainMenuScreen.dispose();
        mainMenuScreen = new MainMenuScreen(this, assets);
        showScreen(mainMenuScreen, MusicTrack.MENU);
    }

    public void showCharacterSelect() {
        showCharacterSelect(BattleFormat.ONE_V_ONE);
    }

    public void showCharacterSelect(BattleFormat format) {
        showCharacterSelect(
            BattleConfiguration.standard(format), BattleController.ControlMode.PLAYER_VS_AI);
    }

    /** Opens the local-play format choice before character selection. */
    public void showSinglePlayerBattle() {
        showBattleFormatSelection(BattleController.ControlMode.PLAYER_VS_AI);
    }

    private void showCharacterSelect(
        BattleConfiguration configuration,
        BattleController.ControlMode controlMode
    ) {
        characterSelectScreen.prepare(
            configuration.format(), configuration.statMode(), controlMode);
        showScreen(characterSelectScreen, MusicTrack.MENU);
    }

    public void showAuthorBattle() {
        requireAuthorControlMode(BattleController.ControlMode.HUMAN_CONTROLS_BOTH_TEAMS);
        showBattleFormatSelection(BattleController.ControlMode.HUMAN_CONTROLS_BOTH_TEAMS);
    }

    private void showBattleFormatSelection(BattleController.ControlMode controlMode) {
        battleFormatScreen.prepare(configuration ->
            showCharacterSelect(configuration, controlMode));
        showScreen(battleFormatScreen, MusicTrack.MENU);
    }

    public void showMultiplayerMenu() {
        showScreen(multiplayerMenuScreen, MusicTrack.MENU);
    }

    public void showHostChallenge() {
        showScreen(hostChallengeScreen, MusicTrack.MENU);
    }

    public void showChallengeBrowser() {
        showScreen(challengeBrowserScreen, MusicTrack.MENU);
    }

    public void showMultiplayerBattle(MatchSetup setup) {
        multiplayerSession.setMatchSetup(setup);
        battleScreen.prepareMultiplayer(setup, multiplayerSession, multiplayerMatchService);
        showScreen(battleScreen, MusicTrack.BATTLE);
    }

    public void showMultiplayerDisconnected(String error) {
        MultiplayerSession.Snapshot snapshot = multiplayerSession.snapshot();
        showScreen(multiplayerDisconnectedScreen, MusicTrack.MENU);
        multiplayerDisconnectedScreen.begin(
            snapshot.matchSetup(), snapshot.latestState(), error);
    }

    public void showMoveEditor() {
        moveEditorScreen.dispose();
        moveEditorScreen = new MoveEditorScreen(this, assets);
        showScreen(moveEditorScreen, MusicTrack.MENU);
    }

    public void showCharacterEditor() {
        characterEditorScreen.dispose();
        characterEditorScreen = new CharacterEditorScreen(this, assets);
        showScreen(characterEditorScreen, MusicTrack.MENU);
    }

    public void showAbilityEditor() {
        abilityEditorScreen.dispose();
        abilityEditorScreen = new AbilityEditorScreen(this, assets);
        showScreen(abilityEditorScreen, MusicTrack.MENU);
    }

    public void showTechniqueEditor() {
        techniqueEditorScreen.dispose();
        techniqueEditorScreen = new TechniqueEditorScreen(this, assets);
        showScreen(techniqueEditorScreen, MusicTrack.MENU);
    }

    /** Shared audio entry point for screens and other presentation-layer code. */
    public GameAudio audio() {
        return audio;
    }

    public UiProfile activeUiProfile() {
        return launchOptions.uiProfile();
    }

    private void showScreen(Screen screen, MusicTrack musicTrack) {
        setScreen(screen);
        audio.playMusic(musicTrack);
    }

    public void reloadMultiplayerRoster() {
        try {
            multiplayerCharacterRepository.load();
            multiplayerRoster = multiplayerCharacterRepository.getAll().stream()
                .filter(character -> character.id != null && !character.id.isBlank())
                .filter(com.jjktbf.model.character.CharacterData::effectiveSelectable)
                .map(character -> new MultiplayerFighter(character.id, character.name))
                .toList();
            multiplayerRosterError = multiplayerRoster.isEmpty()
                ? "The local fighter roster is empty." : null;
            reconcileSelectedMultiplayerRoster();
        } catch (IOException | RuntimeException failure) {
            multiplayerRosterError = "The local fighter roster could not be loaded.";
            System.err.println("Multiplayer roster load failed: "
                + failure.getClass().getSimpleName());
            if (multiplayerRoster.isEmpty()) {
                selectedMultiplayerCharacterIds = List.of(DEFAULT_MULTIPLAYER_CHARACTER_ID);
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

    public com.jjktbf.model.combat.BattleFormat getSelectedMultiplayerFormat() {
        return selectedMultiplayerFormat;
    }

    public BattleStatMode getSelectedMultiplayerStatMode() {
        return selectedMultiplayerStatMode;
    }

    public void setSelectedMultiplayerStatMode(BattleStatMode statMode) {
        selectedMultiplayerStatMode = statMode == null
            ? BattleStatMode.STANDARD : statMode;
    }

    public void setSelectedMultiplayerFormat(
        com.jjktbf.model.combat.BattleFormat format
    ) {
        selectedMultiplayerFormat = format == null
            ? com.jjktbf.model.combat.BattleFormat.ONE_V_ONE : format;
        // Grow/shrink the selection to match the new format, resetting any slot
        // that pointed at a fighter no longer in the roster.
        reconcileSelectedMultiplayerRoster();
    }

    public List<String> getSelectedMultiplayerCharacterIds() {
        return selectedMultiplayerCharacterIds;
    }

    /**
     * Set the ordered multiplayer fighter roster. Exactly
     * {@link com.jjktbf.model.combat.BattleFormat#fightersPerSide()} ids are
     * kept; blanks and duplicates are rejected by the server at challenge time,
     * so the client only enforces length here.
     */
    public void setSelectedMultiplayerCharacterIds(List<String> characterIds) {
        int slots = selectedMultiplayerFormat.fightersPerSide();
        if (characterIds == null || characterIds.isEmpty()) {
            selectedMultiplayerCharacterIds = List.of(DEFAULT_MULTIPLAYER_CHARACTER_ID);
            return;
        }
        java.util.List<String> copy = new java.util.ArrayList<>(slots);
        for (int index = 0; index < slots; index++) {
            String id = index < characterIds.size() ? characterIds.get(index) : null;
            copy.add(id == null || id.isBlank() ? DEFAULT_MULTIPLAYER_CHARACTER_ID : id);
        }
        selectedMultiplayerCharacterIds = List.copyOf(copy);
    }

    /** Legacy singular view: slot 0 (the primary fighter). */
    public String getSelectedMultiplayerCharacterId() {
        return selectedMultiplayerCharacterIds.isEmpty()
            ? DEFAULT_MULTIPLAYER_CHARACTER_ID : selectedMultiplayerCharacterIds.get(0);
    }

    /** Legacy singular setter: writes slot 0 and grows the list to one entry. */
    public void setSelectedMultiplayerCharacterId(String characterId) {
        if (characterId == null || characterId.isBlank()) {
            characterId = DEFAULT_MULTIPLAYER_CHARACTER_ID;
        }
        java.util.List<String> ids = new java.util.ArrayList<>(
            selectedMultiplayerCharacterIds);
        if (ids.isEmpty()) {
            ids.add(characterId);
        } else {
            ids.set(0, characterId);
        }
        selectedMultiplayerCharacterIds = List.copyOf(ids);
    }

    /**
     * Reset any selected slot pointing at a fighter no longer in the roster to
     * the default, and ensure the slot count matches the chosen format.
     */
    private void reconcileSelectedMultiplayerRoster() {
        java.util.Set<String> available = multiplayerRoster.stream()
            .map(MultiplayerFighter::id)
            .collect(java.util.stream.Collectors.toSet());
        int slots = selectedMultiplayerFormat.fightersPerSide();
        java.util.List<String> reconciled = new java.util.ArrayList<>(slots);
        for (int index = 0; index < slots; index++) {
            String current = index < selectedMultiplayerCharacterIds.size()
                ? selectedMultiplayerCharacterIds.get(index) : null;
            reconciled.add(current != null && available.contains(current)
                ? current : DEFAULT_MULTIPLAYER_CHARACTER_ID);
        }
        selectedMultiplayerCharacterIds = List.copyOf(reconciled);
    }

    public String multiplayerFighterName(String characterId) {
        return multiplayerRoster.stream()
            .filter(fighter -> fighter.id().equals(characterId))
            .map(MultiplayerFighter::name)
            .findFirst()
            .orElse(DEFAULT_MULTIPLAYER_CHARACTER_ID.equals(characterId)
                ? "Canonical fighter" : "Local fighter");
    }

    /** Returns bundled cursed-technique metadata for an authoritative character ID. */
    public String multiplayerCursedTechnique(String characterId) {
        if (characterId == null || multiplayerCharacterRepository == null) return null;
        return multiplayerCharacterRepository.findById(characterId)
            .map(character -> character.innateTechniqueName)
            .filter(technique -> technique != null && !technique.isBlank())
            .orElse(null);
    }

    /** Returns bundled visual metadata for an authoritative character ID. */
    public String multiplayerSpriteAsset(String characterId) {
        if (characterId == null || multiplayerCharacterRepository == null) return null;
        return multiplayerCharacterRepository.getAll().stream()
            .filter(character -> characterId.equals(character.id))
            .map(character -> character.spriteAsset)
            .filter(spriteAsset -> spriteAsset != null && !spriteAsset.isBlank())
            .findFirst()
            .orElse(null);
    }

    /**
     * Returns bundled visual metadata for an ordered authoritative roster, in
     * the same order as the input ids. Unknown ids yield a {@code null} entry
     * (callers fall back to the default sprite).
     */
    public java.util.List<String> multiplayerSpriteAssets(java.util.List<String> characterIds) {
        if (characterIds == null) return java.util.List.of();
        return characterIds.stream()
            .map(this::multiplayerSpriteAsset)
            .toList();
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
        startBattle(playerData, cpuData, moveRepo, abilityRepo, techniqueRepo,
            BattleController.ControlMode.PLAYER_VS_AI, BattleStatMode.STANDARD);
    }

    public void startBattle(CharacterData playerData, CharacterData cpuData,
                            MoveRepository moveRepo, AbilityRepository abilityRepo,
                            TechniqueRepository techniqueRepo,
                            BattleController.ControlMode controlMode) {
        startBattle(playerData, cpuData, moveRepo, abilityRepo, techniqueRepo,
            controlMode, BattleStatMode.STANDARD);
    }

    public void startBattle(CharacterData playerData, CharacterData cpuData,
                            MoveRepository moveRepo, AbilityRepository abilityRepo,
                            TechniqueRepository techniqueRepo,
                            BattleController.ControlMode controlMode,
                            BattleStatMode statMode) {
        requireAuthorControlMode(controlMode);
        battleScreen.prepareLocal();
        battleScreen.setCombatantSprites(
            assets.characterBattleSprite(playerData.spriteAsset, false, assets.playerSprite),
            assets.characterBattleSprite(cpuData.spriteAsset, true, assets.enemySprite));
        showScreen(battleScreen, MusicTrack.BATTLE);

        Thread battleThread = new Thread(() -> {
            try {
                Character player = playerData.toCharacter(moveRepo, abilityRepo, techniqueRepo);
                Character cpu    = cpuData.toCharacter(moveRepo, abilityRepo, techniqueRepo);
                BattleController controller = new BattleController(
                    battleScreen,
                    characterId -> multiplayerCharacterRepository.findById(characterId)
                        .map(data -> data.toCharacter(moveRepo, abilityRepo, techniqueRepo)),
                    controlMode
                );
                controller.runBattle(player, cpu, statMode);
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

    /**
     * Start a local team battle (e.g. 2v2). Builds one {@link BattleCombatant}
     * per selected fighter per side, assembles a team {@link BattleState} via
     * {@link BattleState#teamOfFighters}, and runs it through the multi-fighter
     * loop ({@link BattleController#runTeamBattle}). The engine, AI, and team
     * planning UI already handle N fighters per side; this is the single-player
     * setup entry point.
     */
    public void startTeamBattle(
        java.util.List<CharacterData> playerTeam,
        java.util.List<CharacterData> cpuTeam,
        MoveRepository moveRepo, AbilityRepository abilityRepo,
        TechniqueRepository techniqueRepo
    ) {
        startTeamBattle(playerTeam, cpuTeam, moveRepo, abilityRepo, techniqueRepo,
            BattleController.ControlMode.PLAYER_VS_AI, BattleStatMode.STANDARD);
    }

    public void startTeamBattle(
        java.util.List<CharacterData> playerTeam,
        java.util.List<CharacterData> cpuTeam,
        MoveRepository moveRepo, AbilityRepository abilityRepo,
        TechniqueRepository techniqueRepo,
        BattleController.ControlMode controlMode
    ) {
        startTeamBattle(playerTeam, cpuTeam, moveRepo, abilityRepo, techniqueRepo,
            controlMode, BattleStatMode.STANDARD);
    }

    public void startTeamBattle(
        java.util.List<CharacterData> playerTeam,
        java.util.List<CharacterData> cpuTeam,
        MoveRepository moveRepo, AbilityRepository abilityRepo,
        TechniqueRepository techniqueRepo,
        BattleController.ControlMode controlMode,
        BattleStatMode statMode
    ) {
        requireAuthorControlMode(controlMode);
        battleScreen.prepareLocal();
        // Set sprites per side (front fighter per side for now; full 4-fighter
        // rendering is handled by BattleScreen once the state arrives).
        battleScreen.setTeamSprites(
            playerTeam.stream()
                .map(d -> assets.characterBattleSprite(d.spriteAsset, false, assets.playerSprite))
                .toList(),
            cpuTeam.stream()
                .map(d -> assets.characterBattleSprite(d.spriteAsset, true, assets.enemySprite))
                .toList());
        showScreen(battleScreen, MusicTrack.BATTLE);

        Thread battleThread = new Thread(() -> {
            try {
                java.util.List<BattleCombatant> playerFighters = playerTeam.stream()
                    .map(d -> d.toCharacter(moveRepo, abilityRepo, techniqueRepo))
                    .map(c -> new BattleCombatant(c, c.getAbilities(), statMode))
                    .toList();
                java.util.List<BattleCombatant> cpuFighters = cpuTeam.stream()
                    .map(d -> d.toCharacter(moveRepo, abilityRepo, techniqueRepo))
                    .map(c -> new BattleCombatant(c, c.getAbilities(), statMode))
                    .toList();
                BattleState state = new BattleState(
                    BattleState.teamOfFighters(BattleTeamId.PLAYER, playerFighters),
                    BattleState.teamOfFighters(BattleTeamId.ENEMY, cpuFighters));
                BattleController controller = new BattleController(
                    battleScreen,
                    characterId -> multiplayerCharacterRepository.findById(characterId)
                        .map(data -> data.toCharacter(moveRepo, abilityRepo, techniqueRepo)),
                    controlMode
                );
                controller.runTeamBattle(state);
            } catch (Throwable t) {
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
        battleThread.setDaemon(true);
        battleThread.start();
    }

    private static void requireAuthorControlMode(BattleController.ControlMode controlMode) {
        if (controlMode == BattleController.ControlMode.HUMAN_CONTROLS_BOTH_TEAMS
            && !AppPaths.isAuthoringMode()) {
            throw new IllegalStateException("Control-both-teams mode requires authoring mode");
        }
    }
}
