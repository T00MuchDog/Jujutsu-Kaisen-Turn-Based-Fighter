package com.jjktbf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.model.character.*;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CombatStats;
import com.jjktbf.model.combat.*;
import com.jjktbf.model.move.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

public class StatVerificationTest {

    @Test
    void sukunaHpShouldBeAround800() {
        Character sukuna = CharacterFactory.createSukuna();
        int hp = sukuna.getCombatStats().getMaxHp();
        System.out.println("Sukuna HP: " + hp);
        assertTrue(hp >= 750 && hp <= 850,
            "Expected Sukuna HP ~800, got: " + hp);
    }

    @Test
    void baselineApBarShouldBeAround80() {
        CharacterStats stats = new CharacterStats.Builder()
            .speed(80).combatAbility(80).build();
        CombatStats cs = new CombatStats(stats);
        int ap = cs.getMaxApBar();
        System.out.println("Baseline AP bar: " + ap);
        // (80*15 + 80*3) / 18 = 1440/18 = 80 exactly
        assertEquals(80, ap, "Expected baseline AP = 80 exactly");
    }

    @Test
    void highStatApBarShouldBeAround380() {
        CharacterStats stats = new CharacterStats.Builder()
            .speed(300).combatAbility(300).build();
        CombatStats cs = new CombatStats(stats);
        int ap = cs.getMaxApBar();
        System.out.println("Max AP bar (300/300): " + ap);
        // (300*15 + 300*3) / 18 = 5400/18 = 300 exactly
        assertEquals(300, ap, "Expected max AP = 300 exactly");
    }

    @Test
    void equalAccuracyEvasionShouldGive95PercentHitOn100BaseAccuracy() {
        double hit = CombatStats.computeHitChance(80, 80, 1.0);
        System.out.printf("Hit chance (equal stats, 100%% base): %.4f%n", hit);
        assertEquals(0.95, hit, 0.001, "Expected 95% hit chance on equal stats");
    }

    @Test
    void ceEfficiencyBaselineShouldNotChangeCost() {
        // Baseline efficiency = 80, so factor = 80/80 = 1.0, no change
        var move = CoreMoves.cursedStrike(); // baseCeCost = 20
        int cost = com.jjktbf.model.combat.CeEfficiencyCalculator.computeActualCost(move, 80);
        System.out.println("CE cost at baseline efficiency: " + cost);
        assertEquals(20, cost);
    }

    @Test
    void highCeEfficiencyShouldReduceCost() {
        var move = CoreMoves.cursedStrike(); // baseCeCost=20, min=8, max=40
        int cost = com.jjktbf.model.combat.CeEfficiencyCalculator.computeActualCost(move, 200);
        System.out.println("CE cost at efficiency 200: " + cost);
        assertTrue(cost < 20 && cost >= 8, "Cost should be reduced but above min=8, got: " + cost);
    }

    @Test
    void lowCeEfficiencyShouldIncreaseCost() {
        var move = CoreMoves.cursedStrike(); // baseCeCost=20, min=8, max=40
        int cost = com.jjktbf.model.combat.CeEfficiencyCalculator.computeActualCost(move, 30);
        System.out.println("CE cost at efficiency 30: " + cost);
        assertTrue(cost > 20 && cost <= 40, "Cost should be increased but below max=40, got: " + cost);
    }

    @Test
    void zeroCeCostCanStillBeExplicitlyCeBased() {
        Move move = new Move.Builder("ZERO_CE")
            .name("Zero CE Technique")
            .category(MoveCategory.PHYSICAL)
            .apCost(10)
            .unleashPoint(1)
            .baseCeCost(0)
            .hasCeCost(true)
            .minCeCost(0)
            .maxCeCost(0)
            .build();

        assertTrue(move.hasCeCost());
        assertEquals(0, CeEfficiencyCalculator.computeActualCost(move, 80));
    }

    @Test
    void ceOutputUpTemporarilyRaisesCursedEnergyOutput() {
        CharacterStats stats = new CharacterStats.Builder().cursedEnergyOutput(80).build();
        Character character = new SorcererCharacter("CE_OUT", "Output Test", stats, null, List.of());
        BattleCombatant combatant = new BattleCombatant(character);

        combatant.addStatusEffect(new StatusEffect(StatusEffectType.CE_OUTPUT_UP, 1, 15));

        assertEquals(95, combatant.getEffectiveStats().getCursedEnergyOutput());
        combatant.tickStatusEffects();
        assertEquals(80, combatant.getEffectiveStats().getCursedEnergyOutput());
    }

    @Test
    void focusTemporarilyRaisesBaseAccuracy() {
        Character character = new SorcererCharacter(
            "FOCUS", "Focus Test", new CharacterStats.Builder().build(), null, List.of());
        BattleCombatant combatant = new BattleCombatant(character);

        combatant.addStatusEffect(new StatusEffect(StatusEffectType.FOCUS, 1, 0.1));

        assertEquals(0.1, combatant.getStatusBaseAccuracyBonus(), 0.0001);
        combatant.tickStatusEffects();
        assertEquals(0.0, combatant.getStatusBaseAccuracyBonus(), 0.0001);
    }

    @Test
    void bundledMoveDataLoadsAndBuilds() throws IOException {
        Path movesPath = List.of(
                Path.of("data", "moves", "all_moves.json"),
                Path.of("..", "data", "moves", "all_moves.json"))
            .stream()
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(() -> new IOException("Could not locate bundled move data"));

        List<MoveData> moves = new ObjectMapper().readValue(
            movesPath.toFile(), new TypeReference<List<MoveData>>() {});

        assertEquals(22, moves.size());
        moves.forEach(move -> assertDoesNotThrow(move::toMove, move.name));

        MoveData surge = moves.stream()
            .filter(move -> "Cursed Energy Surge".equals(move.name))
            .findFirst()
            .orElseThrow();
        assertEquals(StatusEffectType.CE_OUTPUT_UP.name(), surge.selfEffects.get(0).type);
        assertEquals(15.0, surge.selfEffects.get(0).magnitude);
    }

    @Test
    void bundledMoveMigrationAddsMissingMovesWithoutOverwritingPlayerMoves() throws IOException {
        Path savedMoves = Files.createTempFile("moves", ".json");
        try {
            Files.writeString(savedMoves, """
                [
                  { "id": "000000", "name": "Basic Strike", "description": "Player edit" },
                  { "id": "000001", "name": "Custom Move" }
                ]
                """);
            String bundled = """
                [
                  { "id": "000000", "name": "Basic Strike", "description": "Bundled default" },
                  { "id": "000001", "name": "Jab" }
                ]
                """;

            assertTrue(AppPaths.mergeBundledMoves(savedMoves,
                new ByteArrayInputStream(bundled.getBytes(StandardCharsets.UTF_8))));

            List<MoveData> migrated = new ObjectMapper().readValue(
                savedMoves.toFile(), new TypeReference<List<MoveData>>() {});
            assertEquals(3, migrated.size());
            assertEquals("Player edit", migrated.get(0).description);
            assertEquals("000002", migrated.get(2).id);
            assertEquals("Jab", migrated.get(2).name);
        } finally {
            Files.deleteIfExists(savedMoves);
        }
    }

    @Test
    void yujiShouldHaveExpectedMoveCount() {
        Character yuji = CharacterFactory.createYuji();
        int moveCount = yuji.getKnownMoves().size();
        System.out.println("Yuji move count: " + moveCount);
        // 9 moves defined in CharacterFactory (2 guaranteed + 7 slotted)
        assertEquals(9, moveCount);
    }

    @Test
    void sukunaShouldHaveShrineMovesOnly() {
        Character sukuna = CharacterFactory.createSukuna();
        sukuna.getKnownMoves().stream()
            .filter(m -> m.getRequiredTechniqueId() != null)
            .forEach(m -> {
                System.out.println("Technique move: " + m.getName() + " requires: " + m.getRequiredTechniqueId());
                assertEquals("SHRINE", m.getRequiredTechniqueId());
            });
    }

    @Test
    void printAllStats() {
        Character yuji   = CharacterFactory.createYuji();
        Character sukuna = CharacterFactory.createSukuna();
        System.out.println("YUJI:   " + yuji.getCombatStats());
        System.out.println("SUKUNA: " + sukuna.getCombatStats());
        yuji.getKnownMoves().forEach(m -> System.out.println("  Yuji: " + m));
        sukuna.getKnownMoves().forEach(m -> System.out.println("  Sukuna: " + m));
    }

    /**
     * Verify the damage formula is in a reasonable range.
     * Sukuna Cleave (basePower=110, INNATE_TECHNIQUE) vs Yuji at full CE.
     *
     * Expected: 80–100 damage on Yuji (467 HP) = ~17–21% HP per hit.
     * Formula: basePower × (power/defense) × 0.5 × roll[0.85,1.0]
     *   power  = (CE_base + CTM) / 2 = (289 + 300) / 2 = 294
     *   defense = (CE_RES*3 + DUR*2) / 5 = (160*3 + 190*2)/5 = (480+380)/5 = 172
     *   damage = 110 × (294/172) × 0.5 × ~0.925 ≈ 87
     */
    @Test
    void sukunaCleaveVsYujiDamageShouldBeReasonable() {
        Character sukuna = CharacterFactory.createSukuna();
        Character yuji   = CharacterFactory.createYuji();

        // Compute power and defense manually to verify formula
        int sukunaCTM = sukuna.getBaseStats().getCursedTechniqueMastery(); // 300
        int ceOut     = sukuna.getBaseStats().getCursedEnergyOutput();     // 295
        int ceRes     = sukuna.getBaseStats().getCursedEnergyReserves();   // 300
        int ceEff     = sukuna.getBaseStats().getCursedEnergyEfficiency(); // 250
        int ceBase    = (ceOut * 3 + ceRes * 2 + ceEff) / 6;
        int power     = (ceBase + sukunaCTM) / 2;

        int yujiDur   = yuji.getBaseStats().getDurability();              // 190
        int yujiCeRes = yuji.getBaseStats().getCursedEnergyReserves();    // 160
        int defense   = (yujiCeRes * 3 + yujiDur * 2) / 5;               // full CE

        int basePower = 110; // Cleave
        // damage = basePower × (power/defense) × 0.5 × 1.0 (max roll, no variance)
        double maxDamage = basePower * ((double) power / defense) * 0.5;
        double minDamage = maxDamage * 0.85;

        System.out.printf("Sukuna Cleave vs Yuji — power=%d defense=%d%n", power, defense);
        System.out.printf("Expected damage range: %.1f – %.1f%n", minDamage, maxDamage);
        System.out.printf("Yuji HP: %d%n", yuji.getCombatStats().getMaxHp());

        // Should deal between 70 and 115 (allowing for formula rounding)
        assertTrue(minDamage >= 70 && maxDamage <= 115,
            String.format("Damage out of expected range: %.1f–%.1f", minDamage, maxDamage));
    }

    @Test
    void blockDurationStartsAtFireTickAndUsesApCostWhenDurationIsZero() {
        Move attack = new Move.Builder("TEST_ATTACK")
            .name("Test Attack")
            .category(MoveCategory.PHYSICAL)
            .basePower(50)
            .apCost(10)
            .unleashPoint(1)
            .build();
        Move block = new Move.Builder("TEST_BLOCK")
            .name("Test Block")
            .category(MoveCategory.DEFENSIVE)
            .apCost(10)
            .unleashPoint(3)
            .defenseType(DefenseType.PERCENTAGE_BLOCK)
            .blockDuration(0)
            .blockAffectedTags(List.of("PHYSICAL"))
            .blockDamageReduction(50)
            .build();

        Timeline timeline = new Timeline(30);
        timeline.placeAt(block, 1, 0);

        assertNull(timeline.activeBlockAt(2, attack));
        assertNotNull(timeline.activeBlockAt(3, attack));
        assertNotNull(timeline.activeBlockAt(12, attack));
        assertNull(timeline.activeBlockAt(13, attack));
    }

    @Test
    void blockAffectedTagsFilterIncomingMoveTags() {
        Move physicalAttack = new Move.Builder("PHYSICAL_TEST")
            .name("Physical Test")
            .category(MoveCategory.PHYSICAL)
            .basePower(50)
            .build();
        Move innateAttack = new Move.Builder("INNATE_TEST")
            .name("Innate Test")
            .category(MoveCategory.INNATE_TECHNIQUE)
            .requiredTechniqueId("SHRINE")                                  // technique-tag invariant
            .prerequisites(java.util.Map.of("cursedtechniquemastery", 0))   // technique-tag invariant
            .basePower(50)
            .build();
        Move physicalBlock = new Move.Builder("PHYSICAL_BLOCK")
            .name("Physical Block")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.PERCENTAGE_BLOCK)
            .blockAffectedTags(List.of("PHYSICAL"))
            .blockDamageReduction(50)
            .build();

        Timeline timeline = new Timeline(30);
        timeline.placeAt(physicalBlock, 1, 0);

        assertNotNull(timeline.activeBlockAt(10, physicalAttack));
        assertNull(timeline.activeBlockAt(10, innateAttack));
    }

    /**
     * Block coverage is subset-direction: a block fires iff it covers every
     * damage tag the incoming attack uses (attack tags ⊆ block tags).
     *
     * Example 1: a PHYSICAL-only block does NOT stop a PHYSICAL+CURSED_ENERGY
     * attack — the CE component slips through.
     */
    @Test
    void physicalBlockDoesNotCoverHybridAttack() {
        Move hybridAttack = new Move.Builder("HYBRID_ATK")
            .name("Hybrid Attack")
            .category(MoveCategory.PHYSICAL_CURSED_ENERGY)
            .basePower(50)
            .apCost(10).unleashPoint(1)
            .build();
        Move physicalBlock = new Move.Builder("PHYS_ONLY_BLOCK")
            .name("Physical Only Block")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.PERCENTAGE_BLOCK)
            .apCost(10).unleashPoint(1)
            .blockAffectedTags(List.of("PHYSICAL"))
            .blockDamageReduction(50)
            .build();

        Timeline timeline = new Timeline(30);
        timeline.placeAt(physicalBlock, 1, 0);

        assertNull(timeline.activeBlockAt(5, hybridAttack),
            "A [PHYSICAL]-only block must NOT cover a PHYSICAL+CURSED_ENERGY attack.");
    }

    /**
     * Example 2: a [PHYSICAL, CURSED_ENERGY] block DOES stop a pure-PHYSICAL
     * attack — the block's coverage is a superset of the attack's tags.
     */
    @Test
    void dualTagBlockCoversPhysicalSubset() {
        Move physicalAttack = new Move.Builder("PURE_PHYS")
            .name("Pure Physical")
            .category(MoveCategory.PHYSICAL)
            .basePower(50)
            .apCost(10).unleashPoint(1)
            .build();
        Move dualBlock = new Move.Builder("DUAL_BLOCK")
            .name("Dual Block")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.PERCENTAGE_BLOCK)
            .apCost(10).unleashPoint(1)
            .blockAffectedTags(List.of("PHYSICAL", "CURSED_ENERGY"))
            .blockDamageReduction(50)
            .build();

        Timeline timeline = new Timeline(30);
        timeline.placeAt(dualBlock, 1, 0);

        assertNotNull(timeline.activeBlockAt(5, physicalAttack),
            "A [PHYSICAL, CURSED_ENERGY] block MUST cover a pure-PHYSICAL attack.");
    }

    /**
     * Raw CE-only attacks now resolve to the dedicated CURSED_ENERGY category
     * (previously degraded to UTILITY), and compute Power via the CE base formula.
     */
    @Test
    void cursedEnergyOnlyIsADedicatedDamagingCategory() {
        MoveData d = new MoveData();
        d.tags = List.of("CURSED_ENERGY", "ATTACK");
        assertEquals(MoveCategory.CURSED_ENERGY, d.derivedCategory(),
            "A CE-only tag set must derive to the CURSED_ENERGY category.");

        // A CE-only move (no PHYSICAL tag) is a Jujutsu Art — slots granted
        // by Jujutsu Skill, not Combat Ability.
        assertEquals(MovePool.JUJUTSU_ARTS, d.derivedPool(),
            "A CE-only (no PHYSICAL) move must be a Jujutsu Art.");

        Move ceAttack = new Move.Builder("CE_TEST")
            .name("CE Test")
            .category(MoveCategory.CURSED_ENERGY)
            .basePower(50)
            .apCost(10).unleashPoint(1)
            .build();
        assertTrue(ceAttack.hasTag("CURSED_ENERGY"));
        assertFalse(ceAttack.isBlackFlashEligible(),
            "A CE-only move has no physical contact and cannot Black Flash.");

        // Power uses the 3:2:1 CE base formula: (OUT*3 + RES*2 + EFF)/6.
        CharacterStats stats = new CharacterStats.Builder()
            .cursedEnergyOutput(80).cursedEnergyReserves(80).cursedEnergyEfficiency(80).build();
        assertEquals((80 * 3 + 80 * 2 + 80) / 6,
            PowerCalculator.compute(MoveCategory.CURSED_ENERGY, stats));
    }

    @Test
    void blockReductionAppliesBeforeDefenseAndScaleRoll() {
        Move attack = new Move.Builder("TEST_ATTACK")
            .name("Test Attack")
            .category(MoveCategory.PHYSICAL)
            .basePower(100)
            .neverMiss(true)
            .apCost(10)
            .unleashPoint(1)
            .build();
        Move block = new Move.Builder("TEST_BLOCK")
            .name("Test Block")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.PERCENTAGE_BLOCK)
            .blockDamageReduction(50)
            .neverMiss(true)
            .apCost(10)
            .unleashPoint(1)
            .build();

        CharacterStats stats = new CharacterStats.Builder().build();
        Character attackerChar = new SorcererCharacter("A", "Attacker", stats, null, List.of(attack));
        Character defenderChar = new SorcererCharacter("D", "Defender", stats, null, List.of(block));
        BattleCombatant attacker = new BattleCombatant(attackerChar);
        BattleCombatant defender = new BattleCombatant(defenderChar);
        Timeline defenderTimeline = new Timeline(30);
        defenderTimeline.placeAt(block, 1, 0);
        defender.setTimeline(defenderTimeline);

        DamageCalculator.DamageResult result = DamageCalculator.resolve(
            attacker, defender, attack, 1, new FixedRandom(0.0), 1
        );

        assertEquals(21, result.getFinalDamage());
    }

    private static final class FixedRandom extends Random {
        private final double value;

        private FixedRandom(double value) {
            this.value = value;
        }

        @Override
        public double nextDouble() {
            return value;
        }
    }
}
