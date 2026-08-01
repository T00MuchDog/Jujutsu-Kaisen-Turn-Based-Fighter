package com.jjktbf;

import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.DamageCalculator;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.BlockStyle;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the PARRY and DODGE defense types, plus the potency gate that
 * governs when a defence can stop an attack.
 *
 * <p>Rules under test:
 * <ul>
 *   <li>DODGE avoids a hit at the configured % chance, taking no damage
 *       (outcome DODGED, distinct from MISS). Scope filters by range.</li>
 *   <li>PARRY negates a hit entirely (outcome PARRIED) when
 *       {@code parry.potency >= attack.potency}; otherwise the parry is ignored.</li>
 *   <li>A successful parry of a non-GUARD_BREAK attack flags the attacker to be
 *       staggered; a GUARD_BREAK attack is parried (no damage) but NOT staggered.</li>
 *   <li>BLOCK is potency-gated; a higher-potency attack ignores a lower-potency block.</li>
 *   <li>GUARD_BREAK bypasses BLOCK but NOT PARRY/DODGE.</li>
 * </ul>
 */
public class ParryDodgeTest {

    // -------------------------------------------------------------------------
    // Dodge
    // -------------------------------------------------------------------------

    @Test
    void dodgeAt100PercentAvoidsAttack() {
        Move attack = rangedAttack("RANGED");
        Move dodge = dodge("DODGE", 100, "BOTH");

        BattleCombatant attacker = combatant(attack);
        BattleCombatant defender = combatantWithDefense(dodge);

        DamageCalculator.DamageResult result = resolve(attacker, defender, attack);
        assertTrue(result.isDodged(), "100% dodge should avoid the attack (DODGED).");
        assertEquals(0, result.getFinalDamage(), "Dodged attack deals no damage.");
    }

    @Test
    void dodgeAt0PercentNeverAvoidsAttack() {
        Move attack = rangedAttack("RANGED");
        Move dodge = dodge("DODGE", 0, "BOTH");

        BattleCombatant attacker = combatant(attack);
        BattleCombatant defender = combatantWithDefense(dodge);

        DamageCalculator.DamageResult result = resolve(attacker, defender, attack);
        assertFalse(result.isDodged(), "0% dodge should never trigger.");
        assertTrue(result.isHit(), "Attack should land (HIT).");
        assertTrue(result.getFinalDamage() > 0, "Attack should deal damage.");
    }

    @Test
    void dodgeMeleeScopeIgnoresRangedAttack() {
        Move attack = rangedAttack("RANGED");
        Move dodge = dodge("DODGE", 100, "MELEE"); // only reacts to melee

        BattleCombatant attacker = combatant(attack);
        BattleCombatant defender = combatantWithDefense(dodge);

        DamageCalculator.DamageResult result = resolve(attacker, defender, attack);
        assertFalse(result.isDodged(), "MELEE-scoped dodge must not react to a RANGED attack.");
        assertTrue(result.isHit(), "Ranged attack should land through a melee-only dodge.");
    }

    @Test
    void dodgeRangedScopeIgnoresMeleeAttack() {
        Move attack = meleeAttack("MELEE");
        Move dodge = dodge("DODGE", 100, "RANGED"); // only reacts to ranged

        BattleCombatant attacker = combatant(attack);
        BattleCombatant defender = combatantWithDefense(dodge);

        DamageCalculator.DamageResult result = resolve(attacker, defender, attack);
        assertFalse(result.isDodged(), "RANGED-scoped dodge must not react to a MELEE attack.");
        assertTrue(result.isHit(), "Melee attack should land through a ranged-only dodge.");
    }

    @Test
    void dodgeIsNotPotencyGated() {
        // A potency-1 dodge still avoids a potency-5 attack at 100%.
        Move attack = attackWithPotency("P5", 5);
        Move dodge = dodgeWithPotency("D1", 100, "BOTH", 1);

        BattleCombatant attacker = combatant(attack);
        BattleCombatant defender = combatantWithDefense(dodge);

        DamageCalculator.DamageResult result = resolve(attacker, defender, attack);
        assertTrue(result.isDodged(), "Dodge is chance-based and ignores potency.");
    }

    // -------------------------------------------------------------------------
    // Parry
    // -------------------------------------------------------------------------

    @Test
    void parryNegatesEqualPotencyAttack() {
        Move attack = attackWithPotency("ATK", 1);
        Move parry = parry("PARRY", 1, 3);

        BattleCombatant attacker = combatant(attack);
        BattleCombatant defender = combatantWithDefense(parry);

        DamageCalculator.DamageResult result = resolve(attacker, defender, attack);
        assertTrue(result.isParried(), "Parry at equal potency should negate the attack.");
        assertEquals(0, result.getFinalDamage(), "Parried attack deals no damage.");
        assertTrue(result.staggersAttacker(), "Non-guard-break parry should flag attacker stagger.");
        assertEquals(3, result.getParryStaggerTicks(), "Stagger ticks come from the parry move.");
    }

    @Test
    void parryNegatesLowerPotencyAttack() {
        Move attack = attackWithPotency("ATK", 1);
        Move parry = parryWithPotency("PARRY", 2, 3);

        BattleCombatant attacker = combatant(attack);
        BattleCombatant defender = combatantWithDefense(parry);

        DamageCalculator.DamageResult result = resolve(attacker, defender, attack);
        assertTrue(result.isParried(), "Higher-potency parry should negate a lower-potency attack.");
    }

    @Test
    void parryAffectedTagsUseBlockCoverageRules() {
        Move physical = attackWithPotency("PHYSICAL", 1);
        Move cursedEnergy = new Move.Builder("CE")
            .name("CE")
            .category(MoveCategory.CURSED_ENERGY)
            .basePower(100).neverMiss(true).potency(1)
            .apCost(10).unleashPoint(1).build();
        Move nonInnate = new Move.Builder("NON_INNATE")
            .name("NON_INNATE")
            .category(MoveCategory.NON_INNATE_TECHNIQUE)
            .basePower(100).neverMiss(true).potency(1)
            .prerequisites(java.util.Map.of("jujutsuSkill", 0))
            .apCost(10).unleashPoint(1).build();
        Move parry = new Move.Builder("TAGGED_PARRY")
            .name("Tagged Parry")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.PARRY)
            .blockAffectedTags(List.of("PHYSICAL", "CURSED_ENERGY"))
            .potency(1).apCost(10).unleashPoint(1).build();

        assertTrue(resolve(combatant(physical), combatantWithDefense(parry), physical).isParried());
        assertTrue(resolve(combatant(cursedEnergy), combatantWithDefense(parry), cursedEnergy).isParried());
        assertFalse(resolve(
            combatant(nonInnate), combatantWithDefense(parry), nonInnate).isParried());
    }

    @Test
    void parryIgnoredWhenAttackPotencyHigher() {
        Move attack = attackWithPotency("ATK", 5);
        Move parry = parry("PARRY", 1, 3);

        BattleCombatant attacker = combatant(attack);
        BattleCombatant defender = combatantWithDefense(parry);

        DamageCalculator.DamageResult result = resolve(attacker, defender, attack);
        assertFalse(result.isParried(), "Parry below attack potency must be ignored.");
        assertTrue(result.isHit(), "Attack should land through an under-potent parry.");
        assertTrue(result.getFinalDamage() > 0, "Attack should deal damage.");
    }

    @Test
    void parryDoesNotStaggerGuardBreakAttack() {
        // GUARD_BREAK is still parried (no damage) but the attacker is NOT staggered.
        Move attack = guardBreakAttackWithPotency("GB", 1);
        Move parry = parry("PARRY", 1, 3);

        BattleCombatant attacker = combatant(attack);
        BattleCombatant defender = combatantWithDefense(parry);

        DamageCalculator.DamageResult result = resolve(attacker, defender, attack);
        assertTrue(result.isParried(), "Parry still negates a GUARD_BREAK attack's damage.");
        assertEquals(0, result.getFinalDamage(), "Parried guard-break deals no damage.");
        assertFalse(result.staggersAttacker(), "GUARD_BREAK attack must not be staggered by parry.");
    }

    @Test
    void guardBreakDoesNotBypassParryOrDodge() {
        // GUARD_BREAK bypasses BLOCK only — a parry/dodge at equal potency still fires.
        Move gbAttack = guardBreakAttackWithPotency("GB", 1);

        // vs parry
        Move parry = parry("PARRY", 1, 0);
        BattleCombatant attacker = combatant(gbAttack);
        BattleCombatant defender = combatantWithDefense(parry);
        DamageCalculator.DamageResult vsParry = resolve(attacker, defender, gbAttack);
        assertTrue(vsParry.isParried(), "GUARD_BREAK must not bypass a parry.");

        // vs dodge
        Move dodge = dodge("DODGE", 100, "BOTH");
        BattleCombatant attacker2 = combatant(gbAttack);
        BattleCombatant defender2 = combatantWithDefense(dodge);
        DamageCalculator.DamageResult vsDodge = resolve(attacker2, defender2, gbAttack);
        assertTrue(vsDodge.isDodged(), "GUARD_BREAK must not bypass a dodge.");
    }

    // -------------------------------------------------------------------------
    // Block potency gate
    // -------------------------------------------------------------------------

    @Test
    void blockIsIgnoredWhenAttackPotencyHigher() {
        Move attack = attackWithPotency("P5", 5);
        Move block = blockWithPotency("B1", 100, 1); // full block, potency 1

        BattleCombatant attacker = combatant(attack);
        BattleCombatant defender = combatantWithDefense(block);

        DamageCalculator.DamageResult result = resolve(attacker, defender, attack);
        assertFalse(result.isBlocked(), "Lower-potency block must not stop a higher-potency attack.");
        assertTrue(result.isHit(), "Attack should land through an under-potent block.");
    }

    @Test
    void blockStopsEqualPotencyAttack() {
        Move attack = attackWithPotency("P3", 3);
        Move block = blockWithPotency("B3", 100, 3); // full block, potency 3

        BattleCombatant attacker = combatant(attack);
        BattleCombatant defender = combatantWithDefense(block);

        DamageCalculator.DamageResult result = resolve(attacker, defender, attack);
        assertTrue(result.isBlocked(), "Equal-potency block should stop the attack.");
    }

    // -------------------------------------------------------------------------
    // Parry forces weaponRequired + round-trip
    // -------------------------------------------------------------------------

    @Test
    void parryForcesWeaponRequired() {
        Move parry = new Move.Builder("PARRY_MOVE")
            .name("Parry")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.PARRY)
            .parryStaggerTicks(2)
            .apCost(10)
            .unleashPoint(1)
            .build();
        assertTrue(parry.isWeaponRequired(), "A parry move must force weaponRequired on.");
    }

    @Test
    void weaponRequiredMoveRejectedWithoutWeapon() {
        Move weaponMove = new Move.Builder("WEAPON_MOVE")
            .name("Sword Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(50).apCost(10).unleashPoint(1)
            .weaponRequired(true).build();
        CharacterStats stats = new CharacterStats.Builder().build();
        // Without a weapon → rejected.
        assertThrows(IllegalArgumentException.class,
            () -> new SorcererCharacter("ID", "No Weapon", stats, null,
                                        List.of(weaponMove), List.of(), false),
            "A weaponRequired move must be rejected for a weaponless character.");
        // With a weapon → accepted.
        assertDoesNotThrow(
            () -> new SorcererCharacter("ID2", "Armed", stats, null,
                                        List.of(weaponMove), List.of(), true),
            "A weaponRequired move must be accepted for an armed character.");
    }

    @Test
    void defenseFieldsSurviveMoveDataRoundTrip() {
        Move parry = new Move.Builder("PARRY_RT")
            .name("Parry RT")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.PARRY)
            .parryStaggerTicks(4)
            .blockAffectedTags(List.of("PHYSICAL", "CURSED_ENERGY"))
            .potency(2)
            .apCost(10)
            .unleashPoint(1)
            .build();

        MoveData dto = MoveData.fromMove(parry);
        assertEquals(DefenseType.PARRY.name(), dto.defenseType);
        assertEquals(4, dto.parryStaggerTicks);
        assertEquals(2, dto.potency);
        assertEquals(List.of("PHYSICAL", "CURSED_ENERGY"), dto.blockAffectedTags);
        assertTrue(dto.weaponRequired, "weaponRequired should round-trip true for a parry.");

        Move restored = dto.toMove();
        assertTrue(restored.isParry());
        assertEquals(4, restored.getParryStaggerTicks());
        assertEquals(2, restored.getPotency());
        assertEquals(List.of("PHYSICAL", "CURSED_ENERGY"), restored.getBlockAffectedTags());
        assertTrue(restored.isWeaponRequired());
    }

    @Test
    void dodgeFieldsSurviveMoveDataRoundTrip() {
        Move dodge = new Move.Builder("DODGE_RT")
            .name("Dodge RT")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.DODGE)
            .dodgeChance(80)
            .dodgeScope("MELEE")
            .apCost(10)
            .unleashPoint(1)
            .build();

        Move restored = MoveData.fromMove(dodge).toMove();
        assertTrue(restored.isDodge());
        assertEquals(80, restored.getDodgeChance());
        assertEquals("MELEE", restored.getDodgeScope());
    }

    @Test
    void legacyDefenseTypeNamesLoadAsBlock() {
        // Older saves used PERCENTAGE_BLOCK / FLAT_BLOCK directly; the loader must
        // map them to BLOCK + the matching blockStyle.
        MoveData legacy = new MoveData();
        legacy.id = "LEGACY";
        legacy.name = "Legacy Block";
        legacy.tags = List.of(MoveTag.DEFENSIVE.name());
        legacy.apCost = 10;
        legacy.unleashPoint = 1;
        legacy.defenseType = "PERCENTAGE_BLOCK";
        legacy.blockDuration = 5;
        Move restored = legacy.toMove();
        assertTrue(restored.isBlock(), "Legacy PERCENTAGE_BLOCK should load as BLOCK.");
        assertEquals(BlockStyle.PERCENTAGE, restored.getBlockStyle());

        MoveData legacyFlat = new MoveData();
        legacyFlat.id = "LEGACY2";
        legacyFlat.name = "Legacy Flat";
        legacyFlat.tags = List.of(MoveTag.DEFENSIVE.name());
        legacyFlat.apCost = 10;
        legacyFlat.unleashPoint = 1;
        legacyFlat.defenseType = "FLAT_BLOCK";
        Move restoredFlat = legacyFlat.toMove();
        assertTrue(restoredFlat.isBlock(), "Legacy FLAT_BLOCK should load as BLOCK.");
        assertEquals(BlockStyle.FLAT, restoredFlat.getBlockStyle());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static DamageCalculator.DamageResult resolve(
        BattleCombatant attacker, BattleCombatant defender, Move attack) {
        return DamageCalculator.resolve(attacker, defender, attack, 1, new FixedRandom(0.0), 1);
    }

    private static Move rangedAttack(String id) {
        return new Move.Builder(id).name(id).category(MoveCategory.PHYSICAL)
            .basePower(100).neverMiss(true).apCost(10).unleashPoint(1)
            .tags(java.util.Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.RANGED))
            .build();
    }

    private static Move meleeAttack(String id) {
        return new Move.Builder(id).name(id).category(MoveCategory.PHYSICAL)
            .basePower(100).neverMiss(true).apCost(10).unleashPoint(1)
            .tags(java.util.Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.MELEE))
            .build();
    }

    private static Move attackWithPotency(String id, int potency) {
        return new Move.Builder(id).name(id).category(MoveCategory.PHYSICAL)
            .basePower(100).neverMiss(true).apCost(10).unleashPoint(1)
            .potency(potency).build();
    }

    private static Move guardBreakAttackWithPotency(String id, int potency) {
        return new Move.Builder(id).name(id).category(MoveCategory.PHYSICAL)
            .basePower(100).neverMiss(true).guardBreak(true)
            .potency(potency).apCost(10).unleashPoint(1).build();
    }

    private static Move blockWithPotency(String id, int reduction, int potency) {
        return new Move.Builder(id).name(id).category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.BLOCK).blockStyle(BlockStyle.PERCENTAGE)
            .blockDamageReduction(reduction).potency(potency)
            .apCost(10).unleashPoint(1).build();
    }

    private static Move dodge(String id, int chance, String scope) {
        return new Move.Builder(id).name(id).category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.DODGE).dodgeChance(chance).dodgeScope(scope)
            .apCost(10).unleashPoint(1).build();
    }

    private static Move dodgeWithPotency(String id, int chance, String scope, int potency) {
        return new Move.Builder(id).name(id).category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.DODGE).dodgeChance(chance).dodgeScope(scope)
            .potency(potency).apCost(10).unleashPoint(1).build();
    }

    private static Move parry(String id, int potency, int staggerTicks) {
        return new Move.Builder(id).name(id).category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.PARRY).potency(potency).parryStaggerTicks(staggerTicks)
            .apCost(10).unleashPoint(1).build();
    }

    private static Move parryWithPotency(String id, int potency, int staggerTicks) {
        return parry(id, potency, staggerTicks);
    }

    private static BattleCombatant combatant(Move move) {
        return combatant(move, false);
    }

    private static BattleCombatant combatant(Move move, boolean hasWeapon) {
        CharacterStats stats = new CharacterStats.Builder().build();
        Character c = new SorcererCharacter("ID", "Name", stats, null,
            List.of(move), List.of(), hasWeapon);
        return new BattleCombatant(c);
    }

    private static BattleCombatant combatantWithDefense(Move defense) {
        // A parry forces weaponRequired, so its wielder must have a weapon to
        // pass move validation at construction.
        BattleCombatant c = combatant(defense, defense.isParry());
        Timeline tl = new Timeline(30);
        tl.placeAt(defense, 1, 0);
        c.setTimeline(tl);
        return c;
    }

    private static final class FixedRandom extends Random {
        private final double value;
        private FixedRandom(double value) { this.value = value; }
        @Override public double nextDouble() { return value; }
    }
}
