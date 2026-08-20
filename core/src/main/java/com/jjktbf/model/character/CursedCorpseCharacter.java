package com.jjktbf.model.character;

import com.jjktbf.model.move.Move;

import java.util.List;

/** A cursed corpse, able to learn both sorcerer and shikigami moves. */
public class CursedCorpseCharacter extends Character {

    public CursedCorpseCharacter(
        String id,
        String name,
        CharacterStats baseStats,
        String innateTechniqueName,
        List<Move> knownMoves
    ) {
        super(id, name, CharacterType.CURSED_CORPSE, baseStats, innateTechniqueName, knownMoves);
    }

    public CursedCorpseCharacter(
        String id,
        String name,
        CharacterStats baseStats,
        String innateTechniqueName,
        List<Move> knownMoves,
        List<Ability> abilities
    ) {
        super(id, name, CharacterType.CURSED_CORPSE, baseStats, innateTechniqueName,
            knownMoves, abilities);
    }

    public CursedCorpseCharacter(
        String id,
        String name,
        CharacterStats baseStats,
        String innateTechniqueName,
        List<Move> knownMoves,
        List<Ability> abilities,
        java.util.Set<String> accessibleTechniques,
        boolean hasWeapon
    ) {
        super(id, name, CharacterType.CURSED_CORPSE, baseStats, innateTechniqueName,
            knownMoves, abilities, accessibleTechniques, hasWeapon);
    }

    public CursedCorpseCharacter(
        String id,
        String name,
        CharacterStats baseStats,
        String innateTechniqueName,
        List<Move> knownMoves,
        List<Ability> abilities,
        boolean hasWeapon
    ) {
        this(id, name, baseStats, innateTechniqueName, knownMoves, abilities,
            accessibleTechniquesOf(innateTechniqueName, abilities), hasWeapon);
    }
}
