package com.jjktbf.text;

import com.jjktbf.controller.BattleController;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterRepository;
import com.jjktbf.model.move.MoveRepository;
import com.jjktbf.view.BattleView;

import java.io.IOException;
import java.util.List;
import java.util.Scanner;

public class TextMain {

    private static final String CHAR_DATA_DIR = "data/characters";
    private static final String MOVE_DATA_DIR = "data/moves";

    private final Scanner scanner = new Scanner(System.in);
    private final CharacterRepository charRepo;
    private final MoveRepository moveRepo;

    public TextMain() {
        this.charRepo = new CharacterRepository(CHAR_DATA_DIR);
        this.moveRepo = new MoveRepository(MOVE_DATA_DIR);
    }

    public static void main(String[] args) {
        new TextMain().run();
    }

    private void run() {
        printBanner();

        try {
            moveRepo.load();
            charRepo.load();
        } catch (IOException e) {
            System.out.println("[ERROR] Failed to load data: " + e.getMessage());
            return;
        }

        List<CharacterData> characters = charRepo.getAll();
        if (characters.isEmpty()) {
            System.out.println("No characters available.");
            System.out.println("Create characters using the Character Editor, then return here.");
            return;
        }

        CharacterData playerChar = selectCharacter("PLAYER", characters);
        if (playerChar == null) {
            System.out.println("Cancelled.");
            return;
        }

        CharacterData enemyChar = selectCharacter("CPU ENEMY", characters);
        if (enemyChar == null) {
            System.out.println("Cancelled.");
            return;
        }

        Character player = playerChar.toCharacter(moveRepo);
        Character enemy  = enemyChar.toCharacter(moveRepo);

        BattleView       view       = new TextBattleView();
        BattleController controller = new BattleController(view);

        controller.runBattle(player, enemy);
    }

    private CharacterData selectCharacter(String side, List<CharacterData> characters) {
        while (true) {
            System.out.println();
            System.out.println("═══ " + side + " SELECTION ═══");
            System.out.println("Available characters:");
            for (int i = 0; i < characters.size(); i++) {
                CharacterData c = characters.get(i);
                String innate = c.innateTechniqueName != null ? " [" + c.innateTechniqueName + "]" : "";
                System.out.printf("  %d. %-20s%s%n", i + 1, c.name, innate);
            }
            System.out.println("  0. Cancel");
            System.out.print("Select (#): ");
            String input = scanner.nextLine().trim();
            if ("0".equals(input)) {
                return null;
            }
            try {
                int choice = Integer.parseInt(input) - 1;
                if (choice >= 0 && choice < characters.size()) {
                    return characters.get(choice);
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid selection.");
            }
        }
    }

    private void printBanner() {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║        JUJUTSU KAISEN — TURN BASED FIGHTER               ║");
        System.out.println("║                   Text Mode v0.1                         ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}