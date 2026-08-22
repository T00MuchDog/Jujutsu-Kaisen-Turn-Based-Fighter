package com.jjktbf;

import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.DamageCalculator;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.BlockStyle;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.jjktbf.model.character.Equipment;
import com.jjktbf.model.weapon.WeaponType;

class IntangibleTagTest {

    @Test
    void intangibleAttackCannotBeParried() {
        Move attack = intangibleAttack("INTANGIBLE_ATTACK");
        Move parry = parry("PARRY", 5);

        DamageCalculator.DamageResult result = resolve(
            combatant("A", attack, false), defenderWithDefense("D", parry), attack);

        assertTrue(result.isHit());
        assertFalse(result.isParried());
        assertTrue(result.getFinalDamage() > 0);
    }

    @Test
    void intangibleDamageIgnoresEveryBlockStyle() {
        Move attack = intangibleAttack("INTANGIBLE_ATTACK");
        BattleCombatant attacker = combatant("A", attack, false);
        int unblockedDamage = resolve(
            attacker, combatant("BARE", plainAttack("FILLER"), false), attack)
            .getFinalDamage();

        List<Move> blocks = List.of(
            percentageBlock("HALF_BLOCK", 50),
            percentageBlock("FULL_BLOCK", 100),
            flatBlock("FLAT_BLOCK", 10_000));

        for (Move block : blocks) {
            DamageCalculator.DamageResult result = resolve(
                attacker, defenderWithDefense("D_" + block.getId(), block), attack);
            assertTrue(result.isHit(), block.getId() + " must not block an intangible attack");
            assertTrue(result.bypassedBlock());
            assertEquals(unblockedDamage, result.getFinalDamage(),
                block.getId() + " must not reduce intangible damage");
        }
    }

    @Test
    void intangibleAttackBypassesForcedFullBlock() {
        Move attack = intangibleAttack("INTANGIBLE_ATTACK");
        BattleCombatant attacker = combatant("A", attack, false);
        BattleCombatant defender = combatant("D", plainAttack("FILLER"), false);
        int unblockedDamage = resolve(attacker, defender, attack).getFinalDamage();

        DamageCalculator.DamageResult result = DamageCalculator.resolve(
            attacker, defender, attack, 1,
            new SeededRandomSource(new FixedRandom(0.0)), 1, true);

        assertTrue(result.isHit());
        assertTrue(result.bypassedBlock());
        assertEquals(unblockedDamage, result.getFinalDamage());
    }

    @Test
    void intangibleDoesNotBypassDodge() {
        Move attack = intangibleAttack("INTANGIBLE_ATTACK");
        Move dodge = new Move.Builder("DODGE")
            .name("Dodge")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.DODGE)
            .dodgeChance(100)
            .apCost(10)
            .unleashPoint(1)
            .build();

        DamageCalculator.DamageResult result = resolve(
            combatant("A", attack, false), defenderWithDefense("D", dodge), attack);

        assertTrue(result.isDodged());
    }

    @Test
    void intangibleAppliesToEveryHitComponent() {
        Move attack = new Move.Builder("MULTI_INTANGIBLE")
            .name("Multi Intangible")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.INTANGIBLE))
            .hitComponents(List.of(
                new HitComponent(40, MoveCategory.PHYSICAL, 0),
                new HitComponent(60, MoveCategory.PHYSICAL, 1)))
            .neverMiss(true)
            .apCost(10)
            .unleashPoint(1)
            .build();
        Move fullBlock = percentageBlock("FULL_BLOCK", 100);
        BattleCombatant attacker = combatant("A", attack, false);
        BattleCombatant defender = defenderWithDefense("D", fullBlock);

        for (HitComponent component : attack.getHitComponents()) {
            DamageCalculator.DamageResult result = DamageCalculator.resolve(
                attacker, defender, attack, component, 1,
                new SeededRandomSource(new FixedRandom(0.0)), 1);
            assertTrue(result.isHit());
            assertTrue(result.getFinalDamage() > 0);
        }
    }

    @Test
    void intangibleTagSurvivesMoveDataRoundTrip() {
        MoveData data = MoveData.fromMove(intangibleAttack("ROUND_TRIP"));

        assertTrue(data.tags.contains(MoveTag.INTANGIBLE.name()));
        Move restored = data.toMove();
        assertTrue(restored.isIntangible());
        assertTrue(restored.hasTag("INTANGIBLE"));
    }

    private static DamageCalculator.DamageResult resolve(
        BattleCombatant attacker, BattleCombatant defender, Move attack
    ) {
        return DamageCalculator.resolve(
            attacker, defender, attack, 1, new FixedRandom(0.0), 1);
    }

    private static Move intangibleAttack(String id) {
        return new Move.Builder(id)
            .name(id)
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.INTANGIBLE))
            .basePower(100)
            .neverMiss(true)
            .potency(1)
            .apCost(10)
            .unleashPoint(1)
            .build();
    }

    private static Move plainAttack(String id) {
        return new Move.Builder(id)
            .name(id)
            .category(MoveCategory.PHYSICAL)
            .basePower(100)
            .neverMiss(true)
            .apCost(10)
            .unleashPoint(1)
            .build();
    }

    private static Move percentageBlock(String id, int reduction) {
        return new Move.Builder(id)
            .name(id)
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.BLOCK)
            .blockStyle(BlockStyle.PERCENTAGE)
            .blockDamageReduction(reduction)
            .potency(5)
            .apCost(10)
            .unleashPoint(1)
            .build();
    }

    private static Move flatBlock(String id, int reduction) {
        return new Move.Builder(id)
            .name(id)
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.BLOCK)
            .blockStyle(BlockStyle.FLAT)
            .blockFlatReduction(reduction)
            .potency(5)
            .apCost(10)
            .unleashPoint(1)
            .build();
    }

    private static Move parry(String id, int potency) {
        return new Move.Builder(id)
            .name(id)
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.PARRY)
            .potency(potency)
            .parryStaggerTicks(3)
            .apCost(10)
            .unleashPoint(1)
            .build();
    }

    private static BattleCombatant defenderWithDefense(String id, Move defense) {
        BattleCombatant defender = combatant(id, defense, defense.isParry());
        Timeline timeline = new Timeline(30);
        timeline.placeAt(defense, 1, 0);
        defender.setTimeline(timeline);
        return defender;
    }

    private static BattleCombatant combatant(String id, Move move, boolean hasWeapon) {
        CharacterStats stats = new CharacterStats.Builder().build();
        Character character = new SorcererCharacter(
            id, id, stats, null, List.of(move), List.of(),
            hasWeapon ? Equipment.base(WeaponType.KATANA) : Equipment.NONE);
        return new BattleCombatant(character);
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
