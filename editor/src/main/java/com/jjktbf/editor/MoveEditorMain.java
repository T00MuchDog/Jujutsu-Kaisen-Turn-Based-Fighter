package com.jjktbf.editor;

import com.jjktbf.model.move.*;

import java.io.IOException;
import java.util.*;

/**
 * Interactive terminal move editor.
 *
 * All changes are written immediately to data/moves/all_moves.json.
 *
 * Main menu:
 *   L  — List all moves
 *   V  — View / inspect a move
 *   N  — New move
 *   E  — Edit a move
 *   D  — Delete a move
 *   Q  — Quit
 */
public class MoveEditorMain {

    private static final String DATA_DIR = "data/moves";

    private final Scanner        sc   = new Scanner(System.in);
    private final MoveRepository repo;

    public MoveEditorMain() {
        this.repo = new MoveRepository(DATA_DIR);
    }

    public static void main(String[] args) {
        new MoveEditorMain().run();
    }

    // =========================================================================
    // Main loop
    // =========================================================================

    private void run() {
        try {
            repo.load();
        } catch (IOException e) {
            System.out.println("[ERROR] Could not load move data: " + e.getMessage());
            return;
        }

        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║              JJK MOVE EDITOR  v1.0                      ║");
        System.out.printf ("║  Data file: %-44s║%n", repo.getDataFile().getPath());
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.printf ("  %d moves loaded.%n%n", repo.getAll().size());

        while (true) {
            printMainMenu();
            String choice = prompt("> ").toUpperCase();

            switch (choice) {
                case "L" -> listMoves();
                case "V" -> viewMove();
                case "N" -> newMove();
                case "E" -> editMove();
                case "D" -> deleteMove();
                case "Q" -> { System.out.println("Goodbye."); return; }
                default  -> System.out.println("  Unknown option.");
            }
        }
    }

    private void printMainMenu() {
        System.out.println();
        System.out.println("  ─── MOVE EDITOR ──────────────────────────────────────────");
        System.out.println("   L  List all moves");
        System.out.println("   V  View move details");
        System.out.println("   N  New move");
        System.out.println("   E  Edit move");
        System.out.println("   D  Delete move");
        System.out.println("   Q  Quit");
        System.out.println("  ──────────────────────────────────────────────────────────");
    }

    // =========================================================================
    // List
    // =========================================================================

    private void listMoves() {
        List<MoveData> all = repo.getAll();
        System.out.println();
        System.out.printf("  %-22s %-28s %-7s %-5s %-5s%n",
            "ID", "Name", "Category", "AP", "Pwr");
        System.out.println("  " + "─".repeat(72));
        for (MoveData md : all) {
            System.out.printf("  %-22s %-28s %-7s %-5d %-5d%n",
                truncate(md.id, 22),
                truncate(md.name, 28),
                truncate(md.category, 7),
                md.apCost,
                md.basePower);
        }
        System.out.printf("  %d moves total.%n", all.size());
    }

    // =========================================================================
    // View
    // =========================================================================

    private void viewMove() {
        String id = promptMoveId("Enter move ID to view: ");
        if (id == null) return;
        repo.findById(id).ifPresentOrElse(
            this::printMoveDetail,
            () -> System.out.println("  Move not found: " + id)
        );
    }

    private void printMoveDetail(MoveData md) {
        System.out.println();
        System.out.println("  ┌─────────────────────────────────────────────────────────┐");
        System.out.printf ("  │  %-55s│%n", md.name);
        System.out.printf ("  │  ID: %-52s│%n", md.id);
        System.out.println("  ├─────────────────────────────────────────────────────────┤");
        printWrapped("Description", md.description != null ? md.description : "—");
        System.out.println("  ├─────────────────────────────────────────────────────────┤");
        printField("Category",        md.category);
        printField("AP Cost",          md.apCost);
        printField("Unleash Point",    md.unleashPoint + " of " + md.apCost);
        printField("Base Power",       md.basePower == 0 ? "—" : md.basePower);
        printField("Base Accuracy",    md.neverMiss ? "Cannot miss" : String.format("%.0f%%", md.baseAccuracy * 100));
        printField("CE Cost (base)",   md.baseCeCost == 0 ? "—" : md.baseCeCost);
        printField("CE Cost (min)",    md.minCeCost == 0 ? "—" : md.minCeCost);
        printField("CE Cost (max)",    md.maxCeCost == 0 ? "—" : md.maxCeCost);
        printField("Interrupt",        md.interruptType);
        printField("Defense Type",     md.defenseType);
        if (md.defenseBuffAmount > 0) {
            printField("Def Buff Amt", md.defenseBuffAmount);
            printField("Def Buff Dur", md.defenseBuffDuration == -1 ? "full round" : md.defenseBuffDuration + " ticks");
        }
        printField("BF Eligible",      isBfEligible(md) ? "Yes ★" : "No");
        printField("Guaranteed Move",   md.isGuaranteedMove ? "Yes" : "No");
        if (md.requiredTechniqueId != null)
            printField("Requires Tech", md.requiredTechniqueId);
        if (md.prerequisites != null && !md.prerequisites.isEmpty())
            printField("Prerequisites", md.prerequisites.toString());
        if (md.onHitEffects != null && !md.onHitEffects.isEmpty())
            printField("On-Hit Effects", formatEffects(md.onHitEffects));
        if (md.selfEffects != null && !md.selfEffects.isEmpty())
            printField("Self Effects",  formatEffects(md.selfEffects));
        System.out.println("  └─────────────────────────────────────────────────────────┘");
    }

    // =========================================================================
    // New move
    // =========================================================================

    private void newMove() {
        System.out.println();
        System.out.println("  ─── NEW MOVE ─────────────────────────────────────────────");

        MoveData md = new MoveData();
        md.id = promptNonEmpty("Move ID (unique, e.g. BLOOD_SLASH): ").toUpperCase().replace(" ", "_");

        if (repo.exists(md.id)) {
            System.out.println("  A move with that ID already exists. Use Edit instead.");
            return;
        }

        fillMoveFields(md);
        validateAndSave(md);
    }

    // =========================================================================
    // Edit move
    // =========================================================================

    private void editMove() {
        String id = promptMoveId("Enter move ID to edit: ");
        if (id == null) return;

        Optional<MoveData> found = repo.findById(id);
        if (found.isEmpty()) {
            System.out.println("  Move not found: " + id);
            return;
        }

        MoveData md = found.get();
        System.out.println();
        System.out.println("  Editing: " + md.name + " (" + md.id + ")");
        System.out.println("  Press ENTER to keep current value.");
        System.out.println();

        fillMoveFields(md);
        validateAndSave(md);
    }

    // =========================================================================
    // Delete move
    // =========================================================================

    private void deleteMove() {
        String id = promptMoveId("Enter move ID to delete: ");
        if (id == null) return;

        repo.findById(id).ifPresentOrElse(md -> {
            System.out.printf("  Delete '%s' (%s)? (y/N): ", md.name, md.id);
            String confirm = sc.nextLine().trim();
            if (confirm.equalsIgnoreCase("y")) {
                repo.delete(id);
                persistNow();
                System.out.println("  Deleted.");
            } else {
                System.out.println("  Cancelled.");
            }
        }, () -> System.out.println("  Move not found: " + id));
    }

    // =========================================================================
    // Field editing — the core of the editor
    // =========================================================================

    /**
     * Interactively fill all editable fields of a MoveData.
     * Pressing ENTER keeps the existing/default value.
     */
    private void fillMoveFields(MoveData md) {
        System.out.println("  ─── Basic Info ───────────────────────────────────────────");
        md.name        = promptWithDefault("Name",        md.name);
        md.description = promptWithDefault("Description", md.description != null ? md.description : "");

        System.out.println();
        System.out.println("  ─── Category ─────────────────────────────────────────────");
        System.out.println("  Options: PHYSICAL, INNATE_TECHNIQUE, NON_INNATE_TECHNIQUE,");
        System.out.println("           PHYSICAL_CURSED_ENERGY, PHYSICAL_INNATE_TECHNIQUE,");
        System.out.println("           PHYSICAL_NON_INNATE_TECHNIQUE, INNATE_NON_INNATE_TECHNIQUE,");
        System.out.println("           PHYSICAL_INNATE_NON_INNATE_TECHNIQUE, UTILITY, DEFENSIVE");
        md.category    = promptEnum("Category", md.category, MoveCategory.class);

        System.out.println();
        System.out.println("  ─── AP Timeline ──────────────────────────────────────────");
        md.apCost       = promptInt("AP Cost (5–100)",  md.apCost,       5, 100);
        md.unleashPoint = promptInt("Unleash Point (1–" + md.apCost + ")", md.unleashPoint, 1, md.apCost);

        System.out.println();
        System.out.println("  ─── Damage ───────────────────────────────────────────────");
        md.basePower    = promptInt("Base Power (0 = non-damaging)", md.basePower, 0, 9999);
        String accInput = promptWithDefault("Base Accuracy % (1–100, or 'never' for cannot-miss)",
            md.neverMiss ? "never" : String.valueOf((int)(md.baseAccuracy * 100)));
        if (accInput.equalsIgnoreCase("never")) {
            md.neverMiss    = true;
            md.baseAccuracy = 1.0;
        } else {
            md.neverMiss    = false;
            try {
                md.baseAccuracy = Math.min(100, Math.max(1, Integer.parseInt(accInput))) / 100.0;
            } catch (NumberFormatException e) {
                System.out.println("  Invalid — keeping previous accuracy.");
            }
        }

        System.out.println();
        System.out.println("  ─── Cursed Energy Cost ───────────────────────────────────");
        md.baseCeCost = promptInt("Base CE Cost (0 = no CE)",  md.baseCeCost, 0, 9999);
        if (md.baseCeCost > 0) {
            md.minCeCost = promptInt("Min CE Cost (efficiency floor)", md.minCeCost, 0, md.baseCeCost);
            md.maxCeCost = promptInt("Max CE Cost (efficiency ceiling, >= base)", md.maxCeCost, md.baseCeCost, 9999);
        } else {
            md.minCeCost = 0;
            md.maxCeCost = 0;
        }

        System.out.println();
        System.out.println("  ─── Interrupt ─────────────────────────────────────────────");
        System.out.println("  Options: NONE, KNOCK_CURRENT_BLOCK, KNOCK_NEXT_BLOCK");
        md.interruptType = promptEnum("Interrupt Type", md.interruptType, InterruptType.class);

        System.out.println();
        System.out.println("  ─── Defensive Properties ─────────────────────────────────");
        System.out.println("  Options: NONE, STAT_BUFF, FULL_BLOCK");
        md.defenseType = promptEnum("Defense Type", md.defenseType, DefenseType.class);
        if (!md.defenseType.equals("NONE")) {
            md.defenseBuffAmount   = promptInt("Defense Buff Amount (0 if FULL_BLOCK)",   md.defenseBuffAmount,   0, 9999);
            md.defenseBuffDuration = promptInt("Defense Buff Duration (-1 = full round)", md.defenseBuffDuration, -1, 9999);
        }

        System.out.println();
        System.out.println("  ─── Restrictions ─────────────────────────────────────────");
        String reqTech = promptWithDefault(
            "Required Technique ID (e.g. SHRINE, or blank for none)",
            md.requiredTechniqueId != null ? md.requiredTechniqueId : "");
        md.requiredTechniqueId = reqTech.isBlank() ? null : reqTech.toUpperCase();

        System.out.println();
        System.out.println("  ─── Prerequisites ────────────────────────────────────────");
        System.out.println("  Current: " + (md.prerequisites != null ? md.prerequisites : "none"));
        System.out.println("  Enter stat prerequisites as  stat=value  pairs (comma separated),");
        System.out.println("  e.g.  strength=80,speed=60  — or press ENTER to keep.");
        System.out.println("  Valid stats: vitality, strength, durability, speed,");
        System.out.println("               cursedenergyreserves, cursedenergyefficiency,");
        System.out.println("               cursedenergyoutput, jujutsuskill, combatability, cursedtechniquemastery");
        String prereqInput = prompt("  Prerequisites: ").trim();
        if (!prereqInput.isBlank()) {
            Map<String, Integer> prereqs = new LinkedHashMap<>();
            try {
                for (String part : prereqInput.split(",")) {
                    String[] kv = part.trim().split("=");
                    prereqs.put(kv[0].trim().toLowerCase(), Integer.parseInt(kv[1].trim()));
                }
                md.prerequisites = prereqs.isEmpty() ? null : prereqs;
            } catch (Exception e) {
                System.out.println("  Parse error — keeping previous prerequisites.");
            }
        }

        System.out.println();
        System.out.println("  ─── On-Hit Status Effects ────────────────────────────────");
        System.out.println("  Current: " + formatEffectsNullable(md.onHitEffects));
        md.onHitEffects = promptEffects("on-hit", md.onHitEffects);

        System.out.println();
        System.out.println("  ─── Self Status Effects ──────────────────────────────────");
        System.out.println("  Current: " + formatEffectsNullable(md.selfEffects));
        md.selfEffects = promptEffects("self", md.selfEffects);

        System.out.println();
        String isGuaranteed = promptWithDefault("Guaranteed move (available to all, y/N)",
            md.isGuaranteedMove ? "y" : "n");
        md.isGuaranteedMove = isGuaranteed.equalsIgnoreCase("y");
    }

    /**
     * Prompt to build a list of status effects.
     * Returns null if user presses ENTER with no input (keep existing).
     * Returns empty list if user enters "clear".
     */
    private List<MoveData.StatusEffectData> promptEffects(
            String label, List<MoveData.StatusEffectData> existing) {
        System.out.println("  Available effects: STUN, POISON, BIND, CURSED_SEAL, CE_SUPPRESSION,");
        System.out.println("                     POWER_UP, DEFENSE_UP, FOCUS, SPEED_UP, MARKED, AP_DRAIN, BARRIER");
        System.out.println("  Format: TYPE:duration:magnitude (e.g. POISON:3:10.0,STUN:1:0)");
        System.out.println("  Enter 'clear' to remove all, ENTER to keep existing.");
        String input = prompt("  Effects: ").trim();

        if (input.isBlank()) return existing; // keep
        if (input.equalsIgnoreCase("clear")) return null;

        List<MoveData.StatusEffectData> effects = new ArrayList<>();
        try {
            for (String part : input.split(",")) {
                String[] pieces = part.trim().split(":");
                MoveData.StatusEffectData ed = new MoveData.StatusEffectData();
                ed.type            = pieces[0].trim().toUpperCase();
                ed.durationRounds  = pieces.length > 1 ? Integer.parseInt(pieces[1].trim()) : 1;
                ed.magnitude       = pieces.length > 2 ? Double.parseDouble(pieces[2].trim()) : 1.0;
                effects.add(ed);
            }
        } catch (Exception e) {
            System.out.println("  Parse error — keeping existing effects.");
            return existing;
        }
        return effects.isEmpty() ? null : effects;
    }

    // =========================================================================
    // Validation and persistence
    // =========================================================================

    private void validateAndSave(MoveData md) {
        // Quick validation via Move.Builder
        try {
            md.toMove(); // throws if invalid
        } catch (Exception e) {
            System.out.println("  [VALIDATION ERROR] " + e.getMessage());
            System.out.println("  Move was NOT saved. Fix the issue and try again.");
            return;
        }
        repo.upsert(md);
        persistNow();
        System.out.println("  Saved: " + md.name + " (" + md.id + ")");
    }

    private void persistNow() {
        try {
            repo.save();
        } catch (IOException e) {
            System.out.println("  [ERROR] Could not write to disk: " + e.getMessage());
        }
    }

    // =========================================================================
    // Prompt helpers
    // =========================================================================

    private String prompt(String label) {
        System.out.print(label);
        return sc.nextLine();
    }

    private String promptNonEmpty(String label) {
        while (true) {
            String s = prompt("  " + label).trim();
            if (!s.isBlank()) return s;
            System.out.println("  Value cannot be empty.");
        }
    }

    private String promptWithDefault(String label, String current) {
        String display = (current != null && !current.isBlank()) ? " [" + current + "]" : " [blank]";
        String input   = prompt("  " + label + display + ": ").trim();
        return input.isBlank() ? (current != null ? current : "") : input;
    }

    private int promptInt(String label, int current, int min, int max) {
        while (true) {
            String input = prompt("  " + label + " [" + current + "]: ").trim();
            if (input.isBlank()) return current;
            try {
                int v = Integer.parseInt(input);
                if (v >= min && v <= max) return v;
                System.out.printf("  Must be between %d and %d.%n", min, max);
            } catch (NumberFormatException e) {
                System.out.println("  Enter a whole number.");
            }
        }
    }

    private <E extends Enum<E>> String promptEnum(String label, String current, Class<E> enumClass) {
        while (true) {
            String input = promptWithDefault(label, current).toUpperCase().replace(" ", "_");
            try {
                Enum.valueOf(enumClass, input);
                return input;
            } catch (IllegalArgumentException e) {
                System.out.println("  Invalid value. Choose from the listed options.");
            }
        }
    }

    private String promptMoveId(String label) {
        listMoves();
        System.out.println();
        String id = prompt("  " + label).trim().toUpperCase();
        return id.isBlank() ? null : id;
    }

    // =========================================================================
    // Display helpers
    // =========================================================================

    private void printField(String label, Object value) {
        System.out.printf("  │  %-16s: %-39s│%n", label, truncate(String.valueOf(value), 39));
    }

    private void printWrapped(String label, String text) {
        System.out.printf("  │  %-16s:%n", label);
        if (text == null || text.isBlank()) {
            System.out.printf("  │    %-53s│%n", "—");
            return;
        }
        while (text.length() > 53) {
            int cut = text.lastIndexOf(' ', 53);
            if (cut <= 0) cut = 53;
            System.out.printf("  │    %-53s│%n", text.substring(0, cut));
            text = text.substring(cut).trim();
        }
        if (!text.isEmpty()) System.out.printf("  │    %-53s│%n", text);
    }

    private boolean isBfEligible(MoveData md) {
        try {
            return MoveCategory.valueOf(md.category).isBlackFlashEligible();
        } catch (Exception e) { return false; }
    }

    private String formatEffects(List<MoveData.StatusEffectData> effects) {
        if (effects == null || effects.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (var e : effects) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.type).append("(").append(e.durationRounds).append("r, x").append(e.magnitude).append(")");
        }
        return sb.toString();
    }

    private String formatEffectsNullable(List<MoveData.StatusEffectData> effects) {
        return (effects == null || effects.isEmpty()) ? "none" : formatEffects(effects);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
