package com.jjktbf.text;

import com.jjktbf.controller.BattleController;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterFactory;
import com.jjktbf.view.BattleView;

/**
 * Entry point for the text-mode version of JJK Turn-Based Fighter.
 *
 * This is the ONLY class that decides which renderer to use.
 * Swapping to graphics mode means creating a new entry point here that
 * passes a PixelBattleView instead — core is untouched.
 */
public class TextMain {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║        JUJUTSU KAISEN — TURN BASED FIGHTER               ║");
        System.out.println("║                   Text Mode v0.1                         ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        Character player = CharacterFactory.createYuji();
        Character enemy  = CharacterFactory.createSukuna();

        BattleView       view       = new TextBattleView();
        BattleController controller = new BattleController(view);

        controller.runBattle(player, enemy);
    }
}
