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
import com.jjktbf.graphics.audio.SoundCue;
import com.jjktbf.graphics.multiplayer.GuestAccountService;
import com.jjktbf.graphics.multiplayer.GuestCredentials;
import com.jjktbf.graphics.ui.DynamicSelectBox;
import com.jjktbf.model.combat.BattleFormat;

import java.util.ArrayList;
import java.util.List;

/** Multiplayer entry point, guest identity state, and local fighter selection. */
public final class MultiplayerMenuScreen extends MultiplayerScreenBase {
    private final GuestAccountService guestAccountService;
    private final Label identityLabel;
    private final Label statusLabel;
    private final Label rosterStatusLabel;
    private final SelectBox<BattleFormat> formatSelect;
    private final SelectBox<JJKGame.MultiplayerFighter> fighterOneSelect;
    private final SelectBox<JJKGame.MultiplayerFighter> fighterTwoSelect;
    private final Label fighterTwoLabel;
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

        // On dark-blue battle-palette: use small-white so status tints stay
        // legible (TEXT_DARK base × tint multiplies down to near-black).
        statusLabel = wrappedLabel("", "small-white");
        panel.add(statusLabel).growX().left().padBottom(14f).row();

        Label formatTitle = new Label("FORMAT", assets.editorSkin, "white");
        panel.add(formatTitle).growX().left().padBottom(5f).row();
        formatSelect = new DynamicSelectBox<>(assets.editorSkin);
        formatSelect.setItems(BattleFormat.ONE_V_ONE, BattleFormat.TWO_V_TWO);
        formatSelect.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                BattleFormat selected = formatSelect.getSelected();
                if (selected != null) {
                    game.setSelectedMultiplayerFormat(selected);
                    updateFighterTwoVisibility();
                    syncSelectionToGame();
                }
            }
        });
        panel.add(formatSelect).growX().height(44f).padBottom(12f).row();

        Label fighterOneLabel = new Label("FIGHTER 1", assets.editorSkin, "white");
        panel.add(fighterOneLabel).growX().left().padBottom(5f).row();
        fighterOneSelect = new DynamicSelectBox<>(assets.editorSkin);
        fighterOneSelect.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                syncSelectionToGame();
                refreshDuplicateGuard();
            }
        });
        panel.add(fighterOneSelect).growX().height(44f).padBottom(8f).row();

        fighterTwoLabel = new Label("FIGHTER 2", assets.editorSkin, "white");
        panel.add(fighterTwoLabel).growX().left().padBottom(5f).row();
        fighterTwoSelect = new DynamicSelectBox<>(assets.editorSkin);
        fighterTwoSelect.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                syncSelectionToGame();
                refreshDuplicateGuard();
            }
        });
        panel.add(fighterTwoSelect).growX().height(44f).padBottom(5f).row();

        rosterStatusLabel = wrappedLabel("", "small-white");
        panel.add(rosterStatusLabel).growX().left().padBottom(14f).row();

        hostButton = button("HOST CHALLENGE", "primary", SoundCue.UI_CONFIRM, game::showHostChallenge);
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
        Array<JJKGame.MultiplayerFighter> items = new Array<>();
        items.addAll(game.getMultiplayerRoster().toArray(JJKGame.MultiplayerFighter[]::new));
        if (items.isEmpty()) {
            items.add(new JJKGame.MultiplayerFighter(
                JJKGame.DEFAULT_MULTIPLAYER_CHARACTER_ID,
                "Canonical default"
            ));
        }
        fighterOneSelect.setItems(items);
        fighterTwoSelect.setItems(new Array<>(items));

        List<String> selectedIds = game.getSelectedMultiplayerCharacterIds();
        String slotZero = selectedIds.isEmpty()
            ? JJKGame.DEFAULT_MULTIPLAYER_CHARACTER_ID : selectedIds.get(0);
        String slotOne = selectedIds.size() > 1 ? selectedIds.get(1)
            : JJKGame.DEFAULT_MULTIPLAYER_CHARACTER_ID;
        selectFighter(fighterOneSelect, slotZero);
        selectFighter(fighterTwoSelect, slotOne);

        BattleFormat format = game.getSelectedMultiplayerFormat();
        formatSelect.setSelected(format == null ? BattleFormat.ONE_V_ONE : format);
        updateFighterTwoVisibility();

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

    private void selectFighter(
        SelectBox<JJKGame.MultiplayerFighter> box,
        String characterId
    ) {
        for (JJKGame.MultiplayerFighter fighter : box.getItems()) {
            if (fighter.id().equals(characterId)) {
                box.setSelected(fighter);
                return;
            }
        }
    }

    private void updateFighterTwoVisibility() {
        boolean twoFighters = formatSelect.getSelected() == BattleFormat.TWO_V_TWO;
        fighterTwoLabel.setVisible(twoFighters);
        fighterTwoSelect.setVisible(twoFighters);
    }

    /**
     * Write the current format + dropdown picks into game state. Ensures the
     * roster has exactly the format's slot count and that slot 1 is only set in
     * 2v2.
     */
    private void syncSelectionToGame() {
        BattleFormat format = formatSelect.getSelected();
        if (format == null) {
            return;
        }
        game.setSelectedMultiplayerFormat(format);
        List<String> ids = new ArrayList<>(format.fightersPerSide());
        ids.add(fighterId(fighterOneSelect));
        if (format == BattleFormat.TWO_V_TWO) {
            ids.add(fighterId(fighterTwoSelect));
        }
        game.setSelectedMultiplayerCharacterIds(ids);
    }

    private static String fighterId(SelectBox<JJKGame.MultiplayerFighter> box) {
        JJKGame.MultiplayerFighter selected = box.getSelected();
        return selected == null ? JJKGame.DEFAULT_MULTIPLAYER_CHARACTER_ID : selected.id();
    }

    /**
     * Surface a warning when both fighter slots reference the same canonical id;
     * the server will reject the roster, so the player is warned up front. Does
     * not disable the buttons (the host can still navigate), only nudges.
     */
    private void refreshDuplicateGuard() {
        if (formatSelect.getSelected() != BattleFormat.TWO_V_TWO) {
            return;
        }
        String one = fighterId(fighterOneSelect);
        String two = fighterId(fighterTwoSelect);
        if (!one.equals(JJKGame.DEFAULT_MULTIPLAYER_CHARACTER_ID)
            && one.equals(two)) {
            setStatus(rosterStatusLabel,
                "Pick two different fighters for 2v2.", StatusTone.ERROR);
        } else {
            setStatus(rosterStatusLabel,
                "Local roster only. The server validates canonical fighter IDs.",
                StatusTone.NORMAL);
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
        setStatus(statusLabel, "Identity ready. Choose a format and fighter(s), then challenge a player.",
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
        formatSelect.setDisabled(false);
        fighterOneSelect.setDisabled(false);
        fighterTwoSelect.setDisabled(false);
    }

    @Override
    protected void onBackRequested() {
        game.showMainMenu();
    }
}
