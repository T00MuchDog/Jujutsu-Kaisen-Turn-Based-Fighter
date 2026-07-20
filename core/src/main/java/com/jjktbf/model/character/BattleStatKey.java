package com.jjktbf.model.character;

/** Derived or battle-only values which can be modified by a runtime ability effect. */
public enum BattleStatKey {
    MAX_HP("Max HP"),
    MAX_CE("Max CE"),
    MAX_AP("Max AP"),
    ACCURACY("Accuracy"),
    EVASION("Evasion"),
    DEFENSE("Defense"),
    DAMAGE_DEALT("Damage dealt"),
    DAMAGE_TAKEN("Damage taken"),
    CE_COST("CE costs"),
    BLACK_FLASH_CHANCE("Black Flash chance"),
    HEALING("HP healing"),
    CE_RESTORATION("CE restoration");

    public final String label;

    BattleStatKey(String label) {
        this.label = label;
    }

    public static BattleStatKey fromString(String value) {
        if (value == null) throw new IllegalArgumentException("Battle stat is required.");
        String normalized = value.trim().toUpperCase().replace(' ', '_');
        return valueOf(normalized);
    }
}
