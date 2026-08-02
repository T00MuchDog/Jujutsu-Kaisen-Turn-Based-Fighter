package com.jjktbf.model.combat;

import com.jjktbf.model.character.*;
import com.jjktbf.model.character.coded.CodedAbilityBinding;
import com.jjktbf.model.character.coded.CodedHitModifiers;
import com.jjktbf.model.character.coded.CodedMoveResponse;
import com.jjktbf.model.move.StatusEffect;
import com.jjktbf.model.move.StatusEffectType;
import com.jjktbf.model.progression.TechniqueMasteryProgressions;
import com.jjktbf.model.progression.TechniqueMasteryResolver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Evaluates active ability conditions and executes their modular effects. */
public final class AbilityActivationEngine {

    private static final int MAX_CHAINED_TRIGGERS = 128;

    private record RuleActivationKey(
        BattleCombatant owner,
        Ability ability,
        int abilityIndex,
        int ruleIndex
    ) { }

    private final RandomSource rng;

    public AbilityActivationEngine(RandomSource rng) {
        this.rng = rng;
    }

    public List<CombatEvent> process(BattleState state, AbilityTrigger initialTrigger) {
        List<CombatEvent> events = new ArrayList<>();
        ArrayDeque<AbilityTrigger> triggers = new ArrayDeque<>();
        Set<RuleActivationKey> activatedThisChain = new HashSet<>();
        triggers.add(initialTrigger);
        int processed = 0;
        while (!triggers.isEmpty() && processed++ < MAX_CHAINED_TRIGGERS) {
            AbilityTrigger trigger = triggers.removeFirst();
            Map<RuleActivationKey, Boolean> activationCache = new HashMap<>();
            observeRules(
                state, state.getPlayerCombatant(), state.getEnemyCombatant(), trigger,
                activatedThisChain);
            observeRules(
                state, state.getEnemyCombatant(), state.getPlayerCombatant(), trigger,
                activatedThisChain);
            events.addAll(dispatchCodedTrigger(
                state, state.getPlayerCombatant(), state.getEnemyCombatant(), trigger,
                activationCache));
            events.addAll(dispatchCodedTrigger(
                state, state.getEnemyCombatant(), state.getPlayerCombatant(), trigger,
                activationCache));
            evaluateOwner(state, state.getPlayerCombatant(), state.getEnemyCombatant(), trigger,
                events, triggers, activatedThisChain, activationCache);
            evaluateOwner(state, state.getEnemyCombatant(), state.getPlayerCombatant(), trigger,
                events, triggers, activatedThisChain, activationCache);
        }
        if (!triggers.isEmpty()) {
            System.err.println("[WARN] Ability activation chain exceeded "
                + MAX_CHAINED_TRIGGERS + " events.");
        }
        return events;
    }

    /** Evaluate an active coded effect after a hit connects but before defenses. */
    public CodedHitModifiers onAttackConnected(BattleState state, AbilityTrigger trigger) {
        if (state == null || trigger == null || trigger.actor() == null) {
            return CodedHitModifiers.none();
        }
        BattleCombatant owner = trigger.actor();
        BattleCombatant enemy = opponent(state, owner);
        Map<RuleActivationKey, Boolean> activationCache = new HashMap<>();
        return owner.getCodedAbilities().onAttackConnected(
            trigger.actor(), trigger.target(), trigger.move(), trigger.hitComponent(),
            trigger.tick(), rng,
            binding -> allowsCodedBinding(
                binding, owner, enemy, state, trigger, activationCache));
    }

    /** Evaluate coded reactions immediately before an incoming move executes. */
    public CodedMoveResponse beforeIncomingMove(BattleState state, AbilityTrigger trigger) {
        if (state == null || trigger == null || trigger.target() == null) {
            return CodedMoveResponse.none();
        }
        BattleCombatant owner = trigger.target();
        BattleCombatant enemy = opponent(state, owner);
        Map<RuleActivationKey, Boolean> activationCache = new HashMap<>();
        return owner.getCodedAbilities().beforeIncomingMove(
            state, trigger.actor(), trigger.target(), trigger.move(), trigger.tick(),
            binding -> allowsCodedBinding(
                binding, owner, enemy, state, trigger, activationCache));
    }

    /** Evaluate coded fatal-hit protection after shields but before HP is removed. */
    public boolean preventFatalDamage(BattleState state, AbilityTrigger trigger) {
        if (state == null || trigger == null || trigger.target() == null) return false;
        BattleCombatant owner = trigger.target();
        BattleCombatant enemy = opponent(state, owner);
        Map<RuleActivationKey, Boolean> activationCache = new HashMap<>();
        return owner.getCodedAbilities().preventFatalDamage(
            binding -> allowsCodedBinding(
                binding, owner, enemy, state, trigger, activationCache));
    }

    private List<CombatEvent> dispatchCodedTrigger(
        BattleState state,
        BattleCombatant owner,
        BattleCombatant enemy,
        AbilityTrigger trigger,
        Map<RuleActivationKey, Boolean> activationCache
    ) {
        return owner.getCodedAbilities().onTrigger(
            state,
            trigger,
            binding -> allowsCodedBinding(
                binding, owner, enemy, state, trigger, activationCache));
    }

    /** Preserve event facts even when a coded runtime does not query its gate yet. */
    private void observeRules(
        BattleState state,
        BattleCombatant owner,
        BattleCombatant enemy,
        AbilityTrigger trigger,
        Set<RuleActivationKey> activatedThisChain
    ) {
        List<Ability> abilities = owner.getAbilities();
        for (int abilityIndex = 0; abilityIndex < abilities.size(); abilityIndex++) {
            Ability ability = abilities.get(abilityIndex);
            if (ability == null || !ability.isActive()) continue;
            if (trigger.type() == AbilityTrigger.Type.MANUAL_ACTIVATION
                && (trigger.actor() != owner
                    || !java.util.Objects.equals(trigger.abilityId(), ability.getId()))) {
                continue;
            }
            List<AbilityConditionRuleData> rules = ability.getActivationConditions();
            for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
                AbilityConditionRuleData rule = rules.get(ruleIndex);
                RuleActivationKey activationKey = new RuleActivationKey(
                    owner, ability, abilityIndex, ruleIndex);
                if (rule == null || rule.condition == null
                    || Boolean.TRUE.equals(rule.matchSameTrigger)
                    || activatedThisChain.contains(activationKey)) {
                    continue;
                }
                if (trigger.type() == AbilityTrigger.Type.MANUAL_ACTIVATION
                    && !containsConditionType(
                        rule.condition, AbilityConditionType.MANUAL_ACTIVATION)) {
                    continue;
                }
                if (trigger.type() == AbilityTrigger.Type.MANUAL_ACTIVATION) continue;
                if (hasMatchingEventLeaf(rule.condition, owner, enemy, state, trigger)) {
                    owner.recordAbilityTrigger(
                        ruleKey(ability, abilityIndex, ruleIndex), trigger);
                }
            }
        }
    }

    private void evaluateOwner(
        BattleState state,
        BattleCombatant owner,
        BattleCombatant enemy,
        AbilityTrigger trigger,
        List<CombatEvent> events,
        ArrayDeque<AbilityTrigger> followUps,
        Set<RuleActivationKey> activatedThisChain,
        Map<RuleActivationKey, Boolean> activationCache
    ) {
        List<Ability> abilities = owner.getAbilities();
        for (int index = 0; index < abilities.size(); index++) {
            Ability ability = abilities.get(index);
            if (ability == null || !ability.isActive()) continue;
            List<AbilityConditionRuleData> rules = ability.getActivationConditions();
            for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
                AbilityConditionRuleData rule = rules.get(ruleIndex);
                List<AbilityEffectData> targetedEffects = ability.getEffects().stream()
                    .filter(effect -> effect != null && effect.type != null && !effect.isCoded())
                    .filter(effect -> rule.targetsEffect(effect.effectId))
                    .toList();
                if (targetedEffects.isEmpty()) continue;

                RuleActivationKey cacheKey = new RuleActivationKey(
                    owner, ability, index, ruleIndex);
                if (activatedThisChain.contains(cacheKey)) continue;
                Boolean cached = activationCache.get(cacheKey);
                boolean activated = cached != null ? cached : activateRule(
                    ability, index, rule, ruleIndex, owner, enemy, state, trigger, false);
                activationCache.putIfAbsent(cacheKey, activated);
                if (!activated) continue;
                activatedThisChain.add(cacheKey);

                events.add(CombatEvent.of(CombatEvent.Type.ABILITY_ACTIVATED)
                    .source(owner)
                    .tick(trigger.tick())
                    .message(owner.getCharacter().getName() + " activates " + ability.getName() + "!")
                    .build());

                for (AbilityEffectData effect : targetedEffects) {
                    AbilityEffectData resolved = "TECHNIQUE".equalsIgnoreCase(ability.getSourceType())
                        ? TechniqueMasteryResolver.resolve(
                            effect, TechniqueMasteryResolver.masteryOf(owner))
                        : effect;
                    applyEffect(state, owner, enemy, resolved, trigger.tick(), events, followUps);
                }
            }
        }
    }

    private boolean allowsCodedBinding(
        CodedAbilityBinding binding,
        BattleCombatant owner,
        BattleCombatant enemy,
        BattleState state,
        AbilityTrigger trigger,
        Map<RuleActivationKey, Boolean> activationCache
    ) {
        if (binding == null || binding.ability() == null || binding.effect() == null) return false;
        Ability ability = binding.ability();
        if (ability.isPassive()) return true;
        if (!ability.isActive()) return false;
        List<AbilityConditionRuleData> rules = ability.getActivationConditions();
        for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
            AbilityConditionRuleData rule = rules.get(ruleIndex);
            if (!rule.targetsEffect(binding.effect().effectId)) continue;
            int currentRule = ruleIndex;
            RuleActivationKey cacheKey = new RuleActivationKey(
                owner, ability, binding.abilityIndex(), currentRule);
            if (activationCache.computeIfAbsent(cacheKey, ignored -> activateRule(
                ability, binding.abilityIndex(), rule, currentRule,
                owner, enemy, state, trigger, true))) {
                return true;
            }
        }
        return false;
    }

    private boolean activateRule(
        Ability ability,
        int abilityIndex,
        AbilityConditionRuleData rule,
        int ruleIndex,
        BattleCombatant owner,
        BattleCombatant enemy,
        BattleState state,
        AbilityTrigger trigger,
        boolean codedOpportunity
    ) {
        if (rule == null || rule.condition == null) return false;
        if (trigger.type() == AbilityTrigger.Type.MANUAL_ACTIVATION
            && (trigger.actor() != owner
                || !java.util.Objects.equals(trigger.abilityId(), ability.getId()))) {
            return false;
        }
        if (trigger.type() == AbilityTrigger.Type.MANUAL_ACTIVATION
            && !containsConditionType(
                rule.condition, AbilityConditionType.MANUAL_ACTIVATION)) {
            return false;
        }
        String key = ruleKey(ability, abilityIndex, ruleIndex);
        boolean eventOpportunity = hasMatchingEventLeaf(
            rule.condition, owner, enemy, state, trigger);
        boolean sameTrigger = Boolean.TRUE.equals(rule.matchSameTrigger)
            || trigger.type() == AbilityTrigger.Type.MANUAL_ACTIVATION;
        if (eventOpportunity && !sameTrigger) owner.recordAbilityTrigger(key, trigger);
        List<AbilityTrigger> history = owner.getAbilityTriggerHistory(key);
        List<AbilityTrigger> evaluationHistory = sameTrigger
            ? List.of(trigger) : history;
        boolean matches = evaluate(
            rule.condition, owner, enemy, state, trigger, evaluationHistory);
        boolean previouslyMatched = owner.wasAbilityConditionTrue(key);
        owner.setAbilityConditionTrue(key, matches);
        if (!matches || (!codedOpportunity && previouslyMatched && !eventOpportunity)) {
            return false;
        }
        if (!history.isEmpty() || (sameTrigger && eventOpportunity)) {
            owner.clearAbilityTriggerHistory(key);
            owner.setAbilityConditionTrue(
                key, evaluate(rule.condition, owner, enemy, state, trigger, List.of()));
        }
        double chance = TechniqueMasteryResolver.resolvePercent(
            rule.masteryProgression,
            TechniqueMasteryProgressions.ACTIVATION_CHANCE,
            rule.effectiveActivationChance(),
            TechniqueMasteryResolver.masteryOf(owner));
        chance = Math.max(0.0, Math.min(1.0, chance));
        return chance > 0.0 && (chance >= 1.0 || rng.nextDouble() < chance);
    }

    private boolean evaluate(
        AbilityConditionData condition,
        BattleCombatant owner,
        BattleCombatant enemy,
        BattleState state,
        AbilityTrigger trigger,
        List<AbilityTrigger> history
    ) {
        if (condition == null) return false;
        if (condition.containsAlways()) return true;
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
            case MANUAL_ACTIVATION, BATTLE_STARTED -> history.stream().anyMatch(candidate ->
                eventLeafMatches(type, condition, owner, enemy, state, candidate));
            case HP_PERCENT_AT_OR_BELOW -> anyActor(condition, owner, enemy,
                combatant -> ratio(combatant.getCurrentHp(), combatant.getMaxHp())
                    <= conditionPercent(condition, owner));
            case HP_PERCENT_AT_OR_ABOVE -> anyActor(condition, owner, enemy,
                combatant -> ratio(combatant.getCurrentHp(), combatant.getMaxHp())
                    >= conditionPercent(condition, owner));
            case HP_VALUE_AT_OR_BELOW -> anyActor(condition, owner, enemy,
                combatant -> combatant.getCurrentHp() <= conditionAmount(condition, owner));
            case HP_VALUE_AT_OR_ABOVE -> anyActor(condition, owner, enemy,
                combatant -> combatant.getCurrentHp() >= conditionAmount(condition, owner));
            case CE_PERCENT_AT_OR_BELOW -> anyActor(condition, owner, enemy,
                combatant -> ratio(combatant.getCurrentCe(), combatant.getMaxCursedEnergy())
                    <= conditionPercent(condition, owner));
            case CE_PERCENT_AT_OR_ABOVE -> anyActor(condition, owner, enemy,
                combatant -> ratio(combatant.getCurrentCe(), combatant.getMaxCursedEnergy())
                    >= conditionPercent(condition, owner));
            case CE_VALUE_AT_OR_BELOW -> anyActor(condition, owner, enemy,
                combatant -> combatant.getCurrentCe() <= conditionAmount(condition, owner));
            case CE_VALUE_AT_OR_ABOVE -> anyActor(condition, owner, enemy,
                combatant -> combatant.getCurrentCe() >= conditionAmount(condition, owner));
            case BLACK_FLASH_HIT -> history.stream().anyMatch(candidate ->
                eventLeafMatches(type, condition, owner, enemy, state, candidate));
            case IN_BLACK_FLASH_STATE -> anyActor(condition, owner, enemy, BattleCombatant::isInBlackFlashState);
            case BLACK_FLASH_STREAK_AT_LEAST -> anyActor(condition, owner, enemy,
                combatant -> combatant.getConsecutiveBfsHits() >= conditionAmount(condition, owner));
            case MOVE_USED, MOVE_TAG_USED, ATTACK_HIT, ATTACK_MISSED, MOVE_BLOCKED,
                  TIMELINE_POINT_REACHED -> history.stream().anyMatch(candidate ->
                eventLeafMatches(type, condition, owner, enemy, state, candidate));
            case ATTACK_CONNECTED, CONNECTED_HIT_HAS_TAG, FATAL_DAMAGE ->
                eventLeafMatches(type, condition, owner, enemy, state, trigger);
            case ROUND_REACHED -> state.getRoundNumber() >= conditionRound(condition, owner);
            case TIMELINE_POINT_ON_ROUND, EVERY_N_ROUNDS, PHASE_REACHED, HEALED,
                 DAMAGE_DEALT_AT_LEAST, DAMAGE_TAKEN_AT_LEAST, CE_SPENT_AT_LEAST,
                 CE_LOST_AT_LEAST,
                 CE_RESTORED_AT_LEAST -> history.stream().anyMatch(candidate ->
                eventLeafMatches(type, condition, owner, enemy, state, candidate));
            case STAT_AT_OR_ABOVE -> anyActor(condition, owner, enemy,
                combatant -> statValue(combatant, condition.stat) >= conditionAmount(condition, owner));
            case STAT_AT_OR_BELOW -> anyActor(condition, owner, enemy,
                combatant -> statValue(combatant, condition.stat) <= conditionAmount(condition, owner));
            case HAS_STATUS -> statusPredicate(condition, owner, enemy, false);
            case DOES_NOT_HAVE_STATUS -> statusPredicate(condition, owner, enemy, true);
            case STATUS_APPLIED, STATUS_REMOVED -> history.stream().anyMatch(candidate ->
                eventLeafMatches(type, condition, owner, enemy, state, candidate));
            case CODED_STATE_AT_OR_ABOVE -> codedStatePredicate(
                condition, owner, enemy, true);
            case CODED_STATE_AT_OR_BELOW -> codedStatePredicate(
                condition, owner, enemy, false);
            case ALL, ANY -> false;
        };
    }

    private static boolean containsConditionType(
        AbilityConditionData condition,
        AbilityConditionType expected
    ) {
        if (condition == null) return false;
        if (expected.name().equalsIgnoreCase(condition.type)) return true;
        return condition.children != null && condition.children.stream()
            .anyMatch(child -> containsConditionType(child, expected));
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
                        ? target.receiveInstantKill(ignored -> preventFatalDamage(
                            state, AbilityTrigger.fatalDamage(
                                owner, target, null, null, target.getCurrentHp(), tick)))
                        : target.receiveDamage(requested, fatalAmount -> preventFatalDamage(
                            state, AbilityTrigger.fatalDamage(
                                owner, target, null, null, fatalAmount, tick)));
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
                StatusEffectType status = status(effect.stringValue, effect.magnitude);
                if (status == null) return;
                for (BattleCombatant target : targets) {
                    int previousMaxHp = target.getMaxHp();
                    int previousMaxCe = target.getMaxCursedEnergy();
                    StatusEffect applied = new StatusEffect(
                        status,
                        effect.durationRounds == null ? 1 : effect.durationRounds,
                        effect.durationTicks == null ? 0 : effect.durationTicks,
                        StatusEffectType.normalizeStoredMagnitude(
                            effect.stringValue, effect.magnitude == null ? 0.0 : effect.magnitude));
                    boolean accepted = extendStatusForCurrentPhase(state)
                        ? target.addStatusEffect(applied, state.getCurrentPhase())
                        : target.addStatusEffect(applied);
                    if (!accepted) continue;
                    events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                        .source(owner).target(target).tick(tick)
                        .message(target.getCharacter().getName() + " receives "
                            + status.displayName() + "!").build());
                    appendResourceMaximumEvents(
                        owner, target, previousMaxHp, previousMaxCe, tick, events);
                    followUps.add(AbilityTrigger.status(AbilityTrigger.Type.STATUS_APPLIED, target, status, tick));
                }
            }
            case REMOVE_STATUS -> {
                Set<StatusEffectType> referenced =
                    StatusEffectType.referencedTypes(effect.stringValue);
                if (referenced.isEmpty()) return;
                for (BattleCombatant target : targets) {
                    int previousMaxHp = target.getMaxHp();
                    int previousMaxCe = target.getMaxCursedEnergy();
                    List<StatusEffectType> removed = target.getActiveEffects().stream()
                        .map(StatusEffect::getType)
                        .filter(referenced::contains)
                        .distinct()
                        .toList();
                    if (removed.isEmpty()) continue;
                    removed.forEach(target::removeStatusEffects);
                    events.add(CombatEvent.of(CombatEvent.Type.STATUS_EXPIRED)
                        .source(owner).target(target).tick(tick)
                        .message("Matching status effects were removed from "
                            + target.getCharacter().getName() + ".").build());
                    appendResourceMaximumEvents(
                        owner, target, previousMaxHp, previousMaxCe, tick, events);
                    for (StatusEffectType status : removed) {
                        followUps.add(AbilityTrigger.status(
                            AbilityTrigger.Type.STATUS_REMOVED, target, status, tick));
                    }
                }
            }
            case CLEAR_STATUSES -> {
                for (BattleCombatant target : targets) {
                    int previousMaxHp = target.getMaxHp();
                    int previousMaxCe = target.getMaxCursedEnergy();
                    List<StatusEffectType> removed = target.getActiveEffects().stream()
                        .map(StatusEffect::getType).distinct().toList();
                    target.clearStatusEffects();
                    for (StatusEffectType status : removed) {
                        followUps.add(AbilityTrigger.status(AbilityTrigger.Type.STATUS_REMOVED, target, status, tick));
                    }
                    appendResourceMaximumEvents(
                        owner, target, previousMaxHp, previousMaxCe, tick, events);
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
                  OPPONENT_ACCURACY_MULTIPLY, DAMAGE_MULTIPLY,
                  MOVE_BASE_POWER_MULTIPLY, BF_CHANCE_ADD,
                 MODIFY_DEFENSE, MODIFY_AP_BAR, LOCK_MOVE_TAG, COST_CE_PER_ROUND ->
                addRuntimeEffect(state, owner, owner, effect, tick, events);
            case AUTO_STATUS_APPLY -> {
                StatusEffectType status = status(effect.stringValue, effect.magnitude);
                if (status == null) return;
                for (BattleCombatant target : targets) {
                    int previousMaxHp = target.getMaxHp();
                    int previousMaxCe = target.getMaxCursedEnergy();
                    boolean applied = extendStatusForCurrentPhase(state)
                        ? target.addAutomaticStatusEffect(effect, state.getCurrentPhase())
                        : target.addAutomaticStatusEffect(effect);
                    if (!applied) continue;
                    events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                        .source(owner).target(target).tick(tick)
                        .message(target.getCharacter().getName() + " receives "
                            + status.displayName() + "!").build());
                    appendResourceMaximumEvents(
                        owner, target, previousMaxHp, previousMaxCe, tick, events);
                    followUps.add(AbilityTrigger.status(
                        AbilityTrigger.Type.STATUS_APPLIED, target, status, tick));
                }
            }
            case STAT_ALLOCATION_MINIMUM, STAT_BONUS_POINTS,
                  POISON_IMMUNITY, SOUL_AWARE_ATTACKS,
                  GRANT_MOVE, GRANT_ABILITY, UNLOCK_MOVE,
                  UNLOCK_TECHNIQUE, CODED -> { }
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
                  STATUS_APPLIED, STATUS_REMOVED, MANUAL_ACTIVATION, BATTLE_STARTED,
                  ATTACK_CONNECTED, CONNECTED_HIT_HAS_TAG,
                  FATAL_DAMAGE -> true;
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
            case MANUAL_ACTIVATION -> trigger.type() == AbilityTrigger.Type.MANUAL_ACTIVATION;
            case BATTLE_STARTED -> trigger.type() == AbilityTrigger.Type.BATTLE_START;
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
            case ATTACK_CONNECTED -> trigger.type() == AbilityTrigger.Type.ATTACK_CONNECTED
                && eventActorMatches(condition, owner, enemy, trigger.actor());
            case CONNECTED_HIT_HAS_TAG -> trigger.type() == AbilityTrigger.Type.ATTACK_CONNECTED
                && eventActorMatches(condition, owner, enemy, trigger.actor())
                && connectedHitHasTag(trigger, condition.moveTag);
            case FATAL_DAMAGE -> trigger.type() == AbilityTrigger.Type.FATAL_DAMAGE
                && eventActorMatches(condition, owner, enemy, trigger.target());
            case TIMELINE_POINT_REACHED -> trigger.type() == AbilityTrigger.Type.TIMELINE_TICK
                && trigger.tick() == conditionTick(condition, owner);
            case TIMELINE_POINT_ON_ROUND -> trigger.type() == AbilityTrigger.Type.TIMELINE_TICK
                && trigger.tick() == conditionTick(condition, owner)
                && state.getRoundNumber() == conditionRound(condition, owner);
            case EVERY_N_ROUNDS -> trigger.type() == AbilityTrigger.Type.ROUND_START
                && state.getRoundNumber() % Math.max(1, conditionRound(condition, owner)) == 0;
            case PHASE_REACHED -> trigger.type() == AbilityTrigger.Type.PHASE_REACHED
                && trigger.phase() != null && trigger.phase().name().equals(condition.phase);
            case HEALED -> trigger.type() == AbilityTrigger.Type.HEALED
                && eventActorMatches(condition, owner, enemy, trigger.actor())
                && trigger.amount() >= conditionAmount(condition, owner);
            case DAMAGE_DEALT_AT_LEAST -> trigger.type() == AbilityTrigger.Type.DAMAGE
                && eventActorMatches(condition, owner, enemy, trigger.actor())
                && trigger.amount() >= conditionAmount(condition, owner);
            case DAMAGE_TAKEN_AT_LEAST -> trigger.type() == AbilityTrigger.Type.DAMAGE
                && eventActorMatches(condition, owner, enemy, trigger.target())
                && trigger.amount() >= conditionAmount(condition, owner);
            case CE_SPENT_AT_LEAST -> trigger.type() == AbilityTrigger.Type.CE_SPENT
                && eventActorMatches(condition, owner, enemy, trigger.actor())
                && trigger.amount() >= conditionAmount(condition, owner);
            case CE_LOST_AT_LEAST -> trigger.type() == AbilityTrigger.Type.CE_LOST
                && eventActorMatches(condition, owner, enemy, trigger.actor())
                && trigger.amount() >= conditionAmount(condition, owner);
            case CE_RESTORED_AT_LEAST -> trigger.type() == AbilityTrigger.Type.CE_RESTORED
                && eventActorMatches(condition, owner, enemy, trigger.actor())
                && trigger.amount() >= conditionAmount(condition, owner);
            case STATUS_APPLIED -> trigger.type() == AbilityTrigger.Type.STATUS_APPLIED
                && eventActorMatches(condition, owner, enemy, trigger.actor())
                && StatusEffectType.referencedTypes(condition.statusType)
                    .contains(trigger.status());
            case STATUS_REMOVED -> trigger.type() == AbilityTrigger.Type.STATUS_REMOVED
                && eventActorMatches(condition, owner, enemy, trigger.actor())
                && StatusEffectType.referencedTypes(condition.statusType)
                    .contains(trigger.status());
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

    private static boolean statusPredicate(
        AbilityConditionData condition,
        BattleCombatant owner,
        BattleCombatant enemy,
        boolean negated
    ) {
        Set<StatusEffectType> statuses = StatusEffectType.referencedTypes(condition.statusType);
        if (statuses.isEmpty()) return false;
        return anyActor(condition, owner, enemy,
            combatant -> combatant.getActiveEffects().stream()
                .map(StatusEffect::getType)
                .anyMatch(statuses::contains) != negated);
    }

    private static boolean codedStatePredicate(
        AbilityConditionData condition,
        BattleCombatant owner,
        BattleCombatant enemy,
        boolean atOrAbove
    ) {
        return anyActor(condition, owner, enemy, combatant ->
            combatant.getCodedAbilities().state(condition.codedAbilityKey)
                .map(state -> atOrAbove
                    ? state.currentValue() >= conditionAmount(condition, owner)
                    : state.currentValue() <= conditionAmount(condition, owner))
                .orElse(false));
    }

    private static int conditionAmount(AbilityConditionData condition, BattleCombatant owner) {
        return TechniqueMasteryResolver.resolveInt(
            condition.masteryProgression, TechniqueMasteryProgressions.AMOUNT,
            condition.amount, TechniqueMasteryResolver.masteryOf(owner));
    }

    private static int conditionTick(AbilityConditionData condition, BattleCombatant owner) {
        return TechniqueMasteryResolver.resolveInt(
            condition.masteryProgression, TechniqueMasteryProgressions.TICK,
            condition.tick, TechniqueMasteryResolver.masteryOf(owner));
    }

    private static int conditionRound(AbilityConditionData condition, BattleCombatant owner) {
        return TechniqueMasteryResolver.resolveInt(
            condition.masteryProgression, TechniqueMasteryProgressions.ROUND,
            condition.round, TechniqueMasteryResolver.masteryOf(owner));
    }

    private static double conditionPercent(
        AbilityConditionData condition,
        BattleCombatant owner
    ) {
        return TechniqueMasteryResolver.resolvePercent(
            condition.masteryProgression, TechniqueMasteryProgressions.PERCENTAGE,
            condition.percentage, TechniqueMasteryResolver.masteryOf(owner));
    }

    private static boolean connectedHitHasTag(AbilityTrigger trigger, String tagName) {
        com.jjktbf.model.move.MoveTag tag;
        try { tag = com.jjktbf.model.move.MoveTag.valueOf(tagName); }
        catch (Exception ex) { return false; }
        if (trigger.hitComponent() != null
            && com.jjktbf.model.move.MoveTag.TYPE_TAGS.contains(tag)) {
            return trigger.hitComponent().getTags().contains(tag);
        }
        return trigger.move() != null && trigger.move().hasTag(tag.name());
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
        appendResourceMaximumEvents(
            source, target, previousMaxHp, previousMaxCe, tick, events);
    }

    private static void appendResourceMaximumEvents(
        BattleCombatant source,
        BattleCombatant target,
        int previousMaxHp,
        int previousMaxCe,
        int tick,
        List<CombatEvent> events
    ) {
        if (target.isPoolClampDeferred()) return;
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

    private static StatusEffectType status(String value, Double magnitude) {
        try { return StatusEffectType.fromName(value, magnitude != null ? magnitude : 0.0); }
        catch (Exception ex) { return null; }
    }

    private static int statValue(BattleCombatant combatant, String stat) {
        try { return StatKey.fromString(stat).get(combatant.getEffectiveStats()); }
        catch (Exception ex) { return 0; }
    }

    private static String abilityKey(Ability ability, int index) {
        if (ability.getId() != null && !ability.getId().isBlank()) return ability.getId();
        return String.valueOf(ability.getName()) + "#" + index;
    }

    private static String ruleKey(Ability ability, int abilityIndex, int ruleIndex) {
        return abilityKey(ability, abilityIndex) + "#" + abilityIndex
            + "#condition-" + ruleIndex;
    }

    private static BattleCombatant opponent(BattleState state, BattleCombatant owner) {
        return state.getPlayerCombatant() == owner
            ? state.getEnemyCombatant() : state.getPlayerCombatant();
    }

    private static double ratio(int current, int maximum) {
        return maximum <= 0 ? 0.0 : (double) current / maximum;
    }

    private static int value(Integer value) { return value == null ? 0 : value; }
    private static double value(Double value) { return value == null ? 0.0 : value; }
}
