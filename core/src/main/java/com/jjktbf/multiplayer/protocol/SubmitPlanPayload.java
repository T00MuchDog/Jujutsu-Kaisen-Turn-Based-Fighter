package com.jjktbf.multiplayer.protocol;

import java.util.List;

/** Typed payload for {@link CommandType#SUBMIT_PLAN}. */
public record SubmitPlanPayload(
    List<PlanPlacement> placements
) {
    public SubmitPlanPayload {
        placements = placements == null ? List.of() : List.copyOf(placements);
    }
}
