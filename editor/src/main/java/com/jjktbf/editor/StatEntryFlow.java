package com.jjktbf.editor;

import com.jjktbf.model.character.*;

import java.util.Scanner;

/**
 * Handles all stat-entry interactions for a CharacterData being created or edited.
 *
 * Supports two modes:
 *   - Manual: the creator enters any value (0 = N/A, no upper limit enforced)
 *   - Point-Buy: 1000-point budget, all stats start at 80
 *
 * Receives its dependencies via constructor (EditorIO for prompts, constants
 * for bounds). Does not touch MoveRepository, AbilityRepository, or any CRUD.
 */
public class StatEntryFlow {

    private static final int STAT_MIN               = CharacterStats.MIN_STAT;
    private static final int STAT_MAX               = CharacterStats.MAX_STAT;
    private static final int STAT_BASELINE          = CharacterStats.BASELINE;
    private static final int POINT_BUY_BUDGET       = 1000;
    private static final int POINT_BUY_BONUS_NO_TECH = 80;

    /** Display order for stats — must match StatKey.values() order desired by the editor. */
    static final StatKey[] STAT_ORDER = {
        StatKey.VITALITY, StatKey.STRENGTH, StatKey.DURABILITY, StatKey.SPEED,
        StatKey.COMBAT_ABILITY,
        StatKey.CURSED_ENERGY_RESERVES, StatKey.CURSED_ENERGY_EFFICIENCY, StatKey.CURSED_ENERGY_OUTPUT,
        StatKey.JUJUTSU_SKILL, StatKey.CURSED_TECHNIQUE_MASTERY
    };

    private final EditorIO io;
    private final Scanner  sc;

    public StatEntryFlow(EditorIO io, Scanner sc) {
        this.io = io;
        this.sc = sc;
    }

    // =========================================================================
    // Entry points
    // =========================================================================

    public void fillManual(CharacterData cd, boolean hasInnate) {
        io.sep("Stats  (editor mode — no limits enforced)");
        System.out.println("  Baseline: " + STAT_BASELINE + "  |  Game-rule range: " + STAT_MIN + "–" + STAT_MAX
            + "  |  Editor: any value, including 0 (N/A)");
        if (!hasInnate) {
            System.out.println("  Note: Cursed Technique Mastery is N/A (locked — no innate technique).");
        }

        for (StatKey key : STAT_ORDER) {
            if (key == StatKey.CURSED_TECHNIQUE_MASTERY && !hasInnate) {
                key.set(cd, 0);
                System.out.println("  Cursed Technique Mastery: N/A (locked — no innate technique)");
                continue;
            }
            int current = key.get(cd);
            int value   = promptUncapped(key.label, current, cd, key);
            key.set(cd, value);
        }
    }

    public void fillPointBuy(CharacterData cd, boolean hasInnate) {
        if (cd.vitality == 0) {
            for (StatKey key : STAT_ORDER) key.set(cd, STAT_BASELINE);
        }
        if (!hasInnate) {
            StatKey.CURSED_TECHNIQUE_MASTERY.set(cd, STAT_BASELINE);
        }

        int budget = POINT_BUY_BUDGET + (hasInnate ? 0 : POINT_BUY_BONUS_NO_TECH);

        io.sep("Stats — Point-Buy  (budget: " + budget + " pts, baseline " + STAT_BASELINE + ")");
        if (!hasInnate) {
            System.out.println("  +80 bonus points applied (CTM is N/A — locked at " + STAT_BASELINE + ").");
        }

        for (StatKey key : STAT_ORDER) {
            if (key == StatKey.CURSED_TECHNIQUE_MASTERY && !hasInnate) {
                System.out.println("  Cursed Technique Mastery: N/A (locked at " + STAT_BASELINE + ")");
                continue;
            }

            while (true) {
                int pointsSpent     = pointsSpent(cd, hasInnate);
                int pointsRemaining = budget - pointsSpent;
                int current         = key.get(cd);
                int maxAffordable   = Math.min(STAT_MAX, current + pointsRemaining);

                System.out.printf("%n  Points remaining: %d / %d%n", pointsRemaining, budget);
                System.out.printf("  %s [current: %d, max affordable: %d, floor: %d]%n",
                    key.label, current, maxAffordable, STAT_MIN);
                printSummary(cd);

                String input = io.prompt("  Enter value (ENTER to keep " + current + "): ").trim();
                if (input.isBlank()) break;

                try {
                    int v = Integer.parseInt(input);
                    if (v < STAT_MIN) { System.out.printf("  Minimum is %d.%n", STAT_MIN); continue; }
                    if (v > STAT_MAX) { System.out.printf("  Maximum is %d.%n", STAT_MAX); continue; }
                    int delta = v - current;
                    if (delta > 0 && delta > pointsRemaining) {
                        System.out.printf("  Not enough points. You have %d remaining, this costs %d.%n",
                            pointsRemaining, delta);
                        continue;
                    }
                    key.set(cd, v);
                    break;
                } catch (NumberFormatException e) {
                    System.out.println("  Enter a whole number.");
                }
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private int pointsSpent(CharacterData cd, boolean hasInnate) {
        int sumStats = 0, counted = 0;
        for (StatKey key : STAT_ORDER) {
            if (key == StatKey.CURSED_TECHNIQUE_MASTERY && !hasInnate) continue;
            sumStats += key.get(cd);
            counted++;
        }
        return sumStats - (STAT_BASELINE * counted);
    }

    private int promptUncapped(String label, int current, CharacterData cd, StatKey key) {
        while (true) {
            String input = io.prompt("  " + label + " [" + current + "] (0 = N/A, no upper limit): ").trim();
            if (input.isBlank()) {
                printPreviewForKey(key, current, cd);
                return current;
            }
            try {
                int v = Integer.parseInt(input);
                if (v < 0) { System.out.println("  Stats cannot be negative. Enter 0 for N/A."); continue; }
                key.set(cd, v);
                printPreviewForKey(key, v, cd);
                return v;
            } catch (NumberFormatException e) {
                System.out.println("  Enter a whole number.");
            }
        }
    }

    private void printPreviewForKey(StatKey key, int value, CharacterData cd) {
        CombatStats cs = cd.toCombatStats();
        String preview = switch (key) {
            case VITALITY                 -> "→ HP: " + cs.getMaxHp();
            case SPEED, COMBAT_ABILITY    -> "→ AP Bar: " + cs.getMaxApBar()
                                           + "  Accuracy: " + cs.getAccuracy()
                                           + "  Evasion: " + cs.getEvasion();
            case CURSED_ENERGY_RESERVES   -> "→ CE Pool: " + cs.getMaxCursedEnergy();
            case CURSED_ENERGY_EFFICIENCY -> "→ CE efficiency factor: "
                                           + String.format("%.2f", (double) STAT_BASELINE / Math.max(1, value))
                                           + "x cost multiplier";
            case JUJUTSU_SKILL            -> "→ Jujutsu/CE move slots: " + cs.getJujutsuTechniqueSlots();
            case CURSED_TECHNIQUE_MASTERY -> "→ Cursed Technique move slots: " + cs.getCursedTechniqueSlots();
            default -> "";
        };
        if (!preview.isEmpty()) System.out.println("    " + preview);
    }

    void printSummary(CharacterData cd) {
        CombatStats cs = cd.toCombatStats();
        System.out.printf("    [ HP: %-4d  AP: %-4d  CE Pool: %-5d  Accuracy: %-4d  Evasion: %-4d ]%n",
            cs.getMaxHp(), cs.getMaxApBar(), cs.getMaxCursedEnergy(),
            cs.getAccuracy(), cs.getEvasion());
        System.out.printf("    [ Phys slots: %-3d  JJ slots: %-3d  CT slots: %-3d ]%n",
            cs.getPhysicalMoveSlots(), cs.getJujutsuTechniqueSlots(), cs.getCursedTechniqueSlots());
    }
}
