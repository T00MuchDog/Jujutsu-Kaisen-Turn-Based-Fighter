package com.jjktbf.graphics.screens;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.audio.SoundCue;
import com.jjktbf.graphics.multiplayer.GuestAccountService;
import com.jjktbf.graphics.multiplayer.GuestCredentials;

/** Multiplayer entry point with host and public-challenge browser actions. */
public final class MultiplayerMenuScreen extends MultiplayerScreenBase {
    private final GuestAccountService guestAccountService;
    private final Label identityLabel;
    private final Label statusLabel;
    private final TextButton hostButton;
    private final TextButton searchButton;
    private final TextButton retryButton;

    private boolean identityLoading;

    public MultiplayerMenuScreen(
        JJKGame game,
        AssetLoader assets,
        GuestAccountService guestAccountService
    ) {
        super(game, assets);
        this.guestAccountService = guestAccountService;

        root.add(header("MULTIPLAYER", "PUBLIC CHALLENGES")).growX().row();

        Table panel = new Table(assets.editorSkin);
        panel.setBackground(assets.editorSkin.getDrawable("battle-palette"));
        panel.pad(18f);

        identityLabel = new Label("GUEST: NOT CONNECTED", assets.editorSkin, "white");
        statusLabel = wrappedLabel("", "small-white");
        panel.add(identityLabel).growX().left().padBottom(6f).row();
        panel.add(statusLabel).growX().left().padBottom(18f).row();

        hostButton = button(
            "HOST CHALLENGE",
            "primary",
            SoundCue.UI_CONFIRM,
            game::showMultiplayerHostFormatSelection
        );
        searchButton = button(
            "SEARCH CHALLENGES", "primary", game::showChallengeBrowser);
        retryButton = button("RETRY CONNECTION", "default", this::retryIdentity);
        TextButton backButton = button("BACK", "default", game::showMainMenu);

        for (TextButton button : new TextButton[]{hostButton, searchButton, retryButton, backButton}) {
            panel.add(button).growX().height(multiplayerButtonHeight()).pad(4f).row();
        }
        root.add(panel).growX().maxWidth(windowsLayout ? 990f : 660f)
            .top().padTop(16f).expandY().row();
        setIdentityActions(false);
        retryButton.setVisible(false);
    }

    @Override
    protected void onShown(long generation) {
        identityLoading = false;
        identityLabel.setText("GUEST: NOT CONNECTED");
        retryButton.setVisible(false);
        setIdentityActions(false);
        String configurationError = game.getMultiplayerConfigurationError();
        if (configurationError != null) {
            identityLabel.setText("GUEST: UNAVAILABLE");
            setStatus(statusLabel, configurationError, StatusTone.ERROR);
            return;
        }
        requestIdentity(generation);
    }

    private void requestIdentity(long expectedGeneration) {
        if (identityLoading || !isGenerationVisible(expectedGeneration)) {
            return;
        }
        identityLoading = true;
        retryButton.setVisible(false);
        setIdentityActions(false);
        setStatus(statusLabel, "Connecting to the multiplayer server...", StatusTone.NORMAL);

        guestAccountService.ensureGuest().whenComplete((credentials, failure) ->
            postIfCurrent(expectedGeneration, () -> {
                identityLoading = false;
                if (failure != null) {
                    logFailure("guest identity", failure);
                    identityLabel.setText("GUEST: UNAVAILABLE");
                    setStatus(statusLabel, userError(failure), StatusTone.ERROR);
                    retryButton.setVisible(true);
                    return;
                }
                showIdentity(credentials);
            }));
    }

    private void showIdentity(GuestCredentials credentials) {
        identityLabel.setText("GUEST: " + credentials.identity().displayName());
        setStatus(statusLabel,
            "Host a format or browse every open public challenge.", StatusTone.OK);
        retryButton.setVisible(false);
        setIdentityActions(true);
    }

    private void retryIdentity() {
        if (!identityLoading && game.getMultiplayerConfigurationError() == null) {
            requestIdentity(generation());
        }
    }

    private void setIdentityActions(boolean enabled) {
        hostButton.setDisabled(!enabled);
        searchButton.setDisabled(!enabled);
    }

    @Override
    protected void onBackRequested() {
        game.showMainMenu();
    }
}
