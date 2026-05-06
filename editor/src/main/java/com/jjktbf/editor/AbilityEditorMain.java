package com.jjktbf.editor;

import com.jjktbf.model.character.*;
import com.jjktbf.model.move.MoveRepository;
import com.jjktbf.model.move.StatusEffectType;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interactive terminal ability editor.
 *
 * Menu:
 *   L  List all abilities
 *   V  View ability details  (with highlighted keywords in mechanicText)
 *   N  New ability
 *   E  Edit ability
 *   D  Delete ability  (IDs resequence)
 *   Q  Quit
 *
 * Ability structure:
 *   - name, flavourText (in-universe), mechanicText (with ALL_CAPS keywords)
 *   - category: PASSIVE / ACTIVE
 *   - sourceType: CHARACTER / TECHNIQUE / MOVE / STAT_THRESHOLD
 *   - sourceValue: technique name, move ID, or "stat>=value"
 *   - effects: list of effect primitives (add one at a time)
 *   - [ACTIVE only] sub-type: QUEUED (references a move) / TRIGGERED (condition + effects)
 *
 * All changes write immediately to data/abilities/all_abilities.json.
 */
public class AbilityEditorMain {

    private static final String ABILITY_DATA_DIR = "data/abilities";
    private static final String MOVE_DATA_DIR    = "data/moves";

    // ── Effect primitive labels — must stay in same order as AbilityEffectType.values() ──

    /** Human-readable label for each effect type shown in the picker menu. */
    private static final String[] EFFECT_LABELS = {
        "STAT_ADD                  — add flat amount to a stat (negative = downside)",
        "STAT_MULTIPLY             — multiply a stat by a factor (< 1.0 = reduction)",
        "STAT_DIVIDE               — divide a stat by a factor",
        "STAT_SET_VALUE            — set a stat to any specific value (0 = N/A)",
        "STAT_SET_MIN              — set a stat to 0 / N/A",
        "STAT_BONUS_POINTS         — grant extra point-buy budget (creator only)",
        "CE_COST_TO_MINIMUM        — force CE costs to their move minimum",
        "CE_COST_MULTIPLY          — multiply CE costs (0.5 = half, 2.0 = double)",
        "MOVE_ACCURACY_ADD         — add accuracy bonus/penalty to own moves",
        "MOVE_ACCURACY_MULTIPLY    — multiply own accuracy (0.5 = half)",
        "OPPONENT_ACCURACY_ADD     — add accuracy bonus/penalty to opponent moves",
        "OPPONENT_ACCURACY_MULTIPLY— multiply opponent accuracy (0.5 = half)",
        "DAMAGE_MULTIPLY           — multiply damage dealt",
        "GRANT_MOVE                — grant a move outside the slot system",
        "BF_CHANCE_ADD             — add to Black Flash proc chance (negative ok)",
        "UNLOCK_TECHNIQUE          — grant an innate technique by name",
        "MODIFY_DEFENSE            — multiply defense value",
        "MODIFY_AP_BAR             — add to AP bar size (negative ok)",
        "AUTO_STATUS_APPLY         — automatically apply a status effect",
        "LOCK_MOVE_TAG             — lock out moves with a tag (passive=perm, active=temp)",
        "COST_CE_PER_ROUND         — drain CE at the start of each round"
    };

    private static final AbilityEffectType[] EFFECT_TYPES = AbilityEffectType.values();

    private static final String[] STAT_NAMES = {
        "vitality", "strength", "durability", "speed",
        "cursedEnergyReserves", "cursedEnergyEfficiency", "cursedEnergyOutput",
        "jujutsuSkill", "combatAbility", "cursedTechniqueMastery"
    };

    private static final String[] MOVE_TAGS = {
        "PHYSICAL", "CURSED_ENERGY", "INNATE_TECHNIQUE", "NON_INNATE_TECHNIQUE",
        "ATTACK", "UTILITY", "DEFENSIVE"
    };

    private static final String[] STATUS_EFFECT_NAMES;
    static {
        StatusEffectType[] values = StatusEffectType.values();
        STATUS_EFFECT_NAMES = new String[values.length];
        for (int i = 0; i < values.length; i++) STATUS_EFFECT_NAMES[i] = values[i].name();
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final Scanner           sc;
    private final EditorIO          io;
    private final AbilityRepository abilityRepo;
    private final MoveRepository    moveRepo;

    public AbilityEditorMain() {
        this.sc          = new Scanner(System.in);
        this.io          = new EditorIO(sc);
        this.abilityRepo = new AbilityRepository(ABILITY_DATA_DIR);
        this.moveRepo    = new MoveRepository(MOVE_DATA_DIR);
    }

    public static void main(String[] args) {
        new AbilityEditorMain().run();
    }

    // =========================================================================
    // Main loop
    // =========================================================================

    private void run() {
        try {
            moveRepo.load();
            abilityRepo.load();
        } catch (IOException e) {
            System.out.println("[ERROR] Could not load data: " + e.getMessage());
            return;
        }

        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║           JJK ABILITY EDITOR  v1.0                      ║");
        System.out.printf ("║  Abilities: %-44s║%n", abilityRepo.getDataFile().getPath());
        System.out.printf ("║  Moves:     %-44s║%n", moveRepo.getDataFile().getPath());
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.printf ("  %d abilities  |  %d moves loaded.%n%n",
            abilityRepo.size(), moveRepo.size());

        while (true) {
            printMainMenu();
            String choice = prompt("> ").trim().toUpperCase();
            switch (choice) {
                case "L" -> listAbilities();
                case "V" -> viewAbility();
                case "N" -> newAbility();
                case "E" -> editAbility();
                case "D" -> deleteAbility();
                case "Q" -> { System.out.println("Goodbye."); return; }
                default  -> System.out.println("  Unknown option.");
            }
        }
    }

    private void printMainMenu() {
        System.out.println();
        System.out.println("  ─── ABILITY EDITOR ───────────────────────────────────────");
        System.out.println("   L  List all abilities");
        System.out.println("   V  View ability details");
        System.out.println("   N  New ability");
        System.out.println("   E  Edit ability");
        System.out.println("   D  Delete ability  (IDs resequence)");
        System.out.println("   Q  Quit");
        System.out.println("  ──────────────────────────────────────────────────────────");
    }

    // =========================================================================
    // List
    // =========================================================================

    private void listAbilities() {
        List<AbilityData> all = abilityRepo.getAll();
        System.out.println();
        System.out.printf("  %-8s %-26s %-8s %-14s %-16s%n",
            "ID", "Name", "Cat", "Source", "Effects");
        System.out.println("  " + "─".repeat(76));
        for (AbilityData ad : all) {
            int fx     = ad.effects != null ? ad.effects.size() : 0;
            String src = ad.sourceType != null ? ad.sourceType : "?";
            if (ad.sourceValue != null) src += "(" + truncate(ad.sourceValue, 8) + ")";
            System.out.printf("  %-8s %-26s %-8s %-14s %d effect%s%n",
                ad.id,
                truncate(ad.name, 26),
                ad.category != null ? ad.category : "?",
                truncate(src, 14),
                fx, fx == 1 ? "" : "s");
        }
        System.out.printf("  %d abilities total.%n", all.size());
    }

    // =========================================================================
    // View
    // =========================================================================

    private void viewAbility() {
        String id = pickAbilityById("Enter ability ID to view: ");
        if (id == null) return;
        abilityRepo.findById(id).ifPresentOrElse(
            this::printAbilityDetail,
            () -> System.out.println("  Not found: " + id)
        );
    }

    private void printAbilityDetail(AbilityData ad) {
        System.out.println();
        System.out.println("  ┌─────────────────────────────────────────────────────────┐");
        System.out.printf ("  │  %-55s│%n", ad.name != null ? ad.name : "—");
        System.out.printf ("  │  ID: %-52s│%n", ad.id);
        System.out.printf ("  │  %-55s│%n", ad.summaryLine());
        System.out.println("  ├─────────────────────────────────────────────────────────┤");

        // Flavour text (word-wrapped)
        System.out.println("  │  FLAVOUR TEXT:                                          │");
        printWrapped(ad.flavourText != null ? ad.flavourText : "—");

        System.out.println("  ├─────────────────────────────────────────────────────────┤");

        // Mechanic text with keyword highlighting (*** around ALL_CAPS words)
        System.out.println("  │  MECHANICS:                                             │");
        String mechanic = ad.mechanicText != null ? ad.mechanicText : "—";
        printWrapped(highlightKeywords(mechanic));

        System.out.println("  ├─────────────────────────────────────────────────────────┤");

        // Source
        System.out.printf ("  │  Source: %-47s│%n", formatSource(ad));

        // Active-only info
        if ("ACTIVE".equalsIgnoreCase(ad.category)) {
            System.out.printf("  │  Sub-type: %-45s│%n",
                ad.activeSubType != null ? ad.activeSubType : "—");
            if ("QUEUED".equalsIgnoreCase(ad.activeSubType) && ad.activeMoveId != null) {
                String moveName = moveRepo.findById(ad.activeMoveId)
                    .map(m -> m.name).orElse("[missing]");
                System.out.printf("  │  Move: %-49s│%n", ad.activeMoveId + " " + moveName);
            }
            if ("TRIGGERED".equalsIgnoreCase(ad.activeSubType)) {
                System.out.printf("  │  Trigger: %-46s│%n",
                    ad.triggerCondition != null ? ad.triggerCondition : "—");
                if (ad.triggerThreshold != 0) {
                    System.out.printf("  │  Threshold: %-44s│%n", ad.triggerThreshold);
                }
            }
        }

        System.out.println("  ├─────────────────────────────────────────────────────────┤");

        // Effects list
        int fx = ad.effects != null ? ad.effects.size() : 0;
        System.out.printf("  │  EFFECTS (%d):%n", fx);
        if (ad.effects != null && !ad.effects.isEmpty()) {
            for (int i = 0; i < ad.effects.size(); i++) {
                AbilityEffectData e = ad.effects.get(i);
                System.out.printf("  │  %2d. %s%n", i + 1, formatEffect(e));
            }
        } else {
            System.out.printf("  │  %-55s│%n", "[no effects defined]");
        }

        System.out.println("  └─────────────────────────────────────────────────────────┘");
    }

    // =========================================================================
    // New ability
    // =========================================================================

    private void newAbility() {
        System.out.println();
        System.out.println("  ─── NEW ABILITY ──────────────────────────────────────────");
        System.out.printf ("  Next ID will be: %s%n%n", abilityRepo.nextId());

        AbilityData ad = new AbilityData();
        ad.category  = "PASSIVE";
        ad.sourceType = "CHARACTER";

        fillAbilityFields(ad);
        validateAndAdd(ad);
    }

    // =========================================================================
    // Edit ability
    // =========================================================================

    private void editAbility() {
        String id = pickAbilityById("Enter ability ID to edit: ");
        if (id == null) return;

        Optional<AbilityData> found = abilityRepo.findById(id);
        if (found.isEmpty()) { System.out.println("  Not found: " + id); return; }

        AbilityData ad = found.get();
        System.out.println();
        System.out.println("  Editing: " + ad.name + " (ID: " + ad.id + ")");
        System.out.println("  Press ENTER on any field to keep the current value.");
        System.out.println();

        fillAbilityFields(ad);
        validateAndUpdate(ad);
    }

    // =========================================================================
    // Delete ability
    // =========================================================================

    private void deleteAbility() {
        String id = pickAbilityById("Enter ability ID to delete: ");
        if (id == null) return;

        abilityRepo.findById(id).ifPresentOrElse(ad -> {
            System.out.printf("  Delete '%s' (ID: %s)? All subsequent IDs will shift. (y/N): ",
                ad.name, ad.id);
            if (sc.nextLine().trim().equalsIgnoreCase("y")) {
                abilityRepo.delete(id);
                persistNow();
                System.out.println("  Deleted and IDs resequenced.");
            } else {
                System.out.println("  Cancelled.");
            }
        }, () -> System.out.println("  Not found: " + id));
    }

    // =========================================================================
    // Field editor
    // =========================================================================

    private void fillAbilityFields(AbilityData ad) {

        // ── Name ──────────────────────────────────────────────────────────────
        sep("Name");
        ad.name = promptNonEmpty("Ability name", ad.name);

        // ── Category ──────────────────────────────────────────────────────────
        System.out.println();
        sep("Category");
        System.out.println("   1  PASSIVE   — always-on effects");
        System.out.println("   2  ACTIVE    — queued on the AP timeline, or triggered by a condition");
        ad.category = pickFromList(
            "Category",
            ad.category,
            new String[]{"PASSIVE", "ACTIVE"}
        );

        // ── Source ────────────────────────────────────────────────────────────
        System.out.println();
        sep("Source  (what grants this ability)");
        System.out.println("   1  CHARACTER      — intrinsic to this character");
        System.out.println("   2  TECHNIQUE      — requires possessing a named technique");
        System.out.println("   3  MOVE           — requires knowing a specific move (by ID)");
        System.out.println("   4  STAT_THRESHOLD — requires a stat to be at or above a value");
        System.out.println("   5  ABILITY        — granted by possessing another specific ability");
        ad.sourceType = pickFromList(
            "Source type",
            ad.sourceType,
            new String[]{"CHARACTER", "TECHNIQUE", "MOVE", "STAT_THRESHOLD", "ABILITY"}
        );

        switch (ad.sourceType) {
            case "TECHNIQUE" -> {
                System.out.println("  Enter the technique name required (e.g. Limitless, Shrine).");
                ad.sourceValue = promptWithDefault("Technique name", ad.sourceValue);
            }
            case "MOVE" -> {
                System.out.println("  Enter the 6-digit move ID required.");
                moveRepo.getAll().forEach(m -> System.out.printf("    %s  %s%n", m.id, m.name));
                ad.sourceValue = promptWithDefault("Move ID", ad.sourceValue);
            }
            case "STAT_THRESHOLD" -> {
                System.out.println("  Format: statname>=value  e.g.  cursedTechniqueMastery>=200");
                ad.sourceValue = promptWithDefault("Stat threshold", ad.sourceValue);
            }
            case "ABILITY" -> {
                System.out.println("  Enter the ability ID or name that grants this ability.");
                System.out.println("  Example: '000003' or 'Heavenly Restriction'");
                abilityRepo.getAll().forEach(a -> System.out.printf("    %s  %s%n", a.id, a.name));
                ad.sourceValue = promptWithDefault("Ability ID or name", ad.sourceValue);
            }
            default -> ad.sourceValue = null; // CHARACTER — no source value
        }

        // ── Active sub-type ───────────────────────────────────────────────────
        if ("ACTIVE".equalsIgnoreCase(ad.category)) {
            System.out.println();
            sep("Active Sub-type");
            System.out.println("   1  QUEUED    — placed on the AP timeline like a move");
            System.out.println("   2  TRIGGERED — fires automatically when a condition is met");
            ad.activeSubType = pickFromList(
                "Sub-type",
                ad.activeSubType != null ? ad.activeSubType : "QUEUED",
                new String[]{"QUEUED", "TRIGGERED"}
            );

            if ("QUEUED".equalsIgnoreCase(ad.activeSubType)) {
                System.out.println();
                sep("Linked Move  (QUEUED actives ARE a move — create it in the Move Editor)");
                moveRepo.getAll().forEach(m -> System.out.printf("    %s  %s%n", m.id, m.name));
                ad.activeMoveId = promptWithDefault("Move ID for this active", ad.activeMoveId);
                ad.triggerCondition = null;
                ad.triggerThreshold = 0;

            } else { // TRIGGERED
                System.out.println();
                sep("Trigger Condition");
                AbilityTrigger[] triggers = AbilityTrigger.values();
                for (int i = 0; i < triggers.length; i++) {
                    System.out.printf("   %d  %s%n", i + 1, triggers[i].name());
                }
                String currentTrigger = ad.triggerCondition;
                int defaultIdx = 0;
                for (int i = 0; i < triggers.length; i++) {
                    if (triggers[i].name().equals(currentTrigger)) { defaultIdx = i; break; }
                }
                String chosen = prompt("  Select trigger [" + (defaultIdx + 1) + "]: ").trim();
                if (!chosen.isBlank()) {
                    try {
                        int idx = Integer.parseInt(chosen) - 1;
                        if (idx >= 0 && idx < triggers.length) {
                            ad.triggerCondition = triggers[idx].name();
                        }
                    } catch (NumberFormatException ignored) {}
                } else {
                    ad.triggerCondition = currentTrigger != null ? currentTrigger : triggers[0].name();
                }

                // Threshold (for triggers that use one)
                boolean needsThreshold = ad.triggerCondition != null && (
                    ad.triggerCondition.equals("ON_HP_BELOW")
                );
                if (needsThreshold) {
                    ad.triggerThreshold = promptInt(
                        "Threshold (e.g. 30 for ON_HP_BELOW means < 30% HP)",
                        ad.triggerThreshold, 0, 100);
                } else {
                    ad.triggerThreshold = 0;
                }
                ad.activeMoveId = null;
            }
        } else {
            // Passive — clear active fields
            ad.activeSubType    = null;
            ad.activeMoveId     = null;
            ad.triggerCondition = null;
            ad.triggerThreshold = 0;
        }

        // ── Text ──────────────────────────────────────────────────────────────
        System.out.println();
        sep("Flavour Text  (in-universe, shown to the player)");
        System.out.println("  Write as if describing the ability from within the JJK world.");
        System.out.println("  Current: " + (ad.flavourText != null ? truncate(ad.flavourText, 60) : "[none]"));
        ad.flavourText = promptMultiline("Flavour text", ad.flavourText);

        System.out.println();
        sep("Mechanic Text  (precise description — use ALL_CAPS for keywords)");
        System.out.println("  Keywords in ALL_CAPS (2+ letters) will be highlighted in the UI.");
        System.out.println("  Example: Sets CURSED_ENERGY_EFFICIENCY to MAX. All CE costs to MINIMUM.");
        System.out.println("  Current: " + (ad.mechanicText != null ? truncate(ad.mechanicText, 60) : "[none]"));
        ad.mechanicText = promptMultiline("Mechanic text", ad.mechanicText);

        // ── Effects ───────────────────────────────────────────────────────────
        System.out.println();
        sep("Effects  (" + (ad.effects != null ? ad.effects.size() : 0) + " currently defined)");
        ad.effects = promptEffects(ad.effects);
    }

    // =========================================================================
    // Effect builder — the heart of the editor
    // =========================================================================

    private List<AbilityEffectData> promptEffects(List<AbilityEffectData> existing) {
        List<AbilityEffectData> effects = existing != null
            ? new ArrayList<>(existing) : new ArrayList<>();

        System.out.println("  Current effects:");
        if (effects.isEmpty()) {
            System.out.println("    [none]");
        } else {
            for (int i = 0; i < effects.size(); i++) {
                System.out.printf("    %d. %s%n", i + 1, formatEffect(effects.get(i)));
            }
        }
        System.out.println();
        System.out.println("  Options:  A=add  R<n>=remove effect #n  C=clear all  ENTER=keep");
        System.out.print("  > ");
        String cmd = sc.nextLine().trim().toUpperCase();

        if (cmd.isBlank()) return effects; // keep unchanged

        if (cmd.equals("C")) {
            System.out.println("  All effects cleared.");
            return new ArrayList<>();
        }

        if (cmd.startsWith("R")) {
            try {
                int idx = Integer.parseInt(cmd.substring(1).trim()) - 1;
                if (idx >= 0 && idx < effects.size()) {
                    AbilityEffectData removed = effects.remove(idx);
                    System.out.println("  Removed: " + formatEffect(removed));
                } else {
                    System.out.println("  Invalid index.");
                }
            } catch (NumberFormatException e) {
                System.out.println("  Usage: R<number>  e.g. R2");
            }
            // Recurse to allow more changes
            return promptEffects(effects);
        }

        if (cmd.equals("A")) {
            // Add one effect, then recurse to offer more
            AbilityEffectData newEffect = buildOneEffect();
            if (newEffect != null) {
                effects.add(newEffect);
                System.out.println("  Added: " + formatEffect(newEffect));
            }
            return promptEffects(effects);
        }

        System.out.println("  Unrecognised command — keeping existing effects.");
        return effects;
    }

    /**
     * Interactive wizard to build a single AbilityEffectData.
     * Prompts only for the parameters relevant to the chosen effect type.
     */
    private AbilityEffectData buildOneEffect() {
        System.out.println();
        System.out.println("  ─── Select Effect Type ──────────────────────────────────");
        for (int i = 0; i < EFFECT_LABELS.length; i++) {
            System.out.printf("   %-3d %s%n", i + 1, EFFECT_LABELS[i]);
        }
        System.out.println("    0  Cancel");

        String input = prompt("  Select effect (#): ").trim();
        try {
            int idx = Integer.parseInt(input);
            if (idx == 0) return null;
            if (idx < 1 || idx > EFFECT_TYPES.length) {
                System.out.println("  Invalid selection.");
                return null;
            }
            AbilityEffectType type = EFFECT_TYPES[idx - 1];
            return buildEffectForType(type);
        } catch (NumberFormatException e) {
            System.out.println("  Enter a number.");
            return null;
        }
    }

    private AbilityEffectData buildEffectForType(AbilityEffectType type) {
        AbilityEffectData e = new AbilityEffectData();
        e.type = type.name();

        // Note: editors are NOT bound by game stat limits — no min/max enforced here.
        switch (type) {

            case STAT_ADD -> {
                e.stat     = pickStat("Which stat to modify");
                e.intValue = promptIntUnbounded("Amount (positive or negative)", 0);
            }
            case STAT_MULTIPLY -> {
                e.stat        = pickStat("Which stat to multiply");
                e.doubleValue = promptDouble("Multiplier (e.g. 1.5 = +50%, 0.8 = -20%)", 1.0);
            }
            case STAT_DIVIDE -> {
                e.stat        = pickStat("Which stat to divide");
                e.doubleValue = promptDouble("Divisor (e.g. 2.0 = halve the stat)", 2.0);
            }
            case STAT_SET_VALUE -> {
                e.stat     = pickStat("Which stat to set");
                e.intValue = promptIntUnbounded("Value to set (any integer; 0 = N/A)", 0);
            }
            case STAT_SET_MIN -> {
                e.stat = pickStat("Which stat to set to 0 (N/A)");
                System.out.println("  This forces the stat to 0, displaying as N/A in-game.");
            }
            case STAT_BONUS_POINTS -> {
                System.out.println("  Creator-only: grants extra point-buy budget. No combat effect.");
                e.intValue = promptIntUnbounded("Bonus points to grant", 80);
            }
            case CE_COST_TO_MINIMUM -> {
                System.out.println("  Apply to ALL moves, or a specific move tag?");
                e.moveTag = pickMoveTagOrAll();
            }
            case CE_COST_MULTIPLY -> {
                e.moveTag     = pickMoveTagOrAll();
                e.doubleValue = promptDouble(
                    "Cost multiplier (0.5 = half cost, 2.0 = double cost)", 0.5);
            }
            case MOVE_ACCURACY_ADD -> {
                e.moveTag  = pickMoveTagOrAll();
                e.intValue = promptIntUnbounded("Flat accuracy bonus/penalty to OWN moves", 0);
            }
            case MOVE_ACCURACY_MULTIPLY -> {
                e.moveTag     = pickMoveTagOrAll();
                e.doubleValue = promptDouble(
                    "Multiplier for OWN accuracy (0.5 = half, 2.0 = double)", 1.0);
            }
            case OPPONENT_ACCURACY_ADD -> {
                e.moveTag  = pickMoveTagOrAll();
                e.intValue = promptIntUnbounded(
                    "Flat accuracy bonus/penalty to OPPONENT moves (negative = debuff)", 0);
            }
            case OPPONENT_ACCURACY_MULTIPLY -> {
                e.moveTag     = pickMoveTagOrAll();
                e.doubleValue = promptDouble(
                    "Multiplier for OPPONENT accuracy (0.5 = half their accuracy)", 1.0);
            }
            case DAMAGE_MULTIPLY -> {
                e.moveTag     = pickMoveTagOrAll();
                e.doubleValue = promptDouble("Damage multiplier (1.2 = +20%, 0.8 = -20%)", 1.2);
            }
            case GRANT_MOVE -> {
                System.out.println("  Select a move to grant unconditionally:");
                moveRepo.getAll().forEach(m ->
                    System.out.printf("    %s  %s%n", m.id, m.name));
                e.moveId = prompt("  Move ID: ").trim();
            }
            case BF_CHANCE_ADD -> {
                e.doubleValue = promptDouble(
                    "Black Flash chance to add (0.05 = +5%; negative reduces BF chance)", 0.05);
            }
            case UNLOCK_TECHNIQUE -> {
                System.out.println("  Enter the technique name to unlock (e.g. Limitless, Shrine).");
                e.stringValue = promptNonEmpty("Technique name", null);
            }
            case MODIFY_DEFENSE -> {
                e.doubleValue = promptDouble("Defense multiplier (1.3 = +30%, 0.5 = halve)", 1.3);
            }
            case MODIFY_AP_BAR -> {
                e.intValue = promptIntUnbounded("AP bar change (positive = larger, negative = smaller)", 0);
            }
            case AUTO_STATUS_APPLY -> {
                System.out.println("  Select status effect type:");
                for (int i = 0; i < STATUS_EFFECT_NAMES.length; i++) {
                    System.out.printf("   %-3d %s%n", i + 1, STATUS_EFFECT_NAMES[i]);
                }
                String statusInput = prompt("  Select (#): ").trim();
                try {
                    int si = Integer.parseInt(statusInput) - 1;
                    e.stringValue = (si >= 0 && si < STATUS_EFFECT_NAMES.length)
                        ? STATUS_EFFECT_NAMES[si] : "BARRIER";
                } catch (NumberFormatException ex) {
                    System.out.println("  Invalid — defaulting to BARRIER.");
                    e.stringValue = "BARRIER";
                }
                System.out.println("  Target:  1=SELF  2=ENEMY");
                e.target = "2".equals(prompt("  Target [1]: ").trim()) ? "ENEMY" : "SELF";
                System.out.println("  Timing:  1=FIGHT_START  2=ROUND_START  3=ON_HIT");
                String tim = prompt("  Timing [2]: ").trim();
                e.timing = switch (tim) {
                    case "1" -> "FIGHT_START";
                    case "3" -> "ON_HIT";
                    default  -> "ROUND_START";
                };
            }
            case LOCK_MOVE_TAG -> {
                System.out.println("  Which move tag to lock out?");
                System.out.println("  PASSIVE: prevents using/learning moves with this tag.");
                System.out.println("  ACTIVE/TRIGGERED: temporarily removes those blocks from the timeline.");
                e.moveTag = pickMoveTag();
            }
            case COST_CE_PER_ROUND -> {
                e.intValue = promptIntUnbounded(
                    "CE drained per round (positive = drain, negative = restore)", 15);
            }
        }

        return e;
    }

    // =========================================================================
    // Validation and persistence
    // =========================================================================

    private void validateAndAdd(AbilityData ad) {
        String err = validate(ad);
        if (err != null) { System.out.println("  [VALIDATION ERROR] " + err); return; }
        // Assign ID before add (works even when repository was empty)
        if (ad.id == null || ad.id.isBlank()) { ad.id = abilityRepo.nextId(); }
        abilityRepo.add(ad);
        persistNow();
        System.out.println("  Saved as ID: " + ad.id + "  —  " + ad.name);
    }

    private void validateAndUpdate(AbilityData ad) {
        String err = validate(ad);
        if (err != null) { System.out.println("  [VALIDATION ERROR] " + err); return; }
        abilityRepo.update(ad);
        persistNow();
        System.out.println("  Updated: " + ad.name + " (ID: " + ad.id + ")");
    }

    private String validate(AbilityData ad) {
        if (ad.name == null || ad.name.isBlank()) return "Name is required.";
        if (ad.category == null) return "Category is required.";
        if (!"PASSIVE".equalsIgnoreCase(ad.category) && !"ACTIVE".equalsIgnoreCase(ad.category))
            return "Category must be PASSIVE or ACTIVE.";
        if ("ACTIVE".equalsIgnoreCase(ad.category)) {
            if (ad.activeSubType == null) return "Active sub-type is required for ACTIVE abilities.";
            if ("TRIGGERED".equalsIgnoreCase(ad.activeSubType) && ad.triggerCondition == null)
                return "Trigger condition is required for TRIGGERED actives.";
        }
        // Validate each effect's type string
        if (ad.effects != null) {
            for (AbilityEffectData e : ad.effects) {
                try { AbilityEffectType.valueOf(e.type); }
                catch (Exception ex) { return "Unknown effect type: " + e.type; }
            }
        }
        return null;
    }

    private void persistNow() {
        try { abilityRepo.save(); }
        catch (IOException e) { System.out.println("  [ERROR] Could not write: " + e.getMessage()); }
    }

    // =========================================================================
    // Prompt helpers — delegates to EditorIO
    // =========================================================================

    private String prompt(String label)                              { return io.prompt(label); }
    private String promptNonEmpty(String label, String cur)          { return io.promptNonEmpty(label, cur); }
    private String promptWithDefault(String label, String cur)       { return io.promptWithDefault(label, cur); }
    private int    promptInt(String label, int cur, int min, int max){ return io.promptInt(label, cur, min, max); }
    private int    promptIntUnbounded(String label, int cur)         { return io.promptIntUnbounded(label, cur); }
    private double promptDouble(String label, double cur)            { return io.promptDouble(label, cur); }

    /**
     * Multi-line prompt — the user types their text on one line.
     * ENTER with no input keeps the existing value.
     */
    private String promptMultiline(String label, String current) {
        System.out.println("  (Type on one line, ENTER to keep existing)");
        String display = (current != null && !current.isBlank()) ? " [" + EditorIO.truncate(current, 40) + "]" : "";
        String input   = io.prompt("  " + label + display + ": ").trim();
        return input.isBlank() ? (current != null ? current : "") : input;
    }

    /** Pick from a fixed list — user enters a number. Returns one of the options. */
    private String pickFromList(String label, String current, String[] options) {
        while (true) {
            String display = current != null ? " [" + current + "]" : "";
            String input   = prompt("  " + label + display + " (ENTER to keep): ").trim();
            if (input.isBlank()) return current != null ? current : options[0];
            try {
                int v = Integer.parseInt(input) - 1;
                if (v >= 0 && v < options.length) return options[v];
                System.out.printf("  Enter 1–%d.%n", options.length);
            } catch (NumberFormatException e) {
                // Also accept typing the value directly
                for (String opt : options) {
                    if (opt.equalsIgnoreCase(input)) return opt.toUpperCase();
                }
                System.out.println("  Invalid. Enter a number or one of: " + String.join(", ", options));
            }
        }
    }

    private String pickStat(String label) {
        System.out.println("  " + label + ":");
        for (int i = 0; i < STAT_NAMES.length; i++) {
            System.out.printf("   %-3d %s%n", i + 1, STAT_NAMES[i]);
        }
        while (true) {
            String input = prompt("  Select stat (#): ").trim();
            try {
                int v = Integer.parseInt(input) - 1;
                if (v >= 0 && v < STAT_NAMES.length) return STAT_NAMES[v];
                System.out.printf("  Enter 1–%d.%n", STAT_NAMES.length);
            } catch (NumberFormatException e) {
                // accept typing the stat name directly
                for (String s : STAT_NAMES) {
                    if (s.equalsIgnoreCase(input)) return s;
                }
                System.out.println("  Invalid.");
            }
        }
    }

    private String pickMoveTag() {
        for (int i = 0; i < MOVE_TAGS.length; i++) {
            System.out.printf("   %-3d %s%n", i + 1, MOVE_TAGS[i]);
        }
        while (true) {
            String input = prompt("  Select tag (#): ").trim();
            try {
                int v = Integer.parseInt(input) - 1;
                if (v >= 0 && v < MOVE_TAGS.length) return MOVE_TAGS[v];
            } catch (NumberFormatException ignored) {
                for (String t : MOVE_TAGS) { if (t.equalsIgnoreCase(input)) return t; }
            }
            System.out.println("  Invalid.");
        }
    }

    /** Pick a move tag, or null meaning "all moves". */
    private String pickMoveTagOrAll() {
        System.out.println("  Apply to ALL moves, or filter by tag?");
        System.out.println("   0  ALL moves");
        for (int i = 0; i < MOVE_TAGS.length; i++) {
            System.out.printf("   %-3d %s%n", i + 1, MOVE_TAGS[i]);
        }
        while (true) {
            String input = prompt("  Select (0 = all, # = tag): ").trim();
            if (input.equals("0")) return null;
            try {
                int v = Integer.parseInt(input) - 1;
                if (v >= 0 && v < MOVE_TAGS.length) return MOVE_TAGS[v];
            } catch (NumberFormatException ignored) {
                for (String t : MOVE_TAGS) { if (t.equalsIgnoreCase(input)) return t; }
            }
            System.out.printf("  Enter 0–%d.%n", MOVE_TAGS.length);
        }
    }

    private String pickAbilityById(String label) {
        listAbilities();
        System.out.println();
        String id = prompt("  " + label).trim();
        if (id.isBlank()) return null;
        try {
            int n = Integer.parseInt(id);
            id = AbilityRepository.formatId(n);
        } catch (NumberFormatException ignored) {}
        return id;
    }

    // =========================================================================
    // Display helpers — delegates to EditorIO
    // =========================================================================

    private void sep(String title) { io.sep(title); }

    private void printWrapped(String text) {
        if (text == null || text.isBlank()) {
            System.out.printf("  │  %-55s│%n", "—");
            return;
        }
        // Split into words and reflow at ~53 chars
        String[] words  = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            if (line.length() + word.length() + 1 > 53) {
                System.out.printf("  │  %-55s│%n", line.toString());
                line = new StringBuilder(word);
            } else {
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
        }
        if (line.length() > 0) System.out.printf("  │  %-55s│%n", line.toString());
    }

    /**
     * Wrap ALL_CAPS keywords (2+ uppercase letters/underscores) with *** markers
     * so they stand out as highlighted in the terminal output.
     */
    private static String highlightKeywords(String text) {
        if (text == null) return "";
        // Match sequences of 2+ uppercase letters / underscores that aren't
        // sentence-start capitals (i.e. preceded by a non-space or start of text)
        Pattern p = Pattern.compile("\\b([A-Z][A-Z_]{1,}[A-Z])\\b");
        Matcher m = p.matcher(text);
        return m.replaceAll("***$1***");
    }

    private String formatEffect(AbilityEffectData e) {
        if (e == null) return "[null]";
        String type = e.type != null ? e.type : "?";
        StringBuilder sb = new StringBuilder(type).append(": ");
        if (e.stat        != null) sb.append("stat=").append(e.stat).append(" ");
        if (e.intValue    != null) sb.append("value=").append(e.intValue).append(" ");
        if (e.doubleValue != null) sb.append("factor=").append(e.doubleValue).append(" ");
        if (e.moveTag     != null) sb.append("tag=").append(e.moveTag).append(" ");
        if (e.moveId      != null) {
            String name = moveRepo.findById(e.moveId).map(m -> m.name).orElse("[?]");
            sb.append("move=").append(e.moveId).append("(").append(name).append(") ");
        }
        if (e.stringValue != null) sb.append("val=").append(e.stringValue).append(" ");
        if (e.target      != null) sb.append("→").append(e.target).append(" ");
        if (e.timing      != null) sb.append("@").append(e.timing).append(" ");
        return sb.toString().trim();
    }

    private String formatSource(AbilityData ad) {
        if (ad.sourceType == null) return "—";
        String s = ad.sourceType;
        if (ad.sourceValue != null) s += " → " + ad.sourceValue;
        return s;
    }

    private static String truncate(String s, int max) { return EditorIO.truncate(s, max); }
}
