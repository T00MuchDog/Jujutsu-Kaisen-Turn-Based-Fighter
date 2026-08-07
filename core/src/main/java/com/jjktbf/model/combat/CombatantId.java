package com.jjktbf.model.combat;

import java.util.Objects;

/**
 * Stable, battle-scoped identifier for a single combatant instance.
 *
 * <p>A {@code CombatantId} distinguishes duplicate live instances of the same
 * character definition (e.g. two Divine Dog shikigami summoned by different
 * summoners, or two of the same summon summoned twice). It is assigned once by
 * the {@link BattleState} when a combatant is created and never changes for the
 * lifetime of that instance. It must <strong>not</strong> be confused with the
 * canonical {@link com.jjktbf.model.character.Character#getId() character
 * definition id}, which is shared by all instances of the same definition.
 *
 * <p>Ids are compared by their stable string value, so an id carried through a
 * snapshot, a plan, or a wire event remains valid across copies.
 */
public record CombatantId(String value) implements Comparable<CombatantId> {

    public CombatantId {
        Objects.requireNonNull(value, "CombatantId value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("CombatantId value cannot be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public int compareTo(CombatantId other) {
        return value.compareTo(other.value);
    }
}
