package com.jjktbf.model.character.coded;

import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Marker features for the foundational Ten Shadows passives and its two fusion
 * types: Totality (a destroyed shikigami's essence permanently absorbed into a
 * survivor) and Well's Unknown Abyss (a temporary fusion of two living
 * shikigami).
 *
 * <p>Owns the technique's signature restriction: a shikigami destroyed in battle
 * is gone for the rest of that battle — it cannot be resummoned and its summon
 * move stays greyed out. The recording happens here (in the technique runtime),
 * triggered by the {@link #onOwnedSummonDestroyed} lifecycle hook, rather than
 * being keyed on the unrelated {@code maxActiveSummons} cap.
 */
public final class TenShadowsAbility implements CodedAbilityRuntime {

    public static final String KEY = "TEN_SHADOWS";
    public static final String TECHNIQUE = "TECHNIQUE";
    public static final String TOTALITY = "TOTALITY";
    public static final String WELLS_UNKNOWN_ABYSS = "WELLS_UNKNOWN_ABYSS";

    private final Set<String> features;

    public TenShadowsAbility(Set<String> features) {
        this.features = features == null ? Set.of() : Set.copyOf(features);
    }

    public static boolean supportsFeature(String feature) {
        return TECHNIQUE.equals(feature)
            || TOTALITY.equals(feature)
            || WELLS_UNKNOWN_ABYSS.equals(feature);
    }

    @Override
    public List<CombatEvent> onTrigger(
        BattleState state,
        AbilityTrigger trigger,
        Predicate<String> featureActive
    ) {
        return List.of();
    }

    /**
     * When one of this summoner's shikigami is destroyed, mark its definition
     * permanently destroyed for the battle. Summoning the same shikigami again
     * is then blocked (and its move greyed out) for the rest of the fight. Only
     * the foundational technique feature enforces this — fusion features alone
     * do not.
     */
    @Override
    public void onOwnedSummonDestroyed(
        BattleState state,
        BattleCombatant owner,
        BattleCombatant destroyedSummon,
        Predicate<String> featureActive
    ) {
        if (!featureActive.test(TECHNIQUE)) return;
        if (state == null || owner == null || destroyedSummon == null) return;
        if (destroyedSummon.getOriginCharacter() == null) return;
        state.recordSummonDestroyed(owner, destroyedSummon.getOriginCharacter().getId());
    }

    @Override
    public List<CombatEvent> drainPendingEvents(int tick) {
        return List.of();
    }

    @Override
    public CodedAbilityState state() {
        return new CodedAbilityState(KEY, "Ten Shadows", features.size(), features.size());
    }
}
