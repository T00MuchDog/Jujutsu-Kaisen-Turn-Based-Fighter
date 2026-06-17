package com.jjktbf.model.character;

import com.jjktbf.model.move.Move;

import java.util.List;

/**
 * Placeholder for future Cursed Spirit character type.
 * Currently routes through SORCERER type — will be updated when CharacterType
 * expands to include CURSED_SPIRIT.
 */
public class CursedSpiritCharacter extends Character {

    public CursedSpiritCharacter(
        String         id,
        String         name,
        CharacterStats baseStats,
        String         innateTechniqueName,
        List<Move>     knownMoves
    ) {
        super(id, name, CharacterType.SORCERER, baseStats, innateTechniqueName, knownMoves);
    }

    public CursedSpiritCharacter(
        String         id,
        String         name,
        CharacterStats baseStats,
        String         innateTechniqueName,
        List<Move>     knownMoves,
        List<Ability>  abilities
    ) {
        super(id, name, CharacterType.SORCERER, baseStats, innateTechniqueName, knownMoves, abilities);
    }
}
