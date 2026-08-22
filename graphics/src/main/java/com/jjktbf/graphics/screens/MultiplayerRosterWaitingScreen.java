package com.jjktbf.graphics.screens;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.multiplayer.ChallengeService;
import com.jjktbf.multiplayer.protocol.MatchSetup;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Submits the local roster and waits until the opponent has selected theirs. */
public final class MultiplayerRosterWaitingScreen extends MultiplayerScreenBase {
    private final ChallengeService challengeService;
    private final ScheduledExecutorService pollExecutor;
    private final AtomicBoolean pollInFlight = new AtomicBoolean();
    private final Label formatLabel;
    private final Label rosterLabel;
    private final Label statusLabel;
    private final TextButton retryButton;

    private MatchSetup setup;
    private List<String> charactersToSubmit;
    private ScheduledFuture<?> pollTask;
    private volatile long pollCycle;
    private boolean submitting;

    public MultiplayerRosterWaitingScreen(
        JJKGame game,
        AssetLoader assets,
        ChallengeService challengeService
    ) {
        super(game, assets);
        this.challengeService = challengeService;
        pollExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "jjktbf-roster-wait-poll");
            thread.setDaemon(true);
            return thread;
        });

        root.add(header("CHARACTERS LOCKED", "WAITING FOR OPPONENT")).growX().row();
        Table card = new Table(assets.editorSkin);
        card.setBackground(assets.editorSkin.getDrawable("battle-card"));
        card.pad(20f);
        formatLabel = new Label("FORMAT: --", assets.editorSkin);
        rosterLabel = wrappedLabel("YOUR FIGHTERS: --", "small");
        statusLabel = wrappedLabel("Preparing your selection...", "small");
        retryButton = button("RETRY", "primary", this::retry);
        TextButton backButton = button(
            "BACK TO MULTIPLAYER", "default", game::showMultiplayerMenu);
        card.add(formatLabel).growX().left().padBottom(10f).row();
        card.add(rosterLabel).growX().left().padBottom(14f).row();
        card.add(statusLabel).growX().left().padBottom(18f).row();
        card.add(retryButton).growX().height(multiplayerButtonHeight()).pad(4f).row();
        card.add(backButton).growX().height(multiplayerButtonHeight()).pad(4f).row();
        root.add(card).growX().maxWidth(windowsLayout ? 990f : 660f)
            .top().padTop(16f).expandY().row();
    }

    public void prepare(MatchSetup setup, List<String> charactersToSubmit) {
        this.setup = Objects.requireNonNull(setup, "setup");
        this.charactersToSubmit = charactersToSubmit == null
            ? null : List.copyOf(charactersToSubmit);
    }

    @Override
    protected void onShown(long generation) {
        stopPolling();
        submitting = false;
        retryButton.setVisible(false);
        formatLabel.setText("FORMAT: " + setup.format());
        List<String> selected = charactersToSubmit != null
            ? charactersToSubmit : setup.playerCharacterIds();
        rosterLabel.setText("YOUR FIGHTERS: " + rosterSummary(selected));
        if (charactersToSubmit != null) {
            submitCharacters(generation);
        } else {
            setStatus(statusLabel,
                "Waiting for the other player to finish character selection...",
                StatusTone.NORMAL);
            startPolling(generation);
        }
    }

    private void submitCharacters(long expectedGeneration) {
        if (submitting || charactersToSubmit == null) return;
        submitting = true;
        retryButton.setVisible(false);
        setStatus(statusLabel, "Locking in your fighters...", StatusTone.NORMAL);
        challengeService.selectMatchCharacters(setup.matchId(), charactersToSubmit)
            .whenComplete((fresh, failure) -> postIfCurrent(expectedGeneration, () -> {
                submitting = false;
                if (failure != null) {
                    logFailure("select match characters", failure);
                    setStatus(statusLabel, userError(failure), StatusTone.ERROR);
                    retryButton.setVisible(true);
                    return;
                }
                charactersToSubmit = null;
                handleSetup(expectedGeneration, fresh);
            }));
    }

    private void handleSetup(long expectedGeneration, MatchSetup fresh) {
        setup = fresh;
        if (fresh.state() != null) {
            stopPolling();
            game.showMultiplayerBattle(fresh);
            return;
        }
        rosterLabel.setText("YOUR FIGHTERS: " + rosterSummary(fresh.playerCharacterIds()));
        setStatus(statusLabel,
            "Waiting for the other player to finish character selection...",
            StatusTone.OK);
        startPolling(expectedGeneration);
    }

    private void startPolling(long expectedGeneration) {
        stopPolling();
        long cycle = ++pollCycle;
        pollTask = pollExecutor.scheduleAtFixedRate(() -> {
            if (pollCycle != cycle || !isGenerationVisible(expectedGeneration)
                || !pollInFlight.compareAndSet(false, true)) return;
            challengeService.getMatchSetup(setup.matchId()).whenComplete((fresh, failure) -> {
                if (pollCycle == cycle) pollInFlight.set(false);
                postIfCurrent(expectedGeneration, () -> {
                    if (pollCycle != cycle) return;
                    if (failure != null) {
                        logFailure("poll character selection", failure);
                        setStatus(statusLabel,
                            userError(failure) + " Retrying automatically...",
                            StatusTone.ERROR);
                        return;
                    }
                    handleSetup(expectedGeneration, fresh);
                });
            });
        }, 1L, 1L, TimeUnit.SECONDS);
    }

    private String rosterSummary(List<String> characterIds) {
        if (characterIds == null || characterIds.isEmpty()) return "--";
        return characterIds.stream().map(game::multiplayerFighterName)
            .reduce((left, right) -> left + " + " + right)
            .orElse("--");
    }

    private void retry() {
        retryButton.setVisible(false);
        if (charactersToSubmit != null) submitCharacters(generation());
        else startPolling(generation());
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
        game.showMultiplayerMenu();
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
