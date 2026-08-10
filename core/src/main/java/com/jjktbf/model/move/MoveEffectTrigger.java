package com.jjktbf.model.move;

/** Runtime moment that activates an effect attached to a move. */
public enum MoveEffectTrigger {
    ON_FIRE("On move fire"),
    ON_HIT("On hit"),
    ON_BLOCK("On block"),
    ON_PARRY("On parry"),
    ON_DODGE("On dodge");

    private final String displayName;

    MoveEffectTrigger(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static MoveEffectTrigger fromName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Move effect trigger is required.");
        }
        return valueOf(name.trim().toUpperCase());
    }
}
