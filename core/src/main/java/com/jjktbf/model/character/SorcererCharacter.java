package com.jjktbf.model.character;

import com.jjktbf.model.move.Move;

import java.util.List;

/**
 * The single concrete Character subclass for all playable characters.
 *
 * All characters are Sorcerers. Whether a sorcerer has an innate technique is
 * determined by the innateTechniqueName parameter (null = no innate technique).
 *
 * Additional character types (Cursed Spirit, Human, Boss) will be added as
 * separate subclasses in a future pass.
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
}
