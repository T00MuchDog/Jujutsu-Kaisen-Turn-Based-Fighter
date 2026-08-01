package com.jjktbf.model.character.coded;

import com.jjktbf.model.combat.CombatEvent;

import java.util.ArrayList;
import java.util.List;

/** Hit-local changes supplied by compiled abilities after accuracy succeeds. */
public record CodedHitModifiers(
    boolean bypassBlock,
    double defenseMultiplier,
    List<CombatEvent> events
) {

    private static final CodedHitModifiers NONE = new CodedHitModifiers(false, 1.0, List.of());

    public CodedHitModifiers {
        if (!Double.isFinite(defenseMultiplier) || defenseMultiplier <= 0) {
            throw new IllegalArgumentException("Defense multiplier must be positive and finite");
        }
        events = events == null ? List.of() : List.copyOf(events);
    }

    public static CodedHitModifiers none() {
        return NONE;
    }

    public CodedHitModifiers combine(CodedHitModifiers other) {
        if (other == null || other == NONE) return this;
        if (this == NONE) return other;
        List<CombatEvent> combinedEvents = new ArrayList<>(events);
        combinedEvents.addAll(other.events);
        return new CodedHitModifiers(
            bypassBlock || other.bypassBlock,
            Math.min(defenseMultiplier, other.defenseMultiplier),
            combinedEvents
        );
    }
}
