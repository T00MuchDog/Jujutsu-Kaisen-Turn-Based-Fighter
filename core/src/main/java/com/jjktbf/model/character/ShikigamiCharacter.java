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
 * <p>A shikigami may learn sorcerer and shikigami moves. Its separate type also
 * lets content tooling and roster filtering treat summonable definitions
 * differently from directly-selectable fighters.
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
     * Full construction including equipment and an explicit accessible-technique
     * set. Used by {@link CharacterData#toCharacter} so equipment from the
     * data file flows into move validation (gates weapon-tagged moves).
     */
    public ShikigamiCharacter(
        String         id,
        String         name,
        CharacterStats baseStats,
        String         innateTechniqueName,
        List<Move>     knownMoves,
        List<Ability>  abilities,
        java.util.Set<String> accessibleTechniques,
        Equipment      equipment
    ) {
        this(id, name, baseStats, innateTechniqueName, knownMoves, abilities,
            accessibleTechniques, equipment, 0.0);
    }

    public ShikigamiCharacter(
        String         id,
        String         name,
        CharacterStats baseStats,
        String         innateTechniqueName,
        List<Move>     knownMoves,
        List<Ability>  abilities,
        java.util.Set<String> accessibleTechniques,
        Equipment      equipment,
        double         baseCeDrainPerTick
    ) {
        super(id, name, CharacterType.SHIKIGAMI, baseStats, innateTechniqueName,
              knownMoves, abilities, accessibleTechniques, equipment);
        if (!Double.isFinite(baseCeDrainPerTick) || baseCeDrainPerTick < 0.0) {
            throw new IllegalArgumentException("Base CE drain per tick cannot be negative or non-finite");
        }
        this.baseCeDrainPerTick = baseCeDrainPerTick;
    }

    /**
     * Construction with equipment, computing the accessible-technique set from
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
        Equipment      equipment
    ) {
        this(id, name, baseStats, innateTechniqueName, knownMoves, abilities,
            equipment, 0.0);
    }

    public ShikigamiCharacter(
        String         id,
        String         name,
        CharacterStats baseStats,
        String         innateTechniqueName,
        List<Move>     knownMoves,
        List<Ability>  abilities,
        Equipment      equipment,
        double         baseCeDrainPerTick
    ) {
        this(id, name, baseStats, innateTechniqueName, knownMoves, abilities,
             accessibleTechniquesOf(innateTechniqueName, abilities), equipment,
             baseCeDrainPerTick);
    }

    @Override
    public double getBaseCeDrainPerTick() {
        return baseCeDrainPerTick;
    }
}
