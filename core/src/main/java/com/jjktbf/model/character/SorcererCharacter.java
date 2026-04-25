package com.jjktbf.model.character;

import com.jjktbf.model.move.Move;

import java.util.List;

/**
 * A concrete Character subclass for jujutsu sorcerers.
 * Covers both SORCERER_INNATE and SORCERER_NON_INNATE types.
 *
 * Future subclasses (CursedSpiritCharacter, BossCharacter, etc.) follow
 * the same pattern — extend Character, pass the appropriate CharacterType.
 */
public class SorcererCharacter extends Character {

    public SorcererCharacter(
        String         id,
        String         name,
        CharacterType  type,
        CharacterStats baseStats,
        String         innateTechniqueId,
        List<Move>     knownMoves
    ) {
        super(id, name, type, baseStats, innateTechniqueId, knownMoves);
    }
}
