package com.jjktbf;

import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.BattleStatKey;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackFlashOddsModifierTest {

    @Test
    void doubledOddsMatchBlessedByTheSparksOfBlackChances() {
        BattleCombatant yuji = combatant(2.0);

        assertEquals(0.0582524272, yuji.getCurrentBfChance(), 1.0e-9);

        yuji.enterBlackFlashState(1);
        assertEquals(0.1818181818, yuji.getCurrentBfChance(), 1.0e-9);

        yuji.recordBfsHit();
        assertEquals(0.3333333333, yuji.getCurrentBfChance(), 1.0e-9);

        yuji.recordBfsHit();
        assertEquals(0.5185185185, yuji.getCurrentBfChance(), 1.0e-9);

        yuji.recordBfsHit();
        assertEquals(0.6666666667, yuji.getCurrentBfChance(), 1.0e-9);
    }

    @Test
    void oddsMultiplierIsAPassiveProbabilityPrimitive() {
        AbilityEffectData effect = AbilityEffectType.BATTLE_STAT_ODDS_MULTIPLY.createDefault();

        assertTrue(AbilityEffectType.BATTLE_STAT_ODDS_MULTIPLY.isPassiveOnly());
        assertFalse(AbilityEffectType.BATTLE_STAT_ODDS_MULTIPLY.requiresActivation());
        assertEquals(BattleStatKey.BLACK_FLASH_CHANCE.name(), effect.stringValue);
        assertEquals(2.0, effect.doubleValue);
    }

    private static BattleCombatant combatant(double oddsMultiplier) {
        AbilityEffectData effect = AbilityEffectType.BATTLE_STAT_ODDS_MULTIPLY.createDefault();
        effect.doubleValue = oddsMultiplier;
        AbilityData data = new AbilityData();
        data.id = "BLESSED_BY_BLACK";
        data.name = "Blessed by the Sparks of Black";
        data.category = "PASSIVE";
        data.sourceType = "CHARACTER";
        data.effects = List.of(effect);
        SorcererCharacter character = new SorcererCharacter(
            "YUJI",
            "Yuji",
            new CharacterStats.Builder().build(),
            null,
            List.of(),
            List.of(new Ability(data)));
        return new BattleCombatant(character);
    }
}
