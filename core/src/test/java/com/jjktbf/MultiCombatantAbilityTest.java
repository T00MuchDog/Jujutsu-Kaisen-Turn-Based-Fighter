package com.jjktbf;

import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityConditionActor;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityConditionType;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectTarget;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.ShikigamiCharacter;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.AbilityActivationEngine;
import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleTeamId;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 coverage for multi-combatant ability activation: an on-hit ability
 * effect with target ENEMY fans out to every active enemy in a 2v2, and
 * summoned combatants are driven by the same ability engine.
 */
class MultiCombatantAbilityTest {

    @Test
    void onHitEnemyAbilityEffectFansOutToAllActiveEnemies() {
        // An attacker with an ON_HIT AUTO_STATUS_APPLY (target=ENEMY) passive. In a
        // 2v2, the status must apply to BOTH enemies on the hit, not just one.
        AbilityEffectData onHit = new AbilityEffectData();
        onHit.type = AbilityEffectType.AUTO_STATUS_APPLY.name();
        onHit.target = AbilityEffectTarget.ENEMY.name();
        onHit.timing = "ON_HIT";
        onHit.stringValue = "STRENGTH_DECREASE";
        onHit.durationRounds = 1;
        onHit.magnitude = 10.0;

        AbilityData abilityData = ability("AOE_WEAKEN", onHit);
        BattleCombatant attacker = fighter("Attacker", List.of(abilityData));
        BattleCombatant enemy1 = fighter("Enemy1", List.of());
        BattleCombatant enemy2 = fighter("Enemy2", List.of());
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(attacker)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy1, enemy2)));

        Move aoe = aoeAttack("AOE");
        BattlePlan plan = new BattlePlan(attacker.getMaxApBar(), attacker.getCurrentCe(), 60);
        plan.place(aoe, 1, 0);
        attacker.setTimeline(plan.toLegacyTimeline());

        state.transitionTo(BattleState.Phase.RESOLUTION);
        new CombatResolver(new SeededRandomSource(1L)).resolveRound(state);

        assertTrue(enemy1.getActiveEffects().stream()
                .anyMatch(e -> e.getType().name().equals("STRENGTH_DECREASE")),
            "enemy1 received the on-hit ENEMY status");
        assertTrue(enemy2.getActiveEffects().stream()
                .anyMatch(e -> e.getType().name().equals("STRENGTH_DECREASE")),
            "enemy2 received the on-hit ENEMY status (fanned out to all enemies)");
    }

    @Test
    void engineIteratesEveryActiveCombatantAsAbilityOwner() {
        // Two allied fighters each with an on-hit SELF buff passive: both should
        // apply their buff when their move fires (the engine evaluates every
        // active combatant as an owner, not just one).
        AbilityEffectData selfBuff = new AbilityEffectData();
        selfBuff.type = AbilityEffectType.AUTO_STATUS_APPLY.name();
        selfBuff.target = AbilityEffectTarget.SELF.name();
        selfBuff.timing = "ON_HIT";
        selfBuff.stringValue = "STRENGTH_INCREASE";
        selfBuff.durationRounds = 1;
        selfBuff.magnitude = 10.0;

        BattleCombatant a1 = fighter("A1", List.of(ability("BUFF_A1", selfBuff)));
        BattleCombatant a2 = fighter("A2", List.of(ability("BUFF_A2", selfBuff)));
        BattleCombatant enemy = fighter("Enemy", List.of());
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(a1, a2)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy)));

        Move attack = physicalAttack("ATK");
        BattlePlan p1 = new BattlePlan(a1.getMaxApBar(), a1.getCurrentCe(), 60);
        p1.place(attack, 1, 0, enemy.getInstanceId());
        a1.setTimeline(p1.toLegacyTimeline());
        BattlePlan p2 = new BattlePlan(a2.getMaxApBar(), a2.getCurrentCe(), 60);
        p2.place(attack, 1, 0, enemy.getInstanceId());
        a2.setTimeline(p2.toLegacyTimeline());

        state.transitionTo(BattleState.Phase.RESOLUTION);
        new CombatResolver(new SeededRandomSource(1L)).resolveRound(state);

        assertTrue(a1.getActiveEffects().stream()
                .anyMatch(e -> e.getType().name().equals("STRENGTH_INCREASE")),
            "a1's on-hit SELF buff applied (engine evaluated a1 as an owner)");
        assertTrue(a2.getActiveEffects().stream()
                .anyMatch(e -> e.getType().name().equals("STRENGTH_INCREASE")),
            "a2's on-hit SELF buff applied (engine evaluated a2 as an owner)");
    }

    @Test
    void removedCombatantsDoNotInitiateNewTriggers() {
        // A defeated/removed combatant must not drive ability triggers even if
        // it still holds abilities. Here a summon is removed mid-battle; its
        // abilities must not fire afterward.
        BattleCombatant summoner = fighter("Summoner", List.of());
        BattleCombatant enemy = fighter("Enemy", List.of());
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(summoner)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy)));

        // Manually mark a summon as removed and confirm it's excluded from the
        // active-combatant iteration the engine walks.
        var lookup = new com.jjktbf.model.combat.BattleCharacterLookup() {
            @Override public java.util.Optional<com.jjktbf.model.character.Character> findCharacter(String id) {
                return java.util.Optional.of(shikigami("Dog"));
            }
        };
        state.enqueueSummon(summoner, "DOG");
        BattleCombatant summon = state.drainPendingSummons(lookup).get(0);
        assertTrue(state.activeCombatants().contains(summon));
        // Defeat the summon and reconcile lifecycle so it's removed from active combat.
        summon.receiveDamage(summon.getCurrentHp());
        state.reconcileDefeats();
        assertTrue(summon.isRemoved(),
            "a defeated summon is removed from active combat");
        assertTrue(!state.activeCombatants().contains(summon),
            "removed combatants are excluded from the engine's owner iteration");
    }

    @Test
    void alliedTriggerParticipantsAreNeverTreatedAsTheEnemyActor() {
        AbilityEffectData heal = AbilityEffectType.HEAL_HP.createDefault();
        heal.intValue = 20;
        AbilityData reaction = ability("ENEMY_HIT", heal);
        reaction.category = "ACTIVE";
        reaction.activationCondition = AbilityConditionType.ATTACK_HIT.createDefault();
        reaction.activationCondition.actor = AbilityConditionActor.ENEMY.name();
        BattleCombatant owner = fighter("Owner", List.of(reaction));
        BattleCombatant ally = fighter("Ally", List.of());
        BattleCombatant enemy = fighter("Enemy", List.of());
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(owner, ally)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy)));
        owner.applyDamage(50);
        int damagedHp = owner.getCurrentHp();
        AbilityActivationEngine engine = new AbilityActivationEngine(new SeededRandomSource(1L));

        engine.process(state, AbilityTrigger.move(
            AbilityTrigger.Type.ATTACK_HIT, ally, enemy, physicalAttack("ALLY_HIT"), 1));
        assertEquals(damagedHp, owner.getCurrentHp());

        engine.process(state, AbilityTrigger.move(
            AbilityTrigger.Type.ATTACK_HIT, enemy, ally, physicalAttack("ENEMY_HIT"), 2));
        assertEquals(damagedHp + 20, owner.getCurrentHp());
    }

    @Test
    void phaseConditionsEvaluateEnemyPredicatesAcrossTheOpposingTeam() {
        AbilityConditionData phase = AbilityConditionType.PHASE_REACHED.createDefault();
        phase.phase = BattleState.Phase.RESOLUTION.name();
        AbilityConditionData lowEnemy =
            AbilityConditionType.HP_PERCENT_AT_OR_BELOW.createDefault();
        lowEnemy.actor = AbilityConditionActor.ENEMY.name();
        lowEnemy.percentage = 0.5;
        AbilityEffectData heal = AbilityEffectType.HEAL_HP.createDefault();
        heal.intValue = 20;
        AbilityData reaction = ability("LOW_ENEMY", heal);
        reaction.category = "ACTIVE";
        reaction.activationCondition = AbilityConditionData.all(List.of(phase, lowEnemy));
        BattleCombatant owner = fighter("Owner", List.of(reaction));
        BattleCombatant firstEnemy = fighter("Healthy Enemy", List.of());
        BattleCombatant secondEnemy = fighter("Low Enemy", List.of());
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(owner)),
            BattleState.teamOfFighters(
                BattleTeamId.ENEMY, List.of(firstEnemy, secondEnemy)));
        secondEnemy.applyDamage(secondEnemy.getMaxHp() * 3 / 4);
        owner.applyDamage(50);
        int damagedHp = owner.getCurrentHp();

        new AbilityActivationEngine(new SeededRandomSource(1L)).process(
            state, AbilityTrigger.phase(BattleState.Phase.RESOLUTION));

        assertEquals(damagedHp + 20, owner.getCurrentHp());
    }

    private static BattleCombatant fighter(String name, List<AbilityData> abilityData) {
        List<Ability> abilities = abilityData.stream().map(Ability::new).toList();
        CharacterStats stats = new CharacterStats.Builder()
            .vitality(300).speed(100).build();
        SorcererCharacter c = new SorcererCharacter(
            name.toLowerCase(), name, stats, null, List.of(), abilities, false);
        return new BattleCombatant(c, abilities);
    }

    private static ShikigamiCharacter shikigami(String name) {
        return new ShikigamiCharacter(
            name.toLowerCase(), name, new CharacterStats.Builder().build(),
            null, List.of(), List.of(), false);
    }

    private static AbilityData ability(String id, AbilityEffectData effect) {
        AbilityData data = new AbilityData();
        data.id = id;
        data.name = id;
        data.effects = List.of(effect);
        return data;
    }

    private static Move aoeAttack(String id) {
        return new Move.Builder(id)
            .name(id).category(MoveCategory.PHYSICAL).neverMiss(true)
            .apCost(2).unleashPoint(1)
            .tags(Set.of(MoveTag.AOE))
            .hitComponents(List.of(new HitComponent(10, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
    }

    private static Move physicalAttack(String id) {
        return new Move.Builder(id)
            .name(id).category(MoveCategory.PHYSICAL).neverMiss(true)
            .apCost(2).unleashPoint(1)
            .hitComponents(List.of(new HitComponent(10, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
    }

    @Test
    void existingOwnersBattleStartAbilitiesDoNotRetriggerWhenASummonJoins() {
        AbilityEffectData heal = AbilityEffectType.HEAL_HP.createDefault();
        heal.intValue = 20;
        AbilityData opening = ability("OPENING", heal);
        opening.category = "ACTIVE";
        opening.activationCondition = AbilityConditionType.BATTLE_STARTED.createDefault();
        BattleCombatant owner = fighter("Owner", List.of(opening));
        BattleState state = new BattleState(owner, fighter("Enemy", List.of()));
        CombatResolver resolver = new CombatResolver(new SeededRandomSource(1L));
        owner.applyDamage(50);

        resolver.processRoundStart(state);
        state.enqueueSummon(owner, "DOG");
        state.drainPendingSummons(id -> java.util.Optional.of(shikigami("Dog")));
        owner.applyDamage(10);
        int beforeSecondStart = owner.getCurrentHp();

        List<CombatEvent> secondStart = resolver.processRoundStart(state);

        assertEquals(beforeSecondStart, owner.getCurrentHp());
        assertTrue(secondStart.stream().noneMatch(event ->
            event.getType() == CombatEvent.Type.ABILITY_ACTIVATED
                && event.getSource() == owner));
    }
}
