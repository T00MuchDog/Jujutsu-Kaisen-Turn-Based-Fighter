package com.jjktbf.multiplayer.protocol;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Player intent for one plan placement. All costs, board selection, and outcomes
 * are derived by the authoritative server.
 *
 * <p>{@code actorId} is the combatant instance id placing the move. Explicitly
 * selected targets are carried in {@code targetIds}; derived AOE and no-target
 * moves carry an empty list.
 *
 * @param moveId     canonical move identifier
 * @param startTick  where on the timeline grid the move begins
 * @param actorId    combatant instance id placing the move (nullable for legacy 1v1)
 * @param targetIds  selected target combatant instance ids
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanPlacement(
    String moveId,
    int startTick,
    String actorId,
    List<String> targetIds
) {
    public PlanPlacement {
        targetIds = targetIds == null ? List.of() : List.copyOf(targetIds);
    }

    /** Legacy two-field placement (no actor/target). */
    public PlanPlacement(String moveId, int startTick) {
        this(moveId, startTick, null, List.of());
    }

    /** Source-compatible single-target constructor used by current planning clients. */
    public PlanPlacement(String moveId, int startTick, String actorId, String targetId) {
        this(moveId, startTick, actorId,
            targetId == null ? List.of() : List.of(targetId));
    }

    /** Source-compatible singular view for clients not yet migrated to target lists. */
    @JsonIgnore
    public String targetId() {
        return targetIds.isEmpty() ? null : targetIds.get(0);
    }
}
