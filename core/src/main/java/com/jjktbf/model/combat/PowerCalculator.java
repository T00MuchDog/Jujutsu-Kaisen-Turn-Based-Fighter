package com.jjktbf.model.combat;

import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.CombatStats;
import com.jjktbf.model.move.MoveCategory;

/**
 * Computes the Power value for a move based on the user's stats and the move category.
 *
 * Power is a per-move figure; it is NOT stored on the character.
 * It combines with the move's BasePower and the target's Defense inside DamageCalculator.
 *
 * All ratios are exact as specified in the design doc.
 */
public final class PowerCalculator {

    private PowerCalculator() {}

    /**
     * Compute the Power multiplier for a given move category using the attacker's stats.
     *
     * @param category  the move's category (determines formula)
     * @param cs        attacker's base CharacterStats
     * @return          power value (raw integer, fed into damage formula)
     */
    public static int compute(MoveCategory category, CharacterStats cs) {
        return switch (category) {
            case PHYSICAL                         -> physical(cs);
            case CURSED_ENERGY                    -> cursedEnergyBase(cs);
            case INNATE_TECHNIQUE                 -> innateTechnique(cs);
            case NON_INNATE_TECHNIQUE             -> nonInnateTechnique(cs);
            case PHYSICAL_CURSED_ENERGY           -> physicalCursedEnergy(cs);
            case PHYSICAL_INNATE_TECHNIQUE        -> physicalInnate(cs);
            case PHYSICAL_NON_INNATE_TECHNIQUE    -> physicalNonInnate(cs);
            case INNATE_NON_INNATE_TECHNIQUE      -> innateNonInnate(cs);
            case PHYSICAL_INNATE_NON_INNATE_TECHNIQUE -> physicalInnateNonInnate(cs);
            case UTILITY, DEFENSIVE               -> 0; // no damage component
        };
    }

    // -------------------------------------------------------------------------
    // Pure category formulas
    // -------------------------------------------------------------------------

    /**
     * PHYSICAL: 4:1 Strength to CombatAbility.
     * Power = (STR*4 + CA) / 5
     */
    public static int physical(CharacterStats cs) {
        return (cs.getStrength() * 4 + cs.getCombatAbility()) / 5;
    }

    /**
     * CE base component used by technique-derived formulas.
     * 3:2:1  CE_Output : CE_Reserves : CE_Efficiency
     * = (OUT*3 + RES*2 + EFF) / 6
     */
    public static int cursedEnergyBase(CharacterStats cs) {
        return (cs.getCursedEnergyOutput() * 3
              + cs.getCursedEnergyReserves() * 2
              + cs.getCursedEnergyEfficiency()) / 6;
    }

    /**
     * INNATE_TECHNIQUE: 50:50 CE_base and CursedTechniqueMastery.
     * Power = (CE_base + CTM) / 2
     */
    private static int innateTechnique(CharacterStats cs) {
        return (cursedEnergyBase(cs) + cs.getCursedTechniqueMastery()) / 2;
    }

    /**
     * NON_INNATE_TECHNIQUE: 50:50 CE_base and JujutsuSkill.
     * Power = (CE_base + JS) / 2
     */
    private static int nonInnateTechnique(CharacterStats cs) {
        return (cursedEnergyBase(cs) + cs.getJujutsuSkill()) / 2;
    }

    // -------------------------------------------------------------------------
    // Hybrid formulas
    // -------------------------------------------------------------------------

    /**
     * PHYSICAL + CURSED_ENERGY: 3:1 CE : Physical
     * Power = (CE_base*3 + Physical) / 4
     */
    private static int physicalCursedEnergy(CharacterStats cs) {
        return (cursedEnergyBase(cs) * 3 + physical(cs)) / 4;
    }

    /**
     * PHYSICAL + INNATE_TECHNIQUE: 4:1 InnateT : Physical
     * Power = (InnateT*4 + Physical) / 5
     */
    private static int physicalInnate(CharacterStats cs) {
        return (innateTechnique(cs) * 4 + physical(cs)) / 5;
    }

    /**
     * PHYSICAL + NON_INNATE_TECHNIQUE: 3:1 NonInnateT : Physical
     * Power = (NonInnateT*3 + Physical) / 4
     */
    private static int physicalNonInnate(CharacterStats cs) {
        return (nonInnateTechnique(cs) * 3 + physical(cs)) / 4;
    }

    /**
     * INNATE_TECHNIQUE + NON_INNATE_TECHNIQUE: 3:2 InnateT : NonInnateT
     * Power = (InnateT*3 + NonInnateT*2) / 5
     */
    private static int innateNonInnate(CharacterStats cs) {
        return (innateTechnique(cs) * 3 + nonInnateTechnique(cs) * 2) / 5;
    }

    /**
     * PHYSICAL + INNATE_TECHNIQUE + NON_INNATE_TECHNIQUE: 1:3:2 Physical:InnateT:NonInnateT
     * Power = (Physical + InnateT*3 + NonInnateT*2) / 6
     */
    private static int physicalInnateNonInnate(CharacterStats cs) {
        return (physical(cs) + innateTechnique(cs) * 3 + nonInnateTechnique(cs) * 2) / 6;
    }
}
