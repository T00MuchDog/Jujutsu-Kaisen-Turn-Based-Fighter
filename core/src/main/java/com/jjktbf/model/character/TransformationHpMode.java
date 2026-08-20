package com.jjktbf.model.character;

/** Defines how current HP is mapped when a combatant assumes another form. */
public enum TransformationHpMode {
    FULL("New form's starting HP"),
    CURRENT_VALUE("Current HP value"),
    CURRENT_PERCENTAGE("Current HP percentage");

    private final String displayName;

    TransformationHpMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static TransformationHpMode fromName(String value) {
        if (value == null || value.isBlank()) return FULL;
        return valueOf(value.trim().toUpperCase());
    }
}
