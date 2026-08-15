package com.jjktbf.graphics.screens.editors;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.screens.BattleScreen;
import com.jjktbf.graphics.ui.HoverScrollStage;
import com.jjktbf.graphics.ui.battle.BattleUiPreviewFactory;
import com.jjktbf.graphics.ui.battle.BattleUiViewport;
import com.jjktbf.graphics.ui.profile.BattleUiLayout;
import com.jjktbf.graphics.ui.profile.BattleUiLayoutStore;
import com.jjktbf.graphics.ui.profile.UiProfile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/** One cross-platform, authoring-only editor around the production battle renderer. */
public final class BattleUiEditorScreen implements Screen {

    private enum PreviewMode {
        EXECUTION,
        PLANNING
    }

    @FunctionalInterface
    private interface LayoutGetter {
        float get(BattleUiLayout layout);
    }

    @FunctionalInterface
    private interface LayoutSetter {
        void set(BattleUiLayout layout, float value);
    }

    private record LayoutProperty(
        String section,
        String label,
        float minimum,
        float maximum,
        float step,
        LayoutGetter getter,
        LayoutSetter setter
    ) { }

    private static final List<LayoutProperty> EXECUTION_PROPERTIES = List.of(
        property("Canvas", "Outer margin fraction", 0f, 0.15f, 0.001f,
            value -> value.execution.outerMarginFraction,
            (value, changed) -> value.execution.outerMarginFraction = changed),
        property("Canvas", "Outer margin minimum", 0f, 100f, 1f,
            value -> value.execution.outerMarginMin,
            (value, changed) -> value.execution.outerMarginMin = changed),
        property("Canvas", "Outer margin maximum", 0f, 160f, 1f,
            value -> value.execution.outerMarginMax,
            (value, changed) -> value.execution.outerMarginMax = changed),
        property("Battle Log", "Log height fraction", 0.05f, 0.5f, 0.005f,
            value -> value.execution.logHeightFraction,
            (value, changed) -> value.execution.logHeightFraction = changed),
        property("Battle Log", "Log minimum height", 20f, 500f, 1f,
            value -> value.execution.logHeightMin,
            (value, changed) -> value.execution.logHeightMin = changed),
        property("Battle Log", "Log maximum height", 20f, 700f, 1f,
            value -> value.execution.logHeightMax,
            (value, changed) -> value.execution.logHeightMax = changed),
        property("Battle Log", "Line spacing", 0.5f, 3.5f, 0.05f,
            value -> value.execution.logLineSpacing,
            (value, changed) -> value.execution.logLineSpacing = changed),
        property("Battle Log", "Battlefield gap", 0f, 150f, 1f,
            value -> value.execution.fieldLogGap,
            (value, changed) -> value.execution.fieldLogGap = changed),
        property("Combatant HUD", "HUD width fraction", 0.1f, 0.7f, 0.005f,
            value -> value.execution.hudWidthFraction,
            (value, changed) -> value.execution.hudWidthFraction = changed),
        property("Combatant HUD", "HUD minimum width", 50f, 700f, 1f,
            value -> value.execution.hudWidthMin,
            (value, changed) -> value.execution.hudWidthMin = changed),
        property("Combatant HUD", "HUD maximum width", 100f, 1200f, 1f,
            value -> value.execution.hudWidthMax,
            (value, changed) -> value.execution.hudWidthMax = changed),
        property("Combatant HUD", "HUD scale", 0.5f, 2.5f, 0.01f,
            value -> value.execution.hudScale,
            (value, changed) -> value.execution.hudScale = changed),
        property("Combatant HUD", "Team HUD width scale", 0.2f, 1f, 0.01f,
            value -> value.execution.multiCombatantHudWidthScale,
            (value, changed) -> value.execution.multiCombatantHudWidthScale = changed),
        property("Combatant HUD", "HUD height fraction", 0.05f, 0.6f, 0.005f,
            value -> value.execution.hudHeightFraction,
            (value, changed) -> value.execution.hudHeightFraction = changed),
        property("Combatant HUD", "HUD minimum height", 30f, 300f, 1f,
            value -> value.execution.hudHeightMin,
            (value, changed) -> value.execution.hudHeightMin = changed),
        property("Combatant HUD", "HUD maximum height", 30f, 500f, 1f,
            value -> value.execution.hudHeightMax,
            (value, changed) -> value.execution.hudHeightMax = changed),
        property("Combatant HUD", "Player HUD Y fraction", 0f, 0.5f, 0.005f,
            value -> value.execution.playerHudYOffsetFraction,
            (value, changed) -> value.execution.playerHudYOffsetFraction = changed),
        property("Combatant HUD", "HUD center gap", 0f, 300f, 1f,
            value -> value.execution.hudCenterGap,
            (value, changed) -> value.execution.hudCenterGap = changed),
        property("Combatant HUD", "Side shift fraction", 0f, 0.2f, 0.002f,
            value -> value.execution.hudSideShiftFraction,
            (value, changed) -> value.execution.hudSideShiftFraction = changed),
        property("Combatant HUD", "Side shift maximum", 0f, 300f, 1f,
            value -> value.execution.hudSideShiftMax,
            (value, changed) -> value.execution.hudSideShiftMax = changed),
        property("Combatant HUD", "Horizontal nudge fraction", 0f, 0.15f, 0.001f,
            value -> value.execution.hudHorizontalNudgeFraction,
            (value, changed) -> value.execution.hudHorizontalNudgeFraction = changed),
        property("Combatant HUD", "Horizontal nudge maximum", 0f, 200f, 1f,
            value -> value.execution.hudHorizontalNudgeMax,
            (value, changed) -> value.execution.hudHorizontalNudgeMax = changed),
        property("Combatant HUD", "Column gap fraction", 0f, 0.3f, 0.005f,
            value -> value.execution.hudColumnGapFraction,
            (value, changed) -> value.execution.hudColumnGapFraction = changed),
        property("Combatant HUD", "Column gap minimum", 0f, 200f, 1f,
            value -> value.execution.hudColumnGapMin,
            (value, changed) -> value.execution.hudColumnGapMin = changed),
        property("Combatant HUD", "Row gap fraction", 0f, 0.3f, 0.005f,
            value -> value.execution.hudRowGapFraction,
            (value, changed) -> value.execution.hudRowGapFraction = changed),
        property("Combatant HUD", "Row gap minimum", 0f, 200f, 1f,
            value -> value.execution.hudRowGapMin,
            (value, changed) -> value.execution.hudRowGapMin = changed),
        property("Battlefield", "Side center inset", 0.05f, 0.45f, 0.005f,
            value -> value.execution.sideCenterInsetFraction,
            (value, changed) -> value.execution.sideCenterInsetFraction = changed),
        property("Battlefield", "Enemy plate height fraction", 0.1f, 2f, 0.01f,
            value -> value.execution.enemyPlateHeightFraction,
            (value, changed) -> value.execution.enemyPlateHeightFraction = changed),
        property("Battlefield", "Enemy plate width fraction", 0.1f, 1f, 0.01f,
            value -> value.execution.enemyPlateWidthFraction,
            (value, changed) -> value.execution.enemyPlateWidthFraction = changed),
        property("Battlefield", "Player plate height fraction", 0.1f, 2.5f, 0.01f,
            value -> value.execution.playerPlateHeightFraction,
            (value, changed) -> value.execution.playerPlateHeightFraction = changed),
        property("Battlefield", "Player plate width fraction", 0.1f, 1f, 0.01f,
            value -> value.execution.playerPlateWidthFraction,
            (value, changed) -> value.execution.playerPlateWidthFraction = changed),
        property("Battlefield", "Enemy sprite height fraction", 0.1f, 1.5f, 0.01f,
            value -> value.execution.enemySpriteHeightFraction,
            (value, changed) -> value.execution.enemySpriteHeightFraction = changed),
        property("Battlefield", "Enemy sprite width fraction", 0.05f, 0.8f, 0.005f,
            value -> value.execution.enemySpriteWidthFraction,
            (value, changed) -> value.execution.enemySpriteWidthFraction = changed),
        property("Battlefield", "Player sprite height fraction", 0.1f, 1.5f, 0.01f,
            value -> value.execution.playerSpriteHeightFraction,
            (value, changed) -> value.execution.playerSpriteHeightFraction = changed),
        property("Battlefield", "Player sprite width fraction", 0.05f, 0.8f, 0.005f,
            value -> value.execution.playerSpriteWidthFraction,
            (value, changed) -> value.execution.playerSpriteWidthFraction = changed),
        property("Battlefield", "Fighter drop pixels", -200f, 500f, 1f,
            value -> value.execution.fighterDrop,
            (value, changed) -> value.execution.fighterDrop = changed),
        property("Battlefield", "Fighter drop fraction", -0.2f, 0.6f, 0.005f,
            value -> value.execution.fighterDropFraction,
            (value, changed) -> value.execution.fighterDropFraction = changed),
        property("Battlefield", "Player bottom fraction", -0.2f, 0.6f, 0.005f,
            value -> value.execution.playerSpriteBottomFraction,
            (value, changed) -> value.execution.playerSpriteBottomFraction = changed),
        property("Battlefield", "Plate drop fraction", -0.2f, 0.4f, 0.005f,
            value -> value.execution.plateDropFraction,
            (value, changed) -> value.execution.plateDropFraction = changed),
        property("Battlefield", "Sprite foot fraction", -0.1f, 0.5f, 0.005f,
            value -> value.execution.spriteFootFraction,
            (value, changed) -> value.execution.spriteFootFraction = changed),
        property("Battlefield", "Enemy plate lift fraction", -0.2f, 0.5f, 0.005f,
            value -> value.execution.enemyPlateLiftFraction,
            (value, changed) -> value.execution.enemyPlateLiftFraction = changed),
        property("Battlefield", "Plate texture Y offset", -0.2f, 0.2f, 0.002f,
            value -> value.execution.plateTextureYOffsetFraction,
            (value, changed) -> value.execution.plateTextureYOffsetFraction = changed),
        property("Battlefield", "Expanded center nudge fraction", -0.1f, 0.3f, 0.002f,
            value -> value.execution.expandedPlayerCenterNudgeFraction,
            (value, changed) -> value.execution.expandedPlayerCenterNudgeFraction = changed),
        property("Battlefield", "Expanded center nudge maximum", 0f, 400f, 1f,
            value -> value.execution.expandedPlayerCenterNudgeMax,
            (value, changed) -> value.execution.expandedPlayerCenterNudgeMax = changed),
        property("Battlefield", "3v3 player shift", -400f, 400f, 1f,
            value -> value.execution.threeVsThreePlayerShift,
            (value, changed) -> value.execution.threeVsThreePlayerShift = changed),
        property("Meters", "Meter-to-HUD gap", 0f, 250f, 1f,
            value -> value.execution.meterHudGap,
            (value, changed) -> value.execution.meterHudGap = changed),
        property("Meters", "Miracles width fraction", 0.01f, 0.4f, 0.005f,
            value -> value.execution.miraclesWidthFraction,
            (value, changed) -> value.execution.miraclesWidthFraction = changed),
        property("Meters", "Ratio width fraction", 0.01f, 0.4f, 0.005f,
            value -> value.execution.ratioWidthFraction,
            (value, changed) -> value.execution.ratioWidthFraction = changed),
        property("Battle Controls", "Next-round width fraction", 0.05f, 0.6f, 0.005f,
            value -> value.execution.nextRoundWidthFraction,
            (value, changed) -> value.execution.nextRoundWidthFraction = changed),
        property("Battle Controls", "Next-round minimum width", 40f, 700f, 1f,
            value -> value.execution.nextRoundWidthMin,
            (value, changed) -> value.execution.nextRoundWidthMin = changed),
        property("Battle Controls", "Next-round maximum width", 40f, 1000f, 1f,
            value -> value.execution.nextRoundWidthMax,
            (value, changed) -> value.execution.nextRoundWidthMax = changed),
        property("Battle Controls", "Next-round maximum height", 20f, 250f, 1f,
            value -> value.execution.nextRoundHeightMax,
            (value, changed) -> value.execution.nextRoundHeightMax = changed),
        property("Battle Controls", "Next-round vertical padding", 0f, 250f, 1f,
            value -> value.execution.nextRoundVerticalPadding,
            (value, changed) -> value.execution.nextRoundVerticalPadding = changed),
        property("Battle Controls", "Control inset", 0f, 150f, 1f,
            value -> value.execution.nextRoundInset,
            (value, changed) -> value.execution.nextRoundInset = changed)
    );

    private static final List<LayoutProperty> PLANNER_PROPERTIES = List.of(
        property("Canvas", "Margin fraction", 0f, 0.2f, 0.001f,
            value -> value.planner.marginFraction,
            (value, changed) -> value.planner.marginFraction = changed),
        property("Canvas", "Margin minimum", 0f, 150f, 1f,
            value -> value.planner.marginMin,
            (value, changed) -> value.planner.marginMin = changed),
        property("Canvas", "Margin maximum", 0f, 250f, 1f,
            value -> value.planner.marginMax,
            (value, changed) -> value.planner.marginMax = changed),
        property("Responsive Seam", "Compact width threshold", 320f, 3200f, 10f,
            value -> value.planner.compactWidthThreshold,
            (value, changed) -> value.planner.compactWidthThreshold = changed),
        property("Header", "Header height", 30f, 240f, 1f,
            value -> value.planner.headerHeight,
            (value, changed) -> value.planner.headerHeight = changed),
        property("Header", "Compact header height", 40f, 350f, 1f,
            value -> value.planner.compactHeaderHeight,
            (value, changed) -> value.planner.compactHeaderHeight = changed),
        property("Header", "Lock button width", 40f, 400f, 1f,
            value -> value.planner.lockButtonWidth,
            (value, changed) -> value.planner.lockButtonWidth = changed),
        property("Header", "Lock horizontal inset", 0f, 150f, 1f,
            value -> value.planner.lockButtonHorizontalInset,
            (value, changed) -> value.planner.lockButtonHorizontalInset = changed),
        property("Header", "Lock vertical inset", 0f, 100f, 1f,
            value -> value.planner.lockButtonVerticalInset,
            (value, changed) -> value.planner.lockButtonVerticalInset = changed),
        property("Header", "Compact lock width", 40f, 300f, 1f,
            value -> value.planner.compactLockButtonWidth,
            (value, changed) -> value.planner.compactLockButtonWidth = changed),
        property("Header", "Compact lock height", 20f, 160f, 1f,
            value -> value.planner.compactLockButtonHeight,
            (value, changed) -> value.planner.compactLockButtonHeight = changed),
        property("Header", "Compact lock right inset", 0f, 150f, 1f,
            value -> value.planner.compactLockButtonRightInset,
            (value, changed) -> value.planner.compactLockButtonRightInset = changed),
        property("Header", "Compact lock top inset", 0f, 150f, 1f,
            value -> value.planner.compactLockButtonTopInset,
            (value, changed) -> value.planner.compactLockButtonTopInset = changed),
        property("Move Palette", "Palette-to-board gap", 0f, 250f, 1f,
            value -> value.planner.paletteBoardGap,
            (value, changed) -> value.planner.paletteBoardGap = changed),
        property("Timelines", "Label width fraction", 0f, 0.4f, 0.005f,
            value -> value.planner.timelineLabelWidthFraction,
            (value, changed) -> value.planner.timelineLabelWidthFraction = changed),
        property("Timelines", "Label minimum width", 0f, 400f, 1f,
            value -> value.planner.timelineLabelWidthMin,
            (value, changed) -> value.planner.timelineLabelWidthMin = changed),
        property("Timelines", "Label maximum width", 0f, 600f, 1f,
            value -> value.planner.timelineLabelWidthMax,
            (value, changed) -> value.planner.timelineLabelWidthMax = changed),
        property("Timelines", "Timeline board gap", 0f, 200f, 1f,
            value -> value.planner.boardGap,
            (value, changed) -> value.planner.boardGap = changed),
        property("Timelines", "Compact board gap", 0f, 250f, 1f,
            value -> value.planner.compactBoardGap,
            (value, changed) -> value.planner.compactBoardGap = changed),
        property("Actor Label", "Actor-name reserved height", 0f, 250f, 1f,
            value -> value.planner.actorNameReservedHeight,
            (value, changed) -> value.planner.actorNameReservedHeight = changed),
        property("Actor Label", "Empty-name reserved height", 0f, 250f, 1f,
            value -> value.planner.emptyActorNameReservedHeight,
            (value, changed) -> value.planner.emptyActorNameReservedHeight = changed),
        property("Meters", "Miracles top gap", 0f, 150f, 1f,
            value -> value.planner.miraclesTopGap,
            (value, changed) -> value.planner.miraclesTopGap = changed),
        property("Meters", "Miracles bottom gap", 0f, 150f, 1f,
            value -> value.planner.miraclesBottomGap,
            (value, changed) -> value.planner.miraclesBottomGap = changed),
        property("Meters", "Compact miracles bottom gap", 0f, 200f, 1f,
            value -> value.planner.compactMiraclesBottomGap,
            (value, changed) -> value.planner.compactMiraclesBottomGap = changed)
    );

    private final AssetLoader assets;
    private final BattleUiLayoutStore layoutStore;
    private final HoverScrollStage stage;
    private final Table root;
    private final Table controlPanel;
    private final Table propertyRows;
    private final EnumMap<UiProfile, BattleUiLayout> drafts = new EnumMap<>(UiProfile.class);
    private final EnumMap<UiProfile, Boolean> dirtyProfiles = new EnumMap<>(UiProfile.class);
    private final BattleScreen preview;
    private final Label environmentLabel;
    private final Label coordinateLabel;
    private final Label componentLabel;
    private final Label statusLabel;
    private final TextButton saveButton;
    private final CheckBox productionBoundsToggle;
    private final CheckBox coordinateGridToggle;
    private final CheckBox sceneDebugToggle;

    private UiProfile activeProfile;
    private PreviewMode previewMode = PreviewMode.EXECUTION;
    private BattleUiViewport previewViewport;
    private String selectedComponent = "Click the preview to select a component.";
    private boolean controlsUpdating;
    private boolean disposed;

    public BattleUiEditorScreen(
        JJKGame game,
        AssetLoader assets,
        BattleUiLayoutStore layoutStore,
        UiProfile initialProfile,
        BattleUiLayout initialLayout,
        String initialLoadWarning
    ) {
        this.assets = assets;
        this.layoutStore = layoutStore;
        this.activeProfile = initialProfile;
        drafts.put(initialProfile, initialLayout.copy());
        dirtyProfiles.put(initialProfile, false);

        BattleUiPreviewFactory.PreviewBattle previewData;
        try {
            previewData = BattleUiPreviewFactory.loadRepresentativeBattle();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not build the battle UI preview", failure);
        }
        List<Texture> playerSprites = previewData.playerSpriteAssets().stream()
            .map(path -> assets.characterBattleSprite(path, false, assets.playerSprite))
            .toList();
        List<Texture> enemySprites = previewData.enemySpriteAssets().stream()
            .map(path -> assets.characterBattleSprite(path, true, assets.enemySprite))
            .toList();
        preview = new BattleScreen(game, assets, initialLayout);
        preview.prepareEditorPreview(previewData.state(), playerSprites, enemySprites);

        stage = new HoverScrollStage(new ScreenViewport());
        root = new Table();
        root.setFillParent(true);
        root.top().right();
        root.setTouchable(Touchable.childrenOnly);
        stage.addActor(root);

        controlPanel = new Table(assets.editorSkin);
        controlPanel.setBackground(assets.editorSkin.getDrawable("window"));
        controlPanel.pad(12f);
        controlPanel.top();
        root.add(controlPanel).width(510f).growY().pad(12f);

        Label title = new Label("BATTLE UI EDITOR", assets.editorSkin, "title");
        controlPanel.add(title).left().growX();
        controlPanel.row();

        Table selectors = new Table(assets.editorSkin);
        SelectBox<String> profileSelector = new SelectBox<>(assets.editorSkin);
        profileSelector.setItems("MAC", "WINDOWS");
        profileSelector.setSelected(initialProfile.name());
        SelectBox<String> modeSelector = new SelectBox<>(assets.editorSkin);
        modeSelector.setItems("EXECUTION", "PLANNING");
        selectors.add(new Label("PROFILE", assets.editorSkin, "small")).left().padRight(6f);
        selectors.add(profileSelector).width(125f).padRight(14f);
        selectors.add(new Label("VIEW", assets.editorSkin, "small")).left().padRight(6f);
        selectors.add(modeSelector).width(130f).growX();
        controlPanel.add(selectors).growX().padTop(8f);
        controlPanel.row();

        Table previewScenarios = new Table(assets.editorSkin);
        SelectBox<String> formationSelector = new SelectBox<>(assets.editorSkin);
        formationSelector.setItems("1V1", "2V2", "3V3", "4V4");
        formationSelector.setSelected("4V4");
        SelectBox<String> meterSelector = new SelectBox<>(assets.editorSkin);
        meterSelector.setItems("MIRACLES", "RATIO", "NONE");
        meterSelector.setSelected("MIRACLES");
        previewScenarios.add(new Label("FORMATION", assets.editorSkin, "small"))
            .left().padRight(6f);
        previewScenarios.add(formationSelector).width(105f).padRight(14f);
        previewScenarios.add(new Label("METER", assets.editorSkin, "small"))
            .left().padRight(6f);
        previewScenarios.add(meterSelector).width(130f).growX();
        controlPanel.add(previewScenarios).growX().padTop(7f);
        controlPanel.row();

        Table debugControls = new Table(assets.editorSkin);
        productionBoundsToggle = new CheckBox(" UI bounds", assets.editorSkin);
        coordinateGridToggle = new CheckBox(" Grid", assets.editorSkin);
        sceneDebugToggle = new CheckBox(" Scene2D", assets.editorSkin);
        debugControls.add(productionBoundsToggle).left().padRight(10f);
        debugControls.add(coordinateGridToggle).left().padRight(10f);
        debugControls.add(sceneDebugToggle).left().growX();
        controlPanel.add(debugControls).growX().padTop(7f);
        controlPanel.row();

        propertyRows = new Table(assets.editorSkin);
        propertyRows.top().left();
        ScrollPane propertyScroll = new ScrollPane(propertyRows, assets.editorSkin);
        propertyScroll.setFadeScrollBars(false);
        propertyScroll.setScrollingDisabled(true, false);
        controlPanel.add(propertyScroll).grow().padTop(8f).minHeight(220f);
        controlPanel.row();

        Table actions = new Table(assets.editorSkin);
        saveButton = new TextButton("SAVE", assets.editorSkin, "primary");
        TextButton reloadButton = new TextButton("RELOAD", assets.editorSkin);
        TextButton defaultsButton = new TextButton("FACTORY DEFAULTS", assets.editorSkin);
        actions.add(saveButton).width(125f).height(38f).padRight(6f);
        actions.add(reloadButton).width(110f).height(38f).padRight(6f);
        actions.add(defaultsButton).growX().height(38f);
        controlPanel.add(actions).growX().padTop(8f);
        controlPanel.row();

        environmentLabel = new Label("", assets.editorSkin, "small-white");
        environmentLabel.setWrap(true);
        environmentLabel.setAlignment(Align.left);
        coordinateLabel = new Label("", assets.editorSkin, "small-white");
        componentLabel = new Label(selectedComponent, assets.editorSkin, "small-white");
        componentLabel.setWrap(true);
        statusLabel = new Label("F1 hides controls. F2 toggles production bounds.",
            assets.editorSkin, "small-white");
        statusLabel.setWrap(true);
        controlPanel.add(environmentLabel).growX().left().padTop(8f);
        controlPanel.row();
        controlPanel.add(coordinateLabel).growX().left().padTop(3f);
        controlPanel.row();
        controlPanel.add(componentLabel).growX().left().padTop(3f);
        controlPanel.row();
        controlPanel.add(statusLabel).growX().left().padTop(5f);

        profileSelector.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                if (!controlsUpdating) switchProfile(UiProfile.parse(profileSelector.getSelected()));
            }
        });
        modeSelector.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                if (controlsUpdating) return;
                previewMode = PreviewMode.valueOf(modeSelector.getSelected());
                preview.setEditorPreviewPlanning(previewMode == PreviewMode.PLANNING);
                rebuildPropertyRows();
            }
        });
        formationSelector.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                String selected = formationSelector.getSelected();
                preview.setEditorPreviewTeamSize(Integer.parseInt(selected.substring(0, 1)));
            }
        });
        meterSelector.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                preview.setEditorPreviewMeter(
                    BattleScreen.EditorMeterPreview.valueOf(meterSelector.getSelected()));
            }
        });
        sceneDebugToggle.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                stage.setDebugAll(sceneDebugToggle.isChecked());
            }
        });
        saveButton.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                saveActiveProfile();
            }
        });
        reloadButton.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                reloadActiveProfile();
            }
        });
        defaultsButton.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                BattleUiLayout reset = BattleUiLayout.defaults(activeProfile);
                drafts.put(activeProfile, reset);
                dirtyProfiles.put(activeProfile, true);
                preview.setUiLayout(reset);
                rebuildPropertyRows();
                updateSaveButton();
                setStatus("Factory defaults loaded into the " + activeProfile
                    + " draft. Save to persist them.", false);
            }
        });

        rebuildPropertyRows();
        updateSaveButton();
        if (initialLoadWarning != null && !initialLoadWarning.isBlank()) {
            dirtyProfiles.put(initialProfile, true);
            updateSaveButton();
            setStatus(initialLoadWarning, true);
        }
    }

    private static LayoutProperty property(
        String section,
        String label,
        float minimum,
        float maximum,
        float step,
        LayoutGetter getter,
        LayoutSetter setter
    ) {
        return new LayoutProperty(section, label, minimum, maximum, step, getter, setter);
    }

    private BattleUiLayout activeLayout() {
        return drafts.get(activeProfile);
    }

    private void switchProfile(UiProfile profile) {
        if (profile == activeProfile) return;
        String recoveryMessage = null;
        try {
            if (!drafts.containsKey(profile)) {
                try {
                    drafts.put(profile, layoutStore.load(profile));
                    dirtyProfiles.put(profile, false);
                } catch (IOException | IllegalArgumentException failure) {
                    drafts.put(profile, BattleUiLayout.defaults(profile));
                    dirtyProfiles.put(profile, true);
                    recoveryMessage = "Could not load " + profile + ": "
                        + failure.getMessage() + ". Factory defaults are active; save to repair it.";
                }
            }
            activeProfile = profile;
            preview.setUiLayout(activeLayout());
            preview.setEditorPreviewPlanning(previewMode == PreviewMode.PLANNING);
            rebuildPropertyRows();
            updateSaveButton();
            if (recoveryMessage == null) {
                setStatus("Editing " + profile + ". Its draft and save file are isolated.", false);
            } else {
                setStatus(recoveryMessage, true);
            }
        } catch (RuntimeException failure) {
            setStatus("Could not activate " + profile + ": " + failure.getMessage(), true);
        }
    }

    private void rebuildPropertyRows() {
        controlsUpdating = true;
        try {
            propertyRows.clearChildren();
            List<LayoutProperty> properties = previewMode == PreviewMode.EXECUTION
                ? EXECUTION_PROPERTIES : PLANNER_PROPERTIES;
            String previousSection = null;
            for (LayoutProperty property : properties) {
                if (!property.section().equals(previousSection)) {
                    Label section = new Label(property.section().toUpperCase(Locale.ROOT),
                        assets.editorSkin, "small-white");
                    propertyRows.add(section).left().colspan(3).growX().padTop(10f).padBottom(3f);
                    propertyRows.row();
                    previousSection = property.section();
                }
                addPropertyRow(property);
            }
        } finally {
            controlsUpdating = false;
        }
    }

    private void addPropertyRow(LayoutProperty property) {
        Label label = new Label(property.label(), assets.editorSkin, "small");
        Slider slider = new Slider(
            property.minimum(), property.maximum(), property.step(), false, assets.editorSkin);
        TextField valueField = new TextField(format(property.getter().get(activeLayout())),
            assets.editorSkin);
        slider.setValue(clamp(
            property.getter().get(activeLayout()), property.minimum(), property.maximum()));

        slider.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                if (controlsUpdating) return;
                controlsUpdating = true;
                valueField.setText(format(slider.getValue()));
                controlsUpdating = false;
                applyProperty(property, slider.getValue());
            }
        });
        valueField.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                if (controlsUpdating) return;
                try {
                    float parsed = Float.parseFloat(valueField.getText().trim());
                    if (!Float.isFinite(parsed)
                        || parsed < property.minimum() || parsed > property.maximum()) {
                        return;
                    }
                    controlsUpdating = true;
                    slider.setValue(parsed);
                    controlsUpdating = false;
                    applyProperty(property, parsed);
                } catch (NumberFormatException ignored) {
                    // Partial text entry is allowed; the last valid value remains live.
                }
            }
        });

        propertyRows.add(label).width(185f).left().padRight(6f).padBottom(4f);
        propertyRows.add(slider).growX().minWidth(155f).padRight(6f).padBottom(4f);
        propertyRows.add(valueField).width(90f).padBottom(4f);
        propertyRows.row();
    }

    private void applyProperty(LayoutProperty property, float value) {
        property.setter().set(activeLayout(), value);
        dirtyProfiles.put(activeProfile, true);
        preview.setUiLayout(activeLayout());
        updateSaveButton();
    }

    private void saveActiveProfile() {
        try {
            Path target = layoutStore.save(activeProfile, activeLayout());
            dirtyProfiles.put(activeProfile, false);
            updateSaveButton();
            setStatus("Saved " + activeProfile + " to " + target, false);
        } catch (IOException | IllegalArgumentException failure) {
            setStatus("Save failed: " + failure.getMessage(), true);
        }
    }

    private void reloadActiveProfile() {
        try {
            BattleUiLayout loaded = layoutStore.load(activeProfile);
            drafts.put(activeProfile, loaded);
            dirtyProfiles.put(activeProfile, false);
            preview.setUiLayout(loaded);
            preview.setEditorPreviewPlanning(previewMode == PreviewMode.PLANNING);
            rebuildPropertyRows();
            updateSaveButton();
            setStatus("Reloaded " + activeProfile + " from disk.", false);
        } catch (IOException | IllegalArgumentException failure) {
            BattleUiLayout defaults = BattleUiLayout.defaults(activeProfile);
            drafts.put(activeProfile, defaults);
            dirtyProfiles.put(activeProfile, true);
            preview.setUiLayout(defaults);
            preview.setEditorPreviewPlanning(previewMode == PreviewMode.PLANNING);
            rebuildPropertyRows();
            updateSaveButton();
            setStatus("Reload failed: " + failure.getMessage()
                + ". Factory defaults are active; save to repair the file.", true);
        }
    }

    private void updateSaveButton() {
        boolean dirty = Boolean.TRUE.equals(dirtyProfiles.get(activeProfile));
        saveButton.setText("SAVE " + activeProfile + (dirty ? " *" : ""));
    }

    private void setStatus(String message, boolean error) {
        statusLabel.setText(message);
        statusLabel.setColor(error
            ? assets.editorSkin.getColor("text-error")
            : assets.editorSkin.getColor("text-light"));
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(stage);
    }

    @Override
    public void render(float delta) {
        if (Gdx.input.isKeyJustPressed(Input.Keys.F1)) {
            controlPanel.setVisible(!controlPanel.isVisible());
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.F2)) {
            productionBoundsToggle.setChecked(!productionBoundsToggle.isChecked());
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            Gdx.app.exit();
            return;
        }

        int screenWidth = Math.max(1, Gdx.graphics.getWidth());
        int screenHeight = Math.max(1, Gdx.graphics.getHeight());
        BattleUiLayout layout = activeLayout();
        previewViewport = BattleUiViewport.fit(
            layout.referenceWidth,
            layout.referenceHeight,
            screenWidth,
            screenHeight,
            Math.max(1, Gdx.graphics.getBackBufferWidth()),
            Math.max(1, Gdx.graphics.getBackBufferHeight()));

        Gdx.gl.glViewport(0, 0,
            Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        Gdx.gl.glClearColor(0.025f, 0.032f, 0.05f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        clearLogicalCanvasToProductionColor();
        preview.renderEditorPreview(
            delta,
            previewViewport,
            productionBoundsToggle.isChecked(),
            coordinateGridToggle.isChecked());
        BattleUiViewport.restoreHostViewport();

        stage.act(Math.min(delta, 1f / 15f));
        updateDiagnostics(screenWidth, screenHeight);
        if (controlPanel.isVisible()) stage.draw();
    }

    private void clearLogicalCanvasToProductionColor() {
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glScissor(
            previewViewport.backBufferX(),
            previewViewport.backBufferY(),
            previewViewport.backBufferWidth(),
            previewViewport.backBufferHeight());
        Gdx.gl.glClearColor(0.804f, 0.863f, 0.980f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
    }

    private void updateDiagnostics(int screenWidth, int screenHeight) {
        float hostX = Gdx.input.getX();
        float hostY = screenHeight - Gdx.input.getY();
        BattleUiViewport.LogicalPoint point = previewViewport.toLogical(hostX, hostY);
        if (point == null) {
            coordinateLabel.setText("Cursor: outside logical preview");
        } else {
            coordinateLabel.setText(String.format(Locale.ROOT,
                "Cursor: %.1f, %.1f", point.x(), point.y()));
            String underCursor = preview.describeUiAt(point.x(), point.y());
            Actor editorActor = controlPanel.isVisible()
                ? stage.hit(hostX, hostY, true) : null;
            if (editorActor == null && Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)) {
                selectedComponent = underCursor;
            }
        }
        componentLabel.setText("Selected: " + selectedComponent);
        float previewScale = previewViewport.screenWidth() / activeLayout().referenceWidth;
        environmentLabel.setText(String.format(Locale.ROOT,
            "%s  |  logical %d x %d  |  preview %.3fx\nwindow %d x %d  |  back buffer %d x %d",
            activeProfile,
            activeLayout().referenceWidth,
            activeLayout().referenceHeight,
            previewScale,
            screenWidth,
            screenHeight,
            Gdx.graphics.getBackBufferWidth(),
            Gdx.graphics.getBackBufferHeight()));
    }

    @Override
    public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
    }

    @Override public void pause() { }
    @Override public void resume() { }

    @Override
    public void hide() {
        if (Gdx.input.getInputProcessor() == stage) Gdx.input.setInputProcessor(null);
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        preview.dispose();
        stage.dispose();
    }

    private static String format(float value) {
        if (Math.abs(value - Math.round(value)) < 0.0001f) {
            return Integer.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.3f", value)
            .replaceAll("0+$", "")
            .replaceAll("\\.$", "");
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
