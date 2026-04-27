package com.jjktbf.model.character;

import java.util.List;

/**
 * Applies passive ability effects to produce modified character stats and
 * a set of non-stat flags for use by BattleCombatant.
 *
 * Called once at BattleCombatant construction.
 *
 * Two outputs:
 *   1. A modified CharacterStats (using the unclamped constructor so abilities
 *      can push stats to 0 or beyond normal game bounds).
 *   2. An AbilityFlags object for all non-stat runtime effects.
 *
 * Processing order per stat:
 *   STAT_SET_MIN (→ 0) / STAT_SET_VALUE → overrides
 *   STAT_ADD                             → additive
 *   STAT_MULTIPLY / STAT_DIVIDE          → multiplicative (product of all)
 *
 * Active / triggered effects are NOT applied here — they are registered
 * as event listeners by BattleState.
 *
 * STAT_BONUS_POINTS is editor/creator-only and is silently ignored at runtime.
 */
public final class AbilityApplicator {

    private AbilityApplicator() {}

    public static ApplicationResult apply(CharacterStats baseStats, List<Ability> abilities) {

        // Start from base values (can go to 0 via STAT_SET_MIN)
        int vit  = baseStats.getVitality();
        int str  = baseStats.getStrength();
        int dur  = baseStats.getDurability();
        int spd  = baseStats.getSpeed();
        int ceR  = baseStats.getCursedEnergyReserves();
        int ceEf = baseStats.getCursedEnergyEfficiency();
        int ceO  = baseStats.getCursedEnergyOutput();
        int js   = baseStats.getJujutsuSkill();
        int ca   = baseStats.getCombatAbility();
        int ctm  = baseStats.getCursedTechniqueMastery();

        // Multipliers (product of all STAT_MULTIPLY / divided by STAT_DIVIDE)
        double vitM=1, strM=1, durM=1, spdM=1, ceRM=1, ceEfM=1, ceOM=1, jsM=1, caM=1, ctmM=1;

        AbilityFlags flags = new AbilityFlags();

        for (Ability ability : abilities) {
            for (AbilityEffectData eff : ability.getEffects()) {
                AbilityEffectType type;
                try {
                    type = AbilityEffectType.valueOf(eff.type);
                } catch (IllegalArgumentException e) {
                    System.err.println("[WARN] Unknown ability effect type: " + eff.type);
                    continue;
                }

                switch (type) {

                    // ── Override (set) — applied before add/multiply ─────────
                    case STAT_SET_MIN -> {
                        switch (norm(eff.stat)) {
                            case "vitality"               -> vit  = 0;
                            case "strength"               -> str  = 0;
                            case "durability"             -> dur  = 0;
                            case "speed"                  -> spd  = 0;
                            case "cursedenergyreserves"   -> ceR  = 0;
                            case "cursedenergyefficiency" -> ceEf = 0;
                            case "cursedenergyoutput"     -> ceO  = 0;
                            case "jujutsuskill"           -> js   = 0;
                            case "combatability"          -> ca   = 0;
                            case "cursedtechniquemastery" -> ctm  = 0;
                            default -> warn(eff.stat);
                        }
                    }
                    case STAT_SET_VALUE -> {
                        int v = eff.intValue != null ? eff.intValue : 0;
                        switch (norm(eff.stat)) {
                            case "vitality"               -> vit  = v;
                            case "strength"               -> str  = v;
                            case "durability"             -> dur  = v;
                            case "speed"                  -> spd  = v;
                            case "cursedenergyreserves"   -> ceR  = v;
                            case "cursedenergyefficiency" -> ceEf = v;
                            case "cursedenergyoutput"     -> ceO  = v;
                            case "jujutsuskill"           -> js   = v;
                            case "combatability"          -> ca   = v;
                            case "cursedtechniquemastery" -> ctm  = v;
                            default -> warn(eff.stat);
                        }
                    }

                    // ── Additive ─────────────────────────────────────────────
                    case STAT_ADD -> {
                        int a = eff.intValue != null ? eff.intValue : 0;
                        switch (norm(eff.stat)) {
                            case "vitality"               -> vit  += a;
                            case "strength"               -> str  += a;
                            case "durability"             -> dur  += a;
                            case "speed"                  -> spd  += a;
                            case "cursedenergyreserves"   -> ceR  += a;
                            case "cursedenergyefficiency" -> ceEf += a;
                            case "cursedenergyoutput"     -> ceO  += a;
                            case "jujutsuskill"           -> js   += a;
                            case "combatability"          -> ca   += a;
                            case "cursedtechniquemastery" -> ctm  += a;
                            default -> warn(eff.stat);
                        }
                    }

                    // ── Multiplicative ───────────────────────────────────────
                    case STAT_MULTIPLY -> {
                        double f = eff.doubleValue != null ? eff.doubleValue : 1.0;
                        switch (norm(eff.stat)) {
                            case "vitality"               -> vitM  *= f;
                            case "strength"               -> strM  *= f;
                            case "durability"             -> durM  *= f;
                            case "speed"                  -> spdM  *= f;
                            case "cursedenergyreserves"   -> ceRM  *= f;
                            case "cursedenergyefficiency" -> ceEfM *= f;
                            case "cursedenergyoutput"     -> ceOM  *= f;
                            case "jujutsuskill"           -> jsM   *= f;
                            case "combatability"          -> caM   *= f;
                            case "cursedtechniquemastery" -> ctmM  *= f;
                            default -> warn(eff.stat);
                        }
                    }
                    case STAT_DIVIDE -> {
                        double d = eff.doubleValue != null && eff.doubleValue != 0 ? eff.doubleValue : 1.0;
                        switch (norm(eff.stat)) {
                            case "vitality"               -> vitM  /= d;
                            case "strength"               -> strM  /= d;
                            case "durability"             -> durM  /= d;
                            case "speed"                  -> spdM  /= d;
                            case "cursedenergyreserves"   -> ceRM  /= d;
                            case "cursedenergyefficiency" -> ceEfM /= d;
                            case "cursedenergyoutput"     -> ceOM  /= d;
                            case "jujutsuskill"           -> jsM   /= d;
                            case "combatability"          -> caM   /= d;
                            case "cursedtechniquemastery" -> ctmM  /= d;
                            default -> warn(eff.stat);
                        }
                    }

                    // ── Non-stat → flags ─────────────────────────────────────
                    case CE_COST_TO_MINIMUM      -> flags.ceCostToMinimum = true;
                    case CE_COST_MULTIPLY        -> flags.ceCostMultiplier *= nvl(eff.doubleValue, 1.0);

                    case MOVE_ACCURACY_ADD       -> flags.accuracyBonus   += nvl(eff.intValue, 0);
                    case MOVE_ACCURACY_MULTIPLY  -> flags.accuracyMultiplier *= nvl(eff.doubleValue, 1.0);

                    case OPPONENT_ACCURACY_ADD   -> flags.opponentAccuracyBonus += nvl(eff.intValue, 0);
                    case OPPONENT_ACCURACY_MULTIPLY -> flags.opponentAccuracyMultiplier *= nvl(eff.doubleValue, 1.0);

                    case DAMAGE_MULTIPLY         -> flags.damageMultiplier *= nvl(eff.doubleValue, 1.0);
                    case BF_CHANCE_ADD           -> flags.bfChanceBonus   += nvl(eff.doubleValue, 0.0);
                    case MODIFY_DEFENSE          -> flags.defenseMultiplier *= nvl(eff.doubleValue, 1.0);
                    case MODIFY_AP_BAR           -> flags.apBarBonus       += nvl(eff.intValue, 0);
                    case COST_CE_PER_ROUND       -> flags.ceCostPerRound   += nvl(eff.intValue, 0);

                    case GRANT_MOVE -> {
                        if (eff.moveId != null) flags.grantedMoveIds.add(eff.moveId);
                    }
                    case LOCK_MOVE_TAG -> {
                        if (eff.moveTag != null) flags.lockedMoveTags.add(eff.moveTag);
                    }
                    case AUTO_STATUS_APPLY -> flags.autoStatusEffects.add(eff);

                    case UNLOCK_TECHNIQUE  -> { /* handled at CharacterData.toCharacter() */ }
                    case STAT_BONUS_POINTS -> { /* editor/creator-only — no runtime effect */ }
                }
            }
        }

        // Apply multipliers — use unclamped constructor so 0 is valid
        CharacterStats modified = new CharacterStats(
            noNeg((int) Math.round(vit  * vitM)),
            noNeg((int) Math.round(str  * strM)),
            noNeg((int) Math.round(dur  * durM)),
            noNeg((int) Math.round(spd  * spdM)),
            noNeg((int) Math.round(ceR  * ceRM)),
            noNeg((int) Math.round(ceEf * ceEfM)),
            noNeg((int) Math.round(ceO  * ceOM)),
            noNeg((int) Math.round(js   * jsM)),
            noNeg((int) Math.round(ca   * caM)),
            noNeg((int) Math.round(ctm  * ctmM))
        );

        return new ApplicationResult(modified, flags);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String norm(String s) {
        return s != null ? s.toLowerCase().replace("_","").replace(" ","") : "";
    }
    private static void warn(String stat) {
        System.err.println("[WARN] AbilityApplicator: unknown stat '" + stat + "'");
    }
    private static int    nvl(Integer v, int    def) { return v != null ? v : def; }
    private static double nvl(Double  v, double def) { return v != null ? v : def; }
    private static int    noNeg(int v)               { return Math.max(0, v); }

    // =========================================================================
    // Result types
    // =========================================================================

    public static class ApplicationResult {
        public final CharacterStats modifiedStats;
        public final AbilityFlags   flags;
        public ApplicationResult(CharacterStats modifiedStats, AbilityFlags flags) {
            this.modifiedStats = modifiedStats;
            this.flags         = flags;
        }
    }

    /** All non-stat ability effects tracked at runtime by BattleCombatant. */
    public static class AbilityFlags {

        // CE
        public boolean ceCostToMinimum   = false;
        public double  ceCostMultiplier   = 1.0;
        public int     ceCostPerRound     = 0;

        // Own accuracy
        public int     accuracyBonus      = 0;
        public double  accuracyMultiplier = 1.0;

        // Opponent accuracy modifiers (applied to the opponent's hit chance)
        public int     opponentAccuracyBonus      = 0;
        public double  opponentAccuracyMultiplier = 1.0;

        // Damage / defense
        public double  damageMultiplier   = 1.0;
        public double  defenseMultiplier  = 1.0;

        // Black Flash
        public double  bfChanceBonus      = 0.0;

        // AP bar
        public int     apBarBonus         = 0;

        // Moves
        public final java.util.List<String>            grantedMoveIds    = new java.util.ArrayList<>();

        /**
         * Move tags that are locked by this character's abilities.
         * PASSIVE: blocks the tag in the character creator move assignment and
         *          prevents queuing those moves during combat planning.
         * ACTIVE/TRIGGERED: the engine removes those blocks from the opponent's
         *          timeline when the ability fires.
         */
        public final java.util.List<String>            lockedMoveTags    = new java.util.ArrayList<>();

        // Status automation
        public final java.util.List<AbilityEffectData> autoStatusEffects = new java.util.ArrayList<>();

        public boolean hasAnyEffect() {
            return ceCostToMinimum || ceCostMultiplier != 1.0
                || accuracyBonus != 0 || accuracyMultiplier != 1.0
                || opponentAccuracyBonus != 0 || opponentAccuracyMultiplier != 1.0
                || damageMultiplier != 1.0 || defenseMultiplier != 1.0
                || bfChanceBonus != 0.0 || apBarBonus != 0 || ceCostPerRound != 0
                || !grantedMoveIds.isEmpty() || !lockedMoveTags.isEmpty()
                || !autoStatusEffects.isEmpty();
        }
    }
}
