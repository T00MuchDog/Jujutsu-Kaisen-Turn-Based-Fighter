package com.jjktbf.editor;

import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityRepository;
import com.jjktbf.model.character.CharacterData;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Handles the ability assignment step of character creation/editing.
 *
 * Presents the full ability list with * markers for already-assigned abilities.
 * Selecting an ability toggles it: adds if not present, removes if already assigned.
 * No hard eligibility gating — source requirements are informational only.
 */
public class AbilityAssignmentFlow {

    private final EditorIO          io;
    private final Scanner           sc;
    private final AbilityRepository abilityRepo;

    public AbilityAssignmentFlow(EditorIO io, Scanner sc, AbilityRepository abilityRepo) {
        this.io          = io;
        this.sc          = sc;
        this.abilityRepo = abilityRepo;
    }

    public void run(CharacterData cd) {
        io.sep("Ability Assignment");

        if (cd.abilityIds == null) cd.abilityIds = new ArrayList<>();
        List<String> assignedIds = new ArrayList<>(cd.abilityIds);

        List<AbilityData> abilities = new ArrayList<>(abilityRepo.getAll());

        System.out.println("  Abilities are global — browse and assign by ID.");
        System.out.println("  Some abilities have source requirements (e.g. technique, stat threshold).");
        System.out.println("  Currently assigned: " + formatAssigned(assignedIds));
        System.out.println("  Enter f to finish, R to reset.");
        System.out.println();
        printList(abilities, assignedIds);
        System.out.println();

        while (true) {
            System.out.print("  > Add/remove ability (#/ID), f to finish, R to reset: ");
            String input = sc.nextLine().trim().toUpperCase();

            if (input.equals("F")) break;
            if (input.equals("R")) {
                assignedIds.clear();
                System.out.println("  Ability list cleared.");
                printList(abilities, assignedIds);
                System.out.println();
                continue;
            }

            try {
                AbilityData chosen = resolve(input, abilities);
                if (chosen == null) { System.out.println("  Invalid ability ID or number."); continue; }

                if (assignedIds.contains(chosen.id)) {
                    assignedIds.remove(chosen.id);
                    System.out.println("  Removed: " + chosen.name);
                } else {
                    assignedIds.add(chosen.id);
                    System.out.println("  Added: " + chosen.name);
                }

                printList(abilities, assignedIds);
                System.out.println();

            } catch (NumberFormatException e) {
                System.out.println("  Enter an ability ID (e.g. 000001), index, or f.");
            }
        }

        cd.abilityIds = assignedIds;
        System.out.printf("  %d abilities assigned.%n", assignedIds.size());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private AbilityData resolve(String input, List<AbilityData> abilities) {
        for (AbilityData ad : abilities) {
            if (ad.id.equals(input)) return ad;
        }
        int idx = Integer.parseInt(input); // may throw — propagated to caller
        return (idx >= 0 && idx < abilities.size()) ? abilities.get(idx) : null;
    }

    private void printList(List<AbilityData> abilities, List<String> assigned) {
        if (abilities.isEmpty()) {
            System.out.println("  [No abilities exist]");
            return;
        }
        System.out.printf("  %-4s %-8s %-24s %-10s %-20s%n", "#", "ID", "Name", "Category", "Source");
        System.out.println("  " + "─".repeat(72));
        for (int i = 0; i < abilities.size(); i++) {
            AbilityData ad = abilities.get(i);
            String src = ad.sourceType != null ? ad.sourceType : "?";
            if (ad.sourceValue != null) src += "(" + EditorIO.truncate(ad.sourceValue, 12) + ")";
            String prefix = assigned.contains(ad.id) ? "*" : " ";
            System.out.printf("  %s%-3d %-8s %-24s %-10s %-20s%n",
                prefix, i, ad.id, EditorIO.truncate(ad.name, 24), ad.category, EditorIO.truncate(src, 20));
        }
    }

    private String formatAssigned(List<String> ids) {
        if (ids == null || ids.isEmpty()) return "[none]";
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(abilityRepo.findById(id).map(ad -> ad.name).orElse(id));
        }
        return sb.toString();
    }
}
