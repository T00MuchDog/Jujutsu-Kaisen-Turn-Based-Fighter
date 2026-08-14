package com.jjktbf;

import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityConditionRuleData;
import com.jjktbf.model.character.AbilityConditionType;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.character.StatKey;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.AbilityActivationEngine;
import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleStatMode;
import com.jjktbf.model.combat.PowerCalculator;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.combat.SummonUpkeepScaler;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.progression.TechniqueMasteryProgressionData;
import com.jjktbf.model.progression.TechniqueMasteryProgressions;
import com.jjktbf.model.progression.TechniqueMasteryResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class EqualizedBattleStatModeTest {

    @Test
    void blendsAlreadyScaledStatsHalfwayTowardEighty() {
        assertEquals(45, BattleStatMode.EQUALIZED.applyToScaled(10));
        assertEquals(80, BattleStatMode.EQUALIZED.applyToScaled(80));
        assertEquals(115, BattleStatMode.EQUALIZED.applyToScaled(150));
        assertEquals(276, BattleStatMode.EQUALIZED.applyToScaled(472));
        assertEquals(472, BattleStatMode.STANDARD.applyToScaled(472));

        assertEquals(276, BattleStatMode.EQUALIZED.scale(300),
            "raw 300 must first become 472, then blend to 276");
        assertEquals(190, BattleStatMode.EQUALIZED.scaleForAp(300),
            "the AP-specific first scale is also blended as a second layer");
    }

    @Test
    void changesOnlyBattleTimeValuesAndKeepsMovesAndAbilities() {
        Move gatedMove = new Move.Builder("equalized-gated-move")
            .name("Gated Move")
            .category(MoveCategory.PHYSICAL)
            .basePower(20)
            .apCost(5)
            .unleashPoint(1)
            .freeMove(true)
            .prerequisites(Map.of("strength", 300))
            .build();
        AbilityData abilityData = new AbilityData();
        abilityData.id = "equalized-test-ability";
        abilityData.name = "Unchanged Ability";
        Ability ability = new Ability(abilityData);
        CharacterStats peakStats = allStats(300);
        SorcererCharacter character = new SorcererCharacter(
            "equalized-fighter", "Equalized Fighter", peakStats, null,
            List.of(gatedMove), List.of(ability));

        BattleCombatant standard = new BattleCombatant(
            character, character.getAbilities(), BattleStatMode.STANDARD);
        BattleCombatant equalized = new BattleCombatant(
            character, character.getAbilities(), BattleStatMode.EQUALIZED);

        assertSame(character, equalized.getCharacter());
        assertEquals(List.of(gatedMove), equalized.getCharacter().getKnownMoves());
        assertEquals(List.of(ability), equalized.getAbilities());
        assertEquals(300, equalized.getEffectiveStats().getStrength(),
            "authored/passive-adjusted stats remain untouched");
        assertEquals(276, equalized.getRuntimeStat(StatKey.STRENGTH));

        assertEquals(1652, standard.getMaxHp());
        assertEquals(966, equalized.getMaxHp());
        assertEquals(3776, standard.getMaxCursedEnergy());
        assertEquals(2208, equalized.getMaxCursedEnergy());
        assertEquals(300, standard.getMaxApBar());
        assertEquals(190, equalized.getMaxApBar());
        assertEquals(472, PowerCalculator.compute(
            MoveCategory.PHYSICAL, standard.getEffectiveStats(), standard.getStatMode()));
        assertEquals(276, PowerCalculator.compute(
            MoveCategory.PHYSICAL, equalized.getEffectiveStats(), equalized.getStatMode()));
        assertEquals(393, standard.computeCurrentDefense(1));
        assertEquals(230, equalized.computeCurrentDefense(1));

        Move ceMove = new Move.Builder("equalized-ce-cost")
            .name("CE Cost")
            .category(MoveCategory.CURSED_ENERGY)
            .basePower(10)
            .baseCeCost(100)
            .minCeCost(0)
            .maxCeCost(1000)
            .apCost(5)
            .unleashPoint(1)
            .freeMove(true)
            .build();
        assertEquals(6, standard.computeMoveCeCost(ceMove));
        assertEquals(102, equalized.computeMoveCeCost(ceMove));
        assertEquals(0.2, SummonUpkeepScaler.upkeepMultiplier(
            300, BattleStatMode.STANDARD), 1e-9);
        assertEquals(0.6, SummonUpkeepScaler.upkeepMultiplier(
            300, BattleStatMode.EQUALIZED), 1e-9);
    }

    @Test
    void masteryDrivenEffectPercentagesUseEqualizedRuntimeMastery() {
        BattleCombatant standard = combatantWithMastery(300, BattleStatMode.STANDARD);
        BattleCombatant equalized = combatantWithMastery(300, BattleStatMode.EQUALIZED);
        assertEquals(300, TechniqueMasteryResolver.masteryOf(standard));
        assertEquals(276, TechniqueMasteryResolver.masteryOf(equalized));

        TechniqueMasteryProgressionData progression = new TechniqueMasteryProgressionData();
        progression.mode = TechniqueMasteryProgressionData.FORMULA;
        progression.formula = "ctm/3";
        AbilityEffectData percentage = new AbilityEffectData();
        percentage.type = AbilityEffectType.HEAL_HP_PERCENT.name();
        percentage.doubleValue = 0.0;
        percentage.masteryProgression = Map.of(
            TechniqueMasteryProgressions.DOUBLE_VALUE, progression);

        assertEquals(1.0, TechniqueMasteryResolver.resolve(
            percentage, TechniqueMasteryResolver.masteryOf(standard)).doubleValue, 1e-9);
        assertEquals(0.92, TechniqueMasteryResolver.resolve(
            percentage, TechniqueMasteryResolver.masteryOf(equalized)).doubleValue, 1e-9);
    }

    @Test
    void techniqueAbilityValuesUseEqualizedMasteryWithoutChangingTheAbility() {
        TechniqueMasteryProgressionData progression = new TechniqueMasteryProgressionData();
        progression.mode = TechniqueMasteryProgressionData.FORMULA;
        progression.formula = "1+ctm/20";
        AbilityEffectData summonCap = new AbilityEffectData();
        summonCap.type = AbilityEffectType.MAX_ACTIVE_SUMMONS.name();
        summonCap.intValue = 1;
        summonCap.masteryProgression = Map.of(
            TechniqueMasteryProgressions.INT_VALUE, progression);
        AbilityData data = new AbilityData();
        data.id = "mastery-passive";
        data.name = "Mastery Passive";
        data.category = "PASSIVE";
        data.sourceType = "TECHNIQUE";
        data.sourceValue = "Test Technique";
        data.effects = List.of(summonCap);
        Ability ability = new Ability(data);
        CharacterStats stats = new CharacterStats.Builder()
            .cursedTechniqueMastery(10)
            .build();
        SorcererCharacter character = new SorcererCharacter(
            "ability-fighter", "Ability Fighter", stats, "Test Technique",
            List.of(), List.of(ability));

        BattleCombatant standard = new BattleCombatant(
            character, character.getAbilities(), BattleStatMode.STANDARD);
        BattleCombatant equalized = new BattleCombatant(
            character, character.getAbilities(), BattleStatMode.EQUALIZED);

        assertEquals(1, standard.getAbilityFlags().maxActiveSummons);
        assertEquals(3, equalized.getAbilityFlags().maxActiveSummons);
        assertEquals(List.of(ability), equalized.getAbilities());
    }

    @Test
    void runtimeStatConditionsReadEqualizedStats() {
        AbilityConditionData condition = AbilityConditionType.STAT_AT_OR_ABOVE.createDefault();
        condition.stat = "strength";
        condition.amount = 300;
        AbilityEffectData heal = AbilityEffectType.HEAL_HP.createDefault();
        heal.target = "SELF";
        heal.intValue = 10;
        AbilityData data = new AbilityData();
        data.id = "runtime-stat-condition";
        data.name = "Runtime Stat Condition";
        data.category = "ACTIVE";
        data.sourceType = "CHARACTER";
        data.effects = List.of(heal);
        data.activationConditions = List.of(AbilityConditionRuleData.allEffects(condition));
        Ability ability = new Ability(data);
        SorcererCharacter character = new SorcererCharacter(
            "condition-fighter", "Condition Fighter", allStats(300), null,
            List.of(), List.of(ability));
        SorcererCharacter enemyCharacter = new SorcererCharacter(
            "condition-enemy", "Condition Enemy", allStats(80), null, List.of());

        BattleCombatant standard = new BattleCombatant(
            character, character.getAbilities(), BattleStatMode.STANDARD);
        BattleCombatant standardEnemy = new BattleCombatant(enemyCharacter);
        BattleState standardState = new BattleState(standard, standardEnemy);
        standard.applyDamage(20);
        new AbilityActivationEngine(new SeededRandomSource(1L)).process(
            standardState, AbilityTrigger.battleStart(standard));

        BattleCombatant equalized = new BattleCombatant(
            character, character.getAbilities(), BattleStatMode.EQUALIZED);
        BattleCombatant equalizedEnemy = new BattleCombatant(
            enemyCharacter, enemyCharacter.getAbilities(), BattleStatMode.EQUALIZED);
        BattleState equalizedState = new BattleState(equalized, equalizedEnemy);
        equalized.applyDamage(20);
        new AbilityActivationEngine(new SeededRandomSource(1L)).process(
            equalizedState, AbilityTrigger.battleStart(equalized));

        assertEquals(standard.getMaxHp() - 10, standard.getCurrentHp());
        assertEquals(equalized.getMaxHp() - 20, equalized.getCurrentHp());
    }

    private static BattleCombatant combatantWithMastery(int mastery, BattleStatMode mode) {
        CharacterStats stats = new CharacterStats.Builder()
            .cursedTechniqueMastery(mastery)
            .build();
        SorcererCharacter character = new SorcererCharacter(
            "mastery-" + mode, "Mastery", stats, "Test Technique", List.of());
        return new BattleCombatant(character, character.getAbilities(), mode);
    }

    private static CharacterStats allStats(int value) {
        return new CharacterStats.Builder()
            .vitality(value)
            .strength(value)
            .durability(value)
            .speed(value)
            .cursedEnergyReserves(value)
            .cursedEnergyEfficiency(value)
            .cursedEnergyOutput(value)
            .jujutsuSkill(value)
            .combatAbility(value)
            .cursedTechniqueMastery(value)
            .build();
    }
}
