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
    private final List<Integer> pendingFatalAversions = new ArrayList<>();

    MiraclesAbility(BattleCombatant owner, Set<String> features) {
        this.owner = owner;
        this.features = Set.copyOf(features);
    }

    @Override
    public List<CombatEvent> onTrigger(BattleState state, AbilityTrigger trigger) {
        if (trigger.type() == AbilityTrigger.Type.BATTLE_START && hasFeature(RESERVOIR)) {
            miracles = MAX_MIRACLES;
            return List.of(event(trigger.tick(), gainMessage(MAX_MIRACLES)));
        }
        if (!hasFeature(FORTUNE_RECLAIMED) || !gainsFrom(trigger)) return List.of();
        if (!addMiracle()) return List.of();
        return List.of(event(trigger.tick(), gainMessage(1)));
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
            .codedAbilityState(state())
            .message(owner.getCharacter().getName() + " gains 1 Miracle through Miracle Creation ("
                + remainingText(miracles) + ").")
            .build());
    }

    @Override
    public boolean preventFatalDamage() {
        if (!hasFeature(FATEFUL_REPRIEVE) || miracles <= 0) return false;
        miracles--;
        pendingFatalAversions.add(miracles);
        return true;
    }

    @Override
    public List<CombatEvent> drainPendingEvents(int tick) {
        if (pendingFatalAversions.isEmpty()) return List.of();
        List<CombatEvent> events = new ArrayList<>();
        for (int remaining : pendingFatalAversions) {
            events.add(event(tick, owner.getCharacter().getName()
                + " uses 1 Miracle to avert a fatal blow (" + remainingText(remaining) + ").", remaining));
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

    private String gainMessage(int gained) {
        return owner.getCharacter().getName() + " gains " + gained + " Miracle"
            + (gained == 1 ? "" : "s") + " (" + remainingText(miracles) + ").";
    }

    private static String remainingText(int remaining) {
        return remaining + "/" + MAX_MIRACLES + " remaining";
    }

    private static CodedAbilityState miracleState(int value) {
        return new CodedAbilityState(KEY, "Miracles", value, MAX_MIRACLES);
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
}
