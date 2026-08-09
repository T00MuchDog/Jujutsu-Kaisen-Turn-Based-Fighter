package com.jjktbf.multiplayer.protocol;

import java.util.List;

/** Canonical move fields needed to display and construct plan intent. */
public record MoveState(
    String moveId,
    String name,
    String description,
    String category,
    List<String> tags,
    PlanBoard board,
    int basePower,
    List<HitComponentState> hitComponents,
    double baseAccuracy,
    boolean neverMiss,
    int apCost,
    int unleashPoint,
    boolean hasCeCost,
    int baseCeCost,
    int effectiveCeCost,
    int minCeCost,
    int maxCeCost,
    int moveCap,
    boolean available,
    String restrictionReason,
    String summonCharacterId,
    List<String> summonedCharacterIds
) {
    public MoveState {
        tags = tags == null ? List.of() : List.copyOf(tags);
        hitComponents = hitComponents == null ? List.of() : List.copyOf(hitComponents);
        summonedCharacterIds = summonedCharacterIds == null
            ? List.of() : List.copyOf(summonedCharacterIds);
    }

    /** Source-compatible constructor for protocol-v9 callers. */
    public MoveState(
        String moveId,
        String name,
        String description,
        String category,
        List<String> tags,
        PlanBoard board,
        int basePower,
        List<HitComponentState> hitComponents,
        double baseAccuracy,
        boolean neverMiss,
        int apCost,
        int unleashPoint,
        boolean hasCeCost,
        int baseCeCost,
        int effectiveCeCost,
        int minCeCost,
        int maxCeCost,
        int moveCap,
        boolean available,
        String restrictionReason
    ) {
        this(moveId, name, description, category, tags, board, basePower, hitComponents,
            baseAccuracy, neverMiss, apCost, unleashPoint, hasCeCost, baseCeCost,
            effectiveCeCost, minCeCost, maxCeCost, moveCap, available, restrictionReason,
            null, List.of());
    }

    /** Source-compatible constructor for protocol-v7 callers with hit components. */
    public MoveState(
        String moveId,
        String name,
        String description,
        String category,
        List<String> tags,
        PlanBoard board,
        int basePower,
        List<HitComponentState> hitComponents,
        double baseAccuracy,
        boolean neverMiss,
        int apCost,
        int unleashPoint,
        boolean hasCeCost,
        int baseCeCost,
        int effectiveCeCost,
        int minCeCost,
        int maxCeCost,
        boolean available,
        String restrictionReason
    ) {
        this(moveId, name, description, category, tags, board, basePower, hitComponents,
            baseAccuracy, neverMiss, apCost, unleashPoint, hasCeCost, baseCeCost,
            effectiveCeCost, minCeCost, maxCeCost, 0, available, restrictionReason,
            null, List.of());
    }

    /** Source-compatible constructor for protocol-v6 callers. */
    public MoveState(
        String moveId,
        String name,
        String description,
        String category,
        List<String> tags,
        PlanBoard board,
        int basePower,
        double baseAccuracy,
        boolean neverMiss,
        int apCost,
        int unleashPoint,
        boolean hasCeCost,
        int baseCeCost,
        int effectiveCeCost,
        int minCeCost,
        int maxCeCost,
        boolean available,
        String restrictionReason
    ) {
        this(moveId, name, description, category, tags, board, basePower, List.of(),
            baseAccuracy, neverMiss, apCost, unleashPoint, hasCeCost, baseCeCost,
            effectiveCeCost, minCeCost, maxCeCost, 0, available, restrictionReason,
            null, List.of());
    }
}
