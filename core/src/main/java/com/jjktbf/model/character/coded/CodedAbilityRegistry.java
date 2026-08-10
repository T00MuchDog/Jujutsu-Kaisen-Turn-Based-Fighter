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
    public enum ParameterUnit { INTEGER, PERCENT }
    public record CodedParameter(
        String key,
        String label,
        ParameterUnit unit,
        int minimum,
        int maximum,
        int defaultValue
    ) { }

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
                effects.addAll(move.getOnBlockEffects());
                effects.addAll(move.getOnParryEffects());
                effects.addAll(move.getOnDodgeEffects());
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
                runtime = new MiraclesAbility(
                    owner, entry.getValue(), bindingsByKey.getOrDefault(entry.getKey(), Map.of()));
            } else if (RatioAbility.KEY.equals(entry.getKey())) {
                runtime = new RatioAbility(
                    owner, entry.getValue(), bindingsByKey.getOrDefault(entry.getKey(), Map.of()));
            } else if (NewShadowStyleAbility.KEY.equals(entry.getKey())) {
                runtime = new NewShadowStyleAbility(owner, entry.getValue());
            } else if (ShikigamiMoveRuntime.KEY.equals(entry.getKey())) {
                runtime = new ShikigamiMoveRuntime();
            } else if (TenShadowsAbility.KEY.equals(entry.getKey())) {
                runtime = new TenShadowsAbility(entry.getValue());
            } else if (CursedSpeechAbility.KEY.equals(entry.getKey())) {
                runtime = new CursedSpeechAbility(
                    owner, entry.getValue(), bindingsByKey.getOrDefault(entry.getKey(), Map.of()));
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
            && NewShadowStyleAbility.supportsFeature(normalizedFeature))
            || (TenShadowsAbility.KEY.equals(normalizedKey)
            && TenShadowsAbility.supportsFeature(normalizedFeature))
            || (CursedSpeechAbility.KEY.equals(normalizedKey)
            && CursedSpeechAbility.supportsFeature(normalizedFeature));
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
                "Simple Domain Binding Vow"),
            new AbilityFeature(TenShadowsAbility.KEY,
                TenShadowsAbility.TECHNIQUE, "Ten Shadows Technique"),
            new AbilityFeature(TenShadowsAbility.KEY,
                TenShadowsAbility.TOTALITY, "Totality"),
            new AbilityFeature(CursedSpeechAbility.KEY,
                CursedSpeechAbility.TECHNIQUE, "Cursed Speech"),
            new AbilityFeature(CursedSpeechAbility.KEY,
                CursedSpeechAbility.REFINED_COMMANDS, "Refined Commands")
        );
    }

    public static List<StateKey> stateKeys() {
        return List.of(
            new StateKey(MiraclesAbility.KEY, "Miracles"),
            new StateKey(RatioAbility.KEY, "Ratio stacks"),
            new StateKey(NewShadowStyleAbility.KEY, "Simple Domain")
        );
    }

    public static List<CodedParameter> abilityParameters(String key, String feature) {
        String normalizedKey = normalize(key);
        String normalizedFeature = normalize(feature);
        if (MiraclesAbility.KEY.equals(normalizedKey)) {
            return switch (normalizedFeature) {
                case MiraclesAbility.RESERVOIR -> List.of(
                    integer(MiraclesAbility.CAPACITY, "Miracle capacity", 1, 99,
                        MiraclesAbility.MAX_MIRACLES),
                    integer(MiraclesAbility.STARTING_AMOUNT, "Starting Miracles", 0, 99,
                        MiraclesAbility.MAX_MIRACLES));
                case MiraclesAbility.FATEFUL_REPRIEVE -> List.of(
                    integer(MiraclesAbility.COST, "Miracles spent", 1, 99, 1));
                case MiraclesAbility.FORTUNE_RECLAIMED -> List.of(
                    integer(MiraclesAbility.GAIN, "Miracles gained", 1, 99, 1));
                default -> List.of();
            };
        }
        if (RatioAbility.KEY.equals(normalizedKey)
            && RatioAbility.REINFORCEMENT_RATIO.equals(normalizedFeature)) {
            return List.of(
                integer(RatioAbility.STACK_CAPACITY, "Ratio stack capacity", 1, 99,
                    RatioAbility.MAX_STACKS),
                percent(RatioAbility.DEFENSE_PERCENT, "Defense remaining %", 1, 100, 30));
        }
        if (CursedSpeechAbility.KEY.equals(normalizedKey)
            && CursedSpeechAbility.REFINED_COMMANDS.equals(normalizedFeature)) {
            return List.of(percent(
                CursedSpeechAbility.SUCCESS_BONUS_PERCENT,
                "Command success bonus %", 0, 100, 10));
        }
        return List.of();
    }

    public static List<CodedParameter> effectParameters(
        String key,
        String action,
        String target
    ) {
        String normalizedKey = normalize(key);
        String normalizedAction = normalize(action);
        String normalizedTarget = normalize(target);
        if (MiraclesAbility.KEY.equals(normalizedKey)
            && MiraclesAbility.CREATE.equals(normalizedAction)) {
            return List.of(integer(MiraclesAbility.GAIN, "Miracles gained", 1, 99, 1));
        }
        if (RatioAbility.KEY.equals(normalizedKey)
            && RatioAbility.RATIO_EFFECT.equals(normalizedAction)) {
            if (RatioAbility.CREATE_STACKS.equals(normalizedTarget)) {
                return List.of(
                    integer(RatioAbility.STACK_DURATION_PARAMETER, "Stack duration ticks", 1, 300,
                        RatioAbility.STACK_DURATION_TICKS),
                    percent(RatioAbility.TRIGGER_CHANCE_PERCENT, "Stack trigger chance %", 0, 100,
                        (int) Math.round(RatioAbility.STACK_TRIGGER_CHANCE * 100)),
                    percent(RatioAbility.DEFENSE_PERCENT, "Defense remaining %", 1, 100, 30));
            }
            if (RatioAbility.APPLY_TO_MOVE.equals(normalizedTarget)) {
                return List.of(
                    percent(RatioAbility.TRIGGER_CHANCE_PERCENT, "Ratio chance %", 0, 100, 100),
                    percent(RatioAbility.DEFENSE_PERCENT, "Defense remaining %", 1, 100, 30));
            }
        }
        if (CursedSpeechAbility.KEY.equals(normalizedKey)
            && CursedSpeechAbility.COMMAND.equals(normalizedAction)) {
            return List.of(
                percent(CursedSpeechAbility.BASE_CHANCE_PERCENT,
                    "Base command chance %", 0, 100, 50),
                integer(CursedSpeechAbility.BASE_RECOIL,
                    "Base recoil", 0, 9999, 1));
        }
        return List.of();
    }

    public static void prepareAbilityParameters(AbilityEffectData effect) {
        if (effect == null) return;
        effect.codedParameters = prepareParameters(
            effect.codedParameters, abilityParameters(effect.codedAbilityKey, effect.codedFeature));
    }

    public static Map<String, Integer> prepareEffectParameters(
        Map<String, Integer> values,
        String key,
        String action,
        String target
    ) {
        return prepareParameters(values, effectParameters(key, action, target));
    }

    public static String abilityParameterValidationError(AbilityEffectData effect) {
        return parameterValidationError(
            effect == null ? null : effect.codedParameters,
            effect == null ? List.of()
                : abilityParameters(effect.codedAbilityKey, effect.codedFeature));
    }

    public static String effectParameterValidationError(
        String key,
        String action,
        String target,
        Map<String, Integer> values
    ) {
        return parameterValidationError(values, effectParameters(key, action, target));
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
            && NewShadowStyleAbility.ACTIVATE_SIMPLE_DOMAIN.equals(normalizedAction))
            || (ShikigamiMoveRuntime.KEY.equals(normalizedKey)
            && ShikigamiMoveRuntime.DESUMMON_SELF.equals(normalizedAction))
            || (CursedSpeechAbility.KEY.equals(normalizedKey)
            && CursedSpeechAbility.COMMAND.equals(normalizedAction));
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
        if (CursedSpeechAbility.KEY.equals(normalizedKey)) {
            return CursedSpeechAbility.supportsTarget(normalizedTarget, stackCount);
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
                NewShadowStyleAbility.ACTIVATE_SIMPLE_DOMAIN, "Activate Simple Domain"),
            new EffectAction(ShikigamiMoveRuntime.KEY,
                ShikigamiMoveRuntime.DESUMMON_SELF, "Desummon self"),
            new EffectAction(CursedSpeechAbility.KEY,
                CursedSpeechAbility.COMMAND, "Cursed Speech command")
        );
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static CodedParameter integer(
        String key, String label, int minimum, int maximum, int defaultValue
    ) {
        return new CodedParameter(
            key, label, ParameterUnit.INTEGER, minimum, maximum, defaultValue);
    }

    private static CodedParameter percent(
        String key, String label, int minimum, int maximum, int defaultValue
    ) {
        return new CodedParameter(
            key, label, ParameterUnit.PERCENT, minimum, maximum, defaultValue);
    }

    private static Map<String, Integer> prepareParameters(
        Map<String, Integer> values,
        List<CodedParameter> definitions
    ) {
        if (definitions.isEmpty()) return null;
        Map<String, Integer> prepared = new LinkedHashMap<>();
        for (CodedParameter definition : definitions) {
            prepared.put(definition.key(), values == null
                ? definition.defaultValue()
                : values.getOrDefault(definition.key(), definition.defaultValue()));
        }
        return prepared;
    }

    private static String parameterValidationError(
        Map<String, Integer> values,
        List<CodedParameter> definitions
    ) {
        Set<String> allowed = definitions.stream()
            .map(CodedParameter::key)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (values != null) {
            for (String key : values.keySet()) {
                if (!allowed.contains(key)) return "Unsupported coded parameter: " + key;
            }
        }
        for (CodedParameter definition : definitions) {
            int value = values == null
                ? definition.defaultValue()
                : values.getOrDefault(definition.key(), definition.defaultValue());
            if (value < definition.minimum() || value > definition.maximum()) {
                return definition.label() + " must be between "
                    + definition.minimum() + " and " + definition.maximum() + ".";
            }
        }
        return null;
    }
}
