package com.jjktbf.editor;

import com.jjktbf.model.move.*;

import java.io.IOException;
import java.util.*;

/**
 * Interactive terminal move editor.
 *
 * IDs are auto-assigned 6-digit numbers (000000, 000001, …).
 * Deleting a move resequences all IDs so there are never gaps.
 *
 * Category is built by selecting tags interactively.
 * Available tags: PHYSICAL, CURSED_ENERGY, INNATE_TECHNIQUE, NON_INNATE_TECHNIQUE,
 *                 ATTACK, UTILITY, DEFENSIVE
 *
 * All changes are written immediately to data/moves/all_moves.json.
 */
public class MoveEditorMain {

    private static final String DATA_DIR = "data/moves";

    // All tags the editor exposes
    private static final List<String> ALL_TAGS = List.of(
        "PHYSICAL", "CURSED_ENERGY", "INNATE_TECHNIQUE", "NON_INNATE_TECHNIQUE",
        "ATTACK", "UTILITY", "DEFENSIVE"
    );

    private static final List<String> EFFECT_TYPES = List.of(
        "STUN", "POISON", "BIND", "CURSED_SEAL", "CE_SUPPRESSION",
        "POWER_UP", "DEFENSE_UP", "FOCUS", "SPEED_UP", "MARKED", "AP_DRAIN", "BARRIER"
    );

    private final Scanner        sc;
    private final EditorIO       io;
    private final MoveRepository repo;

    public MoveEditorMain() {
        this.sc   = new Scanner(System.in);
        this.io   = new EditorIO(sc);
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
        System.out.println("║              JJK MOVE EDITOR  v2.0                      ║");
        System.out.printf ("║  Data file: %-44s║%n", repo.getDataFile().getPath());
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.printf ("  %d moves loaded.%n%n", repo.size());

        while (true) {
            printMainMenu();
            String choice = prompt("> ").toUpperCase().trim();

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
        System.out.println();
        System.out.println("  ──────────────────────────────────────────────────────────");
    }

    // =========================================================================
    // List
    // =========================================================================

    private void listMoves() {
        List<MoveData> all = repo.getAll();
        System.out.println();
        System.out.printf("  %-8s %-28s %-20s %-5s %-5s%n",
            "ID", "Name", "Tags", "AP", "Pwr");
        System.out.println("  " + "─".repeat(72));
        for (MoveData md : all) {
            String tagStr = md.tags != null ? String.join(",", md.tags) : "—";
            System.out.printf("  %-8s %-28s %-20s %-5d %-5s%n",
                md.id,
                truncate(md.name, 28),
                truncate(tagStr, 20),
                md.apCost,
                md.basePower == 0 ? "N/A" : String.valueOf(md.basePower));
        }
        System.out.printf("  %d moves total.%n", all.size());
    }

    // =========================================================================
    // View
    // =========================================================================

    private void viewMove() {
        String id = pickMoveById("Enter move ID to view: ");
        if (id == null) return;
        repo.findById(id).ifPresentOrElse(
            this::printMoveDetail,
            () -> System.out.println("  Move not found: " + id)
        );
    }

    private void printMoveDetail(MoveData md) {
        String tagStr  = md.tags != null ? String.join(", ", md.tags) : "—";
        String catName = "";
        try { catName = " → " + md.derivedCategory().name(); } catch (Exception ignored) {}

        System.out.println();
        System.out.println("  ┌─────────────────────────────────────────────────────────┐");
        System.out.printf ("  │  %-55s│%n", md.name);
        System.out.printf ("  │  ID: %-52s│%n", md.id);
        System.out.println("  ├─────────────────────────────────────────────────────────┤");
        printWrapped("Description", md.description != null ? md.description : "—");
        System.out.println("  ├─────────────────────────────────────────────────────────┤");
        printField("Tags",           tagStr + catName);
        printField("AP Cost",        md.apCost);
        printField("Unleash Point",  md.unleashPoint + " of " + md.apCost);
        boolean isAttack = md.tags != null && md.tags.contains("ATTACK");
        if (isAttack) {
            printField("Base Power",     md.basePower);
            printField("Base Accuracy", md.neverMiss ? "Cannot miss"
                                         : String.format("%.0f%%", md.baseAccuracy * 100));
        } else {
            printField("Base Power",     "N/A");
            printField("Base Accuracy", "N/A");
        }
        printField("CE Cost (base)", md.baseCeCost == 0 ? "N/A" : md.baseCeCost);
        if (md.baseCeCost > 0) {
            printField("CE Cost (min)", md.minCeCost);
            printField("CE Cost (max)", md.maxCeCost);
        }
        printField("Interrupt",      md.interruptType);
        printField("Defense Type",   md.defenseType);
        if ("BLOCK".equals(md.defenseType) || "FLAT_BLOCK".equals(md.defenseType)) {
            if (md.blockDuration == -1)
                printField("Block Duration", "end of round");
            else if (md.blockDuration == 0)
                printField("Block Duration", "uses AP cost");
            else
                printField("Block Duration", md.blockDuration + " ticks");
            if (md.blockAffectedTags != null)
                printField("Block Tags", md.blockAffectedTags.toString());
            if ("BLOCK".equals(md.defenseType))
                printField("Block Reduction", md.blockDamageReduction + "%");
            else
                printField("Flat Reduction", "-" + md.blockFlatReduction + " dmg");
        }
        try {
            boolean bf = md.derivedCategory().isBlackFlashEligible();
            printField("BF Eligible", bf ? "Yes ★" : "No");
        } catch (Exception ignored) {}
        printField("Free",        md.isGuaranteedMove ? "Yes" : "No");
        if (md.requiredTechniqueName != null)
            printField("Requires",   md.requiredTechniqueName);
        if (md.prerequisites != null && !md.prerequisites.isEmpty())
            printField("Prereqs",    md.prerequisites.toString());
        if (md.onHitEffects != null && !md.onHitEffects.isEmpty())
            printField("On-Hit",     formatEffects(md.onHitEffects));
        if (md.selfEffects != null && !md.selfEffects.isEmpty())
            printField("Self FX",    formatEffects(md.selfEffects));
        System.out.println("  └─────────────────────────────────────────────────────────┘");
    }

    // =========================================================================
    // New move
    // =========================================================================

    private void newMove() {
        System.out.println();
        System.out.println("  ─── NEW MOVE ─────────────────────────────────────────────");
        System.out.printf ("  Next ID will be: %s%n%n", repo.nextId());

        MoveData md = new MoveData();
        // Set safe defaults
        md.interruptType = "NONE";
        md.defenseType   = "NONE";
        md.baseAccuracy  = 1.0;

        fillMoveFields(md);
        validateAndAdd(md);
    }

    // =========================================================================
    // Edit move
    // =========================================================================

    private void editMove() {
        String id = pickMoveById("Enter move ID to edit: ");
        if (id == null) return;

        Optional<MoveData> found = repo.findById(id);
        if (found.isEmpty()) {
            System.out.println("  Move not found: " + id);
            return;
        }

        MoveData md = found.get();
        System.out.println();
        System.out.println("  Editing: " + md.name + " (ID: " + md.id + ")");
        System.out.println("  Press ENTER on any field to keep the current value.");
        System.out.println();

        fillMoveFields(md);
        validateAndUpdate(md);
    }

    // =========================================================================
    // Delete move
    // =========================================================================

    private void deleteMove() {
        String id = pickMoveById("Enter move ID to delete: ");
        if (id == null) return;

        repo.findById(id).ifPresentOrElse(md -> {
            System.out.printf("  Delete '%s' (ID: %s)? All subsequent IDs will shift. (y/N): ",
                md.name, md.id);
            if (sc.nextLine().trim().equalsIgnoreCase("y")) {
                repo.delete(id);
                persistNow();
                System.out.println("  Deleted and IDs resequenced.");
            } else {
                System.out.println("  Cancelled.");
            }
        }, () -> System.out.println("  Move not found: " + id));
    }

    // =========================================================================
    // Field editor
    // =========================================================================

    private void fillMoveFields(MoveData md) {

        // ── Basic info ────────────────────────────────────────────────────────
        sep("Basic Info");
        md.name        = promptWithDefault("Name",        md.name);
        md.description = promptWithDefault("Description", md.description != null ? md.description : "");

        // ── Tags ──────────────────────────────────────────────────────────────
        System.out.println();
        sep("Tags");
        System.out.println("  Current tags: " + (md.tags != null ? md.tags : "none"));
        md.tags = promptTags(md.tags);

        // ── AP timeline ───────────────────────────────────────────────────────
        System.out.println();
        sep("AP Timeline");
        md.apCost       = promptIntUnbounded("AP Cost (no minimum enforced in editor)", md.apCost, 0);
        md.unleashPoint = promptInt("Unleash Point (1–" + Math.max(md.apCost, 1) + ")",
                                    md.unleashPoint > 0 ? md.unleashPoint : 1,
                                    1, Math.max(md.apCost, 1));

        // ── Damage (only for ATTACK moves) ────────────────────────────────────────────
        boolean isAttack = md.tags != null && md.tags.contains("ATTACK");
        if (isAttack) {
            System.out.println();
            sep("Damage  (attacks only — utility moves have 0 power)");
            md.basePower = promptIntUnbounded("Base Power", md.basePower, 0);

            String accInput = promptWithDefault(
                "Base Accuracy % (1–100, or 'never' for cannot-miss)",
                md.neverMiss ? "never" : String.valueOf((int) (md.baseAccuracy * 100)));
            if (accInput.equalsIgnoreCase("never")) {
                md.neverMiss    = true;
                md.baseAccuracy = 1.0;
            } else {
                md.neverMiss = false;
                try {
                    md.baseAccuracy = Math.min(100, Math.max(1, Integer.parseInt(accInput))) / 100.0;
                } catch (NumberFormatException e) {
                    System.out.println("  Invalid — keeping previous value.");
                }
            }
        } else {
            md.basePower = 0;
            md.baseAccuracy = 1.0;
            md.neverMiss = false;
        }

        // ── CE Cost ───────────────────────────────────────────────────────────
        System.out.println();
        sep("Cursed Energy Cost");
        md.baseCeCost = promptIntUnbounded("Base CE Cost (0 = no CE)", md.baseCeCost, 0);
        if (md.baseCeCost > 0) {
            md.minCeCost = promptInt("Min CE Cost (efficiency floor)", md.minCeCost, 0, md.baseCeCost);
            md.maxCeCost = promptIntUnbounded("Max CE Cost (efficiency ceiling, >= base)",
                               Math.max(md.maxCeCost, md.baseCeCost), md.baseCeCost);
        } else {
            md.minCeCost = 0;
            md.maxCeCost = 0;
        }

        // ── Interrupt ─────────────────────────────────────────────────────────
        System.out.println();
        sep("Interrupt");
        System.out.println("  Options: NONE  KNOCK_CURRENT_BLOCK  KNOCK_NEXT_BLOCK");
        md.interruptType = promptEnum("Interrupt Type", md.interruptType, InterruptType.class);

        // ── Defensive ─────────────────────────────────────────────────────────
        System.out.println();
        sep("Defense Type");
        System.out.println("  Options: NONE  BLOCK  FLAT_BLOCK");
        System.out.println("  BLOCK:      reduces incoming damage by a % (0–100).");
        System.out.println("  FLAT_BLOCK: reduces incoming damage by a flat amount.");
        md.defenseType = promptEnum("Defense Type", md.defenseType, DefenseType.class);
        if ("BLOCK".equals(md.defenseType) || "FLAT_BLOCK".equals(md.defenseType)) {
            System.out.println();
            sep("Block Settings");
            md.blockDuration = promptInt(
                "Duration in AP ticks (0 = use move's AP cost, -1 = end of round)",
                md.blockDuration, -1, 99999);
            String tagsInput = promptWithDefault(
                "Affected tags (comma-separated: PHYSICAL, CURSED_ENERGY, INNATE_TECHNIQUE, NON_INNATE_TECHNIQUE; blank = all)",
                md.blockAffectedTags != null ? String.join(", ", md.blockAffectedTags) : "");
            md.blockAffectedTags = tagsInput.isBlank() ? null
                : java.util.Arrays.asList(tagsInput.split(",")).stream()
                    .map(String::trim).filter(s -> !s.isBlank()).toList();
            if ("BLOCK".equals(md.defenseType)) {
                md.blockDamageReduction = promptInt(
                    "Damage reduction % (100 = full block, 50 = half damage)",
                    md.blockDamageReduction, 0, 100);
                md.blockFlatReduction = 0;
            } else {
                md.blockFlatReduction = promptIntUnbounded(
                    "Flat damage reduction (e.g. 30 = subtract 30 from incoming damage)",
                    md.blockFlatReduction, 0);
                md.blockDamageReduction = 100;
            }
        } else {
            md.defenseType = "NONE";
            md.blockDuration = 0;
            md.blockAffectedTags = null;
            md.blockDamageReduction = 100;
            md.blockFlatReduction = 0;
        }

        // ── Technique restriction ──────────────────────────────────────────────
        System.out.println();
        sep("Technique Restriction");
        String tech = promptWithDefault(
            "Required technique name (e.g. Shrine, Blood Manipulation — or blank for none)",
            md.requiredTechniqueName != null ? md.requiredTechniqueName : "");
        md.requiredTechniqueName = tech.isBlank() ? null : tech;

        // ── Prerequisites ─────────────────────────────────────────────────────
        System.out.println();
        sep("Stat Prerequisites");
        System.out.println("  Current: " + (md.prerequisites != null ? md.prerequisites : "none"));
        System.out.println("  Format:  stat=value,stat=value   e.g.  strength=80,speed=60");
        System.out.println("  Valid stats: vitality strength durability speed");
        System.out.println("               cursedenergyreserves cursedenergyefficiency");
        System.out.println("               cursedenergyoutput jujutsuskill combatability cursedtechniquemastery");
        System.out.println("  Enter 'clear' to remove, ENTER to keep.");
        String prereqInput = prompt("  Prerequisites: ").trim();
        if (prereqInput.equalsIgnoreCase("clear")) {
            md.prerequisites = null;
        } else if (!prereqInput.isBlank()) {
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

        // ── On-hit effects ─────────────────────────────────────────────────────
        System.out.println();
        sep("On-Hit Status Effects");
        System.out.println("  Current: " + formatEffectsNullable(md.onHitEffects));
        md.onHitEffects = promptEffects(md.onHitEffects);

        // ── Self effects ───────────────────────────────────────────────────────
        System.out.println();
        sep("Self Status Effects  (applied to user on unleash)");
        System.out.println("  Current: " + formatEffectsNullable(md.selfEffects));
        md.selfEffects = promptEffects(md.selfEffects);

        // ── Free move (doesn't use a slot) ────────────────────────────
        System.out.println();
        String isF = promptWithDefault("Free move (doesn't use a slot, y/N)",
                                       md.isGuaranteedMove ? "y" : "n");
        md.isGuaranteedMove = isF.equalsIgnoreCase("y");
    }

    // =========================================================================
    // Tag picker — interactive multi-select loop
    // =========================================================================

    /**
     * Let the user add tags one by one. Enter 0 to finish.
     * Existing tags are shown; user can reset or keep them.
     */
    private List<String> promptTags(List<String> existing) {
        List<String> chosen = existing != null ? new ArrayList<>(existing) : new ArrayList<>();

        System.out.println("  ─ Available tags ─────────────────────────────────────────");
        for (int i = 0; i < ALL_TAGS.size(); i++) {
            System.out.printf("   %d  %s%n", i + 1, ALL_TAGS.get(i));
        }
        System.out.println("   0  Done");
        System.out.println("   C  Clear all tags");
        System.out.println();

        while (true) {
            System.out.println("  Current tags: " + (chosen.isEmpty() ? "[none]" : String.join(", ", chosen)));
            String input = prompt("  Add tag (#), C to clear, 0 to finish: ").trim().toUpperCase();

            if (input.equals("0")) break;

            if (input.equals("C")) {
                chosen.clear();
                continue;
            }

            try {
                int idx = Integer.parseInt(input) - 1;
                if (idx < 0 || idx >= ALL_TAGS.size()) {
                    System.out.println("  Invalid number.");
                    continue;
                }
                String tag = ALL_TAGS.get(idx);
                if (chosen.contains(tag)) {
                    System.out.println("  Tag already added: " + tag);
                } else {
                    chosen.add(tag);
                }
            } catch (NumberFormatException e) {
                System.out.println("  Enter a number, C, or 0.");
            }
        }

        return chosen.isEmpty() ? null : chosen;
    }

    // =========================================================================
    // Status effect picker
    // =========================================================================

    private List<MoveData.StatusEffectData> promptEffects(List<MoveData.StatusEffectData> existing) {
        System.out.println("  ─ Available effect types ─────────────────────────────────");
        for (int i = 0; i < EFFECT_TYPES.size(); i++) {
            System.out.printf("   %-3d %s%n", i + 1, EFFECT_TYPES.get(i));
        }
        System.out.println("   0   Done / no effects");
        System.out.println("   C   Clear all effects");
        System.out.println("  (ENTER keeps current effects)");
        System.out.println();

        // Peek — if blank, keep existing
        System.out.print("  Press ENTER to keep, or select an option: ");
        String peek = sc.nextLine().trim().toUpperCase();

        if (peek.isBlank()) return existing;
        if (peek.equals("C") || peek.equals("0")) return null;

        // Otherwise treat peek as first selection, then loop
        List<MoveData.StatusEffectData> effects = new ArrayList<>();

        String input = peek;
        while (true) {
            if (input.equals("0")) break;
            if (input.equals("C")) { effects.clear(); break; }

            try {
                int idx = Integer.parseInt(input) - 1;
                if (idx < 0 || idx >= EFFECT_TYPES.size()) {
                    System.out.println("  Invalid number.");
                } else {
                    String typeName = EFFECT_TYPES.get(idx);
                    int dur = promptInt("  Duration (rounds, -1 = permanent)", 1, -1, 999);
                    double mag = promptDouble("  Magnitude", 1.0);
                    MoveData.StatusEffectData ed = new MoveData.StatusEffectData();
                    ed.type           = typeName;
                    ed.durationRounds = dur;
                    ed.magnitude      = mag;
                    effects.add(ed);
                    System.out.println("  Added: " + typeName + " (" + dur + "r, x" + mag + ")");
                }
            } catch (NumberFormatException e) {
                System.out.println("  Enter a number.");
            }

            System.out.print("  Add another (# / 0 to finish): ");
            input = sc.nextLine().trim().toUpperCase();
        }

        return effects.isEmpty() ? null : effects;
    }

    // =========================================================================
    // Validation and persistence
    // =========================================================================

    private void validateAndAdd(MoveData md) {
        if (md.id == null || md.id.isBlank()) {
            md.id = repo.nextId();  // assign BEFORE validation
        }
        try {
            md.toMove();
        } catch (Exception e) {
            System.out.println("  [VALIDATION ERROR] " + e.getMessage());
            System.out.println("  Move was NOT saved.");
            return;
        }
        repo.add(md);
        persistNow();
        System.out.println("  Saved as ID: " + md.id + "  —  " + md.name);
    }

    private void validateAndUpdate(MoveData md) {
        try {
            md.toMove();
        } catch (Exception e) {
            System.out.println("  [VALIDATION ERROR] " + e.getMessage());
            System.out.println("  Move was NOT saved.");
            return;
        }
        repo.update(md);
        persistNow();
        System.out.println("  Updated: " + md.name + " (ID: " + md.id + ")");
    }

    private void persistNow() {
        try {
            repo.save();
        } catch (IOException e) {
            System.out.println("  [ERROR] Could not write to disk: " + e.getMessage());
        }
    }

    // =========================================================================
    // Prompt helpers — delegates to EditorIO
    // =========================================================================

    private String prompt(String label)                                          { return io.prompt(label); }
    private String promptWithDefault(String label, String cur)                   { return io.promptWithDefault(label, cur); }
    private int    promptInt(String label, int cur, int min, int max)            { return io.promptInt(label, cur, min, max); }
    private int    promptIntUnbounded(String label, int cur, int min)            { return io.promptIntUnbounded(label, cur, min); }
    private double promptDouble(String label, double cur)                        { return io.promptDouble(label, cur); }
    private <E extends Enum<E>> String promptEnum(String label, String cur, Class<E> cls) { return io.promptEnum(label, cur, cls); }

    private String pickMoveById(String label) {
        listMoves();
        System.out.println();
        String id = prompt("  " + label).trim();
        if (id.isBlank()) return null;
        try {
            int n = Integer.parseInt(id);
            id = MoveRepository.formatId(n);
        } catch (NumberFormatException ignored) {}
        return id;
    }

    // =========================================================================
    // Display helpers — delegates to EditorIO
    // =========================================================================

    private void sep(String title)                          { io.sep(title); }
    private void printField(String label, Object value)     { io.printField(label, value); }
    private void printWrapped(String label, String text)    { io.printWrapped(label, text); }
    private static String truncate(String s, int max)       { return EditorIO.truncate(s, max); }

    private String formatEffects(List<MoveData.StatusEffectData> effects) {
        if (effects == null || effects.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (var e : effects) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.type).append("(").append(e.durationRounds).append("r,x").append(e.magnitude).append(")");
        }
        return sb.toString();
    }

    private String formatEffectsNullable(List<MoveData.StatusEffectData> effects) {
        return (effects == null || effects.isEmpty()) ? "none" : formatEffects(effects);
    }
}
