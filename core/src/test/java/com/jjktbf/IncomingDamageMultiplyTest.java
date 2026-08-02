package com.jjktbf;

import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityApplicator;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the INCOMING_DAMAGE_MULTIPLY passive — a defender-side, tag-filtered
 * damage reduction (e.g. Maki's "Partial Resistance to Curses").
 */
class IncomingDamageMultiplyTest {

    @Test
    void defaultEffectIsAuthorableAndValid() {
        AbilityEffectData effect = AbilityEffectType.INCOMING_DAMAGE_MULTIPLY.createDefault();
        assertEquals("INCOMING_DAMAGE_MULTIPLY", effect.type);
        // A MOVE_SCOPE tag is optional (blank = applies to every move).
        assertNull(AbilityEffectType.INCOMING_DAMAGE_MULTIPLY.validationError(effect));
    }

    @Test
    void cursedEnergyDamageIsReducedButOtherDamageIsNot() {
        // 5% reduction against CURSED_ENERGY-tagged hits only.
        AbilityData ability = new AbilityData();
        ability.id = "000019";
        ability.name = "Partial Resistance to Curses";
        ability.category = "PASSIVE";
        ability.sourceType = "CHARACTER";
        AbilityEffectData effect = new AbilityEffectData();
        effect.type = AbilityEffectType.INCOMING_DAMAGE_MULTIPLY.name();
        effect.moveTag = MoveTag.CURSED_ENERGY.name();
        effect.doubleValue = 0.95;
        ability.effects = List.of(effect);

        AbilityApplicator.ApplicationResult result = AbilityApplicator.apply(
            new CharacterStats.Builder().build(), List.of(new Ability(ability)));

        Move cursedEnergyMove = move(MoveCategory.CURSED_ENERGY);
        Move physicalMove = move(MoveCategory.PHYSICAL);

        assertEquals(0.95, result.flags.incomingDamageMultiplierFor(cursedEnergyMove), 1e-9,
            "CURSED_ENERGY-tagged hits should be reduced by 5%.");
        assertEquals(1.0, result.flags.incomingDamageMultiplierFor(physicalMove), 1e-9,
            "Non-CE hits should be unaffected.");
    }

    private static Move move(MoveCategory category) {
        return new Move.Builder(category.name())
            .name(category.name())
            .category(category)
            .baseAccuracy(100)
            .hitComponents(List.of(new HitComponent(10, category, 0)))
            .build();
    }
}
