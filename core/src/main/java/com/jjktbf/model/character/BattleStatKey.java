package com.jjktbf.model.character;

/** Derived or battle-only values which can be modified by a runtime ability effect. */
public enum BattleStatKey {
    MAX_HP("Max HP"),
    MAX_CE("Max CE"),
    MAX_AP("Max AP"),
    ACCURACY("Accuracy"),
    EVASION("Evasion"),
    POWER("Power"),
    DEFENSE("Defense"),
    DAMAGE_DEALT("Damage dealt"),
    DAMAGE_TAKEN("Damage taken"),
    CE_COST("CE costs"),
    BLACK_FLASH_CHANCE("Black Flash chance"),
    HEALING("HP healing"),
    CE_RESTORATION("CE restoration"),
    CE_REGENERATION("Cursed energy regeneration per tick");

    public final String label;

    BattleStatKey(String label) {
        this.label = label;
    }

    /** Whether this value is represented as a probability in the range [0, 1]. */
    public boolean isProbability() {
        return this == BLACK_FLASH_CHANCE;
    }

    public static BattleStatKey fromString(String value) {
        if (value == null) throw new IllegalArgumentException("Battle stat is required.");
        String normalized = value.trim().toUpperCase().replace(' ', '_');
        return valueOf(normalized);
    }
}
