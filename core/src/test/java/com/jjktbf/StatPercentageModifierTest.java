package com.jjktbf;

import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.BattleStatKey;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.character.StatKey;
import com.jjktbf.model.combat.BattleCombatant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the percentage stat primitives ({@link AbilityEffectType#TEMP_STAT_PERCENT}
 * and {@link AbilityEffectType#BATTLE_STAT_PERCENT}) apply to the scaled value and
 * stack additively with each other rather than multiplying.
 */
class StatPercentageModifierTest {

    private static final int BASE_STAT = CharacterStats.BASELINE; // 80

    @Test
    void battleStatPercentRaisesTheScaledValue() {
        BattleCombatant combatant = combatant();
        combatant.addRuntimeAbilityEffect(battlePercent(BattleStatKey.ACCURACY, 0.20));

        assertEquals(120.0, combatant.modifyBattleStat(BattleStatKey.ACCURACY, 100.0), 0.0001);
    }

    @Test
    void battleStatPercentStacksAdditivelyNotMultiplicatively() {
        BattleCombatant combatant = combatant();
        combatant.addRuntimeAbilityEffect(battlePercent(BattleStatKey.ACCURACY, 0.20));
        combatant.addRuntimeAbilityEffect(battlePercent(BattleStatKey.ACCURACY, 0.20));

        // Two +20% modifiers combine as +40% (140), not 1.2 * 1.2 (144).
        assertEquals(140.0, combatant.modifyBattleStat(BattleStatKey.ACCURACY, 100.0), 0.0001);
    }

    @Test
    void battleStatPercentDebuffReducesTheScaledValue() {
        BattleCombatant combatant = combatant();
        combatant.addRuntimeAbilityEffect(battlePercent(BattleStatKey.ACCURACY, -0.20));

        assertEquals(80.0, combatant.modifyBattleStat(BattleStatKey.ACCURACY, 100.0), 0.0001);
    }

    @Test
    void baseStatPercentRaisesTheScaledEffectiveStat() {
        BattleCombatant combatant = combatant();
        combatant.addRuntimeAbilityEffect(statPercent(StatKey.STRENGTH, 0.20));

        assertEquals(Math.round(BASE_STAT * 1.20),
            combatant.getEffectiveStats().getStrength());
    }

    @Test
    void baseStatPercentStacksAdditivelyNotMultiplicatively() {
        BattleCombatant combatant = combatant();
        combatant.addRuntimeAbilityEffect(statPercent(StatKey.STRENGTH, 0.20));
        combatant.addRuntimeAbilityEffect(statPercent(StatKey.STRENGTH, 0.20));

        // Two +20% modifiers combine as +40% (112), not 1.2 * 1.2 (115 after rounding).
        assertEquals(Math.round(BASE_STAT * 1.40),
            combatant.getEffectiveStats().getStrength());
    }

    @Test
    void percentAppliesAfterFlatAdditionsAndMultipliers() {
        BattleCombatant combatant = combatant();

        AbilityEffectData flat = AbilityEffectType.TEMP_STAT_ADD.createDefault();
        flat.stat = StatKey.STRENGTH.fieldName;
        flat.intValue = 20; // 80 -> 100 before the percentage
        combatant.addRuntimeAbilityEffect(flat);

        combatant.addRuntimeAbilityEffect(statPercent(StatKey.STRENGTH, 0.50));

        // Percentage applies to the fully scaled value: (80 + 20) * 1.50 = 150.
        assertEquals(150, combatant.getEffectiveStats().getStrength());
    }

    @Test
    void newPrimitivesAreActivationRequiredAndCleanUpUnusedFields() {
        // Both primitives must be selectable/active in moves and abilities.
        assertTrue(AbilityEffectType.TEMP_STAT_PERCENT.requiresActivation());
        assertTrue(AbilityEffectType.BATTLE_STAT_PERCENT.requiresActivation());

        AbilityEffectData base = AbilityEffectType.TEMP_STAT_PERCENT.createDefault();
        assertNull(base.intValue);
        assertNull(base.magnitude);

        AbilityEffectData battle = AbilityEffectType.BATTLE_STAT_PERCENT.createDefault();
        assertEquals(BattleStatKey.ACCURACY.name(), battle.stringValue);
        assertEquals(0.20, battle.doubleValue);
        assertNull(battle.intValue);
        assertNull(battle.magnitude);
    }

    private static AbilityEffectData statPercent(StatKey stat, double fraction) {
        AbilityEffectData effect = AbilityEffectType.TEMP_STAT_PERCENT.createDefault();
        effect.stat = stat.fieldName;
        effect.doubleValue = fraction;
        return effect;
    }

    private static AbilityEffectData battlePercent(BattleStatKey stat, double fraction) {
        AbilityEffectData effect = AbilityEffectType.BATTLE_STAT_PERCENT.createDefault();
        effect.stringValue = stat.name();
        effect.doubleValue = fraction;
        return effect;
    }

    private static BattleCombatant combatant() {
        Character character = new SorcererCharacter(
            "PERCENT_STATS",
            "Percent Stats",
            new CharacterStats.Builder().build(),
            null,
            java.util.List.of(),
            java.util.List.of());
        return new BattleCombatant(character);
    }
}
