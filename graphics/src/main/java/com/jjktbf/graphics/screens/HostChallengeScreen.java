package com.jjktbf.graphics.screens;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
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

/** Creates a public challenge and polls until another player accepts it. */
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

        cancelButton = button("CANCEL CHALLENGE", "primary", this::cancelChallenge);
        retryButton = button("RETRY", "default", this::retry);
        backButton = button("BACK", "default", this::requestBack);
        card.add(cancelButton).growX().height(46f).pad(4f).row();
        card.add(retryButton).growX().height(46f).pad(4f).row();
        card.add(backButton).growX().height(46f).pad(4f).row();

        ScrollPane scroll = new ScrollPane(card, assets.editorSkin);
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
        fetchingMatch = false;

        guestLabel.setText("GUEST: CONNECTING");
        fighterLabel.setText("FIGHTER: " + game.multiplayerFighterName(selectedCharacterId)
            + " [" + selectedCharacterId + "]");
        challengeLabel.setText("CHALLENGE: --");
        challengeStatusLabel.setText("STATUS: PREPARING");
        setStatus(messageLabel, "Validating guest identity...", StatusTone.NORMAL);
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
                    && recoverable.status() == ChallengeStatus.ACCEPTED) {
                    showChallenge(recoverable);
                    fetchMatch(generation, recoverable);
                    return;
                }
                createChallenge(generation);
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

        challengeService.createChallenge(selectedCharacterId).whenComplete((created, failure) -> {
            if (!isGenerationVisible(expectedGeneration)) {
                if (failure == null && created != null && created.status() == ChallengeStatus.OPEN) {
                    challengeService.cancelChallenge(created.challengeId())
                        .exceptionally(cleanupFailure -> null);
                }
                return;
            }
            postIfCurrent(expectedGeneration, () -> {
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
            });
        });
    }

    private void showChallenge(ChallengeSummary current) {
        challenge = current;
        challengeLabel.setText("CHALLENGE: " + current.challengeId());
        challengeStatusLabel.setText("STATUS: " + current.status());
        switch (current.status()) {
            case OPEN -> setStatus(messageLabel,
                "Waiting for another player. This screen checks the server every second.",
                StatusTone.NORMAL);
            case ACCEPTED -> setStatus(messageLabel,
                "Challenge accepted. Loading the authoritative match...", StatusTone.OK);
            case CANCELLED -> setStatus(messageLabel,
                "This challenge was cancelled.", StatusTone.NORMAL);
            case EXPIRED -> setStatus(messageLabel,
                "This challenge expired before another player joined.", StatusTone.ERROR);
        }
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

    private void fetchMatch(long expectedGeneration, ChallengeSummary accepted) {
        if (fetchingMatch) {
            return;
        }
        if (accepted.matchId() == null || accepted.matchId().isBlank()) {
            stopPolling();
            setStatus(messageLabel,
                "The accepted challenge did not include a match ID. Retry shortly.",
                StatusTone.ERROR);
            retryButton.setVisible(true);
            return;
        }
        stopPolling();
        fetchingMatch = true;
        retryButton.setVisible(false);
        setStatus(messageLabel, "Loading match " + accepted.matchId() + "...", StatusTone.NORMAL);
        refreshButtons();

        challengeService.getMatchSetup(accepted.matchId()).whenComplete((setup, failure) ->
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
            || cancelling || fetchingMatch) {
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
        if (creating || cancelling || fetchingMatch) {
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
        cancelButton.setDisabled(!open || cancelling || fetchingMatch);
        backButton.setDisabled(creating || cancelling || fetchingMatch);
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
