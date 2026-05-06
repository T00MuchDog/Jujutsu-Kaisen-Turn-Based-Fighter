package com.jjktbf.editor;

import com.jjktbf.model.character.*;
import com.jjktbf.model.move.*;
import com.jjktbf.model.character.SlotBudgetEnforcer;

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

    private static final String CHAR_DATA_DIR    = "data/characters";
    private static final String MOVE_DATA_DIR    = "data/moves";
    private static final String ABILITY_DATA_DIR = "data/abilities";

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

    /** Stat display order — drives all stat-entry loops. Derived from StatKey enum. */
    private static final StatKey[] STAT_ORDER = {
        StatKey.VITALITY, StatKey.STRENGTH, StatKey.DURABILITY, StatKey.SPEED,
        StatKey.COMBAT_ABILITY,
        StatKey.CURSED_ENERGY_RESERVES, StatKey.CURSED_ENERGY_EFFICIENCY, StatKey.CURSED_ENERGY_OUTPUT,
        StatKey.JUJUTSU_SKILL, StatKey.CURSED_TECHNIQUE_MASTERY
    };

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final Scanner             sc;
    private final EditorIO              io;
    private final CharacterRepository   charRepo;
    private final MoveRepository        moveRepo;
    private final AbilityRepository     abilityRepo;
    private final StatEntryFlow         statEntry;
    private final MoveAssignmentFlow    moveAssignment;
    private final AbilityAssignmentFlow abilityAssignment;

    public CharacterEditorMain() {
        this.sc                = new Scanner(System.in);
        this.io                = new EditorIO(sc);
        this.charRepo          = new CharacterRepository(CHAR_DATA_DIR);
        this.moveRepo          = new MoveRepository(MOVE_DATA_DIR);
        this.abilityRepo       = new AbilityRepository(ABILITY_DATA_DIR);
        this.statEntry         = new StatEntryFlow(io, sc);
        this.moveAssignment    = new MoveAssignmentFlow(io, sc, moveRepo);
        this.abilityAssignment = new AbilityAssignmentFlow(io, sc, abilityRepo);
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
            abilityRepo.load();
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
        System.out.printf ("║  Abilities:  %-43s║%n", abilityRepo.getDataFile().getPath());
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.printf ("  %d characters  |  %d moves  |  %d abilities loaded.%n%n",
            charRepo.size(), moveRepo.size(), abilityRepo.size());
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
        int abilityCount = cd.abilityIds != null ? cd.abilityIds.size() : 0;
        System.out.printf("  │  ABILITIES  (%d assigned)%n", abilityCount);
        if (cd.abilityIds != null && !cd.abilityIds.isEmpty()) {
            for (String abilityId : cd.abilityIds) {
                System.out.printf("  │  %-58s│%n", formatAbilityEntry(abilityId));
            }
        } else {
            System.out.printf("  │  %-58s│%n", "[none assigned]");
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
            statEntry.fillPointBuy(cd, hasInnate);
        } else {
            statEntry.fillManual(cd, hasInnate);
        }

        // ── Move assignment ───────────────────────────────────────────────────
        System.out.println();
        moveAssignment.run(cd);

        // ── Ability assignment ────────────────────────────────────────────────
        System.out.println();
        abilityAssignment.run(cd);
    }


    // =========================================================================
    // Validation and persistence
    // =========================================================================

    private void validateAndAdd(CharacterData cd) {
        if (cd.id == null || cd.id.isBlank()) { cd.id = charRepo.nextId(); }
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

    private String formatAbilityEntry(String abilityId) {
        return abilityRepo.findById(abilityId)
            .map(ad -> abilityId + " " + truncate(ad.name, 40)
                + " [" + (ad.category != null ? ad.category : "?") + "]")
            .orElse(abilityId + " [missing]");
    }

    // =========================================================================
    // Prompt helpers — delegates to EditorIO
    // =========================================================================

    private String prompt(String label)                        { return io.prompt(label); }
    private String promptNonEmpty(String label, String cur)    { return io.promptNonEmpty(label, cur); }
    private String promptWithDefault(String label, String cur) { return io.promptWithDefault(label, cur); }

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
    // Display helpers — delegates to EditorIO
    // =========================================================================

    private void sep(String title) { io.sep(title); }

    private String formatIdList(List<String> ids) {
        if (ids == null || ids.isEmpty()) return "[none]";
        return ids.stream()
            .map(id -> moveRepo.findById(id).map(md -> md.name).orElse(id))
            .collect(Collectors.joining(" | "));
    }

    private static String truncate(String s, int max) { return EditorIO.truncate(s, max); }
}
