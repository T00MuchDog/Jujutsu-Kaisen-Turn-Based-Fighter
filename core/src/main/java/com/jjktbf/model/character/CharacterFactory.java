package com.jjktbf.model.character;

import com.jjktbf.model.move.CoreMoves;
import com.jjktbf.model.move.Move;

import java.util.List;

/**
 * Factory for canonical JJK characters used in testing and as seed data.
 * Stats are PLACEHOLDER values — balance pass will follow.
 */
public final class CharacterFactory {

    private CharacterFactory() {}

    public static Character createYuji() {
        CharacterStats stats = new CharacterStats.Builder()
            .vitality(175)
            .strength(210)
            .durability(190)
            .speed(200)
            .cursedEnergyReserves(160)
            .cursedEnergyEfficiency(100)
            .cursedEnergyOutput(130)
            .jujutsuSkill(140)
            .combatAbility(220)
            .cursedTechniqueMastery(10)
            .build();

        List<Move> moves = List.of(
            CoreMoves.basicPunch(),
            CoreMoves.basicBlock(),
            CoreMoves.rapidStrikes(),
            CoreMoves.heavyPunch(),
            CoreMoves.divekick(),
            CoreMoves.cursedStrike(),
            CoreMoves.divergentFist(),
            CoreMoves.cursedEnergyArmor(),
            CoreMoves.ironwall()
        );

        return new SorcererCharacter("000000", "Yuji Itadori", stats, null, moves);
    }

    public static Character createSukuna() {
        CharacterStats stats = new CharacterStats.Builder()
            .vitality(300)
            .strength(280)
            .durability(270)
            .speed(290)
            .cursedEnergyReserves(300)
            .cursedEnergyEfficiency(250)
            .cursedEnergyOutput(295)
            .jujutsuSkill(290)
            .combatAbility(285)
            .cursedTechniqueMastery(300)
            .build();

        List<Move> moves = List.of(
            CoreMoves.basicPunch(),
            CoreMoves.basicBlock(),
            CoreMoves.rapidStrikes(),
            CoreMoves.heavyPunch(),
            CoreMoves.cursedStrike(),
            CoreMoves.dismantle(),
            CoreMoves.cleave(),
            CoreMoves.sukunaFleshyStrike(),
            CoreMoves.cursedEnergyArmor(),
            CoreMoves.ironwall()
        );

        // Sukuna's technique ID "SHRINE" matches requiredTechniqueId on Dismantle/Cleave/Fleshy Strike
        return new SorcererCharacter("000001", "Ryomen Sukuna", stats, "Shrine", moves);
    }
}
