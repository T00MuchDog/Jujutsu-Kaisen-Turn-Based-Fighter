package com.jjktbf.model.character;

import com.jjktbf.model.move.Move;

import java.util.List;

/**
 * A concrete Character subclass for cursed spirits.
 * Cursed spirits may or may not have an innate technique.
 */
public class CursedSpiritCharacter extends Character {

    public CursedSpiritCharacter(
        String         id,
        String         name,
        CharacterStats baseStats,
        String         innateTechniqueId,
        List<Move>     knownMoves
    ) {
        super(id, name, CharacterType.CURSED_SPIRIT, baseStats, innateTechniqueId, knownMoves);
    }
}
