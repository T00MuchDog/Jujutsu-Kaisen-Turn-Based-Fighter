package com.jjktbf.multiplayer.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Immutable action placement and its current resolution status.
 *
 * <p>{@code actorId} is the combatant instance id that owns this segment;
 * {@code targetId} is the selected target combatant instance id for hostile
 * single-target segments (null otherwise). Segment ids include the actor
 * combatant id so duplicate summons of the same definition are distinguishable.
 */
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
    Integer resolvedTick,
    String actorId,
    String targetId
) {
    /** Legacy constructor without actor/target ids (1v1 compatibility). */
    public ActionSegmentState(
        String segmentId, String moveId, String moveName, PlanBoard board,
        int startTick, int endTick, int fireTick, int apCost, int ceCost,
        ActionSegmentStatus status, Integer resolvedTick
    ) {
        this(segmentId, moveId, moveName, board, startTick, endTick, fireTick,
            apCost, ceCost, status, resolvedTick, null, null);
    }
}
