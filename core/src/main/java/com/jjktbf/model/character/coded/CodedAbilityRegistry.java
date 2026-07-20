package com.jjktbf.model.character.coded;

import com.jjktbf.model.character.Ability;
import com.jjktbf.model.combat.BattleCombatant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Allow-list of compiled ability implementations and their data-defined features. */
public final class CodedAbilityRegistry {

    private CodedAbilityRegistry() {
    }

    public static CodedAbilities create(BattleCombatant owner, List<Ability> abilities) {
        Map<String, Set<String>> featuresByKey = new LinkedHashMap<>();
        for (Ability ability : abilities == null ? List.<Ability>of() : abilities) {
            if (ability == null || !ability.isCoded()) continue;
            String key = normalize(ability.getCodedAbilityKey());
            String feature = normalize(ability.getCodedFeature());
            if (!supportsAbility(key, feature)) {
                System.err.println("[WARN] Unknown coded ability binding: " + key + "/" + feature);
                continue;
            }
            featuresByKey.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(feature);
        }

        List<CodedAbilityRuntime> runtimes = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : featuresByKey.entrySet()) {
            if (MiraclesAbility.KEY.equals(entry.getKey())) {
                runtimes.add(new MiraclesAbility(owner, entry.getValue()));
            }
        }
        return new CodedAbilities(runtimes);
    }

    public static boolean supportsAbility(String key, String feature) {
        String normalizedKey = normalize(key);
        String normalizedFeature = normalize(feature);
        if (normalizedKey.isEmpty() && normalizedFeature.isEmpty()) return true;
        return MiraclesAbility.KEY.equals(normalizedKey)
            && MiraclesAbility.supportsFeature(normalizedFeature);
    }

    public static boolean supportsMoveAction(String key, String action) {
        String normalizedKey = normalize(key);
        String normalizedAction = normalize(action);
        if (normalizedKey.isEmpty() && normalizedAction.isEmpty()) return true;
        return MiraclesAbility.KEY.equals(normalizedKey)
            && MiraclesAbility.CREATE.equals(normalizedAction);
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
