package com.jjktbf.server.content;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jjktbf.model.combat.BattleCombatant;

class ContentCatalogTest {
    @Test
    void loadsCanonicalClasspathDefinitionsIntoDomainCharacters() {
        ContentCatalog catalog = ContentCatalog.load();

        assertEquals(8, catalog.characterSummaries().size());
        assertTrue(catalog.findCharacter("000000").isPresent());
        assertTrue(catalog.findCharacter("000002").orElseThrow().getKnownMoves().stream()
            .anyMatch(move -> "Simple Domain".equals(move.getName())));
        assertTrue(catalog.findCharacter("000002").orElseThrow().hasWeapon());
        assertTrue(catalog.findCharacter("000002").orElseThrow().getKnownMoves().stream()
            .anyMatch(move -> "Sword Slash".equals(move.getName())));
        assertFalse(catalog.findCharacter("missing").isPresent());
        assertFalse(catalog.findCharacter("000000").orElseThrow().getKnownMoves().isEmpty());
        var yuji = catalog.findCharacter("000003").orElseThrow();
        assertEquals(15, yuji.getBaseStats().getJujutsuSkill());
        assertEquals(95, yuji.getBaseStats().getCombatAbility());
        assertFalse(yuji.getKnownMoves().stream()
            .anyMatch(move -> move.getName().startsWith("Reinforced ")));
        var divergentFist = yuji.getKnownMoves().stream()
            .filter(move -> "Divergent Fist".equals(move.getName()))
            .findFirst().orElseThrow();
        assertEquals(35, divergentFist.getBasePower());
        assertEquals(2, divergentFist.getHitComponents().size());
        assertEquals(2, divergentFist.getHitComponents().get(1).getDelayTicks());
        assertFalse(divergentFist.isBlackFlashEligible());
        var maki = catalog.findCharacter("000005").orElseThrow();
        assertTrue(maki.hasWeapon());
        assertEquals(75, maki.getBaseStats().getVitality());
        assertEquals(80, maki.getBaseStats().getStrength());
        assertEquals(0, maki.getCombatStats().getJujutsuArtsSlots());
        assertTrue(maki.getAbilities().stream().anyMatch(ability ->
            "Cursed Tool Reinforcement".equals(ability.getName()) && ability.isActive()));
        var cursedSwordSlash = maki.getKnownMoves().stream()
            .filter(move -> "Cursed Sword Slash".equals(move.getName()))
            .findFirst().orElseThrow();
        var deflection = maki.getKnownMoves().stream()
            .filter(move -> "Cursed Tool Deflection".equals(move.getName()))
            .findFirst().orElseThrow();
        assertEquals(1, deflection.getPotency());
        assertEquals(3, deflection.getBlockDuration());
        assertEquals(1, deflection.getParryStaggerTicks());
        assertEquals(java.util.List.of("PHYSICAL", "CURSED_ENERGY"),
            deflection.getBlockAffectedTags());
        var keenObservation = maki.getKnownMoves().stream()
            .filter(move -> "Keen Observation".equals(move.getName()))
            .findFirst().orElseThrow();
        assertEquals(1, keenObservation.getMoveCap());
        var makiCombatant = new BattleCombatant(maki);
        assertEquals(135, makiCombatant.getEffectiveStats().getCombatAbility());
        assertEquals(108, makiCombatant.computeCurrentDefense(1));
        assertEquals(0, makiCombatant.computeMoveCeCost(cursedSwordSlash));
        var fearsomeMegumi = catalog.findCharacter("000004").orElseThrow();
        assertEquals("Ten Shadows", fearsomeMegumi.getInnateTechniqueName());
        assertEquals(45, fearsomeMegumi.getBaseStats().getVitality());
        assertEquals(18, fearsomeMegumi.getBaseStats().getStrength());
        assertEquals(30, fearsomeMegumi.getBaseStats().getCombatAbility());
        assertEquals(45, fearsomeMegumi.getBaseStats().getCursedTechniqueMastery());
        assertTrue(fearsomeMegumi.getKnownMoves().stream()
            .anyMatch(move -> "Summon Great Serpent".equals(move.getName())));
        var fearsomeCombatant = new BattleCombatant(fearsomeMegumi);
        assertEquals(2, fearsomeCombatant.getAbilityFlags().maxActiveSummons);
        var summonCosts = fearsomeMegumi.getKnownMoves().stream()
            .filter(move -> !com.jjktbf.model.combat.MoveAvailability
                .summonedDefinitionIds(move).isEmpty())
            .collect(java.util.stream.Collectors.toMap(
                com.jjktbf.model.move.Move::getName,
                com.jjktbf.model.move.Move::getBaseCeCost));
        assertEquals(35, summonCosts.get("Summon White Dog"));
        assertEquals(35, summonCosts.get("Summon Black Dog"));
        assertEquals(50, summonCosts.get("Summon Nue"));
        assertEquals(40, summonCosts.get("Summon Toad"));
        assertEquals(70, summonCosts.get("Summon Great Serpent"));
        var kyotoMegumi = catalog.findCharacter("000006").orElseThrow();
        assertEquals(72, kyotoMegumi.getBaseStats().getCursedTechniqueMastery());
        assertEquals(65, kyotoMegumi.getBaseStats().getCombatAbility());
        assertTrue(kyotoMegumi.getKnownMoves().stream()
            .anyMatch(move -> "Summon Max Elephant".equals(move.getName())));
        assertEquals(3, new BattleCombatant(kyotoMegumi).getAbilityFlags().maxActiveSummons);
        assertFalse(kyotoMegumi.getKnownMoves().stream()
            .anyMatch(move -> "Summon White Dog".equals(move.getName())));
        var whiteDog = catalog.findCharacter("000007").orElseThrow();
        assertEquals(com.jjktbf.model.character.CharacterType.SHIKIGAMI, whiteDog.getType());
        assertEquals(0.2, new BattleCombatant(whiteDog)
            .getAbilityFlags().summonCeUpkeepPerActiveTick, 0.000001);
        assertTrue(whiteDog.getKnownMoves().stream()
            .anyMatch(move -> "Desummon".equals(move.getName())));
        assertFalse(catalog.characterSummaries().stream()
            .anyMatch(summary -> "000007".equals(summary.characterId())));
        var inumaki = catalog.findCharacter("000015").orElseThrow();
        assertEquals("Cursed Speech", inumaki.getInnateTechniqueName());
        assertEquals(120, inumaki.getBaseStats().getCursedTechniqueMastery());
        assertEquals(6, inumaki.getKnownMoves().stream()
            .filter(move -> "Cursed Speech".equals(move.getRequiredTechniqueId()))
            .count());
        assertThrows(UnsupportedOperationException.class,
            () -> catalog.characterSummaries().clear());
    }
}
