package com.jjktbf.model.character;

/**
 * A passive ability or trait a character possesses.
 *
 * Currently a stub — stores only a name and description.
 * Mechanical effects (stat modifiers, trigger conditions, combat interactions)
 * will be added in a future pass when the ability system is designed.
 *
 * Abilities are displayed on the character sheet but have no in-combat
 * mechanical effect in this version.
 */
public class Ability {

    private final String name;
    private final String description;

    public Ability(String name, String description) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Ability name required");
        this.name        = name;
        this.description = description != null ? description : "";
    }

    public String getName()        { return name; }
    public String getDescription() { return description; }

    @Override
    public String toString() {
        return name + (description.isBlank() ? "" : ": " + description);
    }
}
