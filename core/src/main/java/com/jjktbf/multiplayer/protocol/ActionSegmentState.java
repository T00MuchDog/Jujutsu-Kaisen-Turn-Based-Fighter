package com.jjktbf.multiplayer.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Immutable action placement and its current resolution status. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionSegmentState(
    String segmentId,
    String moveId,
    String moveName,
    PlanBoard board,
    int startTick,
    int endTick,
    int fireTick,
    int apCost,
    int ceCost,
    ActionSegmentStatus status,
    Integer resolvedTick
) {
}
