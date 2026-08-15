package com.jjktbf.graphics.ui.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Serializable, presentation-only metrics consumed by the production battle UI.
 * Values deliberately describe reusable geometry rather than a character or move.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BattleUiLayout {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public String profile;
    public int referenceWidth;
    public int referenceHeight;
    public Execution execution = new Execution();
    public Planner planner = new Planner();

    public BattleUiLayout() {
    }

    private BattleUiLayout(BattleUiLayout source) {
        schemaVersion = source.schemaVersion;
        profile = source.profile;
        referenceWidth = source.referenceWidth;
        referenceHeight = source.referenceHeight;
        execution = new Execution(source.execution);
        planner = new Planner(source.planner);
    }

    public BattleUiLayout copy() {
        return new BattleUiLayout(this);
    }

    public static BattleUiLayout defaults(UiProfile profile) {
        BattleUiLayout layout = new BattleUiLayout();
        layout.profile = profile.name();
        layout.referenceWidth = profile.defaultReferenceWidth();
        layout.referenceHeight = profile.defaultReferenceHeight();
        return layout;
    }

    public void validate(UiProfile expectedProfile) {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                "Unsupported battle UI layout schema " + schemaVersion);
        }
        UiProfile storedProfile = UiProfile.parse(profile);
        if (storedProfile != expectedProfile) {
            throw new IllegalArgumentException(
                "Layout for " + storedProfile + " cannot be loaded as " + expectedProfile);
        }
        requireRange("referenceWidth", referenceWidth, 640f, 7680f);
        requireRange("referenceHeight", referenceHeight, 480f, 4320f);
        if (referenceWidth != expectedProfile.defaultReferenceWidth()
            || referenceHeight != expectedProfile.defaultReferenceHeight()) {
            throw new IllegalArgumentException(expectedProfile + " reference resolution must be "
                + expectedProfile.defaultReferenceWidth() + " x "
                + expectedProfile.defaultReferenceHeight());
        }
        if (execution == null) throw new IllegalArgumentException("execution layout is required");
        if (planner == null) throw new IllegalArgumentException("planner layout is required");
        execution.validate();
        planner.validate();
    }

    private static void requireRange(String name, float value, float minimum, float maximum) {
        if (!Float.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                name + " must be between " + minimum + " and " + maximum);
        }
    }

    private static void requireOrdered(
        String minimumName,
        float minimum,
        String maximumName,
        float maximum
    ) {
        if (minimum > maximum) {
            throw new IllegalArgumentException(
                minimumName + " cannot exceed " + maximumName);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Execution {
        public float outerMarginFraction = 0.035f;
        public float outerMarginMin = 16f;
        public float outerMarginMax = 32f;
        public float logHeightFraction = 0.22f;
        public float logHeightMin = 100f;
        public float logHeightMax = 145f;
        public float logLineSpacing = 1.7f;
        public float fieldLogGap = 12f;

        public float hudWidthFraction = 0.40f;
        public float hudWidthMin = 150f;
        public float hudWidthMax = 430f;
        public float hudScale = 1.25f;
        public float multiCombatantHudWidthScale = 0.50f;
        public float hudHeightFraction = 0.29f;
        public float hudHeightMin = 82f;
        public float hudHeightMax = 108f;
        public float playerHudYOffsetFraction = 0.08f;
        public float hudCenterGap = 12f;
        public float hudSideShiftFraction = 0.035f;
        public float hudSideShiftMax = 70f;
        public float hudHorizontalNudgeFraction = 0.018f;
        public float hudHorizontalNudgeMax = 30f;
        public float hudColumnGapFraction = 0.05f;
        public float hudColumnGapMin = 10f;
        public float hudRowGapFraction = 0.07f;
        public float hudRowGapMin = 8f;

        public float enemyPlateHeightFraction = 0.84f;
        public float enemyPlateWidthFraction = 0.38f;
        public float playerPlateHeightFraction = 1.08f;
        public float playerPlateWidthFraction = 0.46f;
        public float sideCenterInsetFraction = 0.22f;
        public float enemySpriteHeightFraction = 0.50f;
        public float enemySpriteWidthFraction = 0.20f;
        public float playerSpriteHeightFraction = 0.58f;
        public float playerSpriteWidthFraction = 0.23f;
        public float fighterDrop = 12f;
        public float fighterDropFraction = 0.12f;
        public float playerSpriteBottomFraction = 0.12f;
        public float plateDropFraction = 0.045f;
        public float spriteFootFraction = 0.13f;
        public float enemyPlateLiftFraction = 0.07f;
        public float plateTextureYOffsetFraction = 0.016f;
        public float expandedPlayerCenterNudgeFraction = 0.018f;
        public float expandedPlayerCenterNudgeMax = 28f;
        public float threeVsThreePlayerShift = 48f;

        public float meterHudGap = 14f;
        public float miraclesWidthFraction = 0.11f;
        public float ratioWidthFraction = 0.12f;
        public float nextRoundWidthFraction = 0.20f;
        public float nextRoundWidthMin = 150f;
        public float nextRoundWidthMax = 210f;
        public float nextRoundHeightMax = 54f;
        public float nextRoundVerticalPadding = 24f;
        public float nextRoundInset = 14f;

        public Execution() {
        }

        private Execution(Execution source) {
            outerMarginFraction = source.outerMarginFraction;
            outerMarginMin = source.outerMarginMin;
            outerMarginMax = source.outerMarginMax;
            logHeightFraction = source.logHeightFraction;
            logHeightMin = source.logHeightMin;
            logHeightMax = source.logHeightMax;
            logLineSpacing = source.logLineSpacing;
            fieldLogGap = source.fieldLogGap;
            hudWidthFraction = source.hudWidthFraction;
            hudWidthMin = source.hudWidthMin;
            hudWidthMax = source.hudWidthMax;
            hudScale = source.hudScale;
            multiCombatantHudWidthScale = source.multiCombatantHudWidthScale;
            hudHeightFraction = source.hudHeightFraction;
            hudHeightMin = source.hudHeightMin;
            hudHeightMax = source.hudHeightMax;
            playerHudYOffsetFraction = source.playerHudYOffsetFraction;
            hudCenterGap = source.hudCenterGap;
            hudSideShiftFraction = source.hudSideShiftFraction;
            hudSideShiftMax = source.hudSideShiftMax;
            hudHorizontalNudgeFraction = source.hudHorizontalNudgeFraction;
            hudHorizontalNudgeMax = source.hudHorizontalNudgeMax;
            hudColumnGapFraction = source.hudColumnGapFraction;
            hudColumnGapMin = source.hudColumnGapMin;
            hudRowGapFraction = source.hudRowGapFraction;
            hudRowGapMin = source.hudRowGapMin;
            enemyPlateHeightFraction = source.enemyPlateHeightFraction;
            enemyPlateWidthFraction = source.enemyPlateWidthFraction;
            playerPlateHeightFraction = source.playerPlateHeightFraction;
            playerPlateWidthFraction = source.playerPlateWidthFraction;
            sideCenterInsetFraction = source.sideCenterInsetFraction;
            enemySpriteHeightFraction = source.enemySpriteHeightFraction;
            enemySpriteWidthFraction = source.enemySpriteWidthFraction;
            playerSpriteHeightFraction = source.playerSpriteHeightFraction;
            playerSpriteWidthFraction = source.playerSpriteWidthFraction;
            fighterDrop = source.fighterDrop;
            fighterDropFraction = source.fighterDropFraction;
            playerSpriteBottomFraction = source.playerSpriteBottomFraction;
            plateDropFraction = source.plateDropFraction;
            spriteFootFraction = source.spriteFootFraction;
            enemyPlateLiftFraction = source.enemyPlateLiftFraction;
            plateTextureYOffsetFraction = source.plateTextureYOffsetFraction;
            expandedPlayerCenterNudgeFraction = source.expandedPlayerCenterNudgeFraction;
            expandedPlayerCenterNudgeMax = source.expandedPlayerCenterNudgeMax;
            threeVsThreePlayerShift = source.threeVsThreePlayerShift;
            meterHudGap = source.meterHudGap;
            miraclesWidthFraction = source.miraclesWidthFraction;
            ratioWidthFraction = source.ratioWidthFraction;
            nextRoundWidthFraction = source.nextRoundWidthFraction;
            nextRoundWidthMin = source.nextRoundWidthMin;
            nextRoundWidthMax = source.nextRoundWidthMax;
            nextRoundHeightMax = source.nextRoundHeightMax;
            nextRoundVerticalPadding = source.nextRoundVerticalPadding;
            nextRoundInset = source.nextRoundInset;
        }

        private void validate() {
            requireRange("execution.outerMarginFraction", outerMarginFraction, 0f, 0.25f);
            requireRange("execution.outerMarginMin", outerMarginMin, 0f, 300f);
            requireRange("execution.outerMarginMax", outerMarginMax, 0f, 500f);
            requireOrdered("execution.outerMarginMin", outerMarginMin,
                "execution.outerMarginMax", outerMarginMax);
            requireRange("execution.logHeightFraction", logHeightFraction, 0.05f, 0.60f);
            requireRange("execution.logHeightMin", logHeightMin, 20f, 1000f);
            requireRange("execution.logHeightMax", logHeightMax, 20f, 1400f);
            requireOrdered("execution.logHeightMin", logHeightMin,
                "execution.logHeightMax", logHeightMax);
            requireRange("execution.logLineSpacing", logLineSpacing, 0.5f, 4f);
            requireRange("execution.fieldLogGap", fieldLogGap, 0f, 300f);
            requireRange("execution.hudWidthFraction", hudWidthFraction, 0.05f, 0.90f);
            requireRange("execution.hudWidthMin", hudWidthMin, 20f, 1500f);
            requireRange("execution.hudWidthMax", hudWidthMax, 20f, 2500f);
            requireOrdered("execution.hudWidthMin", hudWidthMin,
                "execution.hudWidthMax", hudWidthMax);
            requireRange("execution.hudScale", hudScale, 0.25f, 3f);
            requireRange("execution.multiCombatantHudWidthScale",
                multiCombatantHudWidthScale, 0.20f, 1f);
            requireRange("execution.hudHeightFraction", hudHeightFraction, 0.05f, 0.80f);
            requireRange("execution.hudHeightMin", hudHeightMin, 20f, 800f);
            requireRange("execution.hudHeightMax", hudHeightMax, 20f, 1200f);
            requireOrdered("execution.hudHeightMin", hudHeightMin,
                "execution.hudHeightMax", hudHeightMax);
            requireRange("execution.playerHudYOffsetFraction", playerHudYOffsetFraction, 0f, 0.80f);
            requireRange("execution.hudCenterGap", hudCenterGap, 0f, 500f);
            requireRange("execution.hudSideShiftFraction", hudSideShiftFraction, 0f, 0.30f);
            requireRange("execution.hudSideShiftMax", hudSideShiftMax, 0f, 600f);
            requireRange("execution.hudHorizontalNudgeFraction", hudHorizontalNudgeFraction, 0f, 0.30f);
            requireRange("execution.hudHorizontalNudgeMax", hudHorizontalNudgeMax, 0f, 600f);
            requireRange("execution.hudColumnGapFraction", hudColumnGapFraction, 0f, 0.50f);
            requireRange("execution.hudColumnGapMin", hudColumnGapMin, 0f, 300f);
            requireRange("execution.hudRowGapFraction", hudRowGapFraction, 0f, 0.50f);
            requireRange("execution.hudRowGapMin", hudRowGapMin, 0f, 300f);
            requireRange("execution.enemyPlateHeightFraction", enemyPlateHeightFraction, 0.05f, 3f);
            requireRange("execution.enemyPlateWidthFraction", enemyPlateWidthFraction, 0.05f, 2f);
            requireRange("execution.playerPlateHeightFraction", playerPlateHeightFraction, 0.05f, 3f);
            requireRange("execution.playerPlateWidthFraction", playerPlateWidthFraction, 0.05f, 2f);
            requireRange("execution.sideCenterInsetFraction", sideCenterInsetFraction, 0f, 0.50f);
            requireRange("execution.enemySpriteHeightFraction", enemySpriteHeightFraction, 0.05f, 2f);
            requireRange("execution.enemySpriteWidthFraction", enemySpriteWidthFraction, 0.05f, 1f);
            requireRange("execution.playerSpriteHeightFraction", playerSpriteHeightFraction, 0.05f, 2f);
            requireRange("execution.playerSpriteWidthFraction", playerSpriteWidthFraction, 0.05f, 1f);
            requireRange("execution.fighterDrop", fighterDrop, -500f, 1000f);
            requireRange("execution.fighterDropFraction", fighterDropFraction, -0.50f, 1f);
            requireRange("execution.playerSpriteBottomFraction", playerSpriteBottomFraction, -0.50f, 1f);
            requireRange("execution.plateDropFraction", plateDropFraction, -0.50f, 1f);
            requireRange("execution.spriteFootFraction", spriteFootFraction, -0.50f, 1f);
            requireRange("execution.enemyPlateLiftFraction", enemyPlateLiftFraction, -0.50f, 1f);
            requireRange("execution.plateTextureYOffsetFraction", plateTextureYOffsetFraction, -0.50f, 0.50f);
            requireRange("execution.expandedPlayerCenterNudgeFraction",
                expandedPlayerCenterNudgeFraction, -0.25f, 0.50f);
            requireRange("execution.expandedPlayerCenterNudgeMax", expandedPlayerCenterNudgeMax, 0f, 800f);
            requireRange("execution.threeVsThreePlayerShift", threeVsThreePlayerShift, -1000f, 1000f);
            requireRange("execution.meterHudGap", meterHudGap, 0f, 500f);
            requireRange("execution.miraclesWidthFraction", miraclesWidthFraction, 0.01f, 0.50f);
            requireRange("execution.ratioWidthFraction", ratioWidthFraction, 0.01f, 0.50f);
            requireRange("execution.nextRoundWidthFraction", nextRoundWidthFraction, 0.02f, 0.80f);
            requireRange("execution.nextRoundWidthMin", nextRoundWidthMin, 20f, 1200f);
            requireRange("execution.nextRoundWidthMax", nextRoundWidthMax, 20f, 1800f);
            requireOrdered("execution.nextRoundWidthMin", nextRoundWidthMin,
                "execution.nextRoundWidthMax", nextRoundWidthMax);
            requireRange("execution.nextRoundHeightMax", nextRoundHeightMax, 10f, 500f);
            requireRange("execution.nextRoundVerticalPadding", nextRoundVerticalPadding, 0f, 500f);
            requireRange("execution.nextRoundInset", nextRoundInset, 0f, 500f);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Planner {
        public float marginFraction = 0.045f;
        public float marginMin = 18f;
        public float marginMax = 34f;
        public float compactWidthThreshold = 700f;
        public float headerHeight = 58f;
        public float compactHeaderHeight = 118f;
        public float lockButtonWidth = 142f;
        public float lockButtonHorizontalInset = 14f;
        public float lockButtonVerticalInset = 10f;
        public float compactLockButtonWidth = 92f;
        public float compactLockButtonHeight = 28f;
        public float compactLockButtonRightInset = 12f;
        public float compactLockButtonTopInset = 12f;
        public float paletteBoardGap = 26f;
        public float emptyActorNameReservedHeight = 24f;
        public float actorNameReservedHeight = 56f;
        public float timelineLabelWidthFraction = 0.12f;
        public float timelineLabelWidthMin = 108f;
        public float timelineLabelWidthMax = 150f;
        public float boardGap = 18f;
        public float compactBoardGap = 30f;
        public float miraclesTopGap = 16f;
        public float miraclesBottomGap = 16f;
        public float compactMiraclesBottomGap = 28f;

        public Planner() {
        }

        private Planner(Planner source) {
            marginFraction = source.marginFraction;
            marginMin = source.marginMin;
            marginMax = source.marginMax;
            compactWidthThreshold = source.compactWidthThreshold;
            headerHeight = source.headerHeight;
            compactHeaderHeight = source.compactHeaderHeight;
            lockButtonWidth = source.lockButtonWidth;
            lockButtonHorizontalInset = source.lockButtonHorizontalInset;
            lockButtonVerticalInset = source.lockButtonVerticalInset;
            compactLockButtonWidth = source.compactLockButtonWidth;
            compactLockButtonHeight = source.compactLockButtonHeight;
            compactLockButtonRightInset = source.compactLockButtonRightInset;
            compactLockButtonTopInset = source.compactLockButtonTopInset;
            paletteBoardGap = source.paletteBoardGap;
            emptyActorNameReservedHeight = source.emptyActorNameReservedHeight;
            actorNameReservedHeight = source.actorNameReservedHeight;
            timelineLabelWidthFraction = source.timelineLabelWidthFraction;
            timelineLabelWidthMin = source.timelineLabelWidthMin;
            timelineLabelWidthMax = source.timelineLabelWidthMax;
            boardGap = source.boardGap;
            compactBoardGap = source.compactBoardGap;
            miraclesTopGap = source.miraclesTopGap;
            miraclesBottomGap = source.miraclesBottomGap;
            compactMiraclesBottomGap = source.compactMiraclesBottomGap;
        }

        private void validate() {
            requireRange("planner.marginFraction", marginFraction, 0f, 0.25f);
            requireRange("planner.marginMin", marginMin, 0f, 300f);
            requireRange("planner.marginMax", marginMax, 0f, 600f);
            requireOrdered("planner.marginMin", marginMin, "planner.marginMax", marginMax);
            requireRange("planner.compactWidthThreshold", compactWidthThreshold, 320f, 2500f);
            requireRange("planner.headerHeight", headerHeight, 20f, 500f);
            requireRange("planner.compactHeaderHeight", compactHeaderHeight, 20f, 800f);
            requireRange("planner.lockButtonWidth", lockButtonWidth, 20f, 600f);
            requireRange("planner.lockButtonHorizontalInset", lockButtonHorizontalInset, 0f, 300f);
            requireRange("planner.lockButtonVerticalInset", lockButtonVerticalInset, 0f, 300f);
            requireRange("planner.compactLockButtonWidth", compactLockButtonWidth, 20f, 600f);
            requireRange("planner.compactLockButtonHeight", compactLockButtonHeight, 10f, 300f);
            requireRange("planner.compactLockButtonRightInset", compactLockButtonRightInset, 0f, 300f);
            requireRange("planner.compactLockButtonTopInset", compactLockButtonTopInset, 0f, 300f);
            requireRange("planner.paletteBoardGap", paletteBoardGap, 0f, 500f);
            requireRange("planner.emptyActorNameReservedHeight", emptyActorNameReservedHeight, 0f, 500f);
            requireRange("planner.actorNameReservedHeight", actorNameReservedHeight, 0f, 800f);
            requireRange("planner.timelineLabelWidthFraction", timelineLabelWidthFraction, 0f, 0.50f);
            requireRange("planner.timelineLabelWidthMin", timelineLabelWidthMin, 0f, 600f);
            requireRange("planner.timelineLabelWidthMax", timelineLabelWidthMax, 0f, 1000f);
            requireOrdered("planner.timelineLabelWidthMin", timelineLabelWidthMin,
                "planner.timelineLabelWidthMax", timelineLabelWidthMax);
            requireRange("planner.boardGap", boardGap, 0f, 500f);
            requireRange("planner.compactBoardGap", compactBoardGap, 0f, 500f);
            requireRange("planner.miraclesTopGap", miraclesTopGap, 0f, 500f);
            requireRange("planner.miraclesBottomGap", miraclesBottomGap, 0f, 500f);
            requireRange("planner.compactMiraclesBottomGap", compactMiraclesBottomGap, 0f, 500f);
        }
    }
}
