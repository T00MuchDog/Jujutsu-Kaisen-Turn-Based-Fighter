package com.jjktbf;

import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityConditionRuleData;
import com.jjktbf.model.character.AbilityConditionType;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.character.coded.MiraclesAbility;
import com.jjktbf.model.character.coded.RatioAbility;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.StatusEffect;
import com.jjktbf.model.progression.TechniqueMasteryProgressionData;
import com.jjktbf.model.progression.TechniqueMasteryProgressions;
import com.jjktbf.model.progression.TechniqueMasteryResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TechniqueMasteryRuntimeTest {

    @Test
    void genericEffectsAndEveryConditionNumberUseIntegerAuthoredUnits() {
        AbilityEffectData percent = AbilityEffectType.HEAL_HP_PERCENT.createDefault();
        percent.masteryProgression = Map.of(
            TechniqueMasteryProgressions.DOUBLE_VALUE, formula("ctm / 2"));
        assertEquals(0.4, TechniqueMasteryResolver.resolve(percent, 80).doubleValue);

        AbilityEffectData decimalPoints = AbilityEffectType.BATTLE_STAT_ADD.createDefault();
        decimalPoints.masteryProgression = Map.of(
            TechniqueMasteryProgressions.DOUBLE_VALUE, formula("ctm / 10"));
        assertEquals(8.0, TechniqueMasteryResolver.resolve(decimalPoints, 80).doubleValue);

        AbilityConditionData condition = AbilityConditionType.TIMELINE_POINT_ON_ROUND.createDefault();
        condition.masteryProgression = new LinkedHashMap<>();
        condition.masteryProgression.put(TechniqueMasteryProgressions.TICK, formula("ctm / 4"));
        condition.masteryProgression.put(TechniqueMasteryProgressions.ROUND, formula("ctm / 20"));
        AbilityConditionData resolved = TechniqueMasteryResolver.resolve(condition, 80);
        assertEquals(20, resolved.tick);
        assertEquals(4, resolved.round);

        AbilityConditionData percentage = AbilityConditionType.HP_PERCENT_AT_OR_BELOW.createDefault();
        percentage.masteryProgression = Map.of(
            TechniqueMasteryProgressions.PERCENTAGE, formula("20 + ctm / 10"));
        assertEquals(0.28, TechniqueMasteryResolver.resolve(percentage, 80).percentage);
    }

    @Test
    void moveEffectProgressionSurvivesDtoDomainRoundTrip() {
        MoveData move = new MoveData();
        move.id = "CTM_MOVE";
        move.name = "CTM Move";
        move.tags = List.of("UTILITY", "INNATE_TECHNIQUE", "CURSED_ENERGY");
        move.apCost = 1;
        move.unleashPoint = 1;
        move.requiredTechniqueId = "Test";
        move.prerequisites = Map.of("cursedTechniqueMastery", 0);
        MoveData.StatusEffectData effect = new MoveData.StatusEffectData();
        effect.type = "STRENGTH_INCREASE";
        effect.durationRounds = 1;
        effect.magnitude = 10;
        effect.masteryProgression = Map.of(
            TechniqueMasteryProgressions.MAGNITUDE, formula("10 + ctm / 10"));
        move.selfEffects = new ArrayList<>(List.of(effect));

        StatusEffect domain = move.toMove().getSelfEffects().get(0);
        assertEquals(20.0, TechniqueMasteryResolver.resolve(domain, 100).getMagnitude());

        MoveData restored = MoveData.fromMove(move.toMove());
        assertNotNull(restored.selfEffects.get(0).masteryProgression);
        assertEquals(20,
            restored.selfEffects.get(0).masteryProgression
                .get(TechniqueMasteryProgressions.MAGNITUDE).resolve(100));
    }

    @Test
    void nonTechniqueContentCannotPersistMasteryProgression() {
        AbilityData ability = new AbilityData();
        ability.id = "INVALID";
        ability.name = "Invalid";
        ability.category = "PASSIVE";
        ability.sourceType = "CHARACTER";
        AbilityEffectData effect = AbilityEffectType.STAT_ADD.createDefault();
        effect.masteryProgression = Map.of(
            TechniqueMasteryProgressions.INT_VALUE, formula("ctm"));
        ability.effects = List.of(effect);
        assertThrows(IllegalArgumentException.class, () -> new Ability(ability));
    }

    @Test
    void miraclesAndRatioResolveConfiguredCapacityCurves() {
        Ability reservoir = codedAbility(
            "Reservoir", MiraclesAbility.KEY, MiraclesAbility.RESERVOIR, "PASSIVE",
            Map.of(MiraclesAbility.CAPACITY, 6, MiraclesAbility.STARTING_AMOUNT, 6),
            Map.of(
                MiraclesAbility.CAPACITY, benchmarks(0, 5, 15, 6, 120, 7, 225, 8),
                MiraclesAbility.STARTING_AMOUNT, benchmarks(0, 5, 15, 6)),
            null);
        BattleCombatant miracles = combatant("MIRACLES", "Miracles", 225, List.of(reservoir));
        BattleCombatant enemy = combatant("ENEMY", null, 0, List.of());
        new CombatResolver(new ZeroRandom()).processRoundStart(new BattleState(miracles, enemy));
        var miracleState = miracles.getCodedAbilities().state(MiraclesAbility.KEY).orElseThrow();
        assertEquals(6, miracleState.currentValue());
        assertEquals(8, miracleState.maximumValue());

        AbilityConditionRuleData ratioRule = AbilityConditionRuleData.allEffects(
            AbilityConditionType.ATTACK_CONNECTED.createDefault());
        ratioRule.targetEffectIds = List.of("effect-000000");
        Ability reinforcement = codedAbility(
            "Reinforcement", RatioAbility.KEY, RatioAbility.REINFORCEMENT_RATIO, "ACTIVE",
            Map.of(RatioAbility.STACK_CAPACITY, 3, RatioAbility.DEFENSE_PERCENT, 30),
            Map.of(RatioAbility.STACK_CAPACITY, benchmarks(0, 2, 50, 3, 150, 4, 250, 5)),
            ratioRule);
        BattleCombatant ratio = combatant("RATIO", "Ratio", 250, List.of(reinforcement));
        BattleCombatant target = combatant("TARGET", null, 0, List.of());

        Map<String, Integer> markParameters = Map.of(
            RatioAbility.STACK_DURATION_PARAMETER, 50,
            RatioAbility.TRIGGER_CHANCE_PERCENT, 70,
            RatioAbility.DEFENSE_PERCENT, 30);
        Map<String, TechniqueMasteryProgressionData> markProgression = Map.of(
            TechniqueMasteryProgressions.CODED_STACK_COUNT, benchmarks(0, 1, 200, 2),
            RatioAbility.STACK_DURATION_PARAMETER,
                benchmarks(0, 45, 50, 50, 100, 55, 150, 60, 200, 65, 250, 70, 300, 75));
        StatusEffect mark = StatusEffect.coded(
            RatioAbility.KEY, RatioAbility.RATIO_EFFECT, RatioAbility.CREATE_STACKS, 1,
            markParameters, markProgression);
        mark = TechniqueMasteryResolver.resolve(mark, 250);
        ratio.getCodedAbilities().onEffectFired(
            new BattleState(ratio, target), mark, ratio, target, 1);

        var ratioState = ratio.getCodedAbilities().state(RatioAbility.KEY).orElseThrow();
        assertEquals(2, ratioState.currentValue());
        assertEquals(5, ratioState.maximumValue());
        assertEquals(70, ratio.getCodedAbilities().getRemainingTimelineEffectTicks());
    }

    private static Ability codedAbility(
        String name,
        String key,
        String feature,
        String category,
        Map<String, Integer> parameters,
        Map<String, TechniqueMasteryProgressionData> progression,
        AbilityConditionRuleData rule
    ) {
        AbilityData data = new AbilityData();
        data.id = name;
        data.name = name;
        data.category = category;
        data.sourceType = "TECHNIQUE";
        data.sourceValue = key;
        AbilityEffectData effect = new AbilityEffectData();
        effect.effectId = "effect-000000";
        effect.type = AbilityEffectType.CODED.name();
        effect.codedAbilityKey = key;
        effect.codedFeature = feature;
        effect.codedParameters = new LinkedHashMap<>(parameters);
        effect.masteryProgression = new LinkedHashMap<>(progression);
        data.effects = List.of(effect);
        data.activationConditions = rule == null ? null : List.of(rule);
        return new Ability(data);
    }

    private static BattleCombatant combatant(
        String id,
        String technique,
        int mastery,
        List<Ability> abilities
    ) {
        return new BattleCombatant(new SorcererCharacter(
            id, id,
            new CharacterStats.Builder().cursedTechniqueMastery(mastery).build(),
            technique, List.of(), abilities));
    }

    private static TechniqueMasteryProgressionData formula(String expression) {
        TechniqueMasteryProgressionData data = new TechniqueMasteryProgressionData();
        data.mode = TechniqueMasteryProgressionData.FORMULA;
        data.formula = expression;
        return data;
    }

    private static TechniqueMasteryProgressionData benchmarks(int... values) {
        TechniqueMasteryProgressionData data = new TechniqueMasteryProgressionData();
        data.mode = TechniqueMasteryProgressionData.BENCHMARKS;
        data.benchmarks = new ArrayList<>();
        for (int index = 0; index < values.length; index += 2) {
            data.benchmarks.add(new TechniqueMasteryProgressionData.BenchmarkData(
                values[index], values[index + 1]));
        }
        return data;
    }

    private static final class ZeroRandom implements RandomSource {
        @Override public int nextInt(int bound) { return 0; }
        @Override public double nextDouble() { return 0.0; }
        @Override public boolean nextBoolean() { return false; }
    }
}
