package com.jjktbf.graphics.ui.battle;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.graphics.audio.SoundCue;
import com.jjktbf.graphics.multiplayer.TargetListSupport;
import com.jjktbf.graphics.ui.profile.BattleUiLayout;
import com.jjktbf.graphics.ui.profile.UiProfile;
import com.jjktbf.model.character.coded.CodedAbilityState;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleTeamId;
import com.jjktbf.model.combat.CombatantId;
import com.jjktbf.model.combat.TeamBattlePlan;
import com.jjktbf.model.move.Move;
import com.jjktbf.multiplayer.protocol.ActionSegmentState;
import com.jjktbf.multiplayer.protocol.PlanPlacement;
import com.jjktbf.multiplayer.protocol.PlanState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Persistent per-combatant planning pages submitted as one atomic team plan. */
public final class TeamPlanningPanel {

    public record PageSpec(
        String actorId,
        String name,
        List<Move> moves,
        Map<String, Integer> ceCosts,
        int apBudget,
        int ceBudget,
        int maxCe,
        CodedAbilityState miraclesState,
        Integer maxActiveSummons,
        int activeSummonCount,
        Map<String, String> moveRestrictions,
        List<PlanningPanel.TargetOption> targets,
        PlanState restoredPlan,
        List<PlanningPanel.TargetOption> allies
    ) {
        public PageSpec(
            String actorId,
            String name,
            List<Move> moves,
            Map<String, Integer> ceCosts,
            int apBudget,
            int ceBudget,
            int maxCe,
            CodedAbilityState miraclesState,
            Map<String, String> moveRestrictions,
            List<PlanningPanel.TargetOption> targets,
            PlanState restoredPlan
        ) {
            this(actorId, name, moves, ceCosts, apBudget, ceBudget, maxCe, miraclesState,
                null, 0, moveRestrictions, targets, restoredPlan, List.of());
        }
    }

    private record Page(String name, PlanningPanel panel) { }

    private final BattleTeamId teamId;
    private final int gridLength;
    private final BattleUiAssets ui;
    private final List<Page> pages = new ArrayList<>();
    private final Rectangle previousBounds = new Rectangle();
    private final Rectangle nextBounds = new Rectangle();
    private final Rectangle pageLabelBounds = new Rectangle();
    private BattleUiLayout.Planner layout = new BattleUiLayout.Planner();
    private int activePage;
    private float screenWidth;
    private float screenHeight;
    private float textGeometryScale = 1f;
    private boolean windowsTextGeometry;
    private boolean submitted;
    private Runnable onConfirm = () -> { };
    private Consumer<SoundCue> soundPlayer = cue -> { };

    public TeamPlanningPanel(
        int gridLength,
        List<BattleCombatant> controlled,
        BattleState state,
        BattleUiAssets ui,
        float screenWidth,
        float screenHeight
    ) {
        if (controlled == null || controlled.isEmpty()) {
            throw new IllegalArgumentException("At least one controlled combatant is required");
        }
        this.teamId = controlled.get(0).getTeamId();
        this.gridLength = gridLength;
        this.ui = ui;
        for (BattleCombatant actor : controlled) {
            PlanningPanel panel = new PlanningPanel(
                gridLength,
                actor,
                state.activeEnemiesOf(actor),
                ui,
                screenWidth,
                screenHeight);
            panel.setBattleState(state);
            panel.setAllyTargets(state.activeAlliesOf(actor));
            addPage(actor.getCharacter().getName(), panel);
        }
        resize(screenWidth, screenHeight);
    }

    public TeamPlanningPanel(
        BattleTeamId teamId,
        int gridLength,
        List<PageSpec> specs,
        BattleUiAssets ui,
        float screenWidth,
        float screenHeight
    ) {
        if (specs == null || specs.isEmpty()) {
            throw new IllegalArgumentException("At least one planning page is required");
        }
        this.teamId = teamId;
        this.gridLength = gridLength;
        this.ui = ui;
        for (PageSpec spec : specs) {
            PlanningPanel panel = new PlanningPanel(
                gridLength,
                spec.actorId(),
                spec.targets(),
                spec.moves(),
                spec.ceCosts(),
                spec.apBudget(),
                spec.ceBudget(),
                spec.maxCe(),
                spec.miraclesState(),
                spec.maxActiveSummons(),
                spec.activeSummonCount(),
                ui,
                screenWidth,
                screenHeight);
            panel.setMoveRestrictions(spec.moveRestrictions());
            panel.setAllyOptions(spec.allies());
            restorePlan(panel, spec);
            addPage(spec.name(), panel);
        }
        resize(screenWidth, screenHeight);
    }

    private void addPage(String name, PlanningPanel panel) {
        panel.setActorName(name);
        panel.setAllowManualUnlock(true);
        panel.setOnConfirm(this::pageLocked);
        panel.setSoundPlayer(soundPlayer);
        pages.add(new Page(name, panel));
    }

    private static void restorePlan(PlanningPanel panel, PageSpec spec) {
        PlanState restored = spec.restoredPlan();
        if (restored == null) return;
        Map<String, Move> moves = new LinkedHashMap<>();
        for (Move move : spec.moves()) moves.put(move.getId(), move);
        List<ActionSegmentState> segments = new ArrayList<>(restored.queuedSegments());
        segments.addAll(restored.resolvedSegments());
        for (ActionSegmentState segment : segments) {
            Move move = moves.get(segment.moveId());
            if (move != null) {
                panel.restorePlacement(
                    move, segment.startTick(), segment.ceCost(), TargetListSupport.targetIds(segment));
            }
        }
    }

    public void setOnConfirm(Runnable onConfirm) {
        this.onConfirm = onConfirm == null ? () -> { } : onConfirm;
    }

    public void setSoundPlayer(Consumer<SoundCue> soundPlayer) {
        this.soundPlayer = soundPlayer == null ? cue -> { } : soundPlayer;
        for (Page page : pages) page.panel().setSoundPlayer(this.soundPlayer);
    }

    private void pageLocked() {
        if (submitted || pages.stream().anyMatch(page -> !page.panel().isConfirmed())) return;
        submitted = true;
        for (Page page : pages) page.panel().setAllowManualUnlock(false);
        onConfirm.run();
    }

    public TeamBattlePlan getTeamPlan() {
        TeamBattlePlan teamPlan = new TeamBattlePlan(teamId, gridLength);
        for (Page page : pages) {
            teamPlan.put(new CombatantId(page.panel().getActorId()), page.panel().getPlan());
        }
        return teamPlan;
    }

    public List<PlanPlacement> getPlacements() {
        return pages.stream().flatMap(page -> page.panel().getPlacements().stream()).toList();
    }

    public void lock() {
        for (Page page : pages) {
            page.panel().setAllowManualUnlock(false);
            page.panel().lock();
        }
        submitted = true;
    }

    public void unlock() {
        for (Page page : pages) {
            page.panel().setAllowManualUnlock(true);
            page.panel().unlock();
        }
        submitted = false;
    }

    public void resize(float width, float height) {
        screenWidth = width;
        screenHeight = height;
        for (Page page : pages) page.panel().resize(width, height);
        layoutNavigation(width, height);
    }

    public void setLayout(BattleUiLayout battleLayout) {
        if (battleLayout == null) return;
        layout = battleLayout.copy().planner;
        windowsTextGeometry = battleLayout.storedProfile() == UiProfile.WINDOWS;
        textGeometryScale = windowsTextGeometry ? layout.textGeometryScale : 1f;
        for (Page page : pages) {
            page.panel().setLayout(battleLayout);
            page.panel().setTeamNavigationHeader(windowsTextGeometry && pages.size() > 1);
        }
        layoutNavigation(screenWidth, screenHeight);
    }

    private void layoutNavigation(float width, float height) {
        if (!windowsTextGeometry) {
            previousBounds.set(14f, height - 52f, 30f, 30f);
            nextBounds.set(50f, height - 52f, 30f, 30f);
            pageLabelBounds.set(nextBounds.x + nextBounds.width + 10f,
                nextBounds.y, Float.MAX_VALUE, nextBounds.height);
            return;
        }

        float margin = Math.min(layout.marginMax,
            Math.max(layout.marginMin, width * layout.marginFraction));
        boolean compact = width < layout.compactWidthThreshold;
        float headerHeight = compact ? layout.compactHeaderHeight : layout.headerHeight;
        float headerY = height - margin - headerHeight;
        float buttonSize = scaled(30f);
        float buttonY = headerY + (headerHeight - buttonSize) / 2f;
        previousBounds.set(margin + scaled(18f), buttonY, buttonSize, buttonSize);
        nextBounds.set(previousBounds.x + buttonSize + scaled(6f),
            buttonY, buttonSize, buttonSize);

        float headerWidth = width - margin * 2f;
        float statX = compact
            ? margin + scaled(12f)
            : margin + Math.min(scaled(340f), headerWidth * 0.42f);
        float lockX = compact
            ? margin + headerWidth
                - layout.compactLockButtonRightInset - layout.compactLockButtonWidth
            : margin + headerWidth - layout.lockButtonHorizontalInset - layout.lockButtonWidth;
        float labelX = nextBounds.x + nextBounds.width + scaled(10f);
        float labelRight = Math.min(statX, lockX) - scaled(10f);
        pageLabelBounds.set(labelX, buttonY,
            Math.max(1f, labelRight - labelX), buttonSize);
    }

    private float scaled(float value) {
        return value * textGeometryScale;
    }

    public void draw(Batch batch, BitmapFont font, BitmapFont titleFont, BitmapFont statFont) {
        active().draw(batch, font, titleFont, statFont);
        if (pages.size() <= 1) return;
        batch.begin();
        batch.setColor(new Color(0.38f, 0.41f, 0.46f, 1f));
        batch.draw(ui.pixel, previousBounds.x, previousBounds.y,
            previousBounds.width, previousBounds.height);
        batch.draw(ui.pixel, nextBounds.x, nextBounds.y, nextBounds.width, nextBounds.height);
        batch.setColor(Color.WHITE);
        font.setColor(Color.WHITE);
        if (!windowsTextGeometry) {
            font.draw(batch, "<", previousBounds.x + 10f, previousBounds.y + 21f);
            font.draw(batch, ">", nextBounds.x + 10f, nextBounds.y + 21f);
            font.draw(batch, pages.get(activePage).name() + "  " + (activePage + 1)
                + "/" + pages.size(), nextBounds.x + nextBounds.width + 10f,
                nextBounds.y + 21f);
            batch.end();
            return;
        }
        font.draw(batch, "<",
            previousBounds.x + scaled(10f), previousBounds.y + scaled(21f));
        font.draw(batch, ">",
            nextBounds.x + scaled(10f), nextBounds.y + scaled(21f));
        String pageLabel = pages.get(activePage).name() + "  " + (activePage + 1)
            + "/" + pages.size();
        font.draw(batch, ellipsize(font, pageLabel, pageLabelBounds.width),
            pageLabelBounds.x, pageLabelBounds.y + scaled(21f));
        batch.end();
    }

    private static String ellipsize(BitmapFont font, String value, float maximumWidth) {
        if (new GlyphLayout(font, value).width <= maximumWidth) return value;
        String suffix = "...";
        if (new GlyphLayout(font, suffix).width > maximumWidth) return "";
        String result = value;
        while (result.length() > 1
            && new GlyphLayout(font, result + suffix).width > maximumWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result + suffix;
    }

    record HeaderRegions(
        Rectangle previous,
        Rectangle next,
        Rectangle pageLabel,
        boolean genericTitleVisible
    ) { }

    HeaderRegions headerRegions() {
        return new HeaderRegions(
            new Rectangle(previousBounds),
            new Rectangle(nextBounds),
            new Rectangle(pageLabelBounds),
            active().isHeaderTitleVisible());
    }

    public InputAdapter inputProcessor() {
        return new TeamPlanningInputProcessor();
    }

    public int pageCount() { return pages.size(); }
    public int activePageIndex() { return activePage; }
    public String activePageName() { return pages.get(activePage).name(); }
    public PlanningPanel activePlanningPanel() { return active(); }

    public void previousPage() {
        if (pages.size() > 1) activePage = (activePage - 1 + pages.size()) % pages.size();
    }

    public void nextPage() {
        if (pages.size() > 1) activePage = (activePage + 1) % pages.size();
    }

    private PlanningPanel active() {
        return pages.get(activePage).panel();
    }

    private final class TeamPlanningInputProcessor extends InputAdapter {
        @Override public boolean keyDown(int keycode) {
            if (keycode == Input.Keys.LEFT) {
                previousPage();
                return true;
            }
            if (keycode == Input.Keys.RIGHT) {
                nextPage();
                return true;
            }
            return active().inputProcessor().keyDown(keycode);
        }

        @Override public boolean touchDown(int x, int y, int pointer, int button) {
            float plannerY = screenHeight - y;
            if (button == Input.Buttons.LEFT && pages.size() > 1) {
                if (previousBounds.contains(x, plannerY)) {
                    previousPage();
                    soundPlayer.accept(SoundCue.UI_CONFIRM);
                    return true;
                }
                if (nextBounds.contains(x, plannerY)) {
                    nextPage();
                    soundPlayer.accept(SoundCue.UI_CONFIRM);
                    return true;
                }
            }
            return active().inputProcessor().touchDown(x, y, pointer, button);
        }

        @Override public boolean touchDragged(int x, int y, int pointer) {
            return active().inputProcessor().touchDragged(x, y, pointer);
        }

        @Override public boolean touchUp(int x, int y, int pointer, int button) {
            return active().inputProcessor().touchUp(x, y, pointer, button);
        }

        @Override public boolean mouseMoved(int x, int y) {
            return active().inputProcessor().mouseMoved(x, y);
        }

        @Override public boolean scrolled(float amountX, float amountY) {
            return active().inputProcessor().scrolled(amountX, amountY);
        }
    }
}
