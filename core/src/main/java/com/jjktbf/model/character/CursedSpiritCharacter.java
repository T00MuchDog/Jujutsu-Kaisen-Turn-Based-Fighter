package com.jjktbf.model.character;

import com.jjktbf.model.move.Move;

import java.util.List;

/** A cursed spirit, restricted to cursed-spirit moves. */
public class CursedSpiritCharacter extends Character {

    public CursedSpiritCharacter(
        String         id,
        String         name,
        CharacterStats baseStats,
        String         innateTechniqueName,
        List<Move>     knownMoves
    ) {
        super(id, name, CharacterType.CURSED_SPIRIT, baseStats, innateTechniqueName, knownMoves);
    }

    public CursedSpiritCharacter(
        String         id,
        String         name,
        CharacterStats baseStats,
        String         innateTechniqueName,
        List<Move>     knownMoves,
        List<Ability>  abilities
    ) {
        super(id, name, CharacterType.CURSED_SPIRIT, baseStats, innateTechniqueName,
            knownMoves, abilities);
    }

    public CursedSpiritCharacter(
        String id,
        String name,
        CharacterStats baseStats,
        String innateTechniqueName,
        List<Move> knownMoves,
        List<Ability> abilities,
        java.util.Set<String> accessibleTechniques,
        Equipment equipment
    ) {
        super(id, name, CharacterType.CURSED_SPIRIT, baseStats, innateTechniqueName,
            knownMoves, abilities, accessibleTechniques, equipment);
    }

    public CursedSpiritCharacter(
        String id,
        String name,
        CharacterStats baseStats,
        String innateTechniqueName,
        List<Move> knownMoves,
        List<Ability> abilities,
        Equipment equipment
    ) {
        this(id, name, baseStats, innateTechniqueName, knownMoves, abilities,
            accessibleTechniquesOf(innateTechniqueName, abilities), equipment);
    }
}
