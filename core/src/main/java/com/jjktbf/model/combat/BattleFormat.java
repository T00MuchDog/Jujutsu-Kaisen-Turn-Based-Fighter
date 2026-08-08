package com.jjktbf.model.combat;

/**
 * Authoritative roster-size format for a battle.
 *
 * <p>The multi-fighter engine ({@link BattleState}, {@link BattleTeam},
 * {@code BattleController.runTeamBattle}) supports any number of fighters per
 * team; this enum captures the <em>configured</em> format for a specific match
 * so setup flows (local character select, and later the multiplayer
 * challenge/match chain) agree on how many fighters each side fields.
 *
 * <ul>
 *   <li>{@link #ONE_V_ONE} — one fighter per side (the legacy default).</li>
 *   <li>{@link #TWO_V_TWO} — two fighters per side.</li>
 * </ul>
 */
public enum BattleFormat {
    /** One fighter per side. */
    ONE_V_ONE(1),
    /** Two fighters per side. */
    TWO_V_TWO(2);

    private final int fightersPerSide;

    BattleFormat(int fightersPerSide) {
        this.fightersPerSide = fightersPerSide;
    }

    /** Number of fighters each side fields in this format. */
    public int fightersPerSide() {
        return fightersPerSide;
    }
}
