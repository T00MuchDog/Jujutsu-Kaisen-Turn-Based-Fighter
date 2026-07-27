package com.jjktbf.model.character;

/** Shared stat ranges used by the character editor's tier scale and BST rating. */
public enum StatTier {

    GRADE_4("Grade 4", 10, 30),
    GRADE_3("Grade 3", 30, 60),
    GRADE_2("Grade 2", 60, 80),
    SEMI_GRADE_1("Semi-Grade 1", 80, 100),
    GRADE_1("Grade 1", 100, 150),
    HEAVY_HITTER("Heavy Hitter", 150, 200),
    SPECIAL_GRADE("Special Grade", 200, 250),
    CALAMITY("Calamity", 250, 300);

    private static final int BASE_STAT_COUNT = 10;

    private final String displayName;
    private final int minimumStat;
    private final int maximumStat;

    StatTier(String displayName, int minimumStat, int maximumStat) {
        this.displayName = displayName;
        this.minimumStat = minimumStat;
        this.maximumStat = maximumStat;
    }

    public String displayName() {
        return displayName;
    }

    public int minimumStat() {
        return minimumStat;
    }

    public int maximumStat() {
        return maximumStat;
    }

    public int maximumBaseStatTotal() {
        return maximumStat * BASE_STAT_COUNT;
    }

    /** Classify a ten-stat BST against each tier's maximum, clamping at Calamity. */
    public static StatTier forBaseStatTotal(int baseStatTotal) {
        for (StatTier tier : values()) {
            if (baseStatTotal <= tier.maximumBaseStatTotal()) return tier;
        }
        return CALAMITY;
    }
}
