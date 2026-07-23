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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
        "CHALLENGE_NOT_FOUND",
        "CHALLENGE_REQUEST_PENDING"
    );

    private final GuestAccountService guestAccountService;
    private final ChallengeService challengeService;
    private final ScheduledExecutorService pollExecutor;
    private final AtomicBoolean pollInFlight = new AtomicBoolean();
    private final Label guestLabel;
    private final Label statusLabel;
    private final Table challengeRows;
    private final TextButton refreshButton;
    private final TextButton backButton;
    private final List<TextButton> joinButtons = new ArrayList<>();

    private boolean identityReady;
    private boolean loading;
    private boolean joining;
    private boolean requestingJoin;
    private boolean fetchingMatch;
    private String localPlayerId;
    private String requestedChallengeId;
    private String requestedCharacterId;
    private ChallengeSummary requestedChallenge;
    private boolean requestConfirmed;
    private ScheduledFuture<?> pollTask;
    private volatile long pollCycle;

    public ChallengeBrowserScreen(
        JJKGame game,
        AssetLoader assets,
        GuestAccountService guestAccountService,
        ChallengeService challengeService
    ) {
        super(game, assets);
        this.guestAccountService = guestAccountService;
        this.challengeService = challengeService;
        this.pollExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "jjktbf-join-request-poll");
            thread.setDaemon(true);
            return thread;
        });

        root.add(header("SEARCH CHALLENGES", "OPEN / COMPATIBLE")).growX().row();

        Table identityBar = new Table(assets.editorSkin);
        identityBar.setBackground(assets.editorSkin.getDrawable("battle-palette"));
        identityBar.pad(10f, 14f, 10f, 14f);
        guestLabel = new Label("GUEST: CONNECTING", assets.editorSkin, "white");
        // On dark-blue battle-palette: small-white so status tints stay legible.
        statusLabel = wrappedLabel("", "small-white");
        identityBar.add(guestLabel).left().padRight(18f);
        identityBar.add(statusLabel).growX().left();
        root.add(identityBar).growX().padTop(12f).row();

        challengeRows = new Table(assets.editorSkin);
        challengeRows.top().left();
        ScrollPane challengeScroll = new AxisLockedScrollPane(challengeRows, assets.editorSkin);
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
        stopPolling();
        identityReady = false;
        loading = true;
        joining = false;
        requestingJoin = false;
        fetchingMatch = false;
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
                ChallengeSummary recoverable = challengeService.currentChallenge().orElse(null);
                if (requestedChallengeId == null && isOwnPendingRequest(recoverable)) {
                    requestedChallengeId = recoverable.challengeId();
                    requestedCharacterId = recoverable.requestedCharacterId();
                    requestedChallenge = recoverable;
                    requestConfirmed = true;
                }
                if (requestedChallengeId != null) {
                    joining = true;
                    loading = false;
                    if (recoverable != null
                        && recoverable.status() == ChallengeStatus.ACCEPTED
                        && requestedChallengeId.equals(recoverable.challengeId())) {
                        requestedChallenge = recoverable;
                        fetchRequesterMatch(generation, recoverable);
                    } else {
                        showWaitingForHost();
                        startPolling(generation);
                    }
                } else {
                    recoverRequestedChallenge(generation);
                }
            }));
    }

    private void recoverRequestedChallenge(long expectedGeneration) {
        challengeService.getRequestedChallenge().whenComplete((recoverable, failure) ->
            postIfCurrent(expectedGeneration, () -> {
                if (failure != null) {
                    if ("CHALLENGE_NOT_FOUND".equals(errorCode(failure))) {
                        requestChallenges(expectedGeneration, true);
                        return;
                    }
                    loading = false;
                    identityReady = false;
                    logFailure("recover requested challenge", failure);
                    setStatus(statusLabel, userError(failure), StatusTone.ERROR);
                    showCenteredMessage(
                        "Could not check your previous join request. Select REFRESH to retry.");
                    refreshActions();
                    return;
                }
                challengeService.rememberChallenge(recoverable);
                requestedChallenge = recoverable;
                requestedChallengeId = recoverable.challengeId();
                requestedCharacterId = recoverable.requestedCharacterId();
                requestConfirmed = recoverable.status() == ChallengeStatus.OPEN;
                joining = true;
                loading = false;
                if (recoverable.status() == ChallengeStatus.ACCEPTED) {
                    fetchRequesterMatch(expectedGeneration, recoverable);
                } else if (isOwnPendingRequest(recoverable)) {
                    showWaitingForHost();
                    startPolling(expectedGeneration);
                } else {
                    returnToBrowser(expectedGeneration,
                        "The previous join request is no longer pending.", StatusTone.NORMAL);
                }
            }));
    }

    private void requestChallenges(long expectedGeneration, boolean showLoadingState) {
        requestChallenges(expectedGeneration, showLoadingState, null, StatusTone.NORMAL);
    }

    private void requestChallenges(
        long expectedGeneration,
        boolean showLoadingState,
        String successMessage,
        StatusTone successTone
    ) {
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
                if (successMessage != null) {
                    setStatus(statusLabel, successMessage, successTone);
                }
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
        requestedChallengeId = challenge.challengeId();
        requestedCharacterId = game.getSelectedMultiplayerCharacterId();
        requestedChallenge = null;
        requestConfirmed = false;
        joining = true;
        requestingJoin = true;
        setStatus(statusLabel,
            "Sending join request with "
                + game.multiplayerFighterName(requestedCharacterId) + "...",
            StatusTone.NORMAL);
        refreshActions();

        challengeService.requestJoin(
            requestedChallengeId, requestedCharacterId
        ).whenComplete((requested, failure) -> postIfCurrent(expectedGeneration, () -> {
            requestingJoin = false;
            if (failure != null) {
                if (isAmbiguousFailure(failure)) {
                    logFailure("request challenge join", failure);
                    showWaitingForHost();
                    startPolling(expectedGeneration);
                    return;
                }
                requestedChallengeId = null;
                requestedCharacterId = null;
                requestedChallenge = null;
                joining = false;
                logFailure("request challenge join", failure);
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
            requestedChallenge = requested;
            requestConfirmed = true;
            challengeService.rememberChallenge(requested);
            showWaitingForHost();
            startPolling(expectedGeneration);
        }));
    }

    private void showWaitingForHost() {
        loading = false;
        joining = true;
        showCenteredMessage(
            "JOIN REQUEST SENT\n\nWaiting for the host to approve or decline...");
        setStatus(statusLabel,
            "Waiting for host approval. This screen checks the server every second.",
            StatusTone.NORMAL);
        refreshActions();
    }

    private void startPolling(long expectedGeneration) {
        stopPolling();
        if (requestedChallengeId == null) {
            return;
        }
        String challengeId = requestedChallengeId;
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
                        logFailure("poll requested challenge", failure);
                        setStatus(statusLabel,
                            userError(failure) + " Still waiting and retrying...",
                            StatusTone.ERROR);
                        return;
                    }
                    challengeService.rememberChallenge(current);
                    if (current.status() == ChallengeStatus.ACCEPTED) {
                        requestedChallenge = current;
                        fetchRequesterMatch(expectedGeneration, current);
                    } else if (isOwnPendingRequest(current)) {
                        requestedChallenge = current;
                        requestConfirmed = true;
                        showWaitingForHost();
                    } else if (current.status() == ChallengeStatus.OPEN) {
                        if (!requestConfirmed) {
                            return;
                        }
                        returnToBrowser(expectedGeneration,
                            "The host declined your join request.", StatusTone.ERROR);
                    } else {
                        returnToBrowser(expectedGeneration,
                            closedChallengeMessageForStatus(current.status()), StatusTone.ERROR);
                    }
                });
            });
        }, 0L, 1L, TimeUnit.SECONDS);
    }

    private void fetchRequesterMatch(
        long expectedGeneration,
        ChallengeSummary accepted
    ) {
        if (fetchingMatch) {
            return;
        }
        if (accepted.matchId() == null || accepted.matchId().isBlank()) {
            setStatus(statusLabel,
                "The accepted match is still being prepared. Retrying...", StatusTone.NORMAL);
            return;
        }
        stopPolling();
        fetchingMatch = true;
        setStatus(statusLabel, "Request accepted. Loading the match...", StatusTone.OK);
        refreshActions();

        challengeService.getMatchSetup(accepted.matchId()).whenComplete((setup, failure) ->
            postIfCurrent(expectedGeneration, () -> {
                fetchingMatch = false;
                if (failure == null) {
                    requestedChallengeId = null;
                    requestedCharacterId = null;
                    requestedChallenge = null;
                    joining = false;
                    game.showMultiplayerBattle(setup);
                    return;
                }
                logFailure("load requested match", failure);
                if ("PLAYER_NOT_IN_MATCH".equals(errorCode(failure))) {
                    returnToBrowser(expectedGeneration,
                        "The host declined your join request.", StatusTone.ERROR);
                    return;
                }
                if ("MATCH_NOT_FOUND".equals(errorCode(failure))
                    || "INCOMPATIBLE_VERSION".equals(errorCode(failure))) {
                    returnToBrowser(expectedGeneration,
                        "That accepted match can no longer be restored.", StatusTone.ERROR);
                    return;
                }
                setStatus(statusLabel,
                    userError(failure) + " Retrying automatically...", StatusTone.ERROR);
                startPolling(expectedGeneration);
            }));
    }

    private void returnToBrowser(
        long expectedGeneration,
        String message,
        StatusTone tone
    ) {
        stopPolling();
        requestedChallengeId = null;
        requestedCharacterId = null;
        requestedChallenge = null;
        requestConfirmed = false;
        joining = false;
        fetchingMatch = false;
        requestChallenges(expectedGeneration, false, message, tone);
    }

    private boolean isOwnPendingRequest(ChallengeSummary current) {
        return current != null
            && current.status() == ChallengeStatus.OPEN
            && localPlayerId != null
            && localPlayerId.equals(current.requestedPlayerId())
            && (requestedCharacterId == null
                || requestedCharacterId.equals(current.requestedCharacterId()));
    }

    private String closedChallengeMessageForStatus(ChallengeStatus status) {
        return switch (status) {
            case CANCELLED -> "The host cancelled that challenge.";
            case EXPIRED -> "That challenge expired before the host responded.";
            case ACCEPTED -> "That challenge was accepted.";
            case OPEN -> "That challenge is still open.";
        };
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
            case "CHALLENGE_REQUEST_PENDING" ->
                "Another request reached that host first. The list is being refreshed.";
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
        backButton.setDisabled(requestingJoin || fetchingMatch);
        for (TextButton button : joinButtons) {
            button.setDisabled(!actionsEnabled);
        }
    }

    private void requestBack() {
        if (requestingJoin || fetchingMatch) {
            setStatus(statusLabel, "Please wait for the current server request.", StatusTone.NORMAL);
            return;
        }
        if (joining && requestedChallengeId != null) {
            if (requestedChallenge == null) {
                stopPolling();
                game.showMultiplayerMenu();
                return;
            }
            withdrawJoinRequest();
            return;
        }
        stopPolling();
        game.showMultiplayerMenu();
    }

    private void withdrawJoinRequest() {
        long expectedGeneration = generation();
        String challengeId = requestedChallengeId;
        ChallengeSummary pending = requestedChallenge;
        if (pending == null) {
            return;
        }
        stopPolling();
        requestingJoin = true;
        setStatus(statusLabel, "Withdrawing join request...", StatusTone.NORMAL);
        refreshActions();

        challengeService.withdrawJoinRequest(pending).whenComplete((withdrawn, failure) ->
            postIfCurrent(expectedGeneration, () -> {
                if (failure == null) {
                    requestingJoin = false;
                    challengeService.rememberChallenge(withdrawn);
                    if (withdrawn.status() == ChallengeStatus.ACCEPTED) {
                        requestedChallenge = withdrawn;
                        fetchRequesterMatch(expectedGeneration, withdrawn);
                        return;
                    }
                    requestedChallengeId = null;
                    requestedCharacterId = null;
                    requestedChallenge = null;
                    joining = false;
                    game.showMultiplayerMenu();
                    return;
                }
                challengeService.getChallenge(challengeId).whenComplete((current, lookupFailure) ->
                    postIfCurrent(expectedGeneration, () -> {
                        requestingJoin = false;
                        if (lookupFailure == null
                            && current.status() == ChallengeStatus.ACCEPTED) {
                            fetchRequesterMatch(expectedGeneration, current);
                            return;
                        }
                        setStatus(statusLabel, userError(failure), StatusTone.ERROR);
                        startPolling(expectedGeneration);
                        refreshActions();
                    }));
            }));
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
