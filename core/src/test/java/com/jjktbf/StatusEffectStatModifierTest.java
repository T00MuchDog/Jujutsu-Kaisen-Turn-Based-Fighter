package com.jjktbf;

import com.jjktbf.model.character.BattleStatKey;
import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectTarget;
import com.jjktbf.model.character.AbilityEffectTiming;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.character.StatKey;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.StatusEffect;
import com.jjktbf.model.move.StatusEffectType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusEffectStatModifierTest {

    private static final EnumSet<BattleStatKey> STATUS_BATTLE_STATS = EnumSet.of(
        BattleStatKey.MAX_HP,
        BattleStatKey.MAX_CE,
        BattleStatKey.MAX_AP,
        BattleStatKey.ACCURACY,
        BattleStatKey.EVASION,
        BattleStatKey.POWER,
        BattleStatKey.DEFENSE
    );

    @Test
    void everyBaseAndRuntimeDerivedStatHasIncreaseAndDecreaseEffects() {
        for (StatKey stat : StatKey.values()) {
            assertEquals(1, effectsFor(stat, true));
            assertEquals(1, effectsFor(stat, false));
        }
        for (BattleStatKey stat : STATUS_BATTLE_STATS) {
            assertEquals(1, effectsFor(stat, true));
            assertEquals(1, effectsFor(stat, false));
        }
    }

    @Test
    void everyStatusAppliesItsFlatAmountInTheDeclaredDirection() {
        for (StatusEffectType type : StatusEffectType.values()) {
            BattleCombatant combatant = combatant();
            combatant.addStatusEffect(new StatusEffect(type, 1, 10.0));
            int direction = type.signedMagnitude(1.0) > 0 ? 1 : -1;

            if (type.baseStat() != null) {
                assertEquals(80 + direction * 10,
                    type.baseStat().get(combatant.getEffectiveStats()), type.name());
            } else {
                assertEquals(100.0 + direction * 10,
                    combatant.modifyBattleStat(type.battleStat(), 100.0), 0.0001, type.name());
            }
        }
    }

    @Test
    void baseStatusAdditionsApplyBeforeRuntimeMultipliers() {
        BattleCombatant combatant = combatant();
        AbilityEffectData multiplier = AbilityEffectType.TEMP_STAT_MULTIPLY.createDefault();
        multiplier.stat = StatKey.STRENGTH.fieldName;
        multiplier.doubleValue = 2.0;
        combatant.addRuntimeAbilityEffect(multiplier);
        combatant.addStatusEffect(new StatusEffect(
            StatusEffectType.STRENGTH_INCREASE, 1, 10.0));

        assertEquals(180, combatant.getEffectiveStats().getStrength());
    }

    @Test
    void opposingFractionalBaseStatusesCancelBeforeRounding() {
        BattleCombatant combatant = combatant();
        combatant.addStatusEffect(new StatusEffect(
            StatusEffectType.STRENGTH_INCREASE, 1, 0.5));
        combatant.addStatusEffect(new StatusEffect(
            StatusEffectType.STRENGTH_DECREASE, 1, 0.5));

        assertEquals(80, combatant.getEffectiveStats().getStrength());
    }

    @Test
    void statusDurationsSupportRoundsTicksAndCombinedValues() {
        assertEquals(5, new StatusEffect(
            StatusEffectType.STRENGTH_INCREASE, 0, 5, 10.0).getDurationTicks());
        assertEquals(5, new StatusEffect(
            StatusEffectType.STRENGTH_INCREASE, 1, 5, 10.0).getDurationTicks());
        assertThrows(IllegalArgumentException.class, () -> new StatusEffect(
            StatusEffectType.STRENGTH_INCREASE, 0, 10.0));
        assertThrows(IllegalArgumentException.class, () -> new StatusEffect(
            StatusEffectType.STRENGTH_INCREASE, -2, 10.0));
        assertThrows(IllegalArgumentException.class, () -> new StatusEffect(
            StatusEffectType.STRENGTH_INCREASE, -1, 1, 10.0));
    }

    @Test
    void threeRoundsAndTwentyTicksExpiresOnTickTwentyOfTheFourthRound() {
        BattleCombatant user = combatant();
        BattleCombatant enemy = combatant();
        user.addStatusEffect(new StatusEffect(
            StatusEffectType.STRENGTH_INCREASE, 3, 20, 10.0));
        BattleState state = new BattleState(user, enemy);
        CombatResolver resolver = new CombatResolver(new Random(1));

        for (int remainingRounds = 2; remainingRounds >= 0; remainingRounds--) {
            state.transitionTo(BattleState.Phase.ROUND_END);
            resolver.processRoundEnd(state);
            StatusEffect active = user.getActiveEffects().get(0);
            assertEquals(remainingRounds, active.getDurationRounds());
            assertEquals(20, active.getDurationTicks());
        }

        state.transitionTo(BattleState.Phase.RESOLUTION);
        resolver.beginResolution(state);
        for (int tick = 1; tick < 20; tick++) {
            resolver.resolveTick(state);
            assertTrue(user.hasEffect(StatusEffectType.STRENGTH_INCREASE));
        }
        List<CombatEvent> expiry = resolver.resolveTick(state);

        assertFalse(user.hasEffect(StatusEffectType.STRENGTH_INCREASE));
        assertTrue(expiry.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.STATUS_EXPIRED && event.getTick() == 20));
    }

    @Test
    void tickOnlyEffectAppliedByTheLastMoveExtendsResolutionUntilExpiry() {
        Move utility = new Move.Builder("TICK_STATUS")
            .name("Tick Status")
            .category(MoveCategory.UTILITY)
            .apCost(1)
            .unleashPoint(1)
            .selfEffects(List.of(new StatusEffect(
                StatusEffectType.STRENGTH_INCREASE, 0, 2, 10.0)))
            .build();
        BattleCombatant user = combatant(List.of(utility));
        BattleCombatant enemy = combatant();
        Timeline timeline = new Timeline(5);
        timeline.placeAt(utility, 1, 0);
        user.setTimeline(timeline);
        enemy.setTimeline(new Timeline(5));
        BattleState state = new BattleState(user, enemy);
        state.transitionTo(BattleState.Phase.RESOLUTION);
        CombatResolver resolver = new CombatResolver(new Random(1));

        resolver.beginResolution(state);
        resolver.resolveTick(state);
        assertEquals(1, user.getActiveEffects().get(0).getDurationTicks());
        assertTrue(resolver.hasMoreTicks());

        List<CombatEvent> expiry = resolver.resolveTick(state);
        assertFalse(user.hasEffect(StatusEffectType.STRENGTH_INCREASE));
        assertTrue(expiry.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.STATUS_EXPIRED && event.getTick() == 2));
    }

    @Test
    void invalidMoveStatusDurationsAreNotSilentlyDiscarded() {
        MoveData data = new MoveData();
        data.id = "INVALID_DURATION";
        data.name = "Invalid Duration";
        data.tags = List.of("UTILITY");
        data.apCost = 1;
        data.unleashPoint = 1;
        data.selfEffects = List.of(effect(StatusEffectType.STRENGTH_INCREASE.name(), 10.0));
        data.selfEffects.get(0).durationRounds = 0;

        assertThrows(IllegalArgumentException.class, data::toMove);
    }

    @Test
    void maximumDecreasesClampCurrentResourcePools() {
        BattleCombatant combatant = combatant();

        combatant.addStatusEffect(new StatusEffect(
            StatusEffectType.MAX_HP_DECREASE, 1, 50.0));
        combatant.addStatusEffect(new StatusEffect(
            StatusEffectType.MAX_CURSED_ENERGY_DECREASE, 1, 100.0));

        assertEquals(combatant.getMaxHp(), combatant.getCurrentHp());
        assertEquals(combatant.getMaxCursedEnergy(), combatant.getCurrentCe());
    }

    @Test
    void simultaneousOffsettingMaximumEffectsExpireBeforeOneFinalClamp() {
        BattleCombatant combatant = combatant();
        int baseMaxHp = combatant.getMaxHp();

        AbilityEffectData maxHpIncrease = AbilityEffectType.BATTLE_STAT_ADD.createDefault();
        maxHpIncrease.stringValue = BattleStatKey.MAX_HP.name();
        maxHpIncrease.doubleValue = 50.0;
        maxHpIncrease.durationRounds = 1;
        combatant.addRuntimeAbilityEffect(maxHpIncrease);
        combatant.addStatusEffect(new StatusEffect(
            StatusEffectType.MAX_HP_DECREASE, 1, 50.0));

        assertEquals(baseMaxHp, combatant.getMaxHp());
        assertEquals(baseMaxHp, combatant.getCurrentHp());

        combatant.tickRoundEffects(1);

        assertEquals(baseMaxHp, combatant.getMaxHp());
        assertEquals(baseMaxHp, combatant.getCurrentHp());
    }

    @Test
    void maxResourceStatusApplicationAndExpiryEmitMaximumEvents() {
        Move utility = new Move.Builder("MAX_HP_STATUS")
            .name("Max HP Status")
            .category(MoveCategory.UTILITY)
            .apCost(1)
            .unleashPoint(1)
            .selfEffects(List.of(new StatusEffect(
                StatusEffectType.MAX_HP_DECREASE, 1, 50.0)))
            .build();
        BattleCombatant user = combatant(List.of(utility));
        BattleCombatant enemy = combatant();
        Timeline timeline = new Timeline(1);
        timeline.placeAt(utility, 1, 0);
        user.setTimeline(timeline);
        enemy.setTimeline(new Timeline(1));
        BattleState state = new BattleState(user, enemy);
        state.transitionTo(BattleState.Phase.RESOLUTION);
        CombatResolver resolver = new CombatResolver(new Random(1));

        List<CombatEvent> applied = resolver.resolveRound(state);
        int reducedMaxHp = user.getMaxHp();

        assertEquals(164, reducedMaxHp);
        assertEquals(1, applied.stream()
            .filter(event -> event.getType() == CombatEvent.Type.MAX_HP_CHANGED)
            .filter(event -> event.getIntValue() == reducedMaxHp)
            .count());

        state.transitionTo(BattleState.Phase.ROUND_END);
        List<CombatEvent> expired = resolver.processRoundEnd(state);

        assertEquals(214, user.getMaxHp());
        assertEquals(1, expired.stream()
            .filter(event -> event.getType() == CombatEvent.Type.MAX_HP_CHANGED)
            .filter(event -> event.getIntValue() == user.getMaxHp())
            .count());
    }

    @Test
    void legacyStatStatusesMigrateWithoutInvalidatingTheMove() {
        MoveData data = new MoveData();
        data.id = "LEGACY_STATUS";
        data.name = "Legacy Status";
        data.tags = List.of("UTILITY");
        data.apCost = 1;
        data.unleashPoint = 1;
        data.selfEffects = new ArrayList<>();
        data.selfEffects.add(effect("FOCUS", 0.10));
        data.selfEffects.add(effect("CE_OUTPUT_UP", 15.0));
        data.selfEffects.add(effect("POISON", 5.0));

        Move move = data.toMove();

        assertEquals(List.of(
            StatusEffectType.ACCURACY_INCREASE,
            StatusEffectType.CURSED_ENERGY_OUTPUT_INCREASE),
            move.getSelfEffects().stream().map(StatusEffect::getType).toList());
        assertEquals(10.0, move.getSelfEffects().get(0).getMagnitude());
        assertEquals(15.0, move.getSelfEffects().get(1).getMagnitude());
    }

    @Test
    void negativeLegacyAmountsBecomeExplicitOppositeEffects() {
        MoveData data = new MoveData();
        data.id = "NEGATIVE_LEGACY_STATUS";
        data.name = "Negative Legacy Status";
        data.tags = List.of("UTILITY");
        data.apCost = 1;
        data.unleashPoint = 1;
        data.selfEffects = List.of(effect("FOCUS", -0.10));

        StatusEffect migrated = data.toMove().getSelfEffects().get(0);

        assertEquals(StatusEffectType.ACCURACY_DECREASE, migrated.getType());
        assertEquals(10.0, migrated.getMagnitude());
    }

    @Test
    void legacyAndMissingReferencesRemainExplicitForEditors() {
        assertEquals("Legacy FOCUS (either direction)",
            StatusEffectType.referenceDisplayName("FOCUS"));
        assertEquals("Missing status: POISON",
            StatusEffectType.referenceDisplayName("POISON"));
    }

    @Test
    void refreshedRoundStartMaximumDoesNotClampResourcesBetweenApplications() {
        AbilityEffectData maxHp = automaticStatus(
            StatusEffectType.MAX_HP_INCREASE, AbilityEffectTiming.ROUND_START, 50.0);
        BattleCombatant user = combatant(List.of(), List.of(ability(maxHp)));
        BattleCombatant enemy = combatant();
        BattleState state = new BattleState(user, enemy);
        CombatResolver resolver = new CombatResolver(new Random(1));
        resolver.processRoundStart(state);
        user.heal(1_000);
        assertEquals(264, user.getCurrentHp());
        state.transitionTo(BattleState.Phase.ROUND_END);

        List<CombatEvent> events = resolver.processRoundEnd(state);

        assertEquals(264, user.getMaxHp());
        assertEquals(264, user.getCurrentHp());
        assertEquals(0, events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.MAX_HP_CHANGED)
            .filter(event -> event.getIntValue() != 264)
            .count());
        assertEquals(0, resolver.processRoundStart(state).stream()
            .filter(event -> event.getType() == CombatEvent.Type.MAX_HP_CHANGED)
            .count());
    }

    @Test
    void queuedAutomaticMaximumEventsRetainEachIntermediateValue() {
        AbilityEffectData first = automaticStatus(
            StatusEffectType.MAX_HP_INCREASE, AbilityEffectTiming.FIGHT_START, 10.0);
        AbilityEffectData second = automaticStatus(
            StatusEffectType.MAX_HP_INCREASE, AbilityEffectTiming.FIGHT_START, 20.0);
        BattleCombatant user = combatant(List.of(), List.of(ability(first, second)));
        BattleCombatant enemy = combatant();
        BattleState state = new BattleState(user, enemy);

        List<Integer> maxima = new CombatResolver(new Random(1)).processRoundStart(state).stream()
            .filter(event -> event.getType() == CombatEvent.Type.MAX_HP_CHANGED)
            .map(CombatEvent::getIntValue)
            .toList();

        assertEquals(List.of(224, 244), maxima);
    }

    private static long effectsFor(StatKey stat, boolean increase) {
        return List.of(StatusEffectType.values()).stream()
            .filter(type -> type.baseStat() == stat)
            .filter(type -> (type.signedMagnitude(1.0) > 0) == increase)
            .count();
    }

    private static long effectsFor(BattleStatKey stat, boolean increase) {
        return List.of(StatusEffectType.values()).stream()
            .filter(type -> type.battleStat() == stat)
            .filter(type -> (type.signedMagnitude(1.0) > 0) == increase)
            .count();
    }

    private static BattleCombatant combatant() {
        return combatant(List.of());
    }

    private static BattleCombatant combatant(List<Move> moves) {
        return combatant(moves, List.of());
    }

    private static BattleCombatant combatant(List<Move> moves, List<Ability> abilities) {
        Character character = new SorcererCharacter(
            "STATUS_STATS",
            "Status Stats",
            new CharacterStats.Builder().build(),
            null,
            moves,
            abilities);
        return new BattleCombatant(character);
    }

    private static Ability ability(AbilityEffectData... effects) {
        AbilityData data = new AbilityData();
        data.id = "STATUS_ABILITY";
        data.name = "Status Ability";
        data.category = "PASSIVE";
        data.sourceType = "CHARACTER";
        data.effects = List.of(effects);
        return new Ability(data);
    }

    private static AbilityEffectData automaticStatus(
        StatusEffectType status,
        AbilityEffectTiming timing,
        double amount
    ) {
        AbilityEffectData effect = AbilityEffectType.AUTO_STATUS_APPLY.createDefault();
        effect.stringValue = status.name();
        effect.target = AbilityEffectTarget.SELF.name();
        effect.timing = timing.name();
        effect.durationRounds = timing == AbilityEffectTiming.ROUND_START ? 1 : -1;
        effect.magnitude = amount;
        return effect;
    }

    private static MoveData.StatusEffectData effect(String type, double amount) {
        MoveData.StatusEffectData effect = new MoveData.StatusEffectData();
        effect.type = type;
        effect.durationRounds = 1;
        effect.magnitude = amount;
        return effect;
    }
}
