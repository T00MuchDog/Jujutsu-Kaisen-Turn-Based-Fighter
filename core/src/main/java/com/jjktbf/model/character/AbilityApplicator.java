package com.jjktbf.model.character;

import java.util.List;

/**
 * Applies passive ability effects to produce modified character stats and
 * a set of non-stat flags for use by BattleCombatant.
 *
 * Called once at BattleCombatant construction. Two outputs:
 *
 *   1. A modified copy of CharacterStats — all STAT_ADD / STAT_MULTIPLY /
 *      STAT_SET_MAX / STAT_SET_VALUE effects are baked in here.
 *
 *   2. An AbilityFlags object — collects all non-stat effects that can't
 *      be expressed as a stat value (CE cost overrides, move grants,
 *      auto status effects, CE per round drain, etc.).
 *
 * Active / triggered ability effects are registered separately by
 * BattleState when the fight begins; they are NOT handled here.
 *
 * STAT_BONUS_POINTS is editor-only and is ignored at runtime.
 */
public final class AbilityApplicator {

    private AbilityApplicator() {}

    /**
     * Process all passive effects from the given ability list.
     *
     * @param baseStats  the character's original CharacterStats
     * @param abilities  all abilities (PASSIVE and ACTIVE — only PASSIVE stat effects applied here)
     * @return           an ApplicationResult containing modified stats and flags
     */
    public static ApplicationResult apply(CharacterStats baseStats, List<Ability> abilities) {
        // Mutable accumulators for stat modifications
        int vitality               = baseStats.getVitality();
        int strength               = baseStats.getStrength();
        int durability             = baseStats.getDurability();
        int speed                  = baseStats.getSpeed();
        int ceReserves             = baseStats.getCursedEnergyReserves();
        int ceEfficiency           = baseStats.getCursedEnergyEfficiency();
        int ceOutput               = baseStats.getCursedEnergyOutput();
        int jujutsuSkill           = baseStats.getJujutsuSkill();
        int combatAbility          = baseStats.getCombatAbility();
        int ctMastery              = baseStats.getCursedTechniqueMastery();

        // Multipliers — applied after all additive mods
        double vitMul  = 1.0, strMul = 1.0, durMul = 1.0, spdMul = 1.0;
        double ceResMul= 1.0, ceEffMul=1.0, ceOutMul=1.0;
        double jsMul   = 1.0, caMul  = 1.0, ctmMul  = 1.0;

        AbilityFlags flags = new AbilityFlags();

        for (Ability ability : abilities) {
            // Only apply passive stat effects at construction time
            // (Active effects are registered as event listeners by BattleState)
            for (AbilityEffectData eff : ability.getEffects()) {
                AbilityEffectType type;
                try {
                    type = AbilityEffectType.valueOf(eff.type);
                } catch (IllegalArgumentException e) {
                    System.err.println("[WARN] Unknown ability effect type: " + eff.type);
                    continue;
                }

                switch (type) {

                    // ── Stat additive ───────────────────────────────────────
                    case STAT_ADD -> {
                        int amt = eff.intValue != null ? eff.intValue : 0;
                        switch (normStat(eff.stat)) {
                            case "vitality"               -> vitality       += amt;
                            case "strength"               -> strength       += amt;
                            case "durability"             -> durability     += amt;
                            case "speed"                  -> speed          += amt;
                            case "cursedenergyreserves"   -> ceReserves     += amt;
                            case "cursedenergyefficiency" -> ceEfficiency   += amt;
                            case "cursedenergyoutput"     -> ceOutput       += amt;
                            case "jujutsuskill"           -> jujutsuSkill   += amt;
                            case "combatability"          -> combatAbility  += amt;
                            case "cursedtechniquemastery" -> ctMastery      += amt;
                            default -> System.err.println("[WARN] Unknown stat: " + eff.stat);
                        }
                    }

                    // ── Stat multiply ────────────────────────────────────────
                    case STAT_MULTIPLY -> {
                        double f = eff.doubleValue != null ? eff.doubleValue : 1.0;
                        switch (normStat(eff.stat)) {
                            case "vitality"               -> vitMul    *= f;
                            case "strength"               -> strMul    *= f;
                            case "durability"             -> durMul    *= f;
                            case "speed"                  -> spdMul    *= f;
                            case "cursedenergyreserves"   -> ceResMul  *= f;
                            case "cursedenergyefficiency" -> ceEffMul  *= f;
                            case "cursedenergyoutput"     -> ceOutMul  *= f;
                            case "jujutsuskill"           -> jsMul     *= f;
                            case "combatability"          -> caMul     *= f;
                            case "cursedtechniquemastery" -> ctmMul    *= f;
                            default -> System.err.println("[WARN] Unknown stat: " + eff.stat);
                        }
                    }

                    // ── Stat set max ─────────────────────────────────────────
                    case STAT_SET_MAX -> {
                        switch (normStat(eff.stat)) {
                            case "vitality"               -> vitality       = CharacterStats.MAX_STAT;
                            case "strength"               -> strength       = CharacterStats.MAX_STAT;
                            case "durability"             -> durability     = CharacterStats.MAX_STAT;
                            case "speed"                  -> speed          = CharacterStats.MAX_STAT;
                            case "cursedenergyreserves"   -> ceReserves     = CharacterStats.MAX_STAT;
                            case "cursedenergyefficiency" -> ceEfficiency   = CharacterStats.MAX_STAT;
                            case "cursedenergyoutput"     -> ceOutput       = CharacterStats.MAX_STAT;
                            case "jujutsuskill"           -> jujutsuSkill   = CharacterStats.MAX_STAT;
                            case "combatability"          -> combatAbility  = CharacterStats.MAX_STAT;
                            case "cursedtechniquemastery" -> ctMastery      = CharacterStats.MAX_STAT;
                            default -> System.err.println("[WARN] Unknown stat: " + eff.stat);
                        }
                    }

                    // ── Stat set value ────────────────────────────────────────
                    case STAT_SET_VALUE -> {
                        int v = eff.intValue != null ? eff.intValue : CharacterStats.BASELINE;
                        v = Math.max(CharacterStats.MIN_STAT, Math.min(CharacterStats.MAX_STAT, v));
                        switch (normStat(eff.stat)) {
                            case "vitality"               -> vitality       = v;
                            case "strength"               -> strength       = v;
                            case "durability"             -> durability     = v;
                            case "speed"                  -> speed          = v;
                            case "cursedenergyreserves"   -> ceReserves     = v;
                            case "cursedenergyefficiency" -> ceEfficiency   = v;
                            case "cursedenergyoutput"     -> ceOutput       = v;
                            case "jujutsuskill"           -> jujutsuSkill   = v;
                            case "combatability"          -> combatAbility  = v;
                            case "cursedtechniquemastery" -> ctMastery      = v;
                            default -> System.err.println("[WARN] Unknown stat: " + eff.stat);
                        }
                    }

                    // ── Non-stat effects → flags ──────────────────────────────
                    case CE_COST_TO_MINIMUM -> flags.ceCostToMinimum    = true;
                    case CE_COST_MULTIPLY   -> {
                        double f = eff.doubleValue != null ? eff.doubleValue : 1.0;
                        flags.ceCostMultiplier *= f;
                    }
                    case MOVE_ACCURACY_ADD  -> flags.accuracyBonus     += eff.intValue  != null ? eff.intValue  : 0;
                    case DAMAGE_MULTIPLY    -> flags.damageMultiplier   *= eff.doubleValue != null ? eff.doubleValue : 1.0;
                    case BF_CHANCE_ADD      -> flags.bfChanceBonus     += eff.doubleValue != null ? eff.doubleValue : 0.0;
                    case MODIFY_DEFENSE     -> flags.defenseMultiplier *= eff.doubleValue != null ? eff.doubleValue : 1.0;
                    case MODIFY_AP_BAR      -> flags.apBarBonus        += eff.intValue  != null ? eff.intValue  : 0;
                    case COST_CE_PER_ROUND  -> flags.ceCostPerRound    += eff.intValue  != null ? eff.intValue  : 0;
                    case GRANT_MOVE         -> { if (eff.moveId != null) flags.grantedMoveIds.add(eff.moveId); }
                    case BLOCK_MOVE_TAG     -> { if (eff.moveTag != null) flags.blockedMoveTags.add(eff.moveTag); }
                    case AUTO_STATUS_APPLY  -> flags.autoStatusEffects.add(eff);
                    case UNLOCK_TECHNIQUE   -> { /* handled at character load time in CharacterData */ }
                    case STAT_BONUS_POINTS  -> { /* editor-only, no runtime effect */ }
                }
            }
        }

        // Apply multipliers and clamp
        CharacterStats modified = new CharacterStats.Builder()
            .vitality              (clamp((int) Math.round(vitality    * vitMul)))
            .strength              (clamp((int) Math.round(strength    * strMul)))
            .durability            (clamp((int) Math.round(durability  * durMul)))
            .speed                 (clamp((int) Math.round(speed       * spdMul)))
            .cursedEnergyReserves  (clamp((int) Math.round(ceReserves  * ceResMul)))
            .cursedEnergyEfficiency(clamp((int) Math.round(ceEfficiency* ceEffMul)))
            .cursedEnergyOutput    (clamp((int) Math.round(ceOutput    * ceOutMul)))
            .jujutsuSkill          (clamp((int) Math.round(jujutsuSkill* jsMul)))
            .combatAbility         (clamp((int) Math.round(combatAbility* caMul)))
            .cursedTechniqueMastery(clamp((int) Math.round(ctMastery   * ctmMul)))
            .build();

        return new ApplicationResult(modified, flags);
    }

    private static int clamp(int v) {
        return Math.max(CharacterStats.MIN_STAT, Math.min(CharacterStats.MAX_STAT, v));
    }

    private static String normStat(String stat) {
        return stat != null ? stat.toLowerCase().replace("_", "").replace(" ", "") : "";
    }

    // -------------------------------------------------------------------------
    // Result types
    // -------------------------------------------------------------------------

    /** The combined output of ability application. */
    public static class ApplicationResult {
        public final CharacterStats modifiedStats;
        public final AbilityFlags   flags;

        public ApplicationResult(CharacterStats modifiedStats, AbilityFlags flags) {
            this.modifiedStats = modifiedStats;
            this.flags         = flags;
        }
    }

    /** All non-stat effects that BattleCombatant needs to track at runtime. */
    public static class AbilityFlags {
        /** If true, all CE costs are forced to their move-defined minimum. */
        public boolean ceCostToMinimum  = false;

        /** Global CE cost multiplier (product of all CE_COST_MULTIPLY effects). */
        public double  ceCostMultiplier = 1.0;

        /** Flat accuracy bonus added to every move's hit roll. */
        public int     accuracyBonus    = 0;

        /** Global damage multiplier (product of all DAMAGE_MULTIPLY effects). */
        public double  damageMultiplier = 1.0;

        /** Bonus added to the Black Flash proc chance. */
        public double  bfChanceBonus    = 0.0;

        /** Global defense multiplier. */
        public double  defenseMultiplier= 1.0;

        /** Flat AP bar size bonus. */
        public int     apBarBonus       = 0;

        /** CE drained from the character at the start of each round. */
        public int     ceCostPerRound   = 0;

        /** Move IDs granted outside the slot system. */
        public final java.util.List<String>            grantedMoveIds    = new java.util.ArrayList<>();

        /** Move tags the character is forbidden from using. */
        public final java.util.List<String>            blockedMoveTags   = new java.util.ArrayList<>();

        /** AUTO_STATUS_APPLY effect entries to resolve during combat. */
        public final java.util.List<AbilityEffectData> autoStatusEffects = new java.util.ArrayList<>();

        public boolean hasAnyEffect() {
            return ceCostToMinimum || ceCostMultiplier != 1.0 || accuracyBonus != 0
                || damageMultiplier != 1.0 || bfChanceBonus != 0.0 || defenseMultiplier != 1.0
                || apBarBonus != 0 || ceCostPerRound != 0
                || !grantedMoveIds.isEmpty() || !blockedMoveTags.isEmpty() || !autoStatusEffects.isEmpty();
        }
    }
}
