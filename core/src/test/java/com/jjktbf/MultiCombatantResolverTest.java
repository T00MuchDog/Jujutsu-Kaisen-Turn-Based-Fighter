package com.jjktbf;

import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.AbilityEffectTarget;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.ShikigamiCharacter;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.character.coded.NewShadowStyleAbility;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleTeam;
import com.jjktbf.model.combat.BattleTeamId;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.MoveTargeting;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.move.MoveEffectData;
import com.jjktbf.model.move.MoveEffectTrigger;
import com.jjktbf.model.move.StatusEffect;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 coverage for the generalized resolver: multi-combatant firing, AOE
 * targeting, single-target retargeting, launched-projectile survival, AOE
 * dodge resolution, AOE batch-completion draws, and deterministic same-seed
 * multi-combatant resolution.
 */
class MultiCombatantResolverTest {

    @Test
    void enemyOnlyAoeHitsAllEnemiesAndNoAllies() {
        BattleCombatant attacker = fighter("Attacker");
        BattleCombatant ally = fighter("Ally");
        BattleCombatant enemy1 = fighter("Enemy1");
        BattleCombatant enemy2 = fighter("Enemy2");
        BattleTeam players = BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(attacker, ally));
        BattleTeam enemies = BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy1, enemy2));
        BattleState state = new BattleState(players, enemies);

        Move aoe = aoeAttack("AOE");
        BattlePlan plan = planFor(attacker);
        plan.place(aoe, 1, 0);
        attacker.setTimeline(plan.toLegacyTimeline());

        int e1Before = enemy1.getCurrentHp();
        int e2Before = enemy2.getCurrentHp();
        int allyBefore = ally.getCurrentHp();
        resolveRound(state);

        assertTrue(enemy1.getCurrentHp() < e1Before, "enemy1 was hit by AOE");
        assertTrue(enemy2.getCurrentHp() < e2Before, "enemy2 was hit by AOE");
        assertEquals(allyBefore, ally.getCurrentHp(), "ally was NOT hit by enemy-only AOE");
    }

    @Test
    void friendlyFireAoeHitsEveryActiveCombatantExceptCaster() {
        BattleCombatant attacker = fighter("Caster");
        BattleCombatant ally = fighter("Ally");
        BattleCombatant enemy = fighter("Enemy");
        BattleTeam players = BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(attacker, ally));
        BattleTeam enemies = BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy));
        BattleState state = new BattleState(players, enemies);

        Move ffAoe = friendlyFireAoe("FF");
        BattlePlan plan = planFor(attacker);
        plan.place(ffAoe, 1, 0);
        attacker.setTimeline(plan.toLegacyTimeline());

        int allyBefore = ally.getCurrentHp();
        int enemyBefore = enemy.getCurrentHp();
        int casterBefore = attacker.getCurrentHp();
        resolveRound(state);

        assertTrue(ally.getCurrentHp() < allyBefore, "ally was hit by friendly-fire AOE");
        assertTrue(enemy.getCurrentHp() < enemyBefore, "enemy was hit by friendly-fire AOE");
        assertEquals(casterBefore, attacker.getCurrentHp(), "caster was NOT hit by its own AOE");
    }

    @Test
    void invalidSelectedTargetDeterministicallyRetargetsAtFireTime() {
        BattleCombatant attacker = fighter("Attacker");
        BattleCombatant enemy1 = fighter("Enemy1");
        BattleCombatant enemy2 = fighter("Enemy2");
        BattleTeam players = BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(attacker));
        BattleTeam enemies = BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy1, enemy2));
        BattleState state = new BattleState(players, enemies);

        Move attack = physicalAttack("ATK");
        BattlePlan plan = planFor(attacker);
        // Select a non-existent target; resolver must retarget to first living enemy.
        plan.place(attack, 1, 0, new com.jjktbf.model.combat.CombatantId("NONEXISTENT-99"));
        attacker.setTimeline(plan.toLegacyTimeline());

        int enemy1Before = enemy1.getCurrentHp();
        List<CombatEvent> events = resolveRoundEvents(state);

        assertTrue(enemy1.getCurrentHp() < enemy1Before,
            "retargeted to the first living enemy (enemy1)");
        assertTrue(events.stream().anyMatch(e -> e.getType() == CombatEvent.Type.TARGET_RETARGETED),
            "a retarget event was emitted");
    }

    @Test
    void aoeCanBeDodged() {
        BattleCombatant attacker = fighter("Attacker");
        BattleCombatant enemy = fighterWithDodge("Dodger");
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(attacker)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy)));

        Move aoe = aoeAttack("AOE");
        BattlePlan plan = planFor(attacker);
        plan.place(aoe, 1, 0);
        attacker.setTimeline(plan.toLegacyTimeline());
        // Enemy has a 100% dodge up.
        BattlePlan enemyPlan = planFor(enemy);
        enemyPlan.place(fullDodge("DODGE"), 1, 0);
        enemy.setTimeline(enemyPlan.toLegacyTimeline());

        int enemyBefore = enemy.getCurrentHp();
        List<CombatEvent> events = resolveRoundEvents(state);

        assertEquals(enemyBefore, enemy.getCurrentHp(), "AOE was dodged");
        assertTrue(events.stream().anyMatch(
            event -> event.getType() == CombatEvent.Type.MOVE_DODGED));
    }

    @Test
    void launchedProjectileSurvivesSourceDismissal() {
        BattleCombatant attacker = fighter("Projectile User");
        BattleCombatant ally = fighter("Ally");
        BattleCombatant enemy = fighter("Enemy");
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(attacker, ally)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy)));

        Move delayed = delayedAttack("DELAYED", 5);
        BattlePlan attackerPlan = planFor(attacker);
        attackerPlan.place(delayed, 1, 0, enemy.getInstanceId());
        attacker.setTimeline(attackerPlan.toLegacyTimeline());
        BattlePlan enemyPlan = planFor(enemy);
        enemyPlan.place(lethalAttack("KILL_SOURCE"), 2, 0, attacker.getInstanceId());
        enemy.setTimeline(enemyPlan.toLegacyTimeline());

        List<CombatEvent> events = resolveRoundEvents(state);

        assertTrue(attacker.isLifecycleDefeated());
        assertTrue(events.stream().anyMatch(e -> e.getType() == CombatEvent.Type.DAMAGE_DEALT
                && e.getTarget() == enemy),
            "the launched delayed impact resolved despite the source being defeated");
    }

    @Test
    void combatantDefeatedEarlierInTheTickCannotFireItsQueuedAction() {
        BattleCombatant fast = lethalFighter("Fast");
        BattleCombatant victim = slowFighter("Victim");
        BattleCombatant survivingEnemy = fighter("Survivor");
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(fast)),
            BattleState.teamOfFighters(
                BattleTeamId.ENEMY, List.of(victim, survivingEnemy)));
        BattlePlan fastPlan = planFor(fast);
        fastPlan.place(lethalAttack("FAST_KILL"), 1, 0, victim.getInstanceId());
        fast.setTimeline(fastPlan.toLegacyTimeline());
        BattlePlan victimPlan = planFor(victim);
        victimPlan.place(physicalAttack("TOO_LATE"), 1, 0, fast.getInstanceId());
        victim.setTimeline(victimPlan.toLegacyTimeline());

        List<CombatEvent> events = resolveRoundEvents(state);

        assertTrue(victim.isLifecycleDefeated());
        assertTrue(events.stream().noneMatch(event ->
            event.getType() == CombatEvent.Type.MOVE_FIRED
                && event.getSource() == victim));
        assertEquals(1, events.stream().filter(event ->
            event.getType() == CombatEvent.Type.COMBATANT_DEFEATED
                && event.getTarget() == victim).count());
    }

    @Test
    void defeatedSummonerEmitsOneRemovalEventForEachDismissedDescendant() {
        BattleCombatant summoner = fighter("Summoner");
        BattleCombatant enemy = lethalFighter("Enemy");
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(summoner)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy)));
        state.enqueueSummon(summoner, "DOG");
        BattleCombatant summon = state.drainPendingSummons(
            id -> java.util.Optional.of(shikigami("Dog"))).get(0);
        BattlePlan enemyPlan = planFor(enemy);
        enemyPlan.place(lethalAttack("KILL_SUMMONER"), 1, 0, summoner.getInstanceId());
        enemy.setTimeline(enemyPlan.toLegacyTimeline());

        List<CombatEvent> events = resolveRoundEvents(state);

        assertTrue(summon.isRemoved());
        assertEquals(1, events.stream().filter(event ->
            event.getType() == CombatEvent.Type.COMBATANT_REMOVED
                && event.getTarget() == summon).count());
    }

    @Test
    void aoeIncomingMoveHooksAreEvaluatedIndependentlyForEveryTarget() {
        BattleCombatant attacker = fighter("Attacker");
        BattleCombatant first = simpleDomainFighter("First");
        BattleCombatant second = simpleDomainFighter("Second");
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(attacker)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(first, second)));
        activateSimpleDomain(state, first);
        activateSimpleDomain(state, second);
        assertEquals(1, first.getCodedAbilities().state(NewShadowStyleAbility.KEY)
            .orElseThrow().currentValue());
        assertEquals(1, second.getCodedAbilities().state(NewShadowStyleAbility.KEY)
            .orElseThrow().currentValue());
        Move rangedAoe = new Move.Builder("RANGED_AOE")
            .name("Ranged AOE").category(MoveCategory.PHYSICAL).neverMiss(true)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.AOE, MoveTag.RANGED))
            .apCost(2).unleashPoint(1)
            .hitComponents(List.of(new HitComponent(
                20, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
        BattlePlan plan = planFor(attacker);
        plan.place(rangedAoe, 1, 0);
        attacker.setTimeline(plan.toLegacyTimeline());
        int firstHp = first.getCurrentHp();
        int secondHp = second.getCurrentHp();

        List<CombatEvent> events = resolveRoundEvents(state);

        assertEquals(firstHp, first.getCurrentHp());
        assertEquals(secondHp, second.getCurrentHp());
        assertEquals(0, first.getCodedAbilities().state(NewShadowStyleAbility.KEY)
            .orElseThrow().currentValue());
        assertEquals(0, second.getCodedAbilities().state(NewShadowStyleAbility.KEY)
            .orElseThrow().currentValue());
        assertTrue(events.stream().anyMatch(event -> event.getSource() == first));
        assertTrue(events.stream().anyMatch(event -> event.getSource() == second));
    }

    @Test
    void aoeBatchResolvesEveryTargetBeforeVictoryCheck() {
        BattleCombatant playerFighter = fighter("Player Fighter");
        BattleCombatant enemyFighter = fighter("Enemy Fighter");
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(playerFighter)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemyFighter)));
        state.enqueueSummon(playerFighter, "CASTER");
        BattleCombatant caster = state.drainPendingSummons(
            id -> java.util.Optional.of(shikigami("Caster"))).get(0);
        Move aoe = lethalFriendlyFireAoe("WIPE");
        BattlePlan plan = planFor(caster);
        plan.place(aoe, 1, 0);
        caster.setTimeline(plan.toLegacyTimeline());

        List<CombatEvent> events = resolveRoundEvents(state);

        assertTrue(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.DAMAGE_DEALT
                && event.getTarget() == playerFighter));
        assertTrue(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.DAMAGE_DEALT
                && event.getTarget() == enemyFighter));
        assertTrue(caster.isRemoved(), "the first hit dismisses its defeated owner's summon");
        assertTrue(state.isBattleOver());
        assertNull(state.getWinnerTeam(),
            "the second target resolves after the first target eliminates its team");
    }

    @Test
    void deterministicSameSeedMultiCombatantResolution() {
        List<CombatEvent> run1 = resolveWithSeed(12345L);
        List<CombatEvent> run2 = resolveWithSeed(12345L);

        assertEquals(run1.size(), run2.size());
        for (int i = 0; i < run1.size(); i++) {
            assertEquals(run1.get(i).getType(), run2.get(i).getType(),
                "event " + i + " type differs at same seed");
        }
    }

    @Test
    void singleTargetChoiceSurvivesFlattenAndResolvesAgainstChosenTarget() {
        BattleCombatant attacker = fighter("Attacker");
        BattleCombatant enemy1 = fighter("Enemy1");
        BattleCombatant enemy2 = fighter("Enemy2");
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(attacker)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy1, enemy2)));

        Move attack = physicalAttack("ATK");
        BattlePlan plan = planFor(attacker);
        plan.place(attack, 1, 0, enemy2.getInstanceId());
        // Flatten preserves the target id.
        assertEquals(enemy2.getInstanceId(),
            plan.toLegacyTimeline().getSegments().get(0).getTarget());
        attacker.setTimeline(plan.toLegacyTimeline());

        int e1Before = enemy1.getCurrentHp();
        int e2Before = enemy2.getCurrentHp();
        resolveRound(state);

        assertEquals(e1Before, enemy1.getCurrentHp(), "enemy1 was NOT the chosen target");
        assertTrue(enemy2.getCurrentHp() < e2Before, "enemy2 (the chosen target) was hit");
    }

    @Test
    void summonMaterializesAfterEverySameTickMoveHasChosenItsTargets() {
        BattleCombatant summoner = fighter("Summoner");
        BattleCombatant slowerEnemy = slowFighter("Enemy");
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(summoner)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(slowerEnemy)));
        Move summonMove = new Move.Builder("SUMMON_DOG")
            .name("Summon Dog")
            .category(MoveCategory.UTILITY)
            .apCost(2)
            .unleashPoint(1)
            .summonCharacterId("DOG")
            .build();

        BattlePlan summonPlan = planFor(summoner);
        summonPlan.place(summonMove, 1, 0);
        summoner.setTimeline(summonPlan.toLegacyTimeline());
        BattlePlan enemyPlan = planFor(slowerEnemy);
        enemyPlan.place(aoeAttack("SAME_TICK_AOE"), 1, 0);
        slowerEnemy.setTimeline(enemyPlan.toLegacyTimeline());

        state.transitionTo(BattleState.Phase.RESOLUTION);
        List<CombatEvent> events = new CombatResolver(
            new SeededRandomSource(1L), id -> java.util.Optional.of(shikigami("Dog")))
            .resolveRound(state);

        BattleCombatant summon = state.playerTeam().all().stream()
            .filter(BattleCombatant::isSummon)
            .findFirst()
            .orElseThrow();
        assertEquals(summon.getMaxHp(), summon.getCurrentHp());
        assertTrue(events.stream().noneMatch(event ->
            event.getType() == CombatEvent.Type.DAMAGE_DEALT
                && event.getTarget() == summon));
    }

    // --- helpers ----------------------------------------------------------------

    private static List<CombatEvent> resolveRoundEvents(BattleState state) {
        state.transitionTo(BattleState.Phase.RESOLUTION);
        CombatResolver resolver = new CombatResolver(new SeededRandomSource(1L));
        return resolver.resolveRound(state);
    }

    private static void resolveRound(BattleState state) {
        resolveRoundEvents(state);
    }

    private static List<CombatEvent> resolveWithSeed(long seed) {
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(fighter("A1"), fighter("A2"))),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(fighter("E1"), fighter("E2"))));
        Move attack = physicalAttack("ATK");
        for (BattleCombatant c : state.playerTeam().active()) {
            BattlePlan p = planFor(c);
            p.place(attack, 1, 0, state.enemyTeam().active().get(0).getInstanceId());
            c.setTimeline(p.toLegacyTimeline());
        }
        for (BattleCombatant c : state.enemyTeam().active()) {
            BattlePlan p = planFor(c);
            p.place(attack, 1, 0, state.playerTeam().active().get(0).getInstanceId());
            c.setTimeline(p.toLegacyTimeline());
        }
        state.transitionTo(BattleState.Phase.RESOLUTION);
        return new CombatResolver(new SeededRandomSource(seed)).resolveRound(state);
    }

    private static BattlePlan planFor(BattleCombatant c) {
        return new BattlePlan(c.getMaxApBar(), c.getCurrentCe(), 60);
    }

    private static BattleCombatant fighter(String name) {
        CharacterStats stats = new CharacterStats.Builder().vitality(300).speed(100).build();
        SorcererCharacter ch = new SorcererCharacter(
            name.toLowerCase(), name, stats, null, List.of(), List.of(), false);
        return new BattleCombatant(ch, List.of());
    }

    private static BattleCombatant lethalFighter(String name) {
        // High output so a single AOE hit is lethal.
        CharacterStats stats = new CharacterStats.Builder()
            .vitality(80).speed(100).cursedEnergyOutput(300).cursedTechniqueMastery(300).build();
        SorcererCharacter ch = new SorcererCharacter(
            name.toLowerCase(), name, stats, null, List.of(), List.of(), false);
        return new BattleCombatant(ch, List.of());
    }

    private static BattleCombatant fighterWithDodge(String name) {
        CharacterStats stats = new CharacterStats.Builder().vitality(300).speed(101).build();
        SorcererCharacter ch = new SorcererCharacter(
            name.toLowerCase(), name, stats, null, List.of(), List.of(), false);
        return new BattleCombatant(ch, List.of());
    }

    private static BattleCombatant slowFighter(String name) {
        CharacterStats stats = new CharacterStats.Builder().vitality(300).speed(1).build();
        SorcererCharacter ch = new SorcererCharacter(
            name.toLowerCase(), name, stats, null, List.of(), List.of(), false);
        return new BattleCombatant(ch, List.of());
    }

    private static ShikigamiCharacter shikigami(String name) {
        return new ShikigamiCharacter(
            name.toLowerCase(), name,
            new CharacterStats.Builder().vitality(300).speed(100).build(),
            null, List.of(), List.of(), false);
    }

    private static BattleCombatant simpleDomainFighter(String name) {
        String reactionId = "000027";
        Move reaction = new Move.Builder(reactionId)
            .name(name + " Reaction")
            .category(MoveCategory.PHYSICAL_CURSED_ENERGY)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.CURSED_ENERGY, MoveTag.ATTACK,
                MoveTag.MELEE, MoveTag.SWORD))
            .effects(List.of(stunEffect()))
            .neverMiss(true).apCost(2).unleashPoint(1)
            .hitComponents(List.of(new HitComponent(10,
                Set.of(MoveTag.PHYSICAL, MoveTag.CURSED_ENERGY), 0, false, true)))
            .build();
        Move domain = new Move.Builder("000028")
            .name(name + " Domain").category(MoveCategory.UTILITY)
            .apCost(2).unleashPoint(1)
            .selfEffects(List.of(StatusEffect.coded(
                NewShadowStyleAbility.KEY,
                NewShadowStyleAbility.ACTIVATE_SIMPLE_DOMAIN,
                reactionId,
                null)))
            .build();
        CharacterStats stats = new CharacterStats.Builder().vitality(300).speed(100).build();
        SorcererCharacter character = new SorcererCharacter(
            name.toLowerCase(), name, stats, null, List.of(reaction, domain), List.of(), true);
        return new BattleCombatant(character, List.of());
    }

    private static MoveEffectData stunEffect() {
        MoveEffectData effect = AbilityEffectType.STUN_CURRENT_ACTION.createDefaultMoveEffect();
        effect.trigger = MoveEffectTrigger.ON_HIT.name();
        effect.target = AbilityEffectTarget.ENEMY.name();
        return effect;
    }

    private static void activateSimpleDomain(BattleState state, BattleCombatant combatant) {
        StatusEffect activation = combatant.getCharacter().getKnownMoves().stream()
            .flatMap(move -> move.getSelfEffects().stream())
            .findFirst().orElseThrow();
        combatant.getCodedAbilities().onEffectFired(
            state, activation, combatant, null, 0);
    }

    private static Move physicalAttack(String id) {
        return new Move.Builder(id)
            .name(id).category(MoveCategory.PHYSICAL).neverMiss(true)
            .apCost(2).unleashPoint(1)
            .hitComponents(List.of(new HitComponent(20, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
    }

    private static Move delayedAttack(String id, int delay) {
        return new Move.Builder(id)
            .name(id).category(MoveCategory.PHYSICAL).neverMiss(true)
            .apCost(2).unleashPoint(1)
            .hitComponents(List.of(new HitComponent(20, Set.of(MoveTag.PHYSICAL), delay, false, true)))
            .build();
    }

    private static Move aoeAttack(String id) {
        return new Move.Builder(id)
            .name(id).category(MoveCategory.PHYSICAL).neverMiss(true)
            .apCost(2).unleashPoint(1)
            .tags(Set.of(MoveTag.AOE))
            .hitComponents(List.of(new HitComponent(20, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
    }

    private static Move friendlyFireAoe(String id) {
        return new Move.Builder(id)
            .name(id).category(MoveCategory.PHYSICAL).neverMiss(true)
            .apCost(2).unleashPoint(1)
            .tags(Set.of(MoveTag.AOE, MoveTag.FRIENDLY_FIRE))
            .hitComponents(List.of(new HitComponent(15, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
    }

    private static Move lethalFriendlyFireAoe(String id) {
        return new Move.Builder(id)
            .name(id).category(MoveCategory.PHYSICAL).neverMiss(true)
            .apCost(2).unleashPoint(1)
            .tags(Set.of(MoveTag.AOE, MoveTag.FRIENDLY_FIRE))
            .hitComponents(List.of(new HitComponent(100000, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
    }

    private static Move lethalAttack(String id) {
        return new Move.Builder(id)
            .name(id).category(MoveCategory.PHYSICAL).neverMiss(true)
            .apCost(2).unleashPoint(1)
            .hitComponents(List.of(new HitComponent(100000, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
    }

    private static Move lethalAoe(String id) {
        return new Move.Builder(id)
            .name(id).category(MoveCategory.PHYSICAL).neverMiss(true)
            .apCost(2).unleashPoint(1)
            .tags(Set.of(MoveTag.AOE))
            .hitComponents(List.of(new HitComponent(100000, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
    }

    private static Move fullDodge(String id) {
        return new Move.Builder(id)
            .name(id).category(MoveCategory.DEFENSIVE)
            .defenseType(com.jjktbf.model.move.DefenseType.DODGE)
            .dodgeChance(100).dodgeScope("BOTH")
            .apCost(2).unleashPoint(1)
            .build();
    }

    @Test
    void moveTargetingForMoveMatchesTags() {
        assertEquals(MoveTargeting.SINGLE_ENEMY, MoveTargeting.forMove(physicalAttack("X")));
        assertEquals(MoveTargeting.ALL_ENEMIES, MoveTargeting.forMove(aoeAttack("Y")));
        assertEquals(MoveTargeting.ALL_OTHERS, MoveTargeting.forMove(friendlyFireAoe("Z")));
    }

    @Test
    void resolverProcessesAllActiveCombatantsInTick() {
        // Smoke: a 2v2 where both attackers fire resolves both without error.
        BattleCombatant a1 = fighter("A1");
        BattleCombatant a2 = fighter("A2");
        BattleCombatant e1 = fighter("E1");
        BattleCombatant e2 = fighter("E2");
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(a1, a2)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(e1, e2)));

        Move attack = physicalAttack("ATK");
        for (BattleCombatant c : state.activeCombatants()) {
            BattlePlan p = planFor(c);
            BattleCombatant firstEnemy = state.firstActiveEnemyOf(c);
            assertNotNull(firstEnemy);
            p.place(attack, 1, 0, firstEnemy.getInstanceId());
            c.setTimeline(p.toLegacyTimeline());
        }
        List<CombatEvent> events = resolveRoundEvents(state);
        assertFalse(events.isEmpty());
    }
}
