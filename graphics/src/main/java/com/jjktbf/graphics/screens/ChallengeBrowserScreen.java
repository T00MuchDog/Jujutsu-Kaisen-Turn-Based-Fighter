package com.jjktbf.graphics.screens;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.multiplayer.ChallengeService;
import com.jjktbf.graphics.multiplayer.GuestAccountService;
import com.jjktbf.multiplayer.protocol.ChallengeListResponse;
import com.jjktbf.multiplayer.protocol.ChallengeStatus;
import com.jjktbf.multiplayer.protocol.ChallengeSummary;
import com.jjktbf.multiplayer.protocol.ProtocolVersion;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Scrollable browser for compatible public challenges. */
public final class ChallengeBrowserScreen extends MultiplayerScreenBase {
    private static final DateTimeFormatter CREATED_TIME = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss z")
        .withZone(ZoneId.systemDefault());
    private static final Set<String> CLOSED_CHALLENGE_CODES = Set.of(
        "CHALLENGE_ALREADY_ACCEPTED",
        "CHALLENGE_CANCELLED",
        "CHALLENGE_EXPIRED",
        "CHALLENGE_NOT_OPEN",
        "CHALLENGE_NOT_FOUND"
    );

    private final GuestAccountService guestAccountService;
    private final ChallengeService challengeService;
    private final Label guestLabel;
    private final Label statusLabel;
    private final Table challengeRows;
    private final TextButton refreshButton;
    private final TextButton backButton;
    private final List<TextButton> joinButtons = new ArrayList<>();

    private boolean identityReady;
    private boolean loading;
    private boolean joining;
    private String localPlayerId;

    public ChallengeBrowserScreen(
        JJKGame game,
        AssetLoader assets,
        GuestAccountService guestAccountService,
        ChallengeService challengeService
    ) {
        super(game, assets);
        this.guestAccountService = guestAccountService;
        this.challengeService = challengeService;

        root.add(header("SEARCH CHALLENGES", "OPEN / COMPATIBLE")).growX().row();

        Table identityBar = new Table(assets.editorSkin);
        identityBar.setBackground(assets.editorSkin.getDrawable("battle-palette"));
        identityBar.pad(10f, 14f, 10f, 14f);
        guestLabel = new Label("GUEST: CONNECTING", assets.editorSkin, "white");
        statusLabel = wrappedLabel("", "small");
        identityBar.add(guestLabel).left().padRight(18f);
        identityBar.add(statusLabel).growX().left();
        root.add(identityBar).growX().padTop(12f).row();

        challengeRows = new Table(assets.editorSkin);
        challengeRows.top().left();
        ScrollPane challengeScroll = new ScrollPane(challengeRows, assets.editorSkin);
        challengeScroll.setFadeScrollBars(false);
        challengeScroll.setScrollingDisabled(true, false);
        root.add(challengeScroll).grow().padTop(10f).row();

        Table actions = new Table(assets.editorSkin);
        refreshButton = button("REFRESH", "primary", this::refresh);
        backButton = button("BACK", "default", this::requestBack);
        actions.add(refreshButton).growX().height(46f).pad(4f);
        actions.add(backButton).growX().height(46f).pad(4f);
        root.add(actions).growX().maxWidth(620f).padTop(8f).row();
    }

    @Override
    protected void onShown(long generation) {
        identityReady = false;
        loading = true;
        joining = false;
        localPlayerId = null;
        guestLabel.setText("GUEST: CONNECTING");
        setStatus(statusLabel, "Validating guest identity...", StatusTone.NORMAL);
        showCenteredMessage("Loading open challenges...");
        refreshActions();

        guestAccountService.ensureGuest().whenComplete((credentials, failure) ->
            postIfCurrent(generation, () -> {
                if (failure != null) {
                    loading = false;
                    logFailure("browser guest identity", failure);
                    guestLabel.setText("GUEST: UNAVAILABLE");
                    setStatus(statusLabel, userError(failure), StatusTone.ERROR);
                    showCenteredMessage("Could not load challenges. Select REFRESH to retry.");
                    refreshActions();
                    return;
                }
                identityReady = true;
                localPlayerId = credentials.identity().playerId();
                guestLabel.setText("GUEST: " + credentials.identity().displayName());
                requestChallenges(generation, true);
            }));
    }

    private void requestChallenges(long expectedGeneration, boolean showLoadingState) {
        if (!identityReady || joining || !isGenerationVisible(expectedGeneration)) {
            return;
        }
        loading = true;
        if (showLoadingState) {
            setStatus(statusLabel, "Loading open challenges...", StatusTone.NORMAL);
            showCenteredMessage("Loading open challenges...");
        }
        refreshActions();

        challengeService.listChallenges().whenComplete((response, failure) ->
            postIfCurrent(expectedGeneration, () -> {
                loading = false;
                if (failure != null) {
                    logFailure("list challenges", failure);
                    setStatus(statusLabel, userError(failure), StatusTone.ERROR);
                    showCenteredMessage("No challenge data is available. Select REFRESH to retry.");
                    refreshActions();
                    return;
                }
                showChallenges(response);
                refreshActions();
            }));
    }

    private void showChallenges(ChallengeListResponse response) {
        List<ChallengeSummary> compatible = response == null
            ? List.of()
            : response.challenges().stream()
                .filter(challenge -> challenge.status() == ChallengeStatus.OPEN)
                .filter(challenge -> ProtocolVersion.isCompatible(
                    challenge.gameVersion(), challenge.protocolVersion(), challenge.ruleset()))
                .filter(challenge -> localPlayerId == null
                    || !localPlayerId.equals(challenge.hostPlayerId()))
                .toList();

        challengeRows.clearChildren();
        joinButtons.clear();
        if (compatible.isEmpty()) {
            showCenteredMessage("NO OPEN CHALLENGES\n\nHost one or select REFRESH to check again.");
            setStatus(statusLabel, "No compatible public challenges are open.", StatusTone.NORMAL);
            return;
        }

        for (ChallengeSummary challenge : compatible) {
            addChallengeRow(challenge);
        }
        setStatus(statusLabel,
            compatible.size() + (compatible.size() == 1
                ? " open challenge" : " open challenges"),
            StatusTone.OK);
    }

    private void addChallengeRow(ChallengeSummary challenge) {
        Table card = new Table(assets.editorSkin);
        card.setBackground(assets.editorSkin.getDrawable("battle-card"));
        card.pad(14f);

        Table info = new Table(assets.editorSkin);
        Label host = new Label(challenge.hostDisplayName(), assets.editorSkin);
        Label fighter = wrappedLabel(
            "FIGHTER: " + challenge.hostCharacterName() + " ["
                + challenge.hostCharacterId() + "]",
            "small");
        Label metadata = wrappedLabel(
            "RULESET: " + challenge.ruleset()
                + "  |  CREATED: " + CREATED_TIME.format(Instant.ofEpochMilli(challenge.createdAt()))
                + "\nGAME: " + challenge.gameVersion()
                + "  |  PROTOCOL: " + challenge.protocolVersion(),
            "small");
        info.add(host).growX().left().padBottom(5f).row();
        info.add(fighter).growX().left().padBottom(4f).row();
        info.add(metadata).growX().left().row();

        TextButton join = button("JOIN", "primary", () -> joinChallenge(challenge));
        joinButtons.add(join);
        card.add(info).growX().left();
        card.add(join).width(118f).height(46f).right().padLeft(12f);
        challengeRows.add(card).growX().pad(5f).row();
    }

    private void joinChallenge(ChallengeSummary challenge) {
        if (joining || loading || !identityReady
            || challenge.status() != ChallengeStatus.OPEN
            || !ProtocolVersion.isCompatible(
                challenge.gameVersion(), challenge.protocolVersion(), challenge.ruleset())) {
            return;
        }
        long expectedGeneration = generation();
        joining = true;
        setStatus(statusLabel,
            "Joining " + challenge.hostDisplayName() + " with "
                + game.multiplayerFighterName(game.getSelectedMultiplayerCharacterId()) + "...",
            StatusTone.NORMAL);
        refreshActions();

        challengeService.acceptChallenge(
            challenge.challengeId(), game.getSelectedMultiplayerCharacterId()
        ).whenComplete((setup, failure) -> postIfCurrent(expectedGeneration, () -> {
            joining = false;
            if (failure != null) {
                logFailure("accept challenge", failure);
                String code = errorCode(failure);
                if (CLOSED_CHALLENGE_CODES.contains(code)) {
                    setStatus(statusLabel, closedChallengeMessage(code), StatusTone.ERROR);
                    requestChallenges(expectedGeneration, false);
                } else {
                    setStatus(statusLabel, userError(failure), StatusTone.ERROR);
                    refreshActions();
                }
                return;
            }
            game.showMultiplayerBattle(setup);
        }));
    }

    private String closedChallengeMessage(String code) {
        return switch (code) {
            case "CHALLENGE_ALREADY_ACCEPTED" ->
                "Another player already accepted that challenge. The list is being refreshed.";
            case "CHALLENGE_CANCELLED" ->
                "The host cancelled that challenge. The list is being refreshed.";
            case "CHALLENGE_EXPIRED" ->
                "That challenge expired. The list is being refreshed.";
            case "CHALLENGE_NOT_FOUND" ->
                "That challenge is no longer available. The list is being refreshed.";
            default -> "That challenge is no longer open. The list is being refreshed.";
        };
    }

    private void showCenteredMessage(String message) {
        challengeRows.clearChildren();
        joinButtons.clear();
        Label empty = wrappedLabel(message, "small");
        empty.setAlignment(com.badlogic.gdx.utils.Align.center);
        challengeRows.add(empty).growX().pad(34f).row();
    }

    private void refresh() {
        if (joining || loading) {
            return;
        }
        if (!identityReady) {
            onShown(generation());
        } else {
            requestChallenges(generation(), true);
        }
    }

    private void refreshActions() {
        boolean actionsEnabled = identityReady && !loading && !joining;
        refreshButton.setDisabled(loading || joining);
        backButton.setDisabled(joining);
        for (TextButton button : joinButtons) {
            button.setDisabled(!actionsEnabled);
        }
    }

    private void requestBack() {
        if (joining) {
            setStatus(statusLabel, "Please wait for the join request to finish.", StatusTone.NORMAL);
            return;
        }
        game.showMultiplayerMenu();
    }

    @Override
    protected void onBackRequested() {
        requestBack();
    }
}
