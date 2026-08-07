package com.jjktbf.model.combat;

import java.util.Objects;

/**
 * Stable identifier for one of the two opposing teams in a battle.
 *
 * <p>Battles always have exactly two teams, but each team may contain any number
 * of combatants. A team loses when it has no living {@link CombatantRole#FIGHTER}s.
 * This type is the team analogue of {@link CombatantId} and similarly must not be
 * confused with the {@link com.jjktbf.multiplayer.protocol.PlayerSide} wire enum
 * (though the two correspond one-to-one in multiplayer).
 */
public record BattleTeamId(String value) implements Comparable<BattleTeamId> {

    public static final BattleTeamId PLAYER = new BattleTeamId("PLAYER");
    public static final BattleTeamId ENEMY  = new BattleTeamId("ENEMY");

    public BattleTeamId {
        Objects.requireNonNull(value, "BattleTeamId value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("BattleTeamId value cannot be blank");
        }
    }

    /** The id of the opposing team. Battles are always exactly two teams. */
    public BattleTeamId opposite() {
        return this.equals(PLAYER) ? ENEMY : PLAYER;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public int compareTo(BattleTeamId other) {
        return value.compareTo(other.value);
    }
}
