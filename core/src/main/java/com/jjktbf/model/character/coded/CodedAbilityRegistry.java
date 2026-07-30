package com.jjktbf.model.character.coded;

import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.StatusEffect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Allow-list of compiled ability implementations and their data-defined features. */
public final class CodedAbilityRegistry {

    public record AbilityFeature(String key, String feature, String label) { }
    public record EffectAction(String key, String action, String label) { }
    public record StateKey(String key, String label) { }

    private CodedAbilityRegistry() {
    }

    public static CodedAbilities create(BattleCombatant owner, List<Ability> abilities) {
        Map<String, Set<String>> featuresByKey = new LinkedHashMap<>();
        Map<String, Map<String, List<CodedAbilityBinding>>> bindingsByKey = new LinkedHashMap<>();
        List<Ability> sourceAbilities = abilities == null ? List.of() : abilities;
        for (int abilityIndex = 0; abilityIndex < sourceAbilities.size(); abilityIndex++) {
            Ability ability = sourceAbilities.get(abilityIndex);
            if (ability == null) continue;
            for (int effectIndex = 0; effectIndex < ability.getEffects().size(); effectIndex++) {
                AbilityEffectData effect = ability.getEffects().get(effectIndex);
                if (effect == null || !effect.isCoded()) continue;
                String key = normalize(effect.codedAbilityKey);
                String feature = normalize(effect.codedFeature);
                if (!supportsAbilityEffect(key, feature)) {
                    System.err.println("[WARN] Unknown coded ability effect: "
                        + key + "/" + feature);
                    continue;
                }
                featuresByKey.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(feature);
                bindingsByKey.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(feature, ignored -> new ArrayList<>())
                    .add(new CodedAbilityBinding(ability, effect, abilityIndex, effectIndex));
            }
        }

        // A coded move effect must be usable independently of optional passive
        // features on the same technique. The move row instantiates the runtime;
        // learned coded abilities only add feature flags to it.
        if (owner != null && owner.getCharacter() != null) {
            for (Move move : owner.getCharacter().getKnownMoves()) {
                if (move == null) continue;
                List<StatusEffect> effects = new ArrayList<>(move.getOnHitEffects());
                effects.addAll(move.getSelfEffects());
                for (StatusEffect effect : effects) {
                    if (effect == null || !effect.isCoded()
                        || !supportsEffect(
                            effect.getCodedAbilityKey(),
                            effect.getCodedAction(),
                            effect.getCodedTarget(),
                            effect.getCodedStackCount())) {
                        continue;
                    }
                    featuresByKey.computeIfAbsent(
                        normalize(effect.getCodedAbilityKey()), ignored -> new LinkedHashSet<>());
                }
            }
        }

        List<CodedAbilities.RuntimeEntry> runtimes = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : featuresByKey.entrySet()) {
            CodedAbilityRuntime runtime = null;
            if (MiraclesAbility.KEY.equals(entry.getKey())) {
                runtime = new MiraclesAbility(owner, entry.getValue());
            } else if (RatioAbility.KEY.equals(entry.getKey())) {
                runtime = new RatioAbility(owner, entry.getValue());
            } else if (NewShadowStyleAbility.KEY.equals(entry.getKey())) {
                runtime = new NewShadowStyleAbility(owner, entry.getValue());
            }
            if (runtime != null) {
                runtimes.add(new CodedAbilities.RuntimeEntry(
                    runtime,
                    bindingsByKey.getOrDefault(entry.getKey(), Map.of())));
            }
        }
        return new CodedAbilities(runtimes);
    }

    public static boolean supportsAbilityEffect(String key, String feature) {
        String normalizedKey = normalize(key);
        String normalizedFeature = normalize(feature);
        return (MiraclesAbility.KEY.equals(normalizedKey)
            && MiraclesAbility.supportsFeature(normalizedFeature))
            || (RatioAbility.KEY.equals(normalizedKey)
            && RatioAbility.supportsFeature(normalizedFeature))
            || (NewShadowStyleAbility.KEY.equals(normalizedKey)
            && NewShadowStyleAbility.supportsFeature(normalizedFeature));
    }

    public static List<AbilityFeature> abilityFeatures() {
        return List.of(
            new AbilityFeature(MiraclesAbility.KEY,
                MiraclesAbility.RESERVOIR, "Miracle Reservoir"),
            new AbilityFeature(MiraclesAbility.KEY,
                MiraclesAbility.FATEFUL_REPRIEVE, "Fateful Reprieve"),
            new AbilityFeature(MiraclesAbility.KEY,
                MiraclesAbility.FORTUNE_RECLAIMED, "Fortune Reclaimed"),
            new AbilityFeature(RatioAbility.KEY,
                RatioAbility.REINFORCEMENT_RATIO, "Ratio Reinforcement"),
            new AbilityFeature(NewShadowStyleAbility.KEY,
                NewShadowStyleAbility.SIMPLE_DOMAIN_BINDING_VOW,
                "Simple Domain Binding Vow")
        );
    }

    public static List<StateKey> stateKeys() {
        return List.of(
            new StateKey(MiraclesAbility.KEY, "Miracles"),
            new StateKey(RatioAbility.KEY, "Ratio stacks"),
            new StateKey(NewShadowStyleAbility.KEY, "Simple Domain")
        );
    }

    public static boolean supportsStateKey(String key) {
        String normalized = normalize(key);
        return stateKeys().stream().anyMatch(state -> state.key().equals(normalized));
    }

    /**
     * Validate a coded <em>effect</em> row's ability key/action binding.
     *
     * <p>This is the allow-list used by the move editor and content catalog to
     * validate coded self-effect and on-hit-effect rows (e.g. Miracle Creation's
     * {@code MIRACLES}/{@code CREATE} self-effect). It is the precedent for
     * future technique moves whose hardcoded behaviour is expressed as an editable
     * effect row rather than state baked onto the move.
     */
    public static boolean supportsEffectAction(String key, String action) {
        String normalizedKey = normalize(key);
        String normalizedAction = normalize(action);
        if (normalizedKey.isEmpty() && normalizedAction.isEmpty()) return true;
        return (MiraclesAbility.KEY.equals(normalizedKey)
            && MiraclesAbility.CREATE.equals(normalizedAction))
            || (RatioAbility.KEY.equals(normalizedKey)
            && RatioAbility.RATIO_EFFECT.equals(normalizedAction))
            || (NewShadowStyleAbility.KEY.equals(normalizedKey)
            && NewShadowStyleAbility.ACTIVATE_SIMPLE_DOMAIN.equals(normalizedAction));
    }

    public static boolean supportsEffect(
        String key,
        String action,
        String target,
        Integer stackCount
    ) {
        String normalizedKey = normalize(key);
        String normalizedTarget = normalize(target);
        if (!supportsEffectAction(normalizedKey, action)) return false;
        if (RatioAbility.KEY.equals(normalizedKey)) {
            return RatioAbility.supportsTarget(normalizedTarget, stackCount);
        }
        if (NewShadowStyleAbility.KEY.equals(normalizedKey)) {
            return NewShadowStyleAbility.supportsTarget(normalizedTarget, stackCount);
        }
        return normalizedTarget.isEmpty() && stackCount == null;
    }

    public static List<EffectAction> effectActions() {
        return List.of(
            new EffectAction(MiraclesAbility.KEY, MiraclesAbility.CREATE, "Create Miracle"),
            new EffectAction(RatioAbility.KEY, RatioAbility.RATIO_EFFECT, "Ratio Effect"),
            new EffectAction(NewShadowStyleAbility.KEY,
                NewShadowStyleAbility.ACTIVATE_SIMPLE_DOMAIN, "Activate Simple Domain")
        );
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
