package com.jjktbf.model.character.coded;

import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.move.StatusEffect;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

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
    public static final double REINFORCEMENT_TRIGGER_CHANCE = 0.05;
    public static final double DEFENSE_MULTIPLIER = 0.3;

    private final BattleCombatant owner;
    private final Set<String> features;
    private final List<RatioStack> stacks = new ArrayList<>();

    RatioAbility(BattleCombatant owner, Set<String> features) {
        this.owner = owner;
        this.features = Set.copyOf(features);
    }

    @Override
    public List<CombatEvent> onTrigger(BattleState state, AbilityTrigger trigger) {
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

        int requested = effect.getCodedStackCount() == null ? 1 : effect.getCodedStackCount();
        int created = 0;
        while (created < requested && stacks.size() < MAX_STACKS) {
            stacks.add(new RatioStack(defender, STACK_DURATION_TICKS));
            created++;
        }
        if (created == 0) return List.of();

        String targetName = defender.getCharacter().getName();
        return List.of(event(tick, defender, owner.getCharacter().getName()
            + " marks " + targetName + " with " + created + " Ratio stack"
            + (created == 1 ? "" : "s") + " (" + stacks.size() + "/" + MAX_STACKS + ")."));
    }

    @Override
    public CodedHitModifiers onAttackConnected(
        BattleCombatant attacker,
        BattleCombatant defender,
        Move move,
        int tick,
        RandomSource rng
    ) {
        if (attacker != owner) return CodedHitModifiers.none();

        boolean directRatio = appliesRatioToMove(move.getOnHitEffects())
            || appliesRatioToMove(move.getSelfEffects());

        boolean consumedStack = consumeStackFor(defender);
        boolean stackRatio = consumedStack && rng.nextDouble() < STACK_TRIGGER_CHANCE;

        boolean reinforcementRatio = false;
        if (features.contains(REINFORCEMENT_RATIO) && isReinforcement(move)) {
            reinforcementRatio = rng.nextDouble() < REINFORCEMENT_TRIGGER_CHANCE;
        }

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
        return ratioApplied
            ? new CodedHitModifiers(true, DEFENSE_MULTIPLIER, events)
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
    public boolean preventFatalDamage() {
        return false;
    }

    @Override
    public List<CombatEvent> drainPendingEvents(int tick) {
        return List.of();
    }

    @Override
    public CodedAbilityState state() {
        return new CodedAbilityState(KEY, "Ratio", stacks.size(), MAX_STACKS);
    }

    public static boolean supportsFeature(String feature) {
        return REINFORCEMENT_RATIO.equals(feature);
    }

    public static boolean supportsTarget(String target, Integer stackCount) {
        if (APPLY_TO_MOVE.equals(target)) return stackCount == null;
        return CREATE_STACKS.equals(target)
            && stackCount != null && stackCount >= 1 && stackCount <= MAX_STACKS;
    }

    private boolean consumeStackFor(BattleCombatant defender) {
        for (Iterator<RatioStack> iterator = stacks.iterator(); iterator.hasNext(); ) {
            if (iterator.next().target == defender) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private static boolean isRatioEffect(StatusEffect effect) {
        return effect != null
            && KEY.equalsIgnoreCase(effect.getCodedAbilityKey())
            && RATIO_EFFECT.equalsIgnoreCase(effect.getCodedAction());
    }

    private static boolean appliesRatioToMove(List<StatusEffect> effects) {
        return effects.stream().anyMatch(effect -> isRatioEffect(effect)
            && APPLY_TO_MOVE.equalsIgnoreCase(effect.getCodedTarget()));
    }

    private static boolean isReinforcement(Move move) {
        return move.getTags().contains(MoveTag.PHYSICAL)
            && move.getTags().contains(MoveTag.CURSED_ENERGY);
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

        private RatioStack(BattleCombatant target, int remainingTicks) {
            this.target = target;
            this.remainingTicks = remainingTicks;
        }
    }
}
