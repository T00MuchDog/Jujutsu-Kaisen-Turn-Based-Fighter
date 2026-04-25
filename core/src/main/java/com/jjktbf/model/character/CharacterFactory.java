package com.jjktbf.model.character;

import com.jjktbf.model.move.CoreMoves;
import com.jjktbf.model.move.Move;

import java.util.List;

/**
 * Factory for creating canonical JJK characters with their correct stat blocks and movesets.
 *
 * Stats are PLACEHOLDER values intended for initial testing — balance pass will follow.
 * All values respect the 10–300 range (baseline 80).
 *
 * Characters are intentionally created above baseline to produce interesting fights.
 * Sukuna as designed should have the highest possible HP (~800), so his VIT is set to 300.
 */
public final class CharacterFactory {

    private CharacterFactory() {}

    // -------------------------------------------------------------------------
    // Yuji Itadori
    // -------------------------------------------------------------------------

    /**
     * Yuji Itadori — Grade 1 (Special Grade vessel).
     *
     * Profile: Exceptional physical power and durability, very high CE reserves
     * as Sukuna's vessel, good CE output. Low innate technique mastery (no innate
     * technique of his own at base). High combat ability from intense training.
     *
     * Signature moves: Divergent Fist (high BF potential), Rapid Strikes.
     */
    public static Character createYuji() {
        CharacterStats stats = new CharacterStats.Builder()
            .vitality(175)               // HP ≈ 467
            .strength(210)               // Exceptional physical power
            .durability(190)             // Very tough body
            .speed(200)                  // Very fast for a human
            .cursedEnergyReserves(160)   // High — Sukuna's vessel
            .cursedEnergyEfficiency(100) // Slightly above baseline
            .cursedEnergyOutput(130)     // High CE output
            .jujutsuSkill(140)           // Trained heavily, good jujutsu understanding
            .combatAbility(220)          // Elite hand-to-hand combatant
            .cursedTechniqueMastery(10)  // No innate technique (minimum)
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

        return new SorcererCharacter(
            "YUJI_ITADORI",
            "Yuji Itadori",
            CharacterType.SORCERER_NON_INNATE,
            stats,
            null,   // no innate technique
            moves
        );
    }

    // -------------------------------------------------------------------------
    // Ryomen Sukuna
    // -------------------------------------------------------------------------

    /**
     * Ryomen Sukuna — Special Grade (King of Curses).
     *
     * Profile: Maximum vitality (800 HP target), extreme stats across the board.
     * Innate technique: Shrine (Dismantle + Cleave). Dominant in all categories.
     * The benchmark fight — if you can beat Sukuna, the system is working.
     */
    public static Character createSukuna() {
        CharacterStats stats = new CharacterStats.Builder()
            .vitality(300)               // ~800 HP — maximum
            .strength(280)               // Overwhelming physical power
            .durability(270)             // Near-invulnerable
            .speed(290)                  // Blindingly fast
            .cursedEnergyReserves(300)   // Limitless cursed energy
            .cursedEnergyEfficiency(250) // Extremely efficient
            .cursedEnergyOutput(295)     // Peak cursed energy output
            .jujutsuSkill(290)           // Millennia of jujutsu mastery
            .combatAbility(285)          // Peak combat instinct
            .cursedTechniqueMastery(300) // Perfect mastery of Shrine
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

        return new CursedSpiritCharacter(
            "RYOMEN_SUKUNA",
            "Ryomen Sukuna",
            stats,
            "SHRINE",
            moves
        );
    }
}
