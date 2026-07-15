package com.jjktbf.model.character;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jjktbf.model.repo.BaseRepository;

import java.util.List;

/**
 * Persistent repository for ability definitions ({@code data/abilities/all_abilities.json}).
 *
 * ID scheme and behaviour are inherited from {@link BaseRepository}: 6-digit
 * zero-padded sequential ids, resequenced on delete.
 *
 * On first run (no file), seeds Six Eyes and Infinity as example abilities.
 */
public class AbilityRepository extends BaseRepository<AbilityData> {

    public AbilityRepository(String dataDirectory) {
        super(dataDirectory, "all_abilities.json");
    }

    @Override protected String idOf(AbilityData d)             { return d.id; }
    @Override protected void assignId(AbilityData d, String id) { d.id = id; }
    @Override protected String entityName()                     { return "ability"; }
    @Override protected TypeReference<List<AbilityData>> typeReference() {
        return new TypeReference<>() {};
    }

    @Override protected void seed() {
        // Six Eyes
        AbilityData sixEyes = new AbilityData();
        sixEyes.name        = "Six Eyes";
        sixEyes.flavourText = "A rare ocular Jujutsu passed down through the Gojo clan once every several "
            + "generations. The eyes perceive cursed energy with such clarity that virtually none is wasted "
            + "during technique activation. Their bearer is said to be born once in a blue moon.";
        sixEyes.mechanicText = "Unlocks the LIMITLESS innate technique. Sets CURSED_ENERGY_EFFICIENCY to "
            + "MAX (300). Reduces all CE costs to their MINIMUM. Grants +20 ACCURACY on all moves. "
            + "Grants +80 BONUS_POINTS to the point-buy budget.";
        sixEyes.category    = "PASSIVE";
        sixEyes.sourceType  = "CHARACTER";
        sixEyes.effects     = List.of(
            AbilityEffectData.unlockTechnique("Limitless"),
            AbilityEffectData.statSetMax("cursedEnergyEfficiency"),
            AbilityEffectData.ceCostToMinimum(null),
            AbilityEffectData.moveAccuracyAdd(null, 20),
            AbilityEffectData.statBonusPoints(80)
        );
        super.add(sixEyes);

        // Infinity (granted by Limitless technique)
        AbilityData infinity = new AbilityData();
        infinity.name        = "Infinity";
        infinity.flavourText = "The core application of Limitless — an invisible wall of slowed space "
            + "that surrounds the user at all times. Attacks approach but never arrive, "
            + "asymptotically closing the distance without ever reaching their target.";
        infinity.mechanicText = "Requires the LIMITLESS technique. Multiplies enemy ACCURACY "
            + "by 0.10 against all moves. Drains 15 CE before planning each round.";
        infinity.category    = "PASSIVE";
        infinity.sourceType  = "TECHNIQUE";
        infinity.sourceValue = "Limitless";
        infinity.effects = List.of(
            eff(AbilityEffectType.OPPONENT_ACCURACY_MULTIPLY, e -> e.doubleValue = 0.10),
            eff(AbilityEffectType.COST_CE_PER_ROUND, e -> e.intValue = 15)
        );
        super.add(infinity);
    }

    /** Inline builder helper for seeding — avoids verbose constructors. */
    @FunctionalInterface
    private interface Configurator { void configure(AbilityEffectData e); }

    private static AbilityEffectData eff(AbilityEffectType type, Configurator config) {
        AbilityEffectData e = new AbilityEffectData();
        e.type = type.name();
        config.configure(e);
        return e;
    }
}
