package com.jjktbf.multiplayer.protocol;

import java.util.List;

/** Current round budgets and both queued and already resolved action segments. */
public record PlanState(
    int roundNumber,
    int apBudget,
    int apUsed,
    int ceBudget,
    int ceUsed,
    List<ActionSegmentState> queuedSegments,
    List<ActionSegmentState> resolvedSegments
) {
    public PlanState {
        queuedSegments = queuedSegments == null ? List.of() : List.copyOf(queuedSegments);
        resolvedSegments = resolvedSegments == null ? List.of() : List.copyOf(resolvedSegments);
    }
}
