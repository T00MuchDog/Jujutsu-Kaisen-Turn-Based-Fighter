package com.jjktbf.multiplayer.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Player intent for one plan placement. All costs, board selection, and outcomes
 * are derived by the authoritative server.
 *
 * <p>{@code actorId} is the combatant instance id placing the move; {@code targetId}
 * is the nullable selected target combatant instance id (required for hostile
 * single-target moves; null for AOE/self/defensive). Both may be null for legacy
 * 1v1 placements, in which case the server derives them from the single
 * participant pair.
 *
 * @param moveId     canonical move identifier
 * @param startTick  where on the timeline grid the move begins
 * @param actorId    combatant instance id placing the move (nullable for legacy 1v1)
 * @param targetId   selected target combatant instance id, or null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanPlacement(
    String moveId,
    int startTick,
    String actorId,
    String targetId
) {
    /** Legacy two-field placement (no actor/target). */
    public PlanPlacement(String moveId, int startTick) {
        this(moveId, startTick, null, null);
    }

    /** Placement with an actor and an explicit single-target selection. */
    public PlanPlacement withTarget(String actorId, String targetId) {
        return new PlanPlacement(moveId, startTick, actorId, targetId);
    }
}
