package com.jjktbf.graphics.screens;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.ui.editor.AxisLockedScrollPane;
import com.jjktbf.graphics.multiplayer.ChallengeService;
import com.jjktbf.graphics.multiplayer.GuestAccountService;
import com.jjktbf.graphics.multiplayer.MultiplayerMatchService;
import com.jjktbf.multiplayer.protocol.MatchSetup;
import com.jjktbf.multiplayer.protocol.MatchState;
import com.jjktbf.multiplayer.protocol.MatchStatus;
import com.jjktbf.multiplayer.protocol.PlayerSide;
import com.jjktbf.multiplayer.protocol.PlayerState;

/** Recovery screen shown after automatic socket reconnect attempts are exhausted. */
public final class MultiplayerDisconnectedScreen extends MultiplayerScreenBase {
    private final GuestAccountService guestAccountService;
    private final ChallengeService challengeService;
    private final MultiplayerMatchService matchService;
    private final Label matchLabel;
    private final Label opponentLabel;
    private final Label stateLabel;
    private final Label errorLabel;
    private final Label recoveryLabel;
    private final TextButton reconnectButton;
    private final TextButton backButton;

    private MatchSetup setup;
    private MatchState latestState;
    private boolean reconnecting;

    public MultiplayerDisconnectedScreen(
        JJKGame game,
        AssetLoader assets,
        GuestAccountService guestAccountService,
        ChallengeService challengeService,
        MultiplayerMatchService matchService
    ) {
        super(game, assets);
        this.guestAccountService = guestAccountService;
        this.challengeService = challengeService;
        this.matchService = matchService;

        root.add(header("CONNECTION LOST", "MATCH RECOVERY")).growX().row();

        Table card = new Table(assets.editorSkin);
        card.setBackground(assets.editorSkin.getDrawable("battle-card"));
        card.pad(20f);
        matchLabel = wrappedLabel("MATCH: --", "default");
        opponentLabel = wrappedLabel("OPPONENT: --", "default");
        stateLabel = wrappedLabel("LATEST STATE: --", "small");
        errorLabel = wrappedLabel("", "small");
        recoveryLabel = wrappedLabel(
            "Automatic retries were exhausted. RECONNECT fetches the latest match "
                + "setup before opening a new socket.",
            "small");

        card.add(matchLabel).growX().left().padBottom(8f).row();
        card.add(opponentLabel).growX().left().padBottom(8f).row();
        card.add(stateLabel).growX().left().padBottom(12f).row();
        card.add(errorLabel).growX().left().padBottom(12f).row();
        card.add(recoveryLabel).growX().left().padBottom(18f).row();

        reconnectButton = button("RECONNECT", "primary", this::reconnect);
        backButton = button("BACK", "default", this::back);
        card.add(reconnectButton).growX().height(46f).pad(4f).row();
        card.add(backButton).growX().height(46f).pad(4f).row();

        ScrollPane scroll = new AxisLockedScrollPane(card, assets.editorSkin);
        scroll.setFadeScrollBars(false);
        scroll.setScrollingDisabled(true, false);
        root.add(scroll).grow().maxWidth(780f).padTop(16f).row();
    }

    @Override
    protected void onShown(long generation) {
        setup = null;
        latestState = null;
        reconnecting = false;
        matchLabel.setText("MATCH: --");
        opponentLabel.setText("OPPONENT: --");
        stateLabel.setText("LATEST STATE: --");
        errorLabel.setText("");
        setStatus(recoveryLabel,
            "Waiting for recovery context...", StatusTone.NORMAL);
        refreshButtons();
    }

    public void begin(MatchSetup matchSetup, MatchState state, String error) {
        if (game.getScreen() != this || !isGenerationVisible(generation())) {
            throw new IllegalStateException("Disconnected screen must be shown before begin");
        }
        setup = matchSetup;
        latestState = state;
        reconnecting = false;
        updateDetails();
        setStatus(errorLabel,
            error == null || error.isBlank() ? "The match connection was interrupted." : error,
            StatusTone.ERROR);
        if (isTerminalContext()) {
            setStatus(recoveryLabel, terminalMessage(), StatusTone.NORMAL);
        } else {
            setStatus(recoveryLabel,
                "Automatic retries were exhausted. RECONNECT will fetch the latest "
                    + "authoritative setup and start a fresh connection.",
                StatusTone.NORMAL);
        }
        refreshButtons();
    }

    private void updateDetails() {
        if (setup == null) {
            matchLabel.setText("MATCH: UNAVAILABLE");
            opponentLabel.setText("OPPONENT: UNKNOWN");
            stateLabel.setText("LATEST STATE: NO RECOVERABLE MATCH SETUP");
            return;
        }
        matchLabel.setText("MATCH: " + setup.matchId());
        opponentLabel.setText("OPPONENT: " + setup.opponentDisplayName()
            + "  |  FIGHTER " + setup.opponentCharacterId());

        if (latestState == null) {
            stateLabel.setText("LATEST STATE: " + setup.status() + "  |  NO SNAPSHOT");
            return;
        }
        PlayerState opponent = latestState.player(opposite(setup.playerSide())).orElse(null);
        String opponentConnection = opponent == null
            ? "UNKNOWN" : (opponent.connected() ? "CONNECTED" : "DISCONNECTED");
        stateLabel.setText("LATEST STATE: " + latestState.status()
            + "  |  ROUND " + latestState.roundNumber()
            + "  |  " + latestState.phase()
            + "  |  VERSION " + latestState.stateVersion()
            + "\nOPPONENT CONNECTION: " + opponentConnection);
    }

    private void reconnect() {
        if (reconnecting || setup == null || setup.matchId() == null
            || setup.matchId().isBlank() || isTerminalContext()) {
            return;
        }
        long expectedGeneration = generation();
        reconnecting = true;
        setStatus(recoveryLabel, "Validating guest identity...", StatusTone.NORMAL);
        refreshButtons();

        guestAccountService.ensureGuest().whenComplete((credentials, identityFailure) ->
            postIfCurrent(expectedGeneration, () -> {
                if (identityFailure != null) {
                    reconnecting = false;
                    logFailure("recovery guest identity", identityFailure);
                    setStatus(errorLabel, userError(identityFailure), StatusTone.ERROR);
                    setStatus(recoveryLabel,
                        "The guest identity could not be validated. You can retry.",
                        StatusTone.NORMAL);
                    refreshButtons();
                    return;
                }
                fetchLatestSetup(expectedGeneration);
            }));
    }

    private void fetchLatestSetup(long expectedGeneration) {
        setStatus(recoveryLabel,
            "Fetching the latest authoritative match setup...", StatusTone.NORMAL);
        challengeService.getMatchSetup(setup.matchId()).whenComplete((freshSetup, failure) ->
            postIfCurrent(expectedGeneration, () -> {
                reconnecting = false;
                if (failure != null) {
                    logFailure("recover match setup", failure);
                    String code = errorCode(failure);
                    if ("MATCH_NOT_FOUND".equals(code)) {
                        setStatus(errorLabel,
                            "[MATCH_NOT_FOUND] This match is no longer available on the server.",
                            StatusTone.ERROR);
                        setStatus(recoveryLabel,
                            "The server no longer has recoverable state for this match.",
                            StatusTone.NORMAL);
                    } else {
                        setStatus(errorLabel, userError(failure), StatusTone.ERROR);
                        setStatus(recoveryLabel,
                            "The latest match state could not be fetched. You can retry.",
                            StatusTone.NORMAL);
                    }
                    refreshButtons();
                    return;
                }

                setup = freshSetup;
                latestState = freshSetup.state();
                updateDetails();
                if (isTerminalContext()) {
                    setStatus(errorLabel, "", StatusTone.NORMAL);
                    setStatus(recoveryLabel, terminalMessage(), StatusTone.NORMAL);
                    refreshButtons();
                    return;
                }
                game.showMultiplayerBattle(freshSetup);
            }));
    }

    private boolean isTerminalContext() {
        MatchStatus status = latestState == null
            ? (setup == null ? null : setup.status()) : latestState.status();
        return status == MatchStatus.ENDED || status == MatchStatus.ABANDONED;
    }

    private String terminalMessage() {
        if (latestState == null) {
            return "The match has already ended. Return to multiplayer.";
        }
        if (latestState.winnerSide() == null) {
            return "The match ended in a draw"
                + endReasonSuffix(latestState) + ".";
        }
        return latestState.winnerSide() == setup.playerSide()
            ? "The match ended with you as winner" + endReasonSuffix(latestState) + "."
            : "The match ended with the opponent as winner"
                + endReasonSuffix(latestState) + ".";
    }

    private static String endReasonSuffix(MatchState state) {
        return state.endReason() == null || state.endReason().isBlank()
            ? "" : " (" + state.endReason() + ")";
    }

    private void refreshButtons() {
        reconnectButton.setDisabled(reconnecting || setup == null || isTerminalContext());
        backButton.setDisabled(reconnecting);
    }

    private void back() {
        if (reconnecting) {
            setStatus(recoveryLabel,
                "Please wait for the recovery request to finish.", StatusTone.NORMAL);
            return;
        }
        matchService.disconnect();
        game.showMultiplayerMenu();
    }

    private static PlayerSide opposite(PlayerSide side) {
        return side == PlayerSide.PLAYER_ONE ? PlayerSide.PLAYER_TWO : PlayerSide.PLAYER_ONE;
    }

    @Override
    protected void onBackRequested() {
        back();
    }
}
