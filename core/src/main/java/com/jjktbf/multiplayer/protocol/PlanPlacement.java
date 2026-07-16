package com.jjktbf.multiplayer.protocol;

/**
 * Player intent for one plan placement. All costs, board selection, and outcomes
 * are derived by the authoritative server.
 */
public record PlanPlacement(
    String moveId,
    int startTick
) {
}
