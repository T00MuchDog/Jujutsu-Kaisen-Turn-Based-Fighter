package com.jjktbf.model.combat;

import java.util.Objects;

/** Independent roster-size and runtime-stat choices for a battle. */
public record BattleConfiguration(BattleFormat format, BattleStatMode statMode) {
    public BattleConfiguration {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(statMode, "statMode");
    }

    public static BattleConfiguration standard(BattleFormat format) {
        return new BattleConfiguration(format, BattleStatMode.STANDARD);
    }
}
