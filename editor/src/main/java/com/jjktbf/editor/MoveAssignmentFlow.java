package com.jjktbf.editor;

import com.jjktbf.model.character.*;
import com.jjktbf.model.move.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Handles the move assignment step of character creation/editing.
 *
 * Presents the full eligible move list with * markers for already-assigned moves.
 * Selecting a move toggles it: adds if not present, removes if already assigned.
 * Slot budget is enforced when adding; never enforced when removing.
 */
public class MoveAssignmentFlow {

    private final EditorIO        io;
    private final Scanner         sc;
    private final MoveRepository  moveRepo;

    public MoveAssignmentFlow(EditorIO io, Scanner sc, MoveRepository moveRepo) {
        this.io       = io;
        this.sc       = sc;
        this.moveRepo = moveRepo;
    }

    public void run(CharacterData cd) {
        io.sep("Move Assignment");

        CharacterStats stats = cd.toCharacterStats();
        CombatStats    cs    = cd.toCombatStats();

        System.out.println("  Slot budgets:");
        System.out.printf("    Physical:               %d slots%n", cs.getPhysicalMoveSlots());
        System.out.printf("    Jujutsu / CE:           %d slots%n", cs.getJujutsuTechniqueSlots());
        System.out.printf("    Cursed Technique:       %d slots%n", cs.getCursedTechniqueSlots());
        System.out.println();

        if (cd.moveIds == null) cd.moveIds = new ArrayList<>();
        List<String> assignedIds = new ArrayList<>(cd.moveIds);

        List<MoveData> eligible = buildEligibleList(cd, stats, cs);

        System.out.println("  Currently assigned: " + formatAssigned(assignedIds));
        System.out.println("  Enter f to finish, or R to reset move list.");
        System.out.println();
        printList(eligible, assignedIds);
        System.out.println();

        while (true) {
            System.out.print("  > Add/remove move (#/ID), f to finish, R to reset: ");
            String input = sc.nextLine().trim().toUpperCase();

            if (input.equals("F")) break;
            if (input.equals("R")) {
                assignedIds.clear();
                System.out.println("  Move list cleared.");
                printList(eligible, assignedIds);
                System.out.println();
                continue;
            }

            try {
                MoveData chosen = resolve(input, eligible);
                if (chosen == null) { System.out.println("  Invalid move ID or number."); continue; }

                if (assignedIds.contains(chosen.id)) {
                    assignedIds.remove(chosen.id);
                    System.out.println("  Removed: " + chosen.name);
                } else {
                    MoveCategory cat = chosen.derivedCategory();
                    if (SlotBudgetEnforcer.isSlotGated(cat) && !chosen.isFreeMove) {
                        Map<MoveCategory, Integer> used = countUsage(assignedIds);
                        int available = SlotBudgetEnforcer.slotBudgetFor(cs, stats, cat);
                        if (used.getOrDefault(cat, 0) >= available) {
                            System.out.println("  No " + cat + " slots available.");
                            continue;
                        }
                    }
                    assignedIds.add(chosen.id);
                    System.out.println("  Added: " + chosen.name);
                }

                printList(eligible, assignedIds);
                System.out.println();

            } catch (NumberFormatException e) {
                System.out.println("  Enter a move ID (e.g. 000001), index, or f.");
            }
        }

        cd.moveIds = assignedIds;
        System.out.printf("  %d moves assigned.%n", assignedIds.size());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private List<MoveData> buildEligibleList(CharacterData cd, CharacterStats stats, CombatStats cs) {
        List<MoveData> list = new ArrayList<>();
        for (MoveData md : moveRepo.getAll()) {
            // Technique restriction
            if (md.requiredTechniqueId != null && !md.requiredTechniqueId.isBlank()) {
                if (cd.innateTechniqueName == null
                    || !md.requiredTechniqueId.equalsIgnoreCase(cd.innateTechniqueName)) {
                    continue;
                }
            }
            // Stat prerequisites
            if (md.prerequisites != null) {
                boolean fail = false;
                for (Map.Entry<String, Integer> prereq : md.prerequisites.entrySet()) {
                    try {
                        if (stats.getByName(prereq.getKey()) < prereq.getValue()) { fail = true; break; }
                    } catch (Exception ignored) { fail = true; break; }
                }
                if (fail) continue;
            }
            list.add(md);
        }
        return list;
    }

    private MoveData resolve(String input, List<MoveData> eligible) {
        for (MoveData md : eligible) {
            if (md.id.equals(input)) return md;
        }
        int idx = Integer.parseInt(input); // may throw NumberFormatException — propagated to caller
        return (idx >= 0 && idx < eligible.size()) ? eligible.get(idx) : null;
    }

    private Map<MoveCategory, Integer> countUsage(List<String> assignedIds) {
        List<MoveCategory> cats = new ArrayList<>();
        for (String id : assignedIds) {
            moveRepo.findById(id).ifPresent(md -> cats.add(md.derivedCategory()));
        }
        return SlotBudgetEnforcer.countUsage(cats);
    }

    private void printList(List<MoveData> moves, List<String> assigned) {
        System.out.printf("  %-4s %-8s %-24s %-20s %-5s %-5s%n",
            "#", "ID", "Name", "Tags", "AP", "Pwr");
        System.out.println("  " + "─".repeat(72));
        for (int i = 0; i < moves.size(); i++) {
            MoveData md  = moves.get(i);
            String tags  = md.tags != null ? String.join(",", md.tags) : "—";
            String power = md.basePower == 0 ? "N/A" : String.valueOf(md.basePower);
            String prefix = assigned.contains(md.id) ? "*" : " ";
            System.out.printf("  %s%-3d %-8s %-24s %-20s %-5d %-5s%n",
                prefix, i, md.id, EditorIO.truncate(md.name, 24), EditorIO.truncate(tags, 20), md.apCost, power);
        }
    }

    private String formatAssigned(List<String> ids) {
        if (ids == null || ids.isEmpty()) return "[none]";
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(moveRepo.findById(id).map(md -> md.name).orElse(id));
        }
        return sb.toString();
    }
}
