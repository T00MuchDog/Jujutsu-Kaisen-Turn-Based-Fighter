package com.jjktbf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.model.character.AbilityConditionActor;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityConditionType;
import com.jjktbf.model.character.AbilityEffectTarget;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.character.coded.RatioAbility;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.AbilityActivationEngine;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveEffectData;
import com.jjktbf.model.move.MoveEffectTrigger;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.move.StatusEffectType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveEffectCompositionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void canonicalMovesUseOnlyTheUnifiedEffectList() throws IOException {
        List<MoveData> moves = MAPPER.readValue(movesPath().toFile(), new TypeReference<>() { });

        for (MoveData move : moves) {
            assertTrue(move.selfEffects == null || move.selfEffects.isEmpty(), move.name);
            assertTrue(move.onHitEffects == null || move.onHitEffects.isEmpty(), move.name);
            assertFalse(move.summonCharacterId != null && !move.summonCharacterId.isBlank(), move.name);
            move.toMove();
        }
        MoveEffectData sleep = moves.stream()
            .filter(move -> "000071".equals(move.id))
            .flatMap(move -> move.effects.stream())
            .filter(effect -> AbilityEffectType.APPLY_STATUS.name().equals(effect.type))
            .filter(effect -> StatusEffectType.SLEEP.name().equals(effect.stringValue))
            .findFirst().orElseThrow();
        assertEquals(0.05, sleep.perTickRemovalChance == null
            ? StatusEffectType.SLEEP.defaultPerTickRemovalChance()
            : sleep.perTickRemovalChance);
        assertTrue(moves.stream().flatMap(move ->
                (move.effects == null ? List.<MoveEffectData>of() : move.effects).stream())
            .anyMatch(effect -> AbilityEffectType.STUN_CURRENT_ACTION.name().equals(effect.type)));
        assertTrue(moves.stream().noneMatch(move -> Boolean.TRUE.equals(move.stun)));
        assertTrue(moves.stream().noneMatch(move -> move.tags != null
            && move.tags.stream().anyMatch("STUN"::equalsIgnoreCase)));
    }

    @Test
    void utilityTagCombinesWithOtherPurposesWithoutChangingTheDerivedCategory() {
        // Hybrid DEFENSIVE+UTILITY: stays defensive, on-fire rows remain in the
        // unified effect list under the ON_FIRE trigger.
        MoveData simpleDomain = new MoveData();
        simpleDomain.id = "000028";
        simpleDomain.name = "Hybrid Defense";
        simpleDomain.tags = List.of(
            "DEFENSIVE", "UTILITY", "NON_INNATE_TECHNIQUE", "CURSED_ENERGY");
        simpleDomain.apCost = 10;
        simpleDomain.unleashPoint = 2;
        simpleDomain.prerequisites = Map.of("jujutsuSkill", 70);
        MoveEffectData coded = new MoveEffectData();
        coded.effectId = "effect-000000";
        coded.type = AbilityEffectType.CODED_MOVE_ACTION.name();
        coded.codedAbilityKey = "NEW_SHADOW_STYLE";
        coded.codedAction = "ACTIVATE_SIMPLE_DOMAIN";
        coded.codedTarget = "000027";
        coded.target = AbilityEffectTarget.SELF.name();
        coded.trigger = MoveEffectTrigger.ON_FIRE.name();
        simpleDomain.effects = new java.util.ArrayList<>(List.of(coded));
        assertEquals(MoveCategory.DEFENSIVE, simpleDomain.derivedCategory());
        Move defenseBuilt = simpleDomain.toMove();
        assertTrue(defenseBuilt.isDefensive());
        assertEquals(1, defenseBuilt.effectsFor(MoveEffectTrigger.ON_FIRE, -1).size());

        // Hybrid ATTACK+UTILITY: keeps its damaging category so hit components
        // and the power formula are unaffected by the utility section.
        MoveData hybridAttack = new MoveData();
        hybridAttack.id = "hybrid-attack";
        hybridAttack.name = "Hybrid Attack";
        hybridAttack.tags = List.of("ATTACK", "UTILITY", "PHYSICAL");
        hybridAttack.basePower = 10;
        hybridAttack.baseAccuracy = 1.0;
        hybridAttack.apCost = 4;
        hybridAttack.unleashPoint = 2;
        assertEquals(MoveCategory.PHYSICAL, hybridAttack.derivedCategory());
        assertEquals(MoveCategory.PHYSICAL, hybridAttack.toMove().getCategory());

        // Pure utility keeps resolving to UTILITY even with damage-nature tags.
        MoveData pureUtility = new MoveData();
        pureUtility.tags = List.of("UTILITY", "CURSED_ENERGY");
        assertEquals(MoveCategory.UTILITY, pureUtility.derivedCategory());
    }

    @Test
    void tenShadowsAssistedMovesDeclareTheirActiveSummonRestrictions() throws IOException {
        List<MoveData> moves = MAPPER.readValue(movesPath().toFile(), new TypeReference<>() { });
        Map<String, List<String>> expected = Map.of(
            "000045", List.of("000007", "000008"),
            "000046", List.of("000009"),
            "000047", List.of("000010"),
            "000048", List.of("000011"),
            "000049", List.of("000012"),
            "000050", List.of("000014"),
            "000051", List.of("000013")
        );

        for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
            MoveData move = moves.stream()
                .filter(candidate -> entry.getKey().equals(candidate.id))
                .findFirst().orElseThrow();
            List<String> restrictedSummons = move.effects.stream()
                .filter(effect -> AbilityEffectType
                    .MOVE_UNAVAILABLE_WHILE_OWNED_SUMMON_ACTIVE.name()
                    .equals(effect.type))
                .filter(effect -> MoveEffectTrigger.AVAILABILITY.name().equals(effect.trigger))
                .map(effect -> effect.characterId)
                .toList();
            assertEquals(entry.getValue(), restrictedSummons, move.name);
        }
    }

    @Test
    void onHitEffectsHonorTargetConditionChanceAndImmutableMoveStorage() {
        MoveEffectData stagger = AbilityEffectType.APPLY_STATUS.createDefaultMoveEffect();
        stagger.effectId = "effect-000000";
        stagger.trigger = MoveEffectTrigger.ON_HIT.name();
        stagger.stringValue = StatusEffectType.STAGGER.name();
        stagger.target = AbilityEffectTarget.ENEMY.name();
        stagger.durationRounds = 0;
        stagger.durationTicks = 4;
        stagger.magnitude = 0.0;
        stagger.activationChanceEnabled = true;
        stagger.activationChance = 1.0;
        AbilityConditionData condition =
            AbilityConditionType.HP_VALUE_AT_OR_ABOVE.createDefault();
        condition.actor = AbilityConditionActor.ENEMY.name();
        condition.amount = 1;
        stagger.condition = condition;

        Move move = attack("COMPOSED", List.of(stagger));
        MoveEffectData leakedCopy = move.getEffects().get(0);
        leakedCopy.target = AbilityEffectTarget.SELF.name();
        assertEquals(AbilityEffectTarget.ENEMY.name(), move.getEffects().get(0).target);

        BattleCombatant attacker = combatant("ATTACKER", move);
        BattleCombatant target = combatant("TARGET", null);
        List<CombatEvent> events = resolve(attacker, target, move);

        assertTrue(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.STATUS_APPLIED
                && event.getTarget() == target));
        assertFalse(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.STATUS_APPLIED
                && event.getTarget() == attacker));
    }

    @Test
    void instantDeathIsAChanceControlledOnHitPrimitive() {
        MoveEffectData never = AbilityEffectType.INSTANT_KILL.createDefaultMoveEffect();
        never.effectId = "effect-000000";
        never.trigger = MoveEffectTrigger.ON_HIT.name();
        never.target = AbilityEffectTarget.ENEMY.name();
        never.activationChanceEnabled = true;
        never.activationChance = 0.0;

        Move failed = attack("FAILED_EXECUTION", List.of(never));
        BattleCombatant failedUser = combatant("FAILED_USER", failed);
        BattleCombatant survivor = combatant("SURVIVOR", null);
        resolve(failedUser, survivor, failed);
        assertTrue(survivor.getCurrentHp() > 0);

        MoveEffectData certain = never.copy();
        certain.activationChance = 1.0;
        Move successful = attack("SUCCESSFUL_EXECUTION", List.of(certain));
        BattleCombatant executioner = combatant("EXECUTIONER", successful);
        BattleCombatant target = combatant("EXECUTED", null);
        resolve(executioner, target, successful);
        assertTrue(target.isDefeated());
    }

    @Test
    void onFireEnemyEffectsReachEveryResolvedAoeTarget() {
        MoveEffectData debuff = AbilityEffectType.APPLY_STATUS.createDefaultMoveEffect();
        debuff.effectId = "effect-000000";
        debuff.trigger = MoveEffectTrigger.ON_FIRE.name();
        debuff.target = AbilityEffectTarget.ENEMY.name();
        debuff.stringValue = StatusEffectType.STRENGTH_DECREASE.name();
        debuff.durationRounds = 2;
        debuff.durationTicks = 0;
        debuff.magnitude = 10.0;
        Move move = utility("AOE_EFFECT", List.of(debuff));
        BattleCombatant user = combatant("USER", move);
        BattleCombatant first = combatant("FIRST", null);
        BattleCombatant second = combatant("SECOND", null);
        BattleState state = teamState(user, first, second);

        List<CombatEvent> events = new AbilityActivationEngine(new ZeroRandom())
            .processMoveEffects(state, user, List.of(first, second), move,
                MoveEffectTrigger.ON_FIRE, -1, 1);

        assertTrue(first.hasEffect(StatusEffectType.STRENGTH_DECREASE));
        assertTrue(second.hasEffect(StatusEffectType.STRENGTH_DECREASE));
        assertEquals(2, events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.STATUS_APPLIED)
            .filter(event -> event.getMove() == move)
            .count());
    }

    @Test
    void moveEnemyConditionsUseTheCurrentTargetInsteadOfAnotherEnemy() {
        MoveEffectData effect = AbilityEffectType.APPLY_STATUS.createDefaultMoveEffect();
        effect.effectId = "effect-000000";
        effect.trigger = MoveEffectTrigger.ON_HIT.name();
        effect.target = AbilityEffectTarget.ENEMY.name();
        effect.stringValue = StatusEffectType.STRENGTH_DECREASE.name();
        effect.durationRounds = 2;
        effect.durationTicks = 0;
        effect.magnitude = 10.0;
        AbilityConditionData lowHp = AbilityConditionType.HP_VALUE_AT_OR_BELOW.createDefault();
        lowHp.actor = AbilityConditionActor.ENEMY.name();
        effect.condition = lowHp;
        BattleCombatant healthy = combatant("HEALTHY", null);
        BattleCombatant injured = combatant("INJURED", null);
        injured.receiveDamage(injured.getCurrentHp() / 2);
        lowHp.amount = injured.getCurrentHp();
        Move move = attack("TARGET_LOCAL", List.of(effect));
        BattleCombatant user = combatant("USER", move);
        BattleState state = teamState(user, healthy, injured);
        AbilityActivationEngine engine = new AbilityActivationEngine(new ZeroRandom());

        engine.processMoveEffects(state, user, healthy, move,
            MoveEffectTrigger.ON_HIT, 0, 1);
        engine.processMoveEffects(state, user, injured, move,
            MoveEffectTrigger.ON_HIT, 0, 1);

        assertFalse(healthy.hasEffect(StatusEffectType.STRENGTH_DECREASE));
        assertTrue(injured.hasEffect(StatusEffectType.STRENGTH_DECREASE));
    }

    @Test
    void aoeOnFireConditionsAreEvaluatedForEachTarget() {
        MoveEffectData effect = AbilityEffectType.APPLY_STATUS.createDefaultMoveEffect();
        effect.effectId = "effect-000000";
        effect.trigger = MoveEffectTrigger.ON_FIRE.name();
        effect.target = AbilityEffectTarget.ENEMY.name();
        effect.stringValue = StatusEffectType.ACCURACY_DECREASE.name();
        effect.durationRounds = 2;
        effect.durationTicks = 0;
        effect.magnitude = 10.0;
        AbilityConditionData lowHp = AbilityConditionType.HP_VALUE_AT_OR_BELOW.createDefault();
        lowHp.actor = AbilityConditionActor.ENEMY.name();
        effect.condition = lowHp;
        BattleCombatant healthy = combatant("HEALTHY", null);
        BattleCombatant injured = combatant("INJURED", null);
        injured.receiveDamage(injured.getCurrentHp() / 2);
        lowHp.amount = injured.getCurrentHp();
        Move move = utility("CONDITIONAL_AOE", List.of(effect));
        BattleCombatant user = combatant("USER", move);
        BattleState state = teamState(user, healthy, injured);

        new AbilityActivationEngine(new ZeroRandom()).processMoveEffects(
            state, user, List.of(healthy, injured), move,
            MoveEffectTrigger.ON_FIRE, -1, 1);

        assertFalse(healthy.hasEffect(StatusEffectType.ACCURACY_DECREASE));
        assertTrue(injured.hasEffect(StatusEffectType.ACCURACY_DECREASE));
    }

    @Test
    void legacyMultiHitMigrationPreservesComponentFallbackSemantics() {
        MoveData data = new MoveData();
        MoveData.StatusEffectData fallback = new MoveData.StatusEffectData();
        fallback.type = StatusEffectType.STRENGTH_DECREASE.name();
        fallback.durationRounds = 1;
        fallback.magnitude = 5.0;
        data.onHitEffects = List.of(fallback);
        MoveData.HitComponentData first = new MoveData.HitComponentData();
        MoveData.HitComponentData second = new MoveData.HitComponentData();
        MoveData.StatusEffectData specific = new MoveData.StatusEffectData();
        specific.type = StatusEffectType.ACCURACY_DECREASE.name();
        specific.durationRounds = 1;
        specific.magnitude = 10.0;
        second.onHitEffects = List.of(specific);
        data.hitComponents = List.of(first, second);

        assertTrue(data.migrateLegacyEffects());

        assertEquals(2, data.effects.size());
        assertTrue(data.effects.stream().anyMatch(effect ->
            effect.hitComponentIndex == 0
                && StatusEffectType.STRENGTH_DECREASE.name().equals(effect.stringValue)));
        assertTrue(data.effects.stream().anyMatch(effect ->
            effect.hitComponentIndex == 1
                && StatusEffectType.ACCURACY_DECREASE.name().equals(effect.stringValue)));
        assertFalse(data.effects.stream().anyMatch(effect -> effect.hitComponentIndex == null));
        assertEquals(AbilityEffectTarget.ENEMY.name(),
            AbilityEffectType.DESUMMON_TARGET_SHIKIGAMI
                .createDefaultMoveEffect().target);
    }

    @Test
    void onHitRowsCanRequireTheAttackHitCondition() {
        MoveEffectData effect = AbilityEffectType.APPLY_STATUS.createDefaultMoveEffect();
        effect.effectId = "effect-000000";
        effect.trigger = MoveEffectTrigger.ON_HIT.name();
        effect.target = AbilityEffectTarget.ENEMY.name();
        effect.stringValue = StatusEffectType.STRENGTH_DECREASE.name();
        effect.durationRounds = 2;
        effect.durationTicks = 0;
        effect.magnitude = 10.0;
        AbilityConditionData attackHit = AbilityConditionType.ATTACK_HIT.createDefault();
        attackHit.actor = AbilityConditionActor.SELF.name();
        effect.condition = attackHit;
        Move move = attack("ATTACK_HIT_CONDITION", List.of(effect));
        BattleCombatant user = combatant("USER", move);
        BattleCombatant target = combatant("TARGET", null);

        resolve(user, target, move);

        assertTrue(target.hasEffect(StatusEffectType.STRENGTH_DECREASE));
    }

    @Test
    void legacyMoveWideRatioMigratesToAnAllHitPreHitRow() {
        MoveData data = new MoveData();
        MoveData.StatusEffectData ratio = new MoveData.StatusEffectData();
        ratio.codedAbilityKey = RatioAbility.KEY;
        ratio.codedAction = RatioAbility.RATIO_EFFECT;
        ratio.codedTarget = RatioAbility.APPLY_TO_MOVE;
        data.selfEffects = List.of(ratio);

        assertTrue(data.migrateLegacyEffects());

        MoveEffectData migrated = data.effects.get(0);
        assertEquals(MoveEffectTrigger.ON_HIT.name(), migrated.trigger);
        assertNull(migrated.hitComponentIndex);
        assertEquals(AbilityEffectTarget.ENEMY.name(), migrated.target);

        MoveData componentData = new MoveData();
        MoveData.HitComponentData first = new MoveData.HitComponentData();
        MoveData.HitComponentData second = new MoveData.HitComponentData();
        second.onHitEffects = List.of(ratio);
        componentData.hitComponents = List.of(first, second);
        componentData.migrateLegacyEffects();
        assertEquals(1, componentData.effects.get(0).hitComponentIndex);
    }

    private static Move attack(String id, List<MoveEffectData> effects) {
        return new Move.Builder(id)
            .name(id)
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.MELEE))
            .basePower(10)
            .neverMiss(true)
            .apCost(1)
            .unleashPoint(1)
            .effects(effects)
            .build();
    }

    private static Move utility(String id, List<MoveEffectData> effects) {
        return new Move.Builder(id)
            .name(id)
            .category(MoveCategory.UTILITY)
            .tags(Set.of(MoveTag.UTILITY))
            .apCost(1)
            .unleashPoint(1)
            .effects(effects)
            .build();
    }

    private static BattleCombatant combatant(String id, Move move) {
        return new BattleCombatant(new SorcererCharacter(
            id, id, stats(), null, move == null ? List.of() : List.of(move)));
    }

    private static BattleState teamState(
        BattleCombatant user,
        BattleCombatant first,
        BattleCombatant second
    ) {
        return new BattleState(
            BattleState.teamOfFighters(
                com.jjktbf.model.combat.BattleTeamId.PLAYER, List.of(user)),
            BattleState.teamOfFighters(
                com.jjktbf.model.combat.BattleTeamId.ENEMY, List.of(first, second)));
    }

    private static List<CombatEvent> resolve(
        BattleCombatant attacker,
        BattleCombatant target,
        Move move
    ) {
        BattlePlan plan = new BattlePlan(attacker.getMaxApBar(), attacker.getCurrentCe());
        ActionSegment segment = plan.place(move, 1, 0);
        assertNotNull(segment);
        segment.setTarget(target.getInstanceId());
        attacker.setTimeline(plan.toLegacyTimeline());
        BattleState state = new BattleState(attacker, target);
        state.transitionTo(BattleState.Phase.RESOLUTION);
        return new CombatResolver(new ZeroRandom()).resolveRound(state);
    }

    private static CharacterStats stats() {
        return new CharacterStats.Builder()
            .vitality(100)
            .strength(100)
            .durability(100)
            .speed(100)
            .cursedEnergyReserves(100)
            .cursedEnergyEfficiency(100)
            .cursedEnergyOutput(100)
            .jujutsuSkill(100)
            .combatAbility(100)
            .cursedTechniqueMastery(100)
            .build();
    }

    private static Path movesPath() throws IOException {
        return List.of(
                Path.of("data", "moves", "all_moves.json"),
                Path.of("..", "data", "moves", "all_moves.json"))
            .stream()
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(() -> new IOException("Could not locate canonical moves"));
    }

    private static final class ZeroRandom implements RandomSource {
        @Override public int nextInt(int bound) { return 0; }
        @Override public double nextDouble() { return 0.0; }
        @Override public boolean nextBoolean() { return false; }
    }
}
