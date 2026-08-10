package com.jjktbf.multiplayer.protocol;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Immutable action placement and its current resolution status.
 *
 * <p>{@code actorId} is the combatant instance id that owns this segment;
 * {@code targetIds} contains its explicit selected targets and is empty for
 * derived AOE/no-target segments. Segment ids include the actor combatant id so
 * duplicate summons of the same definition are distinguishable.
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
    List<String> targetIds
) {
    public ActionSegmentState {
        targetIds = targetIds == null ? List.of() : List.copyOf(targetIds);
    }

    /** Legacy constructor without actor/target ids (1v1 compatibility). */
    public ActionSegmentState(
        String segmentId, String moveId, String moveName, PlanBoard board,
        int startTick, int endTick, int fireTick, int apCost, int ceCost,
        ActionSegmentStatus status, Integer resolvedTick
    ) {
        this(segmentId, moveId, moveName, board, startTick, endTick, fireTick,
            apCost, ceCost, status, resolvedTick, null, List.of());
    }

    /** Source-compatible constructor for current singular-target callers. */
    public ActionSegmentState(
        String segmentId, String moveId, String moveName, PlanBoard board,
        int startTick, int endTick, int fireTick, int apCost, int ceCost,
        ActionSegmentStatus status, Integer resolvedTick, String actorId, String targetId
    ) {
        this(segmentId, moveId, moveName, board, startTick, endTick, fireTick,
            apCost, ceCost, status, resolvedTick, actorId,
            targetId == null ? List.of() : List.of(targetId));
    }

    /** Source-compatible singular view for clients not yet migrated to target lists. */
    @JsonIgnore
    public String targetId() {
        return targetIds.isEmpty() ? null : targetIds.get(0);
    }
}
