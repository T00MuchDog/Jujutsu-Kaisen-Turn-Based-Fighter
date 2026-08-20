package com.jjktbf.server.content;

import com.jjktbf.model.character.CharacterType;
import com.jjktbf.model.combat.AbilityActivationEngine;
import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.move.MoveType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentCatalogTest {
    @Test
    void loadsCanonicalClasspathDefinitions() {
        ContentCatalog catalog = ContentCatalog.load();

        assertFalse(catalog.findCharacter("missing").isPresent());
        assertThrows(UnsupportedOperationException.class,
            () -> catalog.characterSummaries().clear());
    }

    @Test
    void loadsPandaAndHiddenGorillaCoreComposition() {
        ContentCatalog catalog = ContentCatalog.load();
        var panda = catalog.findCharacter("000017").orElseThrow();
        var gorilla = catalog.findCharacter("000018").orElseThrow();

        assertEquals(CharacterType.CURSED_CORPSE, panda.getType());
        assertEquals(CharacterType.CURSED_CORPSE, gorilla.getType());
        assertTrue(catalog.findSelectableCharacter("000017").isPresent());
        assertFalse(catalog.findSelectableCharacter("000018").isPresent());
        assertNull(panda.getInnateTechniqueName());
        assertTrue(new BattleCombatant(panda).isPoisonImmune());
        assertTrue(new BattleCombatant(gorilla).isPoisonImmune());
        assertTrue(panda.getKnownMoves().stream()
            .allMatch(move -> move.getMoveType() == MoveType.SORCERER));
        assertEquals(MoveType.SHIKIGAMI, gorilla.getKnownMoves().stream()
            .filter(move -> "000091".equals(move.getId()))
            .findFirst().orElseThrow().getMoveType());
        assertTrue(gorilla.getAbilities().stream()
            .anyMatch(ability -> "000040".equals(ability.getId())));
    }

    @Test
    void pandaAutomaticallyUsesOnlyASurvivingCore() {
        ContentCatalog catalog = ContentCatalog.load();
        BattleCombatant panda = new BattleCombatant(
            catalog.findCharacter("000017").orElseThrow());
        BattleCombatant enemy = new BattleCombatant(
            catalog.findCharacter("000000").orElseThrow());
        BattleState state = new BattleState(panda, enemy);
        AbilityActivationEngine engine = new AbilityActivationEngine(
            new SeededRandomSource(1L), catalog::findCharacter);

        panda.receiveDamage(panda.getCurrentHp());
        var gorillaEvents = engine.process(state, AbilityTrigger.amount(
            AbilityTrigger.Type.DAMAGE, enemy, panda, 1, 1));

        assertEquals("000018", panda.getCharacter().getId());
        assertEquals(panda.getMaxHp(), panda.getCurrentHp());
        assertTrue(gorillaEvents.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.CHARACTER_TRANSFORMED));

        panda.receiveDamage(panda.getCurrentHp());
        var failedReturn = engine.process(state, AbilityTrigger.amount(
            AbilityTrigger.Type.DAMAGE, enemy, panda, 1, 2));

        assertEquals("000018", panda.getCharacter().getId());
        assertEquals(0, panda.getCurrentHp());
        assertTrue(failedReturn.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.EFFECT_FAILED));
        assertTrue(failedReturn.stream().noneMatch(event ->
            event.getType() == CombatEvent.Type.CHARACTER_REVERTED));
    }
}
