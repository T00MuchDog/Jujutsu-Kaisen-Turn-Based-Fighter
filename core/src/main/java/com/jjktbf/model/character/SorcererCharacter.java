package com.jjktbf.model.character;

import com.jjktbf.model.move.Move;

import java.util.List;

/**
 * A jujutsu sorcerer. Whether a sorcerer has an innate technique is
 * determined by the innateTechniqueName parameter (null = no innate technique).
 */
public class SorcererCharacter extends Character {

    public SorcererCharacter(
        String         id,
        String         name,
        CharacterStats baseStats,
        String         innateTechniqueName,
        List<Move>     knownMoves
    ) {
        super(id, name, CharacterType.SORCERER, baseStats, innateTechniqueName, knownMoves);
    }

    public SorcererCharacter(
        String         id,
        String         name,
        CharacterStats baseStats,
        String         innateTechniqueName,
        List<Move>     knownMoves,
        List<Ability>  abilities
    ) {
        super(id, name, CharacterType.SORCERER, baseStats, innateTechniqueName, knownMoves, abilities);
    }

    /**
     * Full construction including equipment and an explicit accessible-technique
     * set. Used by {@link CharacterData#toCharacter} so equipment from the
     * data file flows into move validation (gates weapon-tagged moves).
     */
    public SorcererCharacter(
        String         id,
        String         name,
        CharacterStats baseStats,
        String         innateTechniqueName,
        List<Move>     knownMoves,
        List<Ability>  abilities,
        java.util.Set<String> accessibleTechniques,
        Equipment      equipment
    ) {
        super(id, name, CharacterType.SORCERER, baseStats, innateTechniqueName,
              knownMoves, abilities, accessibleTechniques, equipment);
    }

    /**
     * Construction with equipment, computing the accessible-technique set from
     * the innate name + abilities (mirrors the default behaviour of the
     * abilities-only constructor). Convenience overload for {@link CharacterData#toCharacter}.
     */
    public SorcererCharacter(
        String         id,
        String         name,
        CharacterStats baseStats,
        String         innateTechniqueName,
        List<Move>     knownMoves,
        List<Ability>  abilities,
        Equipment      equipment
    ) {
        this(id, name, baseStats, innateTechniqueName, knownMoves, abilities,
             accessibleTechniquesOf(innateTechniqueName, abilities), equipment);
    }
}
