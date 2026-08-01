package com.jjktbf.model.character.coded;

import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.move.StatusEffect;
import com.jjktbf.model.progression.TechniqueMasteryResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.function.Predicate;

/** Runtime implementation for the Miracles cursed technique. */
public final class MiraclesAbility implements CodedAbilityRuntime {

    public static final String KEY = "MIRACLES";
    public static final String RESERVOIR = "RESERVOIR";
    public static final String FATEFUL_REPRIEVE = "FATEFUL_REPRIEVE";
    public static final String FORTUNE_RECLAIMED = "FORTUNE_RECLAIMED";
    public static final String CREATE = "CREATE";
    public static final int MAX_MIRACLES = 6;
    public static final String CAPACITY = "capacity";
    public static final String STARTING_AMOUNT = "startingAmount";
    public static final String GAIN = "gain";
    public static final String COST = "cost";

    private final BattleCombatant owner;
    private final Set<String> features;
    private final Map<String, List<CodedAbilityBinding>> bindingsByFeature;
    private int miracles;
    private int capacity = MAX_MIRACLES;
    private final List<Integer> pendingFatalAversions = new ArrayList<>();

    MiraclesAbility(
        BattleCombatant owner,
        Set<String> features,
        Map<String, List<CodedAbilityBinding>> bindingsByFeature
    ) {
        this.owner = owner;
        this.features = Set.copyOf(features);
        this.bindingsByFeature = bindingsByFeature == null ? Map.of() : bindingsByFeature;
    }

    @Override
    public List<CombatEvent> onTrigger(
        BattleState state,
        AbilityTrigger trigger,
        Predicate<String> featureActive
    ) {
        if (trigger.type() == AbilityTrigger.Type.BATTLE_START
            && featureActive.test(RESERVOIR)) {
            capacity = featureParameter(RESERVOIR, CAPACITY, MAX_MIRACLES);
            int startingAmount = Math.min(capacity,
                featureParameter(RESERVOIR, STARTING_AMOUNT, MAX_MIRACLES));
            miracles = startingAmount;
            return List.of(event(trigger.tick(), gainMessage(startingAmount)));
        }
        if (!featureActive.test(FORTUNE_RECLAIMED)) return List.of();
        int gained = addMiracles(featureParameter(FORTUNE_RECLAIMED, GAIN, 1));
        if (gained <= 0) return List.of();
        return List.of(event(trigger.tick(), gainMessage(gained)));
    }

    @Override
    public List<CombatEvent> onEffectFired(
        BattleState state,
        StatusEffect effect,
        BattleCombatant attacker,
        BattleCombatant defender,
        int tick
    ) {
        // Miracle Creation is expressed as a coded self-effect row (key=MIRACLES,
        // action=CREATE). The move itself is plain data — this runtime owns the
        // hardcoded "create a miracle" behaviour, gated on the Reservoir feature.
        if (!hasFeature(RESERVOIR)
            || !KEY.equalsIgnoreCase(effect.getCodedAbilityKey())
            || !CREATE.equalsIgnoreCase(effect.getCodedAction())) {
            return List.of();
        }
        int gained = addMiracles(TechniqueMasteryResolver.codedParameter(
            effect.getCodedParameters(), GAIN, 1));
        if (gained <= 0) return List.of();
        return List.of(CombatEvent.of(CombatEvent.Type.ABILITY_ACTIVATED)
            .source(owner).target(owner).tick(tick)
            .codedAbilityState(state())
            .message(owner.getCharacter().getName() + " gains " + gained
                + (gained == 1 ? " Miracle" : " Miracles") + " through Miracle Creation ("
                + remainingText(miracles) + ").")
            .build());
    }

    @Override
    public boolean preventFatalDamage(Predicate<String> featureActive) {
        int cost = featureParameter(FATEFUL_REPRIEVE, COST, 1);
        if (!featureActive.test(FATEFUL_REPRIEVE) || miracles < cost) return false;
        miracles -= cost;
        pendingFatalAversions.add(miracles);
        return true;
    }

    @Override
    public List<CombatEvent> drainPendingEvents(int tick) {
        if (pendingFatalAversions.isEmpty()) return List.of();
        List<CombatEvent> events = new ArrayList<>();
        for (int remaining : pendingFatalAversions) {
            events.add(event(tick, owner.getCharacter().getName()
                + " uses " + featureParameter(FATEFUL_REPRIEVE, COST, 1)
                + " Miracle to avert a fatal blow (" + remainingText(remaining) + ").", remaining));
        }
        pendingFatalAversions.clear();
        return events;
    }

    @Override
    public CodedAbilityState state() {
        return miracleState(miracles);
    }

    public static boolean supportsFeature(String feature) {
        return RESERVOIR.equals(feature)
            || FATEFUL_REPRIEVE.equals(feature)
            || FORTUNE_RECLAIMED.equals(feature);
    }

    private boolean hasFeature(String feature) {
        return features.contains(feature);
    }

    private int addMiracles(int requested) {
        int gained = Math.max(0, Math.min(requested, capacity - miracles));
        miracles += gained;
        return gained;
    }

    private String gainMessage(int gained) {
        return owner.getCharacter().getName() + " gains " + gained + " Miracle"
            + (gained == 1 ? "" : "s") + " (" + remainingText(miracles) + ").";
    }

    private String remainingText(int remaining) {
        return remaining + "/" + capacity + " remaining";
    }

    private CodedAbilityState miracleState(int value) {
        return new CodedAbilityState(KEY, "Miracles", value, capacity);
    }

    private CombatEvent event(int tick, String message) {
        return event(tick, message, miracles);
    }

    private CombatEvent event(int tick, String message, int remaining) {
        return CombatEvent.of(CombatEvent.Type.ABILITY_ACTIVATED)
            .source(owner).target(owner).tick(tick)
            .codedAbilityState(miracleState(remaining))
            .message(message)
            .build();
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
