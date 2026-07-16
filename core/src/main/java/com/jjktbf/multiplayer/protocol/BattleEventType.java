package com.jjktbf.multiplayer.protocol;

/** Wire-safe event types emitted by battle resolution. */
public enum BattleEventType {
    MOVE_STARTED,
    MOVE_FIRED,
    MOVE_MISSED,
    MOVE_BLOCKED,
    MOVE_BLOCK_REDUCED,
    MOVE_STUNNED,
    DAMAGE_DEALT,
    BLACK_FLASH,
    CE_DRAINED,
    CE_RESTORED,
    CE_DEPLETED,
    STATUS_APPLIED,
    STATUS_EXPIRED,
    BFS_ENTERED,
    BFS_EXPIRED,
    ROUND_START,
    ROUND_END,
    BATTLE_OVER
}
