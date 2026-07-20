package com.jjktbf.model.character.coded;

import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.move.Move;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Runtime implementation for the Miracles cursed technique. */
public final class MiraclesAbility implements CodedAbilityRuntime {

    public static final String KEY = "MIRACLES";
    public static final String RESERVOIR = "RESERVOIR";
    public static final String FATEFUL_REPRIEVE = "FATEFUL_REPRIEVE";
    public static final String FORTUNE_RECLAIMED = "FORTUNE_RECLAIMED";
    public static final String CREATE = "CREATE";
    public static final int MAX_MIRACLES = 6;

    private final BattleCombatant owner;
    private final Set<String> features;
    private int miracles;
    private int pendingFatalAversions;

    MiraclesAbility(BattleCombatant owner, Set<String> features) {
        this.owner = owner;
        this.features = Set.copyOf(features);
    }

    @Override
    public List<CombatEvent> onTrigger(BattleState state, AbilityTrigger trigger) {
        if (trigger.type() == AbilityTrigger.Type.BATTLE_START && hasFeature(RESERVOIR)) {
            miracles = MAX_MIRACLES;
            return List.of(event(trigger.tick(), owner.getCharacter().getName()
                + " enters battle with " + miracles + " Miracles stored."));
        }
        if (!hasFeature(FORTUNE_RECLAIMED) || !gainsFrom(trigger)) return List.of();
        if (!addMiracle()) return List.of();
        return List.of(event(trigger.tick(), owner.getCharacter().getName()
            + " reclaims a Miracle (" + miracles + "/" + MAX_MIRACLES + ")."));
    }

    @Override
    public List<CombatEvent> onMoveUnleashed(BattleState state, Move move, int tick) {
        if (!hasFeature(RESERVOIR)
            || !KEY.equalsIgnoreCase(move.getCodedAbilityKey())
            || !CREATE.equalsIgnoreCase(move.getCodedAction())
            || !addMiracle()) {
            return List.of();
        }
        return List.of(CombatEvent.of(CombatEvent.Type.ABILITY_ACTIVATED)
            .source(owner).target(owner).move(move).tick(tick)
            .message(owner.getCharacter().getName() + " creates a Miracle ("
                + miracles + "/" + MAX_MIRACLES + ").")
            .build());
    }

    @Override
    public boolean preventFatalDamage() {
        if (!hasFeature(FATEFUL_REPRIEVE) || miracles <= 0) return false;
        miracles--;
        pendingFatalAversions++;
        return true;
    }

    @Override
    public List<CombatEvent> drainPendingEvents(int tick) {
        if (pendingFatalAversions == 0) return List.of();
        List<CombatEvent> events = new ArrayList<>();
        int aversions = pendingFatalAversions;
        pendingFatalAversions = 0;
        for (int index = 0; index < aversions; index++) {
            events.add(event(tick, "A stored Miracle averts a fatal blow for "
                + owner.getCharacter().getName() + " (" + miracles + "/" + MAX_MIRACLES + ")."));
        }
        return events;
    }

    @Override
    public CodedAbilityState state() {
        return new CodedAbilityState(KEY, "Miracles", miracles, MAX_MIRACLES);
    }

    public static boolean supportsFeature(String feature) {
        return RESERVOIR.equals(feature)
            || FATEFUL_REPRIEVE.equals(feature)
            || FORTUNE_RECLAIMED.equals(feature);
    }

    private boolean gainsFrom(AbilityTrigger trigger) {
        return (trigger.type() == AbilityTrigger.Type.ATTACK_MISSED
            && trigger.target() == owner && trigger.actor() != owner)
            || (trigger.type() == AbilityTrigger.Type.BLACK_FLASH && trigger.actor() == owner);
    }

    private boolean hasFeature(String feature) {
        return features.contains(feature);
    }

    private boolean addMiracle() {
        if (miracles >= MAX_MIRACLES) return false;
        miracles++;
        return true;
    }

    private CombatEvent event(int tick, String message) {
        return CombatEvent.of(CombatEvent.Type.ABILITY_ACTIVATED)
            .source(owner).target(owner).tick(tick).message(message).build();
    }
}
