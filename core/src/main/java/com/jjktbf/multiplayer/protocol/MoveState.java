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
    public MoveState {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
