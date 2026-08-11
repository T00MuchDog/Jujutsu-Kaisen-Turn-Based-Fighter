package com.jjktbf.model.character;

import com.jjktbf.model.move.Move;

import java.util.List;

/**
 * A {@link Character} definition for a shikigami — a summonable combatant such
 * as the Divine Dogs or Mahoraga.
 *
 * <p>A shikigami is a complete character (stats, moves, abilities) so it can be
 * summoned into battle as a full combatant with HP, CE, statuses, and its own
 * plan. The definition is distinct from the runtime role the combatant plays
 * inside a battle: a shikigami definition becomes a {@code SUMMON} combatant
 * when summoned, but nothing about the definition forces that role.
 *
 * <p>Mechanically a shikigami obeys the same move/stat/technique rules as a
 * sorcerer; the separate type exists so content tooling and roster filtering
 * can treat summonable definitions differently from directly-selectable fighters.
 */
public class ShikigamiCharacter extends Character {

    private final double baseCeDrainPerTick;

    public ShikigamiCharacter(
        String         id,
        String         name,
        CharacterStats baseStats,
        String         innateTechniqueName,
        List<Move>     knownMoves
    ) {
        super(id, name, CharacterType.SHIKIGAMI, baseStats, innateTechniqueName, knownMoves);
        this.baseCeDrainPerTick = 0.0;
    }

    public ShikigamiCharacter(
        String         id,
        String         name,
        CharacterStats baseStats,
        String         innateTechniqueName,
        List<Move>     knownMoves,
        List<Ability>  abilities
    ) {
        super(id, name, CharacterType.SHIKIGAMI, baseStats, innateTechniqueName, knownMoves, abilities);
        this.baseCeDrainPerTick = 0.0;
    }

    /**
     * Full construction including weapon state and an explicit accessible-technique
     * set. Used by {@link CharacterData#toCharacter} so {@code hasWeapon} from the
     * data file flows into move validation (gates {@code weaponRequired} moves).
     */
    public ShikigamiCharacter(
        String         id,
        String         name,
        CharacterStats baseStats,
        String         innateTechniqueName,
        List<Move>     knownMoves,
        List<Ability>  abilities,
        java.util.Set<String> accessibleTechniques,
        boolean        hasWeapon
    ) {
        this(id, name, baseStats, innateTechniqueName, knownMoves, abilities,
            accessibleTechniques, hasWeapon, 0.0);
    }

    public ShikigamiCharacter(
        String         id,
        String         name,
        CharacterStats baseStats,
        String         innateTechniqueName,
        List<Move>     knownMoves,
        List<Ability>  abilities,
        java.util.Set<String> accessibleTechniques,
        boolean        hasWeapon,
        double         baseCeDrainPerTick
    ) {
        super(id, name, CharacterType.SHIKIGAMI, baseStats, innateTechniqueName,
              knownMoves, abilities, accessibleTechniques, hasWeapon);
        if (!Double.isFinite(baseCeDrainPerTick) || baseCeDrainPerTick < 0.0) {
            throw new IllegalArgumentException("Base CE drain per tick cannot be negative or non-finite");
        }
        this.baseCeDrainPerTick = baseCeDrainPerTick;
    }

    /**
     * Construction with weapon state, computing the accessible-technique set from
     * the innate name + abilities (mirrors the default behaviour of the
     * abilities-only constructor). Convenience overload for {@link CharacterData#toCharacter}.
     */
    public ShikigamiCharacter(
        String         id,
        String         name,
        CharacterStats baseStats,
        String         innateTechniqueName,
        List<Move>     knownMoves,
        List<Ability>  abilities,
        boolean        hasWeapon
    ) {
        this(id, name, baseStats, innateTechniqueName, knownMoves, abilities,
            hasWeapon, 0.0);
    }

    public ShikigamiCharacter(
        String         id,
        String         name,
        CharacterStats baseStats,
        String         innateTechniqueName,
        List<Move>     knownMoves,
        List<Ability>  abilities,
        boolean        hasWeapon,
        double         baseCeDrainPerTick
    ) {
        this(id, name, baseStats, innateTechniqueName, knownMoves, abilities,
             accessibleTechniquesOf(innateTechniqueName, abilities), hasWeapon,
             baseCeDrainPerTick);
    }

    @Override
    public double getBaseCeDrainPerTick() {
        return baseCeDrainPerTick;
    }
}
