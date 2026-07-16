package com.jjktbf.graphics.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.utils.Align;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.multiplayer.MatchWebSocketClient;
import com.jjktbf.graphics.multiplayer.MultiplayerMatchService;
import com.jjktbf.graphics.multiplayer.MultiplayerPlanDraft;
import com.jjktbf.graphics.multiplayer.MultiplayerSession;
import com.jjktbf.multiplayer.protocol.ActionSegmentState;
import com.jjktbf.multiplayer.protocol.BattleEventState;
import com.jjktbf.multiplayer.protocol.BattlePhase;
import com.jjktbf.multiplayer.protocol.CharacterState;
import com.jjktbf.multiplayer.protocol.ErrorResponse;
import com.jjktbf.multiplayer.protocol.MatchSetup;
import com.jjktbf.multiplayer.protocol.MatchState;
import com.jjktbf.multiplayer.protocol.MatchStatus;
import com.jjktbf.multiplayer.protocol.MessageType;
import com.jjktbf.multiplayer.protocol.MoveState;
import com.jjktbf.multiplayer.protocol.PlanState;
import com.jjktbf.multiplayer.protocol.PlayerSide;
import com.jjktbf.multiplayer.protocol.PlayerState;
import com.jjktbf.multiplayer.protocol.SocketMessage;
import com.jjktbf.multiplayer.protocol.StatusEffectState;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Asynchronous, snapshot-only renderer and intent builder for online matches. */
public final class MultiplayerBattleScreen extends MultiplayerScreenBase {
    private static final int MAX_CONNECTION_NOTICES = 20;
    private static final DateTimeFormatter DEADLINE_TIME = DateTimeFormatter
        .ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    private final MultiplayerSession session;
    private final MultiplayerMatchService matchService;
    private final MultiplayerPlanDraft draft = new MultiplayerPlanDraft();
    private final Deque<String> connectionNotices = new ArrayDeque<>();
    private final List<MoveButtonBinding> moveButtons = new ArrayList<>();

    private final Label matchHeaderLabel;
    private final Label matchMetadataLabel;
    private final Label connectionLabel;
    private final Label errorLabel;
    private final Label resultLabel;
    private final Table combatants;
    private final Table contentLayout;
    private final Table planPanel;
    private final Table eventsPanel;
    private Table movesTable;
    private Table draftTable;
    private Table eventRows;
    private Label budgetLabel;
    private Label planStatusLabel;
    private TextButton undoButton;
    private TextButton clearButton;
    private TextButton submitButton;
    private final TextButton leaveButton;

    private MatchSetup setup;
    private MatchState latestState;
    private MultiplayerSession.ConnectionState connectionState =
        MultiplayerSession.ConnectionState.DISCONNECTED;
    private MultiplayerMatchService.Listener serviceListener;
    private long battleRun;
    private boolean submissionPending;
    private boolean leaving;
    private boolean terminal;
    private boolean preserveForRecovery;
    private Table localCard;
    private Table opponentCard;

    public MultiplayerBattleScreen(
        JJKGame game,
        AssetLoader assets,
        MultiplayerSession session,
        MultiplayerMatchService matchService
    ) {
        super(game, assets);
        this.session = session;
        this.matchService = matchService;

        Table battleHeader = new Table(assets.editorSkin);
        battleHeader.setBackground(assets.editorSkin.getDrawable("battle-header"));
        battleHeader.pad(11f, 16f, 11f, 16f);
        matchHeaderLabel = new Label("MULTIPLAYER BATTLE", assets.editorSkin, "title");
        matchMetadataLabel = wrappedLabel("WAITING FOR MATCH SETUP", "small");
        matchMetadataLabel.setColor(new Color(0.720f, 0.800f, 0.950f, 1f));
        matchMetadataLabel.setAlignment(Align.right);
        battleHeader.add(matchHeaderLabel).growX().left();
        battleHeader.add(matchMetadataLabel).right().padLeft(12f);
        root.add(battleHeader).growX().row();

        Table page = new Table(assets.editorSkin);
        page.top();

        Table connectionCard = new Table(assets.editorSkin);
        connectionCard.setBackground(assets.editorSkin.getDrawable("battle-card"));
        connectionCard.pad(10f, 14f, 10f, 14f);
        connectionLabel = wrappedLabel("DISCONNECTED", "small");
        errorLabel = wrappedLabel("", "small");
        connectionCard.add(connectionLabel).growX().left().padRight(12f);
        connectionCard.add(errorLabel).growX().right();
        page.add(connectionCard).growX().padBottom(10f).row();

        combatants = new Table(assets.editorSkin);
        page.add(combatants).growX().padBottom(10f).row();

        contentLayout = new Table(assets.editorSkin);
        planPanel = buildPlanPanel();
        eventsPanel = buildEventsPanel();
        page.add(contentLayout).growX().padBottom(10f).row();

        resultLabel = wrappedLabel("", "default");
        resultLabel.setAlignment(Align.center);
        page.add(resultLabel).growX().pad(5f).row();

        leaveButton = button("LEAVE MATCH", "default", this::leaveMatch);
        page.add(leaveButton).growX().maxWidth(520f).height(48f).padTop(5f).row();

        Label forfeitNotice = wrappedLabel(
            "Leaving an active match disconnects immediately. If you do not return "
                + "within the server grace period, the opponent may receive a forfeit win.",
            "small");
        forfeitNotice.setAlignment(Align.center);
        page.add(forfeitNotice).growX().maxWidth(780f).pad(8f).row();

        ScrollPane pageScroll = new ScrollPane(page, assets.editorSkin);
        pageScroll.setFadeScrollBars(false);
        pageScroll.setScrollingDisabled(true, false);
        root.add(pageScroll).grow().padTop(10f).row();

        layoutContent(Gdx.graphics.getWidth());
        resetView();
    }

    private Table buildPlanPanel() {
        Table panel = new Table(assets.editorSkin);
        panel.setBackground(assets.editorSkin.getDrawable("battle-palette"));
        panel.pad(12f);

        Label title = new Label("PLAN BUILDER", assets.editorSkin, "title");
        title.setColor(new Color(1.000f, 0.835f, 0.180f, 1f));
        panel.add(title).growX().left().padBottom(6f).row();

        budgetLabel = new Label("DRAFT AP -- | CE --", assets.editorSkin, "white");
        panel.add(budgetLabel).growX().left().padBottom(5f).row();
        planStatusLabel = wrappedLabel("Waiting for an authoritative planning state.", "small");
        planStatusLabel.setColor(new Color(0.720f, 0.800f, 0.950f, 1f));
        panel.add(planStatusLabel).growX().left().padBottom(8f).row();

        movesTable = new Table(assets.editorSkin);
        movesTable.top();
        ScrollPane moveScroll = new ScrollPane(movesTable, assets.editorSkin);
        moveScroll.setFadeScrollBars(false);
        moveScroll.setScrollingDisabled(true, false);
        panel.add(moveScroll).growX().height(245f).padBottom(8f).row();

        Label draftTitle = new Label("QUEUED PLACEMENTS", assets.editorSkin, "white");
        panel.add(draftTitle).growX().left().padBottom(4f).row();
        draftTable = new Table(assets.editorSkin);
        draftTable.top();
        ScrollPane draftScroll = new ScrollPane(draftTable, assets.editorSkin);
        draftScroll.setFadeScrollBars(false);
        draftScroll.setScrollingDisabled(true, false);
        panel.add(draftScroll).growX().height(155f).padBottom(8f).row();

        Table editingActions = new Table(assets.editorSkin);
        undoButton = button("UNDO", "default", () -> {
            if (draft.undo()) {
                setPlanMessage("Removed the most recent draft placement.", false);
                refreshDraftView();
            }
        });
        clearButton = button("CLEAR", "default", () -> {
            draft.clear();
            setPlanMessage("Draft cleared. Authoritative state was not changed.", false);
            refreshDraftView();
        });
        submitButton = button("SUBMIT PLAN", "primary", this::submitPlan);
        editingActions.add(undoButton).growX().height(43f).pad(3f);
        editingActions.add(clearButton).growX().height(43f).pad(3f);
        editingActions.add(submitButton).growX().height(43f).pad(3f);
        panel.add(editingActions).growX().row();
        return panel;
    }

    private Table buildEventsPanel() {
        Table panel = new Table(assets.editorSkin);
        panel.setBackground(assets.editorSkin.getDrawable("battle-palette"));
        panel.pad(12f);
        Label title = new Label("SERVER EVENTS", assets.editorSkin, "title");
        title.setColor(new Color(1.000f, 0.835f, 0.180f, 1f));
        panel.add(title).growX().left().padBottom(8f).row();

        eventRows = new Table(assets.editorSkin);
        eventRows.top().left();
        ScrollPane eventScroll = new ScrollPane(eventRows, assets.editorSkin);
        eventScroll.setFadeScrollBars(false);
        eventScroll.setScrollingDisabled(true, false);
        panel.add(eventScroll).grow().minHeight(400f).row();
        return panel;
    }

    @Override
    protected void onShown(long generation) {
        detachListener();
        battleRun++;
        setup = null;
        latestState = null;
        connectionState = MultiplayerSession.ConnectionState.DISCONNECTED;
        submissionPending = false;
        leaving = false;
        terminal = false;
        preserveForRecovery = false;
        connectionNotices.clear();
        draft.clear();
        resetView();
    }

    /** Starts or resumes one match after the screen has become visible. */
    public void begin(MatchSetup matchSetup) {
        Objects.requireNonNull(matchSetup, "matchSetup");
        if (game.getScreen() != this || !isGenerationVisible(generation())) {
            throw new IllegalStateException("Multiplayer battle screen must be shown before begin");
        }

        detachListener();
        long run = ++battleRun;
        long expectedGeneration = generation();
        setup = matchSetup;
        latestState = null;
        connectionState = MultiplayerSession.ConnectionState.DISCONNECTED;
        submissionPending = false;
        leaving = false;
        terminal = false;
        preserveForRecovery = false;
        connectionNotices.clear();
        draft.clear();
        resetView();

        matchHeaderLabel.setText("ONLINE MATCH");
        matchMetadataLabel.setText("MATCH " + matchSetup.matchId()
            + "  |  " + matchSetup.ruleset()
            + "  |  v" + matchSetup.gameVersion()
            + " / P" + matchSetup.protocolVersion());
        if (matchSetup.state() != null) {
            applySnapshot(matchSetup.state());
        }

        if (isTerminal(matchSetup.status())
            || (matchSetup.state() != null && isTerminal(matchSetup.state().status()))) {
            terminal = true;
            connectionState = MultiplayerSession.ConnectionState.DISCONNECTED;
            refreshActionState();
            return;
        }

        serviceListener = new BattleListener(expectedGeneration, run, matchSetup.matchId());
        matchService.addListener(serviceListener);
        try {
            matchService.connect(matchSetup).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    postBattle(expectedGeneration, run, () -> {
                        logFailure("connect match", failure);
                        setError(userError(failure));
                    });
                }
            });
        } catch (RuntimeException failure) {
            postBattle(expectedGeneration, run, () -> {
                logFailure("connect match", failure);
                setError(userError(failure));
            });
        }
    }

    private void resetView() {
        matchHeaderLabel.setText("MULTIPLAYER BATTLE");
        matchMetadataLabel.setText("WAITING FOR MATCH SETUP");
        connectionLabel.setText("CONNECTION: DISCONNECTED");
        errorLabel.setText("");
        resultLabel.setText("");
        combatants.clear();
        Label waiting = wrappedLabel("Waiting for complete authoritative player states...", "small");
        waiting.setAlignment(Align.center);
        combatants.add(waiting).growX().pad(24f);
        movesTable.clear();
        draftTable.clear();
        eventRows.clear();
        moveButtons.clear();
        budgetLabel.setText("DRAFT AP -- | CE --");
        setPlanMessage("Waiting for an authoritative planning state.", false);
        leaveButton.setText("LEAVE MATCH");
        refreshActionState();
    }

    private void applySnapshot(MatchState state) {
        if (!isCompleteSnapshot(state)) {
            setError("The server returned an incomplete match snapshot; it was not rendered.");
            return;
        }

        int previousRound = latestState == null ? -1 : latestState.roundNumber();
        latestState = state;
        terminal = isTerminal(state.status()) || state.phase() == BattlePhase.BATTLE_OVER;
        matchHeaderLabel.setText("ROUND " + state.roundNumber() + "  |  " + state.phase());
        matchMetadataLabel.setText("STATUS " + state.status()
            + "  |  TICK " + state.currentTick()
            + "  |  STATE " + state.stateVersion());

        PlayerState local = localPlayer(state);
        PlayerState opponent = opponentPlayer(state);
        localCard = buildCombatantCard("YOU", local);
        opponentCard = buildCombatantCard("OPPONENT", opponent);
        layoutCombatants(Gdx.graphics.getWidth());

        CharacterState localCharacter = local.character();
        PlanState plan = localCharacter.plan();
        int apBudget = Math.max(0,
            plan == null ? localCharacter.currentAp() : plan.apBudget());
        int ceBudget = Math.max(0,
            plan == null ? localCharacter.currentCe() : plan.ceBudget());
        boolean newRound = draft.beginRound(state.roundNumber(), apBudget, ceBudget);
        if (newRound && previousRound >= 0 && state.roundNumber() != previousRound) {
            setPlanMessage("New authoritative round received. The prior draft was cleared.", false);
        }

        rebuildMoves(localCharacter.knownMoves());
        refreshDraftView();
        rebuildEvents();
        updateResult(state);
        setConnectionText();
        refreshActionState();
    }

    private boolean isCompleteSnapshot(MatchState state) {
        if (state == null || setup == null
            || !setup.matchId().equals(state.matchId())
            || state.status() == null || state.phase() == null) {
            return false;
        }
        PlayerState local = state.player(setup.playerSide()).orElse(null);
        PlayerState opponent = state.player(opposite(setup.playerSide())).orElse(null);
        return local != null && opponent != null
            && local.character() != null && opponent.character() != null
            && state.roundNumber() >= 0;
    }

    private Table buildCombatantCard(String role, PlayerState player) {
        Table card = new Table(assets.editorSkin);
        card.setBackground(assets.editorSkin.getDrawable("battle-card"));
        card.pad(13f);
        CharacterState character = player.character();

        Label name = new Label(role + ": " + player.displayName(), assets.editorSkin);
        Label fighter = wrappedLabel(
            character.name() + " [" + character.characterId() + "]  |  "
                + (player.connected() ? "CONNECTED" : "DISCONNECTED")
                + "  |  PLAN " + (player.planSubmitted() ? "SUBMITTED" : "OPEN"),
            "small");
        Label resources = wrappedLabel(
            "HP " + character.currentHp() + "/" + character.maxHp()
                + "  |  CE " + character.currentCe() + "/" + character.maxCe()
                + "  |  AP " + character.currentAp() + "/" + character.maxAp()
                + "  |  DEF " + character.currentDefense(),
            "small");
        Label bfs = wrappedLabel(
            "BFS: " + (character.inBlackFlashState() ? "ACTIVE" : "INACTIVE")
                + "  |  HITS " + character.consecutiveBfsHits()
                + (character.bfsExpiresAfterRound() == null
                    ? "" : "  |  EXPIRES AFTER ROUND " + character.bfsExpiresAfterRound()),
            "small");
        Label effects = wrappedLabel("STATUS: " + formatStatusEffects(character.statusEffects()), "small");

        card.add(name).growX().left().padBottom(5f).row();
        card.add(fighter).growX().left().padBottom(4f).row();
        card.add(resources).growX().left().padBottom(4f).row();
        card.add(bfs).growX().left().padBottom(4f).row();
        card.add(effects).growX().left().row();
        return card;
    }

    private String formatStatusEffects(List<StatusEffectState> effects) {
        if (effects == null || effects.isEmpty()) {
            return "NONE";
        }
        return effects.stream().map(effect -> effect.displayName()
            + " (" + effect.remainingRounds() + "R, x" + trimDouble(effect.magnitude()) + ")")
            .collect(Collectors.joining(", "));
    }

    private void rebuildMoves(List<MoveState> moves) {
        movesTable.clear();
        moveButtons.clear();
        if (moves == null || moves.isEmpty()) {
            movesTable.add(wrappedLabel("No canonical moves are available.", "small"))
                .growX().pad(12f).row();
            return;
        }
        for (MoveState move : moves) {
            Table card = new Table(assets.editorSkin);
            card.setBackground(assets.editorSkin.getDrawable("battle-card"));
            card.pad(9f);
            Label name = new Label(move.name(), assets.editorSkin);
            Label details = wrappedLabel(
                move.board() + "  |  AP " + move.apCost()
                    + "  |  CE " + move.effectiveCeCost()
                    + "  |  FIRE +" + (move.unleashPoint() - 1)
                    + "  |  POWER " + move.basePower()
                    + (move.tags().isEmpty() ? "" : "\n" + String.join(" / ", move.tags()))
                    + (move.available() ? "" : "\nUNAVAILABLE: "
                        + (move.restrictionReason() == null
                            ? "restricted by the server" : move.restrictionReason()))
                    + (move.description() == null || move.description().isBlank()
                        ? "" : "\n" + move.description()),
                "small");
            TextButton queue = button("QUEUE", "primary", () -> queueMove(move));
            moveButtons.add(new MoveButtonBinding(move, queue));

            Table info = new Table(assets.editorSkin);
            info.add(name).growX().left().padBottom(3f).row();
            info.add(details).growX().left().row();
            card.add(info).growX().left();
            card.add(queue).width(108f).height(42f).padLeft(8f);
            movesTable.add(card).growX().pad(3f).row();
        }
    }

    private void queueMove(MoveState move) {
        if (!canPlan()) {
            setPlanMessage("Planning is disabled until the match is active, connected, "
                + "and awaiting your plan.", true);
            return;
        }
        MultiplayerPlanDraft.AddResult result = draft.addFirstFit(move);
        if (!result.added()) {
            setPlanMessage(addFailureMessage(result.status()), true);
            refreshActionState();
            return;
        }
        MultiplayerPlanDraft.DraftPlacement placement = result.placement();
        setPlanMessage(move.name() + " queued on " + move.board()
            + " at ticks " + placement.startTick() + "-" + placement.endTick() + ".", false);
        refreshDraftView();
    }

    private String addFailureMessage(MultiplayerPlanDraft.AddStatus status) {
        return switch (status) {
            case INSUFFICIENT_AP -> "The draft does not have enough authoritative AP budget.";
            case INSUFFICIENT_CE -> "The draft does not have enough authoritative CE budget.";
            case BOARD_FULL -> "No free range remains on that move's declared board.";
            case MOVE_RESTRICTED -> "That move is currently restricted by the server.";
            case INVALID_MOVE -> "The server-declared move cannot be placed.";
            case ADDED -> "Move queued.";
        };
    }

    private void refreshDraftView() {
        budgetLabel.setText("DRAFT AP " + draft.remainingAp() + "/" + draft.apBudget()
            + "  |  CE " + draft.remainingCe() + "/" + draft.ceBudget());
        draftTable.clear();
        if (draft.placements().isEmpty()) {
            draftTable.add(wrappedLabel(
                "DRAFT: EMPTY (submitting an empty plan banks the round)", "small"))
                .growX().pad(8f).row();
        } else {
            int index = 1;
            for (MultiplayerPlanDraft.DraftPlacement placement : draft.placements()) {
                MoveState move = placement.move();
                Label row = wrappedLabel(
                    "DRAFT " + index++ + ": " + move.name()
                        + " | " + move.board()
                        + " | T" + placement.startTick() + "-" + placement.endTick()
                        + " | AP " + move.apCost()
                        + " | CE " + move.effectiveCeCost(),
                    "small");
                draftTable.add(row).growX().left().pad(4f).row();
            }
        }

        PlayerState local = latestState == null ? null : localPlayer(latestState);
        PlanState authoritative = local == null ? null : local.character().plan();
        if (authoritative != null && !authoritative.queuedSegments().isEmpty()) {
            Label title = new Label("AUTHORITATIVE QUEUE", assets.editorSkin);
            draftTable.add(title).growX().left().pad(7f, 4f, 3f, 4f).row();
            for (ActionSegmentState segment : authoritative.queuedSegments()) {
                Label row = wrappedLabel(
                    "SERVER: " + segment.moveName()
                        + " | " + segment.board()
                        + " | T" + segment.startTick() + "-" + segment.endTick()
                        + " | " + segment.status(),
                    "small");
                draftTable.add(row).growX().left().pad(4f).row();
            }
        }
        refreshActionState();
    }

    private void submitPlan() {
        if (!canPlan()) {
            return;
        }
        MultiplayerMatchService.PlanSubmission submission =
            matchService.submitPlan(draft.toIntent());
        if (!submission.sent()) {
            setPlanMessage(submissionStatusMessage(submission.status()), true);
            submissionPending = false;
            refreshActionState();
            return;
        }
        submissionPending = true;
        setPlanMessage("Plan sent. Waiting for an authoritative server snapshot...", false);
        refreshActionState();
    }

    private String submissionStatusMessage(MultiplayerMatchService.SubmissionStatus status) {
        return switch (status) {
            case NO_MATCH -> "No active match setup is available.";
            case NOT_CONNECTED -> "The match is not connected yet.";
            case ALREADY_PENDING -> "A plan command is already awaiting a server response.";
            case MATCH_ENDED -> "The match has already ended.";
            case SERVICE_CLOSED -> "The multiplayer service is closed.";
            case SENT -> "Plan sent.";
        };
    }

    private void rebuildEvents() {
        eventRows.clear();
        for (String notice : connectionNotices) {
            addEventLabel(notice, true);
        }
        if (latestState != null) {
            for (BattleEventState event : latestState.recentEvents()) {
                String text = "R" + event.roundNumber() + " T" + event.tick()
                    + " | " + event.type()
                    + "\n" + eventText(event);
                addEventLabel(text, false);
            }
        }
        if (eventRows.getChildren().isEmpty()) {
            addEventLabel("No recent server events.", true);
        }
    }

    private String eventText(BattleEventState event) {
        if (event.message() != null && !event.message().isBlank()) {
            return event.message();
        }
        String source = event.sourceCharacterName() == null ? "Unknown" : event.sourceCharacterName();
        String move = event.moveName() == null ? "" : " using " + event.moveName();
        String value = event.value() == null ? "" : " (" + event.value() + ")";
        return source + move + value;
    }

    private void addEventLabel(String text, boolean connectionNotice) {
        Label label = wrappedLabel(text, "small");
        label.setColor(connectionNotice
            ? new Color(0.720f, 0.800f, 0.950f, 1f)
            : Color.WHITE);
        eventRows.add(label).growX().left().pad(6f).row();
    }

    private void updateResult(MatchState state) {
        if (!terminal) {
            resultLabel.setText("");
            leaveButton.setText("LEAVE MATCH");
            return;
        }
        String result;
        if (state.winnerSide() == null) {
            result = "MATCH ENDED: DRAW";
        } else if (state.winnerSide() == setup.playerSide()) {
            result = "MATCH ENDED: YOU WIN";
        } else {
            PlayerState winner = state.player(state.winnerSide()).orElse(null);
            result = "MATCH ENDED: "
                + (winner == null ? "OPPONENT WINS" : winner.displayName() + " WINS");
        }
        if (state.endReason() != null && !state.endReason().isBlank()) {
            result += "  |  " + state.endReason();
        }
        resultLabel.setText(result);
        resultLabel.setColor(assets.editorSkin.get("text-ok", Color.class));
        leaveButton.setText("BACK TO MULTIPLAYER");
    }

    private void refreshActionState() {
        boolean canPlan = canPlan();
        for (MoveButtonBinding binding : moveButtons) {
            binding.button().setDisabled(!canPlan || !draft.canAdd(binding.move()));
        }
        undoButton.setDisabled(!canPlan || draft.placements().isEmpty());
        clearButton.setDisabled(!canPlan || draft.placements().isEmpty());
        submitButton.setDisabled(!canPlan);
        leaveButton.setDisabled(setup == null || leaving);
    }

    private boolean canPlan() {
        if (setup == null || latestState == null || terminal || leaving || submissionPending
            || connectionState != MultiplayerSession.ConnectionState.CONNECTED
            || latestState.status() != MatchStatus.ACTIVE
            || latestState.phase() != BattlePhase.PLANNING) {
            return false;
        }
        PlayerState local = localPlayer(latestState);
        return local != null && !local.planSubmitted();
    }

    private void setPlanMessage(String message, boolean error) {
        planStatusLabel.setText(message == null ? "" : message);
        planStatusLabel.setColor(error
            ? assets.editorSkin.get("text-error", Color.class)
            : new Color(0.720f, 0.800f, 0.950f, 1f));
    }

    private void setConnectionText() {
        String matchStatus = latestState == null ? "NO SNAPSHOT" : latestState.status().name();
        connectionLabel.setText("CONNECTION: " + connectionState + "  |  MATCH: " + matchStatus);
    }

    private void setError(String message) {
        errorLabel.setText(message == null ? "" : message);
        errorLabel.setColor(assets.editorSkin.get("text-error", Color.class));
    }

    private void addConnectionNotice(String message) {
        connectionNotices.addLast(message);
        while (connectionNotices.size() > MAX_CONNECTION_NOTICES) {
            connectionNotices.removeFirst();
        }
        rebuildEvents();
    }

    private void leaveMatch() {
        if (setup == null || leaving) {
            return;
        }
        leaving = true;
        preserveForRecovery = false;
        setPlanMessage(terminal
            ? "Returning to multiplayer..."
            : "Disconnecting. The server grace period determines any eventual forfeit.", false);
        detachListener();
        battleRun++;
        matchService.disconnect();
        game.showMultiplayerMenu();
    }

    private void detachListener() {
        if (serviceListener != null) {
            matchService.removeListener(serviceListener);
            serviceListener = null;
        }
    }

    private void postBattle(long expectedGeneration, long expectedRun, Runnable callback) {
        postIfCurrent(expectedGeneration, () -> {
            if (battleRun == expectedRun && setup != null) {
                callback.run();
            }
        });
    }

    private PlayerState localPlayer(MatchState state) {
        return state == null || setup == null
            ? null : state.player(setup.playerSide()).orElse(null);
    }

    private PlayerState opponentPlayer(MatchState state) {
        return state == null || setup == null
            ? null : state.player(opposite(setup.playerSide())).orElse(null);
    }

    private static PlayerSide opposite(PlayerSide side) {
        return side == PlayerSide.PLAYER_ONE ? PlayerSide.PLAYER_TWO : PlayerSide.PLAYER_ONE;
    }

    private static boolean isTerminal(MatchStatus status) {
        return status == MatchStatus.ENDED || status == MatchStatus.ABANDONED;
    }

    private static String trimDouble(double value) {
        if (value == Math.rint(value)) {
            return Long.toString(Math.round(value));
        }
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static String structuredError(ErrorResponse error) {
        if (error == null) {
            return "[COMMAND_REJECTED] The server rejected the plan.";
        }
        String details = error.details().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining(", "));
        return "[" + error.code() + "] " + error.message()
            + (details.isBlank() ? "" : " (" + details + ")");
    }

    private void layoutContent(float width) {
        contentLayout.clear();
        if (width < 940f) {
            contentLayout.add(planPanel).growX().padBottom(10f).row();
            contentLayout.add(eventsPanel).growX().row();
        } else {
            contentLayout.add(planPanel).grow().padRight(10f);
            contentLayout.add(eventsPanel).grow().width(Math.max(320f, width * 0.34f));
        }
    }

    private void layoutCombatants(float width) {
        if (localCard == null || opponentCard == null) {
            return;
        }
        combatants.clear();
        if (width < 720f) {
            combatants.add(localCard).growX().padBottom(8f).row();
            combatants.add(opponentCard).growX().row();
        } else {
            combatants.add(localCard).growX().padRight(5f);
            combatants.add(opponentCard).growX().padLeft(5f);
        }
    }

    @Override
    protected void onResized(int width, int height) {
        layoutContent(width);
        layoutCombatants(width);
    }

    @Override
    protected void onBackRequested() {
        leaveMatch();
    }

    @Override
    protected void onHidden() {
        detachListener();
        battleRun++;
        if (setup != null && !terminal && !leaving && !preserveForRecovery) {
            matchService.disconnect();
        }
    }

    @Override
    protected void onDisposed() {
        detachListener();
        battleRun++;
    }

    private final class BattleListener implements MultiplayerMatchService.Listener {
        private final long expectedGeneration;
        private final long expectedRun;
        private final String expectedMatchId;

        private BattleListener(long expectedGeneration, long expectedRun, String expectedMatchId) {
            this.expectedGeneration = expectedGeneration;
            this.expectedRun = expectedRun;
            this.expectedMatchId = expectedMatchId;
        }

        @Override
        public void onConnectionStateChanged(MultiplayerSession.ConnectionState state) {
            postBattle(expectedGeneration, expectedRun, () -> {
                connectionState = state;
                if (state == MultiplayerSession.ConnectionState.CONNECTED) {
                    errorLabel.setText("");
                    addConnectionNotice("Connected to the authoritative match stream.");
                }
                setConnectionText();
                refreshActionState();
            });
        }

        @Override
        public void onReconnecting(int attempt, Duration delay) {
            postBattle(expectedGeneration, expectedRun, () -> {
                connectionState = MultiplayerSession.ConnectionState.RECONNECTING;
                submissionPending = false;
                setConnectionText();
                setError("Connection interrupted. Retry " + attempt + " in "
                    + delay.toSeconds() + "s.");
                addConnectionNotice("Reconnecting attempt " + attempt
                    + " after " + delay.toSeconds() + " second(s).");
                refreshActionState();
            });
        }

        @Override
        public void onDisconnected(MatchWebSocketClient.DisconnectReason reason) {
            postBattle(expectedGeneration, expectedRun, () -> {
                connectionState = MultiplayerSession.ConnectionState.DISCONNECTED;
                submissionPending = false;
                setConnectionText();
                refreshActionState();
                if (reason == MatchWebSocketClient.DisconnectReason.RETRIES_EXHAUSTED
                    && !terminal
                    && (latestState == null || !isTerminal(latestState.status()))) {
                    String message = errorLabel.getText().toString().isBlank()
                        ? "Reconnect attempts were exhausted."
                        : errorLabel.getText().toString();
                    preserveForRecovery = true;
                    game.showMultiplayerDisconnected(message);
                }
            });
        }

        @Override
        public void onMatchState(MatchState state) {
            if (!expectedMatchId.equals(state.matchId())) {
                return;
            }
            postBattle(expectedGeneration, expectedRun, () -> applySnapshot(state));
        }

        @Override
        public void onPlayerConnectionChanged(SocketMessage message) {
            postBattle(expectedGeneration, expectedRun, () -> {
                boolean localEvent = message.playerSide() == setup.playerSide();
                String who = localEvent ? "You"
                    : (message.playerName() == null ? "Opponent" : message.playerName());
                if (message.type() == MessageType.PLAYER_DISCONNECTED) {
                    String deadline = message.disconnectDeadline() == null
                        ? "unknown" : DEADLINE_TIME.format(
                            Instant.ofEpochMilli(message.disconnectDeadline()));
                    addConnectionNotice(who + " disconnected. Grace deadline: " + deadline + ".");
                    if (!localEvent) {
                        setError("Opponent disconnected. Waiting for reconnect until " + deadline + ".");
                    }
                } else {
                    addConnectionNotice(who + " connected to the match.");
                    if (!localEvent) {
                        errorLabel.setText("");
                    }
                }
            });
        }

        @Override
        public void onCommandCompleted(MultiplayerMatchService.CommandOutcome outcome) {
            postBattle(expectedGeneration, expectedRun, () -> {
                submissionPending = false;
                if (outcome.status() == MultiplayerMatchService.CommandCompletionStatus.REJECTED) {
                    setPlanMessage(structuredError(outcome.error()), true);
                } else if (outcome.status()
                    == MultiplayerMatchService.CommandCompletionStatus.AUTHORITATIVE_STATE) {
                    setPlanMessage("Plan accepted by the server.", false);
                } else if (outcome.status()
                    != MultiplayerMatchService.CommandCompletionStatus.NOT_SENT) {
                    setPlanMessage("Plan command ended with status " + outcome.status() + ".", true);
                }
                refreshActionState();
            });
        }

        @Override
        public void onCommandRejected(String commandId, ErrorResponse error) {
            postBattle(expectedGeneration, expectedRun, () -> {
                submissionPending = false;
                setPlanMessage(structuredError(error), true);
                refreshActionState();
            });
        }

        @Override
        public void onMatchEnded(MatchState state) {
            postBattle(expectedGeneration, expectedRun, () -> {
                applySnapshot(state);
                addConnectionNotice("The server ended the match at state "
                    + state.stateVersion() + ".");
            });
        }

        @Override
        public void onError(String code, String userMessage, Throwable cause) {
            postBattle(expectedGeneration, expectedRun, () -> {
                if (cause != null) {
                    logFailure("match socket", cause);
                }
                setError("[" + code + "] " + userMessage);
                refreshActionState();
            });
        }
    }

    private record MoveButtonBinding(MoveState move, TextButton button) {
    }
}
