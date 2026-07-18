package com.jjktbf.graphics.screens;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Array;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.multiplayer.GuestAccountService;
import com.jjktbf.graphics.multiplayer.GuestCredentials;

import java.util.List;

/** Multiplayer entry point, guest identity state, and local fighter selection. */
public final class MultiplayerMenuScreen extends MultiplayerScreenBase {
    private final GuestAccountService guestAccountService;
    private final Label identityLabel;
    private final Label statusLabel;
    private final Label rosterStatusLabel;
    private final SelectBox<JJKGame.MultiplayerFighter> fighterSelect;
    private final TextButton hostButton;
    private final TextButton searchButton;
    private final TextButton retryButton;

    private boolean identityReady;
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
        panel.add(identityLabel).growX().left().padBottom(6f).row();

        statusLabel = wrappedLabel("", "small");
        panel.add(statusLabel).growX().left().padBottom(14f).row();

        Label fighterTitle = new Label("FIGHTER", assets.editorSkin, "white");
        panel.add(fighterTitle).growX().left().padBottom(5f).row();
        fighterSelect = new SelectBox<>(assets.editorSkin);
        fighterSelect.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                JJKGame.MultiplayerFighter selected = fighterSelect.getSelected();
                if (selected != null) {
                    game.setSelectedMultiplayerCharacterId(selected.id());
                }
            }
        });
        panel.add(fighterSelect).growX().height(44f).padBottom(5f).row();

        rosterStatusLabel = wrappedLabel("", "small");
        panel.add(rosterStatusLabel).growX().left().padBottom(14f).row();

        hostButton = button("HOST CHALLENGE", "primary", game::showHostChallenge);
        searchButton = button("SEARCH CHALLENGES", "primary", game::showChallengeBrowser);
        retryButton = button("RETRY CONNECTION", "default", this::retryIdentity);
        TextButton backButton = button("BACK", "default", game::showMainMenu);

        for (TextButton button : new TextButton[]{hostButton, searchButton, retryButton, backButton}) {
            panel.add(button).growX().height(46f).pad(4f).row();
        }

        root.add(panel).growX().maxWidth(660f).top().padTop(16f).expandY().row();
        setIdentityActions(false);
        retryButton.setVisible(false);
    }

    @Override
    protected void onShown(long generation) {
        identityReady = false;
        identityLoading = false;
        identityLabel.setText("GUEST: NOT CONNECTED");
        retryButton.setVisible(false);
        setIdentityActions(false);
        refreshRoster();
        String configurationError = game.getMultiplayerConfigurationError();
        if (configurationError != null) {
            identityLabel.setText("GUEST: UNAVAILABLE");
            setStatus(statusLabel, configurationError, StatusTone.ERROR);
            return;
        }
        requestIdentity(generation);
    }

    private void refreshRoster() {
        game.reloadMultiplayerRoster();
        List<JJKGame.MultiplayerFighter> roster = game.getMultiplayerRoster();
        Array<JJKGame.MultiplayerFighter> items = new Array<>();
        items.addAll(roster.toArray(JJKGame.MultiplayerFighter[]::new));
        if (items.isEmpty()) {
            items.add(new JJKGame.MultiplayerFighter(
                JJKGame.DEFAULT_MULTIPLAYER_CHARACTER_ID,
                "Canonical default"
            ));
        }
        fighterSelect.setItems(items);

        String selectedId = game.getSelectedMultiplayerCharacterId();
        for (JJKGame.MultiplayerFighter fighter : items) {
            if (fighter.id().equals(selectedId)) {
                fighterSelect.setSelected(fighter);
                break;
            }
        }

        String rosterError = game.getMultiplayerRosterError();
        if (rosterError == null) {
            setStatus(rosterStatusLabel,
                "Local roster only. The server validates canonical fighter IDs.",
                StatusTone.NORMAL);
        } else {
            setStatus(rosterStatusLabel,
                rosterError + " Using canonical fighter 000000.", StatusTone.ERROR);
        }
    }

    private void requestIdentity(long expectedGeneration) {
        if (identityLoading || !isGenerationVisible(expectedGeneration)) {
            return;
        }
        identityLoading = true;
        identityReady = false;
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
                    setIdentityActions(false);
                    return;
                }
                showIdentity(credentials);
            }));
    }

    private void showIdentity(GuestCredentials credentials) {
        identityReady = true;
        identityLabel.setText("GUEST: " + credentials.identity().displayName());
        setStatus(statusLabel, "Identity ready. Choose a fighter and challenge a player.",
            StatusTone.OK);
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
        fighterSelect.setDisabled(false);
    }

    @Override
    protected void onBackRequested() {
        game.showMainMenu();
    }
}
