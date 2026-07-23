package com.jjktbf.graphics.screens;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.ui.editor.AxisLockedScrollPane;
import com.jjktbf.graphics.multiplayer.ChallengeService;
import com.jjktbf.graphics.multiplayer.GuestAccountService;
import com.jjktbf.multiplayer.protocol.ChallengeStatus;
import com.jjktbf.multiplayer.protocol.ChallengeSummary;
import com.jjktbf.multiplayer.protocol.MatchSetup;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Creates a public challenge and lets its host approve one pending join request. */
public final class HostChallengeScreen extends MultiplayerScreenBase {
    private final GuestAccountService guestAccountService;
    private final ChallengeService challengeService;
    private final ScheduledExecutorService pollExecutor;
    private final AtomicBoolean pollInFlight = new AtomicBoolean();

    private final Label guestLabel;
    private final Label fighterLabel;
    private final Label challengeLabel;
    private final Label challengeStatusLabel;
    private final Label messageLabel;
    private final Table requestActions;
    private final TextButton acceptButton;
    private final TextButton rejectButton;
    private final TextButton cancelButton;
    private final TextButton retryButton;
    private final TextButton backButton;

    private ScheduledFuture<?> pollTask;
    private volatile long pollCycle;
    private ChallengeSummary challenge;
    private String selectedCharacterId;
    private boolean ensuringGuest;
    private boolean creating;
    private boolean cancelling;
    private boolean accepting;
    private boolean rejecting;
    private boolean fetchingMatch;

    public HostChallengeScreen(
        JJKGame game,
        AssetLoader assets,
        GuestAccountService guestAccountService,
        ChallengeService challengeService
    ) {
        super(game, assets);
        this.guestAccountService = guestAccountService;
        this.challengeService = challengeService;
        this.pollExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "jjktbf-challenge-poll");
            thread.setDaemon(true);
            return thread;
        });

        root.add(header("HOST CHALLENGE", "PUBLIC / STANDARD")).growX().row();

        Table card = new Table(assets.editorSkin);
        card.setBackground(assets.editorSkin.getDrawable("battle-card"));
        card.pad(20f);
        guestLabel = new Label("GUEST: CONNECTING", assets.editorSkin);
        fighterLabel = new Label("FIGHTER: --", assets.editorSkin);
        challengeLabel = wrappedLabel("CHALLENGE: --", "small");
        challengeStatusLabel = new Label("STATUS: PREPARING", assets.editorSkin);
        messageLabel = wrappedLabel("Preparing your public challenge...", "small");

        card.add(guestLabel).growX().left().padBottom(7f).row();
        card.add(fighterLabel).growX().left().padBottom(12f).row();
        card.add(challengeLabel).growX().left().padBottom(7f).row();
        card.add(challengeStatusLabel).growX().left().padBottom(14f).row();
        card.add(messageLabel).growX().left().padBottom(18f).row();

        requestActions = new Table(assets.editorSkin);
        acceptButton = button("YES", "primary", this::acceptRequest);
        rejectButton = button("NO", "default", this::rejectRequest);
        requestActions.add(acceptButton).growX().height(46f).pad(4f);
        requestActions.add(rejectButton).growX().height(46f).pad(4f);
        requestActions.setVisible(false);
        card.add(requestActions).growX().row();

        cancelButton = button("CANCEL CHALLENGE", "primary", this::cancelChallenge);
        retryButton = button("RETRY", "default", this::retry);
        backButton = button("BACK", "default", this::requestBack);
        card.add(cancelButton).growX().height(46f).pad(4f).row();
        card.add(retryButton).growX().height(46f).pad(4f).row();
        card.add(backButton).growX().height(46f).pad(4f).row();

        ScrollPane scroll = new AxisLockedScrollPane(card, assets.editorSkin);
        scroll.setFadeScrollBars(false);
        scroll.setScrollingDisabled(true, false);
        root.add(scroll).grow().maxWidth(760f).padTop(16f).row();
    }

    @Override
    protected void onShown(long generation) {
        stopPolling();
        challenge = null;
        selectedCharacterId = game.getSelectedMultiplayerCharacterId();
        ensuringGuest = true;
        creating = false;
        cancelling = false;
        accepting = false;
        rejecting = false;
        fetchingMatch = false;

        guestLabel.setText("GUEST: CONNECTING");
        fighterLabel.setText("FIGHTER: " + game.multiplayerFighterName(selectedCharacterId)
            + " [" + selectedCharacterId + "]");
        challengeLabel.setText("CHALLENGE: --");
        challengeStatusLabel.setText("STATUS: PREPARING");
        setStatus(messageLabel, "Validating guest identity...", StatusTone.NORMAL);
        requestActions.setVisible(false);
        retryButton.setVisible(false);
        refreshButtons();

        guestAccountService.ensureGuest().whenComplete((credentials, failure) ->
            postIfCurrent(generation, () -> {
                ensuringGuest = false;
                if (failure != null) {
                    logFailure("host guest identity", failure);
                    guestLabel.setText("GUEST: UNAVAILABLE");
                    challengeStatusLabel.setText("STATUS: CONNECTION ERROR");
                    setStatus(messageLabel, userError(failure), StatusTone.ERROR);
                    retryButton.setVisible(true);
                    refreshButtons();
                    return;
                }
                guestLabel.setText("GUEST: " + credentials.identity().displayName());
                ChallengeSummary recoverable = challengeService.currentChallenge().orElse(null);
                if (recoverable != null
                    && recoverable.status() == ChallengeStatus.ACCEPTED
                    && credentials.identity().playerId().equals(recoverable.hostPlayerId())) {
                    showChallenge(recoverable);
                    fetchMatch(generation, recoverable);
                    return;
                }
                if (recoverable != null
                    && recoverable.status() == ChallengeStatus.OPEN
                    && credentials.identity().playerId().equals(recoverable.hostPlayerId())) {
                    showChallenge(recoverable);
                    startPolling(generation, recoverable.challengeId());
                    return;
                }
                recoverHostedChallenge(generation);
            }));
    }

    private void recoverHostedChallenge(long expectedGeneration) {
        challengeService.getHostedChallenge().whenComplete((recoverable, failure) ->
            postIfCurrent(expectedGeneration, () -> {
                if (failure != null) {
                    if ("CHALLENGE_NOT_FOUND".equals(errorCode(failure))) {
                        createChallenge(expectedGeneration);
                        return;
                    }
                    logFailure("recover hosted challenge", failure);
                    challengeStatusLabel.setText("STATUS: CONNECTION ERROR");
                    setStatus(messageLabel, userError(failure), StatusTone.ERROR);
                    retryButton.setVisible(true);
                    refreshButtons();
                    return;
                }
                showChallenge(recoverable);
                if (recoverable.status() == ChallengeStatus.ACCEPTED) {
                    fetchMatch(expectedGeneration, recoverable);
                } else {
                    startPolling(expectedGeneration, recoverable.challengeId());
                }
            }));
    }

    private void createChallenge(long expectedGeneration) {
        if (creating || !isGenerationVisible(expectedGeneration)) {
            return;
        }
        creating = true;
        retryButton.setVisible(false);
        challengeStatusLabel.setText("STATUS: CREATING");
        setStatus(messageLabel, "Publishing challenge to the server...", StatusTone.NORMAL);
        refreshButtons();

        challengeService.createChallenge(selectedCharacterId).whenComplete((created, failure) ->
            postIfCurrentOrElse(expectedGeneration, () -> {
                creating = false;
                if (failure != null) {
                    logFailure("create challenge", failure);
                    challengeStatusLabel.setText("STATUS: SERVER ERROR");
                    setStatus(messageLabel, userError(failure), StatusTone.ERROR);
                    retryButton.setVisible(true);
                    refreshButtons();
                    return;
                }
                challenge = created;
                showChallenge(created);
                startPolling(expectedGeneration, created.challengeId());
            }, () -> {
                if (failure == null && created != null
                    && created.status() == ChallengeStatus.OPEN
                    && game.getScreen() != this) {
                    challengeService.cancelChallenge(created.challengeId())
                        .exceptionally(cleanupFailure -> null);
                }
            }));
    }

    private void showChallenge(ChallengeSummary current) {
        challenge = current;
        challengeService.rememberChallenge(current);
        selectedCharacterId = current.hostCharacterId();
        fighterLabel.setText("FIGHTER: " + current.hostCharacterName()
            + " [" + current.hostCharacterId() + "]");
        challengeLabel.setText("CHALLENGE: " + current.challengeId());
        challengeStatusLabel.setText("STATUS: " + current.status());
        switch (current.status()) {
            case OPEN -> {
                if (hasPendingRequest(current)) {
                    challengeStatusLabel.setText("STATUS: JOIN REQUEST");
                    setStatus(messageLabel,
                        "A player wants to join this challenge. Accept this request?",
                        StatusTone.OK);
                } else {
                    setStatus(messageLabel,
                        "Waiting for another player. This screen checks the server every second.",
                        StatusTone.NORMAL);
                }
            }
            case ACCEPTED -> setStatus(messageLabel,
                "Challenge accepted. Loading the authoritative match...", StatusTone.OK);
            case CANCELLED -> setStatus(messageLabel,
                "This challenge was cancelled.", StatusTone.NORMAL);
            case EXPIRED -> setStatus(messageLabel,
                "This challenge expired before another player joined.", StatusTone.ERROR);
        }
        requestActions.setVisible(hasPendingRequest(current));
        refreshButtons();
    }

    private void startPolling(long expectedGeneration, String challengeId) {
        stopPolling();
        long cycle = ++pollCycle;
        pollTask = pollExecutor.scheduleAtFixedRate(() -> {
            if (pollCycle != cycle || !isGenerationVisible(expectedGeneration)
                || !pollInFlight.compareAndSet(false, true)) {
                return;
            }
            challengeService.getChallenge(challengeId).whenComplete((current, failure) -> {
                if (pollCycle == cycle) {
                    pollInFlight.set(false);
                }
                postIfCurrent(expectedGeneration, () -> {
                    if (pollCycle != cycle) {
                        return;
                    }
                    if (failure != null) {
                        logFailure("poll challenge", failure);
                        setStatus(messageLabel,
                            userError(failure) + " Retrying automatically...", StatusTone.ERROR);
                        return;
                    }
                    showChallenge(current);
                    if (current.status() == ChallengeStatus.ACCEPTED) {
                        fetchMatch(expectedGeneration, current);
                    } else if (current.status() != ChallengeStatus.OPEN) {
                        stopPolling();
                    }
                });
            });
        }, 1L, 1L, TimeUnit.SECONDS);
    }

    private void acceptRequest() {
        if (!hasPendingRequest(challenge) || accepting || rejecting || cancelling
            || fetchingMatch) {
            return;
        }
        long expectedGeneration = generation();
        accepting = true;
        stopPolling();
        retryButton.setVisible(false);
        setStatus(messageLabel, "Accepting the join request...", StatusTone.NORMAL);
        refreshButtons();

        challengeService.acceptChallenge(challenge)
            .whenComplete((setup, failure) -> postIfCurrent(expectedGeneration, () -> {
                accepting = false;
                if (failure != null) {
                    logFailure("accept join request", failure);
                    setStatus(messageLabel,
                        userError(failure) + " Checking challenge state...",
                        StatusTone.ERROR);
                    retryButton.setVisible(true);
                    refreshButtons();
                    startPolling(expectedGeneration, challenge.challengeId());
                    return;
                }
                game.showMultiplayerBattle(setup);
            }));
    }

    private void rejectRequest() {
        if (!hasPendingRequest(challenge) || accepting || rejecting || cancelling
            || fetchingMatch) {
            return;
        }
        long expectedGeneration = generation();
        rejecting = true;
        stopPolling();
        retryButton.setVisible(false);
        setStatus(messageLabel, "Declining the join request...", StatusTone.NORMAL);
        refreshButtons();

        challengeService.rejectJoinRequest(challenge)
            .whenComplete((open, failure) -> postIfCurrent(expectedGeneration, () -> {
                rejecting = false;
                if (failure != null) {
                    logFailure("reject join request", failure);
                    setStatus(messageLabel,
                        userError(failure) + " Checking challenge state...",
                        StatusTone.ERROR);
                    refreshButtons();
                    startPolling(expectedGeneration, challenge.challengeId());
                    return;
                }
                showChallenge(open);
                startPolling(expectedGeneration, open.challengeId());
            }));
    }

    private void fetchMatch(long expectedGeneration, ChallengeSummary accepted) {
        if (fetchingMatch) {
            return;
        }
        if (accepted.acceptedJoinRequestId() == null
            || accepted.acceptedJoinRequestId().isBlank()) {
            stopPolling();
            setStatus(messageLabel,
                "The accepted challenge did not include its request ID. Retry shortly.",
                StatusTone.ERROR);
            retryButton.setVisible(true);
            return;
        }
        stopPolling();
        fetchingMatch = true;
        retryButton.setVisible(false);
        setStatus(messageLabel, "Restoring the accepted match...", StatusTone.NORMAL);
        refreshButtons();

        challengeService.acceptChallenge(accepted).whenComplete((setup, failure) ->
            postIfCurrent(expectedGeneration, () -> {
                fetchingMatch = false;
                if (failure != null) {
                    logFailure("load hosted match", failure);
                    setStatus(messageLabel, userError(failure), StatusTone.ERROR);
                    retryButton.setVisible(true);
                    refreshButtons();
                    return;
                }
                game.showMultiplayerBattle(setup);
            }));
    }

    private void cancelChallenge() {
        if (challenge == null || challenge.status() != ChallengeStatus.OPEN
            || cancelling || accepting || rejecting || fetchingMatch) {
            return;
        }
        long expectedGeneration = generation();
        cancelling = true;
        stopPolling();
        setStatus(messageLabel, "Cancelling challenge...", StatusTone.NORMAL);
        refreshButtons();

        challengeService.cancelChallenge(challenge.challengeId()).whenComplete((cancelled, failure) ->
            postIfCurrent(expectedGeneration, () -> {
                cancelling = false;
                if (failure != null) {
                    logFailure("cancel challenge", failure);
                    setStatus(messageLabel, userError(failure), StatusTone.ERROR);
                    refreshButtons();
                    startPolling(expectedGeneration, challenge.challengeId());
                    return;
                }
                showChallenge(cancelled);
                game.showMultiplayerMenu();
            }));
    }

    private void retry() {
        long expectedGeneration = generation();
        if (accepting || rejecting || cancelling || fetchingMatch) {
            return;
        }
        retryButton.setVisible(false);
        if (challenge != null && challenge.status() == ChallengeStatus.ACCEPTED) {
            fetchMatch(expectedGeneration, challenge);
        } else if (challenge != null && challenge.status() == ChallengeStatus.OPEN) {
            startPolling(expectedGeneration, challenge.challengeId());
        } else {
            onShown(expectedGeneration);
        }
    }

    private void requestBack() {
        if (creating || cancelling || accepting || rejecting || fetchingMatch) {
            setStatus(messageLabel, "Please wait for the current server request.", StatusTone.NORMAL);
            return;
        }
        if (challenge != null && challenge.status() == ChallengeStatus.OPEN) {
            cancelChallenge();
            return;
        }
        game.showMultiplayerMenu();
    }

    private void refreshButtons() {
        boolean open = challenge != null && challenge.status() == ChallengeStatus.OPEN;
        boolean busy = creating || cancelling || accepting || rejecting || fetchingMatch;
        boolean pending = hasPendingRequest(challenge);
        acceptButton.setDisabled(!pending || busy);
        rejectButton.setDisabled(!pending || busy);
        cancelButton.setDisabled(!open || busy);
        backButton.setDisabled(busy);
    }

    private static boolean hasPendingRequest(ChallengeSummary current) {
        return current != null
            && current.status() == ChallengeStatus.OPEN
            && current.requestedPlayerId() != null
            && current.requestedCharacterId() != null
            && current.requestedAt() != null;
    }

    private void stopPolling() {
        pollCycle++;
        pollInFlight.set(false);
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
    }

    @Override
    protected void onBackRequested() {
        requestBack();
    }

    @Override
    protected void onHidden() {
        stopPolling();
    }

    @Override
    protected void onDisposed() {
        stopPolling();
        pollExecutor.shutdownNow();
    }
}
