package com.jjktbf.model.move;

/**
 * Factory class for the guaranteed baseline moves every character has access to.
 *
 * BASIC_PUNCH and BASIC_BLOCK are stored outside the slot system —
 * every character knows them regardless of stats.
 *
 * Also provides commonly used physical moves for initial characters.
 * As the game grows, move definitions will migrate to JSON data files
 * loaded by a MoveRepository.
 */
public final class CoreMoves {

    private CoreMoves() {}

    // -------------------------------------------------------------------------
    // Guaranteed moves (every character)
    // -------------------------------------------------------------------------

    public static Move basicPunch() {
        return new Move.Builder("BASIC_PUNCH")
            .name("Basic Punch")
            .description("A standard physical strike. Fast and reliable.")
            .category(MoveCategory.PHYSICAL)
            .basePower(30)
            .baseAccuracy(1.0)
            .apCost(10)
            .unleashPoint(5)          // fires mid-block — fast but not instant
            .baseCeCost(0)
            .minCeCost(0)
            .maxCeCost(0)
            .interruptType(InterruptType.NONE)
            .defenseType(DefenseType.NONE)
            .guaranteedMove(true)
            .build();
    }

    public static Move basicBlock() {
        return new Move.Builder("BASIC_BLOCK")
            .name("Basic Block")
            .description("Raises guard. Provides a moderate defense buff for the rest of the round.")
            .category(MoveCategory.DEFENSIVE)
            .basePower(0)
            .baseAccuracy(1.0)
            .neverMiss(true)
            .apCost(8)
            .unleashPoint(1)          // instant — activates immediately on entry
            .baseCeCost(0)
            .minCeCost(0)
            .maxCeCost(0)
            .interruptType(InterruptType.NONE)
            .defenseType(DefenseType.STAT_BUFF)
            .defenseBuffDuration(-1)  // lasts the rest of the round
            .defenseBuffAmount(15)    // PLACEHOLDER: +15 to defense stat
            .guaranteedMove(true)
            .build();
    }

    // -------------------------------------------------------------------------
    // Physical moves (require CombatAbility slots)
    // -------------------------------------------------------------------------

    public static Move heavyPunch() {
        return new Move.Builder("HEAVY_PUNCH")
            .name("Heavy Punch")
            .description("A powerful but slower overhand strike.")
            .category(MoveCategory.PHYSICAL)
            .basePower(65)
            .baseAccuracy(0.88)
            .apCost(22)
            .unleashPoint(18)
            .baseCeCost(0).minCeCost(0).maxCeCost(0)
            .interruptType(InterruptType.NONE)
            .defenseType(DefenseType.NONE)
            .prerequisites(java.util.Map.of("strength", 60))
            .build();
    }

    public static Move rapidStrikes() {
        return new Move.Builder("RAPID_STRIKES")
            .name("Rapid Strikes")
            .description("A flurry of fast jabs. Lower power per hit but very quick.")
            .category(MoveCategory.PHYSICAL)
            .basePower(38)
            .baseAccuracy(0.95)
            .apCost(14)
            .unleashPoint(7)
            .baseCeCost(0).minCeCost(0).maxCeCost(0)
            .interruptType(InterruptType.NONE)
            .defenseType(DefenseType.NONE)
            .prerequisites(java.util.Map.of("speed", 70, "combatability", 60))
            .build();
    }

    public static Move divekick() {
        return new Move.Builder("DIVEKICK")
            .name("Divekick")
            .description("A high-speed aerial kick. Interrupts the opponent's current block on hit.")
            .category(MoveCategory.PHYSICAL)
            .basePower(50)
            .baseAccuracy(0.85)
            .apCost(18)
            .unleashPoint(6)
            .baseCeCost(0).minCeCost(0).maxCeCost(0)
            .interruptType(InterruptType.KNOCK_CURRENT_BLOCK)
            .defenseType(DefenseType.NONE)
            .prerequisites(java.util.Map.of("speed", 80, "combatability", 70))
            .build();
    }

    // -------------------------------------------------------------------------
    // CE-reinforced physical moves (Physical + CE category)
    // -------------------------------------------------------------------------

    public static Move cursedStrike() {
        return new Move.Builder("CURSED_STRIKE")
            .name("Cursed Strike")
            .description("A punch infused with cursed energy. Hits harder and can proc Black Flash.")
            .category(MoveCategory.PHYSICAL_CURSED_ENERGY)
            .basePower(70)
            .baseAccuracy(0.92)
            .apCost(20)
            .unleashPoint(14)
            .baseCeCost(20).minCeCost(8).maxCeCost(40)
            .interruptType(InterruptType.NONE)
            .defenseType(DefenseType.NONE)
            .prerequisites(java.util.Map.of("cursedenergyreserves", 60, "jujutsuskill", 50))
            .build();
    }

    public static Move divergentFist() {
        return new Move.Builder("DIVERGENT_FIST")
            .name("Divergent Fist")
            .description(
                "Yuji's unique delayed CE release. CE erupts a moment after the fist lands, "
                + "bypassing the initial defense window. Has high Black Flash potential.")
            .category(MoveCategory.PHYSICAL_CURSED_ENERGY)
            .basePower(85)
            .baseAccuracy(0.90)
            .apCost(25)
            .unleashPoint(20)
            .baseCeCost(30).minCeCost(15).maxCeCost(55)
            .interruptType(InterruptType.NONE)
            .defenseType(DefenseType.NONE)
            .prerequisites(java.util.Map.of(
                "strength", 100,
                "cursedenergyreserves", 80,
                "jujutsuskill", 70
            ))
            .build();
    }

    // -------------------------------------------------------------------------
    // Sukuna — Shrine (Innate Technique) moves
    // -------------------------------------------------------------------------

    public static Move dismantle() {
        return new Move.Builder("DISMANTLE")
            .name("Dismantle")
            .description(
                "Shrine: a non-targeted slash of cursed energy. Cleaves through defenses.")
            .category(MoveCategory.INNATE_TECHNIQUE)
            .basePower(90)
            .baseAccuracy(0.95)
            .apCost(20)
            .unleashPoint(15)
            .baseCeCost(35).minCeCost(20).maxCeCost(60)
            .interruptType(InterruptType.NONE)
            .defenseType(DefenseType.NONE)
            .requiredTechniqueId("SHRINE")
            .prerequisites(java.util.Map.of("cursedtechniquemastery", 80))
            .build();
    }

    public static Move cleave() {
        return new Move.Builder("CLEAVE")
            .name("Cleave")
            .description(
                "Shrine: a targeted slash that scales with the opponent's power. "
                + "The stronger the enemy, the more devastating this becomes.")
            .category(MoveCategory.INNATE_TECHNIQUE)
            .basePower(110)
            .baseAccuracy(0.88)
            .apCost(30)
            .unleashPoint(25)
            .baseCeCost(50).minCeCost(30).maxCeCost(80)
            .interruptType(InterruptType.NONE)
            .defenseType(DefenseType.NONE)
            .requiredTechniqueId("SHRINE")
            .prerequisites(java.util.Map.of("cursedtechniquemastery", 100))
            .build();
    }

    public static Move sukunaFleshyStrike() {
        return new Move.Builder("SUKUNA_FLESHY_STRIKE")
            .name("Fleshy Strike")
            .description(
                "Shrine: a close-range slash combined with raw physical force. "
                + "Fast and interrupts the opponent's next move.")
            .category(MoveCategory.PHYSICAL_INNATE_TECHNIQUE)
            .basePower(80)
            .baseAccuracy(0.90)
            .apCost(22)
            .unleashPoint(8)
            .baseCeCost(25).minCeCost(12).maxCeCost(45)
            .interruptType(InterruptType.KNOCK_NEXT_BLOCK)
            .defenseType(DefenseType.NONE)
            .requiredTechniqueId("SHRINE")
            .prerequisites(java.util.Map.of(
                "strength", 120,
                "cursedtechniquemastery", 90
            ))
            .build();
    }

    // -------------------------------------------------------------------------
    // Utility / defensive
    // -------------------------------------------------------------------------

    public static Move cursedEnergyArmor() {
        return new Move.Builder("CE_ARMOR")
            .name("Cursed Energy Armor")
            .description("Coat the body in cursed energy. Greatly increases defense for the full round.")
            .category(MoveCategory.DEFENSIVE)
            .basePower(0)
            .neverMiss(true)
            .apCost(12)
            .unleashPoint(1)          // instant activation
            .baseCeCost(25).minCeCost(12).maxCeCost(45)
            .defenseType(DefenseType.STAT_BUFF)
            .defenseBuffDuration(-1)  // entire round
            .defenseBuffAmount(30)    // PLACEHOLDER
            .prerequisites(java.util.Map.of("jujutsuskill", 60, "cursedenergyreserves", 60))
            .build();
    }

    public static Move ironwall() {
        return new Move.Builder("IRONWALL")
            .name("Iron Wall")
            .description(
                "A full defensive stance. Completely blocks any move that fires while "
                + "this block is still active on the timeline — mind-games are key.")
            .category(MoveCategory.DEFENSIVE)
            .basePower(0)
            .neverMiss(true)
            .apCost(20)
            .unleashPoint(1)
            .baseCeCost(0).minCeCost(0).maxCeCost(0)
            .defenseType(DefenseType.FULL_BLOCK)
            .prerequisites(java.util.Map.of("combatability", 70, "durability", 70))
            .build();
    }
}
