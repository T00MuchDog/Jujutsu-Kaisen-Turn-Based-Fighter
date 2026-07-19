package com.jjktbf.multiplayer.protocol;

import java.util.List;

/** Version-checked action submitted to the authoritative match session. */
public record ActionCommand(
    String commandId,
    String matchId,
    long expectedStateVersion,
    CommandType type,
    SubmitPlanPayload payload
) {
    public static ActionCommand submitPlan(
        String commandId,
        String matchId,
        long expectedStateVersion,
        List<PlanPlacement> placements
    ) {
        return new ActionCommand(
            commandId,
            matchId,
            expectedStateVersion,
            CommandType.SUBMIT_PLAN,
            new SubmitPlanPayload(placements)
        );
    }

    public static ActionCommand readyNextRound(
        String commandId,
        String matchId,
        long expectedStateVersion
    ) {
        return new ActionCommand(
            commandId,
            matchId,
            expectedStateVersion,
            CommandType.READY_NEXT_ROUND,
            null
        );
    }
}
