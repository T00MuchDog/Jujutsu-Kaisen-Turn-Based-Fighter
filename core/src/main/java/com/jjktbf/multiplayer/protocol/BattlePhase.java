package com.jjktbf.multiplayer.protocol;

/** Serializable phase of the authoritative battle state machine. */
public enum BattlePhase {
    PLANNING,
    RESOLUTION,
    ROUND_END,
    BATTLE_OVER
}
