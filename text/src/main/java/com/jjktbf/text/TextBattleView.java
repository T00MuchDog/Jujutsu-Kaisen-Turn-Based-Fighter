package com.jjktbf.text;

import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CeEfficiencyCalculator;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.move.Move;
import com.jjktbf.view.BattleView;

import java.util.*;

/**
 * Text-mode implementation of BattleView.
 *
 * This is the ONLY class in the entire project that calls System.out or uses Scanner.
 * All rendering for the text phase lives here.
 *
 * Move selection loop:
 *  - Redraws the full screen each selection: both combatant statuses + move list
 *  - CE bar reflects projected CE after current queue is paid
 *  - Current queue shown as "Move A | Move B | Move C" above the input prompt
 *  - Options: number to queue a move, '?' to inspect a move, 0 to confirm
 *
 * When transitioning to graphics, implement PixelBattleView and pass to BattleController.
 */
public class
TextBattleView implements BattleView {

    private static final int BAR_WIDTH  = 40;
    private static final int DIVIDER_W  = 62;

    private final Scanner scanner;

    public TextBattleView() {
        this.scanner = new Scanner(System.in);
    }

    // =========================================================================
    // BattleView interface
    // =========================================================================

    @Override
    public void displayRoundStart(BattleState state) {
        clearScreen();
        printRoundBanner(state.getRoundNumber());
        printCombatantStatus(state.getPlayerCombatant(), state.getPlayerCombatant().getCurrentCe());
        System.out.println("                          VS");
        printCombatantStatus(state.getEnemyCombatant(), state.getEnemyCombatant().getCurrentCe());
        System.out.println();
    }

    @Override
    public List<Move> promptMoveSelection(BattleCombatant combatant, BattleCombatant opponent) {
        List<Move> queue    = new ArrayList<>();
        int remainingAp     = combatant.getEffectiveCombatStats().getMaxApBar();
        int projectedCe     = combatant.getCurrentCe(); // tracks CE after queued moves

        while (true) {
            // Redraw the full selection screen
            clearScreen();
            printRoundBanner(-1); // no round number in sub-screen
            printCombatantStatus(combatant, projectedCe);   // CE shows projected
            System.out.println("                          VS");
            printCombatantStatus(opponent, opponent.getCurrentCe());
            System.out.println();

            // Current queue bar
            printQueueBar(queue, combatant);

            System.out.printf("  AP remaining: %d%n", remainingAp);
            System.out.println();

            // Available moves list
            List<Move> available = getAffordableMoves(
                combatant.getCharacter().getKnownMoves(), remainingAp, projectedCe, combatant);

            if (available.isEmpty()) {
                System.out.println("  [No more affordable moves — AP or CE exhausted]");
                System.out.print("  Press ENTER to confirm queue: ");
                scanner.nextLine();
                break;
            }

            printMoveMenu(available, combatant);

            System.out.print("\n  > Select move (#), '?' to inspect, 0 to confirm: ");
            String input = scanner.nextLine().trim();

            if (input.equals("0") || input.equalsIgnoreCase("done")) {
                break;
            }

            if (input.startsWith("?")) {
                // Inspect a move
                String numStr = input.substring(1).trim();
                try {
                    int idx = Integer.parseInt(numStr) - 1;
                    if (idx >= 0 && idx < available.size()) {
                        printMoveInspect(available.get(idx), combatant);
                        System.out.print("  [Press ENTER to continue] ");
                        scanner.nextLine();
                    } else {
                        System.out.println("  Invalid move number.");
                        pause(700);
                    }
                } catch (NumberFormatException e) {
                    System.out.println("  Usage: ?<number>  e.g.  ?3");
                    pause(700);
                }
                continue;
            }

            try {
                int choice = Integer.parseInt(input);
                if (choice < 1 || choice > available.size()) {
                    System.out.println("  Invalid selection.");
                    pause(700);
                    continue;
                }

                Move chosen  = available.get(choice - 1);
                int  ceCost  = CeEfficiencyCalculator.computeActualCost(
                    chosen, combatant.getCharacter().getBaseStats().getCursedEnergyEfficiency());

                queue.add(chosen);
                remainingAp -= chosen.getApCost();
                projectedCe -= ceCost;

            } catch (NumberFormatException e) {
                System.out.println("  Enter a number, '?<number>' to inspect, or 0 to confirm.");
                pause(700);
            }
        }

        return queue;
    }

    @Override
    public void displayCombatEvents(List<CombatEvent> events, BattleState state) {
        System.out.println();
        System.out.println("  " + "─".repeat(DIVIDER_W));
        System.out.println("  RESOLUTION");
        System.out.println("  " + "─".repeat(DIVIDER_W));

        for (CombatEvent event : events) {
            String prefix = switch (event.getType()) {
                case BLACK_FLASH      -> "  ★★★ ";
                case BATTLE_OVER      -> "  !!! ";
                case MOVE_MISSED      -> "  ··  ";
                case MOVE_BLOCKED        -> "  ▓▓  ";
                case MOVE_PARTIAL_BLOCK  -> "  ▒▒  ";
                case DAMAGE_DEALT     -> "  ►   ";
                case CE_DEPLETED      -> "  ▼▼▼ ";
                case MOVE_KNOCKED_OUT -> "  ✗   ";
                case BFS_ENTERED      -> "  ★   ";
                case BFS_EXPIRED      -> "  ☆   ";
                default               -> "  ·   ";
            };
            System.out.println(prefix + event.getMessage());

            if (event.getType() == CombatEvent.Type.BLACK_FLASH) pause(600);
            if (event.getType() == CombatEvent.Type.ROUND_END)   pause(300);
        }

        System.out.println("  " + "─".repeat(DIVIDER_W));
        System.out.println();
    }

    @Override
    public void displayRoundEnd(BattleState state) {
        System.out.println("  Status after round:");
        printCombatantStatusLine(state.getPlayerCombatant());
        printCombatantStatusLine(state.getEnemyCombatant());
        System.out.println();
        System.out.print("  [Press ENTER for next round] ");
        scanner.nextLine();
    }

    @Override
    public void displayBattleOver(BattleCombatant winner, BattleState state) {
        clearScreen();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║                     BATTLE OVER                         ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        if (winner == null) {
            System.out.println("  DRAW — both fighters fall simultaneously!");
        } else {
            System.out.println("  WINNER: " + winner.getCharacter().getName());
        }

        System.out.printf("  Rounds fought: %d%n", state.getRoundNumber() - 1);
        System.out.println();
        printCombatantStatus(state.getPlayerCombatant(), state.getPlayerCombatant().getCurrentCe());
        System.out.println();
        printCombatantStatus(state.getEnemyCombatant(), state.getEnemyCombatant().getCurrentCe());
    }

    @Override
    public void displayMessage(String message) {
        System.out.println("  " + message);
    }

    // =========================================================================
    // Move inspect — shown when player types ?<n>
    // =========================================================================

    /**
     * Print a full stat summary and description for a move.
     * Called during move selection when the player types ?<number>.
     */
    private void printMoveInspect(Move move, BattleCombatant combatant) {
        int ceCost = CeEfficiencyCalculator.computeActualCost(
            move, combatant.getCharacter().getBaseStats().getCursedEnergyEfficiency());

        System.out.println();
        System.out.println("  ┌─────────────────────────────────────────────────────────┐");
        System.out.printf ("  │  %-55s│%n", move.getName());
        System.out.println("  ├─────────────────────────────────────────────────────────┤");

        // Description (word-wrap at 53 chars)
        String desc = move.getDescription();
        while (desc.length() > 53) {
            int cut = desc.lastIndexOf(' ', 53);
            if (cut <= 0) cut = 53;
            System.out.printf("  │  %-53s  │%n", desc.substring(0, cut));
            desc = desc.substring(cut).trim();
        }
        if (!desc.isEmpty()) System.out.printf("  │  %-53s  │%n", desc);

        System.out.println("  ├─────────────────────────────────────────────────────────┤");
        System.out.printf ("  │  Category   : %-41s│%n", move.getCategory().name());
        System.out.printf ("  │  AP Cost    : %-41s│%n", move.getApCost());
        System.out.printf ("  │  Unleash At : tick %-37s│%n", move.getUnleashPoint() + " of " + move.getApCost());
        System.out.printf ("  │  Base Power : %-41s│%n", move.getBasePower() == 0 ? "—" : move.getBasePower());
        System.out.printf ("  │  CE Cost    : %-41s│%n", ceCost == 0 ? "—" : ceCost);
        System.out.printf ("  │  Accuracy   : %-41s│%n",
            move.isNeverMiss() ? "Cannot miss" : String.format("%.0f%%", move.getBaseAccuracy() * 100));

        if (move.getInterruptType() != com.jjktbf.model.move.InterruptType.NONE) {
            System.out.printf("  │  Interrupt  : %-41s│%n", move.getInterruptType());
        }
        if (move.isDefensive()) {
            System.out.printf("  │  Defense    : %-41s│%n", move.getDefenseType());
            if (move.getDefenseType() == com.jjktbf.model.move.DefenseType.BLOCK) {
                System.out.printf("  │  Block      : -%d%%%-38s│%n", move.getBlockDamageReduction(), "");
            } else if (move.getDefenseType() == com.jjktbf.model.move.DefenseType.FLAT_BLOCK) {
                System.out.printf("  │  Flat Block : -%d dmg%-36s│%n", move.getBlockFlatReduction(), "");
            }
        }
        if (move.isBlackFlashEligible()) {
            System.out.printf("  │  ★ Black Flash eligible%n");
        }
        if (!move.getOnHitEffects().isEmpty()) {
            System.out.printf("  │  On-Hit     : %-41s│%n",
                move.getOnHitEffects().stream()
                    .map(e -> e.getType().toString())
                    .reduce((a, b) -> a + ", " + b).orElse(""));
        }

        System.out.println("  └─────────────────────────────────────────────────────────┘");
    }

    // =========================================================================
    // Internal rendering helpers
    // =========================================================================

    private void printRoundBanner(int round) {
        if (round > 0) {
            System.out.println("╔══════════════════════════════════════════════════════════╗");
            System.out.printf ("║                     ROUND  %-3d                           ║%n", round);
            System.out.println("╚══════════════════════════════════════════════════════════╝");
        } else {
            System.out.println("╔══════════════════════════════════════════════════════════╗");
            System.out.println("║                   MOVE SELECTION                         ║");
            System.out.println("╚══════════════════════════════════════════════════════════╝");
        }
        System.out.println();
    }

    /**
     * Print a combatant's status block.
     * @param projectedCe  CE to display (may differ from actual during planning — shows post-queue CE)
     */
    private void printCombatantStatus(BattleCombatant c, int projectedCe) {
        int maxHp = c.getEffectiveCombatStats().getMaxHp();
        int maxCe = c.getEffectiveCombatStats().getMaxCursedEnergy();
        int apBar = c.getEffectiveCombatStats().getMaxApBar();

        System.out.printf("  %-24s%s%n",
            c.getCharacter().getName(),
            c.isInBlackFlashState()
                ? "  ★ BLACK FLASH STATE (x" + c.getConsecutiveBfsHits() + ")"
                : "");

        System.out.printf("  HP  [%-40s] %d/%d%n",
            buildBar(c.getCurrentHp(), maxHp, BAR_WIDTH), c.getCurrentHp(), maxHp);

        // CE bar uses projectedCe for display but shows actual/max
        System.out.printf("  CE  [%-40s] %d/%d%n",
            buildBar(projectedCe, maxCe, BAR_WIDTH), projectedCe, maxCe);

        System.out.printf("  AP Bar: %d pts%n", apBar);

        if (!c.getActiveEffects().isEmpty()) {
            System.out.print("  Effects: ");
            c.getActiveEffects().forEach(e ->
                System.out.printf("[%s %dr] ", e.getType(), e.getDurationRounds()));
            System.out.println();
        }
    }

    private void printCombatantStatusLine(BattleCombatant c) {
        System.out.printf("  %-20s  HP:%d/%d  CE:%d/%d%s%n",
            c.getCharacter().getName(),
            c.getCurrentHp(), c.getEffectiveCombatStats().getMaxHp(),
            c.getCurrentCe(), c.getEffectiveCombatStats().getMaxCursedEnergy(),
            c.isInBlackFlashState() ? "  ★BFS" : "");
    }

    /**
     * Print the current queued move chain above the input prompt.
     * Format: "Cursed Energy Armor | Basic Punch | Divergent Fist"
     * Or "[Empty]" if nothing queued yet.
     */
    private void printQueueBar(List<Move> queue, BattleCombatant combatant) {
        System.out.print("  Queue: ");
        if (queue.isEmpty()) {
            System.out.println("[Empty]");
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < queue.size(); i++) {
                if (i > 0) sb.append(" | ");
                sb.append(queue.get(i).getName());
            }
            System.out.println(sb);
        }
        System.out.println();
    }

    private void printMoveMenu(List<Move> moves, BattleCombatant combatant) {
        System.out.printf("  %-3s %-22s %-5s %-5s %-6s %-12s %-6s%n",
            "#", "Name", "AP", "CE", "Power", "Category", "Acc");
        System.out.println("  " + "─".repeat(DIVIDER_W));

        int idx = 1;
        for (Move move : moves) {
            int ceCost = CeEfficiencyCalculator.computeActualCost(
                move, combatant.getCharacter().getBaseStats().getCursedEnergyEfficiency());
            String accStr = move.isNeverMiss() ? "—" : String.format("%.0f%%", move.getBaseAccuracy() * 100);
            System.out.printf("  %-3d %-22s %-5d %-5s %-6s %-12s %-6s%n",
                idx++,
                move.getName(),
                move.getApCost(),
                ceCost == 0 ? "—" : ceCost,
                move.getBasePower() == 0 ? "—" : move.getBasePower(),
                move.getCategory().name().replace("_", " "),
                accStr
            );
        }
        System.out.println("  (type ?<number> to inspect a move, e.g. ?3)");
    }

    private List<Move> getAffordableMoves(List<Move> known, int remainAp, int currentCe,
                                          BattleCombatant combatant) {
        List<Move> affordable = new ArrayList<>();
        for (Move move : known) {
            if (move.getApCost() > remainAp) continue;
            int ceCost = CeEfficiencyCalculator.computeActualCost(
                move, combatant.getCharacter().getBaseStats().getCursedEnergyEfficiency());
            if (ceCost > currentCe) continue;
            affordable.add(move);
        }
        return affordable;
    }

    private String buildBar(int current, int max, int width) {
        if (max <= 0) return " ".repeat(width);
        int filled = (int) Math.round((double) Math.max(0, current) / max * width);
        filled = Math.max(0, Math.min(width, filled));
        return "█".repeat(filled) + "░".repeat(width - filled);
    }

    /**
     * Clear the terminal output.
     *
     * Strategy:
     *  1. Try the ANSI escape sequence — works in macOS Terminal, iTerm2, most real TTYs.
     *  2. If ANSI is not supported (IntelliJ console, Windows cmd without VT mode),
     *     fall back to printing 50 blank lines, which pushes old content off-screen.
     *
     * To force fallback mode (e.g. when running in IntelliJ), set the system property:
     *   -Djjk.noAnsi=true
     */
    private static final boolean USE_ANSI = !Boolean.getBoolean("jjk.noAnsi");

    private static void clearScreen() {
        if (USE_ANSI) {
            System.out.print("\033[H\033[2J");
            System.out.flush();
        } else {
            // Fallback: blank lines push old output above the visible area
            for (int i = 0; i < 50; i++) System.out.println();
        }
    }

    private static void pause(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) {}
    }
}
