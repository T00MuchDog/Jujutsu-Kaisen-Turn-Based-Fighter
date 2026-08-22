package com.jjktbf.model.combat;

import com.jjktbf.model.character.*;
import com.jjktbf.model.character.coded.CodedAbilityBinding;
import com.jjktbf.model.character.coded.CodedAbilityRegistry;
import com.jjktbf.model.character.coded.CodedHitModifiers;
import com.jjktbf.model.character.coded.CodedMoveResponse;
import com.jjktbf.model.move.StatusEffect;
import com.jjktbf.model.move.StatusEffectMessages;
import com.jjktbf.model.move.StatusEffectType;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveEffectData;
import com.jjktbf.model.move.MoveEffectTrigger;
import com.jjktbf.model.move.MoveTag;
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
    private BattleCharacterLookup characterLookup;

    public AbilityActivationEngine(RandomSource rng) {
        this(rng, null);
    }

    public AbilityActivationEngine(RandomSource rng, BattleCharacterLookup characterLookup) {
        this.rng = rng;
        this.characterLookup = characterLookup;
    }

    public AbilityActivationEngine withCharacterLookup(BattleCharacterLookup lookup) {
        this.characterLookup = lookup;
        return this;
    }

    public List<CombatEvent> process(BattleState state, AbilityTrigger initialTrigger) {
        List<CombatEvent> events = new ArrayList<>();
        ArrayDeque<AbilityTrigger> triggers = new ArrayDeque<>();
        Set<RuleActivationKey> activatedThisChain = new HashSet<>();
        triggers.add(initialTrigger);
        int processed = 0;
        while (!triggers.isEmpty() && processed++ < MAX_CHAINED_TRIGGERS) {
            AbilityTrigger trigger = triggers.removeFirst();
            events.addAll(processTransformationReturns(state, trigger));
            Map<RuleActivationKey, Boolean> activationCache = new HashMap<>();
            // Iterate every active combatant as a possible ability owner. The
            // single-enemy context for an owner is the trigger's relevant
            // counterpart (the other participant), falling back to the first
            // active enemy. Removed combatants must not initiate new triggers.
            for (BattleCombatant owner : state.activeCombatants()) {
                if (!owner.isActive()) continue;
                if ((trigger.type() == AbilityTrigger.Type.BATTLE_START
                        || trigger.type() == AbilityTrigger.Type.ROUND_START)
                    && trigger.actor() != null && trigger.actor() != owner) {
                    continue;
                }
                BattleCombatant enemy = relevantEnemy(state, trigger, owner);
                observeRules(state, owner, enemy, trigger, activatedThisChain);
                events.addAll(dispatchCodedTrigger(
                    state, owner, enemy, trigger, activationCache));
                evaluateOwner(state, owner, enemy, trigger,
                    events, triggers, activatedThisChain, activationCache);
            }
        }
        if (!triggers.isEmpty()) {
            System.err.println("[WARN] Ability activation chain exceeded "
                + MAX_CHAINED_TRIGGERS + " events.");
        }
        return events;
    }

    /**
     * Evaluate and execute the shared effect primitives attached to one move
     * trigger. The trigger is implicit and always matches; each row may add an
     * ability-style condition tree and chance roll.
     */
    public List<CombatEvent> processMoveEffects(
        BattleState state,
        BattleCombatant owner,
        BattleCombatant currentTarget,
        Move move,
        MoveEffectTrigger moveTrigger,
        int componentIndex,
        int tick
    ) {
        return processMoveEffects(
            state, owner,
            currentTarget == null ? List.of() : List.of(currentTarget),
            move, moveTrigger, componentIndex, tick);
    }

    /** Execute one move trigger against its complete resolved target set. */
    public List<CombatEvent> processMoveEffects(
        BattleState state,
        BattleCombatant owner,
        List<BattleCombatant> currentTargets,
        Move move,
        MoveEffectTrigger moveTrigger,
        int componentIndex,
        int tick
    ) {
        return processMoveEffects(state, owner, currentTargets, move, moveTrigger,
            componentIndex, tick, List.of());
    }

    /**
     * Execute one move trigger against its complete resolved target set, with an
     * explicit set of allied {@code moveAllies} that {@link AbilityEffectTarget#ALLY}
     * / {@link AbilityEffectTarget#SELF_AND_ALLY} effect rows resolve to. For a
     * defensive move this is the set of allies its active-defense window is
     * conferred to; callers pass it so on-fire effects can buff those same allies.
     */
    public List<CombatEvent> processMoveEffects(
        BattleState state,
        BattleCombatant owner,
        List<BattleCombatant> currentTargets,
        Move move,
        MoveEffectTrigger moveTrigger,
        int componentIndex,
        int tick,
        List<BattleCombatant> moveAllies
    ) {
        if (state == null || owner == null || move == null || !move.usesUnifiedEffects()) {
            return List.of();
        }
        List<BattleCombatant> moveTargets = currentTargets == null
            ? List.of() : currentTargets.stream().filter(java.util.Objects::nonNull).toList();
        BattleCombatant currentTarget = moveTargets.isEmpty() ? null : moveTargets.get(0);
        HitComponent component = componentIndex >= 0
            && componentIndex < move.getHitComponents().size()
                ? move.getHitComponents().get(componentIndex) : null;
        AbilityTrigger trigger = moveEffectTrigger(
            owner, currentTarget, move, component, moveTrigger, tick);
        List<CombatEvent> events = new ArrayList<>();
        ArrayDeque<AbilityTrigger> followUps = new ArrayDeque<>();
        int mastery = TechniqueMasteryResolver.masteryOf(owner);
        for (MoveEffectData authored : move.effectsFor(moveTrigger, componentIndex)) {
            if (CodedAbilityRegistry.executesBeforeHit(authored)) continue;
            AbilityEffectTarget targetMode;
            try { targetMode = AbilityEffectTarget.valueOf(authored.target); }
            catch (Exception exception) { targetMode = AbilityEffectTarget.SELF; }
            if (moveTrigger == MoveEffectTrigger.ON_FIRE
                && moveTargets.size() > 1
                && targetMode != AbilityEffectTarget.SELF) {
                if (targetMode == AbilityEffectTarget.BOTH
                    && activateMoveEffect(
                        state, owner, currentTarget, move, authored,
                        moveTrigger, componentIndex, trigger, tick)) {
                    AbilityEffectData selfEffect = TechniqueMasteryResolver.resolve(
                        authored, mastery);
                    selfEffect.target = AbilityEffectTarget.SELF.name();
                    applyEffect(state, owner, currentTarget, selfEffect,
                        tick, events, followUps, true, move, component, List.of(), List.of());
                }
                for (BattleCombatant target : moveTargets) {
                    AbilityTrigger targetTrigger = moveEffectTrigger(
                        owner, target, move, component, moveTrigger, tick);
                    if (!activateMoveEffect(
                        state, owner, target, move, authored,
                        moveTrigger, componentIndex, targetTrigger, tick)) continue;
                    AbilityEffectData targetEffect = TechniqueMasteryResolver.resolve(
                        authored, mastery);
                    targetEffect.target = AbilityEffectTarget.ENEMY.name();
                    applyEffect(state, owner, target, targetEffect,
                        tick, events, followUps, true, move, component, List.of(target), List.of());
                }
                continue;
            }
            if (!activateMoveEffect(
                state, owner, currentTarget, move, authored,
                moveTrigger, componentIndex, trigger, tick)) continue;

            AbilityEffectData effect = TechniqueMasteryResolver.resolve(authored, mastery);
            applyEffect(state, owner, currentTarget, effect, tick, events, followUps,
                true, move, component, moveTargets, moveAllies);
        }
        while (!followUps.isEmpty()) {
            events.addAll(process(state, followUps.removeFirst()));
        }
        return events;
    }

    private boolean activateMoveEffect(
        BattleState state,
        BattleCombatant owner,
        BattleCombatant currentTarget,
        Move move,
        MoveEffectData authored,
        MoveEffectTrigger trigger,
        int componentIndex,
        AbilityTrigger abilityTrigger,
        int tick
    ) {
        int mastery = TechniqueMasteryResolver.masteryOf(owner);
        AbilityConditionData resolvedCondition = TechniqueMasteryResolver.resolve(
            authored.resolvedCondition(), mastery);
        if (!evaluateMoveCondition(
            resolvedCondition, owner, currentTarget, state,
            abilityTrigger, List.of(abilityTrigger))) {
            return false;
        }
        double chance = Math.max(0.0, Math.min(1.0,
            authored.resolvedActivationChance(mastery)));
        return chance > 0.0 && (chance >= 1.0 || rng.nextDouble() < chance);
    }

    /**
     * Evaluate a Defensive+Attack hybrid's attack-launch gate: the authored
     * condition tree (evaluated against the launch context — the defender and
     * the attacker it just resolved against, or the fire-time target) plus the
     * optional chance roll. Mirrors {@link #activateMoveEffect} for the launch
     * itself rather than an effect row.
     */
    public boolean allowsAttackLaunch(
        BattleState state,
        BattleCombatant owner,
        BattleCombatant enemy,
        Move move,
        int tick
    ) {
        if (move == null) return false;
        int mastery = TechniqueMasteryResolver.masteryOf(owner);
        AbilityConditionData resolved = move.getAttackLaunchCondition() == null
            ? AbilityConditionData.always()
            : TechniqueMasteryResolver.resolve(move.getAttackLaunchCondition(), mastery);
        AbilityTrigger trigger = AbilityTrigger.move(
            move.launchesAttackOnDefence()
                ? AbilityTrigger.Type.MOVE_BLOCKED : AbilityTrigger.Type.MOVE_USED,
            owner, enemy, move, tick);
        if (!evaluateMoveCondition(resolved, owner, enemy, state, trigger, List.of(trigger))) {
            return false;
        }
        if (!move.isAttackLaunchChanceEnabled()) return true;
        double chance = Math.max(0.0, Math.min(1.0, move.getAttackLaunchChance() / 100.0));
        return chance > 0.0 && (chance >= 1.0 || rng.nextDouble() < chance);
    }

    private static AbilityTrigger moveEffectTrigger(
        BattleCombatant owner,
        BattleCombatant target,
        Move move,
        HitComponent component,
        MoveEffectTrigger trigger,
        int tick
    ) {
        if (trigger == MoveEffectTrigger.ON_HIT) {
            return AbilityTrigger.attackHit(owner, target, move, component, tick);
        }
        AbilityTrigger.Type type = trigger == MoveEffectTrigger.ON_FIRE
            ? AbilityTrigger.Type.MOVE_USED : AbilityTrigger.Type.MOVE_BLOCKED;
        return AbilityTrigger.move(type, owner, target, move, tick);
    }

    /**
     * The single-enemy context for an owner evaluating a trigger: the trigger's
     * other participant if it is on the opposing team, otherwise the first
     * active enemy. Phase/special triggers without a counterpart use the first
     * active enemy as the representative for legacy single-enemy evaluation
     * (multi-target fan-out is handled by {@link #targets}).
     */
    private static BattleCombatant relevantEnemy(
        BattleState state, AbilityTrigger trigger, BattleCombatant owner
    ) {
        if (state == null || owner == null) return null;
        BattleCombatant actor = trigger == null ? null : trigger.actor();
        BattleCombatant target = trigger == null ? null : trigger.target();
        if (actor == owner && isOpponent(state, owner, target)) return target;
        if (target == owner && isOpponent(state, owner, actor)) return actor;
        if (isOpponent(state, owner, actor)) return actor;
        if (isOpponent(state, owner, target)) return target;
        return state.firstActiveEnemyOf(owner);
    }

    private static boolean isOpponent(
        BattleState state, BattleCombatant owner, BattleCombatant candidate
    ) {
        BattleTeam ownerTeam = state.teamOf(owner);
        BattleTeam candidateTeam = state.teamOf(candidate);
        return ownerTeam != null && candidateTeam != null && ownerTeam != candidateTeam;
    }

    /** Evaluate an active coded effect after a hit connects but before defenses. */
    public CodedHitModifiers onAttackConnected(BattleState state, AbilityTrigger trigger) {
        if (state == null || trigger == null || trigger.actor() == null) {
            return CodedHitModifiers.none();
        }
        BattleCombatant owner = trigger.actor();
        BattleCombatant enemy = relevantEnemy(state, trigger, owner);
        Map<RuleActivationKey, Boolean> activationCache = new HashMap<>();
        Move move = trigger.move();
        int componentIndex = move == null || trigger.hitComponent() == null
            ? -1 : move.getHitComponents().indexOf(trigger.hitComponent());
        return owner.getCodedAbilities().onAttackConnected(
            trigger.actor(), trigger.target(), move, trigger.hitComponent(),
            trigger.tick(), rng,
            binding -> allowsCodedBinding(
                binding, owner, enemy, state, trigger, activationCache),
            effect -> activateMoveEffect(
                state, owner, trigger.target(), move, effect,
                effect.resolvedTrigger(),
                effect.resolvedTrigger() == MoveEffectTrigger.ON_HIT
                    ? componentIndex : -1,
                effect.resolvedTrigger() == MoveEffectTrigger.ON_HIT
                    ? trigger : moveEffectTrigger(
                        owner, trigger.target(), move, trigger.hitComponent(),
                        effect.resolvedTrigger(), trigger.tick()),
                trigger.tick()));
    }

    /** Evaluate coded reactions immediately before an incoming move executes. */
    public CodedMoveResponse beforeIncomingMove(BattleState state, AbilityTrigger trigger) {
        if (state == null || trigger == null || trigger.target() == null) {
            return CodedMoveResponse.none();
        }
        BattleCombatant owner = trigger.target();
        BattleCombatant enemy = relevantEnemy(state, trigger, owner);
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
        BattleCombatant enemy = relevantEnemy(state, trigger, owner);
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
        return evaluate(condition, owner, enemy, state, trigger, history, false);
    }

    private boolean evaluateMoveCondition(
        AbilityConditionData condition,
        BattleCombatant owner,
        BattleCombatant enemy,
        BattleState state,
        AbilityTrigger trigger,
        List<AbilityTrigger> history
    ) {
        return evaluate(condition, owner, enemy, state, trigger, history, true);
    }

    private boolean evaluate(
        AbilityConditionData condition,
        BattleCombatant owner,
        BattleCombatant enemy,
        BattleState state,
        AbilityTrigger trigger,
        List<AbilityTrigger> history,
        boolean targetLocal
    ) {
        if (condition == null) return false;
        if (condition.containsAlways()) return true;
        AbilityConditionType type;
        try { type = AbilityConditionType.fromName(condition.type); }
        catch (IllegalArgumentException ex) { return false; }

        if (type == AbilityConditionType.ALL) {
            return condition.children != null && !condition.children.isEmpty()
                && condition.children.stream().allMatch(child -> evaluate(
                    child, owner, enemy, state, trigger, history, targetLocal));
        }
        if (type == AbilityConditionType.ANY) {
            return condition.children != null
                && condition.children.stream().anyMatch(child -> evaluate(
                    child, owner, enemy, state, trigger, history, targetLocal));
        }

        return switch (type) {
            case ALWAYS -> true;
            case MANUAL_ACTIVATION, BATTLE_STARTED -> history.stream().anyMatch(candidate ->
                eventLeafMatches(type, condition, owner, enemy, state, candidate, targetLocal));
            case HP_PERCENT_AT_OR_BELOW -> anyActor(condition, owner, enemy, state, targetLocal,
                combatant -> ratio(combatant.getCurrentHp(), combatant.getMaxHp())
                    <= conditionPercent(condition, owner));
            case HP_PERCENT_AT_OR_ABOVE -> anyActor(condition, owner, enemy, state, targetLocal,
                combatant -> ratio(combatant.getCurrentHp(), combatant.getMaxHp())
                    >= conditionPercent(condition, owner));
            case HP_VALUE_AT_OR_BELOW -> anyActor(condition, owner, enemy, state, targetLocal,
                combatant -> combatant.getCurrentHp() <= conditionAmount(condition, owner));
            case HP_VALUE_AT_OR_ABOVE -> anyActor(condition, owner, enemy, state, targetLocal,
                combatant -> combatant.getCurrentHp() >= conditionAmount(condition, owner));
            case CE_PERCENT_AT_OR_BELOW -> anyActor(condition, owner, enemy, state, targetLocal,
                combatant -> ratio(combatant.getCurrentCe(), combatant.getMaxCursedEnergy())
                    <= conditionPercent(condition, owner));
            case CE_PERCENT_AT_OR_ABOVE -> anyActor(condition, owner, enemy, state, targetLocal,
                combatant -> ratio(combatant.getCurrentCe(), combatant.getMaxCursedEnergy())
                    >= conditionPercent(condition, owner));
            case CE_VALUE_AT_OR_BELOW -> anyActor(condition, owner, enemy, state, targetLocal,
                combatant -> combatant.getCurrentCe() <= conditionAmount(condition, owner));
            case CE_VALUE_AT_OR_ABOVE -> anyActor(condition, owner, enemy, state, targetLocal,
                combatant -> combatant.getCurrentCe() >= conditionAmount(condition, owner));
            case BLACK_FLASH_HIT -> history.stream().anyMatch(candidate ->
                eventLeafMatches(type, condition, owner, enemy, state, candidate, targetLocal));
            case IN_BLACK_FLASH_STATE -> anyActor(
                condition, owner, enemy, state, targetLocal,
                BattleCombatant::isInBlackFlashState);
            case BLACK_FLASH_STREAK_AT_LEAST -> anyActor(
                condition, owner, enemy, state, targetLocal,
                combatant -> combatant.getConsecutiveBfsHits() >= conditionAmount(condition, owner));
            case MOVE_USED, MOVE_TAG_USED, MOVE_WEAPON_REQUIRED, MOVE_TYPE_TAGS_EXACTLY,
                  ATTACK_HIT, ATTACK_MISSED, MOVE_BLOCKED, EVENT_TARGET,
                  TIMELINE_POINT_REACHED -> history.stream().anyMatch(candidate ->
                eventLeafMatches(type, condition, owner, enemy, state, candidate, targetLocal));
            case ATTACK_CONNECTED, CONNECTED_HIT_HAS_TAG, FATAL_DAMAGE ->
                eventLeafMatches(type, condition, owner, enemy, state, trigger, targetLocal);
            case ROUND_REACHED -> state.getRoundNumber() >= conditionRound(condition, owner);
            case TIMELINE_POINT_ON_ROUND, EVERY_N_ROUNDS, PHASE_REACHED, HEALED,
                 DAMAGE_DEALT_AT_LEAST, DAMAGE_TAKEN_AT_LEAST, CE_SPENT_AT_LEAST,
                 CE_LOST_AT_LEAST,
                  CE_RESTORED_AT_LEAST -> history.stream().anyMatch(candidate ->
                eventLeafMatches(type, condition, owner, enemy, state, candidate, targetLocal));
            case STAT_AT_OR_ABOVE -> anyActor(condition, owner, enemy, state, targetLocal,
                combatant -> statValue(combatant, condition.stat) >= conditionAmount(condition, owner));
            case STAT_AT_OR_BELOW -> anyActor(condition, owner, enemy, state, targetLocal,
                combatant -> statValue(combatant, condition.stat) <= conditionAmount(condition, owner));
            case HAS_STATUS -> statusPredicate(
                condition, owner, enemy, state, targetLocal, false);
            case DOES_NOT_HAVE_STATUS -> statusPredicate(
                condition, owner, enemy, state, targetLocal, true);
            case STATUS_APPLIED, STATUS_REMOVED -> history.stream().anyMatch(candidate ->
                eventLeafMatches(type, condition, owner, enemy, state, candidate, targetLocal));
            case CODED_STATE_AT_OR_ABOVE -> codedStatePredicate(
                condition, owner, enemy, state, targetLocal, true);
            case CODED_STATE_AT_OR_BELOW -> codedStatePredicate(
                condition, owner, enemy, state, targetLocal, false);
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
        applyEffect(state, owner, enemy, effect, tick, events, followUps,
            false, null, null, List.of(), List.of());
    }

    private void applyEffect(
        BattleState state,
        BattleCombatant owner,
        BattleCombatant enemy,
        AbilityEffectData effect,
        int tick,
        List<CombatEvent> events,
        ArrayDeque<AbilityTrigger> followUps,
        boolean moveContext,
        Move move,
        HitComponent component,
        List<BattleCombatant> moveTargets,
        List<BattleCombatant> moveAllies
    ) {
        AbilityEffectType type = safeType(effect);
        Integer effectComponentIndex = move == null || component == null
            ? null : move.getHitComponents().indexOf(component);
        List<BattleCombatant> targets = targets(
            effect, owner, enemy, state, moveContext, moveTargets, moveAllies);
        switch (type) {
            case HEAL_HP, HEAL_HP_PERCENT -> {
                for (BattleCombatant target : targets) {
                    int requested = type == AbilityEffectType.HEAL_HP
                        ? value(effect.intValue)
                        : (int) Math.round(target.getMaxHp() * value(effect.doubleValue));
                    int healed = target.heal(requested);
                    if (healed <= 0) continue;
                    events.add(CombatEvent.of(CombatEvent.Type.HP_RESTORED)
                        .source(owner).target(target).move(move)
                        .componentIndex(effectComponentIndex).intValue(healed).tick(tick)
                        .build());
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
                        .source(owner).target(target).move(move)
                        .componentIndex(effectComponentIndex).intValue(restored).tick(tick)
                        .build());
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
                        .source(owner).target(target).move(move)
                        .componentIndex(effectComponentIndex).intValue(drained).tick(tick)
                        .build());
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
                                owner, target, move, component, target.getCurrentHp(), tick)))
                        : target.receiveDamage(requested, fatalAmount -> preventFatalDamage(
                            state, AbilityTrigger.fatalDamage(
                                owner, target, move, component, fatalAmount, tick)));
                    events.addAll(target.getCodedAbilities().drainPendingEvents(tick));
                    events.add(CombatEvent.of(damage == 0
                            ? CombatEvent.Type.DAMAGE_IGNORED : CombatEvent.Type.DAMAGE_DEALT)
                        .source(owner).target(target).move(move).intValue(damage).tick(tick)
                        .componentIndex(effectComponentIndex)
                        .build());
                    if (damage > 0) {
                        followUps.add(AbilityTrigger.amount(
                            AbilityTrigger.Type.DAMAGE, owner, target, damage, tick));
                        if (target.removeStatusEffects(StatusEffectType.SLEEP) > 0) {
                            events.add(CombatEvent.of(CombatEvent.Type.STATUS_EXPIRED)
                                .source(owner).target(target).move(move)
                                .componentIndex(effectComponentIndex).tick(tick)
                                .message(target.getCharacter().getName()
                                    + " wakes from Sleep!").build());
                            followUps.add(AbilityTrigger.status(
                                AbilityTrigger.Type.STATUS_REMOVED,
                                target, StatusEffectType.SLEEP, tick));
                        }
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
                            effect.stringValue, effect.magnitude == null ? 0.0 : effect.magnitude),
                        effect.perTickRemovalChance == null
                            ? status.defaultPerTickRemovalChance()
                            : effect.perTickRemovalChance);
                    boolean accepted = extendStatusForCurrentPhase(state)
                        ? target.addStatusEffect(applied, state.getCurrentPhase())
                        : target.addStatusEffect(applied);
                    if (!accepted) continue;
                    events.add(CombatEvent.of(CombatEvent.Type.STATUS_APPLIED)
                        .source(owner).target(target).move(move)
                        .componentIndex(effectComponentIndex).tick(tick)
                        .message(StatusEffectMessages.applicationMessage(
                            owner.getCharacter().getName(),
                            target.getCharacter().getName(),
                            status,
                            owner == target)).build());
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
                        .source(owner).target(target).move(move)
                        .componentIndex(effectComponentIndex).tick(tick)
                        .build());
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
            case TEMP_STAT_ADD, TEMP_STAT_MULTIPLY, TEMP_STAT_SET_VALUE, TEMP_STAT_PERCENT,
                 BATTLE_STAT_ADD, BATTLE_STAT_MULTIPLY, BATTLE_STAT_PERCENT,
                 IGNORE_DAMAGE, DAMAGE_SHIELD,
                 SURVIVE_FATAL_DAMAGE, GUARANTEE_NEXT_HIT, GUARANTEE_NEXT_DODGE,
                 GUARANTEE_NEXT_BLACK_FLASH, CANCEL_NEXT_MOVE,
                 TEMP_LOCK_MOVE_TAG -> {
                for (BattleCombatant target : targets) {
                    addRuntimeEffect(state, owner, target, effect, tick, events);
                }
            }
            case TAUNT -> {
                // Refreshing group: re-taunting resets the duration instead of
                // stacking parallel Taunt effects on the same combatant.
                for (BattleCombatant target : targets) {
                    addRuntimeEffect(state, owner, target, effect, tick, events, "TAUNT");
                }
            }
            case STUN_CURRENT_ACTION -> {
                for (BattleCombatant target : targets) {
                    if (!target.stunCurrentAction(tick)) continue;
                    events.add(CombatEvent.of(CombatEvent.Type.MOVE_STUNNED)
                        .source(owner).target(target).move(move)
                        .componentIndex(effectComponentIndex).tick(tick)
                        .message(owner.getCharacter().getName() + "'s "
                            + (move == null ? "ability" : move.getName()) + " stunned "
                            + target.getCharacter().getName() + ", who could not move.")
                        .build());
                }
            }
            case STAT_ADD, STAT_MULTIPLY, STAT_DIVIDE, STAT_SET_VALUE, STAT_SET_MIN,
                 CE_COST_TO_MINIMUM, CE_COST_MULTIPLY, MOVE_ACCURACY_ADD,
                 MOVE_ACCURACY_MULTIPLY, OPPONENT_ACCURACY_ADD,
                 OPPONENT_ACCURACY_MULTIPLY, NEVER_MISS, NEVER_HIT, DAMAGE_MULTIPLY,
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
                        .source(owner).target(target).move(move)
                        .componentIndex(effectComponentIndex).tick(tick)
                        .message(StatusEffectMessages.applicationMessage(
                            owner.getCharacter().getName(),
                            target.getCharacter().getName(),
                            status,
                            owner == target)).build());
                    appendResourceMaximumEvents(
                        owner, target, previousMaxHp, previousMaxCe, tick, events);
                    followUps.add(AbilityTrigger.status(
                        AbilityTrigger.Type.STATUS_APPLIED, target, status, tick));
                }
            }
            case STAT_ALLOCATION_MINIMUM, STAT_BONUS_POINTS,
                  POISON_IMMUNITY, SOUL_AWARE_ATTACKS,
                  GRANT_MOVE, GRANT_ABILITY, UNLOCK_MOVE,
                  UNLOCK_TECHNIQUE, CE_COST_ALTER,
                  CODED -> {
                // CE_COST_ALTER is evaluated while the current move's cost is quoted.
            }
            case SUMMON_CHARACTER -> {
                // Shared runtime summon path: enqueue a shikigami onto the
                // owner's team, then materialize it immediately via
                // BattleState.drainPendingSummons so it is active for every
                // later move this same tick. A summon created during resolution
                // still gets no plan until next round's planning phase.
                if (effect.characterId != null && !effect.characterId.isBlank()
                    && state.enqueueSummon(owner, effect.characterId,
                        move != null && move.getTags().contains(MoveTag.INNATE_TECHNIQUE))
                    && moveContext) {
                    events.add(CombatEvent.of(CombatEvent.Type.MOVE_SUMMON)
                        .source(owner).move(move).componentIndex(effectComponentIndex).tick(tick)
                        .build());
                    if (characterLookup != null) {
                        for (BattleCombatant summon
                                : state.drainPendingSummons(characterLookup)) {
                            events.add(CombatEvent.summoned(
                                state.combatant(summon.getSummonerId()), summon, tick));
                        }
                    }
                }
            }
            case TRANSFORM_CHARACTER -> {
                if (effect.characterId == null || effect.characterId.isBlank()
                    || characterLookup == null) {
                    return;
                }
                java.util.Optional<com.jjktbf.model.character.Character> form =
                    characterLookup.findCharacter(effect.characterId.trim());
                if (form.isEmpty()) {
                    System.err.println("[WARN] Unknown transformation character id "
                        + effect.characterId + " — transformation skipped");
                    return;
                }
                TransformationHpMode hpMode;
                try {
                    hpMode = TransformationHpMode.fromName(effect.transformationHpMode);
                } catch (IllegalArgumentException exception) {
                    hpMode = TransformationHpMode.FULL;
                }
                for (BattleCombatant target : targets) {
                    BattleCombatant.TransformationAttempt attempt = target.transformInto(
                        form.get(), hpMode, effect.returnCondition);
                    // A row naming the origin form of an already-transformed
                    // combatant is a revert — broadcast it as one.
                    boolean reverted = attempt.changed() && target.getOriginCharacter() != null
                        && attempt.change().characterId()
                            .equals(target.getOriginCharacter().getId());
                    appendTransformationAttemptEvents(
                        owner, target, form.get(), attempt, reverted,
                        move, effectComponentIndex, tick, events);
                }
            }
            case DESUMMON_OWNED_SHIKIGAMI ->
                state.voluntarilyDesummonOwnedShikigami(owner);
            case DESUMMON_TARGET_SHIKIGAMI -> {
                for (BattleCombatant target : targets) {
                    if (target != null && target.isSummon()) {
                        state.voluntarilyDesummon(target);
                    }
                }
            }
            case CODED_MOVE_ACTION -> {
                StatusEffect coded = StatusEffect.coded(
                    effect.codedAbilityKey,
                    effect.codedAction,
                    effect.codedTarget,
                    effect.codedStackCount,
                    effect.codedParameters,
                    effect.masteryProgression);
                for (BattleCombatant target : targets) {
                    events.addAll(owner.getCodedAbilities().onEffectFired(
                        state, coded, owner, target, tick));
                }
            }
            case MAX_ACTIVE_SUMMONS, SUMMON_CE_UPKEEP_PER_ACTIVE_TICK,
                   BATTLE_STAT_ODDS_MULTIPLY,
                   MOVE_UNAVAILABLE_WHILE_OWNED_SUMMON_ACTIVE -> { }
        }
    }

    private List<CombatEvent> processTransformationReturns(
        BattleState state,
        AbilityTrigger trigger
    ) {
        List<CombatEvent> events = new ArrayList<>();
        for (BattleCombatant transformed : state.activeCombatants()) {
            AbilityConditionData condition = transformed.transformationReturnCondition();
            if (condition == null) continue;
            BattleCombatant enemy = relevantEnemy(state, trigger, transformed);
            boolean eventOpportunity = hasMatchingEventLeaf(
                condition, transformed, enemy, state, trigger);
            if (eventOpportunity) transformed.recordTransformationReturnTrigger(trigger);
            List<AbilityTrigger> history = transformed.transformationReturnTriggerHistory();
            boolean matches = evaluate(
                condition, transformed, enemy, state, trigger, history);
            boolean previouslyMatched = transformed.wasTransformationReturnConditionTrue();
            transformed.setTransformationReturnConditionTrue(matches);
            if (!matches || (previouslyMatched && !eventOpportunity)) continue;

            BattleCombatant.TransformationAttempt attempt = transformed.returnToOriginalForm();
            appendTransformationAttemptEvents(
                transformed, transformed, transformed.getOriginCharacter(), attempt, true, null, null,
                trigger == null ? 0 : trigger.tick(), events);
        }
        return events;
    }

    private static void appendTransformationAttemptEvents(
        BattleCombatant source,
        BattleCombatant target,
        com.jjktbf.model.character.Character destination,
        BattleCombatant.TransformationAttempt attempt,
        boolean reverted,
        Move move,
        Integer componentIndex,
        int tick,
        List<CombatEvent> events
    ) {
        if (attempt == null) return;
        if (attempt.failed()) {
            events.add(CombatEvent.of(CombatEvent.Type.EFFECT_FAILED)
                .source(source).target(target).move(move).componentIndex(componentIndex).tick(tick)
                .message(target.getCharacter().getName() + " cannot transform into "
                    + destination.getName() + " because that form has already been defeated.")
                .build());
            return;
        }
        appendTransformationEvents(
            source, target, attempt.change(), reverted, move, componentIndex, tick, events);
    }

    private static void appendTransformationEvents(
        BattleCombatant source,
        BattleCombatant target,
        BattleCombatant.TransformationChange change,
        boolean reverted,
        Move move,
        Integer componentIndex,
        int tick,
        List<CombatEvent> events
    ) {
        if (change == null) return;
        appendResourceMaximumEvents(
            source, target, change.previousMaxHp(), change.previousMaxCe(), tick, events);
        String message = reverted
            ? change.previousCharacterName() + " returns to " + change.characterName() + "!"
            : change.previousCharacterName() + " transforms into " + change.characterName() + "!";
        events.add(CombatEvent.of(reverted
                ? CombatEvent.Type.CHARACTER_REVERTED
                : CombatEvent.Type.CHARACTER_TRANSFORMED)
            .source(source).target(target).move(move).componentIndex(componentIndex)
            .previousCharacterId(change.previousCharacterId())
            .characterId(change.characterId()).characterName(change.characterName())
            .intValue(change.currentHp()).tick(tick).message(message).build());
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
            case BLACK_FLASH_HIT, MOVE_USED, MOVE_TAG_USED, MOVE_WEAPON_REQUIRED,
                  MOVE_TYPE_TAGS_EXACTLY, ATTACK_HIT, ATTACK_MISSED, MOVE_BLOCKED,
                  EVENT_TARGET,
                  TIMELINE_POINT_REACHED, TIMELINE_POINT_ON_ROUND,
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
        return eventLeafMatches(type, condition, owner, enemy, state, trigger, false);
    }

    private static boolean eventLeafMatches(
        AbilityConditionType type,
        AbilityConditionData condition,
        BattleCombatant owner,
        BattleCombatant enemy,
        BattleState state,
        AbilityTrigger trigger,
        boolean moveContext
    ) {
        return switch (type) {
            case MANUAL_ACTIVATION -> trigger.type() == AbilityTrigger.Type.MANUAL_ACTIVATION;
            case BATTLE_STARTED -> trigger.type() == AbilityTrigger.Type.BATTLE_START;
            case BLACK_FLASH_HIT -> trigger.type() == AbilityTrigger.Type.BLACK_FLASH
                && eventActorMatches(condition, owner, state, trigger.actor());
            case MOVE_USED -> trigger.type() == AbilityTrigger.Type.MOVE_USED
                && eventActorMatches(condition, owner, state, trigger.actor())
                && trigger.move() != null && trigger.move().getId().equals(condition.moveId);
            case MOVE_TAG_USED -> trigger.type() == AbilityTrigger.Type.MOVE_USED
                && eventActorMatches(condition, owner, state, trigger.actor())
                && trigger.move() != null && trigger.move().hasTag(condition.moveTag);
            case MOVE_WEAPON_REQUIRED -> trigger.type() == AbilityTrigger.Type.MOVE_USED
                && eventActorMatches(condition, owner, state, trigger.actor())
                && trigger.move() != null && trigger.move().hasWeaponTag();
            case MOVE_TYPE_TAGS_EXACTLY -> trigger.type() == AbilityTrigger.Type.MOVE_USED
                && eventActorMatches(condition, owner, state, trigger.actor())
                && AbilityConditionType.moveHasExactTypeTags(trigger.move(), condition.moveTags);
            case ATTACK_HIT -> trigger.type() == AbilityTrigger.Type.ATTACK_HIT
                && eventActorMatches(condition, owner, state, trigger.actor());
            case ATTACK_MISSED -> trigger.type() == AbilityTrigger.Type.ATTACK_MISSED
                && eventActorMatches(condition, owner, state, trigger.actor());
            case MOVE_BLOCKED -> trigger.type() == AbilityTrigger.Type.MOVE_BLOCKED
                && eventActorMatches(condition, owner, state, trigger.actor());
            case EVENT_TARGET -> trigger.target() != null
                && eventActorMatches(condition, owner, state, trigger.target());
            case ATTACK_CONNECTED -> (trigger.type() == AbilityTrigger.Type.ATTACK_CONNECTED
                    || moveContext && trigger.type() == AbilityTrigger.Type.ATTACK_HIT)
                && eventActorMatches(condition, owner, state, trigger.actor());
            case CONNECTED_HIT_HAS_TAG -> (trigger.type() == AbilityTrigger.Type.ATTACK_CONNECTED
                    || moveContext && trigger.type() == AbilityTrigger.Type.ATTACK_HIT)
                && eventActorMatches(condition, owner, state, trigger.actor())
                && connectedHitHasTag(trigger, condition.moveTag);
            case FATAL_DAMAGE -> trigger.type() == AbilityTrigger.Type.FATAL_DAMAGE
                && eventActorMatches(condition, owner, state, trigger.target());
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
                && eventActorMatches(condition, owner, state, trigger.actor())
                && trigger.amount() >= conditionAmount(condition, owner);
            case DAMAGE_DEALT_AT_LEAST -> trigger.type() == AbilityTrigger.Type.DAMAGE
                && eventActorMatches(condition, owner, state, trigger.actor())
                && trigger.amount() >= conditionAmount(condition, owner);
            case DAMAGE_TAKEN_AT_LEAST -> trigger.type() == AbilityTrigger.Type.DAMAGE
                && eventActorMatches(condition, owner, state, trigger.target())
                && trigger.amount() >= conditionAmount(condition, owner);
            case CE_SPENT_AT_LEAST -> trigger.type() == AbilityTrigger.Type.CE_SPENT
                && eventActorMatches(condition, owner, state, trigger.actor())
                && trigger.amount() >= conditionAmount(condition, owner);
            case CE_LOST_AT_LEAST -> trigger.type() == AbilityTrigger.Type.CE_LOST
                && eventActorMatches(condition, owner, state, trigger.actor())
                && trigger.amount() >= conditionAmount(condition, owner);
            case CE_RESTORED_AT_LEAST -> trigger.type() == AbilityTrigger.Type.CE_RESTORED
                && eventActorMatches(condition, owner, state, trigger.actor())
                && trigger.amount() >= conditionAmount(condition, owner);
            case STATUS_APPLIED -> trigger.type() == AbilityTrigger.Type.STATUS_APPLIED
                && eventActorMatches(condition, owner, state, trigger.actor())
                && StatusEffectType.referencedTypes(condition.statusType)
                    .contains(trigger.status());
            case STATUS_REMOVED -> trigger.type() == AbilityTrigger.Type.STATUS_REMOVED
                && eventActorMatches(condition, owner, state, trigger.actor())
                && StatusEffectType.referencedTypes(condition.statusType)
                    .contains(trigger.status());
            default -> false;
        };
    }

    /**
     * Resolve the combatants an ability effect hits. {@code SELF} targets the
     * owner; {@code ENEMY} fans out to every active enemy of the owner (multi-
     * combatant); {@code BOTH} is the union of self and all active enemies.
     *
     * <p>{@code ALLY} resolves, in move context, to the defensive move's conferred
     * beneficiaries ({@code moveAllies}) — i.e. exactly the allies its active-defense
     * window is granted to — or, in ability context, to every active ally of the
     * owner. {@code SELF_AND_ALLY} is the owner plus those allies.
     */
    private static List<BattleCombatant> targets(
        AbilityEffectData effect,
        BattleCombatant owner,
        BattleCombatant currentTarget,
        BattleState state,
        boolean moveContext,
        List<BattleCombatant> moveTargets,
        List<BattleCombatant> moveAllies
    ) {
        if (owner == null || state == null) return List.of();
        AbilityEffectTarget target;
        try { target = AbilityEffectTarget.valueOf(effect.target); }
        catch (Exception ex) { target = AbilityEffectTarget.SELF; }
        List<BattleCombatant> allies = moveContext
            ? (moveAllies == null ? List.of() : moveAllies)
            : state.activeAlliesOf(owner);
        return switch (target) {
            case SELF -> List.of(owner);
            case ENEMY -> moveContext
                ? moveTargets
                : state.activeEnemiesOf(owner);
            case ALLY -> allies;
            case BOTH -> {
                List<BattleCombatant> out = new ArrayList<>();
                out.add(owner);
                if (moveContext) {
                    moveTargets.stream().filter(targetCombatant -> targetCombatant != owner)
                        .forEach(out::add);
                } else {
                    out.addAll(state.activeEnemiesOf(owner));
                }
                yield out;
            }
            case SELF_AND_ALLY -> {
                List<BattleCombatant> out = new ArrayList<>();
                out.add(owner);
                allies.stream().filter(targetCombatant -> targetCombatant != owner)
                    .forEach(out::add);
                yield out;
            }
        };
    }

    private static boolean statusPredicate(
        AbilityConditionData condition,
        BattleCombatant owner,
        BattleCombatant enemy,
        BattleState state,
        boolean targetLocal,
        boolean negated
    ) {
        Set<StatusEffectType> statuses = StatusEffectType.referencedTypes(condition.statusType);
        if (statuses.isEmpty()) return false;
        return anyActor(condition, owner, enemy, state, targetLocal,
            combatant -> combatant.getActiveEffects().stream()
                .map(StatusEffect::getType)
                .anyMatch(statuses::contains) != negated);
    }

    private static boolean codedStatePredicate(
        AbilityConditionData condition,
        BattleCombatant owner,
        BattleCombatant enemy,
        BattleState state,
        boolean targetLocal,
        boolean atOrAbove
    ) {
        return anyActor(condition, owner, enemy, state, targetLocal, combatant ->
            combatant.getCodedAbilities().state(condition.codedAbilityKey)
                .map(codedState -> atOrAbove
                    ? codedState.currentValue() >= conditionAmount(condition, owner)
                    : codedState.currentValue() <= conditionAmount(condition, owner))
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
        addRuntimeEffect(state, source, target, effect, tick, events, null);
    }

    private static void addRuntimeEffect(
        BattleState state,
        BattleCombatant source,
        BattleCombatant target,
        AbilityEffectData effect,
        int tick,
        List<CombatEvent> events,
        String refreshGroup
    ) {
        int previousMaxHp = target.getMaxHp();
        int previousMaxCe = target.getMaxCursedEnergy();
        target.addRuntimeAbilityEffect(
            effect, state.getRoundNumber(), state.getCurrentPhase(), refreshGroup);
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
                .build());
        }
        if (target.getMaxCursedEnergy() != previousMaxCe) {
            events.add(CombatEvent.of(CombatEvent.Type.MAX_CE_CHANGED)
                .source(source).target(target).intValue(target.getMaxCursedEnergy()).tick(tick)
                .build());
        }
    }

    private static boolean anyActor(
        AbilityConditionData condition,
        BattleCombatant owner,
        BattleCombatant enemy,
        BattleState state,
        boolean targetLocal,
        java.util.function.Predicate<BattleCombatant> predicate
    ) {
        AbilityConditionActor actor = actor(condition);
        return switch (actor) {
            case SELF -> predicate.test(owner);
            case ENEMY -> targetLocal
                ? enemy != null && predicate.test(enemy)
                : state.activeEnemiesOf(owner).stream().anyMatch(predicate);
            case ANY -> predicate.test(owner)
                || (targetLocal
                    ? enemy != null && predicate.test(enemy)
                    : state.activeEnemiesOf(owner).stream().anyMatch(predicate));
        };
    }

    private static boolean eventActorMatches(
        AbilityConditionData condition,
        BattleCombatant owner,
        BattleState state,
        BattleCombatant eventActor
    ) {
        if (eventActor == null) return false;
        return switch (actor(condition)) {
            case SELF -> eventActor == owner;
            case ENEMY -> isOpponent(state, owner, eventActor);
            case ANY -> eventActor == owner || isOpponent(state, owner, eventActor);
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
        try { return combatant.getRuntimeStat(StatKey.fromString(stat)); }
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

    private static double ratio(int current, int maximum) {
        return maximum <= 0 ? 0.0 : (double) current / maximum;
    }

    private static int value(Integer value) { return value == null ? 0 : value; }
    private static double value(Double value) { return value == null ? 0.0 : value; }
}
