package com.jjktbf.model.combat;

import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.move.MoveCategory;

import java.util.Objects;

/**
 * Computes the Power value for a move based on the user's stats and the move category.
 *
 * Power is a per-move figure; it is NOT stored on the character.
 * It combines with the move's BasePower and the target's Defense inside DamageCalculator.
 *
 * All stat inputs are first passed through StatScale.scale() — the nonlinear stat
 * transform anchored at 80. The weighted ratios below are unchanged; only the raw
 * stat values are replaced by their scaled equivalents before the ratios are applied.
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
        return compute(category, cs, BattleStatMode.STANDARD);
    }

    public static int compute(
        MoveCategory category,
        CharacterStats cs,
        BattleStatMode statMode
    ) {
        Objects.requireNonNull(statMode, "statMode");
        return switch (category) {
            case PHYSICAL                         -> physical(cs, statMode);
            case CURSED_ENERGY                    -> cursedEnergyBase(cs, statMode);
            case INNATE_TECHNIQUE                 -> innateTechnique(cs, statMode);
            case NON_INNATE_TECHNIQUE             -> nonInnateTechnique(cs, statMode);
            case PHYSICAL_CURSED_ENERGY           -> physicalCursedEnergy(cs, statMode);
            case PHYSICAL_INNATE_TECHNIQUE        -> physicalInnate(cs, statMode);
            case PHYSICAL_NON_INNATE_TECHNIQUE    -> physicalNonInnate(cs, statMode);
            case INNATE_NON_INNATE_TECHNIQUE      -> innateNonInnate(cs, statMode);
            case PHYSICAL_INNATE_NON_INNATE_TECHNIQUE ->
                physicalInnateNonInnate(cs, statMode);
            case UTILITY, DEFENSIVE               -> 0; // no damage component
        };
    }

    // -------------------------------------------------------------------------
    // Pure category formulas  (inputs are StatScale-scaled)
    // -------------------------------------------------------------------------

    /**
     * PHYSICAL: 4:1 Strength to CombatAbility.
     * Power = (S(STR)*4 + S(CA)) / 5
     */
    public static int physical(CharacterStats cs) {
        return physical(cs, BattleStatMode.STANDARD);
    }

    public static int physical(CharacterStats cs, BattleStatMode statMode) {
        return (statMode.scale(cs.getStrength()) * 4
              + statMode.scale(cs.getCombatAbility())) / 5;
    }

    /**
     * CE base component used by technique-derived formulas.
     * 3:2:1  CE_Output : CE_Reserves : CE_Efficiency
     * = (S(OUT)*3 + S(RES)*2 + S(EFF)) / 6
     */
    public static int cursedEnergyBase(CharacterStats cs) {
        return cursedEnergyBase(cs, BattleStatMode.STANDARD);
    }

    public static int cursedEnergyBase(CharacterStats cs, BattleStatMode statMode) {
        return (statMode.scale(cs.getCursedEnergyOutput()) * 3
              + statMode.scale(cs.getCursedEnergyReserves()) * 2
              + statMode.scale(cs.getCursedEnergyEfficiency())) / 6;
    }

    /**
     * INNATE_TECHNIQUE: 50:50 CE_base and CursedTechniqueMastery.
     * Power = (CE_base + S(CTM)) / 2
     */
    private static int innateTechnique(CharacterStats cs, BattleStatMode statMode) {
        return (cursedEnergyBase(cs, statMode)
            + statMode.scale(cs.getCursedTechniqueMastery())) / 2;
    }

    /**
     * NON_INNATE_TECHNIQUE: 50:50 CE_base and JujutsuSkill.
     * Power = (CE_base + S(JS)) / 2
     */
    private static int nonInnateTechnique(CharacterStats cs, BattleStatMode statMode) {
        return (cursedEnergyBase(cs, statMode) + statMode.scale(cs.getJujutsuSkill())) / 2;
    }

    // -------------------------------------------------------------------------
    // Hybrid formulas  (composed from the scaled pure formulas above)
    // -------------------------------------------------------------------------

    /**
     * PHYSICAL + CURSED_ENERGY: 3:1 CE : Physical
     * Power = (CE_base*3 + Physical) / 4
     */
    private static int physicalCursedEnergy(CharacterStats cs, BattleStatMode statMode) {
        return (cursedEnergyBase(cs, statMode) * 3 + physical(cs, statMode)) / 4;
    }

    /**
     * PHYSICAL + INNATE_TECHNIQUE: 4:1 InnateT : Physical
     * Power = (InnateT*4 + Physical) / 5
     */
    private static int physicalInnate(CharacterStats cs, BattleStatMode statMode) {
        return (innateTechnique(cs, statMode) * 4 + physical(cs, statMode)) / 5;
    }

    /**
     * PHYSICAL + NON_INNATE_TECHNIQUE: 3:1 NonInnateT : Physical
     * Power = (NonInnateT*3 + Physical) / 4
     */
    private static int physicalNonInnate(CharacterStats cs, BattleStatMode statMode) {
        return (nonInnateTechnique(cs, statMode) * 3 + physical(cs, statMode)) / 4;
    }

    /**
     * INNATE_TECHNIQUE + NON_INNATE_TECHNIQUE: 3:2 InnateT : NonInnateT
     * Power = (InnateT*3 + NonInnateT*2) / 5
     */
    private static int innateNonInnate(CharacterStats cs, BattleStatMode statMode) {
        return (innateTechnique(cs, statMode) * 3
            + nonInnateTechnique(cs, statMode) * 2) / 5;
    }

    /**
     * PHYSICAL + INNATE_TECHNIQUE + NON_INNATE_TECHNIQUE: 1:3:2 Physical:InnateT:NonInnateT
     * Power = (Physical + InnateT*3 + NonInnateT*2) / 6
     */
    private static int physicalInnateNonInnate(CharacterStats cs, BattleStatMode statMode) {
        return (physical(cs, statMode) + innateTechnique(cs, statMode) * 3
            + nonInnateTechnique(cs, statMode) * 2) / 6;
    }
}
