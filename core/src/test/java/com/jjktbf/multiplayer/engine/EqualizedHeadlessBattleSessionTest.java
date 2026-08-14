package com.jjktbf.multiplayer.engine;

import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleStatMode;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.multiplayer.protocol.CharacterState;
import com.jjktbf.multiplayer.protocol.PlayerSide;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EqualizedHeadlessBattleSessionTest {
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void equalizedRulesApplyIndependentlyOfRosterSize(int fightersPerSide) {
        MatchParticipant first = participant(
            "player-1", PlayerSide.PLAYER_ONE, "p1", fightersPerSide);
        MatchParticipant second = participant(
            "player-2", PlayerSide.PLAYER_TWO, "p2", fightersPerSide);
        HeadlessBattleSession session = new HeadlessBattleSession(
            "equalized-match-" + fightersPerSide,
            first,
            second,
            7L,
            CLOCK,
            null,
            BattleStatMode.EQUALIZED);

        var state = session.snapshot();
        assertEquals(BattleStatMode.EQUALIZED.rulesetId(), state.ruleset());
        assertEquals(fightersPerSide, state.players().get(0).combatants().size());
        assertEquals(fightersPerSide, state.players().get(1).combatants().size());
        for (var player : state.players()) {
            for (CharacterState fighter : player.combatants()) {
                assertEquals(966, fighter.maxHp());
                assertEquals(2208, fighter.maxCe());
                assertEquals(190, fighter.maxAp());
                assertEquals(1, fighter.knownMoves().size(),
                    "runtime equalization must not alter move selection");
            }
        }
    }

    private static MatchParticipant participant(
        String playerId,
        PlayerSide side,
        String idPrefix,
        int size
    ) {
        List<Character> characters = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            String id = idPrefix + "-" + index;
            Move move = new Move.Builder("move-" + id)
                .name("Move " + id)
                .category(MoveCategory.PHYSICAL)
                .basePower(10)
                .apCost(5)
                .unleashPoint(1)
                .freeMove(true)
                .build();
            characters.add(new SorcererCharacter(
                id, id, allStats(300), null, List.of(move)));
        }
        return new MatchParticipant(playerId, playerId, characters, side);
    }

    private static CharacterStats allStats(int value) {
        return new CharacterStats.Builder()
            .vitality(value)
            .strength(value)
            .durability(value)
            .speed(value)
            .cursedEnergyReserves(value)
            .cursedEnergyEfficiency(value)
            .cursedEnergyOutput(value)
            .jujutsuSkill(value)
            .combatAbility(value)
            .cursedTechniqueMastery(value)
            .build();
    }
}
