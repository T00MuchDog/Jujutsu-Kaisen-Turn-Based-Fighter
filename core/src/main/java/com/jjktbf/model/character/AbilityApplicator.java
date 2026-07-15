package com.jjktbf.model.character;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
 * Active abilities are represented by their linked move and are not applied here.
 *
 * STAT_BONUS_POINTS is editor/creator-only and is silently ignored at runtime.
 */
public final class AbilityApplicator {

    private AbilityApplicator() {}

    public static ApplicationResult apply(CharacterStats baseStats, List<Ability> abilities) {

        // Use EnumMap — one entry per StatKey. No parallel variable arrays.
        Map<StatKey, Integer> overrides   = new EnumMap<>(StatKey.class);
        Map<StatKey, Integer> additions   = new EnumMap<>(StatKey.class);
        Map<StatKey, Double>  multipliers = new EnumMap<>(StatKey.class);

        // Sets are resolved first, then additions, then multipliers.
        for (StatKey k : StatKey.values()) {
            additions.put(k, 0);
            multipliers.put(k, 1.0);
        }

        AbilityFlags flags = new AbilityFlags();

        for (Ability ability : abilities == null ? List.<Ability>of() : abilities) {
            if (ability == null || !ability.isPassive()) continue;
            for (AbilityEffectData eff : ability.getEffects()) {
                if (eff == null || eff.type == null) {
                    System.err.println("[WARN] AbilityApplicator: missing ability effect type");
                    continue;
                }
                AbilityEffectType type;
                try {
                    type = AbilityEffectType.fromName(eff.type);
                } catch (IllegalArgumentException e) {
                    System.err.println("[WARN] Unknown ability effect type: " + eff.type);
                    continue;
                }

                switch (type) {

                    // ── Override (set) — applied before add/multiply ─────────
                    case STAT_SET_MIN -> {
                        StatKey k = resolveStatKey(eff.stat);
                        if (k != null) overrides.put(k, 0);
                    }
                    case STAT_SET_VALUE -> {
                        StatKey k = resolveStatKey(eff.stat);
                        if (k != null) overrides.put(k, nvl(eff.intValue, 0));
                    }

                    // ── Additive ─────────────────────────────────────────────
                    case STAT_ADD -> {
                        StatKey k = resolveStatKey(eff.stat);
                        if (k != null) additions.merge(k, nvl(eff.intValue, 0), Integer::sum);
                    }

                    // ── Multiplicative ───────────────────────────────────────
                    case STAT_MULTIPLY -> {
                        StatKey k = resolveStatKey(eff.stat);
                        if (k != null) multipliers.merge(k, nvl(eff.doubleValue, 1.0), (a, b) -> a * b);
                    }
                    case STAT_DIVIDE -> {
                        double d = (eff.doubleValue != null && eff.doubleValue != 0) ? eff.doubleValue : 1.0;
                        StatKey k = resolveStatKey(eff.stat);
                        if (k != null) multipliers.merge(k, d, (a, b) -> a / b);
                    }

                    // ── Non-stat → flags ─────────────────────────────────────
                    case CE_COST_TO_MINIMUM      -> {
                        flags.ceCostToMinimum = true;
                        flags.ceCostToMinimumEffects.add(eff);
                    }
                    case CE_COST_MULTIPLY        -> {
                        flags.ceCostMultiplier *= nvl(eff.doubleValue, 1.0);
                        flags.ceCostMultiplierEffects.add(eff);
                    }

                    case MOVE_ACCURACY_ADD       -> {
                        flags.accuracyBonus += nvl(eff.intValue, 0);
                        flags.accuracyAddEffects.add(eff);
                    }
                    case MOVE_ACCURACY_MULTIPLY  -> {
                        flags.accuracyMultiplier *= nvl(eff.doubleValue, 1.0);
                        flags.accuracyMultiplierEffects.add(eff);
                    }

                    case OPPONENT_ACCURACY_ADD      -> {
                        flags.opponentAccuracyBonus += nvl(eff.intValue, 0);
                        flags.opponentAccuracyAddEffects.add(eff);
                    }
                    case OPPONENT_ACCURACY_MULTIPLY -> {
                        flags.opponentAccuracyMultiplier *= nvl(eff.doubleValue, 1.0);
                        flags.opponentAccuracyMultiplierEffects.add(eff);
                    }

                    case DAMAGE_MULTIPLY         -> {
                        flags.damageMultiplier *= nvl(eff.doubleValue, 1.0);
                        flags.damageMultiplierEffects.add(eff);
                    }
                    case BF_CHANCE_ADD           -> flags.bfChanceBonus    += nvl(eff.doubleValue, 0.0);
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

                    case UNLOCK_TECHNIQUE  -> {
                        // Technique access is resolved once at construction, not per
                        // applicator pass: Character.accessibleTechniquesOf() unions the
                        // granted technique name into the access set, and move validation
                        // (Character.validateAndBuildMoveList) checks membership. Nothing
                        // to mutate on the combatant here — the granted moves flow in via
                        // the character's knownMoves/ability list, and UNLOCK_TECHNIQUE
                        // has no stat/slot side effects.
                    }
                    case STAT_BONUS_POINTS -> { /* editor/creator-only — no runtime effect */ }
                }
            }
        }

        // Apply multipliers via unclamped constructor (0 is valid)
        CharacterStats modified = new CharacterStats(
            finalStat(StatKey.VITALITY, baseStats, overrides, additions, multipliers),
            finalStat(StatKey.STRENGTH, baseStats, overrides, additions, multipliers),
            finalStat(StatKey.DURABILITY, baseStats, overrides, additions, multipliers),
            finalStat(StatKey.SPEED, baseStats, overrides, additions, multipliers),
            finalStat(StatKey.CURSED_ENERGY_RESERVES, baseStats, overrides, additions, multipliers),
            finalStat(StatKey.CURSED_ENERGY_EFFICIENCY, baseStats, overrides, additions, multipliers),
            finalStat(StatKey.CURSED_ENERGY_OUTPUT, baseStats, overrides, additions, multipliers),
            finalStat(StatKey.JUJUTSU_SKILL, baseStats, overrides, additions, multipliers),
            finalStat(StatKey.COMBAT_ABILITY, baseStats, overrides, additions, multipliers),
            finalStat(StatKey.CURSED_TECHNIQUE_MASTERY, baseStats, overrides, additions, multipliers)
        );

        return new ApplicationResult(modified, flags);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Resolve a stat name string to a StatKey, logging a warning on failure. */
    private static StatKey resolveStatKey(String stat) {
        if (stat == null || stat.isBlank()) {
            System.err.println("[WARN] AbilityApplicator: null/blank stat name");
            return null;
        }
        try {
            return StatKey.fromString(stat);
        } catch (IllegalArgumentException e) {
            System.err.println("[WARN] AbilityApplicator: unknown stat '" + stat + "'");
            return null;
        }
    }
    private static int    nvl(Integer v, int    def) { return v != null ? v : def; }
    private static double nvl(Double  v, double def) { return v != null ? v : def; }
    private static int    noNeg(int v)               { return Math.max(0, v); }

    private static int finalStat(
        StatKey key,
        CharacterStats baseStats,
        Map<StatKey, Integer> overrides,
        Map<StatKey, Integer> additions,
        Map<StatKey, Double> multipliers
    ) {
        int startingValue = overrides.getOrDefault(key, key.get(baseStats));
        return noNeg((int) Math.round(
            (startingValue + additions.getOrDefault(key, 0))
                * multipliers.getOrDefault(key, 1.0)));
    }

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
        public final java.util.List<AbilityEffectData> ceCostToMinimumEffects = new java.util.ArrayList<>();
        public final java.util.List<AbilityEffectData> ceCostMultiplierEffects = new java.util.ArrayList<>();

        // Own accuracy
        public int     accuracyBonus      = 0;
        public double  accuracyMultiplier = 1.0;
        public final java.util.List<AbilityEffectData> accuracyAddEffects = new java.util.ArrayList<>();
        public final java.util.List<AbilityEffectData> accuracyMultiplierEffects = new java.util.ArrayList<>();

        // Opponent accuracy modifiers (applied to the opponent's hit chance)
        public int     opponentAccuracyBonus      = 0;
        public double  opponentAccuracyMultiplier = 1.0;
        public final java.util.List<AbilityEffectData> opponentAccuracyAddEffects = new java.util.ArrayList<>();
        public final java.util.List<AbilityEffectData> opponentAccuracyMultiplierEffects = new java.util.ArrayList<>();

        // Damage / defense
        public double  damageMultiplier   = 1.0;
        public double  defenseMultiplier  = 1.0;
        public final java.util.List<AbilityEffectData> damageMultiplierEffects = new java.util.ArrayList<>();

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
         * Active abilities configure move behavior rather than using this flag.
         */
        public final java.util.List<String>            lockedMoveTags    = new java.util.ArrayList<>();

        // Status automation
        public final java.util.List<AbilityEffectData> autoStatusEffects = new java.util.ArrayList<>();

        private boolean appliesTo(AbilityEffectData effect, com.jjktbf.model.move.Move move) {
            return effect.moveTag == null || effect.moveTag.isBlank() || move.hasTag(effect.moveTag);
        }

        public boolean forcesMinimumCeCost(com.jjktbf.model.move.Move move) {
            return ceCostToMinimumEffects.stream().anyMatch(effect -> appliesTo(effect, move));
        }

        public double ceCostMultiplierFor(com.jjktbf.model.move.Move move) {
            double multiplier = 1.0;
            for (AbilityEffectData effect : ceCostMultiplierEffects) {
                if (appliesTo(effect, move)) multiplier *= nvl(effect.doubleValue, 1.0);
            }
            return multiplier;
        }

        public int accuracyBonusFor(com.jjktbf.model.move.Move move) {
            int bonus = 0;
            for (AbilityEffectData effect : accuracyAddEffects) {
                if (appliesTo(effect, move)) bonus += nvl(effect.intValue, 0);
            }
            return bonus;
        }

        public double accuracyMultiplierFor(com.jjktbf.model.move.Move move) {
            double multiplier = 1.0;
            for (AbilityEffectData effect : accuracyMultiplierEffects) {
                if (appliesTo(effect, move)) multiplier *= nvl(effect.doubleValue, 1.0);
            }
            return multiplier;
        }

        public int opponentAccuracyBonusFor(com.jjktbf.model.move.Move move) {
            int bonus = 0;
            for (AbilityEffectData effect : opponentAccuracyAddEffects) {
                if (appliesTo(effect, move)) bonus += nvl(effect.intValue, 0);
            }
            return bonus;
        }

        public double opponentAccuracyMultiplierFor(com.jjktbf.model.move.Move move) {
            double multiplier = 1.0;
            for (AbilityEffectData effect : opponentAccuracyMultiplierEffects) {
                if (appliesTo(effect, move)) multiplier *= nvl(effect.doubleValue, 1.0);
            }
            return multiplier;
        }

        public double damageMultiplierFor(com.jjktbf.model.move.Move move) {
            double multiplier = 1.0;
            for (AbilityEffectData effect : damageMultiplierEffects) {
                if (appliesTo(effect, move)) multiplier *= nvl(effect.doubleValue, 1.0);
            }
            return multiplier;
        }

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
