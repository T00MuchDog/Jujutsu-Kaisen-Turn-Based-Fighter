package com.jjktbf.model.character.coded;

import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.StatusEffect;
import com.jjktbf.model.progression.TechniqueMasteryResolver;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.function.Predicate;

/** Runtime implementation for the Ratio cursed technique. */
public final class RatioAbility implements CodedAbilityRuntime {

    public static final String KEY = "RATIO";
    public static final String REINFORCEMENT_RATIO = "REINFORCEMENT_RATIO";
    public static final String RATIO_EFFECT = "RATIO_EFFECT";
    public static final String APPLY_TO_MOVE = "APPLY_TO_MOVE";
    public static final String CREATE_STACKS = "CREATE_STACKS";

    public static final int MAX_STACKS = 3;
    public static final int STACK_DURATION_TICKS = 50;
    public static final double STACK_TRIGGER_CHANCE = 0.70;
    public static final double DEFENSE_MULTIPLIER = 0.3;
    public static final String STACK_CAPACITY = "stackCapacity";
    public static final String STACK_DURATION_PARAMETER = "stackDurationTicks";
    public static final String TRIGGER_CHANCE_PERCENT = "triggerChancePercent";
    public static final String DEFENSE_PERCENT = "defensePercent";

    private final BattleCombatant owner;
    private final Map<String, List<CodedAbilityBinding>> bindingsByFeature;
    private final List<RatioStack> stacks = new ArrayList<>();
    private Integer maximumStacks;

    RatioAbility(
        BattleCombatant owner,
        Set<String> features,
        Map<String, List<CodedAbilityBinding>> bindingsByFeature
    ) {
        this.owner = owner;
        this.bindingsByFeature = bindingsByFeature == null ? Map.of() : bindingsByFeature;
    }

    @Override
    public List<CombatEvent> onTrigger(
        BattleState state,
        AbilityTrigger trigger,
        Predicate<String> featureActive
    ) {
        if (trigger.type() == AbilityTrigger.Type.BATTLE_START) {
            maximumStacks = configuredCapacity();
        }
        return List.of();
    }

    @Override
    public List<CombatEvent> onEffectFired(
        BattleState state,
        StatusEffect effect,
        BattleCombatant attacker,
        BattleCombatant defender,
        int tick
    ) {
        if (attacker != owner || !isRatioEffect(effect)
            || !CREATE_STACKS.equalsIgnoreCase(effect.getCodedTarget())) {
            return List.of();
        }

        int capacity = ensureCapacity();
        if (capacity <= 0) return List.of();
        int requested = effect.getCodedStackCount() == null ? 1 : effect.getCodedStackCount();
        int duration = TechniqueMasteryResolver.codedParameter(
            effect.getCodedParameters(), STACK_DURATION_PARAMETER, STACK_DURATION_TICKS);
        int triggerChance = TechniqueMasteryResolver.codedParameter(
            effect.getCodedParameters(), TRIGGER_CHANCE_PERCENT,
            (int) Math.round(STACK_TRIGGER_CHANCE * 100));
        int defensePercent = TechniqueMasteryResolver.codedParameter(
            effect.getCodedParameters(), DEFENSE_PERCENT,
            (int) Math.round(DEFENSE_MULTIPLIER * 100));
        int created = 0;
        while (created < requested && stacks.size() < capacity) {
            stacks.add(new RatioStack(defender, duration, triggerChance, defensePercent));
            created++;
        }
        if (created == 0) return List.of();

        String targetName = defender.getCharacter().getName();
        return List.of(event(tick, defender, owner.getCharacter().getName()
            + " marks " + targetName + " with " + created + " Ratio stack"
            + (created == 1 ? "" : "s") + " (" + stacks.size() + "/" + capacity + ")."));
    }

    @Override
    public CodedHitModifiers onAttackConnected(
        BattleCombatant attacker,
        BattleCombatant defender,
        Move move,
        HitComponent component,
        int tick,
        RandomSource rng,
        Predicate<String> featureActive
    ) {
        if (attacker != owner) return CodedHitModifiers.none();

        // Ratio's APPLY_TO_MOVE effect now lives on a specific HitComponent;
        // directRatio fires only for the component that carries it. The
        // move-level self effects (cast-time Ratio) still apply move-wide.
        StatusEffect directEffect = ratioMoveEffect(component.getOnHitEffects());
        if (directEffect == null) directEffect = ratioMoveEffect(move.getSelfEffects());
        directEffect = TechniqueMasteryResolver.resolve(
            directEffect, TechniqueMasteryResolver.masteryOf(owner));
        int directChance = directEffect == null ? 0 : TechniqueMasteryResolver.codedParameter(
            directEffect.getCodedParameters(), TRIGGER_CHANCE_PERCENT, 100);
        boolean directRatio = directEffect != null && (directChance >= 100
            || (directChance > 0 && rng.nextDouble() < directChance / 100.0));

        RatioStack consumed = consumeStackFor(defender);
        boolean consumedStack = consumed != null;
        boolean stackRatio = consumedStack
            && rng.nextDouble() < consumed.triggerChancePercent / 100.0;

        boolean reinforcementRatio = featureActive.test(REINFORCEMENT_RATIO);

        boolean ratioApplied = directRatio || stackRatio || reinforcementRatio;
        if (!ratioApplied && !consumedStack) return CodedHitModifiers.none();

        String ownerName = owner.getCharacter().getName();
        String targetName = defender.getCharacter().getName();
        String message = ratioApplied
            ? (stackRatio ? "Ratio triggers! " : "Ratio activates! ")
                + ownerName + " strikes " + targetName + "'s 7:3 point with " + move.getName() + "!"
            : ownerName + " consumes 1 Ratio stack on " + targetName
                + ", but the 7:3 point does not open.";
        List<CombatEvent> events = List.of(event(
            stackRatio ? CombatEvent.Type.RATIO_TRIGGERED : CombatEvent.Type.ABILITY_ACTIVATED,
            tick,
            defender,
            message
        ));
        int defensePercent = 100;
        if (directRatio) defensePercent = Math.min(defensePercent,
            TechniqueMasteryResolver.codedParameter(
                directEffect.getCodedParameters(), DEFENSE_PERCENT, 30));
        if (stackRatio) defensePercent = Math.min(defensePercent, consumed.defensePercent);
        if (reinforcementRatio) defensePercent = Math.min(defensePercent,
            featureParameter(REINFORCEMENT_RATIO, DEFENSE_PERCENT, 30));
        return ratioApplied
            ? new CodedHitModifiers(true, defensePercent / 100.0, events)
            : new CodedHitModifiers(false, 1.0, events);
    }

    @Override
    public List<CombatEvent> tickTimelineEffects(int tick) {
        List<BattleCombatant> expiredTargets = new ArrayList<>();
        for (Iterator<RatioStack> iterator = stacks.iterator(); iterator.hasNext(); ) {
            RatioStack stack = iterator.next();
            stack.remainingTicks--;
            if (stack.remainingTicks <= 0) {
                iterator.remove();
                expiredTargets.add(stack.target);
            }
        }
        if (expiredTargets.isEmpty()) return List.of();
        List<CombatEvent> events = new ArrayList<>();
        for (BattleCombatant target : expiredTargets) {
            events.add(event(tick, target, "A Ratio stack marking "
                + target.getCharacter().getName() + " expires."));
        }
        return events;
    }

    @Override
    public int getRemainingTimelineEffectTicks() {
        return stacks.stream().mapToInt(stack -> stack.remainingTicks).max().orElse(0);
    }

    @Override
    public List<CombatEvent> drainPendingEvents(int tick) {
        return List.of();
    }

    @Override
    public CodedAbilityState state() {
        int capacity = maximumStacks == null ? configuredCapacity() : maximumStacks;
        return new CodedAbilityState(KEY, "Ratio", stacks.size(), Math.max(0, capacity));
    }

    public static boolean supportsFeature(String feature) {
        return REINFORCEMENT_RATIO.equals(feature);
    }

    public static boolean supportsTarget(String target, Integer stackCount) {
        if (APPLY_TO_MOVE.equals(target)) return stackCount == null;
        return CREATE_STACKS.equals(target)
            && stackCount != null && stackCount >= 1 && stackCount <= 99;
    }

    private RatioStack consumeStackFor(BattleCombatant defender) {
        for (Iterator<RatioStack> iterator = stacks.iterator(); iterator.hasNext(); ) {
            RatioStack stack = iterator.next();
            if (stack.target == defender) {
                iterator.remove();
                return stack;
            }
        }
        return null;
    }

    private static boolean isRatioEffect(StatusEffect effect) {
        return effect != null
            && KEY.equalsIgnoreCase(effect.getCodedAbilityKey())
            && RATIO_EFFECT.equalsIgnoreCase(effect.getCodedAction());
    }

    private static StatusEffect ratioMoveEffect(List<StatusEffect> effects) {
        return effects.stream().filter(effect -> isRatioEffect(effect)
            && APPLY_TO_MOVE.equalsIgnoreCase(effect.getCodedTarget()))
            .findFirst().orElse(null);
    }

    private CombatEvent event(int tick, BattleCombatant target, String message) {
        return event(CombatEvent.Type.ABILITY_ACTIVATED, tick, target, message);
    }

    private CombatEvent event(
        CombatEvent.Type type,
        int tick,
        BattleCombatant target,
        String message
    ) {
        return CombatEvent.of(type)
            .source(owner).target(target).tick(tick)
            .codedAbilityState(state())
            .message(message)
            .build();
    }

    private static final class RatioStack {
        private final BattleCombatant target;
        private int remainingTicks;
        private final int triggerChancePercent;
        private final int defensePercent;

        private RatioStack(
            BattleCombatant target,
            int remainingTicks,
            int triggerChancePercent,
            int defensePercent
        ) {
            this.target = target;
            this.remainingTicks = remainingTicks;
            this.triggerChancePercent = triggerChancePercent;
            this.defensePercent = defensePercent;
        }
    }

    private int ensureCapacity() {
        if (maximumStacks == null) maximumStacks = configuredCapacity();
        return maximumStacks;
    }

    private int configuredCapacity() {
        return bindingsByFeature.containsKey(REINFORCEMENT_RATIO)
            ? featureParameter(REINFORCEMENT_RATIO, STACK_CAPACITY, MAX_STACKS) : 0;
    }

    private int featureParameter(String feature, String parameter, int fallback) {
        List<CodedAbilityBinding> bindings = bindingsByFeature.getOrDefault(feature, List.of());
        if (bindings.isEmpty() || bindings.get(0).effect() == null) return fallback;
        var resolved = TechniqueMasteryResolver.resolve(
            bindings.get(0).effect(), TechniqueMasteryResolver.masteryOf(owner));
        return TechniqueMasteryResolver.codedParameter(
            resolved.codedParameters, parameter, fallback);
    }
}
