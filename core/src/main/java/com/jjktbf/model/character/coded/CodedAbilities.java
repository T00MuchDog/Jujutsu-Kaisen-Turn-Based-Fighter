package com.jjktbf.model.character.coded;

import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.MoveEffectData;
import com.jjktbf.model.move.StatusEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/** Generic dispatcher and state holder for compiled ability runtimes on one combatant. */
public final class CodedAbilities {

    static record RuntimeEntry(
        CodedAbilityRuntime runtime,
        Map<String, List<CodedAbilityBinding>> bindingsByFeature
    ) {
        RuntimeEntry {
            bindingsByFeature = bindingsByFeature == null ? Map.of() : bindingsByFeature;
        }
    }

    private final List<RuntimeEntry> runtimes;

    CodedAbilities(List<RuntimeEntry> runtimes) {
        this.runtimes = runtimes == null ? List.of() : List.copyOf(runtimes);
    }

    public List<CombatEvent> onTrigger(BattleState state, AbilityTrigger trigger) {
        return onTrigger(state, trigger, CodedAbilities::passiveBinding);
    }

    public List<CombatEvent> onTrigger(
        BattleState state,
        AbilityTrigger trigger,
        Predicate<CodedAbilityBinding> activationGate
    ) {
        List<CombatEvent> events = new ArrayList<>();
        for (RuntimeEntry entry : runtimes) {
            events.addAll(entry.runtime().onTrigger(
                state, trigger, featureGate(entry, activationGate)));
        }
        return events;
    }

    /**
     * Dispatch a coded effect row to every runtime (see
     * {@link CodedAbilityRuntime#onEffectFired}). A runtime that does not own the
     * row's coded key/action returns an empty list.
     */
    public List<CombatEvent> onEffectFired(
        BattleState state,
        StatusEffect effect,
        BattleCombatant attacker,
        BattleCombatant defender,
        int tick
    ) {
        List<CombatEvent> events = new ArrayList<>();
        for (RuntimeEntry entry : runtimes) {
            events.addAll(entry.runtime().onEffectFired(state, effect, attacker, defender, tick));
        }
        return events;
    }

    public boolean preventFatalDamage() {
        return preventFatalDamage(CodedAbilities::passiveBinding);
    }

    public boolean preventFatalDamage(Predicate<CodedAbilityBinding> activationGate) {
        for (RuntimeEntry entry : runtimes) {
            if (entry.runtime().preventFatalDamage(featureGate(entry, activationGate))) return true;
        }
        return false;
    }

    /**
     * Notify every runtime that one of the owner's summons was destroyed. See
     * {@link CodedAbilityRuntime#onOwnedSummonDestroyed}. Uses the passive-binding
     * gate: destruction state is a property of a passive technique, not an
     * activated ability.
     */
    public void onOwnedSummonDestroyed(
        BattleState state, BattleCombatant owner, BattleCombatant destroyedSummon
    ) {
        onOwnedSummonDestroyed(state, owner, destroyedSummon, CodedAbilities::passiveBinding);
    }

    public void onOwnedSummonDestroyed(
        BattleState state,
        BattleCombatant owner,
        BattleCombatant destroyedSummon,
        Predicate<CodedAbilityBinding> activationGate
    ) {
        for (RuntimeEntry entry : runtimes) {
            entry.runtime().onOwnedSummonDestroyed(
                state, owner, destroyedSummon, featureGate(entry, activationGate));
        }
    }

    public CodedHitModifiers onAttackConnected(
        BattleCombatant attacker,
        BattleCombatant defender,
        Move move,
        int tick,
        RandomSource rng
    ) {
        HitComponent component = move == null || move.getHitComponents().isEmpty()
            ? null : move.getHitComponents().get(0);
        return onAttackConnected(attacker, defender, move, component, tick, rng);
    }

    public CodedHitModifiers onAttackConnected(
        BattleCombatant attacker,
        BattleCombatant defender,
        Move move,
        HitComponent component,
        int tick,
        RandomSource rng
    ) {
        return onAttackConnected(
            attacker, defender, move, component, tick, rng,
            CodedAbilities::passiveBinding, effect -> true);
    }

    public CodedHitModifiers onAttackConnected(
        BattleCombatant attacker,
        BattleCombatant defender,
        Move move,
        HitComponent component,
        int tick,
        RandomSource rng,
        Predicate<CodedAbilityBinding> activationGate
    ) {
        return onAttackConnected(
            attacker, defender, move, component, tick, rng,
            activationGate, effect -> true);
    }

    public CodedHitModifiers onAttackConnected(
        BattleCombatant attacker,
        BattleCombatant defender,
        Move move,
        HitComponent component,
        int tick,
        RandomSource rng,
        Predicate<CodedAbilityBinding> activationGate,
        Predicate<MoveEffectData> moveEffectActive
    ) {
        CodedHitModifiers modifiers = CodedHitModifiers.none();
        for (RuntimeEntry entry : runtimes) {
            modifiers = modifiers.combine(entry.runtime().onAttackConnected(
                attacker, defender, move, component, tick, rng,
                featureGate(entry, activationGate), moveEffectActive));
        }
        return modifiers;
    }

    public CodedMoveResponse beforeIncomingMove(
        BattleState state,
        BattleCombatant attacker,
        BattleCombatant defender,
        Move move,
        int tick
    ) {
        return beforeIncomingMove(
            state, attacker, defender, move, tick, CodedAbilities::passiveBinding);
    }

    public CodedMoveResponse beforeIncomingMove(
        BattleState state,
        BattleCombatant attacker,
        BattleCombatant defender,
        Move move,
        int tick,
        Predicate<CodedAbilityBinding> activationGate
    ) {
        CodedMoveResponse response = CodedMoveResponse.none();
        for (RuntimeEntry entry : runtimes) {
            response = response.combine(entry.runtime().beforeIncomingMove(
                state, attacker, defender, move, tick,
                featureGate(entry, activationGate)));
        }
        return response;
    }

    public List<CombatEvent> tickTimelineEffects(int tick) {
        List<CombatEvent> events = new ArrayList<>();
        for (RuntimeEntry entry : runtimes) {
            events.addAll(entry.runtime().tickTimelineEffects(tick));
        }
        return events;
    }

    public int getRemainingTimelineEffectTicks() {
        return runtimes.stream()
            .mapToInt(entry -> entry.runtime().getRemainingTimelineEffectTicks())
            .max().orElse(0);
    }

    public List<CombatEvent> drainPendingEvents(int tick) {
        List<CombatEvent> events = new ArrayList<>();
        for (RuntimeEntry entry : runtimes) {
            events.addAll(entry.runtime().drainPendingEvents(tick));
        }
        return events;
    }

    public List<CodedAbilityState> states() {
        return runtimes.stream().map(entry -> entry.runtime().state()).toList();
    }

    public Optional<CodedAbilityState> state(String key) {
        if (key == null) return Optional.empty();
        return states().stream().filter(state -> state.key().equalsIgnoreCase(key)).findFirst();
    }

    private static Predicate<String> featureGate(
        RuntimeEntry entry,
        Predicate<CodedAbilityBinding> activationGate
    ) {
        Predicate<CodedAbilityBinding> gate = activationGate == null
            ? binding -> false : activationGate;
        return feature -> entry.bindingsByFeature()
            .getOrDefault(CodedAbilityRegistry.normalize(feature), List.of())
            .stream()
            .anyMatch(gate);
    }

    private static boolean passiveBinding(CodedAbilityBinding binding) {
        return binding != null && binding.ability() != null && binding.ability().isPassive();
    }

}
