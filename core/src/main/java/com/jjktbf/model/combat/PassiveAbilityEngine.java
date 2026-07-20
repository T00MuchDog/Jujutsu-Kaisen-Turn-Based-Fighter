package com.jjktbf.model.combat;

import com.jjktbf.model.character.*;
import com.jjktbf.model.move.StatusEffect;
import com.jjktbf.model.move.StatusEffectType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Evaluates passive predicate trees and executes their modular effects. */
public final class PassiveAbilityEngine {

    private static final int MAX_CHAINED_TRIGGERS = 128;

    private final RandomSource rng;

    public PassiveAbilityEngine(RandomSource rng) {
        this.rng = rng;
    }

    public List<CombatEvent> process(BattleState state, AbilityTrigger initialTrigger) {
        List<CombatEvent> events = new ArrayList<>();
        ArrayDeque<AbilityTrigger> triggers = new ArrayDeque<>();
        Set<String> activatedThisChain = new HashSet<>();
        triggers.add(initialTrigger);
        int processed = 0;
        while (!triggers.isEmpty() && processed++ < MAX_CHAINED_TRIGGERS) {
            AbilityTrigger trigger = triggers.removeFirst();
            events.addAll(state.getPlayerCombatant().getCodedAbilities().onTrigger(state, trigger));
            events.addAll(state.getEnemyCombatant().getCodedAbilities().onTrigger(state, trigger));
            evaluateOwner(state, state.getPlayerCombatant(), state.getEnemyCombatant(), trigger,
                events, triggers, activatedThisChain);
            evaluateOwner(state, state.getEnemyCombatant(), state.getPlayerCombatant(), trigger,
                events, triggers, activatedThisChain);
        }
        if (!triggers.isEmpty()) {
            System.err.println("[WARN] Passive ability trigger chain exceeded " + MAX_CHAINED_TRIGGERS + " events.");
        }
        return events;
    }

    private void evaluateOwner(
        BattleState state,
        BattleCombatant owner,
        BattleCombatant enemy,
        AbilityTrigger trigger,
        List<CombatEvent> events,
        ArrayDeque<AbilityTrigger> followUps,
        Set<String> activatedThisChain
    ) {
        List<Ability> abilities = owner.getAbilities();
        for (int index = 0; index < abilities.size(); index++) {
            Ability ability = abilities.get(index);
            if (ability == null || !ability.isPassive() || ability.isCoded()) continue;
            if (ability.isAlwaysActive() && ability.getEffects().stream()
                .noneMatch(effect -> safeType(effect).isTriggeredRuntimeEffect())) {
                continue;
            }

            String key = abilityKey(ability, index);
            boolean eventOpportunity = hasMatchingEventLeaf(
                ability.getActivationCondition(), owner, enemy, state, trigger);
            if (eventOpportunity) owner.recordAbilityTrigger(key, trigger);
            List<AbilityTrigger> history = owner.getAbilityTriggerHistory(key);
            boolean matches = evaluate(
                ability.getActivationCondition(), owner, enemy, state, trigger, history);
            boolean previouslyMatched = owner.wasAbilityConditionTrue(key);
            owner.setAbilityConditionTrue(key, matches);
            if (!matches || (previouslyMatched && !eventOpportunity)) continue;
            if (!history.isEmpty()) {
                owner.clearAbilityTriggerHistory(key);
                owner.setAbilityConditionTrue(key, evaluate(
                    ability.getActivationCondition(), owner, enemy, state, trigger, List.of()));
            }
            String chainKey = System.identityHashCode(owner) + ":" + key;
            if (!activatedThisChain.add(chainKey)) continue;
            double chance = ability.getActivationChance();
            if (chance <= 0.0) continue;
            if (chance < 1.0 && rng.nextDouble() >= chance) continue;

            events.add(CombatEvent.of(CombatEvent.Type.ABILITY_ACTIVATED)
                .source(owner)
                .tick(trigger.tick())
                .message(owner.getCharacter().getName() + " activates " + ability.getName() + "!")
                .build());

            for (AbilityEffectData effect : ability.getEffects()) {
                if (effect == null || effect.type == null) continue;
                AbilityEffectType type = safeType(effect);
                if (ability.isAlwaysActive() && !type.isTriggeredRuntimeEffect()) continue;
                applyEffect(state, owner, enemy, effect, trigger.tick(), events, followUps);
            }
        }
    }

    private boolean evaluate(
        AbilityConditionData condition,
        BattleCombatant owner,
        BattleCombatant enemy,
        BattleState state,
        AbilityTrigger trigger,
        List<AbilityTrigger> history
    ) {
        if (condition == null || condition.containsAlways()) return true;
        AbilityConditionType type;
        try { type = AbilityConditionType.fromName(condition.type); }
        catch (IllegalArgumentException ex) { return false; }

        if (type == AbilityConditionType.ALL) {
            return condition.children != null && !condition.children.isEmpty()
                && condition.children.stream().allMatch(child -> evaluate(child, owner, enemy, state, trigger, history));
        }
        if (type == AbilityConditionType.ANY) {
            return condition.children != null
                && condition.children.stream().anyMatch(child -> evaluate(child, owner, enemy, state, trigger, history));
        }

        return switch (type) {
            case ALWAYS -> true;
            case HP_PERCENT_AT_OR_BELOW -> anyActor(condition, owner, enemy,
                combatant -> ratio(combatant.getCurrentHp(), combatant.getMaxHp()) <= value(condition.percentage));
            case HP_PERCENT_AT_OR_ABOVE -> anyActor(condition, owner, enemy,
                combatant -> ratio(combatant.getCurrentHp(), combatant.getMaxHp()) >= value(condition.percentage));
            case HP_VALUE_AT_OR_BELOW -> anyActor(condition, owner, enemy,
                combatant -> combatant.getCurrentHp() <= value(condition.amount));
            case HP_VALUE_AT_OR_ABOVE -> anyActor(condition, owner, enemy,
                combatant -> combatant.getCurrentHp() >= value(condition.amount));
            case CE_PERCENT_AT_OR_BELOW -> anyActor(condition, owner, enemy,
                combatant -> ratio(combatant.getCurrentCe(), combatant.getMaxCursedEnergy()) <= value(condition.percentage));
            case CE_PERCENT_AT_OR_ABOVE -> anyActor(condition, owner, enemy,
                combatant -> ratio(combatant.getCurrentCe(), combatant.getMaxCursedEnergy()) >= value(condition.percentage));
            case CE_VALUE_AT_OR_BELOW -> anyActor(condition, owner, enemy,
                combatant -> combatant.getCurrentCe() <= value(condition.amount));
            case CE_VALUE_AT_OR_ABOVE -> anyActor(condition, owner, enemy,
                combatant -> combatant.getCurrentCe() >= value(condition.amount));
            case BLACK_FLASH_HIT -> history.stream().anyMatch(candidate ->
                eventLeafMatches(type, condition, owner, enemy, state, candidate));
            case IN_BLACK_FLASH_STATE -> anyActor(condition, owner, enemy, BattleCombatant::isInBlackFlashState);
            case BLACK_FLASH_STREAK_AT_LEAST -> anyActor(condition, owner, enemy,
                combatant -> combatant.getConsecutiveBfsHits() >= value(condition.amount));
            case MOVE_USED, MOVE_TAG_USED, ATTACK_HIT, ATTACK_MISSED, MOVE_BLOCKED,
                 TIMELINE_POINT_REACHED -> history.stream().anyMatch(candidate ->
                eventLeafMatches(type, condition, owner, enemy, state, candidate));
            case ROUND_REACHED -> state.getRoundNumber() >= value(condition.round);
            case TIMELINE_POINT_ON_ROUND, EVERY_N_ROUNDS, PHASE_REACHED, HEALED,
                 DAMAGE_DEALT_AT_LEAST, DAMAGE_TAKEN_AT_LEAST, CE_SPENT_AT_LEAST,
                 CE_LOST_AT_LEAST,
                 CE_RESTORED_AT_LEAST -> history.stream().anyMatch(candidate ->
                eventLeafMatches(type, condition, owner, enemy, state, candidate));
            case STAT_AT_OR_ABOVE -> anyActor(condition, owner, enemy,
                combatant -> statValue(combatant, condition.stat) >= value(condition.amount));
            case STAT_AT_OR_BELOW -> anyActor(condition, owner, enemy,
                combatant -> statValue(combatant, condition.stat) <= value(condition.amount));
            case HAS_STATUS -> anyActor(condition, owner, enemy,
                combatant -> combatant.hasEffect(status(condition.statusType)));
            case DOES_NOT_HAVE_STATUS -> anyActor(condition, owner, enemy,
                combatant -> !combatant.hasEffect(status(condition.statusType)));
            case STATUS_APPLIED, STATUS_REMOVED -> history.stream().anyMatch(candidate ->
                eventLeafMatches(type, condition, owner, enemy, state, candidate));
            case ALL, ANY -> false;
        };
    }

    private void applyEffect(
        BattleState state,
        BattleCombatant owner,
        BattleCombatant enemy,
        AbilityEffectData effect,
        int tick,
        List<CombatEvent> events,
        ArrayDeque<AbilityTrigger> followUps
    ) {
        AbilityEffectType type = safeType(effect);
        List<BattleCombatant> targets = targets(effect, owner, enemy);
        switch (type) {
            case HEAL_HP, HEAL_HP_PERCENT -> {
                for (BattleCombatant target : targets) {
                    int requested = type == AbilityEffectType.HEAL_HP
                        ? value(effect.intValue)
                        : (int) Math.round(target.getMaxHp() * value(effect.doubleValue));
                    int healed = target.heal(requested);
                    if (healed <= 0) continue;
                    events.add(CombatEvent.of(CombatEvent.Type.HP_RESTORED)
                        .source(owner).target(target).intValue(healed).tick(tick)
                        .message(target.getCharacter().getName() + " restores " + healed + " HP!").build());
                    followUps.add(AbilityTrigger.amount(AbilityTrigger.Type.HEALED, target, null, healed, tick));
                }
            }
            case RESTORE_CE, RESTORE_CE_PERCENT -> {
                for (BattleCombatant target : targets) {
                    int requested = type == AbilityEffectType.RESTORE_CE
                        ? value(effect.intValue)
                        : (int) Math.round(target.getMaxCursedEnergy() * value(effect.doubleValue));
                    int restored = target.restoreCe(requested);
                    if (restored <= 0) continue;
                    events.add(CombatEvent.of(CombatEvent.Type.CE_RESTORED)
                        .source(owner).target(target).intValue(restored).tick(tick)
                        .message(target.getCharacter().getName() + " restores " + restored + " CE!").build());
                    followUps.add(AbilityTrigger.amount(AbilityTrigger.Type.CE_RESTORED, target, null, restored, tick));
                }
            }
            case DRAIN_CE, DRAIN_CE_PERCENT -> {
                for (BattleCombatant target : targets) {
                    int requested = type == AbilityEffectType.DRAIN_CE
                        ? value(effect.intValue)
                        : (int) Math.round(target.getMaxCursedEnergy() * value(effect.doubleValue));
                    int drained = target.drainCe(requested);
                    if (drained <= 0) continue;
                    events.add(CombatEvent.of(CombatEvent.Type.CE_DRAINED)
                        .source(owner).target(target).intValue(drained).tick(tick)
                        .message(target.getCharacter().getName() + " loses " + drained + " CE!").build());
                    followUps.add(AbilityTrigger.amount(AbilityTrigger.Type.CE_LOST, target, null, drained, tick));
                }
            }
            case DEAL_DIRECT_DAMAGE, DEAL_MAX_HP_DAMAGE, INSTANT_KILL -> {
                for (BattleCombatant target : targets) {
                    int requested = switch (type) {
                        case DEAL_DIRECT_DAMAGE -> value(effect.intValue);
                        case DEAL_MAX_HP_DAMAGE -> (int) Math.round(target.getMaxHp() * value(effect.doubleValue));
                        case INSTANT_KILL -> 0;
                        default -> 0;
                    };
                    int damage = type == AbilityEffectType.INSTANT_KILL
                        ? target.receiveInstantKill() : target.receiveDamage(requested);
                    for (StatusEffect consumed : target.drainConsumedStatusEffects()) {
                        events.add(CombatEvent.of(CombatEvent.Type.STATUS_EXPIRED)
                            .target(target).tick(tick)
                            .message(consumed.getType() + " was consumed on "
                                + target.getCharacter().getName() + ".").build());
                        followUps.add(AbilityTrigger.status(
                            AbilityTrigger.Type.STATUS_REMOVED,
                            target,
                            consumed.getType(),
                            tick));
                    }
                    events.addAll(target.getCodedAbilities().drainPendingEvents(tick));
                    events.add(CombatEvent.of(damage == 0
                            ? CombatEvent.Type.DAMAGE_IGNORED : CombatEvent.Type.DAMAGE_DEALT)
                        .source(owner).target(target).intValue(damage).tick(tick)
                        .message(damage == 0
                            ? target.getCharacter().getName() + " ignores the ability damage!"
                            : target.getCharacter().getName() + " takes " + damage + " ability damage!")
                        .build());
                    if (damage > 0) {
                        followUps.add(AbilityTrigger.amount(
                            AbilityTrigger.Type.DAMAGE, owner, target, damage, tick));
                    }
                }
            }
            case APPLY_STATUS -> {
                StatusEffectType status = status(effect.stringValue);
                for (BattleCombatant target : targets) {
                    StatusEffect applied = new StatusEffect(
                        status,
                        effect.durationRounds == null ? 1 : effect.durationRounds,
                        effect.magnitude == null ? 0.0 : effect.magnitude);
                    if (extendStatusForCurrentPhase(state)) {
                        target.addStatusEffect(applied, state.getCurrentPhase());
                    } else {
                        target.addStatusEffect(applied);
                    }
                    events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                        .source(owner).target(target).tick(tick)
                        .message(target.getCharacter().getName() + " receives " + status + "!").build());
                    followUps.add(AbilityTrigger.status(AbilityTrigger.Type.STATUS_APPLIED, target, status, tick));
                }
            }
            case REMOVE_STATUS -> {
                StatusEffectType status = status(effect.stringValue);
                for (BattleCombatant target : targets) {
                    if (target.removeStatusEffects(status) == 0) continue;
                    events.add(CombatEvent.of(CombatEvent.Type.STATUS_EXPIRED)
                        .source(owner).target(target).tick(tick)
                        .message(status + " was removed from " + target.getCharacter().getName() + ".").build());
                    followUps.add(AbilityTrigger.status(AbilityTrigger.Type.STATUS_REMOVED, target, status, tick));
                }
            }
            case CLEAR_STATUSES -> {
                for (BattleCombatant target : targets) {
                    List<StatusEffectType> removed = target.getActiveEffects().stream()
                        .map(StatusEffect::getType).distinct().toList();
                    target.clearStatusEffects();
                    for (StatusEffectType status : removed) {
                        followUps.add(AbilityTrigger.status(AbilityTrigger.Type.STATUS_REMOVED, target, status, tick));
                    }
                }
            }
            case TEMP_STAT_ADD, TEMP_STAT_MULTIPLY, TEMP_STAT_SET_VALUE,
                 BATTLE_STAT_ADD, BATTLE_STAT_MULTIPLY, IGNORE_DAMAGE, DAMAGE_SHIELD,
                 SURVIVE_FATAL_DAMAGE, GUARANTEE_NEXT_HIT, GUARANTEE_NEXT_DODGE,
                 GUARANTEE_NEXT_BLACK_FLASH, CANCEL_NEXT_MOVE,
                 TEMP_LOCK_MOVE_TAG -> {
                for (BattleCombatant target : targets) {
                    addRuntimeEffect(state, owner, target, effect, tick, events);
                }
            }
            case STAT_ADD, STAT_MULTIPLY, STAT_DIVIDE, STAT_SET_VALUE, STAT_SET_MIN,
                 CE_COST_TO_MINIMUM, CE_COST_MULTIPLY, MOVE_ACCURACY_ADD,
                 MOVE_ACCURACY_MULTIPLY, OPPONENT_ACCURACY_ADD,
                 OPPONENT_ACCURACY_MULTIPLY, DAMAGE_MULTIPLY, BF_CHANCE_ADD,
                 MODIFY_DEFENSE, MODIFY_AP_BAR, LOCK_MOVE_TAG, COST_CE_PER_ROUND ->
                addRuntimeEffect(state, owner, owner, effect, tick, events);
            case AUTO_STATUS_APPLY -> {
                StatusEffectType status = status(effect.stringValue);
                for (BattleCombatant target : targets) {
                    boolean applied = extendStatusForCurrentPhase(state)
                        ? target.addAutomaticStatusEffect(effect, state.getCurrentPhase())
                        : target.addAutomaticStatusEffect(effect);
                    if (!applied) continue;
                    events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                        .source(owner).target(target).tick(tick)
                        .message(target.getCharacter().getName() + " receives " + status + "!").build());
                    followUps.add(AbilityTrigger.status(
                        AbilityTrigger.Type.STATUS_APPLIED, target, status, tick));
                }
            }
            case STAT_BONUS_POINTS, GRANT_MOVE, UNLOCK_TECHNIQUE -> { }
        }
    }

    private boolean hasMatchingEventLeaf(
        AbilityConditionData condition,
        BattleCombatant owner,
        BattleCombatant enemy,
        BattleState state,
        AbilityTrigger trigger
    ) {
        if (condition == null) return false;
        AbilityConditionType type;
        try { type = AbilityConditionType.fromName(condition.type); }
        catch (IllegalArgumentException ex) { return false; }
        if (type.isGroup()) {
            return condition.children != null && condition.children.stream()
                .anyMatch(child -> hasMatchingEventLeaf(child, owner, enemy, state, trigger));
        }
        boolean eventCondition = switch (type) {
            case BLACK_FLASH_HIT, MOVE_USED, MOVE_TAG_USED, ATTACK_HIT, ATTACK_MISSED,
                 MOVE_BLOCKED, TIMELINE_POINT_REACHED, TIMELINE_POINT_ON_ROUND,
                 EVERY_N_ROUNDS, PHASE_REACHED, HEALED, DAMAGE_DEALT_AT_LEAST,
                 DAMAGE_TAKEN_AT_LEAST, CE_SPENT_AT_LEAST, CE_LOST_AT_LEAST,
                 CE_RESTORED_AT_LEAST,
                 STATUS_APPLIED, STATUS_REMOVED -> true;
            default -> false;
        };
        return eventCondition && eventLeafMatches(type, condition, owner, enemy, state, trigger);
    }

    private static boolean eventLeafMatches(
        AbilityConditionType type,
        AbilityConditionData condition,
        BattleCombatant owner,
        BattleCombatant enemy,
        BattleState state,
        AbilityTrigger trigger
    ) {
        return switch (type) {
            case BLACK_FLASH_HIT -> trigger.type() == AbilityTrigger.Type.BLACK_FLASH
                && eventActorMatches(condition, owner, enemy, trigger.actor());
            case MOVE_USED -> trigger.type() == AbilityTrigger.Type.MOVE_USED
                && eventActorMatches(condition, owner, enemy, trigger.actor())
                && trigger.move() != null && trigger.move().getId().equals(condition.moveId);
            case MOVE_TAG_USED -> trigger.type() == AbilityTrigger.Type.MOVE_USED
                && eventActorMatches(condition, owner, enemy, trigger.actor())
                && trigger.move() != null && trigger.move().hasTag(condition.moveTag);
            case ATTACK_HIT -> trigger.type() == AbilityTrigger.Type.ATTACK_HIT
                && eventActorMatches(condition, owner, enemy, trigger.actor());
            case ATTACK_MISSED -> trigger.type() == AbilityTrigger.Type.ATTACK_MISSED
                && eventActorMatches(condition, owner, enemy, trigger.actor());
            case MOVE_BLOCKED -> trigger.type() == AbilityTrigger.Type.MOVE_BLOCKED
                && eventActorMatches(condition, owner, enemy, trigger.actor());
            case TIMELINE_POINT_REACHED -> trigger.type() == AbilityTrigger.Type.TIMELINE_TICK
                && trigger.tick() == value(condition.tick);
            case TIMELINE_POINT_ON_ROUND -> trigger.type() == AbilityTrigger.Type.TIMELINE_TICK
                && trigger.tick() == value(condition.tick) && state.getRoundNumber() == value(condition.round);
            case EVERY_N_ROUNDS -> trigger.type() == AbilityTrigger.Type.ROUND_START
                && state.getRoundNumber() % Math.max(1, value(condition.round)) == 0;
            case PHASE_REACHED -> trigger.type() == AbilityTrigger.Type.PHASE_REACHED
                && trigger.phase() != null && trigger.phase().name().equals(condition.phase);
            case HEALED -> trigger.type() == AbilityTrigger.Type.HEALED
                && eventActorMatches(condition, owner, enemy, trigger.actor())
                && trigger.amount() >= value(condition.amount);
            case DAMAGE_DEALT_AT_LEAST -> trigger.type() == AbilityTrigger.Type.DAMAGE
                && eventActorMatches(condition, owner, enemy, trigger.actor())
                && trigger.amount() >= value(condition.amount);
            case DAMAGE_TAKEN_AT_LEAST -> trigger.type() == AbilityTrigger.Type.DAMAGE
                && eventActorMatches(condition, owner, enemy, trigger.target())
                && trigger.amount() >= value(condition.amount);
            case CE_SPENT_AT_LEAST -> trigger.type() == AbilityTrigger.Type.CE_SPENT
                && eventActorMatches(condition, owner, enemy, trigger.actor())
                && trigger.amount() >= value(condition.amount);
            case CE_LOST_AT_LEAST -> trigger.type() == AbilityTrigger.Type.CE_LOST
                && eventActorMatches(condition, owner, enemy, trigger.actor())
                && trigger.amount() >= value(condition.amount);
            case CE_RESTORED_AT_LEAST -> trigger.type() == AbilityTrigger.Type.CE_RESTORED
                && eventActorMatches(condition, owner, enemy, trigger.actor())
                && trigger.amount() >= value(condition.amount);
            case STATUS_APPLIED -> trigger.type() == AbilityTrigger.Type.STATUS_APPLIED
                && eventActorMatches(condition, owner, enemy, trigger.actor())
                && trigger.status() == status(condition.statusType);
            case STATUS_REMOVED -> trigger.type() == AbilityTrigger.Type.STATUS_REMOVED
                && eventActorMatches(condition, owner, enemy, trigger.actor())
                && trigger.status() == status(condition.statusType);
            default -> false;
        };
    }

    private static List<BattleCombatant> targets(
        AbilityEffectData effect,
        BattleCombatant owner,
        BattleCombatant enemy
    ) {
        AbilityEffectTarget target;
        try { target = AbilityEffectTarget.valueOf(effect.target); }
        catch (Exception ex) { target = AbilityEffectTarget.SELF; }
        return switch (target) {
            case SELF -> List.of(owner);
            case ENEMY -> List.of(enemy);
            case BOTH -> List.of(owner, enemy);
        };
    }

    private static boolean extendStatusForCurrentPhase(BattleState state) {
        return state.getCurrentPhase() != BattleState.Phase.ROUND_END
            || !state.isRoundEndMaintenanceComplete();
    }

    private static void addRuntimeEffect(
        BattleState state,
        BattleCombatant source,
        BattleCombatant target,
        AbilityEffectData effect,
        int tick,
        List<CombatEvent> events
    ) {
        int previousMaxHp = target.getMaxHp();
        int previousMaxCe = target.getMaxCursedEnergy();
        target.addRuntimeAbilityEffect(effect, state.getRoundNumber(), state.getCurrentPhase());
        if (target.getMaxHp() != previousMaxHp) {
            events.add(CombatEvent.of(CombatEvent.Type.MAX_HP_CHANGED)
                .source(source).target(target).intValue(target.getMaxHp()).tick(tick)
                .message(target.getCharacter().getName() + "'s max HP is now "
                    + target.getMaxHp() + ".").build());
        }
        if (target.getMaxCursedEnergy() != previousMaxCe) {
            events.add(CombatEvent.of(CombatEvent.Type.MAX_CE_CHANGED)
                .source(source).target(target).intValue(target.getMaxCursedEnergy()).tick(tick)
                .message(target.getCharacter().getName() + "'s max CE is now "
                    + target.getMaxCursedEnergy() + ".").build());
        }
    }

    private static boolean anyActor(
        AbilityConditionData condition,
        BattleCombatant owner,
        BattleCombatant enemy,
        java.util.function.Predicate<BattleCombatant> predicate
    ) {
        AbilityConditionActor actor = actor(condition);
        return switch (actor) {
            case SELF -> predicate.test(owner);
            case ENEMY -> predicate.test(enemy);
            case ANY -> predicate.test(owner) || predicate.test(enemy);
        };
    }

    private static boolean eventActorMatches(
        AbilityConditionData condition,
        BattleCombatant owner,
        BattleCombatant enemy,
        BattleCombatant eventActor
    ) {
        if (eventActor == null) return false;
        return switch (actor(condition)) {
            case SELF -> eventActor == owner;
            case ENEMY -> eventActor == enemy;
            case ANY -> eventActor == owner || eventActor == enemy;
        };
    }

    private static AbilityConditionActor actor(AbilityConditionData condition) {
        try { return AbilityConditionActor.valueOf(condition.actor); }
        catch (Exception ex) { return AbilityConditionActor.SELF; }
    }

    private static AbilityEffectType safeType(AbilityEffectData effect) {
        try { return AbilityEffectType.fromName(effect == null ? null : effect.type); }
        catch (Exception ex) { return AbilityEffectType.STAT_BONUS_POINTS; }
    }

    private static StatusEffectType status(String value) {
        try { return StatusEffectType.valueOf(value); }
        catch (Exception ex) { return StatusEffectType.POISON; }
    }

    private static int statValue(BattleCombatant combatant, String stat) {
        try { return StatKey.fromString(stat).get(combatant.getEffectiveStats()); }
        catch (Exception ex) { return 0; }
    }

    private static String abilityKey(Ability ability, int index) {
        if (ability.getId() != null && !ability.getId().isBlank()) return ability.getId();
        return String.valueOf(ability.getName()) + "#" + index;
    }

    private static double ratio(int current, int maximum) {
        return maximum <= 0 ? 0.0 : (double) current / maximum;
    }

    private static int value(Integer value) { return value == null ? 0 : value; }
    private static double value(Double value) { return value == null ? 0.0 : value; }
}
