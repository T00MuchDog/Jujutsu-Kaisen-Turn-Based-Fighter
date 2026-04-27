package com.jjktbf.editor;

import com.jjktbf.model.character.*;
import com.jjktbf.model.move.*;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Interactive terminal character editor.
 *
 * Menu:
 *   L  List all characters
 *   V  View character details
 *   N  New character — Manual mode (enter each stat directly)
 *   P  New character — Point-Buy mode (1000 points, all start at 80)
 *   E  Edit character
 *   D  Delete character (IDs resequence)
 *   Q  Quit
 *
 * Both creation modes:
 *   1. Name
 *   2. Innate Technique (name string or N/A)
 *   3. Stats in order (with live derived preview after each)
 *   4. Move assignment (filtered by technique possession + prereqs + slots)
 *   5. Abilities — placeholder (press ENTER to skip)
 *
 * Point-buy mode starts all stats at 80, budget = 1000 points.
 * Raising a stat costs 1 point per point; lowering refunds 1 point.
 * Hard floor 10, hard ceiling 300.
 */
public class CharacterEditorMain {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final String CHAR_DATA_DIR = "data/characters";
    private static final String MOVE_DATA_DIR = "data/moves";

    /** Base point-buy budget for characters WITH an innate technique. */
    private static final int POINT_BUY_BUDGET        = 1000;
    /**
     * Extra points granted when the character has NO innate technique.
     * Compensates for Cursed Technique Mastery being locked at baseline (N/A).
     */
    private static final int POINT_BUY_BONUS_NO_TECH = 80;
    private static final int STAT_MIN          = CharacterStats.MIN_STAT;   // 10
    private static final int STAT_MAX          = CharacterStats.MAX_STAT;   // 300
    private static final int STAT_BASELINE     = CharacterStats.BASELINE;   // 80

    /** Order in which stats are presented to the user. */
    private static final String[] STAT_KEYS = {
        "vitality", "strength", "durability", "speed",
        "cursedEnergyReserves", "cursedEnergyEfficiency", "cursedEnergyOutput",
        "jujutsuSkill", "combatAbility", "cursedTechniqueMastery"
    };

    private static final String[] STAT_LABELS = {
        "Vitality", "Strength", "Durability", "Speed",
        "Cursed Energy Reserves", "Cursed Energy Efficiency", "Cursed Energy Output",
        "Jujutsu Skill", "Combat Ability", "Cursed Technique Mastery"
    };

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final Scanner             sc        = new Scanner(System.in);
    private final CharacterRepository charRepo;
    private final MoveRepository      moveRepo;

    public CharacterEditorMain() {
        this.charRepo = new CharacterRepository(CHAR_DATA_DIR);
        this.moveRepo = new MoveRepository(MOVE_DATA_DIR);
    }

    public static void main(String[] args) {
        new CharacterEditorMain().run();
    }

    // =========================================================================
    // Main loop
    // =========================================================================

    private void run() {
        try {
            moveRepo.load();
            charRepo.load();
        } catch (IOException e) {
            System.out.println("[ERROR] Could not load data: " + e.getMessage());
            return;
        }

        printBanner();

        while (true) {
            printMainMenu();
            String choice = prompt("> ").trim().toUpperCase();
            switch (choice) {
                case "L" -> listCharacters();
                case "V" -> viewCharacter();
                case "N" -> newCharacter(false);
                case "P" -> newCharacter(true);
                case "E" -> editCharacter();
                case "D" -> deleteCharacter();
                case "Q" -> { System.out.println("Goodbye."); return; }
                default  -> System.out.println("  Unknown option.");
            }
        }
    }

    private void printBanner() {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║           JJK CHARACTER EDITOR  v1.0                    ║");
        System.out.printf ("║  Characters: %-43s║%n", charRepo.getDataFile().getPath());
        System.out.printf ("║  Moves:      %-43s║%n", moveRepo.getDataFile().getPath());
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.printf ("  %d characters  |  %d moves loaded.%n%n",
            charRepo.size(), moveRepo.size());
    }

    private void printMainMenu() {
        System.out.println();
        System.out.println("  ─── CHARACTER EDITOR ─────────────────────────────────────");
        System.out.println("   L  List all characters");
        System.out.println("   V  View character details");
        System.out.println("   N  New character  (Manual mode)");
        System.out.println("   P  New character  (Point-Buy — " + POINT_BUY_BUDGET + " pts)");
        System.out.println("   E  Edit character");
        System.out.println("   D  Delete character");
        System.out.println("   Q  Quit");
        System.out.println("  ──────────────────────────────────────────────────────────");
    }

    // =========================================================================
    // List
    // =========================================================================

    private void listCharacters() {
        List<CharacterData> all = charRepo.getAll();
        System.out.println();
        System.out.printf("  %-8s %-24s %-20s %-5s %-5s%n",
            "ID", "Name", "Innate Technique", "HP", "AP");
        System.out.println("  " + "─".repeat(68));
        for (CharacterData cd : all) {
            CombatStats cs = cd.toCombatStats();
            System.out.printf("  %-8s %-24s %-20s %-5d %-5d%n",
                cd.id,
                truncate(cd.name, 24),
                cd.innateTechniqueName != null ? truncate(cd.innateTechniqueName, 20) : "N/A",
                cs.getMaxHp(),
                cs.getMaxApBar());
        }
        System.out.printf("  %d characters total.%n", all.size());
    }

    // =========================================================================
    // View
    // =========================================================================

    private void viewCharacter() {
        String id = pickCharById("Enter character ID to view: ");
        if (id == null) return;
        charRepo.findById(id).ifPresentOrElse(
            this::printCharacterDetail,
            () -> System.out.println("  Character not found: " + id)
        );
    }

    private void printCharacterDetail(CharacterData cd) {
        CombatStats cs = cd.toCombatStats();
        System.out.println();
        System.out.println("  ┌────────────────────────────────────────────────────────────┐");
        System.out.printf ("  │  %-58s│%n", cd.name);
        System.out.printf ("  │  ID: %-54s│%n", cd.id);
        System.out.printf ("  │  Innate Technique: %-40s│%n",
            cd.innateTechniqueName != null ? cd.innateTechniqueName : "N/A");
        System.out.println("  ├────────────────────────────────────────────────────────────┤");
        System.out.println("  │  BASE STATS                     DERIVED STATS              │");
        System.out.println("  │  ─────────────────────────────  ─────────────────────────  │");
        printStatRow("Vitality",              cd.vitality,               "HP",       cs.getMaxHp());
        printStatRow("Strength",              cd.strength,               "AP Bar",   cs.getMaxApBar());
        printStatRow("Durability",            cd.durability,             "Accuracy", cs.getAccuracy());
        printStatRow("Speed",                 cd.speed,                  "Evasion",  cs.getEvasion());
        printStatRow("CE Reserves",           cd.cursedEnergyReserves,   "CE Pool",  cs.getMaxCursedEnergy());
        printStatRow("CE Efficiency",         cd.cursedEnergyEfficiency, "Phys Slots",cs.getPhysicalMoveSlots());
        printStatRow("CE Output",             cd.cursedEnergyOutput,     "JJ Slots", cs.getJujutsuTechniqueSlots());
        printStatRow("Jujutsu Skill",         cd.jujutsuSkill,           "CT Slots", cs.getCursedTechniqueSlots());
        printStatRow("Combat Ability",        cd.combatAbility,          "",         -1);
        // CTM is N/A for characters without an innate technique
        if (cd.innateTechniqueName != null) {
            printStatRow("CT Mastery",        cd.cursedTechniqueMastery, "",         -1);
        } else {
            printStatRowNa("CT Mastery");
        }
        System.out.println("  ├────────────────────────────────────────────────────────────┤");

        // Moves
        int moveCount = cd.moveIds != null ? cd.moveIds.size() : 0;
        System.out.printf("  │  MOVES  (%d assigned)%n", moveCount);
        if (cd.moveIds != null && !cd.moveIds.isEmpty()) {
            for (int i = 0; i < cd.moveIds.size(); i += 2) {
                String left  = formatMoveEntry(cd.moveIds.get(i));
                String right = (i + 1 < cd.moveIds.size()) ? formatMoveEntry(cd.moveIds.get(i + 1)) : "";
                System.out.printf("  │  %-28s %-28s│%n", left, right);
            }
        } else {
            System.out.printf("  │  %-58s│%n", "[none assigned]");
        }

        System.out.println("  ├────────────────────────────────────────────────────────────┤");
        System.out.println("  │  ABILITIES                                                 │");
        if (cd.abilities != null && !cd.abilities.isEmpty()) {
            for (CharacterData.AbilityData ab : cd.abilities) {
                System.out.printf("  │    %-56s│%n", truncate(ab.name + ": " + ab.description, 56));
            }
        } else {
            System.out.printf("  │  %-58s│%n", "[none — ability system coming soon]");
        }
        System.out.println("  └────────────────────────────────────────────────────────────┘");
    }

    private void printStatRow(String statLabel, int statVal, String derivedLabel, int derivedVal) {
        String statStr    = String.format("%-22s %3d", statLabel + ":", statVal);
        String derivedStr = derivedVal >= 0 ? String.format("%-10s %d", derivedLabel + ":", derivedVal) : "";
        System.out.printf("  │  %-31s %-27s│%n", statStr, derivedStr);
    }

    /** Print a stat row where the value is N/A (stat is locked/not applicable). */
    private void printStatRowNa(String statLabel) {
        String statStr = String.format("%-22s N/A", statLabel + ":");
        System.out.printf("  │  %-31s %-27s│%n", statStr, "");
    }

    private String formatMoveEntry(String moveId) {
        return moveRepo.findById(moveId)
            .map(md -> moveId + " " + truncate(md.name, 20))
            .orElse(moveId + " [missing]");
    }

    // =========================================================================
    // New character — Manual or Point-Buy
    // =========================================================================

    private void newCharacter(boolean pointBuy) {
        System.out.println();
        if (pointBuy) {
            System.out.println("  ─── NEW CHARACTER (Point-Buy) ────────────────────────────");
            System.out.println("  All stats start at " + STAT_BASELINE + ". Raise costs 1 pt, lower refunds 1 pt.");
            System.out.println("  Budget: " + POINT_BUY_BUDGET + " pts with innate technique,  "
                + (POINT_BUY_BUDGET + POINT_BUY_BONUS_NO_TECH) + " pts without (CTM locked to baseline).");
            System.out.println("  Range: " + STAT_MIN + "–" + STAT_MAX + " per stat.");
        } else {
            System.out.println("  ─── NEW CHARACTER (Manual) ───────────────────────────────");
            System.out.println("  Characters without an innate technique have CTM locked at "
                + STAT_BASELINE + " (N/A).");
        }
        System.out.printf("  Next character ID will be: %s%n%n", charRepo.nextId());

        CharacterData cd = new CharacterData();
        fillCharacterFields(cd, pointBuy);
        validateAndAdd(cd);
    }

    // =========================================================================
    // Edit
    // =========================================================================

    private void editCharacter() {
        String id = pickCharById("Enter character ID to edit: ");
        if (id == null) return;

        Optional<CharacterData> found = charRepo.findById(id);
        if (found.isEmpty()) { System.out.println("  Not found: " + id); return; }

        CharacterData cd = found.get();
        System.out.println();
        System.out.println("  Editing: " + cd.name + " (ID: " + cd.id + ")");
        System.out.println("  Press ENTER on any field to keep the current value.");
        System.out.println();

        fillCharacterFields(cd, false);
        validateAndUpdate(cd);
    }

    // =========================================================================
    // Delete
    // =========================================================================

    private void deleteCharacter() {
        String id = pickCharById("Enter character ID to delete: ");
        if (id == null) return;

        charRepo.findById(id).ifPresentOrElse(cd -> {
            System.out.printf("  Delete '%s' (ID: %s)? All subsequent IDs will shift. (y/N): ",
                cd.name, cd.id);
            if (sc.nextLine().trim().equalsIgnoreCase("y")) {
                charRepo.delete(id);
                persistNow();
                System.out.println("  Deleted and IDs resequenced.");
            } else {
                System.out.println("  Cancelled.");
            }
        }, () -> System.out.println("  Not found: " + id));
    }

    // =========================================================================
    // Field editor (shared by Manual, Point-Buy, Edit)
    // =========================================================================

    private void fillCharacterFields(CharacterData cd, boolean pointBuy) {

        // ── Name ──────────────────────────────────────────────────────────────
        sep("Name");
        cd.name = promptNonEmpty("Character name: ", cd.name);

        // ── Innate Technique ──────────────────────────────────────────────────
        sep("Innate Technique");
        System.out.println("  Enter the name of this character's innate cursed technique.");
        System.out.println("  Examples: Shrine, Blood Manipulation, Infinite Void, Ten Shadows");
        System.out.println("  Enter N/A (or leave blank) if the character has no innate technique.");
        String techInput = promptWithDefault("Innate Technique",
            cd.innateTechniqueName != null ? cd.innateTechniqueName : "N/A");
        cd.innateTechniqueName = (techInput.isBlank() || techInput.equalsIgnoreCase("n/a"))
            ? null : techInput;

        if (cd.innateTechniqueName != null) {
            System.out.println("  Technique set: " + cd.innateTechniqueName);
            System.out.println("  Moves requiring this technique name will be available during move selection.");
        } else {
            System.out.println("  No innate technique — technique-restricted moves will not be available.");
        }

        // ── Stats ─────────────────────────────────────────────────────────────
        boolean hasInnate = cd.innateTechniqueName != null;
        System.out.println();
        if (pointBuy) {
            fillStatsPointBuy(cd, hasInnate);
        } else {
            fillStatsManual(cd, hasInnate);
        }

        // ── Move assignment ───────────────────────────────────────────────────
        System.out.println();
        fillMoves(cd);

        // ── Abilities (placeholder) ───────────────────────────────────────────
        System.out.println();
        sep("Abilities  [placeholder — system coming soon]");
        System.out.println("  Abilities will be configurable in a future update.");
        System.out.print("  Press ENTER to continue: ");
        sc.nextLine();
    }

    // =========================================================================
    // Manual stat entry
    // =========================================================================

    private void fillStatsManual(CharacterData cd, boolean hasInnate) {
        sep("Stats  (range " + STAT_MIN + "–" + STAT_MAX + ", baseline " + STAT_BASELINE + ")");
        if (!hasInnate) {
            System.out.println("  Note: Cursed Technique Mastery is N/A (locked at " + STAT_BASELINE + ").");
        }

        for (int i = 0; i < STAT_KEYS.length; i++) {
            String key   = STAT_KEYS[i];
            String label = STAT_LABELS[i];

            // CTM is locked at baseline for characters with no innate technique
            if (key.equals("cursedTechniqueMastery") && !hasInnate) {
                setStatField(cd, key, STAT_BASELINE);
                System.out.println("  Cursed Technique Mastery: N/A (baseline " + STAT_BASELINE + " — no innate technique)");
                continue;
            }

            int current = getStatField(cd, key);
            int value   = promptStatWithPreview(label, current, cd, key, STAT_MIN, STAT_MAX);
            setStatField(cd, key, value);
        }
    }

    // =========================================================================
    // Point-buy stat entry
    // =========================================================================

    private void fillStatsPointBuy(CharacterData cd, boolean hasInnate) {
        // Initialise all stats to baseline if this is a fresh character
        if (cd.vitality == 0) {
            for (String key : STAT_KEYS) setStatField(cd, key, STAT_BASELINE);
        }
        // Lock CTM to baseline immediately for no-technique characters
        if (!hasInnate) {
            setStatField(cd, "cursedTechniqueMastery", STAT_BASELINE);
        }

        int budget = POINT_BUY_BUDGET + (hasInnate ? 0 : POINT_BUY_BONUS_NO_TECH);

        sep("Stats — Point-Buy  (budget: " + budget + " pts, baseline " + STAT_BASELINE + ")");
        if (!hasInnate) {
            System.out.println("  +80 bonus points applied (CTM is N/A — locked at " + STAT_BASELINE + ").");
        }

        for (int i = 0; i < STAT_KEYS.length; i++) {
            String key   = STAT_KEYS[i];
            String label = STAT_LABELS[i];

            // CTM locked for no-technique characters
            if (key.equals("cursedTechniqueMastery") && !hasInnate) {
                System.out.println("  Cursed Technique Mastery: N/A (locked at " + STAT_BASELINE + ")");
                continue;
            }

            while (true) {
                int pointsSpent     = pointsSpent(cd, hasInnate);
                int pointsRemaining = budget - pointsSpent;
                int current         = getStatField(cd, key);
                int maxAffordable   = Math.min(STAT_MAX, current + pointsRemaining);

                System.out.printf("%n  Points remaining: %d / %d%n", pointsRemaining, budget);
                System.out.printf("  %s [current: %d, max affordable: %d, floor: %d]%n",
                    label, current, maxAffordable, STAT_MIN);
                printStatPreview(cd);

                String input = prompt("  Enter value (ENTER to keep " + current + "): ").trim();
                if (input.isBlank()) break;

                try {
                    int v = Integer.parseInt(input);
                    if (v < STAT_MIN) {
                        System.out.printf("  Minimum is %d.%n", STAT_MIN);
                        continue;
                    }
                    if (v > STAT_MAX) {
                        System.out.printf("  Maximum is %d.%n", STAT_MAX);
                        continue;
                    }
                    int delta = v - current;
                    if (delta > 0 && delta > pointsRemaining) {
                        System.out.printf("  Not enough points. You have %d remaining, this costs %d.%n",
                            pointsRemaining, delta);
                        continue;
                    }
                    setStatField(cd, key, v);
                    break;
                } catch (NumberFormatException e) {
                    System.out.println("  Enter a whole number.");
                }
            }
        }

        int finalSpent = pointsSpent(cd, hasInnate);
        System.out.printf("%n  Stats finalised. Points spent: %d / %d  (unspent: %d)%n",
            finalSpent, budget, budget - finalSpent);
    }

    /**
     * Points spent = sum of all counted stats minus their baseline values.
     * When hasInnate is false, CTM is excluded from the count (it's locked and free).
     */
    private int pointsSpent(CharacterData cd, boolean hasInnate) {
        int sumStats = 0;
        int countedKeys = 0;
        for (String key : STAT_KEYS) {
            if (key.equals("cursedTechniqueMastery") && !hasInnate) continue;
            sumStats += getStatField(cd, key);
            countedKeys++;
        }
        return sumStats - (STAT_BASELINE * countedKeys);
    }

    // =========================================================================
    // Move assignment
    // =========================================================================

    private void fillMoves(CharacterData cd) {
        sep("Move Assignment");

        CharacterStats stats = cd.toCharacterStats();
        CombatStats    cs    = cd.toCombatStats();

        System.out.println("  Slot budgets:");
        System.out.printf("    Physical:               %d slots%n", cs.getPhysicalMoveSlots());
        System.out.printf("    Jujutsu / CE:           %d slots%n", cs.getJujutsuTechniqueSlots());
        System.out.printf("    Cursed Technique:       %d slots%n", cs.getCursedTechniqueSlots());
        System.out.println("    Defensive / Utility:    unlimited");
        System.out.println();

        if (cd.moveIds == null) cd.moveIds = new ArrayList<>();
        List<String> assignedIds = new ArrayList<>(cd.moveIds);

        // Track slot usage from already-assigned moves
        Map<MoveCategory, Integer> slotUsed = countSlotUsage(assignedIds, cs, stats);

        System.out.println("  Currently assigned: " + formatIdList(assignedIds));
        System.out.println("  Enter 0 to finish, or R to reset move list.");
        System.out.println();

        while (true) {
            // Build eligible move list
            List<MoveData> eligible = getEligibleMoves(cd, assignedIds, slotUsed, stats, cs);

            if (eligible.isEmpty()) {
                System.out.println("  No more moves available (all slots filled or no eligible moves remain).");
                break;
            }

            // Print eligible moves
            printEligibleMoveList(eligible, assignedIds);
            System.out.printf("  Queue: %s%n", formatIdList(assignedIds));
            System.out.println();
            System.out.print("  > Add move (#), 0 to finish, R to reset: ");
            String input = sc.nextLine().trim().toUpperCase();

            if (input.equals("0")) break;
            if (input.equals("R")) {
                assignedIds.clear();
                slotUsed.clear();
                System.out.println("  Move list cleared.");
                continue;
            }

            try {
                int idx = Integer.parseInt(input) - 1;
                if (idx < 0 || idx >= eligible.size()) {
                    System.out.println("  Invalid number.");
                    continue;
                }
                MoveData chosen = eligible.get(idx);
                assignedIds.add(chosen.id);

                // Update slot usage
                MoveCategory cat = chosen.derivedCategory();
                if (cat != MoveCategory.DEFENSIVE && cat != MoveCategory.UTILITY) {
                    slotUsed.merge(cat, 1, Integer::sum);
                }
                System.out.println("  Added: " + chosen.name);

            } catch (NumberFormatException e) {
                System.out.println("  Enter a number or 0.");
            }
        }

        cd.moveIds = assignedIds;
        System.out.printf("  %d moves assigned.%n", assignedIds.size());
    }

    /**
     * Return moves from the repository that this character is eligible for
     * and has not already assigned.
     *
     * Eligibility:
     *  - Not already in assignedIds
     *  - Technique restriction: if move.requiredTechniqueName != null,
     *    character must have a matching innateTechniqueName (case-insensitive)
     *  - All stat prerequisites met
     *  - Slot budget not exhausted for this move's category
     */
    private List<MoveData> getEligibleMoves(
        CharacterData cd,
        List<String> assignedIds,
        Map<MoveCategory, Integer> slotUsed,
        CharacterStats stats,
        CombatStats cs
    ) {
        List<MoveData> eligible = new ArrayList<>();

        for (MoveData md : moveRepo.getAll()) {
            // Skip already assigned
            if (assignedIds.contains(md.id)) continue;

            // Technique restriction check
            if (md.requiredTechniqueName != null && !md.requiredTechniqueName.isBlank()) {
                if (cd.innateTechniqueName == null
                    || !md.requiredTechniqueName.equalsIgnoreCase(cd.innateTechniqueName)) {
                    continue; // character doesn't have this technique
                }
            }

            // Stat prerequisite check
            if (md.prerequisites != null) {
                boolean prereqFailed = false;
                for (Map.Entry<String, Integer> prereq : md.prerequisites.entrySet()) {
                    try {
                        int actual = stats.getByName(prereq.getKey());
                        if (actual < prereq.getValue()) { prereqFailed = true; break; }
                    } catch (Exception ignored) { prereqFailed = true; break; }
                }
                if (prereqFailed) continue;
            }

            // Slot budget check
            MoveCategory cat = md.derivedCategory();
            if (cat != MoveCategory.DEFENSIVE && cat != MoveCategory.UTILITY) {
                int used      = slotUsed.getOrDefault(cat, 0);
                int available = getSlotBudget(cs, stats, cat);
                if (used >= available) continue;
            }

            eligible.add(md);
        }

        return eligible;
    }

    private Map<MoveCategory, Integer> countSlotUsage(
        List<String> moveIds, CombatStats cs, CharacterStats stats
    ) {
        Map<MoveCategory, Integer> slotUsed = new EnumMap<>(MoveCategory.class);
        for (String id : moveIds) {
            moveRepo.findById(id).ifPresent(md -> {
                MoveCategory cat = md.derivedCategory();
                if (cat != MoveCategory.DEFENSIVE && cat != MoveCategory.UTILITY) {
                    slotUsed.merge(cat, 1, Integer::sum);
                }
            });
        }
        return slotUsed;
    }

    private int getSlotBudget(CombatStats cs, CharacterStats stats, MoveCategory cat) {
        return switch (cat) {
            case PHYSICAL             -> cs.getPhysicalMoveSlots();
            case INNATE_TECHNIQUE     -> cs.getCursedTechniqueSlots();
            case NON_INNATE_TECHNIQUE -> cs.getJujutsuTechniqueSlots();
            default                   -> cs.hybridSlots(stats, cat);
        };
    }

    private void printEligibleMoveList(List<MoveData> moves, List<String> assigned) {
        System.out.printf("  %-4s %-8s %-24s %-20s %-5s %-5s%n",
            "#", "ID", "Name", "Tags", "AP", "Pwr");
        System.out.println("  " + "─".repeat(72));
        for (int i = 0; i < moves.size(); i++) {
            MoveData md  = moves.get(i);
            String tags  = md.tags != null ? String.join(",", md.tags) : "—";
            String power = md.basePower == 0 ? "N/A" : String.valueOf(md.basePower);
            System.out.printf("  %-4d %-8s %-24s %-20s %-5d %-5s%n",
                i + 1, md.id, truncate(md.name, 24), truncate(tags, 20), md.apCost, power);
        }
    }

    // =========================================================================
    // Validation and persistence
    // =========================================================================

    private void validateAndAdd(CharacterData cd) {
        String err = validateCharacterData(cd);
        if (err != null) { System.out.println("  [VALIDATION ERROR] " + err); return; }
        charRepo.add(cd);
        persistNow();
        System.out.println("  Saved as ID: " + cd.id + "  —  " + cd.name);
    }

    private void validateAndUpdate(CharacterData cd) {
        String err = validateCharacterData(cd);
        if (err != null) { System.out.println("  [VALIDATION ERROR] " + err); return; }
        charRepo.update(cd);
        persistNow();
        System.out.println("  Updated: " + cd.name + " (ID: " + cd.id + ")");
    }

    private String validateCharacterData(CharacterData cd) {
        if (cd.name == null || cd.name.isBlank()) return "Name is required.";
        // Attempt to build the domain character to catch constraint violations
        try {
            cd.toCharacter(moveRepo);
        } catch (Exception e) {
            return e.getMessage();
        }
        return null;
    }

    private void persistNow() {
        try { charRepo.save(); }
        catch (IOException e) { System.out.println("  [ERROR] Could not write: " + e.getMessage()); }
    }

    // =========================================================================
    // Stat preview helpers
    // =========================================================================

    private int promptStatWithPreview(String label, int current, CharacterData cd,
                                      String key, int min, int max) {
        while (true) {
            String input = prompt("  " + label + " [" + current + ", range " + min + "–" + max + "]: ").trim();
            if (input.isBlank()) {
                printStatPreviewForKey(key, current, cd);
                return current;
            }
            try {
                int v = Integer.parseInt(input);
                if (v < min || v > max) {
                    System.out.printf("  Must be between %d and %d.%n", min, max);
                    continue;
                }
                // Temporarily set to show preview
                setStatField(cd, key, v);
                printStatPreviewForKey(key, v, cd);
                return v;
            } catch (NumberFormatException e) {
                System.out.println("  Enter a whole number.");
            }
        }
    }

    private void printStatPreviewForKey(String key, int value, CharacterData cd) {
        CombatStats cs = cd.toCombatStats();
        String preview = switch (key) {
            case "vitality"               -> "→ HP: " + cs.getMaxHp();
            case "speed", "combatAbility" -> "→ AP Bar: " + cs.getMaxApBar()
                                           + "  Accuracy: " + cs.getAccuracy()
                                           + "  Evasion: " + cs.getEvasion();
            case "cursedEnergyReserves"   -> "→ CE Pool: " + cs.getMaxCursedEnergy()
                                           + "  Physical slots not affected";
            case "cursedEnergyEfficiency" -> "→ CE efficiency factor: "
                                           + String.format("%.2f", (double) CharacterStats.BASELINE / value)
                                           + "x cost multiplier";
            case "jujutsuSkill"           -> "→ Jujutsu/CE move slots: " + cs.getJujutsuTechniqueSlots();
            case "cursedTechniqueMastery" -> "→ Cursed Technique move slots: " + cs.getCursedTechniqueSlots();
            default -> "";
        };
        if (!preview.isEmpty()) System.out.println("    " + preview);
    }

    private void printStatPreview(CharacterData cd) {
        CombatStats cs = cd.toCombatStats();
        System.out.printf("    [ HP: %-4d  AP: %-4d  CE Pool: %-5d  Accuracy: %-4d  Evasion: %-4d ]%n",
            cs.getMaxHp(), cs.getMaxApBar(), cs.getMaxCursedEnergy(),
            cs.getAccuracy(), cs.getEvasion());
        System.out.printf("    [ Phys slots: %-3d  JJ slots: %-3d  CT slots: %-3d ]%n",
            cs.getPhysicalMoveSlots(), cs.getJujutsuTechniqueSlots(), cs.getCursedTechniqueSlots());
    }

    // =========================================================================
    // Stat get/set by key string (maps field name → CharacterData field)
    // =========================================================================

    private int getStatField(CharacterData cd, String key) {
        return switch (key) {
            case "vitality"               -> cd.vitality;
            case "strength"               -> cd.strength;
            case "durability"             -> cd.durability;
            case "speed"                  -> cd.speed;
            case "cursedEnergyReserves"   -> cd.cursedEnergyReserves;
            case "cursedEnergyEfficiency" -> cd.cursedEnergyEfficiency;
            case "cursedEnergyOutput"     -> cd.cursedEnergyOutput;
            case "jujutsuSkill"           -> cd.jujutsuSkill;
            case "combatAbility"          -> cd.combatAbility;
            case "cursedTechniqueMastery" -> cd.cursedTechniqueMastery;
            default -> throw new IllegalArgumentException("Unknown stat key: " + key);
        };
    }

    private void setStatField(CharacterData cd, String key, int value) {
        switch (key) {
            case "vitality"               -> cd.vitality               = value;
            case "strength"               -> cd.strength               = value;
            case "durability"             -> cd.durability             = value;
            case "speed"                  -> cd.speed                  = value;
            case "cursedEnergyReserves"   -> cd.cursedEnergyReserves   = value;
            case "cursedEnergyEfficiency" -> cd.cursedEnergyEfficiency = value;
            case "cursedEnergyOutput"     -> cd.cursedEnergyOutput     = value;
            case "jujutsuSkill"           -> cd.jujutsuSkill           = value;
            case "combatAbility"          -> cd.combatAbility          = value;
            case "cursedTechniqueMastery" -> cd.cursedTechniqueMastery = value;
            default -> throw new IllegalArgumentException("Unknown stat key: " + key);
        }
    }

    // =========================================================================
    // Prompt helpers
    // =========================================================================

    private String prompt(String label) {
        System.out.print(label);
        return sc.nextLine();
    }

    private String promptNonEmpty(String label, String current) {
        while (true) {
            String display = (current != null && !current.isBlank()) ? " [" + current + "]" : "";
            String input   = prompt("  " + label.replace(": ", display + ": ")).trim();
            if (!input.isBlank()) return input;
            if (current != null && !current.isBlank()) return current;
            System.out.println("  Value cannot be empty.");
        }
    }

    private String promptWithDefault(String label, String current) {
        String display = (current != null && !current.isBlank()) ? " [" + current + "]" : " [blank]";
        String input   = prompt("  " + label + display + ": ").trim();
        return input.isBlank() ? (current != null ? current : "") : input;
    }

    private String pickCharById(String label) {
        listCharacters();
        System.out.println();
        String id = prompt("  " + label).trim();
        if (id.isBlank()) return null;
        try {
            int n = Integer.parseInt(id);
            id = CharacterRepository.formatId(n);
        } catch (NumberFormatException ignored) {}
        return id;
    }

    // =========================================================================
    // Display helpers
    // =========================================================================

    private void sep(String title) {
        System.out.println("  ─── " + title + " " + "─".repeat(Math.max(0, 50 - title.length())));
    }

    private String formatIdList(List<String> ids) {
        if (ids == null || ids.isEmpty()) return "[none]";
        return ids.stream()
            .map(id -> moveRepo.findById(id).map(md -> md.name).orElse(id))
            .collect(Collectors.joining(" | "));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
