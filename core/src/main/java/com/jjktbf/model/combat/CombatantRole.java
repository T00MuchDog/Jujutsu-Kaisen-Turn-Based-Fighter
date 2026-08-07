package com.jjktbf.model.combat;

/**
 * Runtime role a combatant plays within its team during a battle.
 *
 * <p>This is distinct from the {@link com.jjktbf.model.character.CharacterType}
 * of the underlying definition: a regular 2v2 fighter and a directly-selectable
 * sorcerer are both {@code FIGHTER}s, while a summoned shikigami is a
 * {@code SUMMON}. A team loses only when it has no living {@code FIGHTER}s;
 * living summons do not prevent defeat.
 */
public enum CombatantRole {
    /** A primary combatant whose defeat contributes to team defeat. */
    FIGHTER,
    /** A summoned combatant. Defeating it removes it but does not, by itself, defeat the team. */
    SUMMON
}
