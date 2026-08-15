package com.jjktbf.graphics.ui.battle;

import com.jjktbf.model.character.AbilityRepository;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterRepository;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleStatMode;
import com.jjktbf.model.combat.BattleTeamId;
import com.jjktbf.model.move.MoveRepository;
import com.jjktbf.model.technique.TechniqueRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Builds deterministic preview state from the same authored catalogs as production battles. */
public final class BattleUiPreviewFactory {

    public record PreviewBattle(
        BattleState state,
        List<String> playerSpriteAssets,
        List<String> enemySpriteAssets
    ) { }

    private record Candidate(CharacterData data, Character character, int presentationScore) { }

    private BattleUiPreviewFactory() {
    }

    public static PreviewBattle loadRepresentativeBattle() throws IOException {
        MoveRepository moves = new MoveRepository("data/moves");
        AbilityRepository abilities = new AbilityRepository("data/abilities");
        TechniqueRepository techniques = new TechniqueRepository("data/techniques");
        CharacterRepository characters = new CharacterRepository("data/characters");
        moves.load();
        abilities.load();
        techniques.load();
        characters.load();

        List<Candidate> candidates = new ArrayList<>();
        for (CharacterData data : characters.getAll()) {
            if (data == null || !data.effectiveSelectable()) continue;
            try {
                Character character = data.toCharacter(moves, abilities, techniques);
                BattleCombatant probe = new BattleCombatant(
                    character, character.getAbilities(), BattleStatMode.STANDARD);
                int codedStateCount = probe.getCodedAbilities().states().size();
                int score = codedStateCount * 100 + character.getKnownMoves().size();
                candidates.add(new Candidate(data, character, score));
            } catch (RuntimeException invalidContent) {
                System.err.println("Battle UI preview skipped character " + data.id
                    + ": " + invalidContent.getMessage());
            }
        }
        candidates.sort(Comparator
            .comparingInt(Candidate::presentationScore).reversed()
            .thenComparing(candidate -> candidate.data().id == null ? "" : candidate.data().id));
        if (candidates.isEmpty()) {
            throw new IOException("No valid selectable characters are available for the battle UI preview");
        }

        // Four slots exercise every production formation/HUD branch. Catalogs
        // with fewer definitions cycle immutable character data into distinct
        // battle instances, which remains deterministic and gameplay-safe.
        int teamSize = 4;
        List<BattleCombatant> playerTeam = new ArrayList<>();
        List<BattleCombatant> enemyTeam = new ArrayList<>();
        List<String> playerSprites = new ArrayList<>();
        List<String> enemySprites = new ArrayList<>();
        for (int index = 0; index < teamSize; index++) {
            Candidate player = candidates.get((index * 2) % candidates.size());
            Candidate enemy = candidates.get((index * 2 + 1) % candidates.size());
            playerTeam.add(new BattleCombatant(
                player.character(), player.character().getAbilities(), BattleStatMode.STANDARD));
            enemyTeam.add(new BattleCombatant(
                enemy.character(), enemy.character().getAbilities(), BattleStatMode.STANDARD));
            playerSprites.add(player.data().spriteAsset);
            enemySprites.add(enemy.data().spriteAsset);
        }

        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, playerTeam),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, enemyTeam));
        applyRepresentativeResources(playerTeam, enemyTeam);
        return new PreviewBattle(
            state, List.copyOf(playerSprites), List.copyOf(enemySprites));
    }

    private static void applyRepresentativeResources(
        List<BattleCombatant> players,
        List<BattleCombatant> enemies
    ) {
        for (int index = 0; index < players.size(); index++) {
            BattleCombatant combatant = players.get(index);
            combatant.applyDamage(Math.max(1,
                Math.round(combatant.getMaxHp() * (index == 0 ? 0.34f : 0.18f))));
            combatant.drainCe(Math.max(1,
                Math.round(combatant.getMaxCursedEnergy() * (index == 0 ? 0.27f : 0.52f))));
        }
        for (int index = 0; index < enemies.size(); index++) {
            BattleCombatant combatant = enemies.get(index);
            combatant.applyDamage(Math.max(1,
                Math.round(combatant.getMaxHp() * (index == 0 ? 0.57f : 0.08f))));
            combatant.drainCe(Math.max(1,
                Math.round(combatant.getMaxCursedEnergy() * (index == 0 ? 0.41f : 0.16f))));
        }
    }
}
